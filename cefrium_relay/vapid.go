// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"math/big"
	"strings"
	"time"
)

// RFC 8292: the longest a VAPID token may live.
const maxVapidLifetime = 24 * time.Hour

var errVapid = errors.New("bad VAPID authorization")

func decodeB64URL(s string) ([]byte, error) {
	return base64.RawURLEncoding.DecodeString(strings.TrimRight(s, "="))
}

// verifyVapid checks an "Authorization: vapid t=..., k=..." header against
// the subscription's key and returns nil if the sender may push.
func verifyVapid(header, audience string, vapidHash [32]byte, now time.Time) error {
	scheme, params, ok := strings.Cut(strings.TrimSpace(header), " ")
	if !ok || !strings.EqualFold(scheme, "vapid") {
		return errVapid
	}
	var token, key string
	for _, p := range strings.Split(params, ",") {
		name, value, _ := strings.Cut(strings.TrimSpace(p), "=")
		switch strings.ToLower(name) {
		case "t":
			token = value
		case "k":
			key = value
		}
	}
	raw, err := decodeB64URL(key)
	if err != nil || len(raw) != 65 || sha256.Sum256(raw) != vapidHash {
		return errVapid
	}
	pub, err := ecdsa.ParseUncompressedPublicKey(elliptic.P256(), raw)
	if err != nil {
		return errVapid
	}

	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return errVapid
	}
	var head struct {
		Alg string `json:"alg"`
	}
	if b, err := decodeB64URL(parts[0]); err != nil || json.Unmarshal(b, &head) != nil || head.Alg != "ES256" {
		return errVapid
	}
	sig, err := decodeB64URL(parts[2])
	if err != nil || len(sig) != 64 {
		return errVapid
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if !ecdsa.Verify(pub, digest[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])) {
		return errVapid
	}
	var claims struct {
		Aud string `json:"aud"`
		Exp int64  `json:"exp"`
	}
	b, err := decodeB64URL(parts[1])
	if err != nil || json.Unmarshal(b, &claims) != nil {
		return errVapid
	}
	exp := time.Unix(claims.Exp, 0)
	if claims.Aud != audience || !exp.After(now) || exp.After(now.Add(maxVapidLifetime+time.Minute)) {
		return errVapid
	}
	return nil
}
