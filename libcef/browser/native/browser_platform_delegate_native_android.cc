// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/native/browser_platform_delegate_native_android.h"

#include "cef/libcef/browser/browser_host_base.h"
#include "cef/libcef/browser/context.h"
#include "cef/libcef/browser/thread_util.h"
#include "components/input/native_web_keyboard_event.h"
#include "ui/events/keycodes/dom/dom_key.h"
#include "ui/events/keycodes/dom/keycode_converter.h"

CefBrowserPlatformDelegateNativeAndroid::
    CefBrowserPlatformDelegateNativeAndroid(const CefWindowInfo& window_info,
                                            SkColor background_color)
    : CefBrowserPlatformDelegateNativeAura(window_info, background_color) {}

void CefBrowserPlatformDelegateNativeAndroid::BrowserDestroyed(
    CefBrowserHostBase* browser) {
  CefBrowserPlatformDelegateNativeAura::BrowserDestroyed(browser);
}

bool CefBrowserPlatformDelegateNativeAndroid::CreateHostWindow() {
  // Android does not create native host windows in the same way as Linux.
  // For OSR mode, no host window is needed.
  return true;
}

void CefBrowserPlatformDelegateNativeAndroid::CloseHostWindow() {
  // No-op for Android.
}

CefWindowHandle
CefBrowserPlatformDelegateNativeAndroid::GetHostWindowHandle() const {
  if (windowless_handler_) {
    return windowless_handler_->GetParentWindowHandle();
  }
  return window_info_.window;
}

views::Widget*
CefBrowserPlatformDelegateNativeAndroid::GetWindowWidget() const {
  return nullptr;
}

void CefBrowserPlatformDelegateNativeAndroid::SetFocus(bool setFocus) {
  if (!setFocus) {
    return;
  }
  if (web_contents_) {
    web_contents_->Focus();
  }
}

void CefBrowserPlatformDelegateNativeAndroid::NotifyMoveOrResizeStarted() {
  CefBrowserPlatformDelegateNativeAura::NotifyMoveOrResizeStarted();
}

void CefBrowserPlatformDelegateNativeAndroid::SizeTo(int width, int height) {
  // No-op for Android stub.
}

void CefBrowserPlatformDelegateNativeAndroid::ViewText(
    const std::string& text) {
  // No-op for Android — no xdg-open equivalent needed for PoC.
}

bool CefBrowserPlatformDelegateNativeAndroid::HandleKeyboardEvent(
    const input::NativeWebKeyboardEvent& event) {
  return false;
}

CefEventHandle CefBrowserPlatformDelegateNativeAndroid::GetEventHandle(
    const input::NativeWebKeyboardEvent& event) const {
  return nullptr;
}

ui::KeyEvent CefBrowserPlatformDelegateNativeAndroid::TranslateUiKeyEvent(
    const CefKeyEvent& key_event) const {
  int flags = TranslateUiEventModifiers(key_event.modifiers);
  ui::KeyboardCode key_code =
      static_cast<ui::KeyboardCode>(key_event.windows_key_code);
  ui::DomCode dom_code =
      ui::KeycodeConverter::NativeKeycodeToDomCode(key_event.native_key_code);
  char16_t character = key_event.character;
  base::TimeTicks time_stamp = GetEventTimeStamp();

  if (key_event.type == KEYEVENT_CHAR) {
    return ui::KeyEvent::FromCharacter(character, key_code, dom_code, flags,
                                       time_stamp);
  }

  ui::EventType type = ui::EventType::kUnknown;
  switch (key_event.type) {
    case KEYEVENT_RAWKEYDOWN:
    case KEYEVENT_KEYDOWN:
      type = ui::EventType::kKeyPressed;
      break;
    case KEYEVENT_KEYUP:
      type = ui::EventType::kKeyReleased;
      break;
    default:
      DCHECK(false);
  }

  ui::DomKey dom_key = ui::DomKey::NONE;
  return ui::KeyEvent(type, key_code, dom_code, flags, dom_key, time_stamp);
}

input::NativeWebKeyboardEvent
CefBrowserPlatformDelegateNativeAndroid::TranslateWebKeyEvent(
    const CefKeyEvent& key_event) const {
  // On Android, NativeWebKeyboardEvent does not have a ui::KeyEvent constructor.
  // Create from blink::WebKeyboardEvent parameters directly.
  blink::WebInputEvent::Type type;
  switch (key_event.type) {
    case KEYEVENT_RAWKEYDOWN:
      type = blink::WebInputEvent::Type::kRawKeyDown;
      break;
    case KEYEVENT_KEYDOWN:
      type = blink::WebInputEvent::Type::kKeyDown;
      break;
    case KEYEVENT_KEYUP:
      type = blink::WebInputEvent::Type::kKeyUp;
      break;
    case KEYEVENT_CHAR:
      type = blink::WebInputEvent::Type::kChar;
      break;
    default:
      type = blink::WebInputEvent::Type::kUndefined;
      break;
  }

  int modifiers = 0;
  if (key_event.modifiers & EVENTFLAG_SHIFT_DOWN)
    modifiers |= blink::WebInputEvent::kShiftKey;
  if (key_event.modifiers & EVENTFLAG_CONTROL_DOWN)
    modifiers |= blink::WebInputEvent::kControlKey;
  if (key_event.modifiers & EVENTFLAG_ALT_DOWN)
    modifiers |= blink::WebInputEvent::kAltKey;

  return input::NativeWebKeyboardEvent(
      type, modifiers, base::TimeTicks::Now());
}
