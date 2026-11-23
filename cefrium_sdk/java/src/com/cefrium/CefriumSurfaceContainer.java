// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.core.view.NestedScrollingChild3;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;

import org.chromium.content_public.browser.RenderCoordinates;
import org.chromium.content_public.browser.WebContents;

/**
 * FrameLayout that hosts the browser surface and participates in Android nested
 * scrolling. The embedded page handles vertical drags itself; when
 * it reaches a vertical scroll boundary (top or bottom), the remaining drag is
 * handed off to a {@code NestedScrollingParent} (e.g. {@code NestedScrollView},
 * {@code SwipeRefreshLayout}, or a Compose scroll container hosting the
 * {@code AndroidView}). This lets a CefriumView live inside a scrollable
 * container without the browser swallowing the parent's scroll.
 *
 * <p>Drag interop only; fling forwarding is not handled (the page keeps its own
 * flings).
 */
public class CefriumSurfaceContainer extends FrameLayout
        implements NestedScrollingChild3 {

    private final NestedScrollingChildHelper mHelper;
    private final int mTouchSlop;
    private WebContents mWebContents;
    private float mLastY;
    private final int[] mConsumed = new int[2];
    private final int[] mOffset = new int[2];

    public CefriumSurfaceContainer(Context context) {
        super(context);
        mHelper = new NestedScrollingChildHelper(this);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setNestedScrollingEnabled(true);
    }

    /** Binds the WebContents whose scroll position gates the handoff. */
    void setWebContents(WebContents webContents) {
        mWebContents = webContents;
    }

    /** Receives this view's window-focus transitions. */
    interface FocusSink {
        void onWindowFocus(boolean focused);
    }

    private FocusSink mFocusSink;

    /** Binds the sink that forwards window focus to CefBrowserHost::SetFocus. */
    void setFocusSink(FocusSink sink) {
        mFocusSink = sink;
        // The window may already be focused by the time we are bound (the view is
        // created and attached before the native browser exists), and in that case
        // no further onWindowFocusChanged will arrive. Seed from the current state
        // instead of waiting for an edge that already happened.
        if (sink != null && hasWindowFocus() && !anotherViewHasFocus()) {
            sink.onWindowFocus(true);
        }
    }

    // Focusing the page requests Android focus for the browser's view
    // (RenderWidgetHostViewAndroid::Focus). When the window regains focus while
    // another view holds it, a native text field for example, the page must not
    // take it: the user is typing there. A tap on the page still focuses it.
    private boolean anotherViewHasFocus() {
        View focused = getRootView().findFocus();
        if (focused == null) return false;
        for (ViewParent p = focused.getParent(); p != null; p = p.getParent()) {
            if (p == this) return false;
        }
        return focused != this;
    }

    // Issue #10: nothing used to deliver focus to the browser, so the page's
    // document was NEVER focused -- measured on device: document.hasFocus() was
    // false in the on-screen flagship browser, and navigator.clipboard.readText()
    // rejected with "Document is not focused." Blink checks document focus before
    // any permission check, so this looked like a permission-handler bug.
    //
    // The desktop ports drive CefBrowserHost::SetFocus from their platform focus
    // events; on Android this view IS the platform, so it owns that job.
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        FocusSink sink = mFocusSink;
        if (sink != null && !(hasWindowFocus && anotherViewHasFocus())) {
            sink.onWindowFocus(hasWindowFocus);
        }
    }

    private boolean pageAtTop() {
        if (mWebContents == null) return true;
        RenderCoordinates rc = RenderCoordinates.fromWebContents(mWebContents);
        return rc == null || rc.getScrollYPixInt() <= 0;
    }

    private boolean pageAtBottom() {
        if (mWebContents == null) return false;
        RenderCoordinates rc = RenderCoordinates.fromWebContents(mWebContents);
        return rc != null && rc.getScrollYPixInt() >= rc.getMaxVerticalScrollPixInt();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mLastY = ev.getY();
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH);
                break;
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() - mLastY;
                if (Math.abs(dy) > mTouchSlop
                        && hasNestedScrollingParent(ViewCompat.TYPE_TOUCH)) {
                    // dy > 0: finger down → reveal content above → matters at top.
                    // dy < 0: finger up → scroll page down → matters at bottom.
                    if ((dy > 0 && pageAtTop()) || (dy < 0 && pageAtBottom())) {
                        // Steal the gesture; the browser ContentView gets CANCEL.
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopNestedScroll(ViewCompat.TYPE_TOUCH);
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                int y = (int) ev.getY();
                int dy = (int) (mLastY - y);  // +dy = scroll page down (finger up)
                mLastY = y;
                if (dispatchNestedPreScroll(0, dy, mConsumed, mOffset,
                        ViewCompat.TYPE_TOUCH)) {
                    dy -= mConsumed[1];
                }
                dispatchNestedScroll(0, 0, 0, dy, mOffset, ViewCompat.TYPE_TOUCH,
                        mConsumed);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                stopNestedScroll(ViewCompat.TYPE_TOUCH);
                break;
            default:
                break;
        }
        return super.onTouchEvent(ev);
    }

    // ── NestedScrollingChild3 (delegated to the helper) ──

    @Override
    public void setNestedScrollingEnabled(boolean enabled) {
        mHelper.setNestedScrollingEnabled(enabled);
    }

    @Override
    public boolean isNestedScrollingEnabled() {
        return mHelper.isNestedScrollingEnabled();
    }

    @Override
    public boolean startNestedScroll(int axes) {
        return mHelper.startNestedScroll(axes);
    }

    @Override
    public void stopNestedScroll() {
        mHelper.stopNestedScroll();
    }

    @Override
    public boolean hasNestedScrollingParent() {
        return mHelper.hasNestedScrollingParent();
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed, int[] offsetInWindow) {
        return mHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed,
                dyUnconsumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed,
            int[] offsetInWindow) {
        return mHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow);
    }

    @Override
    public boolean dispatchNestedFling(float velocityX, float velocityY,
            boolean consumed) {
        return mHelper.dispatchNestedFling(velocityX, velocityY, consumed);
    }

    @Override
    public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
        return mHelper.dispatchNestedPreFling(velocityX, velocityY);
    }

    @Override
    public boolean startNestedScroll(int axes, int type) {
        return mHelper.startNestedScroll(axes, type);
    }

    @Override
    public void stopNestedScroll(int type) {
        mHelper.stopNestedScroll(type);
    }

    @Override
    public boolean hasNestedScrollingParent(int type) {
        return mHelper.hasNestedScrollingParent(type);
    }

    @Override
    public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed, int[] offsetInWindow, int type) {
        return mHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed,
                dyUnconsumed, offsetInWindow, type);
    }

    @Override
    public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed,
            int[] offsetInWindow, int type) {
        return mHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow,
                type);
    }

    @Override
    public void dispatchNestedScroll(int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed, int[] offsetInWindow, int type,
            int[] consumed) {
        mHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed,
                dyUnconsumed, offsetInWindow, type, consumed);
    }
}
