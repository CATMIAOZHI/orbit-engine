// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Loopback HTTPS server with a self-signed certificate, for hermetic instrumented
 * tests of certificate-error handling. Chromium validates the TLS cert even for
 * 127.0.0.1, so a self-signed cert triggers a cert error (OnCertificateError) --
 * no external bad-cert host (badssl.com) needed; a release gate must be hermetic.
 *
 * Load the bundled PKCS12 keystore from the androidTest assets and pass its
 * stream. Serves a fixed HTML body over TLS for every request. try-with-resources.
 */
public final class LocalHttpsServer implements AutoCloseable {

    private final SSLServerSocket server;
    private final int port;
    private volatile boolean running = true;

    public LocalHttpsServer(InputStream keystoreP12, char[] password, String html)
            throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(keystoreP12, password);
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        server = (SSLServerSocket) ctx.getServerSocketFactory()
                .createServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();

        final byte[] body = html.getBytes(StandardCharsets.UTF_8);
        final byte[] head = ("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        Thread t = new Thread(() -> {
            while (running) {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    s.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            s.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) { /* drain */ }
                    OutputStream out = s.getOutputStream();
                    out.write(head);
                    out.write(body);
                    out.flush();
                } catch (Exception ignored) {
                    // accept() throws on close(); a failed handshake just drops.
                }
            }
        }, "cefrium-test-httpsd");
        t.setDaemon(true);
        t.start();
    }

    /** URL of the served page, e.g. https://127.0.0.1:45123/ (self-signed cert). */
    public String url() {
        return "https://127.0.0.1:" + port + "/";
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
