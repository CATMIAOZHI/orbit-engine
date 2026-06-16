// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/android/browser_surface_android.h"

#include "base/logging.h"
#include "content/browser/renderer_host/render_widget_host_view_android.h"
#include "content/public/browser/android/child_process_importance.h"
#include "content/public/browser/render_widget_host.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/web_contents.h"
#include "ui/android/delegated_frame_host_android.h"
#include "ui/android/view_android.h"
#include "ui/android/window_android.h"

namespace {

// Give a surface browser's render processes an explicit Android binding
// importance so they survive aggressive OEM process reapers. The default
// (NORMAL) leaves a visible renderer with only a waived binding; some Android
// builds (e.g. HyperOS) then kill it as an isolated process that is "not
// needed" even while it is foreground (adj 0), taking down the app -- which is
// exactly what Chrome/WebView avoid by marking their visible tab IMPORTANT.
// IMPORTANT for the attached (visible) browser; MODERATE for a detached pooled
// one, so the system reclaims idle pooled browsers FIRST and never the active.
// Subframe importance matches (kSubframeImportance is enabled by default) so a
// cross-origin iframe's separate process (e.g. a YouTube embed) is protected
// too. No-op when the WebContents is gone.
void SetSurfaceImportance(content::WebContents* web_contents,
                          content::ChildProcessImportance importance) {
  if (web_contents) {
    web_contents->SetPrimaryPageImportance(importance, importance);
  }
}

}  // namespace

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

  // If WebContents is already attached, notify it of the new size and that it
  // is visible again. A re-attach happens when a pooled-but-detached browser
  // (e.g. returning to a warm CefriumBrowserPool lesson) gets a fresh surface;
  // WasShown() restores its renderer's foreground importance (paired with the
  // WasHidden() in SurfaceDestroyed()).
  if (web_contents_) {
    gfx::Size size(width, height);
    web_contents_->Resize(gfx::Rect(size));
    web_contents_->GetNativeView()->OnPhysicalBackingSizeChanged(size);
    web_contents_->WasShown();
    SetSurfaceImportance(web_contents_, content::ChildProcessImportance::IMPORTANT);
    compositor_->SetNeedsComposite();
  }
}

void BrowserSurfaceAndroid::SurfaceDestroyed() {
  DCHECK(compositor_);
  compositor_->SetSurface(nullptr, false, nullptr);
  // Mark the WebContents hidden so its renderer drops to a background binding
  // importance. When a CefriumBrowserPool keeps several browsers alive but only
  // one is attached, the detached ones must not all claim foreground priority:
  // on process-per-browser devices that left Android unable to tell the active
  // renderer from the idle ones, so under slot pressure it could kill a needed
  // one. Paired with WasShown() on re-attach in SurfaceChanged(). Guarded:
  // during a browser close the surface bridge is torn down before this runs.
  if (web_contents_) {
    web_contents_->WasHidden();
    SetSurfaceImportance(web_contents_, content::ChildProcessImportance::MODERATE);
  }
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

  // Set the WebContents' layer as the compositor's root layer directly.
  // This bypasses the WindowAndroid's layer and puts the content layer
  // tree directly into the compositor output.
  scoped_refptr<cc::slim::Layer> view_layer = view->GetLayer();
  if (view_layer) {
    compositor_->SetRootLayer(view_layer);
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
            << " view_layer=" << view_layer.get()
            << " view_layer_bounds=" << (view_layer ? view_layer->bounds().ToString() : "null");

  // Tell the WebContents it is now visible.
  web_contents_->WasShown();
  SetSurfaceImportance(web_contents_, content::ChildProcessImportance::IMPORTANT);
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

  // 1. Show RWHV FIRST — sets is_showing_ = true
  rwhv->Show();
  web_contents_->WasShown();
  SetSurfaceImportance(web_contents_, content::ChildProcessImportance::IMPORTANT);

  // 2. Re-trigger OnAttachedToWindow on the RWHVA by removing and
  //    re-adding its view. This time is_showing_ is true, so
  //    StartObservingRootWindow() will be called, which calls
  //    AttachToCompositor() and sets up BeginFrame routing.
  auto* rwhv_view = rwhv->GetNativeView();
  ui::ViewAndroid* wc_view = web_contents_->GetNativeView();
  if (rwhv_view && wc_view) {
    rwhv_view->RemoveFromParent();
    wc_view->AddChild(rwhv_view);

    // 3. Connect cc::Layer hierarchy — ViewAndroid::AddChild does NOT
    //    connect layers. Without this, the content_layer (SurfaceLayer)
    //    floats disconnected and CompositorFrames are invisible.
    if (rwhv_view->GetLayer() && wc_view->GetLayer()) {
      wc_view->GetLayer()->AddChild(rwhv_view->GetLayer());
      rwhv_view->GetLayer()->SetBounds(size);
    }
  }

  // 4. Size everything
  if (!size.IsEmpty()) {
    rwhv->SetSize(size);
    web_contents_->Resize(gfx::Rect(size));
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
