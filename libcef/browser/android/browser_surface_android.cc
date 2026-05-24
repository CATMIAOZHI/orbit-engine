// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/browser_surface_android.h"

#include "base/logging.h"
#include "content/browser/renderer_host/render_widget_host_view_android.h"
#include "content/public/browser/render_widget_host.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/web_contents.h"
#include "ui/android/delegated_frame_host_android.h"
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
  // Add WebContents view to WindowAndroid view hierarchy.
  window_->AddChild(view);

  // Connect layer trees: add WebContents' layer as child of
  // WindowAndroid's root layer. ViewAndroid::AddChild only connects
  // the view hierarchy, NOT the cc::Layer hierarchy.
  scoped_refptr<cc::slim::Layer> window_layer = window_->GetLayer();
  scoped_refptr<cc::slim::Layer> view_layer = view->GetLayer();
  if (window_layer && view_layer) {
    window_layer->AddChild(view_layer);
  }

  // Size the window — propagates to kMatchParent children.
  gfx::Size size = compositor_->GetWindowBounds();
  if (!size.IsEmpty()) {
    window_->OnSizeChanged(size.width(), size.height());
    if (view_layer) {
      view_layer->SetBounds(size);
    }
    view->OnPhysicalBackingSizeChanged(size);
    web_contents_->Resize(gfx::Rect(size));
  }

  LOG(INFO) << "BrowserSurfaceAndroid: WebContents attached, size="
            << size.ToString()
            << " window_layer=" << window_layer.get()
            << " view_layer=" << view_layer.get()
            << " view_layer_bounds=" << (view_layer ? view_layer->bounds().ToString() : "null");

  // Tell the WebContents it is now visible.
  web_contents_->WasShown();
  compositor_->SetNeedsComposite();
}

void BrowserSurfaceAndroid::TryAttachRWHVLayer() {
  if (!web_contents_ || !compositor_) return;

  auto* rwhv = web_contents_->GetRenderWidgetHostView();
  if (!rwhv) {
    LOG(INFO) << "BrowserSurfaceAndroid::TryAttachRWHVLayer: RWHV still null";
    return;
  }

  gfx::Size size = compositor_->GetWindowBounds();

  // 1. Show RWHV + WebContents
  rwhv->Show();
  web_contents_->WasShown();

  // 2. Size everything
  if (!size.IsEmpty()) {
    rwhv->SetSize(size);
    web_contents_->Resize(gfx::Rect(size));
  }

  // 3. Attach DFH to our compositor — registers renderer's FrameSinkId
  auto* rwhva = static_cast<content::RenderWidgetHostViewAndroid*>(rwhv);
  auto* dfh = rwhva->delegated_frame_host_for_testing();
  if (dfh && window_->GetCompositor()) {
    dfh->AttachToCompositor(window_->GetCompositor());
  }

  // 4. Tell renderer its viewport size — triggers first CompositorFrame
  auto* rwh = rwhv->GetRenderWidgetHost();
  if (rwh) {
    rwh->SynchronizeVisualProperties();
  }

  compositor_->SetNeedsComposite();

  LOG(INFO) << "BrowserSurfaceAndroid::TryAttachRWHVLayer done"
            << " size=" << size.ToString();
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
