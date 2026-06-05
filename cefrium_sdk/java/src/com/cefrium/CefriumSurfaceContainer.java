// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.core.view.NestedScrollingChild3;
import androidx.core.view.NestedScrollingChildHelper;
import androidx.core.view.ViewCompat;

import org.chromium.content_public.browser.RenderCoordinates;
import org.chromium.content_public.browser.WebContents;

/**
 * FrameLayout that hosts the browser surface and participates in Android nested
 * scrolling (FEATURE-033). The embedded page handles vertical drags itself; when
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
