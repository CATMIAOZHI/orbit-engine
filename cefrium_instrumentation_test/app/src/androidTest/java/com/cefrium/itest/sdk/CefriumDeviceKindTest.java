// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumDeviceKind;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A device chooser says which Web API asked, as an enum: a WebUSB request
 * reaches the handler as USB (and the deprecated int as DEVICE_USB), a WebHID
 * request as HID.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDeviceKindTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    @Test
    @SuppressWarnings("deprecation")
    public void webUsbIsUsb() throws Exception {
        Assert.assertEquals("USB " + CefriumBrowser.DEVICE_USB,
                kindOf("navigator.usb.requestDevice({filters: []})"));
    }

    /** WebHID, exposed on Android since Chromium 158, reaches the same handler. */
    @Test
    public void webHidIsHid() throws Exception {
        Assert.assertEquals("HID 3", kindOf("navigator.hid.requestDevice({filters: []})"));
    }

    @SuppressWarnings("deprecation")
    private String kindOf(String request) throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> title = new AtomicReference<>("");
        AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
        try (LocalHttpServer server = new LocalHttpServer("<title>ready</title>")) {
            activityRule.getActivity().runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activityRule.getActivity());
                browser.set(b);
                activityRule.getActivity().addContentView(b.getSurfaceContainer(),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                b.setOnTitleChangedListener(title::set);
                b.setDeviceChooserHandler(new CefriumBrowser.DeviceChooserHandler() {
                    @Override
                    public void onShowDeviceChooser(CefriumBrowser.DeviceChooserSession s) {
                        seen.set(s.getKind() + " " + s.getType());
                        s.cancel();
                        shown.countDown();
                    }

                    @Override
                    public void onDeviceAdded(CefriumBrowser.DeviceChooserSession s,
                            CefriumBrowser.ChooserDevice device) {}

                    @Override
                    public void onDeviceChooserClosed(CefriumBrowser.DeviceChooserSession s) {}
                });
                b.loadUrl(server.url());
            });
            long end = System.currentTimeMillis() + 20000;
            while (!title.get().equals("ready") && System.currentTimeMillis() < end) {
                Thread.sleep(100);
            }
            activityRule.getActivity().runOnUiThread(() -> browser.get().executeDevToolsMethod(
                    "Runtime.evaluate",
                    "{\"expression\":\"" + request + ".catch(() => 0);1\","
                            + "\"userGesture\":true}", (ok, r) -> {}));
            Assert.assertTrue("no device chooser", shown.await(15, TimeUnit.SECONDS));
            return seen.get();
        }
    }
}
