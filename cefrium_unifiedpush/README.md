# cefrium-unifiedpush

Web Push through [UnifiedPush](https://unifiedpush.org) instead of Firebase
Cloud Messaging. Pages subscribe with `PushManager.subscribe()` as usual; the
subscription is registered with the UnifiedPush distributor on the device
(ntfy, NextPush, ...), so push works without Google Play services.

```java
// Application.onCreate()
CefriumUnifiedPush.install(this);

// Once, from an Activity: use the chosen distributor, or let the user pick.
UnifiedPush.tryUseCurrentOrDefaultDistributor(activity, ok -> kotlin.Unit.INSTANCE);
```

Messages stay encrypted for the page (RFC 8291 `aes128gcm`) until the engine
decrypts them; the distributor only carries them.

## Build

```bash
mkdir -p libs && cp ../../out/release/cefrium-sdk.aar libs/
./gradlew assembleRelease
```

Sample: `cefrium-sample/unifiedpush-demo`.
