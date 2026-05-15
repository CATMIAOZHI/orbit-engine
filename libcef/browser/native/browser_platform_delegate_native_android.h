// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_NATIVE_BROWSER_PLATFORM_DELEGATE_NATIVE_ANDROID_H_
#define CEF_LIBCEF_BROWSER_NATIVE_BROWSER_PLATFORM_DELEGATE_NATIVE_ANDROID_H_

#include "cef/libcef/browser/native/browser_platform_delegate_native.h"

// Native browser platform delegate for Android.
// Android primarily uses OSR mode. This delegate provides the platform-specific
// event translation layer without Aura/Views dependencies. The Aura layer
// (ui::Compositor, RenderWidgetHostViewAura, views::Widget) is not available
// on Android; events are translated directly to blink::Web*Event types.
class CefBrowserPlatformDelegateNativeAndroid
    : public CefBrowserPlatformDelegateNative {
 public:
  CefBrowserPlatformDelegateNativeAndroid(const CefWindowInfo& window_info,
                                          SkColor background_color);

  // CefBrowserPlatformDelegate methods:
  bool CreateHostWindow() override;
  void CloseHostWindow() override;
  CefWindowHandle GetHostWindowHandle() const override;
  views::Widget* GetWindowWidget() const override;
  void SetFocus(bool setFocus) override;
  void SizeTo(int width, int height) override;
  gfx::Point GetScreenPoint(const gfx::Point& view,
                            bool want_dip_coords) const override;
  void ViewText(const std::string& text) override;
  bool HandleKeyboardEvent(
      const input::NativeWebKeyboardEvent& event) override;
  CefEventHandle GetEventHandle(
      const input::NativeWebKeyboardEvent& event) const override;
  std::unique_ptr<CefMenuRunner> CreateMenuRunner() override;

  // CefBrowserPlatformDelegateNative methods:
  input::NativeWebKeyboardEvent TranslateWebKeyEvent(
      const CefKeyEvent& key_event) const override;
  blink::WebMouseEvent TranslateWebClickEvent(
      const CefMouseEvent& mouse_event,
      CefBrowserHost::MouseButtonType type,
      bool mouseUp,
      int clickCount) const override;
  blink::WebMouseEvent TranslateWebMoveEvent(
      const CefMouseEvent& mouse_event,
      bool mouseLeave) const override;
  blink::WebMouseWheelEvent TranslateWebWheelEvent(
      const CefMouseEvent& mouse_event,
      int deltaX,
      int deltaY) const override;

 private:
  // Set common fields on a blink::WebMouseEvent from a CefMouseEvent.
  void TranslateWebMouseEvent(blink::WebMouseEvent& result,
                              const CefMouseEvent& mouse_event) const;
};

#endif  // CEF_LIBCEF_BROWSER_NATIVE_BROWSER_PLATFORM_DELEGATE_NATIVE_ANDROID_H_
