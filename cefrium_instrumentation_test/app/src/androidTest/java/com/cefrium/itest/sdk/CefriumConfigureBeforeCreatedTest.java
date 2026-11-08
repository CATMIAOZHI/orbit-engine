// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The off-screen constructor creates the native browser asynchronously. Issue
 * #18: configuration called right after it, in the same main-thread task, was
 * dropped without a word (setUserAgentOverride, setJavaScriptEnabled,
 * setContentSetting) or failed (executeDevToolsMethod). Each call must take
 * effect once the browser exists.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumConfigureBeforeCreatedTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private final AtomicReference<String> mTitle = new AtomicReference<>("");

    /** Constructs an off-screen browser and runs |configure| in the same task. */
    private void openConfigured(String url, Consumer<CefriumBrowser> configure) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}
            });
            b.setOnTitleChangedListener(mTitle::set);
            configure.accept(b);
            if (url != null) b.loadUrl(url);
            done.countDown();
        });
        done.await();
    }

    private String waitTitle(String prefix) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!mTitle.get().startsWith(prefix) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        return mTitle.get();
    }

    @Test
    public void devToolsMethodRunsOnceTheBrowserExists() throws Exception {
        final AtomicReference<String> result = new AtomicReference<>();
        final CountDownLatch answered = new CountDownLatch(1);
        // An off-screen browser is created by its first loadUrl.
        try (LocalHttpServer server = new LocalHttpServer("<title>page</title>")) {
            openConfigured(server.url(), b -> b.executeDevToolsMethod("Runtime.evaluate",
                    "{\"expression\":\"6*7\"}", (ok, json) -> {
                        result.set(ok + " " + json);
                        answered.countDown();
                    }));
            Assert.assertTrue("no answer in 20 s", answered.await(20, TimeUnit.SECONDS));
        }
        String r = result.get();
        Assert.assertTrue(r, r.startsWith("true "));
        Assert.assertEquals(r, 42, new JSONObject(r.substring(5))
                .getJSONObject("result").getInt("value"));
    }

    @Test
    public void userAgentOverrideReachesTheFirstPage() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(
                "<script>document.title='ua:'+navigator.userAgent</script>")) {
            openConfigured(server.url(), b -> b.setUserAgentOverride("CefriumUA/1"));
            Assert.assertEquals("ua:CefriumUA/1", waitTitle("ua:"));
        }
    }

    @Test
    public void javaScriptDisabledHoldsForTheFirstPage() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(
                "<title>static</title><script>document.title='ran'</script>")) {
            openConfigured(server.url(), b -> b.setJavaScriptEnabled(false));
            String title = waitTitle("static");
            Thread.sleep(1000);  // a script that ran late would have changed it
            Assert.assertEquals("static", mTitle.get());
            Assert.assertEquals("static", title);
        }
    }

    @Test
    public void contentSettingHoldsForTheFirstPage() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(
                "<script>document.title='perm:'+Notification.permission</script>")) {
            openConfigured(server.url(), b -> b.setContentSetting(server.origin(),
                    CefriumBrowser.PERMISSION_NOTIFICATIONS, CefriumBrowser.SETTING_BLOCK));
            Assert.assertEquals("perm:denied", waitTitle("perm:"));
        }
    }
}
