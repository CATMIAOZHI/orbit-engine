// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.app.Activity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumRenderHandler;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * addDocumentStartJavaScript: the script defines window.__inj before the
 * page's own script reads it, so the page titles itself "seen:true" only if
 * the script ran first.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumDocumentStartScriptTest {
    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule = new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String SCRIPT = "window.__inj = 1;";
    private static final String PAGE = "<html><body><script>"
            + "document.title='seen:'+(window.__inj===1)+':'+location.host+location.search;"
            + "</script></body></html>";
    private static final Set<String> ANY = Collections.singleton("*");

    private CefriumBrowser mBrowser;
    private final AtomicReference<String> mTitle = new AtomicReference<>("");

    private void create(java.util.function.Consumer<CefriumBrowser> beforeLoad, String url)
            throws Exception {
        activityRule.getActivity().runOnUiThread(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
            b.setRenderHandler(new CefriumRenderHandler() {
                @Override
                public void onPaint(int width, int height, ByteBuffer px) {}
            });
            b.setOnTitleChangedListener(mTitle::set);
            if (beforeLoad != null) beforeLoad.accept(b);
            mBrowser = b;
            b.loadUrl(url);
        });
    }

    private void onMain(Runnable r) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        activityRule.getActivity().runOnUiThread(() -> {
            r.run();
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

    /**
     * The title of the PAGE loaded from {@code url}. Waiting for "seen:" alone
     * can return the previous document's title: its title event may arrive
     * after a new load has started.
     */
    private String waitFor(String url) throws Exception {
        android.net.Uri u = android.net.Uri.parse(url);
        String doc = ":" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "")
                + (u.getQuery() != null ? "?" + u.getQuery() : "");
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String t = mTitle.get();
            if (t.startsWith("seen:") && t.endsWith(doc)) return t;
            Thread.sleep(50);
        }
        return mTitle.get();
    }

    private void load(String url) throws Exception {
        mTitle.set("");
        onMain(() -> mBrowser.loadUrl(url));
    }

    @Test
    public void runsBeforeThePagesOwnScripts() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            create(b -> b.addDocumentStartJavaScript(SCRIPT, ANY), server.url());
            String title = waitFor(server.url());
            Assert.assertTrue(title, title.startsWith("seen:true"));
        }
    }

    @Test
    public void originRulesLimitWhereItRuns() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Set<String> only = Collections.singleton(server.origin());
            create(b -> b.addDocumentStartJavaScript(SCRIPT, only), server.url());
            Assert.assertTrue(waitFor(server.url()).startsWith("seen:true"));
            load(server.localhostUrl());
            String title = waitFor(server.localhostUrl());
            Assert.assertTrue(title, title.startsWith("seen:false"));
        }
    }

    // A new host can mean a new renderer process: the script must be there too.
    @Test
    public void followsTheBrowserToAnotherSite() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            create(b -> b.addDocumentStartJavaScript(SCRIPT, ANY), server.url());
            Assert.assertTrue(waitFor(server.url()).startsWith("seen:true"));
            load(server.localhostUrl());
            String title = waitFor(server.localhostUrl());
            Assert.assertTrue(title, title.startsWith("seen:true:localhost"));
        }
    }

    @Test
    public void addedAfterALoadAppliesToTheNextDocument() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            create(null, server.url());
            Assert.assertTrue(waitFor(server.url()).startsWith("seen:false"));
            onMain(() -> mBrowser.addDocumentStartJavaScript(SCRIPT, ANY));
            load(server.url() + "?again");
            String title = waitFor(server.url() + "?again");
            Assert.assertTrue(title, title.startsWith("seen:true"));
        }
    }

    @Test
    public void removeStopsItForLaterDocuments() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            AtomicReference<CefriumBrowser.ScriptHandler> handler = new AtomicReference<>();
            create(b -> handler.set(b.addDocumentStartJavaScript(SCRIPT, ANY)), server.url());
            Assert.assertTrue(waitFor(server.url()).startsWith("seen:true"));
            onMain(() -> handler.get().remove());
            load(server.url() + "?again");
            String title = waitFor(server.url() + "?again");
            Assert.assertTrue(title, title.startsWith("seen:false"));
        }
    }

    @Test
    public void runsInACrossOriginIframe() throws Exception {
        String child = "<html><body><script>"
                + "parent.postMessage('frame:'+(window.__inj===1),'*');</script></body></html>";
        try (LocalHttpServer frame = new LocalHttpServer(child)) {
            String parent = "<html><body><script>onmessage=function(e){document.title=e.data;};"
                    + "</script><iframe src='" + frame.localhostUrl() + "'></iframe></body></html>";
            try (LocalHttpServer server = new LocalHttpServer(parent)) {
                create(b -> b.addDocumentStartJavaScript(SCRIPT, ANY), server.url());
                Assert.assertEquals("frame:true", waitTitle("frame:"));
            }
        }
    }

    // The cosmetic-filter shape: a stylesheet in place before the page renders.
    @Test
    public void addsCssBeforeThePageHasAHead() throws Exception {
        String css = "const s=new CSSStyleSheet();s.replaceSync('.ad{display:none}');"
                + "document.adoptedStyleSheets=[...document.adoptedStyleSheets,s];";
        String page = "<html><body><div class=ad id=a>ad</div><script>"
                + "document.title='seen:'+getComputedStyle(a).display;</script></body></html>";
        try (LocalHttpServer server = new LocalHttpServer(page)) {
            create(b -> b.addDocumentStartJavaScript(css, ANY), server.url());
            Assert.assertEquals("seen:none", waitTitle("seen:"));
        }
    }

    @Test
    public void worksInASurfaceBrowser() throws Exception {
        try (LocalHttpServer server = new LocalHttpServer(PAGE)) {
            Activity activity = activityRule.getActivity();
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.track(CefriumBrowser.createWithSurface(activity, false));
                b.addDocumentStartJavaScript(SCRIPT, ANY);
                b.setOnTitleChangedListener(mTitle::set);
                b.loadUrl(server.url());
            });
            String title = waitFor(server.url());
            Assert.assertTrue(title, title.startsWith("seen:true"));
        }
    }

    @Test
    public void anInvalidRuleIsRefused() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        onMain(() -> {
            CefriumBrowser b = browsers.track(new CefriumBrowser(200, 200, 1.0f));
            try {
                b.addDocumentStartJavaScript(SCRIPT, Collections.singleton("https://exa*mple.com"));
                error.set("none");
            } catch (IllegalArgumentException e) {
                error.set(e.getMessage());
            }
        });
        Assert.assertTrue(error.get(), error.get().contains("is invalid"));
    }
}
