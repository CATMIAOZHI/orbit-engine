// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A certificate that failed validation, as
 * {@link CefriumNavigationClient#onSslError} sees it. Like
 * {@code android.net.http.SslError}, with the whole chain.
 */
@CefriumExperimental
public final class CefriumSslError {
    private final int mErrorCode;
    private final String mUrl;
    private final List<X509Certificate> mChain;

    CefriumSslError(int errorCode, String url, byte[][] derChain) {
        mErrorCode = errorCode;
        mUrl = url;
        List<X509Certificate> chain = new ArrayList<>();
        if (derChain != null) {
            try {
                CertificateFactory f = CertificateFactory.getInstance("X.509");
                for (byte[] der : derChain) {
                    if (der != null && der.length > 0) {
                        chain.add((X509Certificate) f.generateCertificate(
                                new ByteArrayInputStream(der)));
                    }
                }
            } catch (CertificateException e) {
                // A chain that does not parse is reported empty, not partially.
                chain.clear();
            }
        }
        mChain = Collections.unmodifiableList(chain);
    }

    /**
     * Chromium's net error code for the failure, negative: -202 authority
     * invalid, -201 date invalid, -200 name mismatch, ... Stable across engine
     * versions (net_error_list.h).
     */
    public int getErrorCode() {
        return mErrorCode;
    }

    /** The URL whose certificate failed. */
    public String getUrl() {
        return mUrl;
    }

    /** The server's certificate, or null when the engine gave none. */
    public X509Certificate getCertificate() {
        return mChain.isEmpty() ? null : mChain.get(0);
    }

    /** The chain the server sent, its certificate first. Unmodifiable. */
    public List<X509Certificate> getCertificateChain() {
        return mChain;
    }
}
