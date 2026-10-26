// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package main

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const origin = "https://push.example.test"

type fakeSender struct {
	sent []fcmMessage
	err  error
}

func (f *fakeSender) send(m fcmMessage) error {
	if f.err != nil {
		return f.err
	}
	f.sent = append(f.sent, m)
	return nil
}

type fixture struct {
	t     *testing.T
	relay *relay
	fcm   *fakeSender
	vapid *ecdsa.PrivateKey
	seal  string
	now   time.Time
}

func newFixture(t *testing.T) *fixture {
	key, _ := ecdh.P256().GenerateKey(rand.Reader)
	vapid, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	f := &fixture{t: t, fcm: &fakeSender{}, vapid: vapid, now: time.Unix(1_800_000_000, 0)}
	f.relay = &relay{origin: origin, key: key, fcm: f.fcm, now: func() time.Time { return f.now }}
	sub := &subscription{vapidHash: sha256.Sum256(vapidPublic(vapid)),
		appID: "wp:https://site.example/#8f0e", token: "fcm-token:APA91b"}
	seal, err := makeSeal(key.PublicKey(), sub)
	if err != nil {
		t.Fatal(err)
	}
	f.seal = seal
	return f
}

func vapidPublic(k *ecdsa.PrivateKey) []byte {
	pub, _ := k.PublicKey.Bytes()
	return pub
}

func vapidHeader(k *ecdsa.PrivateKey, aud string, exp time.Time) string {
	enc := base64.RawURLEncoding
	claims, _ := json.Marshal(map[string]any{"aud": aud, "exp": exp.Unix(), "sub": "mailto:a@b.test"})
	input := enc.EncodeToString([]byte(`{"typ":"JWT","alg":"ES256"}`)) + "." + enc.EncodeToString(claims)
	digest := sha256.Sum256([]byte(input))
	r, s, _ := ecdsa.Sign(rand.Reader, k, digest[:])
	sig := append(r.FillBytes(make([]byte, 32)), s.FillBytes(make([]byte, 32))...)
	return fmt.Sprintf("vapid t=%s.%s, k=%s", input, enc.EncodeToString(sig), enc.EncodeToString(vapidPublic(k)))
}

type request struct {
	seal, auth, ttl, encoding, urgency, topic string
	body                                      []byte
}

func (f *fixture) defaults() request {
	return request{seal: f.seal, auth: vapidHeader(f.vapid, origin, f.now.Add(time.Hour)),
		ttl: "60", encoding: "aes128gcm", body: []byte("ciphertext")}
}

func (f *fixture) do(r request) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/push/"+r.seal, bytes.NewReader(r.body))
	for name, value := range map[string]string{"Authorization": r.auth, "TTL": r.ttl,
		"Content-Encoding": r.encoding, "Urgency": r.urgency, "Topic": r.topic} {
		if value != "" {
			req.Header.Set(name, value)
		}
	}
	w := httptest.NewRecorder()
	f.relay.handler().ServeHTTP(w, req)
	return w
}

func reassemble(t *testing.T, sent []fcmMessage) []byte {
	var joined string
	for i, m := range sent {
		if len(sent) > 1 && m.data[keyPart] != fmt.Sprintf("%d/%d", i, len(sent)) {
			t.Fatalf("part %d labelled %q", i, m.data[keyPart])
		}
		joined += m.data[keyBody]
	}
	b, err := base64.RawURLEncoding.DecodeString(joined)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestDeliversOneMessage(t *testing.T) {
	f := newFixture(t)
	r := f.defaults()
	r.topic = "news"
	w := f.do(r)
	if w.Code != http.StatusCreated || !strings.HasPrefix(w.Header().Get("Location"), origin+"/message/") {
		t.Fatalf("got %d %q", w.Code, w.Header().Get("Location"))
	}
	if len(f.fcm.sent) != 1 {
		t.Fatalf("sent %d messages", len(f.fcm.sent))
	}
	m := f.fcm.sent[0]
	if m.token != "fcm-token:APA91b" || m.data[keyApp] != "wp:https://site.example/#8f0e" ||
		m.ttl != time.Minute || !m.high || m.collapseKey != "news" || m.data[keyPart] != "" {
		t.Fatalf("message %+v", m)
	}
	if got := reassemble(t, f.fcm.sent); string(got) != "ciphertext" {
		t.Fatalf("body %q", got)
	}
}

func TestSplitsALargeMessage(t *testing.T) {
	f := newFixture(t)
	r := f.defaults()
	r.topic = "news"
	r.body = make([]byte, maxBody)
	rand.Read(r.body)
	if w := f.do(r); w.Code != http.StatusCreated {
		t.Fatalf("got %d", w.Code)
	}
	if len(f.fcm.sent) != 2 {
		t.Fatalf("sent %d messages", len(f.fcm.sent))
	}
	for _, m := range f.fcm.sent {
		size := 0
		for k, v := range m.data {
			size += len(k) + len(v)
		}
		if size > 4096 || m.collapseKey != "" || m.data[keyMsg] != f.fcm.sent[0].data[keyMsg] {
			t.Fatalf("part of %d bytes, collapse %q, msg %q", size, m.collapseKey, m.data[keyMsg])
		}
	}
	if got := reassemble(t, f.fcm.sent); !bytes.Equal(got, r.body) {
		t.Fatal("reassembled body differs")
	}
}

func TestEmptyMessage(t *testing.T) {
	f := newFixture(t)
	r := f.defaults()
	r.body, r.encoding = nil, ""
	if w := f.do(r); w.Code != http.StatusCreated || len(f.fcm.sent) != 1 || f.fcm.sent[0].data[keyBody] != "" {
		t.Fatalf("got %d, sent %+v", w.Code, f.fcm.sent)
	}
}

func TestUrgencyAndTTL(t *testing.T) {
	f := newFixture(t)
	r := f.defaults()
	r.urgency, r.ttl = "low", "99999999"
	w := f.do(r)
	if w.Code != http.StatusCreated || f.fcm.sent[0].high || f.fcm.sent[0].ttl != fcmMaxTTL ||
		w.Header().Get("TTL") != "2419200" {
		t.Fatalf("got %d TTL %q, sent %+v", w.Code, w.Header().Get("TTL"), f.fcm.sent[0])
	}
}

func TestRefusals(t *testing.T) {
	other, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	cases := []struct {
		name   string
		edit   func(f *fixture, r *request)
		status int
	}{
		{"unknown seal", func(f *fixture, r *request) { r.seal = f.seal[:len(f.seal)-4] + "AAAA" }, 404},
		{"garbage seal", func(f *fixture, r *request) { r.seal = "x" }, 404},
		{"no authorization", func(f *fixture, r *request) { r.auth = "" }, 403},
		{"other VAPID key", func(f *fixture, r *request) { r.auth = vapidHeader(other, origin, f.now.Add(time.Hour)) }, 403},
		{"other audience", func(f *fixture, r *request) { r.auth = vapidHeader(f.vapid, "https://evil.test", f.now.Add(time.Hour)) }, 403},
		{"expired", func(f *fixture, r *request) { r.auth = vapidHeader(f.vapid, origin, f.now.Add(-time.Minute)) }, 403},
		{"lives too long", func(f *fixture, r *request) { r.auth = vapidHeader(f.vapid, origin, f.now.Add(25*time.Hour)) }, 403},
		{"tampered signature", func(f *fixture, r *request) {
			h := vapidHeader(f.vapid, origin, f.now.Add(time.Hour))
			i := strings.Index(h, ", k=") - 2
			c := byte('A')
			if h[i] == 'A' {
				c = 'B'
			}
			r.auth = h[:i] + string(c) + h[i+1:]
		}, 403},
		{"no TTL", func(f *fixture, r *request) { r.ttl = "" }, 400},
		{"negative TTL", func(f *fixture, r *request) { r.ttl = "-1" }, 400},
		{"too large", func(f *fixture, r *request) { r.body = make([]byte, maxBody+1) }, 413},
		{"legacy aesgcm", func(f *fixture, r *request) { r.encoding = "aesgcm" }, 415},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := newFixture(t)
			r := f.defaults()
			c.edit(f, &r)
			if w := f.do(r); w.Code != c.status || len(f.fcm.sent) != 0 {
				t.Fatalf("got %d (want %d), sent %d", w.Code, c.status, len(f.fcm.sent))
			}
		})
	}
}

func TestFCMErrors(t *testing.T) {
	cases := []struct {
		err    error
		status int
	}{
		{&fcmError{status: 404, code: "UNREGISTERED"}, http.StatusGone},
		{&fcmError{status: 400, code: "UNREGISTERED"}, http.StatusGone},
		{&fcmError{status: 429, code: "QUOTA_EXCEEDED", retryAfter: "30"}, http.StatusTooManyRequests},
		{&fcmError{status: 503, code: "UNAVAILABLE"}, http.StatusBadGateway},
		{fmt.Errorf("network down"), http.StatusBadGateway},
	}
	for _, c := range cases {
		f := newFixture(t)
		f.fcm.err = c.err
		w := f.do(f.defaults())
		if w.Code != c.status {
			t.Errorf("%v: got %d, want %d", c.err, w.Code, c.status)
		}
		if c.status == http.StatusTooManyRequests && w.Header().Get("Retry-After") != "30" {
			t.Errorf("Retry-After %q", w.Header().Get("Retry-After"))
		}
	}
}

// The real sender against a fake OAuth + FCM server.
func TestFCMSender(t *testing.T) {
	rsaKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	var tokens, sends int
	var lastBody map[string]any
	fail := ""
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/token":
			tokens++
			if r.FormValue("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" || r.FormValue("assertion") == "" {
				http.Error(w, "bad grant", 400)
				return
			}
			fmt.Fprint(w, `{"access_token":"ya29.test","expires_in":3600}`)
		case "/v1/projects/demo/messages:send":
			sends++
			if r.Header.Get("Authorization") != "Bearer ya29.test" {
				http.Error(w, "no auth", 401)
				return
			}
			json.NewDecoder(r.Body).Decode(&lastBody)
			if fail != "" {
				w.WriteHeader(404)
				fmt.Fprintf(w, `{"error":{"status":"NOT_FOUND","details":[{"errorCode":%q}]}}`, fail)
				return
			}
			fmt.Fprint(w, `{"name":"projects/demo/messages/1"}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	der, _ := x509.MarshalPKCS8PrivateKey(rsaKey)
	sa, _ := json.Marshal(map[string]string{"type": "service_account", "project_id": "demo",
		"client_email": "relay@demo.iam.gserviceaccount.com", "token_uri": server.URL + "/token",
		"private_key": string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))})
	path := filepath.Join(t.TempDir(), "sa.json")
	os.WriteFile(path, sa, 0o600)
	f, err := loadServiceAccount(path)
	if err != nil {
		t.Fatal(err)
	}
	f.baseURL = server.URL

	m := fcmMessage{token: "tok", data: map[string]string{keyApp: "wp:x"}, ttl: 90 * time.Second, high: true, collapseKey: "t"}
	if err := f.send(m); err != nil {
		t.Fatal(err)
	}
	if err := f.send(m); err != nil {
		t.Fatal(err)
	}
	if tokens != 1 || sends != 2 {
		t.Fatalf("tokens %d sends %d", tokens, sends)
	}
	android := lastBody["message"].(map[string]any)["android"].(map[string]any)
	if android["ttl"] != "90s" || android["priority"] != "high" || android["collapse_key"] != "t" {
		t.Fatalf("android %v", android)
	}
	fail = "UNREGISTERED"
	err = f.send(m)
	if e, ok := err.(*fcmError); !ok || !e.unregistered() {
		t.Fatalf("got %v", err)
	}
}

func TestSealRoundTrip(t *testing.T) {
	key, _ := ecdh.P256().GenerateKey(rand.Reader)
	in := &subscription{appID: "wp:a", token: "t"}
	in.vapidHash[0] = 7
	seal, _ := makeSeal(key.PublicKey(), in)
	out, err := openSeal(key, seal)
	if err != nil || *out != *in {
		t.Fatalf("got %+v %v", out, err)
	}
	other, _ := ecdh.P256().GenerateKey(rand.Reader)
	if _, err := openSeal(other, seal); err == nil {
		t.Fatal("opened with the wrong key")
	}
}
