// Copyright 2026 The Chromium Embedded Framework Authors. Portions copyright
// 2026 The Chromium Authors. All rights reserved. Use of this source code is
// governed by a BSD-style license that can be found in the LICENSE file.

#include "cef/libcef/browser/chrome/device_chooser_impl.h"

#include <cstdlib>
#include <utility>

#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "cef/include/cef_client.h"
#include "cef/include/cef_frame.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "chrome/browser/ui/serial/serial_chooser_controller.h"
#include "chrome/browser/usb/usb_chooser_controller.h"
#include "content/public/browser/browser_thread.h"
#include "content/public/browser/render_frame_host.h"

namespace {

// The CefDeviceChooserCallback handed to the app. Select()/Cancel() may arrive
// on any thread and after the chooser is gone, so they hop to the UI thread and
// run weak-pointer-bound continuations (no-ops if the chooser is already gone).
class CefDeviceChooserCallbackImpl : public CefDeviceChooserCallback {
 public:
  CefDeviceChooserCallbackImpl(
      base::RepeatingCallback<void(const std::string&)> select,
      base::RepeatingClosure cancel)
      : select_(std::move(select)), cancel_(std::move(cancel)) {}

  void Select(const CefString& device_id) override {
    PostOrRun(base::BindOnce(&CefDeviceChooserCallbackImpl::DoSelect, this,
                             device_id.ToString()));
  }

  void Cancel() override {
    PostOrRun(base::BindOnce(&CefDeviceChooserCallbackImpl::DoCancel, this));
  }

 private:
  void PostOrRun(base::OnceClosure task) {
    if (content::BrowserThread::CurrentlyOn(content::BrowserThread::UI)) {
      std::move(task).Run();
    } else {
      content::GetUIThreadTaskRunner({})->PostTask(FROM_HERE, std::move(task));
    }
  }
  void DoSelect(const std::string& id) { select_.Run(id); }
  void DoCancel() { cancel_.Run(); }

  base::RepeatingCallback<void(const std::string&)> select_;
  base::RepeatingClosure cancel_;

  IMPLEMENT_REFCOUNTING(CefDeviceChooserCallbackImpl);
};

CefRefPtr<CefDeviceChooserHandler> HandlerForHost(
    content::RenderFrameHost* frame,
    CefRefPtr<CefBrowserHostBase>* out_browser) {
  CefRefPtr<CefBrowserHostBase> browser =
      CefBrowserHostBase::GetBrowserForHost(frame);
  *out_browser = browser;
  if (!browser || !browser->GetClient()) {
    return nullptr;
  }
  return browser->GetClient()->GetDeviceChooserHandler();
}

}  // namespace

// ============================ Bluetooth ============================

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
  callback_ = new CefDeviceChooserCallbackImpl(
      base::BindRepeating(&CefBluetoothChooser::OnSelect,
                          weak_factory_.GetWeakPtr()),
      base::BindRepeating(&CefBluetoothChooser::OnCancel,
                          weak_factory_.GetWeakPtr()));
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

// ===================== USB / Serial (controller view) =====================

CefDeviceChooserView::CefDeviceChooserView(
    CefRefPtr<CefBrowserHostBase> browser,
    CefRefPtr<CefDeviceChooserHandler> handler,
    cef_device_chooser_type_t type,
    const CefString& requesting_origin,
    std::unique_ptr<permissions::ChooserController> controller)
    : browser_(browser),
      handler_(handler),
      type_(type),
      requesting_origin_(requesting_origin),
      controller_(std::move(controller)) {
  callback_ = new CefDeviceChooserCallbackImpl(
      base::BindRepeating(&CefDeviceChooserView::OnSelect,
                          weak_factory_.GetWeakPtr()),
      base::BindRepeating(&CefDeviceChooserView::OnCancel,
                          weak_factory_.GetWeakPtr()));
  controller_->set_view(this);
  // Defer OnShowDeviceChooser + the initial device catch-up so they never run
  // reentrantly inside RunChooser (a synchronous resolve there could delete
  // this object mid-construction).
  content::GetUIThreadTaskRunner({})->PostTask(
      FROM_HERE, base::BindOnce(&CefDeviceChooserView::Start,
                                weak_factory_.GetWeakPtr()));
}

CefDeviceChooserView::~CefDeviceChooserView() {
  controller_->set_view(nullptr);
  if (started_ && !closed_notified_ && handler_) {
    closed_notified_ = true;
    handler_->OnDeviceChooserClosed(browser_.get(), callback_);
  }
}

void CefDeviceChooserView::Start() {
  if (resolved_) {
    return;
  }
  CefRefPtr<CefFrame> cef_frame = browser_->GetMainFrame();
  if (!handler_->OnShowDeviceChooser(browser_.get(), cef_frame, type_,
                                     requesting_origin_, callback_)) {
    OnCancel();
    return;
  }
  started_ = true;
  // Devices discovered before Start ran are already tracked in ids_; emit them.
  for (size_t i = 0; i < ids_.size(); ++i) {
    EmitDevice(i);
  }
}

void CefDeviceChooserView::EmitDevice(size_t index) {
  if (!handler_ || index >= ids_.size()) {
    return;
  }
  handler_->OnDeviceAdded(browser_.get(), callback_,
                          CefString(std::to_string(ids_[index])),
                          CefString(controller_->GetOption(index)));
}

void CefDeviceChooserView::OnOptionsInitialized() {}

void CefDeviceChooserView::OnOptionAdded(size_t index) {
  // Keep ids_ mirrored with the controller's option list at all times so a
  // later Select() resolves to the right device even across removals.
  if (index > ids_.size()) {
    index = ids_.size();
  }
  ids_.insert(ids_.begin() + index, next_id_++);
  if (started_) {
    EmitDevice(index);
  }
}

void CefDeviceChooserView::OnOptionRemoved(size_t index) {
  if (index >= ids_.size()) {
    return;
  }
  if (started_ && handler_) {
    handler_->OnDeviceRemoved(browser_.get(), callback_,
                              CefString(std::to_string(ids_[index])));
  }
  ids_.erase(ids_.begin() + index);
}

void CefDeviceChooserView::OnOptionUpdated(size_t index) {
  if (started_) {
    EmitDevice(index);  // same id, refreshed label; the app de-dups by id.
  }
}

void CefDeviceChooserView::OnAdapterEnabledChanged(bool enabled) {}

void CefDeviceChooserView::OnRefreshStateChanged(bool refreshing) {}

void CefDeviceChooserView::OnSelect(const std::string& device_id) {
  if (resolved_) {
    return;
  }
  const uint64_t id = std::strtoull(device_id.c_str(), nullptr, 10);
  for (size_t i = 0; i < ids_.size(); ++i) {
    if (ids_[i] == id) {
      resolved_ = true;
      controller_->Select({i});
      return;
    }
  }
  // Unknown / stale id (the device went away): cancel.
  OnCancel();
}

void CefDeviceChooserView::OnCancel() {
  if (resolved_) {
    return;
  }
  resolved_ = true;
  controller_->Cancel();
}

std::unique_ptr<content::UsbChooser> CefUsbDelegate::RunChooser(
    content::RenderFrameHost& frame,
    blink::mojom::WebUsbRequestDeviceOptionsPtr options,
    blink::mojom::WebUsbService::GetPermissionCallback callback) {
  CefRefPtr<CefBrowserHostBase> browser;
  CefRefPtr<CefDeviceChooserHandler> handler = HandlerForHost(&frame, &browser);
  if (!handler) {
    // No handler: cancel (the page promise rejects) rather than show Chrome's
    // native dialog, which is unavailable in this embedding.
    std::move(callback).Run(nullptr);
    return nullptr;
  }
  CefString origin(frame.GetLastCommittedOrigin().Serialize());
  auto controller = std::make_unique<UsbChooserController>(
      &frame, std::move(options), std::move(callback));
  auto view = std::make_unique<CefDeviceChooserView>(
      browser, handler, CEF_DEVICE_CHOOSER_TYPE_USB, origin,
      std::move(controller));
  return std::make_unique<CefUsbChooser>(std::move(view));
}

std::unique_ptr<content::SerialChooser> CefSerialDelegate::RunChooser(
    content::RenderFrameHost* frame,
    std::vector<blink::mojom::SerialPortFilterPtr> filters,
    std::vector<::device::BluetoothUUID> allowed_bluetooth_service_class_ids,
    content::SerialChooser::Callback callback) {
  CefRefPtr<CefBrowserHostBase> browser;
  CefRefPtr<CefDeviceChooserHandler> handler = HandlerForHost(frame, &browser);
  if (!handler) {
    std::move(callback).Run(nullptr);
    return nullptr;
  }
  CefString origin(frame->GetLastCommittedOrigin().Serialize());
  auto controller = std::make_unique<SerialChooserController>(
      frame, std::move(filters),
      std::move(allowed_bluetooth_service_class_ids), std::move(callback));
  auto view = std::make_unique<CefDeviceChooserView>(
      browser, handler, CEF_DEVICE_CHOOSER_TYPE_SERIAL, origin,
      std::move(controller));
  return std::make_unique<CefSerialChooser>(std::move(view));
}
