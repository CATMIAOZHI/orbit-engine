// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.chromium.components.gcm_driver.GCMDriver;
import org.chromium.components.gcm_driver.GCMMessage;

/**
 * Delivers Web Push messages to the engine.
 *
 * <p>A page's {@code PushManager.subscribe()} registers with Firebase Cloud
 * Messaging, and FCM delivers each message to the app's
 * {@code FirebaseMessagingService}. An app that already has one passes every
 * message through {@link #handleMessage}; the ones that belong to a web page
 * go to the engine, which wakes that page's service worker in the profile that
 * subscribed, and the rest stay with the app:</p>
 *
 * <pre>{@code
 * public void onMessageReceived(RemoteMessage message) {
 *     if (CefriumPush.handleMessage(this, message.getFrom(),
 *             message.toIntent().getExtras())) {
 *         return;  // A Web Push message, delivered to the page.
 *     }
 *     // The app's own message.
 * }
 * }</pre>
 *
 * <p>An app without its own Firebase service enables {@link CefriumPushService}
 * instead, which does the above.</p>
 */
public final class CefriumPush {
    // Web Push app ids: Chromium's push_messaging::kAppIdentifierPrefix.
    private static final String WEB_PUSH_APP_ID_PREFIX = "wp:";
    private static final String SUBTYPE = "subtype";

    private CefriumPush() {}

    /** @return true if {@code data} is a Web Push message for the engine. */
    public static boolean isWebPushMessage(Bundle data) {
        if (data == null) return false;
        String subtype = data.getString(SUBTYPE);
        return subtype != null && subtype.startsWith(WEB_PUSH_APP_ID_PREFIX);
    }

    /**
     * Hands a Web Push message to the engine.
     *
     * @param context any context of the app.
     * @param from the message's sender, {@code RemoteMessage.getFrom()}.
     * @param data the message's extras, {@code RemoteMessage.toIntent().getExtras()}.
     * @return true if it was a Web Push message, now on its way to the page;
     *         false if it belongs to the app.
     */
    public static boolean handleMessage(Context context, String from, Bundle data) {
        if (!isWebPushMessage(data)) return false;
        final GCMMessage message = new GCMMessage(from, data);
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!Cefrium.isInitialized()) Cefrium.initialize(app);
            GCMDriver.dispatchMessage(message);
        });
        return true;
    }
}
