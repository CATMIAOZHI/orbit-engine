// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Zero-config initializer for the Cefrium SDK.
 *
 * <p>Declared in the SDK manifest, the Android framework instantiates this
 * ContentProvider automatically at app launch — before any Activity — in the
 * browser (main) process. Its {@link #onCreate()} runs the per-process Chromium
 * plumbing and initializes the engine ({@link Cefrium#initialize(android.content.Context)}),
 * at the same startup phase the manual {@code Application.onCreate()} pattern used to.</p>
 *
 * <p>Initializing here (rather than lazily on first browser creation) is deliberate:
 * CEF's context initialization completes asynchronously on the UI message loop, so a
 * browser can only be created after the looper has spun past it. Doing init at app
 * launch leaves the whole startup-to-first-Activity window for that to happen;
 * initializing synchronously inside the first {@code createWithSurface()} would crash
 * (no looper iteration between init and browser creation).</p>
 *
 * <p>This is why a consumer needs no custom {@code Application} and no explicit
 * {@code Cefrium.initialize()} call. {@link Cefrium#initialize(android.content.Context)}
 * is a no-op in Chromium child processes, which self-initialize. The provider performs
 * no data operations — every query method below is an inert stub.</p>
 */
public final class CefriumInitProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        // getContext() is the application context in a provider; safe this early.
        // initialize() runs setupProcess() itself and is a no-op in child processes.
        Cefrium.initialize(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }
}
