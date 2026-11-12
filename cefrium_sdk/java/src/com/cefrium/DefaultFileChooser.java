// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.ActivityResultRegistryOwner;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The file chooser of an off-screen browser with no {@link CefriumDialogHandler},
 * on its dialog activity ({@link CefriumBrowser#setDialogActivity}). A surface
 * browser uses the engine's own chooser instead, as Chrome's. Results are
 * content URIs.
 */
final class DefaultFileChooser {
    /** Gets the chosen URIs; empty when the user cancelled. */
    interface Result {
        void deliver(String[] uris);
    }

    /** The request code used without a result registry; see onActivityResult. */
    static final int REQUEST_CODE = 0x0CEF;

    private static final AtomicInteger sKeys = new AtomicInteger();

    private DefaultFileChooser() {}

    /**
     * Shows the system picker for a file input.
     *
     * @return false if it could not be shown (the caller cancels).
     */
    static boolean show(Activity activity, int mode, String title, String[] acceptFilters,
            Result result) {
        Intent intent = intentFor(mode & 0xF, title, acceptFilters);
        ActivityResultRegistry registry = activity instanceof ActivityResultRegistryOwner
                ? ((ActivityResultRegistryOwner) activity).getActivityResultRegistry()
                : null;
        try {
            if (registry == null) {
                activity.startActivityForResult(intent, REQUEST_CODE);
                return true;
            }
            @SuppressWarnings("unchecked")
            ActivityResultLauncher<Intent>[] launcher = new ActivityResultLauncher[1];
            launcher[0] = registry.register("com.cefrium.file." + sKeys.incrementAndGet(),
                    new ActivityResultContracts.StartActivityForResult(), r -> {
                        launcher[0].unregister();
                        result.deliver(urisOf(r.getResultCode(), r.getData()));
                    });
            try {
                launcher[0].launch(intent);
            } catch (ActivityNotFoundException e) {
                launcher[0].unregister();
                return false;
            }
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    static Intent intentFor(int mode, String title, String[] acceptFilters) {
        if (mode == CefriumDialogHandler.MODE_OPEN_FOLDER) {
            return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        String[] mimes = mimeTypes(acceptFilters);
        if (mode == CefriumDialogHandler.MODE_SAVE) {
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            save.addCategory(Intent.CATEGORY_OPENABLE);
            save.setType(mimes.length == 1 ? mimes[0] : "*/*");
            if (title != null && !title.isEmpty()) save.putExtra(Intent.EXTRA_TITLE, title);
            return save;
        }
        Intent open = new Intent(Intent.ACTION_GET_CONTENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType("*/*");
        if (mimes.length > 0) open.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        if (mode == CefriumDialogHandler.MODE_OPEN_MULTIPLE) {
            open.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        return open;
    }

    // The input's accept list as MIME types; none (every file) when any entry
    // is unknown or generic, so the user is never left unable to pick.
    static String[] mimeTypes(String[] acceptFilters) {
        Set<String> out = new LinkedHashSet<>();
        if (acceptFilters == null) return new String[0];
        for (String f : acceptFilters) {
            if (f == null) continue;
            String a = f.trim().toLowerCase(java.util.Locale.ROOT);
            if (a.isEmpty()) continue;
            String mime = a.startsWith(".") ? CefriumContentServers.mimeTypeOf("x" + a) : a;
            if (mime.equals("*/*") || mime.equals("application/octet-stream")
                    || mime.indexOf('/') < 0) {
                return new String[0];
            }
            out.add(mime);
        }
        return out.toArray(new String[0]);
    }

    static String[] urisOf(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return new String[0];
        List<String> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u.toString());
            }
        }
        if (uris.isEmpty() && data.getData() != null) uris.add(data.getData().toString());
        return uris.toArray(new String[0]);
    }
}
