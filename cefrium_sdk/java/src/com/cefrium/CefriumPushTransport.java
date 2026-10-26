// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import java.io.IOException;

/**
 * A push service for Web Push other than Firebase Cloud Messaging.
 *
 * <p>By default a page's {@code PushManager.subscribe()} registers with FCM.
 * An app that installs a transport with {@link CefriumPush#setTransport} has
 * every Web Push subscription served by it instead: the endpoint the page
 * receives is the one {@link #subscribe} returns, and the transport hands each
 * incoming message to {@link CefriumPush#deliverMessage}. Messages are
 * encrypted for the page (RFC 8291), so a transport only carries them.</p>
 *
 * <p>The cefrium-unifiedpush library provides one for UnifiedPush.</p>
 */
public abstract class CefriumPushTransport {
    /**
     * Registers a subscription with the push service. Called off the main
     * thread; may block until the service answers.
     *
     * @param appId identifies the subscription; pass it back to
     *        {@link CefriumPush#deliverMessage} with its messages.
     * @param applicationServerKey the page's VAPID public key, base64url,
     *        or the sender id of a page that subscribed without one.
     * @return the https URL that application servers send messages to.
     * @throws IOException if the service could not register it.
     */
    public abstract String subscribe(String appId, String applicationServerKey)
            throws IOException;

    /**
     * Removes a subscription from the push service. Called off the main thread.
     *
     * @throws IOException if the service could not remove it.
     */
    public abstract void unsubscribe(String appId) throws IOException;
}
