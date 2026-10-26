// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Receives Web Push messages for an app that has no
 * {@code FirebaseMessagingService} of its own. Declared disabled in the SDK
 * manifest, so it never competes with the app's own service; enable it in the
 * app's manifest:
 *
 * <pre>{@code
 * <service android:name="com.cefrium.CefriumPushService"
 *     android:enabled="true" tools:replace="android:enabled" />
 * }</pre>
 *
 * An app with its own service calls {@link CefriumPush#handleMessage} instead.
 */
public class CefriumPushService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage message) {
        CefriumPush.handleMessage(this, message.getFrom(), message.toIntent().getExtras());
    }
}
