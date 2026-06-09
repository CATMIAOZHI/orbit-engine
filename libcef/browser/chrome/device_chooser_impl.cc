// Copyright 2026 The Chromium Embedded Framework Authors. Portions copyright
// 2026 The Chromium Authors. All rights reserved. Use of this source code is
// governed by a BSD-style license that can be found in the LICENSE file.

#include "cef/libcef/browser/chrome/device_chooser_impl.h"

#include <utility>

#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "cef/include/cef_frame.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/render_frame_host.h"

namespace {

// The CefDeviceChooserCallback handed to the app. Select()/Cancel() may arrive
// on any thread and after the chooser is gone, so they hop to the UI thread and
// guard the chooser with a weak pointer.
class CefDeviceChooserCallbackImpl : public CefDeviceChooserCallback {
 public:
  explicit CefDeviceChooserCallbackImpl(
      base::WeakPtr<CefBluetoothChooser> chooser)
      : chooser_(std::move(chooser)) {}

  void Select(const CefString& device_id) override {
    const std::string id = device_id.ToString();
    if (content::BrowserThread::CurrentlyOn(content::BrowserThread::UI)) {
      DoSelect(id);
    } else {
      content::GetUIThreadTaskRunner({})->PostTask(
          FROM_HERE,
          base::BindOnce(&CefDeviceChooserCallbackImpl::DoSelect, this, id));
    }
  }

  void Cancel() override {
    if (content::BrowserThread::CurrentlyOn(content::BrowserThread::UI)) {
      DoCancel();
    } else {
      content::GetUIThreadTaskRunner({})->PostTask(
          FROM_HERE,
          base::BindOnce(&CefDeviceChooserCallbackImpl::DoCancel, this));
    }
  }

 private:
  void DoSelect(const std::string& id) {
    if (chooser_) {
      chooser_->OnSelect(id);
    }
  }
  void DoCancel() {
    if (chooser_) {
      chooser_->OnCancel();
    }
  }

  base::WeakPtr<CefBluetoothChooser> chooser_;

  IMPLEMENT_REFCOUNTING(CefDeviceChooserCallbackImpl);
};

}  // namespace

// static
std::unique_ptr<CefBluetoothChooser> CefBluetoothChooser::Create(
    CefRefPtr<CefBrowserHostBase> browser,
    content::RenderFrameHost* frame,
    const content::BluetoothChooser::EventHandler& event_handler) {
  if (!browser) {
    return nullptr;
  }
  CefRefPtr<CefClient> client = browser->GetClient();
  if (!client) {
    return nullptr;
  }
  CefRefPtr<CefDeviceChooserHandler> handler =
      client->GetDeviceChooserHandler();
  if (!handler) {
    return nullptr;
  }

  auto chooser =
      std::make_unique<CefBluetoothChooser>(browser, handler, event_handler);

  CefRefPtr<CefFrame> cef_frame = browser->GetMainFrame();
  CefString origin(frame->GetLastCommittedOrigin().Serialize());
  if (!handler->OnShowDeviceChooser(browser.get(), cef_frame,
                                    CEF_DEVICE_CHOOSER_TYPE_BLUETOOTH, origin,
                                    chooser->callback_)) {
    // Declined: cancel the request (the page promise rejects).
    chooser->OnCancel();
  }
  return chooser;
}

CefBluetoothChooser::CefBluetoothChooser(
    CefRefPtr<CefBrowserHostBase> browser,
    CefRefPtr<CefDeviceChooserHandler> handler,
    const content::BluetoothChooser::EventHandler& event_handler)
    : browser_(browser),
      handler_(handler),
      event_handler_(event_handler) {
  callback_ = new CefDeviceChooserCallbackImpl(weak_factory_.GetWeakPtr());
}

CefBluetoothChooser::~CefBluetoothChooser() {
  if (!closed_ && handler_) {
    handler_->OnDeviceChooserClosed(browser_.get(), callback_);
  }
}

void CefBluetoothChooser::AddOrUpdateDevice(const std::string& device_id,
                                            bool should_update_name,
                                            const std::u16string& device_name,
                                            bool is_gatt_connected,
                                            bool is_paired,
                                            int signal_strength_level) {
  if (closed_ || !handler_) {
    return;
  }
  handler_->OnDeviceAdded(browser_.get(), callback_, device_id,
                          CefString(device_name));
}

void CefBluetoothChooser::OnSelect(const std::string& device_id) {
  if (closed_) {
    return;
  }
  closed_ = true;
  event_handler_.Run(content::BluetoothChooserEvent::SELECTED, device_id);
}

void CefBluetoothChooser::OnCancel() {
  if (closed_) {
    return;
  }
  closed_ = true;
  event_handler_.Run(content::BluetoothChooserEvent::CANCELLED, std::string());
}

std::unique_ptr<content::BluetoothChooser>
CefBluetoothDelegate::RunBluetoothChooser(
    content::RenderFrameHost* frame,
    const content::BluetoothChooser::EventHandler& event_handler) {
  CefRefPtr<CefBrowserHostBase> browser =
      CefBrowserHostBase::GetBrowserForHost(frame);
  if (auto chooser =
          CefBluetoothChooser::Create(browser, frame, event_handler)) {
    return chooser;
  }
  // No CefDeviceChooserHandler: do NOT fall back to Chrome's native chooser
  // (unavailable in this embedding); cancel so the page promise rejects rather
  // than hanging.
  event_handler.Run(content::BluetoothChooserEvent::CANCELLED, std::string());
  return nullptr;
}
