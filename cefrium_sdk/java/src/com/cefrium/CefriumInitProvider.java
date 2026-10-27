// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Zero-config setup for the Cefrium SDK.
 *
 * <p>Declared in the SDK manifest, so Android creates it at app launch in the
 * main process, before any Activity. Its {@link #onCreate()} runs only the
 * per-process plumbing ({@link Cefrium#setupProcess(android.content.Context)}),
 * which costs milliseconds. The engine starts the first time the app uses it,
 * so a process started for something else (a push message, a job, a
 * broadcast) does not load it.</p>
 *
 * <p>The plumbing has to run here, before the first Activity: Chromium tracks
 * activities from {@code ApplicationStatus}, which misses one created before it
 * is initialized. The provider performs no data operations; every query method
 * below is an inert stub.</p>
 */
public final class CefriumInitProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        // A no-op in Chromium child processes, which set themselves up.
        Cefrium.setupProcess(getContext());
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
