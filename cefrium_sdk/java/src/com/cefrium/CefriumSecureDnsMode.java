// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** Chrome's secure DNS setting. See {@link Cefrium#setSecureDns(CefriumSecureDnsMode, String)}. */
@CefriumExperimental
public enum CefriumSecureDnsMode {
    /** The system resolver. */
    OFF("off"),
    /** DNS over HTTPS when the system's resolver offers it; Chrome's default. */
    AUTOMATIC("automatic"),
    /** Only DNS over HTTPS through the given servers; names fail without them. */
    SECURE("secure");

    final String pref;

    CefriumSecureDnsMode(String pref) {
        this.pref = pref;
    }
}
