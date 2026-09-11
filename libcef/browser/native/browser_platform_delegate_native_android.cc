// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/native/browser_platform_delegate_native_android.h"

#include "cef/libcef/browser/browser_host_base.h"
#include "cef/libcef/browser/native/menu_runner_android.h"
#include "cef/libcef/browser/thread_util.h"
#include "components/input/native_web_keyboard_event.h"
#include "content/browser/renderer_host/render_widget_host_impl.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/web_contents.h"
#include "third_party/blink/public/common/input/web_mouse_event.h"
#include "third_party/blink/public/common/input/web_mouse_wheel_event.h"
#include "ui/events/base_event_utils.h"

CefBrowserPlatformDelegateNativeAndroid::
    CefBrowserPlatformDelegateNativeAndroid(const CefWindowInfo& window_info,
                                            SkColor background_color)
    : CefBrowserPlatformDelegateNative(window_info, background_color) {}

bool CefBrowserPlatformDelegateNativeAndroid::CreateHostWindow() {
  // Android does not create native host windows in the same way as desktop.
  // For OSR mode, no host window is needed.
  return true;
}

void CefBrowserPlatformDelegateNativeAndroid::CloseHostWindow() {
  // Android has no native host window -- rendering goes through the Java
  // ContentViewRenderView. On desktop CloseHostWindow() destroys the real OS
  // window, and the platform window-destruction callback re-enters the host's
  // WindowDestroyed(); that second pass is what takes the DestroyBrowser()
  // branch in CloseContents() and ultimately fires OnBeforeClose(). A no-op
  // here leaves a windowed (non-OSR) browser stuck: window_destroyed_ never
  // becomes true, DestroyBrowser() is never reached, and the WebContents --
  // along with every subframe RenderProcessHost (e.g. a cross-origin iframe) --
  // leaks. Drive the handshake ourselves by calling WindowDestroyed(), posted
  // to the UI thread so we do not re-enter CloseBrowser() within the current
  // CloseContents() call (mirrors the async window_x11_->Close() ->
  // WindowDestroyed() path on Linux). OSR/windowless browsers never reach here
  // (CloseContents() short-circuits to DestroyBrowser() when IsWindowless()).
  if (browser_) {
    CEF_POST_TASK(CEF_UIT, base::BindOnce(&CefBrowserHostBase::WindowDestroyed,
                                          browser_.get()));
  }
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
  // Views/Aura widgets are not available on Android.
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

void CefBrowserPlatformDelegateNativeAndroid::SizeTo(int width, int height) {
  // No-op for Android — window sizing is managed by the Android framework.
}

content::RenderWidgetHostImpl*
CefBrowserPlatformDelegateNativeAndroid::GetRenderWidgetHost() const {
  if (!web_contents_) {
    return nullptr;
  }
  content::RenderWidgetHostView* view =
      web_contents_->GetRenderWidgetHostView();
  if (!view) {
    return nullptr;
  }
  return content::RenderWidgetHostImpl::From(view->GetRenderWidgetHost());
}

void CefBrowserPlatformDelegateNativeAndroid::SendMouseClickEvent(
    const CefMouseEvent& event,
    CefBrowserHost::MouseButtonType type,
    bool mouseUp,
    int clickCount) {
  content::RenderWidgetHostImpl* rwh = GetRenderWidgetHost();
  if (!rwh) {
    return;
  }
  blink::WebMouseEvent web_event =
      TranslateWebClickEvent(event, type, mouseUp, clickCount);
  rwh->ForwardMouseEvent(web_event);
}

void CefBrowserPlatformDelegateNativeAndroid::SendMouseMoveEvent(
    const CefMouseEvent& event,
    bool mouseLeave) {
  content::RenderWidgetHostImpl* rwh = GetRenderWidgetHost();
  if (!rwh) {
    return;
  }
  blink::WebMouseEvent web_event = TranslateWebMoveEvent(event, mouseLeave);
  rwh->ForwardMouseEvent(web_event);
}

void CefBrowserPlatformDelegateNativeAndroid::SendMouseWheelEvent(
    const CefMouseEvent& event,
    int deltaX,
    int deltaY) {
  content::RenderWidgetHostImpl* rwh = GetRenderWidgetHost();
  if (!rwh) {
    return;
  }
  blink::WebMouseWheelEvent web_event =
      TranslateWebWheelEvent(event, deltaX, deltaY);
  rwh->ForwardWheelEvent(web_event);
}

gfx::Point CefBrowserPlatformDelegateNativeAndroid::GetScreenPoint(
    const gfx::Point& view,
    bool want_dip_coords) const {
  if (windowless_handler_) {
    return windowless_handler_->GetParentScreenPoint(view, want_dip_coords);
  }
  // Without a windowless handler, return view coordinates as-is.
  return view;
}

void CefBrowserPlatformDelegateNativeAndroid::ViewText(
    const std::string& text) {
  // No-op for Android — no desktop text viewer equivalent.
}

bool CefBrowserPlatformDelegateNativeAndroid::HandleKeyboardEvent(
    const input::NativeWebKeyboardEvent& event) {
  return false;
}

CefEventHandle CefBrowserPlatformDelegateNativeAndroid::GetEventHandle(
    const input::NativeWebKeyboardEvent& event) const {
  return nullptr;
}

std::unique_ptr<CefMenuRunner>
CefBrowserPlatformDelegateNativeAndroid::CreateMenuRunner() {
  return std::make_unique<CefMenuRunnerAndroid>();
}

input::NativeWebKeyboardEvent
CefBrowserPlatformDelegateNativeAndroid::TranslateWebKeyEvent(
    const CefKeyEvent& key_event) const {
  // On Android, NativeWebKeyboardEvent does not have a ui::KeyEvent
  // constructor. Create from blink::WebKeyboardEvent parameters directly.
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

  int modifiers = TranslateWebEventModifiers(key_event.modifiers);

  return input::NativeWebKeyboardEvent(type, modifiers,
                                       base::TimeTicks::Now());
}

blink::WebMouseEvent
CefBrowserPlatformDelegateNativeAndroid::TranslateWebClickEvent(
    const CefMouseEvent& mouse_event,
    CefBrowserHost::MouseButtonType type,
    bool mouseUp,
    int clickCount) const {
  blink::WebMouseEvent result;
  TranslateWebMouseEvent(result, mouse_event);

  switch (type) {
    case MBT_LEFT:
      result.SetType(mouseUp ? blink::WebInputEvent::Type::kMouseUp
                             : blink::WebInputEvent::Type::kMouseDown);
      result.button = blink::WebMouseEvent::Button::kLeft;
      break;
    case MBT_MIDDLE:
      result.SetType(mouseUp ? blink::WebInputEvent::Type::kMouseUp
                             : blink::WebInputEvent::Type::kMouseDown);
      result.button = blink::WebMouseEvent::Button::kMiddle;
      break;
    case MBT_RIGHT:
      result.SetType(mouseUp ? blink::WebInputEvent::Type::kMouseUp
                             : blink::WebInputEvent::Type::kMouseDown);
      result.button = blink::WebMouseEvent::Button::kRight;
      break;
    default:
      DCHECK(false);
  }

  result.click_count = clickCount;

  return result;
}

blink::WebMouseEvent
CefBrowserPlatformDelegateNativeAndroid::TranslateWebMoveEvent(
    const CefMouseEvent& mouse_event,
    bool mouseLeave) const {
  blink::WebMouseEvent result;
  TranslateWebMouseEvent(result, mouse_event);

  if (!mouseLeave) {
    result.SetType(blink::WebInputEvent::Type::kMouseMove);
    if (mouse_event.modifiers & EVENTFLAG_LEFT_MOUSE_BUTTON) {
      result.button = blink::WebMouseEvent::Button::kLeft;
    } else if (mouse_event.modifiers & EVENTFLAG_MIDDLE_MOUSE_BUTTON) {
      result.button = blink::WebMouseEvent::Button::kMiddle;
    } else if (mouse_event.modifiers & EVENTFLAG_RIGHT_MOUSE_BUTTON) {
      result.button = blink::WebMouseEvent::Button::kRight;
    } else {
      result.button = blink::WebMouseEvent::Button::kNoButton;
    }
  } else {
    result.SetType(blink::WebInputEvent::Type::kMouseLeave);
    result.button = blink::WebMouseEvent::Button::kNoButton;
  }

  result.click_count = 0;

  return result;
}

blink::WebMouseWheelEvent
CefBrowserPlatformDelegateNativeAndroid::TranslateWebWheelEvent(
    const CefMouseEvent& mouse_event,
    int deltaX,
    int deltaY) const {
  blink::WebMouseWheelEvent result;
  TranslateWebMouseEvent(result, mouse_event);

  result.SetType(blink::WebInputEvent::Type::kMouseWheel);
  result.delta_x = deltaX;
  result.delta_y = deltaY;

  // Android uses precise pixel scrolling (touch-based).
  result.delta_units = ui::ScrollGranularity::kScrollByPrecisePixel;

  if (mouse_event.modifiers & EVENTFLAG_LEFT_MOUSE_BUTTON) {
    result.button = blink::WebMouseEvent::Button::kLeft;
  } else if (mouse_event.modifiers & EVENTFLAG_MIDDLE_MOUSE_BUTTON) {
    result.button = blink::WebMouseEvent::Button::kMiddle;
  } else if (mouse_event.modifiers & EVENTFLAG_RIGHT_MOUSE_BUTTON) {
    result.button = blink::WebMouseEvent::Button::kRight;
  } else {
    result.button = blink::WebMouseEvent::Button::kNoButton;
  }

  return result;
}

void CefBrowserPlatformDelegateNativeAndroid::TranslateWebMouseEvent(
    blink::WebMouseEvent& result,
    const CefMouseEvent& mouse_event) const {
  // Widget-relative position.
  result.SetPositionInWidget(mouse_event.x, mouse_event.y);

  // Screen position (via windowless handler in OSR mode, identity otherwise).
  const gfx::Point& screen_pt = GetScreenPoint(
      gfx::Point(mouse_event.x, mouse_event.y), /*want_dip_coords=*/false);
  result.SetPositionInScreen(screen_pt.x(), screen_pt.y());

  // Modifiers.
  result.SetModifiers(result.GetModifiers() |
                      TranslateWebEventModifiers(mouse_event.modifiers));

  // Timestamp.
  result.SetTimeStamp(base::TimeTicks::Now());

  result.pointer_type = blink::WebPointerProperties::PointerType::kMouse;
}
