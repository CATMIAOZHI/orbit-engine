// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package main

import (
	"bytes"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"sync"
	"time"
)

const (
	fcmScope   = "https://www.googleapis.com/auth/firebase.messaging"
	fcmBaseURL = "https://fcm.googleapis.com"
	// FCM keeps a message for at most 28 days.
	fcmMaxTTL = 28 * 24 * time.Hour
)

// fcm sends FCM HTTP v1 messages as the app's service account.
type fcm struct {
	project  string
	email    string
	tokenURI string
	key      *rsa.PrivateKey
	baseURL  string
	client   *http.Client

	mu      sync.Mutex
	access  string
	expires time.Time
}

func loadServiceAccount(path string) (*fcm, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var sa struct {
		ProjectID   string `json:"project_id"`
		PrivateKey  string `json:"private_key"`
		ClientEmail string `json:"client_email"`
		TokenURI    string `json:"token_uri"`
	}
	if err := json.Unmarshal(b, &sa); err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	block, _ := pem.Decode([]byte(sa.PrivateKey))
	if block == nil {
		return nil, fmt.Errorf("%s: no private key", path)
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", path, err)
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok || sa.ProjectID == "" || sa.ClientEmail == "" || sa.TokenURI == "" {
		return nil, fmt.Errorf("%s: not a service account key", path)
	}
	return &fcm{project: sa.ProjectID, email: sa.ClientEmail, tokenURI: sa.TokenURI,
		key: key, baseURL: fcmBaseURL, client: &http.Client{Timeout: 20 * time.Second}}, nil
}

// accessToken returns an OAuth2 token from a signed JWT assertion (RFC 7523).
func (f *fcm) accessToken() (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.access != "" && time.Now().Before(f.expires) {
		return f.access, nil
	}
	now := time.Now()
	enc := base64.RawURLEncoding
	head := enc.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`))
	claims, _ := json.Marshal(map[string]any{"iss": f.email, "scope": fcmScope,
		"aud": f.tokenURI, "iat": now.Unix(), "exp": now.Add(time.Hour).Unix()})
	input := head + "." + enc.EncodeToString(claims)
	digest := sha256.Sum256([]byte(input))
	sig, err := rsa.SignPKCS1v15(rand.Reader, f.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", err
	}
	resp, err := f.client.PostForm(f.tokenURI, url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {input + "." + enc.EncodeToString(sig)}})
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&out) != nil || out.AccessToken == "" {
		return "", fmt.Errorf("token endpoint answered %d", resp.StatusCode)
	}
	f.access = out.AccessToken
	f.expires = now.Add(time.Duration(out.ExpiresIn)*time.Second - time.Minute)
	return f.access, nil
}

type fcmMessage struct {
	token       string
	data        map[string]string
	ttl         time.Duration
	high        bool
	collapseKey string
}

// fcmError is FCM's answer to a message it did not take.
type fcmError struct {
	status     int
	code       string
	retryAfter string
}

func (e *fcmError) Error() string { return fmt.Sprintf("FCM answered %d %s", e.status, e.code) }

// unregistered reports a token that will never work again.
func (e *fcmError) unregistered() bool {
	return e.code == "UNREGISTERED" || e.status == http.StatusNotFound
}

func (f *fcm) send(m fcmMessage) error {
	access, err := f.accessToken()
	if err != nil {
		return err
	}
	android := map[string]any{"ttl": fmt.Sprintf("%ds", int64(m.ttl/time.Second))}
	if m.high {
		android["priority"] = "high"
	} else {
		android["priority"] = "normal"
	}
	if m.collapseKey != "" {
		android["collapse_key"] = m.collapseKey
	}
	body, _ := json.Marshal(map[string]any{"message": map[string]any{
		"token": m.token, "data": m.data, "android": android}})
	req, _ := http.NewRequest(http.MethodPost,
		f.baseURL+"/v1/projects/"+f.project+"/messages:send", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+access)
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		io.Copy(io.Discard, resp.Body)
		return nil
	}
	var out struct {
		Error struct {
			Status  string `json:"status"`
			Details []struct {
				ErrorCode string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	json.NewDecoder(io.LimitReader(resp.Body, 1<<16)).Decode(&out)
	e := &fcmError{status: resp.StatusCode, code: out.Error.Status,
		retryAfter: resp.Header.Get("Retry-After")}
	for _, d := range out.Error.Details {
		if d.ErrorCode != "" {
			e.code = d.ErrorCode
		}
	}
	if resp.StatusCode == http.StatusUnauthorized {
		f.mu.Lock()
		f.access = ""
		f.mu.Unlock()
	}
	return e
}
