// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Handles the file-chooser dialog for {@code <input type=file>} (WebView
 * {@code WebChromeClient.onShowFileChooser}), mirroring CEF's
 * {@code CefDialogHandler}. Register with
 * {@link CefriumBrowser#setDialogHandler(CefriumDialogHandler)} to show a
 * chooser of the app's own.
 *
 * <p>Without a handler, a surface browser shows the engine's chooser, as
 * Chrome does (files, camera, photo picker, filtered by the input's accept
 * list), and an off-screen browser shows the system picker on its dialog
 * activity ({@link CefriumBrowser#setDialogActivity}), or cancels without
 * one. Both get the result through an AndroidX activity's result registry; a
 * plain {@code Activity} must forward {@code onActivityResult} to
 * {@link CefriumBrowser#onActivityResult}.
 *
 * <p>Delivered on the Android main thread so you can launch an Intent directly.
 */
public interface CefriumDialogHandler {
    /** File dialog mode: open a single file. */
    int MODE_OPEN = 0;
    /** File dialog mode: open multiple files. */
    int MODE_OPEN_MULTIPLE = 1;
    /** File dialog mode: open a folder. */
    int MODE_OPEN_FOLDER = 2;
    /** File dialog mode: save a file. */
    int MODE_SAVE = 3;

    /** Supplies the selected file path(s)/URI(s), or cancels. */
    interface FileDialogCallback {
        /** Continue with the chosen paths/URIs (empty cancels). */
        void select(String[] paths);
        /** Cancel the file selection. */
        void cancel();
    }

    /**
     * A file input asked to choose file(s). Launch a picker (e.g.
     * {@code ACTION_GET_CONTENT}) and deliver the result through
     * {@code callback}. The mode's low bits are the CEF file-dialog mode; use
     * {@link #MODE_OPEN} etc. (mask with 0xF).
     *
     * @param mode          CEF file-dialog mode/flags.
     * @param title         Suggested dialog title (may be empty).
     * @param acceptFilters MIME types / extensions the input accepts.
     * @param callback      Result callback.
     */
    void onFileDialog(int mode, String title, String[] acceptFilters,
                      FileDialogCallback callback);
}
