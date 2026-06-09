// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be found
// in the LICENSE file.

package com.cefrium.payments;

import androidx.annotation.Nullable;

import org.chromium.content_public.browser.InterfaceRegistrar;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.payments.mojom.PaymentRequest;
import org.chromium.services.service_manager.InterfaceRegistry;

/**
 * Registers the no-UI Cefrium PaymentRequest factory for render frames.
 *
 * <p>Install once at SDK init via {@link #register()}. Because the interface registry applies
 * render-frame-host registrars in insertion order and {@code addInterface} is last-writer-wins,
 * adding this registrar after Chrome's (Chrome's runs at browser-process startup) makes
 * {@link CefPaymentRequestFactory} override Chrome's ChromePaymentRequestFactory for
 * {@code PaymentRequest.MANAGER}. The Chrome factory requires a ChromeActivity + TabModel that an
 * embedding lacks; ours launches the matching Android payment app with no browser UI, like WebView.
 */
public final class CefPaymentInterfaceRegistrar {
    private static boolean sRegistered;

    private CefPaymentInterfaceRegistrar() {}

    /** Idempotent; safe to call on every navigation. */
    public static void register() {
        if (sRegistered) return;
        sRegistered = true;
        InterfaceRegistrar.Registry.addRenderFrameHostRegistrar(new FrameRegistrar());
    }

    private static class FrameRegistrar implements InterfaceRegistrar<RenderFrameHost> {
        @Override
        public void registerInterfaces(
                InterfaceRegistry registry, @Nullable RenderFrameHost renderFrameHost) {
            if (renderFrameHost == null) return;
            registry.addInterface(
                    PaymentRequest.MANAGER, new CefPaymentRequestFactory(renderFrameHost));
        }
    }
}
