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
import com.cefrium.CefriumProfile;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * FEATURE-071: per-site data. One site's data is listed and cleared without
 * touching another site's.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumSiteDataTest {

    @Rule(order = 0)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 1)
    public final BrowserRule browsers = new BrowserRule();

    // ?write stores a cookie, localStorage, IndexedDB, Cache Storage and a
    // service worker; every load reports which of them it can see.
    private static final String PAGE = "<html><body><script>"
            + "async function run(){"
            + "if(location.search==='?write'){"
            + "document.cookie='s=1; max-age=86400; path=/';"
            + "localStorage.setItem('s','1');"
            + "await new Promise(function(res,rej){var r=indexedDB.open('db',1);"
            + "r.onupgradeneeded=function(){r.result.createObjectStore('o');};"
            + "r.onsuccess=function(){var t=r.result.transaction('o','readwrite');"
            + "t.objectStore('o').put('v','k');t.oncomplete=function(){r.result.close();res();};};"
            + "r.onerror=rej;});"
            + "var c=await caches.open('c');await c.put('/x',new Response('payload'));"
            + "await navigator.serviceWorker.register('/sw.js');"
            + "await navigator.serviceWorker.ready;}"
            + "var ck=document.cookie.indexOf('s=1')>=0;"
            + "var ls=localStorage.getItem('s')==='1';"
            + "var idb=await new Promise(function(res){var r=indexedDB.open('db',1);"
            + "r.onupgradeneeded=function(){r.result.createObjectStore('o');};"
            + "r.onsuccess=function(){var db=r.result;"
            + "var g=db.transaction('o').objectStore('o').get('k');"
            + "g.onsuccess=function(){db.close();res(g.result==='v');};"
            + "g.onerror=function(){db.close();res(false);};};"
            + "r.onerror=function(){res(false);};});"
            + "var cs=await caches.has('c');"
            + "var sw=(await navigator.serviceWorker.getRegistrations()).length>0;"
            + "document.title='site:cookie='+ck+',ls='+ls+',idb='+idb+',cache='+cs+',sw='+sw;}"
            + "run().catch(function(e){document.title='site:error:'+e;});"
            + "</script></body></html>";
    private static final String SW = "self.addEventListener('fetch',function(){});";

    private static final String ALL = "site:cookie=true,ls=true,idb=true,cache=true,sw=true";
    private static final String NONE = "site:cookie=false,ls=false,idb=false,cache=false,sw=false";

    private String visit(CefriumProfile profile, String url) throws Exception {
        Activity activity = activityRule.getActivity();
        final AtomicReference<String> seen = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            CefriumBrowser browser = browsers.createWithSurface(activity, profile);
            browser.setOnTitleChangedListener(t -> {
                if (t != null && t.startsWith("site:")) {
                    seen.set(t);
                    done.countDown();
                }
            });
            browser.loadUrl(url);
        });
        Assert.assertTrue("the page never reported for " + url, done.await(30, TimeUnit.SECONDS));
        return seen.get();
    }

    private List<CefriumProfile.SiteData> list(CefriumProfile profile) throws Exception {
        final AtomicReference<List<CefriumProfile.SiteData>> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        profile.listSiteData(sites -> {
            result.set(sites);
            done.countDown();
        });
        Assert.assertTrue("listSiteData never answered", done.await(20, TimeUnit.SECONDS));
        return result.get();
    }

    private static CefriumProfile.SiteData find(List<CefriumProfile.SiteData> sites, String host) {
        for (CefriumProfile.SiteData d : sites) {
            if (d.site.equals(host) || d.site.contains("://" + host + ":")
                    || d.site.contains("://" + host + "/") || d.site.endsWith("://" + host)) {
                return d;
            }
        }
        return null;
    }

    @Test
    public void clearingOneSiteLeavesTheOtherIntact() throws Exception {
        CefriumProfile profile = CefriumProfile.get("sites-" + UUID.randomUUID().toString().substring(0, 8));
        try (LocalHttpServer server = new LocalHttpServer(PAGE, "/sw.js", SW)) {
            // Two hosts, not two ports: cookies are per host.
            String a = server.url();
            String b = server.localhostUrl();
            Assert.assertEquals(ALL, visit(profile, a + "?write"));
            Assert.assertEquals(ALL, visit(profile, b + "?write"));

            List<CefriumProfile.SiteData> before = list(profile);
            CefriumProfile.SiteData listedA = find(before, "127.0.0.1");
            Assert.assertNotNull("site A is not listed: " + describe(before), listedA);
            Assert.assertNotNull("site B is not listed: " + describe(before), find(before, "localhost"));
            Assert.assertTrue("site A reports no storage: " + describe(before),
                    sumBytes(before, "127.0.0.1") > 0);

            final CountDownLatch cleared = new CountDownLatch(1);
            profile.clearSiteData(server.origin(), cleared::countDown);
            Assert.assertTrue("clearSiteData never finished", cleared.await(20, TimeUnit.SECONDS));

            // The list first: the check page below opens IndexedDB, which
            // creates a database for A again.
            List<CefriumProfile.SiteData> after = list(profile);
            Assert.assertNull("site A is still listed: " + describe(after), find(after, "127.0.0.1"));
            Assert.assertNotNull("site B vanished from the list: " + describe(after),
                    find(after, "localhost"));

            Assert.assertEquals("site A still has data", NONE, visit(profile, a));
            Assert.assertEquals("clearing A touched B", ALL, visit(profile, b));
        }
    }

    private static long sumBytes(List<CefriumProfile.SiteData> sites, String host) {
        long n = 0;
        for (CefriumProfile.SiteData d : sites) {
            if (find(java.util.Collections.singletonList(d), host) != null) n += d.storageBytes;
        }
        return n;
    }

    private static String describe(List<CefriumProfile.SiteData> sites) {
        StringBuilder b = new StringBuilder("[");
        for (CefriumProfile.SiteData d : sites) {
            b.append(d.site).append(' ').append(d.storageBytes).append("B ")
                    .append(d.cookieCount).append("c; ");
        }
        return b.append(']').toString();
    }
}
