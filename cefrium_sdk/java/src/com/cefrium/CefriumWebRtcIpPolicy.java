// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** Which interfaces WebRTC may use. See {@link CefriumProfile#setWebRtcIpHandlingPolicy(CefriumWebRtcIpPolicy)}. */
@CefriumExperimental
public enum CefriumWebRtcIpPolicy {
    /** Every interface (the engine's default). */
    DEFAULT("default"),
    /** The default route's public and private addresses only. */
    PUBLIC_AND_PRIVATE_INTERFACES("default_public_and_private_interfaces"),
    /** The default route's public address only. */
    PUBLIC_INTERFACE_ONLY("default_public_interface_only"),
    /**
     * No UDP that bypasses the proxy, and no local or public address
     * candidates: with a proxy, a page cannot learn the device's address.
     */
    DISABLE_NON_PROXIED_UDP("disable_non_proxied_udp");

    final String pref;

    CefriumWebRtcIpPolicy(String pref) {
        this.pref = pref;
    }
}
