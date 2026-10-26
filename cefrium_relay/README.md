# cefrium-relay

A Web Push service (RFC 8030) for apps built on Cefrium. It delivers through
the app's own Firebase project, instead of the Chromium push channel that
Google provides for testing, and keeps no state: no database, no list of
subscriptions.

Each endpoint carries its subscription (the app's FCM token and the page's
VAPID key), encrypted on the device to the relay's public key. On a push the
relay opens it, checks the sender's VAPID signature (RFC 8292), and sends the
message through FCM, still encrypted for the page (RFC 8291 `aes128gcm`). It
never sees the content.

## Run

```bash
go build -o cefrium-relay .
./cefrium-relay keygen -key relay-key.pem       # prints the public key
./cefrium-relay -origin https://push.example.com -key relay-key.pem \
    -service-account service-account.json \
    -tls-cert cert.pem -tls-key key.pem         # or plain HTTP behind a TLS proxy
```

`service-account.json` is a key of the app's Firebase project (Project
settings, Service accounts). Keep it and `relay-key.pem` private; anyone with
the relay key can read the FCM tokens in the endpoints.

## App

```java
// Application.onCreate()
CefriumPush.setTransport(new CefriumRelayTransport(
        "https://push.example.com", "<public key printed by keygen>"));
```

with `CefriumPushService` enabled, or `CefriumPush.handleMessage()` called
from the app's own `FirebaseMessagingService`. Pages must subscribe with an
`applicationServerKey`.

## Behaviour

- `POST /push/<seal>`: 201 with `Location`; 404 unknown seal; 403 VAPID
  missing, wrong key, wrong `aud` or `exp` over 24 hours; 400 no `TTL`;
  413 over 4096 bytes; 415 not `aes128gcm`; 410 FCM token gone; 429 with
  `Retry-After` from FCM; 502 any other FCM failure.
- `TTL` is capped at FCM's 28 days and returned. `Urgency` `very-low` and
  `low` send at normal priority, otherwise high. `Topic` becomes the FCM
  collapse key.
- A body that does not fit one FCM data message (4096 bytes) is sent in two
  parts that the SDK puts back together; `Topic` is then not applied.
- `GET /key`: the relay's public key.

Sample: `cefrium-sample/push-demo` built with `-PrelayOrigin=... -PrelayKey=...`.
