// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * The keyboard a focused field asks for: its HTML {@code inputmode}, as
 * {@link CefriumRenderHandler#onVirtualKeyboardRequested(CefriumTextInputMode)}
 * reports it. {@link #NONE} means no field is focused: hide the keyboard.
 */
public enum CefriumTextInputMode {
    DEFAULT(0), NONE(1), TEXT(2), TEL(3), URL(4), EMAIL(5), NUMERIC(6), DECIMAL(7), SEARCH(8);

    // The engine's value (cef_text_input_mode_t), for the deprecated int API.
    final int engine;

    CefriumTextInputMode(int engine) {
        this.engine = engine;
    }

    // An engine value this enum does not know shows the default keyboard.
    static CefriumTextInputMode fromEngine(int value) {
        for (CefriumTextInputMode m : values()) {
            if (m.engine == value) return m;
        }
        return DEFAULT;
    }
}
