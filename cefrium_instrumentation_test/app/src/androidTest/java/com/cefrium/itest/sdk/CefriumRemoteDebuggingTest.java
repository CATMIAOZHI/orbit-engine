// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.HostActivity;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.Cefrium;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * FEATURE-082: Cefrium.setRemoteDebuggingEnabled opens Chrome's DevTools
 * server on <package>_devtools_remote, the socket chrome://inspect reaches
 * through adb, and closes it again. This process has the app's uid, which
 * the server lets in.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumRemoteDebuggingTest {

    @Rule
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    private static String socketName() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()
                + "_devtools_remote";
    }

    private void setEnabled(boolean enabled) throws Exception {
        activityRule.getActivity().runOnUiThread(() -> Cefrium.setRemoteDebuggingEnabled(enabled));
        Thread.sleep(1000);  // the server binds on its own thread
    }

    // GET /json/version over the abstract socket; null if nothing listens.
    private static String version() {
        try (LocalSocket s = new LocalSocket()) {
            s.connect(new LocalSocketAddress(socketName(), LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(5000);
            s.getOutputStream().write("GET /json/version HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.toString("UTF-8").contains("}")) break;
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    public void serverOpensAndCloses() throws Exception {
        setEnabled(false);
        Assert.assertNull("a DevTools socket was open before enabling", version());

        setEnabled(true);
        String v = version();
        Assert.assertNotNull("nothing listens on " + socketName(), v);
        Assert.assertTrue("not the DevTools HTTP server: " + v,
                v.contains("200 OK") && v.contains("\"Protocol-Version\""));

        setEnabled(false);
        Assert.assertNull("the socket stayed open after disabling", version());
    }
}
