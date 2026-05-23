// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/browser_surface_android.h"

#include "base/logging.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/web_contents.h"
#include "ui/android/view_android.h"
#include "ui/android/window_android.h"

BrowserSurfaceAndroid::BrowserSurfaceAndroid(ui::WindowAndroid* window)
    : window_(window) {
  DCHECK(window_);

  // Create the compositor. It will render to the Surface when one is provided.
  compositor_.reset(content::Compositor::Create(this, window_));
  compositor_->SetBackgroundColor(SK_ColorWHITE);

  LOG(INFO) << "BrowserSurfaceAndroid: compositor created";
}

BrowserSurfaceAndroid::~BrowserSurfaceAndroid() {
  if (web_contents_) {
    web_contents_->GetNativeView()->RemoveFromParent();
  }
  compositor_.reset();
}

void BrowserSurfaceAndroid::SurfaceChanged(
    JNIEnv* env,
    const base::android::JavaRef<jobject>& surface,
    int width,
    int height) {
  DCHECK(compositor_);

  compositor_->SetSurface(surface, /*can_be_used_with_surface_control=*/true,
                          /*host_input_token=*/nullptr);
  compositor_->SetWindowBounds(gfx::Size(width, height));

  LOG(INFO) << "BrowserSurfaceAndroid: surface set " << width << "x" << height;

  // If WebContents is already attached, notify it of the new size.
  if (web_contents_) {
    gfx::Size size(width, height);
    web_contents_->Resize(gfx::Rect(size));
    web_contents_->GetNativeView()->OnPhysicalBackingSizeChanged(size);
    compositor_->SetNeedsComposite();
  }
}

void BrowserSurfaceAndroid::SurfaceDestroyed() {
  DCHECK(compositor_);
  compositor_->SetSurface(nullptr, false, nullptr);
  LOG(INFO) << "BrowserSurfaceAndroid: surface destroyed";
}

void BrowserSurfaceAndroid::AttachWebContents(
    content::WebContents* web_contents) {
  DCHECK(web_contents);
  DCHECK(compositor_);

  web_contents_ = web_contents;

  // Attach the WebContents' view to our WindowAndroid.
  ui::ViewAndroid* view = web_contents_->GetNativeView();
  if (!view) {
    LOG(ERROR) << "BrowserSurfaceAndroid: GetNativeView() returned null!";
    return;
  }
  window_->AddChild(view);

  // Set the compositor's root layer to the WebContents' layer.
  scoped_refptr<cc::slim::Layer> layer = view->GetLayer();
  if (!layer) {
    LOG(ERROR) << "BrowserSurfaceAndroid: GetLayer() returned null!";
    return;
  }
  compositor_->SetRootLayer(layer);

  LOG(INFO) << "BrowserSurfaceAndroid: WebContents attached, layer="
            << layer.get() << " bounds=" << layer->bounds().ToString();

  // Set the size on the WindowAndroid (parent), NOT on the WebContents'
  // ViewAndroid directly — WebContents' view is kMatchParent and inherits
  // from its parent. OnSizeChanged propagates to kMatchParent children.
  gfx::Size size = compositor_->GetWindowBounds();
  if (!size.IsEmpty()) {
    window_->OnSizeChanged(size.width(), size.height());
    view->OnPhysicalBackingSizeChanged(size);
    web_contents_->Resize(gfx::Rect(size));
    LOG(INFO) << "BrowserSurfaceAndroid: window resized to " << size.ToString()
              << " view layer bounds=" << view->GetLayer()->bounds().ToString();
  }

  // Tell the WebContents it is now visible — this activates the
  // renderer's compositing pipeline.
  web_contents_->WasShown();

  // Request a composite to trigger the first frame.
  compositor_->SetNeedsComposite();
}

// CompositorClient implementation.

void BrowserSurfaceAndroid::RecreateSurface() {
  // The compositor is requesting a new Surface. This happens when the
  // GPU context is lost. We'll get a new surfaceChanged() from Java.
  LOG(INFO) << "BrowserSurfaceAndroid: RecreateSurface requested";
}

void BrowserSurfaceAndroid::UpdateLayerTreeHost() {
  // Called before each composite. Nothing to do — the layer tree
  // is updated by the WebContents' rendering pipeline.
}

void BrowserSurfaceAndroid::DidSwapFrame(int pending_frames) {
  // A frame was rendered to the Surface.
}

void BrowserSurfaceAndroid::DidSwapBuffers(const gfx::Size& swap_size) {
  // Swap completed.
}
