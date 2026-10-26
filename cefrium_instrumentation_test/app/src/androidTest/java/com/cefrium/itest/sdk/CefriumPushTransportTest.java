// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.Manifest;
import android.app.Activity;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumPush;
import com.cefrium.CefriumPushTransport;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * FEATURE-074: with a push transport installed, a page's subscription gets
 * the transport's endpoint, and a message the transport delivers (encrypted
 * for the page, RFC 8291) reaches the page's service worker decrypted.
 * Unsubscribing reaches the transport.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumPushTransportTest {

    @Rule(order = 0)
    public GrantPermissionRule notifications =
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS);

    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    // Any P-256 public key: the transport is fake, nothing checks VAPID.
    private static final String SERVER_KEY =
            "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U";
    private static final String ENDPOINT_BASE = "https://push.example.test/sub/";

    private static final String PAGE = "<html><body><script>"
            + "navigator.serviceWorker.onmessage=function(e){document.title='push:'+e.data;};"
            + "var reg;"
            + "async function go(){"
            + "reg=await navigator.serviceWorker.register('/sw.js');"
            + "await navigator.serviceWorker.ready;"
            + "var key=Uint8Array.from(atob('" + SERVER_KEY + "'.replace(/-/g,'+').replace(/_/g,'/')),"
            + "function(c){return c.charCodeAt(0);});"
            + "var s=await reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:key});"
            + "var j=s.toJSON();"
            + "document.title='sub:'+JSON.stringify({e:j.endpoint,p:j.keys.p256dh,a:j.keys.auth});}"
            + "function unsub(){reg.pushManager.getSubscription().then(function(s){return s.unsubscribe();})"
            + ".then(function(ok){document.title='unsub:'+ok;});}"
            + "go().catch(function(e){document.title='error:'+e;});"
            + "</script></body></html>";
    private static final String SW = "self.addEventListener('push',function(e){"
            + "var text=e.data?e.data.text():'none';"
            + "e.waitUntil(self.registration.showNotification('push').then(function(){"
            + "return clients.matchAll({includeUncontrolled:true});}).then(function(cs){"
            + "cs.forEach(function(c){c.postMessage(text);});}));});";

    private static final List<String> sSubscribed = new CopyOnWriteArrayList<>();
    private static final List<String> sUnsubscribed = new CopyOnWriteArrayList<>();

    private static final class FakeTransport extends CefriumPushTransport {
        @Override
        public String subscribe(String appId, String applicationServerKey) {
            sSubscribed.add(appId);
            return ENDPOINT_BASE + Integer.toHexString(appId.hashCode());
        }

        @Override
        public void unsubscribe(String appId) {
            sUnsubscribed.add(appId);
        }
    }

    @Test
    public void transportCarriesTheSubscriptionAndItsMessages() throws Exception {
        sSubscribed.clear();
        sUnsubscribed.clear();
        CefriumPush.setTransport(new FakeTransport());
        try {
            run();
        } finally {
            CefriumPush.setTransport(null);
        }
    }

    private void run() throws Exception {
        Activity activity = activityRule.getActivity();
        CefriumProfile profile = CefriumProfile.get("push-t-" + UUID.randomUUID().toString().substring(0, 8));
        try (LocalHttpServer server = new LocalHttpServer(PAGE, "/sw.js", SW)) {
            final AtomicReference<String> title = new AtomicReference<>();
            final AtomicReference<CefriumBrowser> browser = new AtomicReference<>();
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity, profile);
                browser.set(b);
                b.setContentSetting(CefriumBrowser.QueryOriginPolicy.originOf(server.url()),
                        CefriumBrowser.PERMISSION_NOTIFICATIONS, CefriumBrowser.SETTING_ALLOW);
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            String sub = waitForTitle(title, "sub:");
            Assert.assertEquals("the transport was not asked to subscribe", 1, sSubscribed.size());
            String appId = sSubscribed.get(0);
            JSONObject json = new JSONObject(sub.substring(4));
            Assert.assertEquals("the page did not get the transport's endpoint",
                    ENDPOINT_BASE + Integer.toHexString(appId.hashCode()), json.getString("e"));

            String text = "hello-" + UUID.randomUUID().toString().substring(0, 6);
            byte[] body = encrypt(text.getBytes(StandardCharsets.UTF_8),
                    b64(json.getString("p")), b64(json.getString("a")));
            Assert.assertFalse("an unknown subscription was accepted",
                    CefriumPush.deliverMessage(activity, "wp:unknown#0", body));
            Assert.assertTrue("deliverMessage refused the subscription",
                    CefriumPush.deliverMessage(activity, appId, body));
            Assert.assertEquals("the service worker did not get the decrypted message",
                    "push:" + text, waitForTitle(title, "push:"));

            activity.runOnUiThread(() -> browser.get().evaluateJavaScript("unsub()"));
            Assert.assertEquals("unsub:true", waitForTitle(title, "unsub:"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (sUnsubscribed.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            Assert.assertTrue("the transport was not asked to unsubscribe: " + sUnsubscribed,
                    sUnsubscribed.contains(appId));
        }
    }

    private static String waitForTitle(AtomicReference<String> title, String prefix)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String t = title.get();
            if (t != null && t.startsWith("error:")) Assert.fail(t);
            if (t != null && t.startsWith(prefix)) return t;
            Thread.sleep(100);
        }
        Assert.fail("no '" + prefix + "' title; last=" + title.get());
        return null;
    }

    private static byte[] b64(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    // RFC 8291 aes128gcm, one record, as an application server sends it.
    private static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret)
            throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair as = gen.generateKeyPair();
        ECPublicKey asPub = (ECPublicKey) as.getPublic();
        ECPoint ua = new ECPoint(new BigInteger(1, Arrays.copyOfRange(uaPublic, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(uaPublic, 33, 65)));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(as.getPrivate());
        ka.doPhase(KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(ua, asPub.getParams())), true);
        byte[] ecdh = ka.generateSecret();
        byte[] asPublic = uncompressed(asPub);

        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hkdf(authSecret, ecdh, keyInfo, 32);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = c.doFinal(concat(plaintext, new byte[] {2}));
        return concat(salt, ByteBuffer.allocate(4).putInt(4096).array(), new byte[] {65}, asPublic,
                ciphertext);
    }

    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        return Arrays.copyOf(mac.doFinal(concat(info, new byte[] {1})), length);
    }

    private static byte[] uncompressed(ECPublicKey key) {
        return concat(new byte[] {4}, fixed32(key.getW().getAffineX()), fixed32(key.getW().getAffineY()));
    }

    private static byte[] fixed32(BigInteger n) {
        byte[] b = n.toByteArray();
        byte[] out = new byte[32];
        int len = Math.min(b.length, 32);
        System.arraycopy(b, b.length - len, out, 32 - len, len);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}
