// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.ComponentHostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A file chooser for images offers the camera, and {@code capture} starts it:
 * the camera app writes the photo to a URI of the SDK's file provider, and
 * the page gets that photo. The camera is an ActivityMonitor.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumCameraCaptureTest {
    @Rule(order = 0)
    public GrantPermissionRule camera = GrantPermissionRule.grant(android.Manifest.permission.CAMERA);

    @Rule(order = 1)
    public ActivityTestRule<ComponentHostActivity> rule =
            new ActivityTestRule<>(ComponentHostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    // A 1x1 PNG.
    private static final byte[] PNG = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC",
            android.util.Base64.DEFAULT);

    private Instrumentation.ActivityMonitor monitor;

    @After
    public void tearDown() {
        if (monitor != null) InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);
    }

    private static String page(String attrs) {
        return "<!doctype html><html><head>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0'><input type=file id=f " + attrs
                + " style='position:fixed;left:0;top:0;width:100%;height:100%'>"
                + "<script>f.onchange = () => { const x = f.files[0];"
                + "  document.title = 'picked:' + x.size + ':' + x.type; };"
                + "f.oncancel = () => document.title = 'cancel';"
                + "document.title = 'ready';</script></body></html>";
    }

    private static String fileProvider() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()
                + ".cefrium.fileprovider";
    }

    @Test
    public void imageInputOffersTheCamera() throws Exception {
        AtomicReference<Intent> chooser = new AtomicReference<>();
        monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_CHOOSER.equals(intent.getAction())) return null;
                chooser.set(intent);
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor);

        Assert.assertEquals("cancel", tap(page("accept='image/*'")));
        Assert.assertNotNull("no chooser", chooser.get());
        android.os.Parcelable[] extra =
                chooser.get().getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        // The page's accept types reach the picker.
        Intent content = chooser.get().getParcelableExtra(Intent.EXTRA_INTENT);
        String[] types = content == null ? null
                : content.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
        Assert.assertTrue("mime types " + java.util.Arrays.toString(types),
                types != null && java.util.Arrays.asList(types).contains("image/*"));
        StringBuilder seen = new StringBuilder();
        boolean camera = false;
        if (extra != null) {
            for (android.os.Parcelable p : extra) {
                Intent i = (Intent) p;
                Uri out = i.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
                seen.append(i.getAction()).append(' ').append(out).append("; ");
                Assert.assertNotEquals("video offered for images: " + seen,
                        MediaStore.ACTION_VIDEO_CAPTURE, i.getAction());
                camera |= MediaStore.ACTION_IMAGE_CAPTURE.equals(i.getAction()) && out != null
                        && fileProvider().equals(out.getAuthority());
            }
        }
        Assert.assertTrue("no camera in the chooser: " + seen, camera);
    }

    @Test
    public void captureTakesAPhoto() throws Exception {
        AtomicReference<String> started = new AtomicReference<>("nothing");
        monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                String a = intent.getAction();
                if (Intent.ACTION_CHOOSER.equals(a)) {
                    started.set(a);
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                if (!MediaStore.ACTION_IMAGE_CAPTURE.equals(a)) return null;
                Uri out = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
                started.set(a + " " + out);
                try (OutputStream s = InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getContentResolver().openOutputStream(out)) {
                    s.write(PNG);
                } catch (Exception e) {
                    started.set(a + " " + out + " write failed: " + e);
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent());
            }
        };
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor);

        String t = tap(page("accept='image/*' capture=environment"));
        Assert.assertTrue(started.get(), started.get().startsWith(MediaStore.ACTION_IMAGE_CAPTURE
                + " content://" + fileProvider() + "/"));
        // The engine names the photo .jpg, so the page sees image/jpeg.
        Assert.assertTrue(started.get() + " -> " + t, t.startsWith("picked:" + PNG.length + ":image/"));
    }

    // Loads the page in a surface browser, taps the input, returns the title it ends with.
    private String tap(String html) throws Exception {
        Activity activity = rule.getActivity();
        AtomicReference<CefriumBrowser> b = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> title = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(html)) {
            activity.runOnUiThread(() -> {
                CefriumBrowser browser = browsers.createWithSurface(activity);
                activity.addContentView(browser.getSurfaceContainer(),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                browser.setOnTitleChangedListener(t -> {
                    if ("ready".equals(t)) ready.countDown();
                    if (t.startsWith("picked:") || t.equals("cancel")) {
                        title.set(t);
                        done.countDown();
                    }
                });
                b.set(browser);
                browser.loadUrl(server.url());
            });
            Assert.assertTrue("the page did not load", ready.await(30, TimeUnit.SECONDS));
            Thread.sleep(500);
            Handler main = new Handler(Looper.getMainLooper());
            main.post(() -> {
                CefriumBrowser browser = b.get();
                float d = activity.getResources().getDisplayMetrics().density;
                float x = browser.getSurfaceContainer().getWidth() / 2f / d;
                float y = browser.getSurfaceContainer().getHeight() / 2f / d;
                browser.sendTouchDown(x, y);
                main.postDelayed(() -> { if (!browser.isClosed()) browser.sendTouchUp(x, y); }, 150);
            });
            Assert.assertTrue("the input reported nothing", done.await(20, TimeUnit.SECONDS));
            return title.get();
        }
    }
}
