package com.cefrium.itest.sdk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pull-to-refresh enabled AFTER the WebContents connected still reloads, and
 * a disabled one does not.
 *
 * The browser app enables it after its first navigation. When the connect ran
 * on a 2 s timer the flag was already set by then; once the connect moved to
 * browser creation, the handler was bound only if the flag was set, and the
 * app silently lost the feature. The page counts its own loads in
 * sessionStorage and writes the count into the title.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPullToRefreshTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    private static final String PAGE =
            "<!doctype html><html><head>"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "</head><body style=\"margin:0;width:100vw;height:100vh;background:#eee\">"
            + "<script>var n=+(sessionStorage.getItem('n')||0)+1;"
            + "sessionStorage.setItem('n',n);document.title='load:'+n;</script>"
            + "</body></html>";

    /** Returns the highest load count seen after a pull with the flag as given. */
    private int loadsAfterPull(boolean enable) throws Exception {
        Activity activity = activityRule.getActivity();
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicInteger loads = new AtomicInteger();
        final AtomicReference<CefriumBrowser> created = new AtomicReference<>();
        final CountDownLatch firstLoad = new CountDownLatch(1);
        final CountDownLatch secondLoad = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
                created.set(browser);
                activity.setContentView(browser.getSurfaceContainer());
                browser.setOnTitleChangedListener(title -> {
                    if (title == null || !title.startsWith("load:")) return;
                    int n = Integer.parseInt(title.substring(5));
                    loads.accumulateAndGet(n, Math::max);
                    if (n == 1) firstLoad.countDown();
                    if (n >= 2) secondLoad.countDown();
                });
                browser.loadUrl(server.url());
            });
            Assert.assertTrue("the page never loaded", firstLoad.await(20, TimeUnit.SECONDS));
            // Enable only now, after the connect, as the browser app does.
            final CountDownLatch pulled = new CountDownLatch(1);
            main.postDelayed(() -> {
                CefriumBrowser b = created.get();
                b.setPullToRefreshEnabled(enable);
                float x = 300f;
                b.sendTouchDown(x, 200f);
                for (int i = 1; i <= 20; i++) {
                    final float y = 200f + i * 40f;
                    main.postDelayed(() -> b.sendTouchMove(x, y), i * 16L);
                }
                main.postDelayed(() -> {
                    b.sendTouchUp(x, 1000f);
                    pulled.countDown();
                }, 21 * 16L + 50);
            }, 1000);
            Assert.assertTrue("the pull was never injected", pulled.await(10, TimeUnit.SECONDS));
            secondLoad.await(enable ? 15 : 5, TimeUnit.SECONDS);
            return loads.get();
        } finally {
            activity.runOnUiThread(() -> {
                if (created.get() != null) created.get().close();
            });
        }
    }

    @Test
    public void pullReloadsWhenEnabledAfterConnect() throws Exception {
        Assert.assertTrue("pull-to-refresh enabled after the connect never reloaded",
                loadsAfterPull(true) >= 2);
    }

    @Test
    public void pullDoesNothingWhenDisabled() throws Exception {
        Assert.assertEquals("a disabled pull-to-refresh reloaded the page",
                1, loadsAfterPull(false));
    }
}
