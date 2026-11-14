// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Answers a server's request for a client certificate (see
 * {@link CefriumNavigationClient#onSelectClientCertificate}), from any
 * thread. The first answer counts.
 */
@CefriumExperimental
public interface CefriumClientCertificateCallback {
    /** Use the certificate at {@code index} of the list offered. */
    void select(int index);

    /** Go on without a client certificate. */
    void cancel();
}
