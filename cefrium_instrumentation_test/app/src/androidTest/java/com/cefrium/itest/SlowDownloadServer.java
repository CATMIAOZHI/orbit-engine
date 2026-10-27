// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loopback server for download tests: {@code /} is a page, {@code /file} a
 * file of {@link #size()} bytes sent slowly enough to pause or interrupt, with
 * an ETag and {@code Range} support so Chromium can resume it. Records the
 * first byte each request for the file asked for, so a test can tell a resume
 * from a restart. Byte {@code i} of the file is {@link #byteAt}{@code (i)}.
 */
public final class SlowDownloadServer implements AutoCloseable {
    private static final int CHUNK = 32 * 1024;

    private final ServerSocket server;
    private final int size;
    private final int delayMs;
    private final String name;
    private final String page;
    private final List<Long> rangeStarts = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    /**
     * @param name     the file name the server suggests.
     * @param size     bytes in the file.
     * @param delayMs  pause after each 32 KiB.
     * @param page     the HTML served at every other path.
     */
    public SlowDownloadServer(String name, int size, int delayMs, String page) throws Exception {
        this.name = name;
        this.size = size;
        this.delayMs = delayMs;
        this.page = page;
        server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = server.accept();
                    Thread c = new Thread(() -> serve(s), "cefrium-test-slow-conn");
                    c.setDaemon(true);
                    c.start();
                } catch (Exception ignored) {
                    // accept() throws on close().
                }
            }
        }, "cefrium-test-slow");
        t.setDaemon(true);
        t.start();
    }

    public static byte byteAt(long i) {
        return (byte) (i % 251);
    }

    public int size() {
        return size;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/";
    }

    public String fileUrl() {
        return url() + "file";
    }

    /** The first byte asked for by each request for the file, in order. */
    public List<Long> rangeStarts() {
        return rangeStarts;
    }

    private void serve(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            long start = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("range: bytes=")) {
                    String spec = line.substring(13).trim();
                    start = Long.parseLong(spec.substring(0, spec.indexOf('-')));
                }
            }
            OutputStream out = socket.getOutputStream();
            boolean file = requestLine != null && requestLine.split(" ").length > 1
                    && requestLine.split(" ")[1].startsWith("/file");
            if (!file) {
                byte[] body = page.getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.write(body);
                out.flush();
                return;
            }
            rangeStarts.add(start);
            String head = (start > 0 ? "HTTP/1.1 206 Partial Content\r\n"
                            + "Content-Range: bytes " + start + "-" + (size - 1) + "/" + size
                            + "\r\n"
                            : "HTTP/1.1 200 OK\r\n")
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Disposition: attachment; filename=\"" + name + "\"\r\n"
                    + "Content-Length: " + (size - start) + "\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "ETag: \"cefrium-slow-" + size + "\"\r\n"
                    + "Last-Modified: Mon, 26 Oct 2026 00:00:00 GMT\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            byte[] chunk = new byte[CHUNK];
            for (long pos = start; pos < size && running; ) {
                int n = (int) Math.min(CHUNK, size - pos);
                for (int i = 0; i < n; i++) chunk[i] = byteAt(pos + i);
                out.write(chunk, 0, n);
                out.flush();
                pos += n;
                Thread.sleep(delayMs);
            }
        } catch (Exception ignored) {
            // The client went away (pause, cancel, close).
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (Exception ignored) {
        }
    }
}
