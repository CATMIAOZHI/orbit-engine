// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Java side of a content server request (FEATURE-090). The engine calls
 * {@link #start} when a page requests a registered origin, then {@link #read}
 * for each chunk; both answer later, from a pool thread, through the natives.
 * {@link #cancel} may come at any time.
 */
final class ContentServerHost {
    private static final String TAG = "CefriumContent";

    private static final AtomicInteger sThreads = new AtomicInteger();
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            16, 16, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "CefriumContent-" + sThreads.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
    static {
        POOL.allowCoreThreadTimeOut(true);
    }

    // What a skip the engine asks for does (see skip()).
    private static final int SKIP_FAIL = 0;
    private static final int SKIP_READ = 1;
    private static final int SKIP_IGNORE = 2;

    private static final class Active {
        volatile boolean cancelled;
        InputStream body;
        long remaining = -1;
        int skipMode = SKIP_FAIL;
    }

    // net::ERR_REQUEST_RANGE_NOT_SATISFIABLE.
    private static final long ERR_RANGE = -328;

    private static final Map<Long, Active> ACTIVE = new ConcurrentHashMap<>();

    private ContentServerHost() {}

    // Called by the engine, on its IO thread.
    static void start(long id, CefriumContentServer server, String method, String url,
            String[] headerPairs, byte[] body) {
        final Active active = new Active();
        ACTIVE.put(id, active);
        POOL.execute(() -> respond(id, active, server, method, url, headerPairs, body));
    }

    // Called by the engine, on its IO thread: the next chunk, up to max bytes.
    static void read(long id, int max) {
        POOL.execute(() -> {
            Active active = ACTIVE.get(id);
            if (active == null || active.cancelled || active.body == null) {
                finish(id, active);
                nativeOnData(id, null, active == null || active.cancelled ? -1 : 0);
                return;
            }
            int want = max;
            if (active.remaining >= 0 && active.remaining < want) {
                want = (int) active.remaining;
            }
            int n = -1;
            byte[] buf = null;
            if (want > 0) {
                buf = new byte[want];
                try {
                    n = active.body.read(buf, 0, want);
                } catch (IOException e) {
                    Log.w(TAG, "reading a response body failed", e);
                    finish(id, active);
                    nativeOnData(id, null, -1);
                    return;
                }
            }
            if (n <= 0) {
                finish(id, active);
                nativeOnData(id, null, 0);
                return;
            }
            if (active.remaining >= 0) active.remaining -= n;
            nativeOnData(id, buf, n);
        });
    }

    // Called by the engine, on its IO thread, before the first read of a
    // request with a single "bytes=<first>-" range: it skips <first> bytes
    // itself. Answers with the bytes skipped, or a net error.
    static void skip(long id, long n) {
        POOL.execute(() -> {
            Active active = ACTIVE.get(id);
            long result;
            if (active == null || active.cancelled) {
                result = ERR_RANGE;
            } else if (active.skipMode == SKIP_IGNORE || n == 0) {
                result = n;
            } else if (active.skipMode == SKIP_READ && active.body != null) {
                try {
                    skipFully(active.body, n);
                    result = n;
                } catch (IOException e) {
                    Log.w(TAG, "seeking a response body failed", e);
                    result = ERR_RANGE;
                }
            } else {
                result = ERR_RANGE;
            }
            nativeOnSkipped(id, result);
        });
    }

    // Called by the engine when the request goes away.
    static void cancel(long id) {
        Active active = ACTIVE.remove(id);
        if (active != null) {
            active.cancelled = true;
            POOL.execute(() -> closeQuietly(active.body));
        }
    }

    private static void finish(long id, Active active) {
        ACTIVE.remove(id);
        if (active != null) closeQuietly(active.body);
    }

    /** Runs |task| on the content pool, where a server or interceptor may block. */
    static void execute(Runnable task) {
        POOL.execute(task);
    }

    /** The request the engine described, as servers and interceptors see it. */
    static CefriumContentRequest requestOf(String method, String url, String[] headerPairs,
            byte[] body) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i], headerPairs[i + 1]);
        }
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        return new CefriumContentRequest(method, url,
                path == null || path.isEmpty() ? "/" : path, uri.getEncodedQuery(),
                headers, body);
    }

    private static void respond(long id, Active active, CefriumContentServer server,
            String method, String url, String[] headerPairs, byte[] body) {
        CefriumContentRequest request = requestOf(method, url, headerPairs, body);

        CefriumContentResponse response;
        try {
            response = server.serve(request);
            if (response == null) response = text(404, "Not Found");
        } catch (Throwable t) {
            Log.e(TAG, "content server failed for " + url, t);
            response = text(500, "Internal Server Error");
        }
        if (active.cancelled) {
            closeQuietly(response.getBody());
            return;
        }

        int status = response.getStatus();
        String reason = response.getReason();
        long length = response.getContentLength();
        InputStream in = response.getBody();
        Map<String, String> out = response.getHeaders();
        String range = request.getHeader("Range");
        boolean ranged = status == 200 && length >= 0 && range != null
                && (method.equals("GET") || method.equals("HEAD"));
        if (status == 200 && length >= 0 && !out.containsKey("Accept-Ranges")) {
            out.put("Accept-Ranges", "bytes");
        }
        // The engine skips to <first> itself for a single explicit range, by
        // calling skip(); any other range is skipped here. A request whose
        // status is not a success ignores the skip, as a server would ignore
        // Range on an error.
        active.skipMode = status >= 200 && status < 300 ? SKIP_FAIL : SKIP_IGNORE;
        if (ranged) {
            long[] span = parseRange(range, length);
            if (span == null) {
                closeQuietly(in);
                in = null;
                status = 416;
                reason = "Range Not Satisfiable";
                out.put("Content-Range", "bytes */" + length);
                length = 0;
                active.skipMode = SKIP_IGNORE;  // the 416 is the answer, not a net error
            } else {
                if (engineSkips(range)) {
                    active.skipMode = SKIP_READ;
                } else {
                    try {
                        skipFully(in, span[0]);
                    } catch (IOException e) {
                        Log.w(TAG, "seeking a response body failed", e);
                        closeQuietly(in);
                        respondError(id, active);
                        return;
                    }
                }
                status = 206;
                reason = "Partial Content";
                out.put("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + length);
                length = span[1] - span[0] + 1;
            }
        }
        if (method.equals("HEAD")) {
            closeQuietly(in);
            in = null;
            // The length a GET would have, with no body: a header, not the
            // engine's body length.
            if (length >= 0) out.put("Content-Length", Long.toString(length));
            length = -1;
        }
        active.body = in;
        active.remaining = in == null ? 0 : length;

        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> h : out.entrySet()) {
            pairs.add(h.getKey());
            pairs.add(h.getValue());
        }
        nativeOnResponse(id, status, reason, response.getMimeType(), response.getCharset(),
                pairs.toArray(new String[0]), length);
    }

    // Whether the engine will skip to the range start itself: one range with
    // an explicit first byte (net::HttpUtil::ParseRangeHeader, one range,
    // first_byte_position() >= 0).
    static boolean engineSkips(String header) {
        String h = header.trim();
        if (!h.startsWith("bytes=")) return false;
        String spec = h.substring(6).trim();
        if (spec.indexOf(',') >= 0) return false;
        int dash = spec.indexOf('-');
        return dash > 0;
    }

    private static void respondError(long id, Active active) {
        active.body = null;
        active.remaining = 0;
        nativeOnResponse(id, 500, "Internal Server Error", "text/plain", null,
                new String[0], 0);
    }

    // A single "bytes=" range as [first, last], or null when it cannot be met.
    // Several ranges are answered with the first: a multipart response is
    // rarely asked for by a browser and media players never do.
    static long[] parseRange(String header, long length) {
        String h = header.trim();
        if (!h.startsWith("bytes=")) return null;
        String spec = h.substring(6);
        int comma = spec.indexOf(',');
        if (comma >= 0) spec = spec.substring(0, comma);
        spec = spec.trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long first;
            long last;
            if (a.isEmpty()) {
                long suffix = Long.parseLong(b);
                if (suffix <= 0) return null;
                first = Math.max(0, length - suffix);
                last = length - 1;
            } else {
                first = Long.parseLong(a);
                last = b.isEmpty() ? length - 1 : Math.min(Long.parseLong(b), length - 1);
            }
            if (first < 0 || first >= length || last < first) return null;
            return new long[] {first, last};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                // skip() may stop early without being at the end; read instead.
                if (in.read() < 0) throw new IOException("body shorter than its length");
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static CefriumContentResponse text(int status, String reason) {
        byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
        return new CefriumContentResponse("text/plain", new ByteArrayInputStream(bytes))
                .setCharset("utf-8")
                .setContentLength(bytes.length)
                .setStatus(status, reason);
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing left to do with it.
        }
    }

    private static native void nativeOnResponse(long id, int status, String reason,
            String mimeType, String charset, String[] headerPairs, long length);

    // n > 0: data[0..n); 0: the end; -1: an error.
    private static native void nativeOnData(long id, byte[] data, int n);

    // The bytes skipped, or a net error (negative).
    private static native void nativeOnSkipped(long id, long result);
}
