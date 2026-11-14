// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** Why a page's renderer process ended. */
public enum CefriumRendererTerminationStatus {
    /** Ended abnormally, reason not otherwise known. */
    ABNORMAL,
    /** Killed, for example by the system's low-memory killer. */
    KILLED,
    /** Crashed, when the system could tell (see {@link #OOM}). */
    CRASHED,
    /**
     * Ran out of memory, or ended while the page was in use. Android cannot
     * tell a low-memory kill of a visible page from a crash, so a crash of the
     * page on screen is usually reported here (measured: a {@code chrome://crash}
     * load gives OOM on a phone).
     */
    OOM,
    /** Any other reason (for example it could not start). */
    UNKNOWN
}
