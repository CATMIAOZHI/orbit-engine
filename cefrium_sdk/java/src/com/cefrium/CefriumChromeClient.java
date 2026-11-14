// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

/**
 * Called on the UI thread. What a page asks of the app around it, like
 * {@code android.webkit.WebChromeClient}. Set it with
 * {@link CefriumBrowser#setChromeClient}; every method has a default, so
 * implement only the ones you need. The browser is the first parameter, so one
 * client can serve several browsers.
 */
public interface CefriumChromeClient {

    /** The page's title changed. */
    default void onTitleChanged(CefriumBrowser browser, String title) {}

    /**
     * The page's icon URLs changed, best first (may be empty). Fetch one with
     * {@link CefriumBrowser#downloadImage}.
     */
    default void onFaviconUrlsChanged(CefriumBrowser browser, java.util.List<String> urls) {}

    /** The page entered or left fullscreen ({@code element.requestFullscreen()}). */
    default void onFullscreenChanged(CefriumBrowser browser, boolean fullscreen) {}

    /** The page wrote to its console. */
    default void onConsoleMessage(CefriumBrowser browser, CefriumConsoleMessage message) {}

    /** The text to show for the link under the pointer; empty when it leaves the link. */
    default void onStatusMessage(CefriumBrowser browser, String text) {}

    /** The page started or stopped using the camera or the microphone. */
    default void onMediaAccessChanged(CefriumBrowser browser, boolean camera,
            boolean microphone) {}

    /**
     * A result of {@link CefriumBrowser#find}: the active match (1-based, 0
     * when none) and the number of matches.
     */
    default void onFindResult(CefriumBrowser browser, int activeMatch, int matches) {}

    /**
     * A page opened {@code alert()}, {@code confirm()} or {@code prompt()}.
     * Return true to show your own dialog and answer through {@code callback}
     * (now or later, from any thread; the first answer counts): {@code
     * success} is OK, {@code userInput} the prompt's text. Return false for
     * the engine's own dialog. The page waits until there is an answer.
     *
     * @param defaultText a prompt's initial text, empty otherwise
     */
    default boolean onJsDialog(CefriumBrowser browser, CefriumJsDialogType type,
            String message, String defaultText, CefriumBrowser.JsDialogCallback callback) {
        return false;
    }

    /**
     * A page asks whether to leave it ({@code beforeunload}, for example a
     * form with unsaved changes). Return true to ask the user and answer
     * through {@code callback}: {@code success} leaves the page. Return false
     * to leave without asking.
     *
     * @param isReload whether the page is being reloaded rather than left
     */
    default boolean onBeforeUnloadDialog(CefriumBrowser browser, String message,
            boolean isReload, CefriumBrowser.JsDialogCallback callback) {
        return false;
    }

    /**
     * A file input ({@code <input type=file>}) asks for files. Return true to
     * show your own chooser and answer through {@code callback}; {@code
     * params.createIntent()} gives the system picker's intent. Return false
     * for the SDK's chooser: the engine's on a surface browser (camera, photo
     * picker, filtered by {@code accept}), the system picker on an off-screen
     * browser's dialog activity.
     */
    default boolean onShowFileChooser(CefriumBrowser browser, CefriumFileChooserParams params,
            CefriumFileChooserCallback callback) {
        return false;
    }

    /**
     * The page asks for a context menu (a long press or a right click on a
     * link or an image). Return true to show your own and answer through
     * {@code menu}. Return false to leave it to a context-menu listener set
     * the old way, or to close it.
     */
    default boolean onContextMenu(CefriumBrowser browser, CefriumContextMenu menu) {
        return false;
    }

    /**
     * A key is about to reach the page. Return true to take it yourself: the
     * page does not get it. Like {@code WebViewClient.shouldOverrideKeyEvent},
     * with the Android key code and meta state. Keys an input method composes
     * (no key code of their own) are not reported.
     */
    default boolean shouldOverrideKeyEvent(CefriumBrowser browser,
            android.view.KeyEvent event) {
        return false;
    }

    /**
     * The page did not handle a key (no {@code preventDefault}), so the app
     * may: for example a shortcut. Like {@code WebViewClient.onUnhandledKeyEvent}.
     */
    default void onUnhandledKeyEvent(CefriumBrowser browser, android.view.KeyEvent event) {}

    /**
     * A page asks for permissions. Answer with {@code request.grant()} or
     * {@code request.deny()}, now or later (after asking the user). The
     * default denies.
     *
     * <p>Some requests never get here because the platform refuses them
     * first: geolocation when the app does not hold the location permission
     * or location is off, clipboard reads from a page that is not focused
     * (an off-screen browser needs {@link CefriumBrowser#setFocus}), and any
     * permission of a page with an opaque origin ({@link CefriumBrowser#loadHtml}).
     */
    default void onPermissionRequest(CefriumBrowser browser, CefriumPermissionRequest request) {
        request.deny();
    }

    /**
     * A request that was not answered yet is no longer wanted (the page
     * navigated away): close any prompt shown for it. Answering it now does
     * nothing.
     */
    default void onPermissionRequestCanceled(CefriumBrowser browser,
            CefriumPermissionRequest request) {}
}
