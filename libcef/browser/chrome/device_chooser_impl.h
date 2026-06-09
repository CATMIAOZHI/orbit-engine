// Copyright 2026 The Chromium Embedded Framework Authors. Portions copyright
// 2026 The Chromium Authors. All rights reserved. Use of this source code is
// governed by a BSD-style license that can be found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_CHROME_DEVICE_CHOOSER_IMPL_H_
#define CEF_LIBCEF_BROWSER_CHROME_DEVICE_CHOOSER_IMPL_H_
#pragma once

#include <memory>
#include <string>

#include "cef/include/cef_device_chooser_handler.h"
#include "chrome/browser/bluetooth/chrome_bluetooth_delegate.h"
#include "content/public/browser/bluetooth_chooser.h"

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

// A ChromeBluetoothDelegate that inherits all permission/device logic but swaps
// RunBluetoothChooser to route to the app's CefDeviceChooserHandler.
class CefBluetoothDelegate : public ChromeBluetoothDelegate {
 public:
  using ChromeBluetoothDelegate::ChromeBluetoothDelegate;

  std::unique_ptr<content::BluetoothChooser> RunBluetoothChooser(
      content::RenderFrameHost* frame,
      const content::BluetoothChooser::EventHandler& event_handler) override;
};

#endif  // CEF_LIBCEF_BROWSER_CHROME_DEVICE_CHOOSER_IMPL_H_
