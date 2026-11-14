// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** A message a page wrote to its console, like {@code android.webkit.ConsoleMessage}. */
@CefriumExperimental
public final class CefriumConsoleMessage {
    /** How severe the message is. */
    public enum Level { VERBOSE, INFO, WARNING, ERROR }

    private final Level mLevel;
    private final String mMessage;
    private final String mSourceId;
    private final int mLineNumber;

    CefriumConsoleMessage(Level level, String message, String sourceId, int lineNumber) {
        mLevel = level;
        mMessage = message;
        mSourceId = sourceId;
        mLineNumber = lineNumber;
    }

    /** {@code console.debug} is VERBOSE, {@code log} and {@code info} INFO. */
    public Level level() {
        return mLevel;
    }

    public String message() {
        return mMessage;
    }

    /** The script or page the message came from. */
    public String sourceId() {
        return mSourceId;
    }

    public int lineNumber() {
        return mLineNumber;
    }
}
