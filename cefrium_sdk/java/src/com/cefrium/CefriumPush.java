// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.chromium.components.gcm_driver.GCMDriver;
import org.chromium.components.gcm_driver.GCMMessage;
import org.chromium.components.gcm_driver.instance_id.InstanceIDWithSubtype;

import java.util.Map;

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
 * instead, which does the above. An app that uses another push service
 * installs a {@link CefriumPushTransport} with {@link #setTransport}.
 * Messages that a {@link CefriumRelayTransport} relay sends through the app's
 * Firebase project arrive the same way and are handled here too.</p>
 */
public final class CefriumPush {
    // Web Push app ids: Chromium's push_messaging::kAppIdentifierPrefix.
    private static final String WEB_PUSH_APP_ID_PREFIX = "wp:";
    private static final String SUBTYPE = "subtype";
    private static final String RAW_DATA = "rawData";
    // GCMEncryptionProvider: the salt and key id travel in an aes128gcm body.
    private static final String CONTENT_ENCODING = "content-encoding";
    private static final String AES128GCM = "aes128gcm";
    // A cefrium-relay message; a body over FCM's size limit comes in parts.
    private static final String RELAY_APP = "cefrium.relay.app";
    private static final String RELAY_BODY = "cefrium.relay.body";
    private static final String RELAY_MSG = "cefrium.relay.msg";
    private static final String RELAY_PART = "cefrium.relay.part";
    private static final String RELAY_PARTS = "cefrium_relay_parts";
    // FCM keeps a message 28 days at most; a part older than that is orphaned.
    private static final long RELAY_PART_LIFETIME_MS = 28L * 24 * 60 * 60 * 1000;

    private static final String TAG = "CefriumPush";

    private CefriumPush() {}

    /** @return true if {@code data} is a Web Push message for the engine. */
    public static boolean isWebPushMessage(Bundle data) {
        if (data == null) return false;
        if (data.getString(RELAY_APP) != null) return true;
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
        if (data.getString(RELAY_APP) != null) {
            handleRelayMessage(context, data);
        } else {
            dispatch(context, new GCMMessage(from, data));
        }
        return true;
    }

    /**
     * Serves every Web Push subscription made from now on with
     * {@code transport} instead of Firebase Cloud Messaging; null restores
     * FCM. Call it in {@code Application.onCreate()}, so the transport is in
     * place when a message or an unsubscribe arrives after a restart.
     * Subscriptions made before keep the service they were made with.
     */
    public static void setTransport(CefriumPushTransport transport) {
        InstanceIDWithSubtype.setFactory(transport == null ? null : subtype ->
                subtype.startsWith(WEB_PUSH_APP_ID_PREFIX)
                        ? new PushTransportInstanceID(subtype, transport) : null);
    }

    /**
     * Hands a message that a {@link CefriumPushTransport} received to the
     * engine.
     *
     * @param appId the subscription, as passed to
     *        {@link CefriumPushTransport#subscribe}.
     * @param body the request body the application server sent: an RFC 8291
     *        {@code aes128gcm} message, still encrypted.
     * @return false if {@code appId} is not a subscription the transport made.
     */
    public static boolean deliverMessage(Context context, String appId, byte[] body) {
        String sender = PushTransportInstanceID.senderOf(appId);
        if (sender == null) return false;
        Bundle data = new Bundle();
        data.putString(SUBTYPE, appId);
        // A push without a payload is not encrypted.
        if (body.length > 0) {
            data.putString(CONTENT_ENCODING, AES128GCM);
            data.putByteArray(RAW_DATA, body);
        }
        dispatch(context, new GCMMessage(sender, data));
        return true;
    }

    private static void handleRelayMessage(Context context, Bundle data) {
        String body = data.getString(RELAY_BODY, "");
        String msg = data.getString(RELAY_MSG);
        if (msg != null) {
            body = addRelayPart(context, msg, data.getString(RELAY_PART, ""), body);
            if (body == null) return;
        }
        byte[] raw;
        try {
            raw = Base64.decode(body, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return;
        }
        deliverMessage(context, data.getString(RELAY_APP), raw);
    }

    /**
     * Keeps part "index/count" of message {@code msg}.
     *
     * @return the whole body once every part is in, else null.
     */
    private static synchronized String addRelayPart(
            Context context, String msg, String part, String body) {
        int index;
        int count;
        try {
            String[] p = part.split("/", -1);
            index = Integer.parseInt(p[0]);
            count = Integer.parseInt(p[1]);
        } catch (RuntimeException e) {
            return null;
        }
        if (count < 2 || count > 8 || index < 0 || index >= count) return null;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(RELAY_PARTS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        long now = System.currentTimeMillis();
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getKey().endsWith(".t") && e.getValue() instanceof Long
                    && now - (Long) e.getValue() > RELAY_PART_LIFETIME_MS) {
                String stale = e.getKey().substring(0, e.getKey().length() - 1);
                for (String k : all.keySet()) {
                    if (k.startsWith(stale)) edit.remove(k);
                }
            }
        }
        StringBuilder whole = new StringBuilder();
        for (int i = 0; i < count && whole != null; i++) {
            String piece = i == index ? body : prefs.getString(msg + "." + i, null);
            if (piece == null) whole = null;
            else whole.append(piece);
        }
        if (whole == null) {
            edit.putString(msg + "." + index, body);
            if (!prefs.contains(msg + ".t")) edit.putLong(msg + ".t", now);
        } else {
            for (int i = 0; i < count; i++) edit.remove(msg + "." + i);
            edit.remove(msg + ".t");
        }
        edit.commit();
        return whole == null ? null : whole.toString();
    }

    // Waits until the engine has the message: the engine starts on first use,
    // and a messaging service that returned first could leave a process that
    // Android freezes or reclaims before the service worker runs.
    private static void dispatch(Context context, GCMMessage message) {
        Runnable deliver = () -> {
            Cefrium.ensureInitialized();
            GCMDriver.dispatchMessage(message);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver.run();
            return;
        }
        java.util.concurrent.FutureTask<Void> task =
                new java.util.concurrent.FutureTask<>(deliver, null);
        new Handler(Looper.getMainLooper()).post(task);
        try {
            task.get(DISPATCH_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            Log.w(TAG, "push message not handed to the engine: " + e);
        }
    }

    // Firebase stops the service about 20 s after onMessageReceived starts.
    private static final long DISPATCH_TIMEOUT_S = 15;
}
