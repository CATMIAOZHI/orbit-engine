// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** How a profile reaches the network. See {@link CefriumProfile#setProxy(CefriumProxyMode, String)}. */
@CefriumExperimental
public enum CefriumProxyMode {
    /** No proxy. */
    DIRECT("direct"),
    /** The system's proxy settings. */
    SYSTEM("system"),
    /** Find the proxy with WPAD. */
    AUTO_DETECT("auto_detect"),
    /** One proxy: {@code "host:port"} or {@code "scheme://host:port"}. */
    FIXED("fixed_servers"),
    /** A PAC script at a URL. */
    PAC("pac_script");

    // Chromium's "proxy" preference value: Cefrium's to map, not the app's.
    final String pref;

    CefriumProxyMode(String pref) {
        this.pref = pref;
    }
}
