// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be found
// in the LICENSE file.

package com.cefrium.webauthn;

import androidx.annotation.Nullable;

import org.chromium.blink.mojom.Authenticator;
import org.chromium.components.webauthn.AuthenticatorFactory;
import org.chromium.content_public.browser.InterfaceRegistrar;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.services.service_manager.InterfaceRegistry;

/**
 * FEATURE-064: registers the WebAuthn Authenticator factory for render frames so
 * {@code navigator.credentials.create/get({publicKey})} (passkeys) reach the
 * Android Fido2 / Credential Manager path (surfacing providers like Proton Pass).
 *
 * <p>Cefrium stubs out Chrome's {@code ChromeInterfaceRegistrar} (which is what
 * binds the Authenticator mojo interface in a full Chrome build), and re-adds the
 * interfaces an embedding needs per-embedder -- as it already does for
 * PaymentRequest. Without this, the {@code blink.mojom.Authenticator} interface
 * is never bound to a Java factory, so the passkey prompt has no Android
 * authenticator and the credential picker never appears.
 *
 * <p>Uses the reusable components factory {@link AuthenticatorFactory} (not
 * Chrome's, which pulls chrome deps); a null confirmation-UI factory is
 * explicitly supported. The WebAuthn mode is set elsewhere (Cefrium.configureWebAuthn
 * + per-WebContents CHROME_3PP_ENABLED), so the factory produces a live authenticator.
 */
public final class CefAuthenticatorInterfaceRegistrar {
    private static final String TAG = "Cefrium";
    private static boolean sRegistered;

    private CefAuthenticatorInterfaceRegistrar() {}

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
            try {
                registry.addInterface(
                        Authenticator.MANAGER,
                        new AuthenticatorFactory(renderFrameHost, /* confirmationFactory= */ null));
            } catch (Throwable t) {
                // WebAuthn Java components may be absent on some configs -- don't
                // break interface registration for other consumers.
                org.chromium.base.Log.w(TAG, "WebAuthn Authenticator not registered: %s", t);
            }
        }
    }
}
