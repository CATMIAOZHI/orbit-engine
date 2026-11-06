// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

/**
 * Cefrium: the Chromium engine in an Android app, through CEF.
 *
 * <p>{@link com.cefrium.Cefrium} initializes the engine and
 * {@link com.cefrium.CefriumBrowser} is one browser, on a surface or off
 * screen. This package is the whole public API. The engine's own classes
 * ({@code org.chromium.*}, {@code GEN_JNI}) are on the classpath too, but code
 * that calls them or reaches them by reflection breaks on the next engine
 * update; <a href="https://cefrium.com/guides/public-api/">Public API, not
 * engine internals</a> lists the call to use instead.</p>
 *
 * <p>Guides: <a href="https://cefrium.com/guides/">cefrium.com/guides</a>.
 * A summary of the docs for tools that read them:
 * <a href="https://cefrium.com/llms.txt">cefrium.com/llms.txt</a>.</p>
 */
package com.cefrium;
