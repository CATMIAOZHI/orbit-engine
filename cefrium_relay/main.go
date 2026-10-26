// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// cefrium-relay is a Web Push service (RFC 8030) that delivers through the
// app's own Firebase project. It keeps no state: each endpoint carries the
// subscription, sealed on the device to the relay's key.
//
//	cefrium-relay keygen -key relay-key.pem
//	cefrium-relay -origin https://push.example.com -key relay-key.pem \
//	    -service-account service-account.json [-tls-cert c.pem -tls-key k.pem]
package main

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	// RFC 8291 messages are at most 4096 bytes; push services must take that.
	maxBody = 4096
	// FCM refuses data payloads over 4096 bytes, keys included.
	fcmDataBudget = 3800

	keyApp  = "cefrium.relay.app"
	keyBody = "cefrium.relay.body"
	keyMsg  = "cefrium.relay.msg"
	keyPart = "cefrium.relay.part"
)

type sender interface {
	send(fcmMessage) error
}

type relay struct {
	origin string
	key    *ecdh.PrivateKey
	fcm    sender
	now    func() time.Time
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "keygen" {
		keygen(os.Args[2:])
		return
	}
	listen := flag.String("listen", ":8443", "address to listen on")
	origin := flag.String("origin", "", "public origin of this relay, e.g. https://push.example.com")
	keyPath := flag.String("key", "relay-key.pem", "relay private key (from keygen)")
	saPath := flag.String("service-account", "", "Firebase service account JSON of the app")
	cert := flag.String("tls-cert", "", "TLS certificate; without it, plain HTTP behind a TLS proxy")
	tlsKey := flag.String("tls-key", "", "TLS private key")
	flag.Parse()
	if !strings.HasPrefix(*origin, "https://") || strings.Count(*origin, "/") != 2 {
		log.Fatal("-origin must be an https origin with no path")
	}
	key, err := loadKey(*keyPath)
	if err != nil {
		log.Fatal(err)
	}
	f, err := loadServiceAccount(*saPath)
	if err != nil {
		log.Fatal(err)
	}
	r := &relay{origin: *origin, key: key, fcm: f, now: time.Now}
	server := &http.Server{Addr: *listen, Handler: r.handler(),
		ReadTimeout: 10 * time.Second, WriteTimeout: 30 * time.Second}
	log.Printf("relay for %s on %s, public key %s", *origin, *listen,
		base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes()))
	if *cert != "" {
		err = server.ListenAndServeTLS(*cert, *tlsKey)
	} else {
		err = server.ListenAndServe()
	}
	log.Fatal(err)
}

func keygen(args []string) {
	fs := flag.NewFlagSet("keygen", flag.ExitOnError)
	path := fs.String("key", "relay-key.pem", "where to write the private key")
	fs.Parse(args)
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		log.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		log.Fatal(err)
	}
	out, err := os.OpenFile(*path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		log.Fatal(err)
	}
	if err := pem.Encode(out, &pem.Block{Type: "PRIVATE KEY", Bytes: der}); err != nil {
		log.Fatal(err)
	}
	if err := out.Close(); err != nil {
		log.Fatal(err)
	}
	pub, _ := priv.ECDH()
	fmt.Println(base64.RawURLEncoding.EncodeToString(pub.PublicKey().Bytes()))
}

func loadKey(path string) (*ecdh.PrivateKey, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(b)
	if block == nil {
		return nil, fmt.Errorf("%s: no PEM key", path)
	}
	k, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	ec, ok := k.(*ecdsa.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("%s: not a P-256 key", path)
	}
	return ec.ECDH()
}

func (r *relay) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /push/{seal}", r.push)
	mux.HandleFunc("GET /key", func(w http.ResponseWriter, _ *http.Request) {
		io.WriteString(w, base64.RawURLEncoding.EncodeToString(r.key.PublicKey().Bytes()))
	})
	return mux
}

func fail(w http.ResponseWriter, status int, reason string) {
	http.Error(w, reason, status)
}

func (r *relay) push(w http.ResponseWriter, req *http.Request) {
	sub, err := openSeal(r.key, req.PathValue("seal"))
	if err != nil {
		fail(w, http.StatusNotFound, "no such subscription")
		return
	}
	if verifyVapid(req.Header.Get("Authorization"), r.origin, sub.vapidHash, r.now()) != nil {
		fail(w, http.StatusForbidden, "VAPID authorization does not match the subscription")
		return
	}
	ttlHeader := req.Header.Get("TTL")
	ttlSeconds, err := strconv.ParseInt(ttlHeader, 10, 64)
	if err != nil || ttlSeconds < 0 {
		fail(w, http.StatusBadRequest, "TTL header required")
		return
	}
	ttl := min(time.Duration(ttlSeconds)*time.Second, fcmMaxTTL)
	body, err := io.ReadAll(http.MaxBytesReader(w, req.Body, maxBody))
	if err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			fail(w, http.StatusRequestEntityTooLarge, "payload over 4096 bytes")
		} else {
			fail(w, http.StatusBadRequest, "could not read the payload")
		}
		return
	}
	if len(body) > 0 && !strings.EqualFold(req.Header.Get("Content-Encoding"), "aes128gcm") {
		fail(w, http.StatusUnsupportedMediaType, "payload must be aes128gcm (RFC 8291)")
		return
	}
	var high bool
	switch strings.ToLower(req.Header.Get("Urgency")) {
	case "very-low", "low":
	default:
		high = true
	}

	parts := split(base64.RawURLEncoding.EncodeToString(body), fcmDataBudget-len(sub.appID))
	id := randomID()
	for i, p := range parts {
		m := fcmMessage{token: sub.token, ttl: ttl, high: high,
			data: map[string]string{keyApp: sub.appID, keyBody: p}}
		if len(parts) > 1 {
			m.data[keyMsg] = id
			m.data[keyPart] = fmt.Sprintf("%d/%d", i, len(parts))
		} else {
			m.collapseKey = req.Header.Get("Topic")
		}
		if err := r.fcm.send(m); err != nil {
			r.fcmFailed(w, err)
			return
		}
	}
	w.Header().Set("Location", r.origin+"/message/"+id)
	w.Header().Set("TTL", strconv.FormatInt(int64(ttl/time.Second), 10))
	w.WriteHeader(http.StatusCreated)
}

func (r *relay) fcmFailed(w http.ResponseWriter, err error) {
	var e *fcmError
	switch {
	case errors.As(err, &e) && e.unregistered():
		fail(w, http.StatusGone, "subscription expired")
	case errors.As(err, &e) && e.status == http.StatusTooManyRequests:
		if e.retryAfter != "" {
			w.Header().Set("Retry-After", e.retryAfter)
		}
		fail(w, http.StatusTooManyRequests, "rate limited")
	default:
		log.Printf("FCM: %v", err)
		fail(w, http.StatusBadGateway, "FCM did not take the message")
	}
}

// split cuts s into pieces of at most n characters; "" stays one piece.
func split(s string, n int) []string {
	parts := []string{}
	for len(s) > n {
		parts = append(parts, s[:n])
		s = s[n:]
	}
	return append(parts, s)
}

func randomID() string {
	b := make([]byte, 12)
	rand.Read(b)
	return hex.EncodeToString(b)
}
