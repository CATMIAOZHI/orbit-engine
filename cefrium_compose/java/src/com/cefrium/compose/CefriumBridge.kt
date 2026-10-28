// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.compose

import com.cefrium.CefriumBrowser

/**
 * The `window.cefriumQuery` bridge for a [CefriumView]: which origins may call
 * it, and the handler that answers them. There is no way to build one without
 * an origin decision, so forgetting it is a compile error, not a crash at
 * launch.
 *
 * ```kotlin
 * CefriumView(
 *     state,
 *     bridge = CefriumBridge(CefriumBrowser.QueryOriginPolicy.loopback()) { request, origin, callback ->
 *         callback.success("pong")
 *         true
 *     },
 * )
 * ```
 *
 * @param origins Which origins may reach [onQuery]. Calls from other origins
 *        fail on the page with [CefriumBrowser.QUERY_ERROR_ORIGIN_DENIED] and
 *        never reach [onQuery]. Use [CefriumBrowser.QueryOriginPolicy.anyOrigin]
 *        only for pages you fully control.
 * @param onQuery Handles a query. Return true and resolve via the callback
 *        (sync or async); return false to fail it with -1.
 */
class CefriumBridge(
    val origins: CefriumBrowser.QueryOriginPolicy,
    val onQuery: (request: String, origin: String, callback: CefriumBrowser.QueryCallback) -> Boolean,
) {
    internal var onFrameQuery:
        ((String, String, Boolean, CefriumBrowser.QueryCallback) -> Boolean)? = null
        private set

    companion object {
        /**
         * A bridge whose handler is also told whether the main frame called.
         * Only a policy from [CefriumBrowser.QueryOriginPolicy.withSubframes]
         * lets an iframe call at all.
         */
        @JvmStatic
        fun withFrames(
            origins: CefriumBrowser.QueryOriginPolicy,
            onQuery: (
                request: String,
                origin: String,
                isMainFrame: Boolean,
                callback: CefriumBrowser.QueryCallback,
            ) -> Boolean,
        ): CefriumBridge =
            CefriumBridge(origins) { request, origin, callback ->
                onQuery(request, origin, true, callback)
            }.also { it.onFrameQuery = onQuery }
    }
}
