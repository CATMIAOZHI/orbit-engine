// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium.unifiedpush;

import android.util.Log;

import com.cefrium.CefriumPush;

import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** Receives the distributor's events for {@link CefriumUnifiedPush}. */
public final class CefriumUnifiedPushService extends PushService {
    private static final String TAG = "CefriumUnifiedPush";

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        CompletableFuture<String> pending = CefriumUnifiedPush.sPending.get(instance);
        if (pending != null) {
            pending.complete(endpoint.getUrl());
        } else {
            // The distributor moved an existing subscription; the page keeps
            // the old endpoint until it subscribes again.
            Log.w(TAG, "endpoint changed for " + instance);
        }
    }

    @Override
    public void onMessage(PushMessage message, String instance) {
        CefriumPush.deliverMessage(this, instance, message.getContent());
    }

    @Override
    public void onRegistrationFailed(FailedReason reason, String instance) {
        CompletableFuture<String> pending = CefriumUnifiedPush.sPending.get(instance);
        if (pending != null) {
            pending.completeExceptionally(new IOException("registration failed: " + reason));
        }
    }

    @Override
    public void onUnregistered(String instance) {}
}
