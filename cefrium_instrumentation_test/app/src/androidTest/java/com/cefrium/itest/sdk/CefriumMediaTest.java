package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-034 (F2): getUserMedia (camera + mic / WebRTC). The request must be
 * routed to the app's PermissionHandler via OnRequestMediaAccessPermission and,
 * when granted, must NOT fail with NotAllowedError (the permission gate is
 * passed). On a device with a camera the stream opens (gum:ok).
 */
@RunWith(AndroidJUnit4.class)
public class CefriumMediaTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule
    public GrantPermissionRule mediaPerms = GrantPermissionRule.grant(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO);

    @Test
    public void testGetUserMediaGrantedReachesCapture() throws Exception {
        Activity activity = activityRule.getActivity();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> gum = new AtomicReference<>(null);
        final AtomicInteger seenMask = new AtomicInteger(0);
        final boolean[] probed = {false};

        activity.runOnUiThread(() -> {
            CefriumBrowser browser = CefriumBrowser.createWithSurface(activity);
            browser.setPermissionHandler((origin, permissions, cb) -> {
                seenMask.set(seenMask.get() | permissions);
                cb.respond(true);
            });
            browser.setQueryHandler((queryId, request, origin, callback) -> {
                if (request != null && request.startsWith("gum:")) {
                    gum.set(request);
                    callback.success("ack");
                    done.countDown();
                    return true;
                }
                return false;
            });
            browser.setOnLoadingStateChangedListener((loading, back, fwd) -> {
                if (!loading && !probed[0]) {
                    probed[0] = true;
                    browser.evaluateJavaScript(
                        "navigator.mediaDevices.getUserMedia({video:true,audio:true})"
                        + ".then(function(s){window.cefriumQuery({request:'gum:ok:'+s.getTracks().length});})"
                        + ".catch(function(e){window.cefriumQuery({request:'gum:err:'+e.name});});");
                }
            });
            browser.loadUrl("https://cefrium.com/");
        });

        Assert.assertTrue("getUserMedia produced no result", done.await(40, TimeUnit.SECONDS));
        // The media permission must have been routed to our handler with the
        // camera and microphone bits.
        Assert.assertTrue("media permission not routed to handler (mask=" + seenMask.get() + ")",
                (seenMask.get() & CefriumBrowser.PERMISSION_CAMERA) != 0
                        && (seenMask.get() & CefriumBrowser.PERMISSION_MICROPHONE) != 0);
        // Granted: must not be a permission denial.
        Assert.assertFalse("granted getUserMedia should not be NotAllowedError: " + gum.get(),
                gum.get().contains("NotAllowedError"));
    }
}
