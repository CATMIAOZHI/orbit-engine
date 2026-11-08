// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.os.Bundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-084: one public CefriumBrowser method in one situation, driven by
 * instrumentation arguments (method, mode) so that a native crash ends only
 * this run. The outcome is appended to files/contract.txt. Without arguments
 * it does nothing: the suite skips it; the sweep script drives it.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumContractSweepTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String TAG = "CefriumContract";

    @Test
    public void sweep() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String name = args.getString("method");
        String mode = args.getString("mode");
        if (name == null || mode == null) {
            contractHolds();
            return;
        }
        Method method = null;
        for (Method m : CefriumBrowser.class.getMethods()) {
            if (m.getDeclaringClass() == CefriumBrowser.class
                    && !Modifier.isStatic(m.getModifiers())
                    && signature(m).equals(name)) {
                method = m;
            }
        }
        if (method == null) {
            record(name, mode, "NO_SUCH_METHOD");
            return;
        }
        final Method target = method;
        try (LocalHttpServer server = new LocalHttpServer("<title>page</title>")) {
            final AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
            final CountDownLatch loaded = new CountDownLatch(1);
            onMain(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
                b.setRenderHandler(new CefriumRenderHandler() {
                    @Override
                    public void onPaint(int width, int height, ByteBuffer px) {}
                });
                browser.set(b);
                if (!"before".equals(mode)) {
                    b.setOnTitleChangedListener(t -> {
                        if ("page".equals(t)) loaded.countDown();
                    });
                    b.loadUrl(server.url());
                }
            });
            if (!"before".equals(mode)) {
                loaded.await(20, TimeUnit.SECONDS);
            }
            final AtomicReference<String> outcome = new AtomicReference<>();
            Runnable call = () -> outcome.set(invoke(target, browser.get()));
            switch (mode) {
                case "closed":
                    onMain(() -> {
                        browser.get().close();
                        call.run();
                    });
                    break;
                case "offmain":
                    Thread t = new Thread(call);
                    t.start();
                    t.join(10_000);
                    break;
                default:  // before: right after the constructor, same task
                    onMain(() -> {
                        CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
                        browser.set(b);
                        call.run();
                    });
                    break;
            }
            // A crash shows up a little later: give it time before recording.
            Thread.sleep(1500);
            record(name, mode, outcome.get() == null ? "TIMEOUT" : outcome.get());
        }
    }

    /**
     * The contract, in one process: off the main thread every public method
     * throws IllegalStateException (executeDevToolsMethod posts instead);
     * after close() and before the browser exists none throws anything else.
     * A method that crashes instead ends the suite run.
     */
    private void contractHolds() throws Exception {
        java.util.List<String> broken = new java.util.ArrayList<>();
        java.util.List<Method> methods = new java.util.ArrayList<>();
        for (Method m : CefriumBrowser.class.getMethods()) {
            if (m.getDeclaringClass() == CefriumBrowser.class
                    && !Modifier.isStatic(m.getModifiers())
                    && !m.getName().equals("close")) {
                methods.add(m);
            }
        }
        try (LocalHttpServer server = new LocalHttpServer("<title>page</title>")) {
            final AtomicReference<CefriumBrowser> live = new AtomicReference<>();
            final CountDownLatch loaded = new CountDownLatch(1);
            onMain(() -> {
                CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
                b.setRenderHandler(new CefriumRenderHandler() {
                    @Override
                    public void onPaint(int width, int height, ByteBuffer px) {}
                });
                b.setOnTitleChangedListener(t -> {
                    if ("page".equals(t)) loaded.countDown();
                });
                b.loadUrl(server.url());
                live.set(b);
            });
            loaded.await(20, TimeUnit.SECONDS);
            for (Method m : methods) {
                final AtomicReference<String> r = new AtomicReference<>();
                Thread t = new Thread(() -> r.set(invoke(m, live.get())));
                t.start();
                t.join(10_000);
                boolean posts = m.getName().equals("executeDevToolsMethod");
                String o = r.get();
                if (posts ? !"RETURNED".equals(o)
                        : o == null || !o.startsWith("THREW IllegalStateException")
                                || !o.contains("main thread")) {
                    broken.add("offmain " + signature(m) + ": " + o);
                }
            }
            // One real browser, closed: nothing may reach native after close().
            onMain(() -> live.get().close());
            Thread.sleep(500);
            for (String mode : new String[] {"closed", "before"}) {
                for (Method m : methods) {
                    final AtomicReference<String> r = new AtomicReference<>();
                    onMain(() -> {
                        CefriumBrowser b = mode.equals("closed") ? live.get()
                                : browsers.track(new CefriumBrowser(200, 200, 1.0f));
                        r.set(invoke(m, b));
                    });
                    String o = r.get();
                    if (o == null || o.startsWith("HARNESS") || (o.startsWith("THREW")
                            && !o.startsWith("THREW IllegalStateException")
                            && !o.startsWith("THREW IllegalArgumentException"))) {
                        broken.add(mode + " " + signature(m) + ": " + o);
                    }
                }
            }
        }
        org.junit.Assert.assertTrue(String.join("\n", broken), broken.isEmpty());
    }

    private static String invoke(Method m, CefriumBrowser b) {
        Object[] values = new Object[m.getParameterCount()];
        Class<?>[] types = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) values[i] = defaultValue(types[i]);
        try {
            m.invoke(b, values);
            return "RETURNED";
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            return "THREW " + c.getClass().getSimpleName() + ": " + c.getMessage();
        } catch (Throwable e) {
            return "HARNESS " + e;
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == char.class) return 'a';
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 1.0f;
        if (t == double.class) return 0.0;
        if (t == String.class) return "about:blank";
        if (t == byte[].class) return new byte[0];
        if (t == String[].class) return new String[0];
        if (t == java.util.Set.class) return java.util.Collections.singleton("*");
        return null;
    }

    static String signature(Method m) {
        StringBuilder s = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) s.append(',');
            s.append(p[i].getSimpleName());
        }
        return s.append(')').toString();
    }

    private void onMain(Runnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        done.await(30, TimeUnit.SECONDS);
    }

    private void record(String name, String mode, String outcome) throws Exception {
        File f = new File(activityRule.getActivity().getFilesDir(), "contract.txt");
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(mode + "\t" + name + "\t" + outcome.replace('\n', ' ') + "\n");
        }
        Log.i(TAG, mode + " " + name + " " + outcome);
    }
}
