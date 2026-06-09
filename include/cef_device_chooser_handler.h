// Copyright (c) 2026 Marshall A. Greenblatt. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are
// met:
//
//    * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above
// copyright notice, this list of conditions and the following disclaimer
// in the documentation and/or other materials provided with the
// distribution.
//    * Neither the name of Google Inc. nor the name Chromium Embedded
// Framework nor the names of its contributors may be used to endorse
// or promote products derived from this software without specific prior
// written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// ---------------------------------------------------------------------------
//
// The contents of this file must follow a specific format in order to
// support the CEF translator tool. See the translator.README.txt file in the
// tools directory for more information.
//

#ifndef CEF_INCLUDE_CEF_DEVICE_CHOOSER_HANDLER_H_
#define CEF_INCLUDE_CEF_DEVICE_CHOOSER_HANDLER_H_
#pragma once

#include "include/cef_base.h"
#include "include/cef_browser.h"
#include "include/cef_frame.h"

///
/// Callback interface used for the asynchronous continuation of a device
/// chooser. The client calls Select() once the user picks a device, or Cancel()
/// to dismiss the chooser. The same callback instance is passed back to the
/// CefDeviceChooserHandler::OnDeviceAdded and OnDeviceChooserClosed methods so
/// the client can correlate updates with the chooser it opened.
///
/*--cef(source=library,added=experimental)--*/
class CefDeviceChooserCallback : public virtual CefBaseRefCounted {
 public:
  ///
  /// Continue the device request with the device identified by |device_id| (an
  /// id reported to OnDeviceAdded). Has no effect if the chooser is already
  /// closed.
  ///
  /*--cef(capi_name=cont)--*/
  virtual void Select(const CefString& device_id) = 0;

  ///
  /// Cancel the device request (the page's requestDevice()/requestPort() promise
  /// rejects). Has no effect if the chooser is already closed.
  ///
  /*--cef()--*/
  virtual void Cancel() = 0;
};

///
/// Implement this interface to handle device chooser requests for the
/// "frontier" web device APIs (WebUSB, Web Bluetooth, Web Serial). The methods
/// of this class will be called on the browser process UI thread.
///
/// When a page calls navigator.usb.requestDevice(),
/// navigator.bluetooth.requestDevice() or navigator.serial.requestPort() (under
/// a user gesture), OnShowDeviceChooser is called once. Devices then stream in
/// via OnDeviceAdded (continuously while a Bluetooth scan runs; once per present
/// device for USB/Serial). The client presents its own picker UI and calls
/// CefDeviceChooserCallback::Select or Cancel. If no handler is registered, or
/// OnShowDeviceChooser returns false, the request is cancelled (the promise
/// rejects) -- it never hangs.
///
/*--cef(source=client,added=experimental)--*/
class CefDeviceChooserHandler : public virtual CefBaseRefCounted {
 public:
  typedef cef_device_chooser_type_t DeviceChooserType;

  ///
  /// Called when |frame| in |browser| requests a device of |type| from
  /// |requesting_origin|. Return true to handle the request: the client shows a
  /// chooser, retains |callback|, and calls Select()/Cancel() when the user
  /// acts. Return false (default) to cancel the request immediately.
  ///
  /*--cef()--*/
  virtual bool OnShowDeviceChooser(CefRefPtr<CefBrowser> browser,
                                   CefRefPtr<CefFrame> frame,
                                   DeviceChooserType type,
                                   const CefString& requesting_origin,
                                   CefRefPtr<CefDeviceChooserCallback> callback) {
    return false;
  }

  ///
  /// Called as a candidate device becomes available for the chooser associated
  /// with |callback| (the instance from OnShowDeviceChooser). |device_id| is the
  /// opaque id to pass to CefDeviceChooserCallback::Select; |device_name| is a
  /// human-readable label for the picker (may be empty). May be called multiple
  /// times for the same |device_id| to update its label.
  ///
  /*--cef()--*/
  virtual void OnDeviceAdded(CefRefPtr<CefBrowser> browser,
                             CefRefPtr<CefDeviceChooserCallback> callback,
                             const CefString& device_id,
                             const CefString& device_name) {}

  ///
  /// Called when the chooser associated with |callback| is closed by the
  /// framework (for example the requesting frame navigated away). The client
  /// should dismiss its picker UI. After this call |callback| is invalid.
  ///
  /*--cef()--*/
  virtual void OnDeviceChooserClosed(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefDeviceChooserCallback> callback) {}
};

#endif  // CEF_INCLUDE_CEF_DEVICE_CHOOSER_HANDLER_H_
