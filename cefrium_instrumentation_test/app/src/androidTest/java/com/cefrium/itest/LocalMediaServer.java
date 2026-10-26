package com.cefrium.itest;

import android.content.res.AssetManager;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Loopback HTTP server for media tests: serves one HTML page, and the test
 * APK's .mp4/.webm/.mp3 assets by path with byte ranges, as a media element
 * requests them. Hermetic like {@link LocalHttpServer}.
 */
public final class LocalMediaServer implements AutoCloseable {
    private final ServerSocket mServer;
    private volatile boolean mRunning = true;

    public LocalMediaServer(String html) throws Exception {
        mServer = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        Thread t = new Thread(() -> {
            while (mRunning) {
                try {
                    Socket s = mServer.accept();
                    new Thread(() -> serve(s, html, assets)).start();
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void serve(Socket s, String html, AssetManager assets) {
        try (Socket sock = s) {
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String req = in.readLine();
            String range = null, line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
            }
            String path = req == null ? "/" : req.split(" ")[1];
            byte[] body;
            String type;
            if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mp3")) {
                try (InputStream a = assets.open(path.substring(1))) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = a.read(buf)) > 0) o.write(buf, 0, n);
                    body = o.toByteArray();
                }
                type = path.endsWith(".mp4") ? "video/mp4" : path.endsWith(".webm") ? "video/webm" : "audio/mpeg";
            } else {
                body = html.getBytes(StandardCharsets.UTF_8);
                type = "text/html";
            }
            int start = 0, end = body.length - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=") && !type.equals("text/html")) {
                String[] p = range.substring(6).split("-", -1);
                start = Integer.parseInt(p[0]);
                if (p.length > 1 && !p[1].isEmpty()) end = Math.min(end, Integer.parseInt(p[1]));
                partial = true;
            }
            OutputStream out = sock.getOutputStream();
            String head = (partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                    + "Content-Type: " + type + "\r\nAccept-Ranges: bytes\r\n"
                    + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + body.length + "\r\n" : "")
                    + "Content-Length: " + (end - start + 1) + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body, start, end - start + 1);
            out.flush();
        } catch (Exception ignored) {
        }
    }

    /** URL of the page, e.g. http://127.0.0.1:45123/ */
    public String url() {
        return "http://127.0.0.1:" + mServer.getLocalPort() + "/";
    }

    @Override
    public void close() {
        mRunning = false;
        try {
            mServer.close();
        } catch (Exception ignored) {
        }
    }
}
