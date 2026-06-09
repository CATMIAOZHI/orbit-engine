// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be found
// in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_ANDROID_CEF_SHARE_SERVICE_H_
#define CEF_LIBCEF_BROWSER_ANDROID_CEF_SHARE_SERVICE_H_
#pragma once

#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "third_party/blink/public/mojom/webshare/webshare.mojom.h"

namespace content {
class RenderFrameHost;
}

namespace cefrium {

// Binds the Web Share API (navigator.share) to a CEF implementation that routes
// the share to the embedding app's Android share sheet. Replaces the default
// Android binding (ForwardToJavaWebContents), which targets Chrome's Java
// WebContents share machinery that is absent in this embedding.
void BindShareService(
    content::RenderFrameHost* frame,
    mojo::PendingReceiver<blink::mojom::ShareService> receiver);

}  // namespace cefrium

#endif  // CEF_LIBCEF_BROWSER_ANDROID_CEF_SHARE_SERVICE_H_
