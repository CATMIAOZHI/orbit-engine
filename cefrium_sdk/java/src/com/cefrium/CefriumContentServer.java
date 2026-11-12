// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers the requests for an https origin the app owns, from Java: the
 * app's assets, files, or bytes it generates. Register one with
 * {@link CefriumProfile#registerContentServer}. No socket is opened, so no
 * other app can reach the content, and the origin is the same on every
 * launch: localStorage, IndexedDB, cookies and service workers keep their
 * data.
 *
 * <p>The origin is a secure context, as any https origin, so the APIs that
 * need one work (service workers, {@code crypto.subtle}, clipboard). This is
 * the Cefrium counterpart of {@code androidx.webkit.WebViewAssetLoader}; see
 * {@link CefriumPathContentServer} for its path handlers.
 *
 * <p>{@link #serve} runs on a background thread, never the main thread, and
 * may block (a database, decryption, the network). Several requests can run
 * at once, so an implementation must be thread-safe.
 *
 * <p>Range requests (media, large assets) and HEAD are answered for the
 * server: a response with a known length ({@link CefriumContentResponse#setContentLength})
 * gets a {@code 206 Partial Content} with the requested bytes, skipped from
 * its stream.
 */
@FunctionalInterface
public interface CefriumContentServer {
    /**
     * Answers a request.
     *
     * @param request The request, its path relative to the server.
     * @return The response, or {@code null} for {@code 404 Not Found}.
     * @throws IOException If the content cannot be read; the page gets
     *     {@code 500 Internal Server Error}.
     */
    CefriumContentResponse serve(CefriumContentRequest request) throws IOException;

    /**
     * This server, with headers added to each of its responses, unless the
     * response sets the header itself. For example, to make the pages
     * cross-origin isolated, as WebAssembly threads and
     * {@code SharedArrayBuffer} need (Unity and Godot web exports):
     *
     * <pre>{@code
     * server.withResponseHeaders(Map.of(
     *         "Cross-Origin-Opener-Policy", "same-origin",
     *         "Cross-Origin-Embedder-Policy", "require-corp"));
     * }</pre>
     *
     * @param headers Header names and values.
     * @return A server that answers as this one, with the headers.
     */
    default CefriumContentServer withResponseHeaders(Map<String, String> headers) {
        final Map<String, String> added =
                Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        final CefriumContentServer inner = this;
        return request -> {
            CefriumContentResponse response = inner.serve(request);
            if (response != null) {
                for (Map.Entry<String, String> h : added.entrySet()) {
                    if (response.getHeader(h.getKey()) == null) {
                        response.setHeader(h.getKey(), h.getValue());
                    }
                }
            }
            return response;
        };
    }
}
