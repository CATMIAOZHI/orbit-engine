// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** Which JavaScript dialog a page opened. */
public enum CefriumJsDialogType {
    /** {@code alert()}: one button. */
    ALERT,
    /** {@code confirm()}: OK or cancel. */
    CONFIRM,
    /** {@code prompt()}: a text field, OK or cancel. */
    PROMPT
}
