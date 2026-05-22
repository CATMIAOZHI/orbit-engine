# Security Policy

## Chromium Version

Current version: **Chromium 148.0.7778.167**.

## Security Updates

Cefrium inherits security vulnerabilities from Chromium. When a
Chromium security update is released, Cefrium updates to match.

**Cefrium does NOT provide independent security patches.** All
security fixes come from upstream Chromium latest releases.

## Update Cadence

| Severity | Target |
|----------|--------|
| Critical CVE (actively exploited) | Update within 1 week of Debian release |
| High CVE | Update within 2 weeks |
| Medium/Low CVE | Next scheduled release |

## Limitations

- Cefrium is provided AS IS, without warranty of any kind
- The maintainer makes best effort to track security releases
  but cannot guarantee timelines
- Users deploying Cefrium in production are responsible for
  monitoring Chromium security advisories and updating promptly
- Cefrium is maintained by a single developer; response times
  may vary

## Reporting Vulnerabilities

- **Chromium vulnerabilities**: report to Chromium directly at
  https://bugs.chromium.org/p/chromium/issues/entry
- **Cefrium-specific vulnerabilities** (patches, SDK, JNI bridge):
  email juan.mendezr@proton.me with subject "SECURITY: [description]"
- Do NOT open public issues for security vulnerabilities

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release | Yes |
| Previous releases | No — update to latest |
