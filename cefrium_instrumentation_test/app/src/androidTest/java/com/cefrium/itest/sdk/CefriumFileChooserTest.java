// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumDialogHandler;
import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.ComponentHostActivity;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Issue #22: {@code <input type=file>} with no CefriumDialogHandler. A surface
 * browser shows the engine's chooser, an off-screen one the system picker on
 * its dialog activity. The picker is an ActivityMonitor that answers with a
 * file in MediaStore, so the test covers the result coming back: the page
 * reads the file it was given. (A content URI of this app's own provider
 * would be refused by the engine's chooser, as Chrome refuses one: it would
 * let a page upload the app's private files.)
 */
@RunWith(AndroidJUnit4.class)
public class CefriumFileChooserTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> plainRule =
            new ActivityTestRule<>(HostActivity.class, false, false);

    @Rule(order = 0)
    public ActivityTestRule<ComponentHostActivity> androidxRule =
            new ActivityTestRule<>(ComponentHostActivity.class, false, false);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // The input covers the page, so a tap anywhere opens the chooser.
    private static final String PAGE = "<!doctype html><html><head>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0'><input type=file id=f accept='.txt'"
            + " style='position:fixed;left:0;top:0;width:100%;height:100%'>"
            + "<script>f.onchange = async () => { const x = f.files[0];"
            + "  document.title = 'picked:' + x.name + ':' + (await x.text()).trim(); };"
            + "f.oncancel = () => document.title = 'cancel';"
            + "document.title = 'ready';</script></body></html>";

    private static final String CONTENT = "the chosen file";

    // What Activity.requestPermissions starts, and what its result carries.
    private static final String REQUEST_PERMISSIONS =
            "android.content.pm.action.REQUEST_PERMISSIONS";
    private static final String PERMISSION_NAMES =
            "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    private static final String PERMISSION_RESULTS =
            "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS";

    private final String name = "cefrium-pick-" + System.nanoTime() + ".txt";
    private Uri file;
    private final AtomicInteger hits = new AtomicInteger();
    private final java.util.List<String> started =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private Instrumentation.ActivityMonitor picker;

    @Before
    public void setUp() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        ContentResolver cr = inst.getTargetContext().getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        file = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        Assert.assertNotNull("could not create the file to pick", file);
        try (OutputStream out = cr.openOutputStream(file)) {
            out.write(CONTENT.getBytes(StandardCharsets.UTF_8));
        }
        // Answers any picker the browser starts, by action, so no real one
        // ever opens (an IntentFilter would miss an intent with a MIME type).
        final Intent result = new Intent().setData(file);
        picker = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                String a = intent.getAction();
                started.add(String.valueOf(a));
                if (REQUEST_PERMISSIONS.equals(a)) {
                    // The user says no; the chooser must still come, without
                    // a camera option.
                    String[] names = intent.getStringArrayExtra(PERMISSION_NAMES);
                    int[] denied = new int[names == null ? 0 : names.length];
                    java.util.Arrays.fill(denied, android.content.pm.PackageManager.PERMISSION_DENIED);
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK, new Intent()
                            .putExtra(PERMISSION_NAMES, names)
                            .putExtra(PERMISSION_RESULTS, denied));
                }
                if (Intent.ACTION_CHOOSER.equals(a) || Intent.ACTION_GET_CONTENT.equals(a)
                        || Intent.ACTION_OPEN_DOCUMENT.equals(a)
                        || "android.provider.action.PICK_IMAGES".equals(a)) {
                    hits.incrementAndGet();
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK, result);
                }
                return null;
            }
        };
        inst.addMonitor(picker);
    }

    @After
    public void tearDown() {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        inst.removeMonitor(picker);
        if (file != null) inst.getTargetContext().getContentResolver().delete(file, null, null);
    }

    /** Surface, AndroidX activity: the engine's chooser, result via the registry. */
    @Test
    public void surfaceUsesTheEngineChooser() throws Exception {
        ComponentHostActivity activity = androidxRule.launchActivity(null);
        String t = pickIn(activity, true, null);
        Assert.assertEquals("picked:" + name + ":" + CONTENT, t);
    }

    /** Surface, plain Activity: the same, with the app forwarding the result. */
    @Test
    public void surfaceWithForwardedResult() throws Exception {
        HostActivity activity = plainRule.launchActivity(null);
        AtomicReference<CefriumBrowser> b = new AtomicReference<>();
        activity.setOnResult((code, result, data) -> {
            b.get().onActivityResult(code, result, data);
            return kotlin.Unit.INSTANCE;
        });
        activity.setOnPermissions((code, names, results) -> {
            b.get().onRequestPermissionsResult(code, names, results);
            return kotlin.Unit.INSTANCE;
        });
        String t = pickIn(activity, true, b);
        Assert.assertEquals("picked:" + name + ":" + CONTENT, t);
    }

    /** Off-screen with a dialog activity: the system picker on it. */
    @Test
    public void offscreenUsesTheDialogActivity() throws Exception {
        ComponentHostActivity activity = androidxRule.launchActivity(null);
        String t = pickIn(activity, false, null);
        Assert.assertEquals("picked:" + name + ":" + CONTENT, t);
    }

    /** Off-screen with no dialog activity: nowhere to show it, so it cancels. */
    @Test
    public void offscreenWithoutActivityCancels() throws Exception {
        ComponentHostActivity activity = androidxRule.launchActivity(null);
        String t = pick(activity, offscreen(activity, false), false);
        Assert.assertEquals("cancel", t);
        Assert.assertEquals("a picker was started", 0, hits.get());
    }

    /** An app handler still gets the dialog, and its answer is used. */
    @Test
    public void appHandlerWins() throws Exception {
        ComponentHostActivity activity = androidxRule.launchActivity(null);
        AtomicReference<CefriumBrowser> b = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = surface(activity);
            browser.setDialogHandler((mode, title, accept, callback) -> {
                asked.countDown();
                callback.select(new String[] {file.toString()});
            });
            b.set(browser);
        });
        String t = pick(activity, b, true);
        Assert.assertTrue(asked.await(1, TimeUnit.SECONDS));
        Assert.assertEquals("picked:" + name + ":" + CONTENT, t);
        Assert.assertEquals("the default picker ran too", 0, hits.get());
    }

    /**
     * A chrome client gets the typed parameters on the UI thread, answers
     * from another thread, and its answer is used; no picker starts.
     */
    @Test
    public void chromeClientAnswers() throws Exception {
        ComponentHostActivity activity = androidxRule.launchActivity(null);
        AtomicReference<CefriumBrowser> b = new AtomicReference<>();
        AtomicReference<String> seen = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = surface(activity);
            browser.setChromeClient(new com.cefrium.CefriumChromeClient() {
                @Override
                public boolean onShowFileChooser(CefriumBrowser x,
                        com.cefrium.CefriumFileChooserParams params,
                        com.cefrium.CefriumFileChooserCallback callback) {
                    seen.set((android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                            ? "" : "OFF-UI ") + params.getMode() + " " + params.getAcceptTypes()
                            + " " + params.createIntent().getAction());
                    new Thread(() -> {
                        callback.select(new String[] {file.toString()});
                        callback.cancel();  // only the first answer counts
                    }).start();
                    return true;
                }
            });
            b.set(browser);
        });
        String t = pick(activity, b, true);
        Assert.assertEquals("OPEN [.txt] " + Intent.ACTION_GET_CONTENT, seen.get());
        Assert.assertEquals("picked:" + name + ":" + CONTENT, t);
        Assert.assertEquals("a picker started although the client answered", 0, hits.get());
    }

    /** A chrome client that returns false gets the SDK's own chooser. */
    @Test
    public void chromeClientDeclinesToTheDefault() throws Exception {
        ComponentHostActivity activity = androidxRule.launchActivity(null);
        AtomicReference<CefriumBrowser> b = new AtomicReference<>();
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = surface(activity);
            browser.setChromeClient(new com.cefrium.CefriumChromeClient() {});
            b.set(browser);
        });
        String t = pick(activity, b, true);
        Assert.assertEquals("the default picker did not start", 1, hits.get());
        Assert.assertEquals("picked:" + name + ":" + CONTENT, t);
    }

    // ---- helpers ----

    private String pickIn(Activity activity, boolean surface,
            AtomicReference<CefriumBrowser> out) throws Exception {
        AtomicReference<CefriumBrowser> b = surface ? new AtomicReference<>()
                : offscreen(activity, true);
        if (surface) {
            CountDownLatch made = new CountDownLatch(1);
            activity.runOnUiThread(() -> {
                b.set(surface(activity));
                made.countDown();
            });
            Assert.assertTrue(made.await(10, TimeUnit.SECONDS));
        }
        if (out != null) out.set(b.get());
        String t = pick(activity, b, surface);
        Assert.assertEquals("the picker was not started", 1, hits.get());
        return t;
    }

    private CefriumBrowser surface(Activity activity) {
        CefriumBrowser browser = browsers.createWithSurface(activity);
        activity.addContentView(browser.getSurfaceContainer(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return browser;
    }

    private AtomicReference<CefriumBrowser> offscreen(Activity activity, boolean withActivity)
            throws Exception {
        AtomicReference<CefriumBrowser> b = new AtomicReference<>();
        CountDownLatch made = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.track(new CefriumBrowser(400, 300, 1.0f));
            if (withActivity) browser.setDialogActivity(activity);
            b.set(browser);
            made.countDown();
        });
        Assert.assertTrue(made.await(10, TimeUnit.SECONDS));
        return b;
    }

    // Loads the page, taps the input, and returns the title it ends with.
    private String pick(Activity activity, AtomicReference<CefriumBrowser> b, boolean surface)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> title = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            activity.runOnUiThread(() -> {
                b.get().setOnTitleChangedListener(t -> {
                    if ("ready".equals(t)) ready.countDown();
                    if (t.startsWith("picked:") || t.equals("cancel")) {
                        title.set(t);
                        done.countDown();
                    }
                });
                b.get().loadUrl(server.url());
            });
            Assert.assertTrue("the page did not load", ready.await(30, TimeUnit.SECONDS));
            Thread.sleep(500);
            Handler main = new Handler(Looper.getMainLooper());
            main.post(() -> {
                CefriumBrowser browser = b.get();
                float x = surface ? browser.getSurfaceContainer().getWidth() / 2f : 200;
                float y = surface ? browser.getSurfaceContainer().getHeight() / 2f : 150;
                browser.sendTouchDown(x, y);
                main.postDelayed(() -> browser.sendTouchUp(x, y), 150);
            });
            boolean reported = done.await(20, TimeUnit.SECONDS);
            Assert.assertTrue("the input reported nothing; picker hits " + hits.get()
                    + ", activities started " + started, reported);
            return title.get();
        }
    }
}
