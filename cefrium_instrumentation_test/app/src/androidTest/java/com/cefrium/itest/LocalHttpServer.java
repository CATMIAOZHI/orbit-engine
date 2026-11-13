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
 * Tiny in-process loopback HTTP server for hermetic instrumented tests. Serves a
 * fixed HTML body (charset utf-8) for every request on 127.0.0.1:&lt;ephemeral&gt;.
 *
 * Why: a release gate must be hermetic -- it must never fail because an external
 * site (httpbin.org, cefrium.com, example.com, ...) was down or unreachable from
 * the test device. Chromium treats http://127.0.0.1 as a SECURE context (a
 * potentially-trustworthy origin), so this also works for features gated on a
 * secure origin: getUserMedia, geolocation, notifications. Distinct ports give
 * distinct origins (for per-origin tests). Use with try-with-resources.
 */
public final class LocalHttpServer implements AutoCloseable {

    private final ServerSocket server;
    private final int port;
    private volatile boolean running = true;

    /** Each request seen, as "<request target> <User-Agent>", in order. */
    public final List<String> requests = new CopyOnWriteArrayList<>();

    public LocalHttpServer(String html) throws Exception {
        this(html, null, null);
    }

    /**
     * Also serves {@code script} as JavaScript at {@code scriptPath} (e.g.
     * {@code "/sw.js"}), for service workers; every other path gets the HTML.
     */
    public LocalHttpServer(String html, String scriptPath, String script) throws Exception {
        server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        final byte[] body = html.getBytes(StandardCharsets.UTF_8);
        final byte[] head = response("text/html; charset=utf-8", body.length);
        final byte[] jsBody = script == null ? null : script.getBytes(StandardCharsets.UTF_8);
        final byte[] jsHead = script == null
                ? null : response("application/javascript; charset=utf-8", jsBody.length);
        Thread t = new Thread(() -> {
            while (running) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            s.getInputStream(), StandardCharsets.UTF_8));
                    String requestLine = in.readLine();
                    String line;
                    String ua = "";
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "User-Agent:", 0, 11)) {
                            ua = line.substring(11).trim();
                        }
                    }
                    if (requestLine != null && requestLine.split(" ").length > 1) {
                        requests.add(requestLine.split(" ")[1] + " " + ua);
                    }
                    boolean js = jsBody != null && requestLine != null
                            && requestLine.split(" ").length > 1
                            && requestLine.split(" ")[1].split("\\?")[0].equals(scriptPath);
                    OutputStream out = s.getOutputStream();
                    out.write(js ? jsHead : head);
                    out.write(js ? jsBody : body);
                    out.flush();
                } catch (Exception ignored) {
                    // accept() throws on close(); a partial request just drops.
                }
            }
        }, "cefrium-test-httpd");
        t.setDaemon(true);
        t.start();
    }

    private static byte[] response(String contentType, int length) {
        return ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** URL of the served page on localhost, a different host from 127.0.0.1. */
    public String localhostUrl() {
        return "http://localhost:" + port + "/";
    }

    /** URL of the served page, e.g. http://127.0.0.1:45123/ */
    public String url() {
        return "http://127.0.0.1:" + port + "/";
    }

    /** Origin of the served page, e.g. http://127.0.0.1:45123 (no trailing slash). */
    public String origin() {
        return "http://127.0.0.1:" + port;
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
