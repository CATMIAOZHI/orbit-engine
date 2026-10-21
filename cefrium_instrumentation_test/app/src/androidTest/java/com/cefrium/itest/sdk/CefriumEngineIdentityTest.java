package com.cefrium.itest.sdk;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.Cefrium;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What the engine tells the web about itself (SC-ei-01, SC-ei-02).
 *
 * Since Chromium 156, version_info on Android returns the host APK's
 * versionName, so every consumer advertised its own app version as the Chrome
 * version. This app's versionName is "1.0": if that regression returns, the
 * User-Agent, the Chromium brand and uaFullVersion read "1" or "1.0" here.
 * The expected value is Cefrium.getChromiumVersion(), so the test also proves
 * that constant matches the engine actually running.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumEngineIdentityTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // Loopback is a secure context, so navigator.userAgentData is exposed.
    private static final String PAGE = "<html><body><script>"
            + "var m=navigator.userAgent.match(/Chrome\\/([0-9.]+)/);"
            + "var ua=m?m[1]:'none';"
            + "var b=(navigator.userAgentData&&navigator.userAgentData.brands||[])"
            + ".filter(function(x){return x.brand==='Chromium';});"
            + "var major=b.length?b[0].version:'none';"
            + "if(!navigator.userAgentData){document.title='id:'+ua+'|'+major+'|nouad';}"
            + "else navigator.userAgentData.getHighEntropyValues(['uaFullVersion'])"
            + ".then(function(v){document.title='id:'+ua+'|'+major+'|'+v.uaFullVersion;},"
            + "function(e){document.title='id:'+ua+'|'+major+'|err';});"
            + "</script></body></html>";

    @Test
    public void userAgentAndClientHintsCarryTheEngineVersion() throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<String> seen = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                com.cefrium.CefriumBrowser b = browsers.createWithSurface(activity);
                b.setOnTitleChangedListener(title -> {
                    if (title != null && title.startsWith("id:")) {
                        seen.set(title);
                        done.countDown();
                    }
                });
                b.loadUrl(server.url());
            });
            Assert.assertTrue("the probe page never reported", done.await(20, TimeUnit.SECONDS));
        }
        String engine = Cefrium.getChromiumVersion();
        String major = engine.substring(0, engine.indexOf('.'));
        Assert.assertEquals("User-Agent Chrome token | Chromium brand | uaFullVersion",
                "id:" + engine + "|" + major + "|" + engine, seen.get());
    }
}
