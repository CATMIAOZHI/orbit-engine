// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** A stored decision for a permission and an origin. */
@CefriumExperimental
public enum CefriumContentSetting {
    /** No stored decision: the engine's default applies. */
    DEFAULT,
    /** Granted without asking. */
    ALLOW,
    /** Denied without asking. */
    BLOCK,
    /** Ask each time: the request reaches the chrome client. */
    ASK
}
