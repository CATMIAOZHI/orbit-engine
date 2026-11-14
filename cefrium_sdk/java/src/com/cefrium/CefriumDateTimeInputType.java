// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** The kind of date or time input a picker is for. */
public enum CefriumDateTimeInputType {
    /** {@code <input type=date>}. */
    DATE(8),
    /** {@code <input type=datetime-local>}. */
    DATETIME_LOCAL(10),
    /** {@code <input type=month>}. */
    MONTH(11),
    /** {@code <input type=time>}. */
    TIME(12),
    /** {@code <input type=week>}. */
    WEEK(13);

    // The engine's value, for the deprecated int API.
    final int engine;

    CefriumDateTimeInputType(int engine) {
        this.engine = engine;
    }

    // Null for a value this enum does not know.
    static CefriumDateTimeInputType fromEngine(int value) {
        for (CefriumDateTimeInputType t : values()) {
            if (t.engine == value) return t;
        }
        return null;
    }
}
