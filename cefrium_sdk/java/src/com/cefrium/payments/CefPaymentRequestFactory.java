// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.payments;

import androidx.annotation.Nullable;

import org.chromium.components.payments.BrowserPaymentRequest;
import org.chromium.components.payments.InvalidPaymentRequest;
import org.chromium.components.payments.MojoPaymentRequestGateKeeper;
import org.chromium.components.payments.OriginSecurityChecker;
import org.chromium.components.payments.PaymentFeatureList;
import org.chromium.components.payments.PaymentRequestService;
import org.chromium.components.payments.PaymentRequestServiceUtil;
import org.chromium.components.payments.SslValidityChecker;
import org.chromium.content_public.browser.LifecycleState;
import org.chromium.content_public.browser.PermissionsPolicyFeature;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.WebContentsStatics;
import org.chromium.payments.mojom.PaymentRequest;
import org.chromium.services.service_manager.InterfaceFactory;

/**
 * Creates a no-UI PaymentRequest service for a frame running inside a Cefrium embedding.
 *
 * <p>Direct port of WebView's {@code AwPaymentRequestFactory}, minus the AwContents/AwSettings
 * gating (an embedding has no per-WebView "payment request enabled" setting, so it is always on).
 * Registered for {@code PaymentRequest.MANAGER} by {@link CefPaymentInterfaceRegistrar}, which runs
 * after Chrome's registrar so this overrides Chrome's ChromePaymentRequestFactory (the interface
 * registry is a last-writer-wins map).
 */
public class CefPaymentRequestFactory implements InterfaceFactory<PaymentRequest> {
    private final RenderFrameHost mRenderFrameHost;

    private class CefPaymentRequestDelegate implements PaymentRequestService.Delegate {
        @Override
        public BrowserPaymentRequest createBrowserPaymentRequest(
                PaymentRequestService paymentRequestService) {
            return new CefPaymentRequestService(paymentRequestService);
        }

        @Override
        public boolean isOffTheRecord() {
            // The embedding has no "off the record"/incognito concept.
            return false;
        }

        @Override
        public @Nullable String getInvalidSslCertificateErrorMessage() {
            WebContents liveWebContents =
                    PaymentRequestServiceUtil.getLiveWebContents(mRenderFrameHost);
            if (liveWebContents == null) return null;
            if (!OriginSecurityChecker.isSchemeCryptographic(
                    liveWebContents.getLastCommittedUrl())) {
                return null;
            }
            return SslValidityChecker.getInvalidSslCertificateErrorMessage(liveWebContents);
        }

        @Override
        public boolean prefsCanMakePayment() {
            // No per-embedding preference; allow the query (the renderer still only sees true when
            // exactly one matching Android payment app is present).
            return true;
        }

        @Override
        @Nullable
        public String getTwaPackageName() {
            return null;
        }
    }

    /**
     * @param renderFrameHost The host of the frame that invoked the PaymentRequest API.
     */
    public CefPaymentRequestFactory(RenderFrameHost renderFrameHost) {
        mRenderFrameHost = renderFrameHost;
    }

    // InterfaceFactory<PaymentRequest>:
    @Override
    public PaymentRequest createImpl() {
        if (mRenderFrameHost == null
                || !mRenderFrameHost.isRenderFrameLive()
                || mRenderFrameHost.getLifecycleState() != LifecycleState.ACTIVE) {
            return new InvalidPaymentRequest();
        }

        if (!mRenderFrameHost.isFeatureEnabled(PermissionsPolicyFeature.PAYMENT)) {
            mRenderFrameHost.terminateRendererDueToBadMessage(241 /*PAYMENTS_WITHOUT_PERMISSION*/);
            return null;
        }

        if (!PaymentFeatureList.isEnabled(PaymentFeatureList.WEB_PAYMENTS)) {
            return new InvalidPaymentRequest();
        }

        WebContents webContents = WebContentsStatics.fromRenderFrameHost(mRenderFrameHost);
        if (webContents == null || webContents.isDestroyed()) {
            return new InvalidPaymentRequest();
        }

        return new MojoPaymentRequestGateKeeper(
                (client, onClosed) ->
                        new PaymentRequestService(
                                mRenderFrameHost,
                                client,
                                onClosed,
                                new CefPaymentRequestDelegate(),
                                // No C++ payment apps (service workers / Secure Payment
                                // Confirmation), because those require browser UI:
                                /* paymentAppServiceBridgeSupplier= */ null));
    }
}
