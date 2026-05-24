// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_BROWSER_SURFACE_ANDROID_H_
#define CEF_LIBCEF_BROWSER_ANDROID_BROWSER_SURFACE_ANDROID_H_

#include <memory>

#include "base/android/scoped_java_ref.h"
#include "content/public/browser/android/compositor.h"
#include "content/public/browser/android/compositor_client.h"
#include "ui/android/window_android.h"

namespace content {
class WebContents;
}

// Manages a content::Compositor that renders a CEF browser's WebContents
// to an Android Surface. This is the bridge between CEF's browser model
// and Chromium's Android rendering pipeline.
//
// Usage:
//   1. Create with a WindowAndroid
//   2. Call SurfaceChanged() when Java provides a Surface
//   3. Call AttachWebContents() after the CEF browser is created
//   4. Chromium renders directly to the Surface — video, audio, GPU compositing
class BrowserSurfaceAndroid : public content::CompositorClient {
 public:
  explicit BrowserSurfaceAndroid(ui::WindowAndroid* window);
  ~BrowserSurfaceAndroid() override;

  // Surface lifecycle — called from JNI when SurfaceView callbacks fire.
  void SurfaceChanged(JNIEnv* env,
                      const base::android::JavaRef<jobject>& surface,
                      int width,
                      int height);
  void SurfaceDestroyed();

  // Attach the CEF browser's WebContents for rendering.
  void AttachWebContents(content::WebContents* web_contents);

  // Try to connect the RenderWidgetHostView's layer to the compositor.
  // Call after page load — RWHV may not exist at browser creation time.
  void TryAttachRWHVLayer();

  // content::CompositorClient implementation.
  void RecreateSurface() override;
  void UpdateLayerTreeHost() override;
  void DidSwapFrame(int pending_frames) override;
  void DidSwapBuffers(const gfx::Size& swap_size) override;

 private:
  ui::WindowAndroid* window_;  // Not owned — must outlive this object.
  std::unique_ptr<content::Compositor> compositor_;
  content::WebContents* web_contents_ = nullptr;
};

#endif  // CEF_LIBCEF_BROWSER_ANDROID_BROWSER_SURFACE_ANDROID_H_
