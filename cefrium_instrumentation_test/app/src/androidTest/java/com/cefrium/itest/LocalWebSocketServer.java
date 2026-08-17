package com.cefrium.itest;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tiny in-process loopback WebSocket echo server (RFC 6455) for hermetic
 * instrumented tests, on 127.0.0.1:&lt;ephemeral&gt;. Completes the upgrade
 * handshake, then echoes every text frame back unmasked. Single connection is
 * enough for the test; extra connections are handled too.
 *
 * Why this exists: it backs the WebSocket regression guard (cef-android issue #1).
 * Chromium 151 enabled Local Network Access checks for WebSockets by default; a
 * page in a more-public address space connecting to this loopback server would
 * stall on an LNA permission decision that Cefrium has no UI to resolve. The guard
 * loads a PUBLIC-origin page (loadHtmlWithBaseUrl) that connects here, so the test
 * fails if LNA (or anything) blocks the handshake. Hermetic -- never touches an
 * external host. Use with try-with-resources.
 */
public final class LocalWebSocketServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final ServerSocket server;
    private final int port;
    private volatile boolean running = true;
    // Fires when the server has completed a WS handshake and received the first
    // client frame -- i.e. the WebSocket actually connected and data flowed. This
    // is the LNA guard's signal (server-side, so it does not depend on any JS
    // bridge): if LNA blocks the handshake this never fires.
    private final CountDownLatch messageReceived = new CountDownLatch(1);

    public LocalWebSocketServer() throws Exception {
        server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort();
        Thread t = new Thread(this::acceptLoop, "cefrium-test-wsd");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread c = new Thread(() -> serve(s), "cefrium-test-ws-conn");
                c.setDaemon(true);
                c.start();
            } catch (Exception ignored) {
                // accept() throws on close(); exit the loop.
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();

            // --- Handshake: read headers, find Sec-WebSocket-Key ---
            StringBuilder req = new StringBuilder();
            int prev = -1, b;
            while ((b = in.read()) != -1) {
                req.append((char) b);
                if (prev == '\r' && b == '\n'
                        && req.length() >= 4
                        && req.charAt(req.length() - 3) == '\n') {
                    break; // end of headers (\r\n\r\n)
                }
                prev = b;
            }
            String key = null;
            for (String line : req.toString().split("\r\n")) {
                int idx = line.indexOf(':');
                if (idx > 0 && line.substring(0, idx).trim()
                        .equalsIgnoreCase("Sec-WebSocket-Key")) {
                    key = line.substring(idx + 1).trim();
                }
            }
            if (key == null) return;
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] accept = sha1.digest((key + GUID).getBytes(StandardCharsets.UTF_8));
            String acceptB64 = Base64.getEncoder().encodeToString(accept);
            String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptB64 + "\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // --- Frame loop: read masked client frames, echo unmasked ---
            while (running) {
                int b0 = in.read();
                if (b0 == -1) break;
                int opcode = b0 & 0x0F;
                int b1 = in.read();
                if (b1 == -1) break;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                byte[] mask = new byte[4];
                if (masked) readFully(in, mask, 4);
                byte[] payload = new byte[(int) len];
                readFully(in, payload, (int) len);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
                }
                if (opcode == 0x8) { // close
                    out.write(new byte[]{(byte) 0x88, 0x00});
                    out.flush();
                    break;
                }
                // The handshake completed and a client frame arrived: the WS is
                // established and data is flowing. Signal the guard.
                messageReceived.countDown();
                // Echo back a server (unmasked) text frame with the same payload.
                writeTextFrame(out, payload);
            }
        } catch (Exception ignored) {
            // connection errors just drop the socket.
        }
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws Exception {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) throw new Exception("eof");
            off += r;
        }
    }

    private static void writeTextFrame(OutputStream out, byte[] payload) throws Exception {
        int n = payload.length;
        if (n < 126) {
            out.write(new byte[]{(byte) 0x81, (byte) n});
        } else if (n < 65536) {
            out.write(new byte[]{(byte) 0x81, (byte) 126, (byte) (n >> 8), (byte) n});
        } else {
            byte[] h = new byte[10];
            h[0] = (byte) 0x81; h[1] = (byte) 127;
            for (int i = 0; i < 8; i++) h[9 - i] = (byte) (n >>> (8 * i));
            out.write(h);
        }
        out.write(payload);
        out.flush();
    }

    /** ws:// URL of the echo endpoint, e.g. ws://127.0.0.1:45123/ */
    public String url() {
        return "ws://127.0.0.1:" + port + "/";
    }

    /**
     * Blocks until a client WebSocket has connected and sent a frame, or the
     * timeout elapses. Returns true if a frame was received (the handshake
     * completed -- LNA did not block it).
     */
    public boolean awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
        return messageReceived.await(timeout, unit);
    }

    @Override
    public void close() {
        running = false;
        try { server.close(); } catch (Exception ignored) {}
    }
}
