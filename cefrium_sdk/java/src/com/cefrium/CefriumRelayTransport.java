// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.util.Base64;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push through the app's own Firebase project and a cefrium-relay the app
 * developer runs, instead of the Chromium channel Google provides for testing.
 *
 * <pre>{@code
 * CefriumPush.setTransport(new CefriumRelayTransport(
 *         "https://push.example.com", "<public key printed by cefrium-relay keygen>"));
 * }</pre>
 *
 * <p>The relay keeps no state. Each endpoint carries the subscription (the
 * app's FCM token, the page's VAPID key), encrypted on the device to the
 * relay's public key. The relay checks the sender's VAPID signature and sends
 * the message, still encrypted for the page, through FCM; {@link
 * CefriumPushService} or the app's own service passes it to {@link
 * CefriumPush#handleMessage}. Pages must subscribe with an
 * {@code applicationServerKey}.</p>
 */
public class CefriumRelayTransport extends CefriumPushTransport {
    private static final byte SEAL_VERSION = 1;
    private static final byte[] SEAL_INFO =
            "cefrium-relay seal v1".getBytes(StandardCharsets.US_ASCII);
    private static final int B64 = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    private final String mOrigin;
    private final byte[] mRelayKey;

    /**
     * @param origin the relay's public https origin, such as
     *        {@code https://push.example.com}.
     * @param relayPublicKey the relay's public key, base64url, as
     *        {@code cefrium-relay keygen} prints it.
     */
    public CefriumRelayTransport(String origin, String relayPublicKey) {
        if (origin == null || !origin.startsWith("https://")
                || origin.indexOf('/', "https://".length()) >= 0) {
            throw new IllegalArgumentException("origin must be an https origin with no path");
        }
        mOrigin = origin;
        mRelayKey = Base64.decode(relayPublicKey, B64);
        if (mRelayKey.length != 65 || mRelayKey[0] != 4) {
            throw new IllegalArgumentException("relayPublicKey is not a P-256 public key");
        }
    }

    @Override
    public String subscribe(String appId, String applicationServerKey) throws IOException {
        byte[] vapid;
        try {
            vapid = Base64.decode(applicationServerKey, B64);
        } catch (IllegalArgumentException e) {
            vapid = new byte[0];
        }
        if (vapid.length != 65) {
            throw new IOException("the relay needs a VAPID applicationServerKey");
        }
        byte[] app = appId.getBytes(StandardCharsets.UTF_8);
        if (app.length > 255) throw new IOException("app id too long");
        try {
            byte[] plain = concat(MessageDigest.getInstance("SHA-256").digest(vapid),
                    new byte[] {(byte) app.length}, app,
                    getToken().getBytes(StandardCharsets.UTF_8));
            return mOrigin + "/push/" + Base64.encodeToString(seal(plain), B64);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** Nothing to remove: the relay holds no subscriptions. */
    @Override
    public void unsubscribe(String appId) {}

    /** The app's FCM registration token. Called off the main thread. */
    protected String getToken() throws IOException {
        try {
            return Tasks.await(FirebaseMessaging.getInstance().getToken(), 30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("no FCM token", e);
        }
    }

    // The relay's openSeal: ECDH with a fresh key, HKDF-SHA256, AES-128-GCM.
    private byte[] seal(byte[] plain) throws GeneralSecurityException {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec curve = params.getParameterSpec(ECParameterSpec.class);
        PublicKey relay = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                new ECPoint(new BigInteger(1, Arrays.copyOfRange(mRelayKey, 1, 33)),
                        new BigInteger(1, Arrays.copyOfRange(mRelayKey, 33, 65))),
                curve));

        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(curve);
        KeyPair ephemeral = gen.generateKeyPair();
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(relay, true);
        byte[] eph = uncompressed((ECPublicKey) ephemeral.getPublic());

        byte[] okm = hkdf(concat(eph, mRelayKey), agreement.generateSecret(), SEAL_INFO, 28);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(okm, 0, 16, "AES"),
                new GCMParameterSpec(128, okm, 16, 12));
        byte[] version = {SEAL_VERSION};
        gcm.updateAAD(version);
        return concat(version, eph, gcm.doFinal(plain));
    }

    // RFC 5869, one block (length <= 32).
    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        return Arrays.copyOf(mac.doFinal(concat(info, new byte[] {1})), length);
    }

    private static byte[] uncompressed(ECPublicKey key) {
        return concat(new byte[] {4}, fixed32(key.getW().getAffineX()),
                fixed32(key.getW().getAffineY()));
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
