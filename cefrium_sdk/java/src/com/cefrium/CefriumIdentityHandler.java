// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.util.List;

/**
 * Shows the account chooser of a FedCM request
 * ({@code navigator.credentials.get({identity})}), which "Sign in with" prompts
 * such as Google's use. Without a handler the browser shows a default dialog on
 * its activity (a surface browser's own, or the one given to
 * {@link CefriumBrowser#setDialogActivity}); with neither, the request fails.
 *
 * <p>Called on the main thread.</p>
 *
 * <p>Experimental: it may still change before the 1.0 API freeze.</p>
 */
public interface CefriumIdentityHandler {

    /** An account an identity provider offers. */
    final class Account {
        public final String id;
        public final String name;
        public final String email;
        /** The URL of the account's picture, or empty. */
        public final String pictureUrl;
        /** The identity provider, for display (e.g. "idp.example"). */
        public final String identityProvider;
        /** The user signed in to this site with this account before. */
        public final boolean returningUser;

        public Account(String id, String name, String email, String pictureUrl,
                String identityProvider, boolean returningUser) {
            this.id = id;
            this.name = name;
            this.email = email;
            this.pictureUrl = pictureUrl;
            this.identityProvider = identityProvider;
            this.returningUser = returningUser;
        }
    }

    /** Answers one {@link #onShowAccountChooser} call; only the first answer counts. */
    interface Request {
        /** Signs in with {@code account}, one of those offered. */
        void select(Account account);

        /** Dismisses the chooser: the page's request fails. */
        void cancel();
    }

    /**
     * {@code relyingParty} (the site, for display) asks to sign in with one of
     * {@code accounts}. Return true and answer {@code request} when the user
     * acts; return false for the default dialog.
     */
    boolean onShowAccountChooser(String relyingParty, List<Account> accounts, Request request);

    /**
     * The chooser of {@code request} is no longer needed (the page went away
     * or gave up): dismiss it. Answering {@code request} has no effect now.
     */
    default void onAccountChooserClosed(Request request) {}
}
