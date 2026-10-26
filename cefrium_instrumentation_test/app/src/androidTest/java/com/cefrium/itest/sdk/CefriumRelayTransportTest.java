package com.cefrium.itest.sdk;

import com.cefrium.itest.BrowserRule;
import com.cefrium.itest.HostActivity;
import com.cefrium.itest.LocalHttpServer;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;
import com.cefrium.CefriumPush;
import com.cefrium.CefriumRelayTransport;

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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * FEATURE-074 D: with CefriumRelayTransport, the endpoint is the relay's
 * origin plus the subscription sealed to the relay's key (opened here as the
 * relay does), and the messages the relay sends through FCM -- in one piece,
 * in two parts in either order, or empty -- reach the page's service worker.
 */
@RunWith(AndroidJUnit4.class)
public class CefriumRelayTransportTest {

    @Rule(order = 0)
    public GrantPermissionRule notifications =
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS);

    @Rule(order = 1)
    public ActivityTestRule<HostActivity> activityRule =
            new ActivityTestRule<>(HostActivity.class);

    @Rule(order = 2)
    public final BrowserRule browsers = new BrowserRule();

    private static final String SERVER_KEY =
            "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U";
    private static final String ORIGIN = "https://relay.example.test";
    private static final String TOKEN = "fcm-token:APA91b-test";
    private static final int B64 = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    private static final String PAGE = "<html><body><script>"
            + "navigator.serviceWorker.onmessage=function(e){document.title='push:'+e.data;};"
            + "async function go(){"
            + "var reg=await navigator.serviceWorker.register('/sw.js');"
            + "await navigator.serviceWorker.ready;"
            + "var key=Uint8Array.from(atob('" + SERVER_KEY + "'.replace(/-/g,'+').replace(/_/g,'/')),"
            + "function(c){return c.charCodeAt(0);});"
            + "var s=await reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:key});"
            + "var j=s.toJSON();"
            + "document.title='sub:'+JSON.stringify({e:j.endpoint,p:j.keys.p256dh,a:j.keys.auth});}"
            + "go().catch(function(e){document.title='error:'+e;});"
            + "</script></body></html>";
    private static final String SW = "self.addEventListener('push',function(e){"
            + "var text=e.data?e.data.text():'none';"
            + "e.waitUntil(self.registration.showNotification('push').then(function(){"
            + "return clients.matchAll({includeUncontrolled:true});}).then(function(cs){"
            + "cs.forEach(function(c){c.postMessage(text);});}));});";

    private static final class TestRelayTransport extends CefriumRelayTransport {
        TestRelayTransport(String key) {
            super(ORIGIN, key);
        }

        @Override
        protected String getToken() {
            return TOKEN;
        }
    }

    @Test
    public void relayEndpointAndMessagesReachThePage() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair relayKey = gen.generateKeyPair();
        CefriumPush.setTransport(new TestRelayTransport(
                Base64.encodeToString(uncompressed((ECPublicKey) relayKey.getPublic()), B64)));
        try {
            run(relayKey);
        } finally {
            CefriumPush.setTransport(null);
        }
    }

    private void run(KeyPair relayKey) throws Exception {
        Activity activity = activityRule.getActivity();
        CefriumProfile profile = CefriumProfile.get("relay-t-" + UUID.randomUUID().toString().substring(0, 8));
        try (LocalHttpServer server = new LocalHttpServer(PAGE, "/sw.js", SW)) {
            final AtomicReference<String> title = new AtomicReference<>();
            activity.runOnUiThread(() -> {
                CefriumBrowser b = browsers.createWithSurface(activity, profile);
                b.setContentSetting(CefriumBrowser.QueryOriginPolicy.originOf(server.url()),
                        CefriumBrowser.PERMISSION_NOTIFICATIONS, CefriumBrowser.SETTING_ALLOW);
                b.setOnTitleChangedListener(title::set);
                b.loadUrl(server.url());
            });
            JSONObject sub = new JSONObject(waitForTitle(title, "sub:").substring(4));
            String endpoint = sub.getString("e");
            Assert.assertTrue("not a relay endpoint: " + endpoint,
                    endpoint.startsWith(ORIGIN + "/push/"));

            // What the relay finds in the seal.
            byte[] plain = openSeal(relayKey, endpoint.substring((ORIGIN + "/push/").length()));
            Assert.assertArrayEquals("the seal does not carry the page's VAPID key",
                    MessageDigest.getInstance("SHA-256").digest(b64(SERVER_KEY)),
                    Arrays.copyOf(plain, 32));
            int n = plain[32] & 0xff;
            String appId = new String(plain, 33, n, StandardCharsets.UTF_8);
            Assert.assertTrue("app id " + appId, appId.startsWith("wp:"));
            Assert.assertEquals("the seal does not carry the FCM token", TOKEN,
                    new String(plain, 33 + n, plain.length - 33 - n, StandardCharsets.UTF_8));

            byte[] p256dh = b64(sub.getString("p"));
            byte[] auth = b64(sub.getString("a"));

            // One message, as the relay sends a body that fits.
            String text = "one-" + UUID.randomUUID().toString().substring(0, 6);
            Bundle one = relayMessage(appId, encrypt(text.getBytes(StandardCharsets.UTF_8), p256dh, auth));
            Assert.assertTrue(CefriumPush.isWebPushMessage(one));
            Assert.assertTrue(CefriumPush.handleMessage(activity, "1234", one));
            Assert.assertEquals("push:" + text, waitForTitle(title, "push:" + text));

            // A body over FCM's limit, in two parts that arrive out of order.
            StringBuilder big = new StringBuilder("big-" + UUID.randomUUID().toString().substring(0, 6) + "-");
            while (big.length() < 3000) big.append('x');
            String body = Base64.encodeToString(
                    encrypt(big.toString().getBytes(StandardCharsets.UTF_8), p256dh, auth), B64);
            String msg = UUID.randomUUID().toString();
            int half = body.length() / 2;
            CefriumPush.handleMessage(activity, "1234", part(appId, body.substring(half), msg, "1/2"));
            Thread.sleep(2000);
            Assert.assertFalse("half a message was delivered",
                    String.valueOf(title.get()).startsWith("push:big-"));
            CefriumPush.handleMessage(activity, "1234", part(appId, body.substring(0, half), msg, "0/2"));
            Assert.assertEquals("push:" + big, waitForTitle(title, "push:big-"));

            // No payload.
            Bundle empty = new Bundle();
            empty.putString("cefrium.relay.app", appId);
            empty.putString("cefrium.relay.body", "");
            CefriumPush.handleMessage(activity, "1234", empty);
            Assert.assertEquals("push:none", waitForTitle(title, "push:none"));

            // A subscription this app did not make is dropped.
            Assert.assertFalse(CefriumPush.deliverMessage(activity, "wp:unknown#0", new byte[0]));
        }
    }

    private static Bundle relayMessage(String appId, byte[] body) {
        Bundle b = new Bundle();
        b.putString("cefrium.relay.app", appId);
        b.putString("cefrium.relay.body", Base64.encodeToString(body, B64));
        return b;
    }

    private static Bundle part(String appId, String body, String msg, String part) {
        Bundle b = new Bundle();
        b.putString("cefrium.relay.app", appId);
        b.putString("cefrium.relay.body", body);
        b.putString("cefrium.relay.msg", msg);
        b.putString("cefrium.relay.part", part);
        return b;
    }

    // cefrium-relay's openSeal.
    private static byte[] openSeal(KeyPair relay, String seal) throws Exception {
        byte[] raw = b64(seal);
        Assert.assertEquals("seal version", 1, raw[0]);
        byte[] eph = Arrays.copyOfRange(raw, 1, 66);
        ECPublicKey relayPub = (ECPublicKey) relay.getPublic();
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(relay.getPrivate());
        ka.doPhase(KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                new ECPoint(new BigInteger(1, Arrays.copyOfRange(eph, 1, 33)),
                        new BigInteger(1, Arrays.copyOfRange(eph, 33, 65))),
                relayPub.getParams())), true);
        byte[] okm = hkdf(concat(eph, uncompressed(relayPub)), ka.generateSecret(),
                "cefrium-relay seal v1".getBytes(StandardCharsets.US_ASCII), 28);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(okm, 0, 16, "AES"),
                new GCMParameterSpec(128, okm, 16, 12));
        c.updateAAD(new byte[] {1});
        return c.doFinal(raw, 66, raw.length - 66);
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
