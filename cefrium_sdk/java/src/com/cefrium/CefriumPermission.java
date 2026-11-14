// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** A web permission a page can ask for. See {@link CefriumPermissionRequest}. */
@CefriumExperimental
public enum CefriumPermission {
    /**
     * {@code navigator.geolocation}. The app must also hold
     * {@code ACCESS_FINE_LOCATION} or {@code ACCESS_COARSE_LOCATION}, and
     * location must be on; otherwise the page is refused before any request.
     */
    GEOLOCATION,
    /** The camera, through {@code getUserMedia}. */
    CAMERA,
    /** The microphone, through {@code getUserMedia}. */
    MICROPHONE,
    /** Web notifications. */
    NOTIFICATIONS,
    /** {@code navigator.clipboard.readText}. Writing needs no permission. */
    CLIPBOARD_READ,
    /** Web NFC ({@code NDEFReader}); also declare {@code android.permission.NFC}. */
    NFC,
    /**
     * Any other permission the engine asks for (for example local network
     * access or MIDI). A request that holds one is granted or denied as a
     * whole, like the others.
     */
    OTHER
}
