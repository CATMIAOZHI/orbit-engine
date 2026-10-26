// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.content.SharedPreferences;

import org.chromium.base.ContextUtils;
import org.chromium.components.gcm_driver.instance_id.InstanceIDWithSubtype;

import java.io.IOException;

/**
 * Serves one Web Push subscription (the engine's InstanceID for its app id)
 * with a {@link CefriumPushTransport}. The token the engine receives is the
 * transport's endpoint, which it hands to the page unchanged.
 */
final class PushTransportInstanceID extends InstanceIDWithSubtype {
    // The authorized entity each subscription was made with: the engine finds
    // the message's decryption keys by (app id, sender).
    private static final String SENDER_PREFIX = "cefrium_push_sender.";
    private static final String CREATED_PREFIX = "cefrium_push_created.";

    private final String mSubtype;
    private final CefriumPushTransport mTransport;

    PushTransportInstanceID(String subtype, CefriumPushTransport transport) {
        super(null);
        mSubtype = subtype;
        mTransport = transport;
    }

    static String senderOf(String appId) {
        return prefs().getString(SENDER_PREFIX + appId, null);
    }

    private static SharedPreferences prefs() {
        return ContextUtils.getAppSharedPreferences();
    }

    @Override
    public String getSubtype() {
        return mSubtype;
    }

    @Override
    public String getId() {
        return "cefrium-" + Integer.toHexString(mSubtype.hashCode());
    }

    @Override
    public long getCreationTime() {
        return prefs().getLong(CREATED_PREFIX + mSubtype, 0);
    }

    @Override
    public String getToken(String authorizedEntity, String scope) throws IOException {
        String endpoint = mTransport.subscribe(mSubtype, authorizedEntity);
        if (endpoint == null || !endpoint.startsWith("https://")) {
            throw new IOException("push transport returned no https endpoint");
        }
        prefs().edit()
                .putString(SENDER_PREFIX + mSubtype, authorizedEntity)
                .putLong(CREATED_PREFIX + mSubtype, System.currentTimeMillis())
                .apply();
        return endpoint;
    }

    @Override
    public void deleteToken(String authorizedEntity, String scope) throws IOException {
        mTransport.unsubscribe(mSubtype);
        forget();
    }

    @Override
    public void deleteInstanceID() throws IOException {
        synchronized (sSubtypeInstancesLock) {
            sSubtypeInstances.remove(mSubtype);
        }
        mTransport.unsubscribe(mSubtype);
        forget();
    }

    private void forget() {
        prefs().edit().remove(SENDER_PREFIX + mSubtype).remove(CREATED_PREFIX + mSubtype).apply();
    }
}
