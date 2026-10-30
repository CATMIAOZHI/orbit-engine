# Cefrium Licensing

Cefrium is the first port of CEF (Chromium Embedded Framework) to
native Android. It is dual-licensed.

## Dual Licensing

The Cefrium SDK (the Java/Kotlin SDK, its JNI bridge, the Compose
wrapper, UnifiedPush, the relay and the Gradle plugin) is available under
two licenses:

### 1. LGPL v3 (everyone)

Free for everyone, commercial and proprietary apps included. You may
use, modify, and distribute Cefrium under the terms of the GNU Lesser
General Public License version 3 or later. Changes to the Cefrium SDK
itself must be shared under the same license. Your application code
can remain proprietary.

See `LICENSES/LGPL-3.0-or-later.txt` and `LICENSES/GPL-3.0-or-later.txt`.

### 2. Cefrium Commercial License

An alternative to the LGPL for the SDK files above only, for products
that cannot meet its terms (for example, devices sold to consumers
without Installation Information). It is part of the Production and
Fleet support plans, not sold separately. It does not change the
licenses of third-party code in `libcef.so`, such as FFmpeg
(LGPL-2.1), whose obligations remain.

See `LICENSES/LicenseRef-Cefrium-Commercial.txt` and
https://cefrium.com/commercial/.

Contact: cefrium@proton.me

## Upstream CEF Code

The original CEF code (by Marshall Greenblatt and contributors)
remains under its BSD 3-Clause license. See `LICENSES/BSD-3-Clause.txt`.

The Android platform code (`libcef/browser/android/`, except the SDK's
`cefrium_*` and `adblock_filter.*` files), the changes to CEF files and the
Chromium patches under `patch/patches/android/` are under the same BSD
3-Clause license, because they are intended for upstream CEF.

## REUSE Compliance

This project follows the [REUSE specification](https://reuse.software/)
for license metadata. See `REUSE.toml` for per-file licensing details.

## Copyright

- Upstream CEF: Copyright (c) 2008-2026 Marshall A. Greenblatt
- Cefrium Android port: Copyright (c) 2026 Juan Manuel Mendez Rey
