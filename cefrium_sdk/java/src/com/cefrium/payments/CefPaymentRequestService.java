// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be found
// in the LICENSE file.

package com.cefrium.payments;

import android.content.Intent;

import androidx.annotation.Nullable;

import org.chromium.base.Callback;
import org.chromium.base.Log;
import org.chromium.components.payments.AndroidIntentLauncher;
import org.chromium.components.payments.BrowserPaymentRequest;
import org.chromium.components.payments.DialogController;
import org.chromium.components.payments.ErrorStrings;
import org.chromium.components.payments.PayerData;
import org.chromium.components.payments.PaymentAddressTypeConverter;
import org.chromium.components.payments.PaymentApp;
import org.chromium.components.payments.PaymentAppError;
import org.chromium.components.payments.PaymentRequestService;
import org.chromium.components.payments.PaymentRequestSpec;
import org.chromium.components.payments.PaymentResponseHelperInterface;
import org.chromium.payments.mojom.PayerDetail;
import org.chromium.payments.mojom.PaymentDetails;
import org.chromium.payments.mojom.PaymentErrorReason;
import org.chromium.payments.mojom.PaymentItem;
import org.chromium.payments.mojom.PaymentResponse;
import org.chromium.payments.mojom.PaymentValidationErrors;
import org.chromium.ui.base.WindowAndroid;

import java.util.ArrayList;
import java.util.List;

/**
 * The Cefrium part of the PaymentRequest service - a no-UI {@link BrowserPaymentRequest}.
 *
 * <p>Direct port of WebView's {@code AwPaymentRequestService}: like WebView, an embedding has no
 * browser-chrome payment sheet, so the invoked Android payment app is responsible for showing UI
 * and communicating with the merchant website. The methods marked "// No UI" are the ones that
 * would update a browser sheet in Chrome; here they are no-ops. canMakePayment() and
 * hasEnrolledInstrument() resolve to true only when exactly one matching Android payment app is
 * found, and show() launches that single app via an Android intent. This avoids Chrome's
 * ChromePaymentRequestService, which requires a ChromeActivity + TabModel that an embedding lacks
 * (it returns "Unable to find Chrome activity").
 */
/* package */ class CefPaymentRequestService
        implements BrowserPaymentRequest, PaymentResponseHelperInterface, AndroidIntentLauncher {
    private static final String TAG = "CefPaymentRequest";
    private static final String RETRY_DISABLED =
            "PaymentResponse.retry() is disabled in an embedding.";
    private static final String MORE_THAN_ONE_APP =
            "An embedding supports launching only one payment app at a time.";

    @Nullable private PaymentRequestService mPaymentRequestService;
    private final List<PaymentApp> mApps = new ArrayList<>();

    private boolean mIsReadyToSendPrePurchaseQueryResponsesToRenderer;
    private boolean mPaymentRequestCanMakePaymentResponse;
    private boolean mPaymentRequestHasEnrolledInstrumentResponse;

    @Nullable private Callback<Boolean> mSenderOfCanMakePaymentResponseToRenderer;
    @Nullable private Callback<Boolean> mSenderOfHasEnrolledInstrumentResponseToRenderer;

    /* package */ CefPaymentRequestService(PaymentRequestService paymentRequestService) {
        mPaymentRequestService = paymentRequestService;
    }

    // BrowserPaymentRequest:
    @Override
    public void onPaymentDetailsUpdated(
            PaymentDetails details, boolean hasNotifiedInvokedPaymentApp) {
        // No UI.
    }

    @Override
    public void onPaymentDetailsNotUpdated(@Nullable String selectedShippingOptionError) {
        // No UI.
    }

    @Override
    public void complete(int result, Runnable onCompleteHandled) {
        // No UI.
        onCompleteHandled.run();
    }

    @Override
    public boolean disconnectIfNoRetrySupport() {
        // Retrying a payment requires either browser UI or payment-handler support; an embedding has
        // neither and Android payment apps do not support retry.
        if (mPaymentRequestService != null) {
            mPaymentRequestService.disconnectFromClientWithDebugMessage(
                    RETRY_DISABLED, PaymentErrorReason.NOT_SUPPORTED);
        }
        close();
        return true; // The Mojo IPC connection has been closed.
    }

    @Override
    public void onRetry(PaymentValidationErrors errors) {
        // Cannot happen because disconnectIfNoRetrySupport() returned true.
        assert false;
    }

    @Override
    public void close() {
        if (mPaymentRequestService != null) {
            mPaymentRequestService.close();
            mPaymentRequestService = null;
        }
    }

    @Override
    public void onSpecValidated(PaymentRequestSpec spec) {
        // No UI.
    }

    @Override
    public boolean hasAvailableApps() {
        return !mApps.isEmpty();
    }

    @Override
    @Nullable
    public String showOrSkipAppSelector(
            boolean isShowWaitingForUpdatedDetails,
            PaymentItem total,
            boolean shouldSkipAppSelector) {
        // No UI.
        return null;
    }

    @Override
    public void notifyPaymentUiOfPendingApps(List<PaymentApp> pendingApps) {
        mApps.addAll(pendingApps);
        mIsReadyToSendPrePurchaseQueryResponsesToRenderer = true;
        maybeSendPrePurchaseQueryResponsesToRendererIfReady();
    }

    @Override
    @Nullable
    public String onShowCalledAndAppsQueriedAndDetailsFinalized() {
        if (mPaymentRequestService == null
                || mPaymentRequestService.getSpec() == null
                || mPaymentRequestService.getSpec().isDestroyed()) {
            return ErrorStrings.INVALID_STATE;
        }

        if (mApps.size() > 1) {
            // No UI to choose among multiple matching payment apps; abort.
            Log.e(TAG, MORE_THAN_ONE_APP);
            return MORE_THAN_ONE_APP;
        }

        PaymentApp selectedPaymentApp = getSelectedPaymentApp();
        if (selectedPaymentApp == null) {
            Log.e(TAG, "No matching payment apps found.");
            return ErrorStrings.PAYMENT_APP_LAUNCH_FAIL;
        }

        mPaymentRequestService.getJourneyLogger().setSkippedShow(); // No browser UI was shown.
        mPaymentRequestService.invokePaymentApp(
                selectedPaymentApp, /* paymentResponseHelper= */ this);
        return null;
    }

    @Override
    @Nullable
    public PaymentApp getSelectedPaymentApp() {
        return mApps.isEmpty() ? null : mApps.get(0);
    }

    @Override
    public List<PaymentApp> getPaymentApps() {
        return mApps;
    }

    @Override
    public boolean hasAnyCompleteApp() {
        return !mApps.isEmpty();
    }

    @Override
    public DialogController getDialogController() {
        // AndroidPaymentApp uses this only for debug-info and incognito-warning UI; an embedding has
        // no UI and no incognito mode.
        return null;
    }

    @Override
    @Nullable
    public byte[][] getCertificateChain() {
        // The merchant certificate chain is not passed to the invoked Android payment app.
        return null;
    }

    @Override
    public AndroidIntentLauncher getAndroidIntentLauncher() {
        return this;
    }

    @Override
    public boolean isFullDelegationRequired() {
        // The payment app must provide shipping address and contact information when requested.
        return true;
    }

    @Override
    public void maybeOverrideCanMakePaymentResponse(boolean response, Callback<Boolean> sender) {
        mPaymentRequestCanMakePaymentResponse = response;
        mSenderOfCanMakePaymentResponseToRenderer = sender;
        maybeSendPrePurchaseQueryResponsesToRendererIfReady();
    }

    @Override
    public void maybeOverrideHasEnrolledInstrumentResponse(
            boolean response, Callback<Boolean> sender) {
        mPaymentRequestHasEnrolledInstrumentResponse = response;
        mSenderOfHasEnrolledInstrumentResponseToRenderer = sender;
        maybeSendPrePurchaseQueryResponsesToRendererIfReady();
    }

    /**
     * Sends the "pre-purchase query" responses (canMakePayment()/hasEnrolledInstrument()) to the
     * renderer, forcing "false" unless exactly one matching payment app is present.
     */
    private void maybeSendPrePurchaseQueryResponsesToRendererIfReady() {
        if (!mIsReadyToSendPrePurchaseQueryResponsesToRenderer) {
            return;
        }

        boolean isExactlyOneApp = mApps.size() == 1;

        if (mSenderOfCanMakePaymentResponseToRenderer != null) {
            boolean result = mPaymentRequestCanMakePaymentResponse && isExactlyOneApp;
            if (!result) {
                Log.e(TAG, "Cannot make payments. Have %d apps.", mApps.size());
            }
            mSenderOfCanMakePaymentResponseToRenderer.onResult(result);
            mSenderOfCanMakePaymentResponseToRenderer = null;
        }

        if (mSenderOfHasEnrolledInstrumentResponseToRenderer != null) {
            boolean result = mPaymentRequestHasEnrolledInstrumentResponse && isExactlyOneApp;
            if (!result) {
                Log.e(TAG, "No enrolled instrument. Have %d apps.", mApps.size());
            }
            mSenderOfHasEnrolledInstrumentResponseToRenderer.onResult(result);
            mSenderOfHasEnrolledInstrumentResponseToRenderer = null;
        }
    }

    // PaymentResponseHelperInterface:
    @Override
    public void generatePaymentResponse(
            String methodName,
            String stringifiedDetails,
            PayerData payerData,
            PaymentResponseResultCallback resultCallback) {
        PaymentResponse response = new PaymentResponse();

        response.methodName = methodName;
        response.stringifiedDetails = stringifiedDetails;

        if (mPaymentRequestService.getPaymentOptions().requestShipping) {
            response.shippingAddress =
                    PaymentAddressTypeConverter.convertAddressToMojoPaymentAddress(
                            payerData.shippingAddress);
            response.shippingOption = payerData.selectedShippingOptionId;
        }

        response.payer = new PayerDetail();
        if (mPaymentRequestService.getPaymentOptions().requestPayerName) {
            response.payer.name = payerData.payerName;
        }
        if (mPaymentRequestService.getPaymentOptions().requestPayerPhone) {
            response.payer.phone = payerData.payerPhone;
        }
        if (mPaymentRequestService.getPaymentOptions().requestPayerEmail) {
            response.payer.email = payerData.payerEmail;
        }

        resultCallback.onPaymentResponseReady(response);
    }

    // AndroidIntentLauncher:
    @Override
    public void launchPaymentApp(
            Intent intent,
            Callback<PaymentAppError> errorCallback,
            WindowAndroid.IntentCallback intentCallback) {
        WindowAndroid window = mPaymentRequestService.getWebContents().getTopLevelNativeWindow();
        if (window == null) {
            Log.e(TAG, "No window available to launch the payment app.");
            return;
        }
        window.showCancelableIntent(intent, intentCallback, /* errorId= */ null);
    }
}
