// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/** What a request is for, as {@link CefriumResourceInterceptor} sees it. */
@CefriumExperimental
public enum CefriumResourceType {
    /** A page loaded into the main frame. */
    MAIN_FRAME,
    /** A page loaded into an iframe. */
    SUB_FRAME,
    STYLESHEET,
    SCRIPT,
    IMAGE,
    FONT,
    /** Audio and video. */
    MEDIA,
    /** {@code fetch()} and {@code XMLHttpRequest}. */
    XHR,
    /** A dedicated or shared worker's script. */
    WORKER,
    /** A service worker's script. */
    SERVICE_WORKER,
    /** {@code navigator.sendBeacon}, hyperlink auditing, CSP reports. */
    PING,
    FAVICON,
    /** {@code <link rel=prefetch>}. */
    PREFETCH,
    /** Anything else ({@code <object>}, {@code <embed>}, other subresources). */
    OTHER
}
