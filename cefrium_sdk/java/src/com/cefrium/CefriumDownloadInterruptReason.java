// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** Why a download stopped before it completed. */
@CefriumExperimental
public enum CefriumDownloadInterruptReason {
    /** It did not stop. */
    NONE,
    /** Writing the file failed: no space, no access, blocked, ... */
    FILE,
    /** The network failed: disconnected, timed out, ... */
    NETWORK,
    /** The server refused or failed: forbidden, unauthorized, bad content, ... */
    SERVER,
    /** The user or the app cancelled it, or the browser shut down. */
    USER,
    /** Anything else. */
    OTHER
}
