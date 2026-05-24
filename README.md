# Cefrium — CEF for Android

The first working port of [Chromium Embedded Framework](https://github.com/chromiumembedded/cef)
to native Android (`aarch64-linux-android`, bionic libc, Android NDK).

CEF officially supports Windows, Linux, and macOS. Android has long been
considered out of scope due to its fundamentally different process model
and UI stack. This project builds on CEF's solid foundation to close
that gap.

<img src="assets/screenshots/cefrium-browser.png" width="300" alt="Cefrium Browser on Android">
<img src="assets/screenshots/headless-render.png" width="300" alt="Headless rendering — URL to PNG">

*Left: Cefrium Browser with URL bar, navigation, video+audio. Right: Headless render — URL to PNG via OSR mode.*

## What works

| Feature | Status |
|---------|--------|
| `libcef.so` links for Android arm64 and x86_64 | Done |
| `CefInitialize` + `CreateBrowser` | Done |
| OnPaint off-screen rendering (pixel buffer) | Done |
| On-screen rendering (Bitmap + ImageView) | Done |
| Touch, scroll, pinch-zoom | Done |
| Multi-process (renderer as Android Service) | Done |
| `is_official_build=true` (LTO + R8) | Done |
| CEF C API fully available | Done |
| SDK AAR for Gradle integration | Done |
| Web Speech API (text-to-speech) | Done |
| Scroll-driven CSS animations | Done |
| Mobile viewport (device scale factor) | Done |
| Ad blocking (network-level request interception) | Done |
| User-Agent: Chrome/148 Cefrium/1.0 | Done |
| H.264/AAC video (proprietary codecs build) | Done |
| **Video playback (Surface rendering)** | **Done** |
| **Audio output** | **Done** |
| Surface rendering via ContentViewRenderView | Done |
| arm64 validated on real device | Done |

## Why Cefrium, not WebView?

CEF on Android has been considered out of scope due to its different
process model and UI stack
([Issue #1991](https://web.archive.org/web/2024/https://bitbucket.org/chromiumembedded/cef/issues/1991/add-android-support),
opened 2016;
[CEF Forum](https://www.magpcss.org/ceforum/viewtopic.php?f=7&t=10913),
2013: "CEF is not currently supported on android"). The upstream
[`cef_build.h`](https://github.com/chromiumembedded/cef/blob/master/include/base/cef_build.h)
defines `OS_ANDROID` but no working build configuration or runtime
support existed. Cefrium closes that gap.

| Capability | Cefrium | Android WebView |
|------------|---------|-----------------|
| Off-screen rendering (pixel buffer) | Yes (OnPaint) | No |
| Chromium version control | Pinned to 148 | Varies by device |
| Multi-process control | Yes | No |
| Request interception | Yes (CefRequestHandler) | Yes (shouldInterceptRequest) |
| Custom protocol handlers | Yes (CefSchemeHandlerFactory) | Partial (shouldInterceptRequest) |
| Scroll-driven CSS animations | Yes (Chromium 148) | Depends on device WebView version |
| View Transitions | Yes (Chromium 148) | Depends on device WebView version |
| Web Speech API | Yes | Yes |

## Architecture

- **Off-screen rendering** via `CefRenderHandler::OnPaint` — no Views
  framework, no X11, no Wayland
- **Multi-process** with renderer running as an Android `Service`,
  replacing Unix `fork()`/`execve()`
- **11 Chromium patches** for Android adaptations, managed via CEF's
  existing patch infrastructure (`cef/patch/patches/android/`)
- **Gradle Plugin** generates R.java for Chromium packages in consumer
  projects, solving the multi-package resource ID problem

## Building from source

Prerequisites: Chromium source tree at tag `148.0.7778.167`, ~400 GB disk,
64 GB RAM.

```bash
# 1. Apply CEF patches (including Android-specific)
cd chromium/src
export 'target_os=="android"'=1
python3 cef/tools/patcher.py

# 2. Generate build files
depot_tools/gn gen out/Official_android_arm64 --args='
  target_os = "android"
  target_cpu = "arm64"
  is_official_build = true
  is_debug = false
  is_component_build = false
  android_non_final_r_java = true
  proprietary_codecs = true
  ffmpeg_branding = "Chrome"
'
# Omit proprietary_codecs and ffmpeg_branding for the libre variant.

# 3. Build test APK (standalone, all-in-one — for validation)
ninja -C out/Official_android_arm64 cef/tests/cef_android_test:cefrium_test_apk

# 4. Build SDK AAR (for Gradle consumers)
ninja -C out/Official_android_arm64 cef/cefrium_sdk:cefrium_sdk
```

## Running the test APK

The test APK is a standalone app that bundles everything — useful for
validating that CEF works on a device/emulator before packaging as AAR.

```bash
# Start emulator
third_party/android_sdk/public/emulator/emulator -avd cefrium_x64 &

# Wait for boot, install, launch
adb wait-for-device
adb install out/Official_android_x64/apks/CefriumTest.apk
adb shell am start -n org.cefrium.test/.CefriumTestActivity

# Check logs
adb logcat -s "chromium:*" "CefriumTest:*"
```

## SDK releases

Pre-built SDK AARs will be available on Maven Central soon.
Build from source in the meantime — see [Building from source](#building-from-source).

Two variants are published for each Chromium version:

| Artifact | Codecs | Use case |
|----------|--------|----------|
| `cefrium-sdk` | H.264, H.265, AAC, VP8, VP9, Opus | Full site compatibility — video playback, media streaming. Recommended for production. |
| `cefrium-sdk-libre` | VP8, VP9, Opus, Vorbis | Only royalty-free codecs. For projects that require strict FOSS compliance or operate in jurisdictions where H.264 patents apply. |

Each variant is available for `arm64-v8a` (real devices) and `x86_64`
(emulators).

**Patent note:** H.264 base profile patents expired in 2027. In the EU,
software patents are not enforceable (European Patent Convention,
Article 52). The SDK source code (FFmpeg decoders) is LGPL — patent
licensing is orthogonal to copyright licensing.

## SDK AAR usage

The AAR is a distributable library for standard Android Gradle projects.
Unlike the test APK, the consumer provides AndroidX dependencies and
uses the Cefrium Gradle Plugin for R class generation.

```groovy
// build.gradle (root) — requires AGP 8.6+
plugins { id 'com.android.application' version '8.7.3' apply false }

// app/build.gradle
plugins {
    id 'com.android.application'
    id 'com.cefrium'  // Generates R.java for Chromium packages
}

dependencies {
    implementation files('libs/cefrium-sdk.aar')
    implementation 'androidx.core:core:1.16.0'
    implementation 'androidx.appcompat:appcompat:1.7.0'
}
```

See [cefrium-sample](https://codeberg.org/cefrium/cefrium-sample) for a
complete working consumer project with all required configuration.

## Project structure

```
cef/
├── libcef/browser/android/     # Android browser implementation
├── cefrium_sdk/                # AAR packaging (BUILD.gn, slim_aar.py)
│   ├── gradle-plugin/          # Gradle Plugin for R class generation
│   └── java/src/com/cefrium/   # Java API
├── tests/cef_android_test/     # Test APK
└── patch/patches/android/      # 11 Android-specific Chromium patches
```

## Relationship to upstream CEF

Fork of CEF branch 7778, tracking Chromium 148.0.7778.167.
Branch `android-port` contains all Android changes.
Upstream contribution planned via `git format-patch`.

## Author

**Juan Manuel Mendez Rey** — Debian CEF packager (six architectures:
i386, amd64, arm64, armhf, loong64, ppc64el), now extending CEF to
native Android.

## License

BSD, same as upstream CEF.

---

# Upstream CEF README

The Chromium Embedded Framework (CEF) is a simple framework for embedding Chromium-based browsers in other applications.

# Quick Links

* Project Page - https://github.com/chromiumembedded/cef
* Documentation - https://chromiumembedded.github.io/cef/
* Tutorial - https://chromiumembedded.github.io/cef/tutorial
* General Usage - https://chromiumembedded.github.io/cef/general_usage
* Master Build Quick-Start - https://chromiumembedded.github.io/cef/master_build_quick_start
* Branches and Building - https://chromiumembedded.github.io/cef/branches_and_building
* Announcements - https://groups.google.com/forum/#!forum/cef-announce
* Support Forum - http://www.magpcss.org/ceforum/
* Issue Tracker - https://github.com/chromiumembedded/cef/issues
* C++ API Docs - [Stable release docs](https://cef-builds.spotifycdn.com/docs/stable.html) / [Beta release docs](https://cef-builds.spotifycdn.com/docs/beta.html)
* Downloads - https://cef-builds.spotifycdn.com/index.html
* Donations - http://www.magpcss.org/ceforum/donate.php

# Introduction

CEF is a BSD-licensed open source project founded by Marshall Greenblatt in 2008 and based on the [Google Chromium](http://www.chromium.org/Home) project. Unlike the Chromium project itself, which focuses mainly on Google Chrome application development, CEF focuses on facilitating embedded browser use cases in third-party applications. CEF insulates the user from the underlying Chromium and Blink code complexity by offering production-quality stable APIs, release branches tracking specific Chromium releases, and binary distributions. Most features in CEF have default implementations that provide rich functionality while requiring little or no integration work from the user. There are currently over 100 million installed instances of CEF around the world embedded in products from a wide range of companies and industries. A partial list of companies and products using CEF is available on the [CEF Wikipedia page](http://en.wikipedia.org/wiki/Chromium_Embedded_Framework#Applications_using_CEF). Some use cases for CEF include:

* Embedding an HTML5-compliant Web browser control in an existing native application.
* Creating a light-weight native “shell” application that hosts a user interface developed primarily using Web technologies.
* Rendering Web content “off-screen” in applications that have their own custom drawing frameworks.
* Acting as a host for automated testing of existing Web properties and applications.

CEF supports a wide range of programming languages and operating systems and can be easily integrated into both new and existing applications. It was designed from the ground up with both performance and ease of use in mind. The base framework includes C and C++ programming interfaces exposed via native libraries that insulate the host application from Chromium and Blink implementation details. It provides close integration between the browser and the host application including support for custom plugins, protocols, JavaScript objects and JavaScript extensions. The host application can optionally control resource loading, navigation, context menus, printing and more, while taking advantage of the same performance and HTML5 technologies available in the Google Chrome Web browser.

Numerous individuals and organizations contribute time and resources to support CEF development, but more involvement from the community is always welcome. This includes support for both the core CEF project and external projects that integrate CEF with additional programming languages and frameworks (see the "External Projects" section below). If you are interested in donating time to help with CEF development please see the "Helping Out" section below. If you are interested in donating money to support general CEF development and infrastructure efforts please visit the [CEF Donations](http://www.magpcss.org/ceforum/donate.php) page.

# Getting Started

Users new to CEF development should start by reading the [Tutorial](https://chromiumembedded.github.io/cef/tutorial) for an overview of CEF usage and then proceed to the [General Usage](https://chromiumembedded.github.io/cef/general_usage) page for a more in-depth discussion of architectural and usage issues. Complete API documentation is available [here](https://cef-builds.spotifycdn.com/docs/stable.html). CEF support and related discussion is available on the [CEF Forum](http://www.magpcss.org/ceforum/).

# Binary Distributions

Binary distributions, which include all files necessary to build a CEF-based application, are available on the [Downloads](https://cef-builds.spotifycdn.com/index.html) page. Binary distributions are stand-alone and do not require the download of CEF or Chromium source code. Symbol files for debugging binary distributions of libcef can also be downloaded from the above links.

# Source Distributions

The CEF project is an extension of the Chromium project. CEF maintains development and release branches that track Chromium branches. CEF source code can be downloaded, built and packaged manually or with automated tools. Visit the [Branches and Building](https://chromiumembedded.github.io/cef/branches_and_building) page for more information.

# External Projects

The base CEF framework includes support for the C and C++ programming languages. Thanks to the hard work of external maintainers CEF can integrate with a number of other programming languages and frameworks. These external projects are not maintained by CEF so please contact the respective project maintainer if you have any questions or issues.

* .Net (CEF3) - https://github.com/cefsharp/CefSharp
* .Net (CEF1) - https://bitbucket.org/fddima/cefglue
* .Net/Mono (CEF3) - https://gitlab.com/xiliumhq/chromiumembedded/cefglue
* Delphi - https://github.com/hgourvest/dcef3
* Delphi - https://github.com/salvadordf/CEF4Delphi
* Go - https://github.com/CzarekTomczak/cef2go
* Go - https://github.com/energye/energy
* Java - https://github.com/chromiumembedded/java-cef
* Python - http://code.google.com/p/cefpython/

If you're the maintainer of a project not listed above and would like your project listed here please either post to the [CEF Forum](http://www.magpcss.org/ceforum/) or contact Marshall directly.

# Helping Out

CEF is still very much a work in progress. Some ways that you can help out:

\- Vote for issues in the [CEF issue tracker](https://github.com/chromiumembedded/cef/issues) that are important to you. This helps with development prioritization.

\- Report any bugs that you find or feature requests that are important to you. Make sure to first search for existing issues before creating new ones. Please use the [CEF Forum](http://magpcss.org/ceforum) and not the issue tracker for usage questions. Each CEF issue should:

* Include the CEF revision or binary distribution version.
* Include information about your OS and compiler version.
* If the issue is a bug please provide detailed reproduction information.
* If the issue is a feature please describe why the feature is beneficial.

\- Write unit tests for new or existing functionality.

\- Pull requests and patches are welcome. View open issues in the [CEF issue tracker](https://github.com/chromiumembedded/cef/issues) or search for TODO(cef) in the source code for ideas.

If you would like to contribute source code changes to CEF please follow the below guidelines:

\- Create or find an appropriate issue for each distinct bug, feature or change. 

\- Submit a [pull request](https://chromiumembedded.github.io/cef/contributing_with_git) or create a patch with your changes and attach it to the CEF issue. Changes should:

* Be submitted against the current [CEF master branch](https://github.com/chromiumembedded/cef/tree/master) unless explicitly fixing a bug in a CEF release branch.
* Follow the style of existing CEF source files. In general CEF uses the [Chromium C++ style guide](https://chromium.googlesource.com/chromium/src/+/master/styleguide/c++/c++.md).
* Include new or modified unit tests as appropriate to the functionality.
* Not include unnecessary or unrelated changes.
