// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.unifiedpush;

import android.content.Context;

import com.cefrium.CefriumPush;
import com.cefrium.CefriumPushTransport;

import org.unifiedpush.android.connector.UnifiedPush;
import org.unifiedpush.android.connector.data.PublicKeySet;
import org.unifiedpush.android.connector.keys.KeyManager;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Web Push through UnifiedPush: pages' subscriptions are registered with the
 * distributor the user chose (ntfy, NextPush, ...) instead of Firebase Cloud
 * Messaging, so push works on devices without Google Play services.
 *
 * <pre>{@code
 * // Application.onCreate()
 * CefriumUnifiedPush.install(this);
 *
 * // Once, from an Activity: let the user pick a distributor.
 * UnifiedPush.tryUseCurrentOrDefaultDistributor(activity, ok -> {});
 * }</pre>
 *
 * <p>Until a distributor is chosen, {@code PushManager.subscribe()} fails
 * with an {@code AbortError}.</p>
 */
public final class CefriumUnifiedPush extends CefriumPushTransport {
    private static final long REGISTER_TIMEOUT_SECONDS = 30;
    private static final String LABEL = "Web Push";

    // Registrations waiting for their endpoint, by app id (the instance).
    static final Map<String, CompletableFuture<String>> sPending = new ConcurrentHashMap<>();

    // The messages are encrypted for the page and the engine decrypts them:
    // no keys here, so the connector hands over the body as it arrived.
    static final KeyManager NO_KEYS = new KeyManager() {
        @Override
        public byte[] decrypt(String instance, byte[] sealed) {
            return null;
        }

        @Override
        public void generate(String instance) {}

        @Override
        public PublicKeySet getPublicKeySet(String instance) {
            return null;
        }

        @Override
        public boolean exists(String instance) {
            return true;
        }

        @Override
        public void delete(String instance) {}
    };

    private final Context mContext;

    private CefriumUnifiedPush(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Serves Web Push with UnifiedPush. Call it in {@code Application.onCreate()}. */
    public static void install(Context context) {
        CefriumPush.setTransport(new CefriumUnifiedPush(context));
    }

    @Override
    public String subscribe(String appId, String applicationServerKey) throws IOException {
        if (UnifiedPush.getSavedDistributor(mContext) == null) {
            throw new IOException("no UnifiedPush distributor chosen");
        }
        CompletableFuture<String> endpoint = new CompletableFuture<>();
        sPending.put(appId, endpoint);
        try {
            UnifiedPush.register(mContext, appId, LABEL,
                    isVapidKey(applicationServerKey) ? applicationServerKey : null, NO_KEYS);
            return endpoint.get(REGISTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } catch (TimeoutException | InterruptedException e) {
            throw new IOException("UnifiedPush registration did not complete", e);
        } finally {
            sPending.remove(appId, endpoint);
        }
    }

    @Override
    public void unsubscribe(String appId) {
        // The connector forgets the distributor with the last registration;
        // a page unsubscribing must not leave every later subscribe() failing.
        String distributor = UnifiedPush.getSavedDistributor(mContext);
        UnifiedPush.unregister(mContext, appId, NO_KEYS);
        if (distributor != null && UnifiedPush.getSavedDistributor(mContext) == null) {
            UnifiedPush.saveDistributor(mContext, distributor);
        }
    }

    // push_messaging::NormalizeSenderInfo: a VAPID key arrives base64url,
    // 87 characters; anything else is a legacy sender id.
    private static boolean isVapidKey(String key) {
        return key != null && key.length() == 87 && key.matches("[A-Za-z0-9_-]+");
    }
}
