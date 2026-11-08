// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

// Input for a browser: IME (FEATURE-062), key events (FEATURE-062, issue #11),
// touch and mouse.

#include <jni.h>

#include <vector>

#include "cef/include/cef_browser.h"
#include "cef/libcef/browser/android/cefrium_sdk_internal.h"
#include "ui/events/keycodes/keyboard_code_conversion_android.h"

// FEATURE-062 (issue #9): OSR IME. These forward an Android InputConnection's
// composing/commit calls to CEF's OSR IME path (CefBrowserHost::Ime*), which
// routes to the renderer. Text is taken as UTF-16 (jchar) so CJK/emoji and
// supplementary-plane characters survive (GetStringUTFChars would mangle them).
namespace {
CefString JStringToCef16(JNIEnv* env, jstring j) {
  CefString out;
  if (!j) return out;
  const jchar* chars = env->GetStringChars(j, nullptr);
  jsize len = env->GetStringLength(j);
  if (chars) {
    out.FromString16(reinterpret_cast<const char16_t*>(chars),
                     static_cast<size_t>(len));
    env->ReleaseStringChars(j, chars);
  }
  return out;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeImeSetComposition(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring text_jstr,
    jint sel_start, jint sel_end) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefString text = JStringToCef16(env, text_jstr);

  std::vector<CefCompositionUnderline> underlines;
  CefCompositionUnderline underline;
  underline.range = CefRange(0, static_cast<uint32_t>(text.length()));
  underline.color = 0xFF000000;       // opaque black
  underline.background_color = 0;
  underline.thick = 0;
  underline.style = CEF_CUS_SOLID;
  underlines.push_back(underline);

  // No replacement range: replace the existing composition/selection.
  CefRange replacement_range = CefRange::InvalidRange();
  CefRange selection_range(static_cast<uint32_t>(sel_start),
                           static_cast<uint32_t>(sel_end));
  data->browser->GetHost()->ImeSetComposition(text, underlines,
                                              replacement_range,
                                              selection_range);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeImeCommitText(
    JNIEnv* env, jclass clazz, jlong native_ptr, jstring text_jstr,
    jint relative_cursor_pos) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefString text = JStringToCef16(env, text_jstr);
  data->browser->GetHost()->ImeCommitText(text, CefRange::InvalidRange(),
                                          relative_cursor_pos);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeImeFinishComposingText(
    JNIEnv* env, jclass clazz, jlong native_ptr, jboolean keep_selection) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  data->browser->GetHost()->ImeFinishComposingText(keep_selection);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeImeCancelComposition(
    JNIEnv* env, jclass clazz, jlong native_ptr) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  data->browser->GetHost()->ImeCancelComposition();
}

// FEATURE-062 (issue #9): OSR key events. Needed alongside IME for backspace,
// enter, arrows and any non-composed keystroke. |type| is cef_key_event_type_t
// (0=RAWKEYDOWN, 1=KEYDOWN, 2=KEYUP, 3=CHAR). |windows_key_code| is a Windows
// virtual-key code (the platform-independent code Blink expects);
// |native_key_code| is the raw Android key code; |char_code| is the produced
// UTF-16 unit (for CHAR events).
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendKeyEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint type, jint modifiers,
    jint windows_key_code, jint native_key_code, jint char_code) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefKeyEvent event;
  event.type = static_cast<cef_key_event_type_t>(type);
  event.modifiers = static_cast<uint32_t>(modifiers);
  event.windows_key_code = windows_key_code;
  event.native_key_code = native_key_code;
  event.is_system_key = 0;
  event.character = static_cast<char16_t>(char_code);
  event.unmodified_character = static_cast<char16_t>(char_code);
  event.focus_on_editable_field = true;
  data->browser->GetHost()->SendKeyEvent(event);
}

// Issue #11: send a key event described the way ANDROID describes it.
//
// The raw nativeSendKeyEvent above needs a WINDOWS virtual-key code, because
// that is what Blink derives DOM `key` and `code` from. An Android embedder has
// an android.view.KeyEvent and no reason to know what a Windows VK is, so the
// five-int API is easy to get wrong: passing windowsKeyCode=0 yields keydown and
// keyup events whose key/code are empty strings and whose keyCode is 0, which
// looks like the engine dropping the event rather than a mis-filled struct.
//
// Do the conversion here with Chromium's own table rather than a hand-written
// one in Java: ui::KeyboardCodeFromAndroidKeyCode is the mapping the rest of
// Chromium-on-Android uses, so it cannot drift from the platform. This mirrors
// what the Windows and Linux platform delegates do with their own
// keyboard_code_conversion_* headers.
//
// `android_meta_state` is an android.view.KeyEvent metaState; the bit values are
// Android's public API constants and are stable.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendKeyEventAndroid(
    JNIEnv* env, jclass clazz, jlong native_ptr, jint type,
    jint android_key_code, jint android_meta_state, jint unicode_char) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;

  // android.view.KeyEvent.META_* -> cef_event_flags_t.
  enum : int {
    kMetaShiftOn = 0x00000001,
    kMetaAltOn = 0x00000002,
    kMetaSymOn = 0x00000004,
    kMetaCapsLockOn = 0x00100000,
    kMetaCtrlOn = 0x00001000,
    kMetaNumLockOn = 0x00200000,
  };
  uint32_t modifiers = EVENTFLAG_NONE;
  if (android_meta_state & kMetaShiftOn) modifiers |= EVENTFLAG_SHIFT_DOWN;
  if (android_meta_state & kMetaAltOn) modifiers |= EVENTFLAG_ALT_DOWN;
  if (android_meta_state & kMetaCtrlOn) modifiers |= EVENTFLAG_CONTROL_DOWN;
  if (android_meta_state & kMetaCapsLockOn) modifiers |= EVENTFLAG_CAPS_LOCK_ON;
  if (android_meta_state & kMetaNumLockOn) modifiers |= EVENTFLAG_NUM_LOCK_ON;

  CefKeyEvent event;
  event.type = static_cast<cef_key_event_type_t>(type);
  event.modifiers = modifiers;
  event.windows_key_code =
      ui::KeyboardCodeFromAndroidKeyCode(android_key_code);
  event.native_key_code = android_key_code;
  event.is_system_key = 0;
  event.character = static_cast<char16_t>(unicode_char);
  event.unmodified_character = static_cast<char16_t>(unicode_char);
  event.focus_on_editable_field = true;
  data->browser->GetHost()->SendKeyEvent(event);
}

// Touch event forwarding: Android MotionEvent → CEF mouse/touch events.
// action: 0=DOWN, 1=UP, 2=MOVE
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendTouchEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jint action, jfloat x, jfloat y, jint pointer_id) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;

  auto host = data->browser->GetHost();
  CefTouchEvent event;
  event.id = pointer_id;
  event.x = x;
  event.y = y;
  event.radius_x = 10;
  event.radius_y = 10;
  event.pressure = 1.0f;

  switch (action) {
    case 0:  // ACTION_DOWN
      event.type = CEF_TET_PRESSED;
      host->SendTouchEvent(event);
      break;
    case 1:  // ACTION_UP
      event.type = CEF_TET_RELEASED;
      event.pressure = 0.0f;
      host->SendTouchEvent(event);
      break;
    case 2:  // ACTION_MOVE
      event.type = CEF_TET_MOVED;
      host->SendTouchEvent(event);
      break;
    case 3:  // ACTION_CANCEL
      event.type = CEF_TET_CANCELLED;
      host->SendTouchEvent(event);
      break;
  }
}

// Mouse event forwarding: programmatic SendMouse* injection (position/hover,
// click, right-click, wheel). Coordinates are view-relative (upper-left origin),
// like touch. |modifiers| are cef_event_flags_t bits (shift/ctrl/alt, and any
// held mouse buttons for drags). |button|: 0=MBT_LEFT, 1=MBT_MIDDLE, 2=MBT_RIGHT.
extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendMouseMoveEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jint x, jint y, jint modifiers, jboolean mouse_leave) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefMouseEvent event;
  event.x = x;
  event.y = y;
  event.modifiers = static_cast<uint32_t>(modifiers);
  data->browser->GetHost()->SendMouseMoveEvent(event, mouse_leave);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendMouseClickEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jint x, jint y, jint modifiers, jint button, jboolean mouse_up,
    jint click_count) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefMouseEvent event;
  event.x = x;
  event.y = y;
  event.modifiers = static_cast<uint32_t>(modifiers);
  data->browser->GetHost()->SendMouseClickEvent(
      event, static_cast<cef_mouse_button_type_t>(button), mouse_up,
      click_count);
}

extern "C" JNIEXPORT void JNICALL
Java_com_cefrium_CefriumBrowser_nativeSendMouseWheelEvent(
    JNIEnv* env, jclass clazz, jlong native_ptr,
    jint x, jint y, jint modifiers, jint delta_x, jint delta_y) {
  auto* data = reinterpret_cast<SdkBrowserData*>(native_ptr);
  if (!data || !data->browser) return;
  CefMouseEvent event;
  event.x = x;
  event.y = y;
  event.modifiers = static_cast<uint32_t>(modifiers);
  data->browser->GetHost()->SendMouseWheelEvent(event, delta_x, delta_y);
}
