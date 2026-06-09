// Copyright 2026 The Chromium Embedded Framework Authors. Portions copyright
// 2026 The Chromium Authors. All rights reserved. Use of this source code is
// governed by a BSD-style license that can be found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_CHROME_DEVICE_CHOOSER_IMPL_H_
#define CEF_LIBCEF_BROWSER_CHROME_DEVICE_CHOOSER_IMPL_H_
#pragma once

#include <memory>
#include <string>
#include <vector>

#include "base/memory/weak_ptr.h"
#include "cef/include/cef_device_chooser_handler.h"
#include "chrome/browser/bluetooth/chrome_bluetooth_delegate.h"
#include "chrome/browser/serial/chrome_serial_delegate.h"
#include "chrome/browser/usb/chrome_usb_delegate.h"
#include "components/permissions/chooser_controller.h"
#include "content/public/browser/bluetooth_chooser.h"
#include "content/public/browser/serial_chooser.h"
#include "content/public/browser/usb_chooser.h"

namespace content {
class RenderFrameHost;
}

class CefBrowserHostBase;

// A content::BluetoothChooser that routes the picker UI to the app's
// CefDeviceChooserHandler instead of the (Android-)Chrome native dialog. Devices
// discovered during the scan are forwarded to OnDeviceAdded; the app's
// Select()/Cancel() on the CefDeviceChooserCallback drive the EventHandler.
class CefBluetoothChooser : public content::BluetoothChooser {
 public:
  // Returns nullptr (so the caller cancels) if |browser| has no
  // CefDeviceChooserHandler or the handler declines OnShowDeviceChooser.
  static std::unique_ptr<CefBluetoothChooser> Create(
      CefRefPtr<CefBrowserHostBase> browser,
      content::RenderFrameHost* frame,
      const content::BluetoothChooser::EventHandler& event_handler);

  CefBluetoothChooser(CefRefPtr<CefBrowserHostBase> browser,
                      CefRefPtr<CefDeviceChooserHandler> handler,
                      const content::BluetoothChooser::EventHandler& event_handler);

  CefBluetoothChooser(const CefBluetoothChooser&) = delete;
  CefBluetoothChooser& operator=(const CefBluetoothChooser&) = delete;

  ~CefBluetoothChooser() override;

  // content::BluetoothChooser methods:
  void AddOrUpdateDevice(const std::string& device_id,
                         bool should_update_name,
                         const std::u16string& device_name,
                         bool is_gatt_connected,
                         bool is_paired,
                         int signal_strength_level) override;

  // Called by the callback (on the UI thread) when the app decides.
  void OnSelect(const std::string& device_id);
  void OnCancel();

  base::WeakPtr<CefBluetoothChooser> GetWeakPtr() {
    return weak_factory_.GetWeakPtr();
  }

 private:
  CefRefPtr<CefBrowserHostBase> browser_;
  CefRefPtr<CefDeviceChooserHandler> handler_;
  CefRefPtr<CefDeviceChooserCallback> callback_;
  content::BluetoothChooser::EventHandler event_handler_;
  bool closed_ = false;

  base::WeakPtrFactory<CefBluetoothChooser> weak_factory_{this};
};

// Bridges a permissions::ChooserController (the device enumeration + permission
// logic that Chrome's WebUSB / Web Serial choosers use) to the app's
// CefDeviceChooserHandler. This reuses ALL of Chrome's controller logic and only
// replaces the platform picker UI -- the same role Chrome's UsbChooserDialog
// (etc.) plays, but driven by the embedder instead of an Android dialog that is
// not available in this embedding.
//
// Option indices reported by the controller are mapped to stable opaque ids
// handed to the app, so a mid-scan removal cannot misdirect a later Select().
class CefDeviceChooserView : public permissions::ChooserController::View {
 public:
  CefDeviceChooserView(CefRefPtr<CefBrowserHostBase> browser,
                       CefRefPtr<CefDeviceChooserHandler> handler,
                       cef_device_chooser_type_t type,
                       const CefString& requesting_origin,
                       std::unique_ptr<permissions::ChooserController> controller);

  CefDeviceChooserView(const CefDeviceChooserView&) = delete;
  CefDeviceChooserView& operator=(const CefDeviceChooserView&) = delete;

  ~CefDeviceChooserView() override;

  // permissions::ChooserController::View methods:
  void OnOptionsInitialized() override;
  void OnOptionAdded(size_t index) override;
  void OnOptionRemoved(size_t index) override;
  void OnOptionUpdated(size_t index) override;
  void OnAdapterEnabledChanged(bool enabled) override;
  void OnRefreshStateChanged(bool refreshing) override;

  // Called by the callback (on the UI thread) when the app decides.
  void OnSelect(const std::string& device_id);
  void OnCancel();

 private:
  // Posted after construction so OnShowDeviceChooser and the initial device
  // catch-up never run reentrantly inside RunChooser.
  void Start();
  void EmitDevice(size_t index);

  CefRefPtr<CefBrowserHostBase> browser_;
  CefRefPtr<CefDeviceChooserHandler> handler_;
  cef_device_chooser_type_t type_;
  CefString requesting_origin_;
  std::unique_ptr<permissions::ChooserController> controller_;
  CefRefPtr<CefDeviceChooserCallback> callback_;

  // controller option index -> stable opaque id handed to the app.
  std::vector<uint64_t> ids_;
  uint64_t next_id_ = 1;
  bool started_ = false;
  bool resolved_ = false;
  bool closed_notified_ = false;

  base::WeakPtrFactory<CefDeviceChooserView> weak_factory_{this};
};

// content::UsbChooser / SerialChooser are opaque owning handles: deleting one
// cancels the prompt. Each owns a CefDeviceChooserView (which owns the chrome
// controller), so destroying the handle tears down the controller and runs its
// completion callback (cancel) -- matching Chrome's WebUsbChooser ownership.
class CefUsbChooser : public content::UsbChooser {
 public:
  explicit CefUsbChooser(std::unique_ptr<CefDeviceChooserView> view)
      : view_(std::move(view)) {}
  ~CefUsbChooser() override = default;

 private:
  std::unique_ptr<CefDeviceChooserView> view_;
};

class CefSerialChooser : public content::SerialChooser {
 public:
  explicit CefSerialChooser(std::unique_ptr<CefDeviceChooserView> view)
      : view_(std::move(view)) {}
  ~CefSerialChooser() override = default;

 private:
  std::unique_ptr<CefDeviceChooserView> view_;
};

// A ChromeBluetoothDelegate that inherits all permission/device logic but swaps
// RunBluetoothChooser to route to the app's CefDeviceChooserHandler.
class CefBluetoothDelegate : public ChromeBluetoothDelegate {
 public:
  using ChromeBluetoothDelegate::ChromeBluetoothDelegate;

  std::unique_ptr<content::BluetoothChooser> RunBluetoothChooser(
      content::RenderFrameHost* frame,
      const content::BluetoothChooser::EventHandler& event_handler) override;
};

// A ChromeUsbDelegate that inherits all WebUSB logic but routes the chooser to
// the app's CefDeviceChooserHandler (Chrome's native USB dialog is unavailable
// in this embedding).
class CefUsbDelegate : public ChromeUsbDelegate {
 public:
  using ChromeUsbDelegate::ChromeUsbDelegate;

  std::unique_ptr<content::UsbChooser> RunChooser(
      content::RenderFrameHost& frame,
      blink::mojom::WebUsbRequestDeviceOptionsPtr options,
      blink::mojom::WebUsbService::GetPermissionCallback callback) override;
};

// A ChromeSerialDelegate that inherits all Web Serial logic but routes the
// chooser to the app's CefDeviceChooserHandler.
class CefSerialDelegate : public ChromeSerialDelegate {
 public:
  using ChromeSerialDelegate::ChromeSerialDelegate;

  std::unique_ptr<content::SerialChooser> RunChooser(
      content::RenderFrameHost* frame,
      std::vector<blink::mojom::SerialPortFilterPtr> filters,
      std::vector<::device::BluetoothUUID> allowed_bluetooth_service_class_ids,
      content::SerialChooser::Callback callback) override;
};

#endif  // CEF_LIBCEF_BROWSER_CHROME_DEVICE_CHOOSER_IMPL_H_
