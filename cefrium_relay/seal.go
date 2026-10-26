// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
)

// A seal is what the device puts in the endpoint path: the subscription,
// encrypted to the relay's public key, so the relay keeps no state.
//
//	seal      = 0x01 | ephemeral P-256 public key (65) | AES-128-GCM ciphertext
//	key|nonce = HKDF-SHA256(ikm = ECDH x, salt = ephemeral | relay public,
//	                        info = "cefrium-relay seal v1", 28 bytes)
//	plaintext = sha256(VAPID public key) (32) | len(app id) (1) | app id | FCM token
//
// The version byte is also the GCM additional data.

const sealVersion = 0x01

var sealInfo = "cefrium-relay seal v1"

type subscription struct {
	vapidHash [32]byte
	appID     string
	token     string
}

var errBadSeal = errors.New("bad seal")

func deriveSealKey(shared, ephemeral, relay []byte) (cipher.AEAD, []byte, error) {
	salt := append(append([]byte{}, ephemeral...), relay...)
	okm, err := hkdf.Key(sha256.New, shared, salt, sealInfo, 28)
	if err != nil {
		return nil, nil, err
	}
	block, err := aes.NewCipher(okm[:16])
	if err != nil {
		return nil, nil, err
	}
	aead, err := cipher.NewGCM(block)
	return aead, okm[16:], err
}

func openSeal(relay *ecdh.PrivateKey, text string) (*subscription, error) {
	raw, err := base64.RawURLEncoding.DecodeString(text)
	if err != nil || len(raw) < 1+65+16 || raw[0] != sealVersion {
		return nil, errBadSeal
	}
	ephemeral, err := ecdh.P256().NewPublicKey(raw[1:66])
	if err != nil {
		return nil, errBadSeal
	}
	shared, err := relay.ECDH(ephemeral)
	if err != nil {
		return nil, errBadSeal
	}
	aead, nonce, err := deriveSealKey(shared, raw[1:66], relay.PublicKey().Bytes())
	if err != nil {
		return nil, errBadSeal
	}
	plain, err := aead.Open(nil, nonce, raw[66:], raw[:1])
	if err != nil || len(plain) < 33 {
		return nil, errBadSeal
	}
	n := int(plain[32])
	if len(plain) < 33+n+1 {
		return nil, errBadSeal
	}
	s := &subscription{appID: string(plain[33 : 33+n]), token: string(plain[33+n:])}
	copy(s.vapidHash[:], plain[:32])
	return s, nil
}

// makeSeal does what the device does; the relay itself never needs it.
func makeSeal(relay *ecdh.PublicKey, s *subscription) (string, error) {
	if len(s.appID) > 255 {
		return "", errors.New("app id too long")
	}
	ephemeral, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		return "", err
	}
	shared, err := ephemeral.ECDH(relay)
	if err != nil {
		return "", err
	}
	eph := ephemeral.PublicKey().Bytes()
	aead, nonce, err := deriveSealKey(shared, eph, relay.Bytes())
	if err != nil {
		return "", err
	}
	plain := append(append(append(s.vapidHash[:0:0], s.vapidHash[:]...), byte(len(s.appID))), s.appID...)
	plain = append(plain, s.token...)
	out := append([]byte{sealVersion}, eph...)
	out = aead.Seal(out, nonce, plain, out[:1])
	return base64.RawURLEncoding.EncodeToString(out), nil
}
