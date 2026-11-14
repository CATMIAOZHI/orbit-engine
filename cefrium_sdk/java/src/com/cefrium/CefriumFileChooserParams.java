// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.content.Intent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What a file input asks for, as {@link CefriumChromeClient#onShowFileChooser}
 * sees it. Like {@code WebChromeClient.FileChooserParams}.
 */
@CefriumExperimental
public final class CefriumFileChooserParams {
    /** What the page wants chosen. */
    public enum Mode {
        /** One file. */
        OPEN,
        /** One or more files ({@code multiple}). */
        OPEN_MULTIPLE,
        /** A folder ({@code webkitdirectory}). */
        OPEN_FOLDER,
        /** Where to save a file ({@code showSaveFilePicker}). */
        SAVE
    }

    private final Mode mMode;
    private final String mTitle;
    private final List<String> mAcceptTypes;

    CefriumFileChooserParams(Mode mode, String title, String[] acceptTypes) {
        mMode = mode;
        mTitle = title == null ? "" : title;
        mAcceptTypes = Collections.unmodifiableList(
                Arrays.asList(acceptTypes == null ? new String[0] : acceptTypes));
    }

    public Mode getMode() {
        return mMode;
    }

    /** A title the page suggests; usually empty. */
    public String getTitle() {
        return mTitle;
    }

    /**
     * The input's {@code accept} list: MIME types ({@code image/*}) and
     * extensions ({@code .pdf}). Empty accepts any file. Unmodifiable.
     */
    public List<String> getAcceptTypes() {
        return mAcceptTypes;
    }

    /**
     * An intent for the system picker that fits these parameters, as the SDK's
     * own off-screen chooser uses. Start it and pass the result to
     * {@link #parseResult}.
     */
    public Intent createIntent() {
        return DefaultFileChooser.intentFor(mMode.ordinal(), mTitle,
                mAcceptTypes.toArray(new String[0]));
    }

    /**
     * The URIs a picker started with {@link #createIntent} returned, for
     * {@link CefriumFileChooserCallback#select}; empty when cancelled.
     */
    public static String[] parseResult(int resultCode, Intent data) {
        return DefaultFileChooser.urisOf(resultCode, data);
    }
}
