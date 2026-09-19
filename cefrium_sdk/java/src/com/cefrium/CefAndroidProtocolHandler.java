// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.TypedValue;

import org.chromium.base.ContextUtils;
import org.chromium.base.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Java side of Cefrium's APK asset/resource URL loading (FEATURE-061). Opens an
 * InputStream for {@code file:///android_asset/...} (APK assets/) and
 * {@code file:///android_res/...} (APK res/) URLs from the HOST app's context.
 * Called from native via manual JNI (see android_asset_response_delegate.cc).
 * Adapted from android_webview's AndroidProtocolHandler.
 *
 * <p>Kept from R8 stripping by a consumer keep rule (referenced only from
 * native).</p>
 */
public final class CefAndroidProtocolHandler {
    private static final String TAG = "CefAssetHandler";

    private static final String FILE_SCHEME = "file";
    private static final String ASSET_PATH = "/android_asset/";
    private static final String RESOURCE_PATH = "/android_res/";

    private CefAndroidProtocolHandler() {}

    /** Called from native: open an InputStream for the given URL, or null. */
    public static InputStream open(String url) {
        Uri uri = verifyUrl(url);
        if (uri == null) {
            return null;
        }
        InputStream stream = openByScheme(uri);
        String last = uri.getLastPathSegment();
        if (stream != null && last != null && last.endsWith(".svgz")) {
            try {
                stream = new GZIPInputStream(stream);
            } catch (IOException e) {
                Log.e(TAG, "Error decompressing " + uri);
                return null;
            }
        }
        return stream;
    }

    private static InputStream openByScheme(Uri uri) {
        try {
            String scheme = uri.getScheme();
            if (FILE_SCHEME.equals(scheme)) {
                String path = uri.getPath();
                if (path == null) return null;
                if (path.startsWith(ASSET_PATH)) {
                    return openAsset(uri);
                } else if (path.startsWith(RESOURCE_PATH)) {
                    return openResource(uri);
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error opening stream: " + uri);
        }
        return null;
    }

    private static InputStream openAsset(Uri uri) {
        String path = uri.getPath().replaceFirst(ASSET_PATH, "");
        try {
            AssetManager assets = ContextUtils.getApplicationContext().getAssets();
            return assets.open(path, AssetManager.ACCESS_STREAMING);
        } catch (IOException e) {
            Log.e(TAG, "Unable to open asset: " + uri);
            return null;
        }
    }

    @SuppressWarnings("DiscouragedApi")
    private static InputStream openResource(Uri uri) {
        // Path form: /android_res/<asset_type>/<asset_name>.<ext>
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 3) {
            Log.e(TAG, "Incorrect resource path: " + uri);
            return null;
        }
        String assetType = segments.get(1);
        String assetName = segments.get(2).split("\\.")[0];
        try {
            Context ctx = ContextUtils.getApplicationContext();
            int id = ctx.getResources().getIdentifier(
                    assetName, assetType, ctx.getPackageName());
            if (id == 0) {
                Log.e(TAG, "Resource not found: " + uri);
                return null;
            }
            TypedValue value = new TypedValue();
            ctx.getResources().getValue(id, value, true);
            if (value.type != TypedValue.TYPE_STRING) {
                Log.e(TAG, "Resource not a file: " + uri);
                return null;
            }
            return ctx.getResources().openRawResource(id);
        } catch (Exception e) {
            Log.e(TAG, "Unable to open resource: " + uri);
            return null;
        }
    }

    /** Called from native: best-effort MIME type for the URL/stream, or null. */
    public static String getMimeType(InputStream stream, String url) {
        Uri uri = verifyUrl(url);
        if (uri == null) {
            return null;
        }
        try {
            String path = uri.getPath();
            if (path != null && path.startsWith(ASSET_PATH)) {
                String mime = URLConnection.guessContentTypeFromName(path);
                if (mime != null && !mime.isEmpty()) {
                    return mime;
                }
            }
        } catch (Exception ex) {
            // Fall through to stream sniffing.
        }
        try {
            return URLConnection.guessContentTypeFromStream(stream);
        } catch (IOException e) {
            return null;
        }
    }

    private static Uri verifyUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return null;
        }
        return uri;
    }
}
