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

/**
 * Regression guard for the touch-automation API (CefriumBrowser.sendTap /
 * sendTouchDown/Move/Up) in WINDOWED (createWithSurface / ContentViewRenderView)
 * mode -- the mode the flagship browser uses. A tap must drive the full gesture
 * pipeline (via the content EventForwarder) so it synthesizes a click, not just
 * raw touch events. Hermetic: a loopback page reports (via the JS bridge) once it
 * has seen touchstart AND a synthesized click.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumTouchTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    private static final String PAGE =
            "<!doctype html><html><head>"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "</head><body style=\"margin:0;width:100vw;height:100vh;background:#eee\">"
            + "<div style=\"width:100%;height:100%\"></div><script>"
            + "var g={t:0,c:0},sent=false;"
            + "function chk(){if(!sent&&g.t&&g.c){sent=true;"
            + "window.cefriumQuery({request:'touchok'});}}"
            + "document.addEventListener('touchstart',function(){g.t=1;chk();},{passive:true});"
            + "document.addEventListener('click',function(){g.c=1;chk();});"
            + "</script></body></html>";

    @Test
    public void tapReachesWindowedRendererAndClicks() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch got = new CountDownLatch(1);
        final boolean[] tapped = {false};
        final LocalHttpServer server = new LocalHttpServer(PAGE);
        final Handler main = new Handler(Looper.getMainLooper());

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            activity.setContentView(browser.getSurfaceContainer());
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                if (request != null && request.contains("touchok")) {
                    callback.success("ack");
                    got.countDown();
                    return true;
                }
                return false;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !tapped[0]) {
                    tapped[0] = true;
                    main.postDelayed(() -> browser.sendTap(150, 300), 700);
                }
            });
            browser.loadUrl(server.url());
        });

        boolean ok = got.await(30, TimeUnit.SECONDS);
        server.close();
        Assert.assertTrue(
                "windowed browser never reported touchstart+click from the injected"
                        + " tap (gesture pipeline)", ok);
    }
}
