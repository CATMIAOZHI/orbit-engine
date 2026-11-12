// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.TypedValue;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ready-made {@link CefriumContentServer}s for the app's assets, files and
 * resources, and the MIME types they use. Combine them under path prefixes
 * with {@link CefriumPathContentServer}.
 *
 * <p>Each serves {@code index.html} for a path that ends in {@code /}, gives
 * {@code 404} for anything missing, and refuses a path that leaves its
 * directory ({@code ..}).
 */
public final class CefriumContentServers {
    /**
     * The origin {@code androidx.webkit.WebViewAssetLoader} uses by default.
     * Register it to keep a migrating app's URLs, and so its pages' storage
     * origin, unchanged in form; the data itself does not move from WebView.
     */
    public static final String WEBVIEW_ASSET_LOADER_ORIGIN =
            "https://appassets.androidplatform.net";

    private CefriumContentServers() {}

    /**
     * Serves {@code assets/<directory>}: {@code /index.html} is
     * {@code assets/<directory>/index.html}. Pass {@code ""} for the whole
     * assets folder.
     *
     * <p>Range requests need the length, which Android gives only for assets
     * stored uncompressed: list the extensions in the app's
     * {@code androidResources { noCompress += [...] }} (media types are
     * uncompressed by default).
     */
    public static CefriumContentServer assets(Context context, String directory) {
        final AssetManager assets = context.getApplicationContext().getAssets();
        final String base = trimSlashes(directory);
        return request -> {
            String relative = safeRelativePath(request.getPath());
            if (relative == null) return null;
            String path = base.isEmpty() ? relative : base + "/" + relative;
            InputStream in;
            try {
                in = assets.open(path, AssetManager.ACCESS_STREAMING);
            } catch (FileNotFoundException e) {
                return null;
            }
            long length = -1;
            try (AssetFileDescriptor fd = assets.openFd(path)) {
                length = fd.getLength();
            } catch (IOException compressed) {
                // Stored compressed: no length, so no Range.
            }
            return new CefriumContentResponse(mimeTypeOf(path), in)
                    .setContentLength(length);
        };
    }

    /**
     * Serves a directory of files, e.g. {@code context.getFilesDir()} or a
     * downloaded game: {@code /a/b.png} is {@code <directory>/a/b.png}.
     */
    public static CefriumContentServer files(File directory) {
        final File root;
        try {
            root = directory.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("unreadable directory: " + directory, e);
        }
        return request -> {
            String relative = safeRelativePath(request.getPath());
            if (relative == null) return null;
            File file = new File(root, relative).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator)
                    || !file.isFile()) {
                return null;
            }
            return new CefriumContentResponse(mimeTypeOf(file.getName()),
                    new FileInputStream(file))
                    .setContentLength(file.length());
        };
    }

    /**
     * Serves the app's resources as {@code /<type>/<name>.<ext>}: for example
     * {@code /raw/intro.mp4} is {@code R.raw.intro}, {@code /drawable/logo.png}
     * {@code R.drawable.logo}. The counterpart of WebViewAssetLoader's
     * {@code ResourcesPathHandler}.
     */
    public static CefriumContentServer resources(Context context) {
        final Context app = context.getApplicationContext();
        return request -> {
            String relative = safeRelativePath(request.getPath());
            if (relative == null) return null;
            int slash = relative.indexOf('/');
            if (slash <= 0 || relative.indexOf('/', slash + 1) >= 0) return null;
            String type = relative.substring(0, slash);
            String file = relative.substring(slash + 1);
            int dot = file.lastIndexOf('.');
            String name = dot > 0 ? file.substring(0, dot) : file;
            Resources res = app.getResources();
            int id = res.getIdentifier(name, type, app.getPackageName());
            if (id == 0) return null;
            long length = -1;
            try (AssetFileDescriptor fd = res.openRawResourceFd(id)) {
                if (fd != null) length = fd.getLength();
            } catch (Resources.NotFoundException | IOException compressed) {
                // Compressed or not a file: no length.
            }
            InputStream in;
            try {
                in = res.openRawResource(id, new TypedValue());
            } catch (Resources.NotFoundException e) {
                return null;
            }
            return new CefriumContentResponse(mimeTypeOf(file), in).setContentLength(length);
        };
    }

    private static final Map<String, String> WEB_TYPES = new HashMap<>();
    static {
        // The web's types first: Android's table lacks or differs on some
        // (wasm, mjs, webmanifest), and a wrong type breaks the page
        // (WebAssembly.instantiateStreaming needs application/wasm, module
        // scripts a JavaScript type).
        String[][] types = {
            {"html", "text/html"}, {"htm", "text/html"},
            {"js", "text/javascript"}, {"mjs", "text/javascript"},
            {"css", "text/css"}, {"json", "application/json"},
            {"map", "application/json"}, {"webmanifest", "application/manifest+json"},
            {"wasm", "application/wasm"}, {"xml", "application/xml"},
            {"txt", "text/plain"}, {"csv", "text/csv"}, {"md", "text/markdown"},
            {"svg", "image/svg+xml"}, {"png", "image/png"}, {"jpg", "image/jpeg"},
            {"jpeg", "image/jpeg"}, {"gif", "image/gif"}, {"webp", "image/webp"},
            {"avif", "image/avif"}, {"ico", "image/x-icon"}, {"bmp", "image/bmp"},
            {"woff", "font/woff"}, {"woff2", "font/woff2"}, {"ttf", "font/ttf"},
            {"otf", "font/otf"}, {"mp4", "video/mp4"}, {"webm", "video/webm"},
            {"m4v", "video/mp4"}, {"mov", "video/quicktime"}, {"mp3", "audio/mpeg"},
            {"m4a", "audio/mp4"}, {"aac", "audio/aac"}, {"ogg", "audio/ogg"},
            {"oga", "audio/ogg"}, {"ogv", "video/ogg"}, {"opus", "audio/opus"},
            {"wav", "audio/wav"}, {"flac", "audio/flac"}, {"pdf", "application/pdf"},
            {"zip", "application/zip"}, {"bin", "application/octet-stream"},
            {"data", "application/octet-stream"}, {"pck", "application/octet-stream"},
            {"glb", "model/gltf-binary"}, {"gltf", "model/gltf+json"},
            {"ktx2", "image/ktx2"},
        };
        for (String[] t : types) WEB_TYPES.put(t[0], t[1]);
    }

    /**
     * The MIME type for a file name or path, by its extension:
     * {@code application/octet-stream} when unknown.
     */
    public static String mimeTypeOf(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = WEB_TYPES.get(ext);
        if (type == null) type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type != null ? type : "application/octet-stream";
    }

    // The request path without its leading slash, with index.html for a
    // directory; null when it would leave the served directory.
    static String safeRelativePath(String path) {
        StringBuilder out = new StringBuilder();
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (p.equals(".") || p.equals("..") || p.indexOf('\\') >= 0
                    || p.indexOf('\0') >= 0) {
                return null;
            }
            if (out.length() > 0) out.append('/');
            out.append(p);
        }
        if (path.isEmpty() || path.endsWith("/")) {
            if (out.length() > 0) out.append('/');
            out.append("index.html");
        }
        return out.toString();
    }

    private static String trimSlashes(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == '/') a++;
        while (b > a && s.charAt(b - 1) == '/') b--;
        return s.substring(a, b);
    }
}
