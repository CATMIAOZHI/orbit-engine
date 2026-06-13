# Versioning

Scope: this document is for CEFRIUM (CEF ported to native Android) and its SDK, not upstream CEF.

Cefrium follows a semantic-versioning convention during pre-1.0 validation:

- **Minor** (`0.X.0` → `0.(X+1).0`): a release that adds public API.
- **Patch** (`0.X.Y` → `0.X.(Y+1)`): fixes, packaging, and documentation only — no new
  public API.
- **Engine bumps**: when the bundled CEF branch or Chromium version changes, it is
  recorded in the [parity table](https://cefrium.com/releases/) regardless of the
  version step.

The convention applies from the release **after** 0.3.1.

Note: 0.3.0 → 0.3.1 added public API (`setSslErrorHandler`, `setHttpAuthHandler`,
`CefriumInitProvider`), which by this convention is a minor change. It shipped as a
patch and is **not** re-versioned — 0.3.1 is published and immutable.

Until 1.0 the public API may still change between releases; 1.0 marks the first
stability commitment.
