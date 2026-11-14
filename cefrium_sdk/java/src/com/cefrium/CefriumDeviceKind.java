// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** What a device chooser picks: the Web API that asked for a device. */
public enum CefriumDeviceKind {
    /** {@code navigator.bluetooth.requestDevice()}. */
    BLUETOOTH,
    /** {@code navigator.usb.requestDevice()}. */
    USB,
    /** {@code navigator.serial.requestPort()}. */
    SERIAL
}
