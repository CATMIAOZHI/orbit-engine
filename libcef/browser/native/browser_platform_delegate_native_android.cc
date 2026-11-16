// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/native/browser_platform_delegate_native_android.h"

#include "base/android/jni_android.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "cef/libcef/browser/native/menu_runner_android.h"
#include "cef/libcef/browser/thread_util.h"
#include "components/input/native_web_keyboard_event.h"
#include "components/input/input_router.h"
#include "content/browser/renderer_host/render_widget_host_impl.h"
#include "content/browser/renderer_host/render_widget_host_view_android.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/web_contents.h"
#include "third_party/blink/public/common/input/web_mouse_event.h"
#include "third_party/blink/public/common/input/web_mouse_wheel_event.h"
#include "ui/events/base_event_utils.h"
#include "ui/events/event_constants.h"
#include "ui/events/keycodes/dom/dom_code.h"
#include "ui/events/keycodes/dom/dom_key.h"
#include "ui/events/keycodes/keyboard_code_conversion.h"

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

void CefBrowserPlatformDelegateNativeAndroid::SetGestureZoomEnabled(
    bool enabled) {
  if (!web_contents_) {
    return;
  }
  // A windowed browser's view is always a RenderWidgetHostViewAndroid.
  auto* view = static_cast<content::RenderWidgetHostViewAndroid*>(
      web_contents_->GetRenderWidgetHostView());
  if (view) {
    view->SetMultiTouchZoomSupportEnabled(enabled);
    view->SetDoubleTapSupportEnabled(enabled);
  }
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
  content::RenderWidgetHostImpl* rwh =
      content::RenderWidgetHostImpl::From(view->GetRenderWidgetHost());
  if (rwh) {
    // Chromium holds back input while paint-holding shows a snapshot of the
    // previous page (kDropInputEventsWhilePaintHolding), releasing it only when
    // the browser compositor presents the first frame. A programmatically
    // injected mouse event targets the current content by intent, and an
    // embedder may inject before (or without) a presented frame, so release the
    // hold here -- otherwise the event is dropped as kNoConsumerExists.
    if (auto* router = rwh->input_router()) {
      if (!router->IsActive()) {
        router->MakeActive();
      }
    }
  }
  return rwh;
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

  // Build through Android's own WebKeyboardEventBuilder. It derives the
  // Windows key code, dom_code, dom_key and text from the Android key code and
  // the character, which is what Blink turns into event.key and event.code.
  // Constructing from (type, modifiers, timestamp) alone leaves all of those
  // zero, so a page saw keydown/keyup with empty key/code and keyCode 0.
  input::NativeWebKeyboardEvent result(
      base::android::AttachCurrentThread(),
      base::android::JavaRef<jobject>(),  // no originating Android KeyEvent
      type, modifiers, base::TimeTicks::Now(), key_event.native_key_code,
      /*scancode=*/0, key_event.character, key_event.is_system_key != 0);

  // The five-argument CefBrowserHost::SendKeyEvent API documents
  // native_key_code as optional, so a caller may pass only the Windows key
  // code; keep honouring that rather than silently dropping it.
  if (result.windows_key_code == 0 && key_event.windows_key_code != 0) {
    result.windows_key_code = key_event.windows_key_code;
  }

  // An injected event has no Android scancode and no originating KeyEvent
  // object, so the builder leaves dom_code NONE and dom_key UNIDENTIFIED --
  // which reach the page as code "" and key "Unidentified". Fall back to the
  // US layout, the same mapping Chromium uses when there is no physical key.
  if (result.dom_code == static_cast<int>(ui::DomCode::NONE) &&
      result.windows_key_code != 0) {
    result.dom_code = static_cast<int>(ui::UsLayoutKeyboardCodeToDomCode(
        static_cast<ui::KeyboardCode>(result.windows_key_code)));
  }
  if (result.dom_key == static_cast<uint32_t>(ui::DomKey::UNIDENTIFIED) ||
      result.dom_key == static_cast<uint32_t>(ui::DomKey::NONE)) {
    int flags = ui::EF_NONE;
    if (key_event.modifiers & EVENTFLAG_SHIFT_DOWN) flags |= ui::EF_SHIFT_DOWN;
    if (key_event.modifiers & EVENTFLAG_CONTROL_DOWN) {
      flags |= ui::EF_CONTROL_DOWN;
    }
    if (key_event.modifiers & EVENTFLAG_ALT_DOWN) flags |= ui::EF_ALT_DOWN;
    if (key_event.modifiers & EVENTFLAG_CAPS_LOCK_ON) {
      flags |= ui::EF_CAPS_LOCK_ON;
    }
    ui::DomKey dom_key = ui::DomKey::NONE;
    ui::KeyboardCode ignored = ui::VKEY_UNKNOWN;
    if (ui::DomCodeToUsLayoutDomKey(static_cast<ui::DomCode>(result.dom_code),
                                    flags, &dom_key, &ignored)) {
      result.dom_key = dom_key;
    }
  }
  return result;
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
