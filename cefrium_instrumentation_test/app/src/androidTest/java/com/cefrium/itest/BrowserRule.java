package com.cefrium.itest;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.cefrium.CefriumBrowser;

import org.junit.rules.ExternalResource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Creates the test's browsers, closes every one of them after the test, and
 * fails the test if a native browser is still alive afterwards.
 *
 * Why: browsers left open by one test kept renderers alive and finished closing
 * during later tests, which moved shared counters (closedBrowserCount) and once
 * passed a leak check for the wrong browser. Closing in each test by hand was
 * forgotten in half of them. Declare it after the activity rule, with
 * {@code @Rule(order = 1)}, so the browsers close before the activity finishes.
 */
public final class BrowserRule extends ExternalResource {

    private final List<CefriumBrowser> created = new CopyOnWriteArrayList<>();
    private int liveBefore;

    public CefriumBrowser createWithSurface(Activity activity) {
        return track(CefriumBrowser.createWithSurface(activity));
    }

    public CefriumBrowser createWithSurface(Activity activity, boolean incognito) {
        return track(CefriumBrowser.createWithSurface(activity, incognito));
    }

    public CefriumBrowser createWithSurface(Activity activity, com.cefrium.CefriumProfile profile) {
        return track(CefriumBrowser.createWithSurface(activity, profile));
    }

    /** Closes {@code browser} after the test, like the ones created here. */
    public CefriumBrowser track(CefriumBrowser browser) {
        created.add(browser);
        return browser;
    }

    @Override
    protected void before() {
        liveBefore = CefriumBrowser.liveBrowserCount();
    }

    @Override
    protected void after() {
        CountDownLatch closed = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            for (CefriumBrowser b : created) {
                b.close();  // idempotent: a test may already have closed it
            }
            closed.countDown();
        });
        try {
            if (!closed.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("closing the test's browsers timed out");
            }
            int live = CefriumBrowser.liveBrowserCount();
            for (int i = 0; i < 100 && live > liveBefore; i++) {
                Thread.sleep(100);
                live = CefriumBrowser.liveBrowserCount();
            }
            if (live > liveBefore) {
                throw new AssertionError((live - liveBefore)
                        + " native browser(s) still alive 10 s after the test closed "
                        + created.size() + ": a browser was created outside this rule, "
                        + "or close() did not complete");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            created.clear();
        }
    }
}
