// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends each request to the server registered for the longest prefix of its
 * path, with the prefix removed: under {@code /assets/},
 * {@code /assets/img/a.png} reaches that server as {@code /img/a.png}. The
 * counterpart of {@code WebViewAssetLoader.Builder.addPathHandler}:
 *
 * <pre>{@code
 * // WebView
 * WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
 *         .addPathHandler("/assets/", new AssetsPathHandler(context))
 *         .addPathHandler("/res/", new ResourcesPathHandler(context))
 *         .build();
 *
 * // Cefrium
 * CefriumProfile.getDefault().registerContentServer(
 *         CefriumContentServers.WEBVIEW_ASSET_LOADER_ORIGIN,
 *         new CefriumPathContentServer.Builder()
 *                 .addPath("/assets/", CefriumContentServers.assets(context, ""))
 *                 .addPath("/res/", CefriumContentServers.resources(context))
 *                 .build());
 * }</pre>
 */
public final class CefriumPathContentServer implements CefriumContentServer {
    private final List<String> mPrefixes;
    private final List<CefriumContentServer> mServers;

    private CefriumPathContentServer(Builder builder) {
        mPrefixes = new ArrayList<>(builder.mPrefixes);
        mServers = new ArrayList<>(builder.mServers);
    }

    @Override
    public CefriumContentResponse serve(CefriumContentRequest request) throws IOException {
        String path = request.getPath();
        int best = -1;
        for (int i = 0; i < mPrefixes.size(); i++) {
            String prefix = mPrefixes.get(i);
            if (path.startsWith(prefix)
                    && (best < 0 || prefix.length() > mPrefixes.get(best).length())) {
                best = i;
            }
        }
        if (best < 0) return null;
        String rest = path.substring(mPrefixes.get(best).length() - 1);
        return mServers.get(best).serve(request.withPath(rest));
    }

    /** Builds a {@link CefriumPathContentServer}. */
    public static final class Builder {
        private final List<String> mPrefixes = new ArrayList<>();
        private final List<CefriumContentServer> mServers = new ArrayList<>();

        /**
         * Serves the paths under a prefix.
         *
         * @param prefix Starts and ends with {@code /}; {@code /} alone serves
         *     every path no longer prefix takes.
         * @param server The server for those paths.
         */
        public Builder addPath(String prefix, CefriumContentServer server) {
            if (prefix == null || !prefix.startsWith("/") || !prefix.endsWith("/")) {
                throw new IllegalArgumentException(
                        "a path prefix starts and ends with '/': " + prefix);
            }
            if (server == null) throw new IllegalArgumentException("server is null");
            if (mPrefixes.contains(prefix)) {
                throw new IllegalArgumentException("prefix already added: " + prefix);
            }
            mPrefixes.add(prefix);
            mServers.add(server);
            return this;
        }

        public CefriumPathContentServer build() {
            return new CefriumPathContentServer(this);
        }
    }
}
