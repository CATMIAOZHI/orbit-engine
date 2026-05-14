// Copyright 2025 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Stub implementations of desktop-only symbols for the Android build.
// These symbols are referenced by CEF code but come from features that are
// excluded on Android (Views UI, printing, file dialogs, media routing,
// context menus, constrained windows, etc.).

#include "build/build_config.h"

#if BUILDFLAG(IS_ANDROID)

#include <memory>
#include <string>
#include <vector>

#include "base/logging.h"
#include "base/memory/raw_ptr.h"
#include "components/input/native_web_keyboard_event.h"
#include "third_party/blink/public/common/input/web_mouse_wheel_event.h"
#include "third_party/blink/public/common/input/web_mouse_event.h"
#include "third_party/blink/public/common/input/web_touch_event.h"
#include "ui/events/event.h"
#include "ui/shell_dialogs/select_file_policy.h"
#include "content/public/browser/web_drag_dest_delegate.h"
#include "content/public/common/drop_data.h"
#include "components/renderer_context_menu/context_menu_delegate.h"
#include "components/renderer_context_menu/render_view_context_menu_base.h"
#include "cef/include/cef_browser.h"

// Suppress -Wunused-private-field for stub implementations.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-private-field"
#include "cef/include/views/cef_browser_view.h"
#include "cef/include/views/cef_display.h"
#include "cef/include/views/cef_label_button.h"
#include "cef/include/views/cef_menu_button.h"
#include "cef/include/views/cef_panel.h"
#include "cef/include/views/cef_scroll_view.h"
#include "cef/include/views/cef_textfield.h"
#include "cef/include/views/cef_window.h"
#include "cef/libcef/browser/alloy/browser_platform_delegate_alloy.h"
#include "cef/libcef/browser/browser_host_base.h"
#include "cef/libcef/browser/browser_platform_delegate.h"
#include "cef/libcef/browser/chrome/browser_platform_delegate_chrome.h"
#include "cef/libcef/browser/chrome/views/browser_platform_delegate_chrome_child_window.h"
#include "cef/libcef/browser/chrome/views/browser_platform_delegate_chrome_views.h"
#include "cef/libcef/browser/chrome/views/chrome_child_window.h"
#include "cef/libcef/browser/file_dialog_manager.h"
// media_router_manager.h pulls in incomplete types — include it but also
// include the types it forward-declares.
#include "cef/libcef/browser/media_router/media_routes_observer.h"
#include "cef/libcef/browser/media_router/media_router_manager.h"
#include "cef/libcef/browser/menu_manager.h"
#include "cef/libcef/browser/native/browser_platform_delegate_native.h"
#include "cef/libcef/browser/native/browser_platform_delegate_native_aura.h"
#include "cef/libcef/browser/permission_prompt.h"
#include "cef/libcef/browser/views/browser_platform_delegate_views.h"
#include "cef/libcef/browser/views/view_util.h"
#include "cef/libcef/common/util_linux.h"
#include "chrome/browser/chrome_process_singleton.h"
#include "chrome/browser/media/router/providers/cast/dual_media_sink_service.h"
#include "chrome/browser/renderer_context_menu/render_view_context_menu.h"
#include "chrome/browser/themes/theme_service_factory.h"
#include "chrome/browser/ui/startup/startup_browser_creator.h"
#include "chrome/browser/ui/views/chrome_constrained_window_views_client.h"
#include "chrome/browser/ui/views/tab_contents/chrome_web_contents_view_delegate_views.h"
#include "components/constrained_window/constrained_window_views.h"
#include "components/keep_alive_registry/keep_alive_types.h"
#include "components/keep_alive_registry/scoped_keep_alive.h"
#include "components/media_router/common/discovery/media_sink_service_base.h"
#include "content/browser/renderer_host/delegated_frame_host.h"
#include "content/public/browser/context_factory.h"
#include "ui/display/display.h"

// =============================================================================
// Category 1: CefBrowserCreateParams (4 symbols)
// =============================================================================

// static
void CefBrowserCreateParams::InitWindowInfo(CefWindowInfo* window_info,
                                            CefBrowserHostBase* opener) {
  LOG(WARNING) << "CefBrowserCreateParams::InitWindowInfo not implemented on "
                  "Android";
  if (opener && opener->IsAlloyStyle()) {
    window_info->runtime_style = CEF_RUNTIME_STYLE_ALLOY;
  }
}

bool CefBrowserCreateParams::IsChromeStyle() const {
  return true;
}

// static
bool CefBrowserCreateParams::IsChromeStyle(const CefWindowInfo* window_info) {
  if (!window_info) {
    return true;
  }
  return window_info->runtime_style == CEF_RUNTIME_STYLE_CHROME ||
         window_info->runtime_style == CEF_RUNTIME_STYLE_DEFAULT;
}

bool CefBrowserCreateParams::IsWindowless() const {
  return window_info && window_info->windowless_rendering_enabled && client &&
         client->GetRenderHandler().get();
}

void CefBrowserCreateParams::MaybeSetWindowInfo(
    const CefWindowInfo& new_window_info,
    bool allow_alloy_style,
    bool allow_chrome_style) {
  LOG(WARNING) << "CefBrowserCreateParams::MaybeSetWindowInfo not implemented "
                  "on Android";
  window_info = std::make_unique<CefWindowInfo>(new_window_info);
}

// =============================================================================
// Category 2: CefBrowserHost (3 symbols)
// =============================================================================

// static
bool CefBrowserHost::CreateBrowser(
    const CefWindowInfo& windowInfo,
    CefRefPtr<CefClient> client,
    const CefString& url,
    const CefBrowserSettings& settings,
    CefRefPtr<CefDictionaryValue> extra_info,
    CefRefPtr<CefRequestContext> request_context) {
  LOG(WARNING) << "CefBrowserHost::CreateBrowser not implemented on Android";
  return false;
}

// static
CefRefPtr<CefBrowser> CefBrowserHost::CreateBrowserSync(
    const CefWindowInfo& windowInfo,
    CefRefPtr<CefClient> client,
    const CefString& url,
    const CefBrowserSettings& settings,
    CefRefPtr<CefDictionaryValue> extra_info,
    CefRefPtr<CefRequestContext> request_context) {
  LOG(WARNING) << "CefBrowserHost::CreateBrowserSync not implemented on "
                  "Android";
  return nullptr;
}

// static
CefRefPtr<CefBrowser> CefBrowserHost::GetBrowserByIdentifier(int browser_id) {
  LOG(WARNING) << "CefBrowserHost::GetBrowserByIdentifier not implemented on "
                  "Android";
  return nullptr;
}

// =============================================================================
// Category 3: CefBrowserPlatformDelegateAlloy (11 symbols: ctor + vtable)
// =============================================================================

CefBrowserPlatformDelegateAlloy::CefBrowserPlatformDelegateAlloy()
    : weak_ptr_factory_(this) {}

content::WebContents* CefBrowserPlatformDelegateAlloy::CreateWebContents(
    CefBrowserCreateParams& create_params,
    bool& own_web_contents) {
  LOG(WARNING) << "CefBrowserPlatformDelegateAlloy::CreateWebContents not "
                  "implemented on Android";
  own_web_contents = false;
  return nullptr;
}

void CefBrowserPlatformDelegateAlloy::WebContentsCreated(
    content::WebContents* web_contents,
    bool owned) {
  CefBrowserPlatformDelegate::WebContentsCreated(web_contents, owned);
}

void CefBrowserPlatformDelegateAlloy::AddNewContents(
    content::WebContents* source,
    std::unique_ptr<content::WebContents> new_contents,
    const GURL& target_url,
    WindowOpenDisposition disposition,
    const blink::mojom::WindowFeatures& window_features,
    bool user_gesture,
    bool* was_blocked) {
  LOG(WARNING) << "CefBrowserPlatformDelegateAlloy::AddNewContents not "
                  "implemented on Android";
}

void CefBrowserPlatformDelegateAlloy::BrowserCreated(
    CefBrowserHostBase* browser) {
  CefBrowserPlatformDelegate::BrowserCreated(browser);
}

void CefBrowserPlatformDelegateAlloy::BrowserDestroyed(
    CefBrowserHostBase* browser) {
  CefBrowserPlatformDelegate::BrowserDestroyed(browser);
}

web_modal::WebContentsModalDialogHost*
CefBrowserPlatformDelegateAlloy::GetWebContentsModalDialogHost() const {
  return nullptr;
}

void CefBrowserPlatformDelegateAlloy::SendCaptureLostEvent() {}

#if BUILDFLAG(IS_WIN) || (BUILDFLAG(IS_POSIX) && !BUILDFLAG(IS_MAC))
void CefBrowserPlatformDelegateAlloy::NotifyMoveOrResizeStarted() {}
#endif

base::RepeatingClosure
CefBrowserPlatformDelegateAlloy::GetBoundsChangedCallback() {
  return base::RepeatingClosure();
}

void CefBrowserPlatformDelegateAlloy::SetOwnedWebContents(
    content::WebContents* owned_contents) {}

void CefBrowserPlatformDelegateAlloy::AttachHelpers(
    content::WebContents* web_contents) {}

// =============================================================================
// Category 4: CefBrowserPlatformDelegateChrome, ChromeViews, ChildWindow ctors
// =============================================================================

CefBrowserPlatformDelegateChrome::CefBrowserPlatformDelegateChrome(
    std::unique_ptr<CefBrowserPlatformDelegateNative> native_delegate)
    : native_delegate_(std::move(native_delegate)) {}

CefBrowserPlatformDelegateChromeViews::CefBrowserPlatformDelegateChromeViews(
    std::unique_ptr<CefBrowserPlatformDelegateNative> native_delegate,
    CefRefPtr<CefBrowserViewImpl> browser_view)
    : CefBrowserPlatformDelegateChrome(std::move(native_delegate)) {}

CefBrowserPlatformDelegateChromeChildWindow::
    CefBrowserPlatformDelegateChromeChildWindow(
        std::unique_ptr<CefBrowserPlatformDelegateNative> native_delegate,
        CefRefPtr<CefBrowserViewImpl> browser_view)
    : CefBrowserPlatformDelegateChromeViews(std::move(native_delegate),
                                            browser_view) {}

// =============================================================================
// Category 5: CefBrowserPlatformDelegateNativeAura (16 symbols: ctor + vtable)
// =============================================================================

CefBrowserPlatformDelegateNative::CefBrowserPlatformDelegateNative(
    const CefWindowInfo& window_info,
    SkColor background_color)
    : window_info_(window_info), background_color_(background_color) {}

SkColor CefBrowserPlatformDelegateNative::GetBackgroundColor() const {
  return background_color_;
}

void CefBrowserPlatformDelegateNative::WasResized() {}

void CefBrowserPlatformDelegateNative::NotifyScreenInfoChanged() {}

CefBrowserPlatformDelegateNativeAura::CefBrowserPlatformDelegateNativeAura(
    const CefWindowInfo& window_info,
    SkColor background_color)
    : CefBrowserPlatformDelegateNative(window_info, background_color) {}

void CefBrowserPlatformDelegateNativeAura::InstallRootWindowBoundsCallback() {}

void CefBrowserPlatformDelegateNativeAura::RenderViewReady() {}

void CefBrowserPlatformDelegateNativeAura::SendKeyEvent(
    const CefKeyEvent& event) {
  LOG(WARNING) << "CefBrowserPlatformDelegateNativeAura::SendKeyEvent not "
                  "implemented on Android";
}

void CefBrowserPlatformDelegateNativeAura::SendMouseClickEvent(
    const CefMouseEvent& event,
    CefBrowserHost::MouseButtonType type,
    bool mouseUp,
    int clickCount) {
  LOG(WARNING) << "CefBrowserPlatformDelegateNativeAura::SendMouseClickEvent "
                  "not implemented on Android";
}

void CefBrowserPlatformDelegateNativeAura::SendMouseMoveEvent(
    const CefMouseEvent& event,
    bool mouseLeave) {}

void CefBrowserPlatformDelegateNativeAura::SendMouseWheelEvent(
    const CefMouseEvent& event,
    int deltaX,
    int deltaY) {}

void CefBrowserPlatformDelegateNativeAura::SendTouchEvent(
    const CefTouchEvent& event) {}

std::unique_ptr<CefMenuRunner>
CefBrowserPlatformDelegateNativeAura::CreateMenuRunner() {
  return nullptr;
}

gfx::Point CefBrowserPlatformDelegateNativeAura::GetScreenPoint(
    const gfx::Point& view,
    bool want_dip_coords) const {
  return view;
}

input::NativeWebKeyboardEvent
CefBrowserPlatformDelegateNativeAura::TranslateWebKeyEvent(
    const CefKeyEvent& key_event) const {
  return input::NativeWebKeyboardEvent(blink::WebInputEvent::Type::kUndefined,
                                       0, base::TimeTicks());
}

blink::WebMouseEvent
CefBrowserPlatformDelegateNativeAura::TranslateWebClickEvent(
    const CefMouseEvent& mouse_event,
    CefBrowserHost::MouseButtonType type,
    bool mouseUp,
    int clickCount) const {
  return blink::WebMouseEvent();
}

blink::WebMouseEvent
CefBrowserPlatformDelegateNativeAura::TranslateWebMoveEvent(
    const CefMouseEvent& mouse_event,
    bool mouseLeave) const {
  return blink::WebMouseEvent();
}

blink::WebMouseWheelEvent
CefBrowserPlatformDelegateNativeAura::TranslateWebWheelEvent(
    const CefMouseEvent& mouse_event,
    int deltaX,
    int deltaY) const {
  return blink::WebMouseWheelEvent();
}

ui::MouseEvent CefBrowserPlatformDelegateNativeAura::TranslateUiClickEvent(
    const CefMouseEvent& mouse_event,
    CefBrowserHost::MouseButtonType type,
    bool mouseUp,
    int clickCount) const {
  return ui::MouseEvent(ui::EventType::kMousePressed, gfx::Point(),
                        gfx::Point(), base::TimeTicks(), 0, 0);
}

ui::MouseEvent CefBrowserPlatformDelegateNativeAura::TranslateUiMoveEvent(
    const CefMouseEvent& mouse_event,
    bool mouseLeave) const {
  return ui::MouseEvent(ui::EventType::kMouseMoved, gfx::Point(), gfx::Point(),
                        base::TimeTicks(), 0, 0);
}

ui::MouseWheelEvent CefBrowserPlatformDelegateNativeAura::TranslateUiWheelEvent(
    const CefMouseEvent& mouse_event,
    int deltaX,
    int deltaY) const {
  return ui::MouseWheelEvent(gfx::Vector2d(deltaX, deltaY), gfx::Point(),
                             gfx::Point(), base::TimeTicks(), 0, 0);
}

gfx::Vector2d CefBrowserPlatformDelegateNativeAura::GetUiWheelEventOffset(
    int deltaX,
    int deltaY) const {
  return gfx::Vector2d(deltaX, deltaY);
}

std::optional<gfx::Rect>
CefBrowserPlatformDelegateNativeAura::RootWindowBoundsCallback() {
  return std::nullopt;
}

base::OnceClosure
CefBrowserPlatformDelegateNativeAura::GetWidgetDeleteCallback() {
  return base::OnceClosure();
}

// static
base::TimeTicks CefBrowserPlatformDelegateNativeAura::GetEventTimeStamp() {
  return base::TimeTicks::Now();
}

// static
int CefBrowserPlatformDelegateNativeAura::TranslateUiEventModifiers(
    uint32_t cef_modifiers) {
  return 0;
}

// static
int CefBrowserPlatformDelegateNativeAura::TranslateUiChangedButtonFlags(
    uint32_t cef_modifiers) {
  return 0;
}

// =============================================================================
// Category 6: CefBrowserPlatformDelegateViews (1 constructor)
// =============================================================================

CefBrowserPlatformDelegateViews::CefBrowserPlatformDelegateViews(
    std::unique_ptr<CefBrowserPlatformDelegateNative> native_delegate,
    CefRefPtr<CefBrowserViewImpl> browser_view)
    : native_delegate_(std::move(native_delegate)) {}

// =============================================================================
// Category 7: CefBrowserView (2 static methods)
// =============================================================================

// static
CefRefPtr<CefBrowserView> CefBrowserView::CreateBrowserView(
    CefRefPtr<CefClient> client,
    const CefString& url,
    const CefBrowserSettings& settings,
    CefRefPtr<CefDictionaryValue> extra_info,
    CefRefPtr<CefRequestContext> request_context,
    CefRefPtr<CefBrowserViewDelegate> delegate) {
  LOG(WARNING) << "CefBrowserView::CreateBrowserView not implemented on "
                  "Android";
  return nullptr;
}

// static
CefRefPtr<CefBrowserView> CefBrowserView::GetForBrowser(
    CefRefPtr<CefBrowser> browser) {
  return nullptr;
}

// =============================================================================
// Category 8: CefDisplay (8 static methods)
// =============================================================================

// static
CefRefPtr<CefDisplay> CefDisplay::GetPrimaryDisplay() {
  LOG(WARNING) << "CefDisplay::GetPrimaryDisplay not implemented on Android";
  return nullptr;
}

// static
CefRefPtr<CefDisplay> CefDisplay::GetDisplayNearestPoint(
    const CefPoint& point,
    bool input_pixel_coords) {
  return nullptr;
}

// static
CefRefPtr<CefDisplay> CefDisplay::GetDisplayMatchingBounds(
    const CefRect& bounds,
    bool input_pixel_coords) {
  return nullptr;
}

// static
size_t CefDisplay::GetDisplayCount() {
  return 0;
}

// static
void CefDisplay::GetAllDisplays(
    std::vector<CefRefPtr<CefDisplay>>& displays) {
  displays.clear();
}

// static
CefPoint CefDisplay::ConvertScreenPointToPixels(const CefPoint& point) {
  return point;
}

// static
CefPoint CefDisplay::ConvertScreenPointFromPixels(const CefPoint& point) {
  return point;
}

// static
CefRect CefDisplay::ConvertScreenRectToPixels(const CefRect& rect) {
  return rect;
}

// static
CefRect CefDisplay::ConvertScreenRectFromPixels(const CefRect& rect) {
  return rect;
}

// =============================================================================
// Category 9: CefFileDialogManager (5 symbols: ctor, dtor, 3 methods)
// =============================================================================

CefFileDialogManager::CefFileDialogManager(CefBrowserHostBase* browser)
    : browser_(browser) {}

CefFileDialogManager::~CefFileDialogManager() = default;

void CefFileDialogManager::Destroy() {}

void CefFileDialogManager::RunFileDialog(
    cef_file_dialog_mode_t mode,
    const CefString& title,
    const CefString& default_file_path,
    const std::vector<CefString>& accept_filters,
    CefRefPtr<CefRunFileDialogCallback> callback) {
  LOG(WARNING) << "CefFileDialogManager::RunFileDialog not implemented on "
                  "Android";
}

void CefFileDialogManager::RunFileChooser(
    const blink::mojom::FileChooserParams& params,
    RunFileChooserCallback callback) {
  LOG(WARNING) << "CefFileDialogManager::RunFileChooser not implemented on "
                  "Android";
  std::move(callback).Run({});
}

void CefFileDialogManager::RunSelectFile(
    ui::SelectFileDialog::Listener* listener,
    std::unique_ptr<ui::SelectFilePolicy> policy,
    ui::SelectFileDialog::Type type,
    const std::u16string& title,
    const base::FilePath& default_path,
    const ui::SelectFileDialog::FileTypeInfo* file_types,
    int file_type_index,
    const base::FilePath::StringType& default_extension,
    gfx::NativeWindow owning_window) {
  LOG(WARNING) << "CefFileDialogManager::RunSelectFile not implemented on "
                  "Android";
}

void CefFileDialogManager::SelectFileListenerDestroyed(
    ui::SelectFileDialog::Listener* listener) {}

// =============================================================================
// Category 10: Views Create* factory methods (6 symbols)
// =============================================================================

// static
CefRefPtr<CefLabelButton> CefLabelButton::CreateLabelButton(
    CefRefPtr<CefButtonDelegate> delegate,
    const CefString& text) {
  LOG(WARNING) << "CefLabelButton::CreateLabelButton not implemented on "
                  "Android";
  return nullptr;
}

// static
CefRefPtr<CefMenuButton> CefMenuButton::CreateMenuButton(
    CefRefPtr<CefMenuButtonDelegate> delegate,
    const CefString& text) {
  LOG(WARNING) << "CefMenuButton::CreateMenuButton not implemented on Android";
  return nullptr;
}

// static
CefRefPtr<CefPanel> CefPanel::CreatePanel(
    CefRefPtr<CefPanelDelegate> delegate) {
  LOG(WARNING) << "CefPanel::CreatePanel not implemented on Android";
  return nullptr;
}

// static
CefRefPtr<CefScrollView> CefScrollView::CreateScrollView(
    CefRefPtr<CefViewDelegate> delegate) {
  LOG(WARNING) << "CefScrollView::CreateScrollView not implemented on Android";
  return nullptr;
}

// static
CefRefPtr<CefTextfield> CefTextfield::CreateTextfield(
    CefRefPtr<CefTextfieldDelegate> delegate) {
  LOG(WARNING) << "CefTextfield::CreateTextfield not implemented on Android";
  return nullptr;
}

// static
CefRefPtr<CefWindow> CefWindow::CreateTopLevelWindow(
    CefRefPtr<CefWindowDelegate> delegate) {
  LOG(WARNING) << "CefWindow::CreateTopLevelWindow not implemented on Android";
  return nullptr;
}

// =============================================================================
// Category 11: CefMediaRouterManager (7 symbols)
// =============================================================================

CefMediaRouterManager::CefMediaRouterManager(
    content::BrowserContext* browser_context)
    : browser_context_(browser_context),
      query_result_manager_(nullptr),
      weak_ptr_factory_(this) {
  LOG(WARNING) << "CefMediaRouterManager not implemented on Android";
}

CefMediaRouterManager::~CefMediaRouterManager() = default;

void CefMediaRouterManager::AddObserver(Observer* observer) {}

void CefMediaRouterManager::RemoveObserver(Observer* observer) {}

void CefMediaRouterManager::NotifyCurrentSinks() {}

void CefMediaRouterManager::NotifyCurrentRoutes() {}

void CefMediaRouterManager::CreateRoute(
    const media_router::MediaSource::Id& source_id,
    const media_router::MediaSink::Id& sink_id,
    const url::Origin& origin,
    CreateRouteResultCallback callback) {
  LOG(WARNING) << "CefMediaRouterManager::CreateRoute not implemented on "
                  "Android";
}

void CefMediaRouterManager::SendRouteMessage(
    const media_router::MediaRoute::Id& route_id,
    const std::string& message) {}

void CefMediaRouterManager::TerminateRoute(
    const media_router::MediaRoute::Id& route_id) {}

void CefMediaRouterManager::OnSinksUpdated(const MediaSinkVector& sinks) {}

// =============================================================================
// Category 12: CefMenuManager (3 symbols: ctor, CreateContextMenu,
//              CancelContextMenu)
// =============================================================================

CefMenuManager::CefMenuManager(AlloyBrowserHostImpl* browser,
                                std::unique_ptr<CefMenuRunner> runner)
    : browser_(browser),
      runner_(std::move(runner)),
      weak_ptr_factory_(this) {}

CefMenuManager::~CefMenuManager() = default;

void CefMenuManager::Destroy() {}

bool CefMenuManager::IsShowingContextMenu() {
  return false;
}

bool CefMenuManager::CreateContextMenu(const content::ContextMenuParams& params,
                                        bool query_spellcheck) {
  LOG(WARNING) << "CefMenuManager::CreateContextMenu not implemented on "
                  "Android";
  return false;
}

void CefMenuManager::CancelContextMenu() {}

void CefMenuManager::ExecuteCommand(CefRefPtr<CefMenuModelImpl> source,
                                     int command_id,
                                     cef_event_flags_t event_flags) {}

void CefMenuManager::MenuWillShow(CefRefPtr<CefMenuModelImpl> source) {}

void CefMenuManager::MenuClosed(CefRefPtr<CefMenuModelImpl> source) {}

bool CefMenuManager::FormatLabel(CefRefPtr<CefMenuModelImpl> source,
                                  std::u16string& label) {
  return false;
}

// =============================================================================
// Category 13: ChromeWebContentsViewDelegateViews (all methods)
// =============================================================================

ChromeWebContentsViewDelegateViews::ChromeWebContentsViewDelegateViews(
    content::WebContents* web_contents)
    : web_contents_(web_contents) {}

ChromeWebContentsViewDelegateViews::~ChromeWebContentsViewDelegateViews() =
    default;

gfx::NativeWindow ChromeWebContentsViewDelegateViews::GetNativeWindow() {
  return nullptr;
}

content::WebDragDestDelegate*
ChromeWebContentsViewDelegateViews::GetDragDestDelegate() {
  return nullptr;
}

void ChromeWebContentsViewDelegateViews::StoreFocus() {}

bool ChromeWebContentsViewDelegateViews::RestoreFocus() {
  return false;
}

void ChromeWebContentsViewDelegateViews::ResetStoredFocus() {}

bool ChromeWebContentsViewDelegateViews::Focus() {
  return false;
}

bool ChromeWebContentsViewDelegateViews::TakeFocus(bool reverse) {
  return false;
}

void ChromeWebContentsViewDelegateViews::ShowContextMenu(
    content::RenderFrameHost& render_frame_host,
    const content::ContextMenuParams& params) {
  LOG(WARNING) << "ChromeWebContentsViewDelegateViews::ShowContextMenu not "
                  "implemented on Android";
}

void ChromeWebContentsViewDelegateViews::ExecuteCommandForTesting(
    int command_id,
    int event_flags) {}

bool ChromeWebContentsViewDelegateViews::IsContextMenuShowingForTesting() {
  return false;
}

void ChromeWebContentsViewDelegateViews::OnPerformingDrop(
    const content::DropData& drop_data,
    DropCompletionCallback callback) {
  std::move(callback).Run(std::nullopt);
}

std::unique_ptr<RenderViewContextMenuBase>
ChromeWebContentsViewDelegateViews::BuildMenu(
    content::RenderFrameHost& render_frame_host,
    const content::ContextMenuParams& params) {
  return nullptr;
}

void ChromeWebContentsViewDelegateViews::ShowMenu(
    std::unique_ptr<RenderViewContextMenuBase> menu) {}

bool ChromeWebContentsViewDelegateViews::IsMenuRunning() {
  return false;
}

// =============================================================================
// Category 14: chrome_child_window::HasParentHandle (1 symbol)
// =============================================================================

namespace chrome_child_window {

bool HasParentHandle(const CefWindowInfo& window_info) {
  return false;
}

CefRefPtr<CefBrowserHostBase> MaybeCreateChildBrowser(
    const CefBrowserCreateParams& create_params) {
  return nullptr;
}

CefRefPtr<CefBrowserViewDelegate>
GetDefaultBrowserViewDelegateForPopupOpener() {
  return nullptr;
}

}  // namespace chrome_child_window

// =============================================================================
// Category 15: ChromeProcessSingleton::DeleteInstance (1 symbol)
// =============================================================================

// static
void ChromeProcessSingleton::DeleteInstance() {}

// =============================================================================
// Category 16: constrained_window::SetConstrainedWindowViewsClient (1 symbol)
// =============================================================================

namespace constrained_window {

void SetConstrainedWindowViewsClient(
    std::unique_ptr<ConstrainedWindowViewsClient> client) {}

}  // namespace constrained_window

// =============================================================================
// Category 17: content::DelegatedFrameHost (12 symbols)
// =============================================================================

namespace content {

DelegatedFrameHost::DelegatedFrameHost(const viz::FrameSinkId& frame_sink_id,
                                       DelegatedFrameHostClient* client,
                                       bool should_register_frame_sink_id)
    : frame_sink_id_(frame_sink_id),
      client_(client),
      should_register_frame_sink_id_(should_register_frame_sink_id),
      host_frame_sink_manager_(nullptr) {
  LOG(WARNING) << "DelegatedFrameHost not implemented on Android";
}

DelegatedFrameHost::~DelegatedFrameHost() = default;

void DelegatedFrameHost::AddObserverForTesting(Observer* observer) {}
void DelegatedFrameHost::RemoveObserverForTesting(Observer* observer) {}

void DelegatedFrameHost::OnCompositingShuttingDown(
    ui::Compositor* compositor) {}

void DelegatedFrameHost::ClearFallbackSurfaceForCommitPending() {}
void DelegatedFrameHost::ResetFallbackToFirstNavigationSurface() {}

void DelegatedFrameHost::WasHidden(HiddenCause cause) {}

void DelegatedFrameHost::WasShown(
    const viz::LocalSurfaceId& local_surface_id,
    const gfx::Size& dip_size,
    std::optional<blink::RecordContentToVisibleTimeRequest>
        record_tab_switch_time_request) {}

void DelegatedFrameHost::EmbedSurface(
    const viz::LocalSurfaceId& local_surface_id,
    const gfx::Size& dip_size,
    cc::DeadlinePolicy deadline_policy) {}

bool DelegatedFrameHost::HasSavedFrame() const {
  return false;
}

void DelegatedFrameHost::AttachToCompositor(ui::Compositor* compositor) {}

void DelegatedFrameHost::DetachFromCompositor() {}

bool DelegatedFrameHost::CanCopyFromCompositingSurface() const {
  return false;
}

bool DelegatedFrameHost::HasPrimarySurface() const {
  return false;
}

bool DelegatedFrameHost::HasFallbackSurface() const {
  return false;
}

viz::SurfaceId DelegatedFrameHost::GetCurrentSurfaceId() const {
  return viz::SurfaceId();
}

void DelegatedFrameHost::DidNavigate() {}

void DelegatedFrameHost::DidNavigateMainFramePreCommit() {}

void DelegatedFrameHost::DidEnterBackForwardCache() {}

void DelegatedFrameHost::ActivatedOrEvictedFromBackForwardCache() {}

void DelegatedFrameHost::WindowTitleChanged(const std::string& title) {}

void DelegatedFrameHost::TakeFallbackContentFrom(DelegatedFrameHost* other) {}

void DelegatedFrameHost::SetIsFrameSinkIdOwner(bool is_owner) {}

void DelegatedFrameHost::OnFirstSurfaceActivation(
    ui::Compositor* compositor,
    const viz::SurfaceInfo& surface_info) {}

void DelegatedFrameHost::OnFirstSurfaceActivation(
    const viz::SurfaceInfo& surface_info) {}

void DelegatedFrameHost::OnFrameTokenChanged(uint32_t frame_token,
                                             base::TimeTicks activation_time) {}

void DelegatedFrameHost::RequestSuccessfulPresentationTimeForNextFrame(
    blink::RecordContentToVisibleTimeRequest visible_time_request) {}

void DelegatedFrameHost::CancelSuccessfulPresentationTimeRequest() {}

void DelegatedFrameHost::CopyFromCompositingSurface(
    const gfx::Rect& src_subrect,
    const gfx::Size& output_size,
    base::TimeDelta timeout,
    base::OnceCallback<void(const CopyFromSurfaceResult&)> callback) {
  LOG(WARNING) << "DelegatedFrameHost::CopyFromCompositingSurface not "
                  "implemented on Android";
}

void DelegatedFrameHost::CopyFromCompositingSurfaceAsTexture(
    const gfx::Rect& src_subrect,
    const gfx::Size& output_size,
    viz::CopyOutputRequest::CopyOutputRequestCallback callback) {
  LOG(WARNING) << "DelegatedFrameHost::CopyFromCompositingSurfaceAsTexture not "
                  "implemented on Android";
}

void DelegatedFrameHost::EvictDelegatedFrame(
    const std::vector<viz::SurfaceId>& surface_ids) {}

viz::FrameEvictorClient::EvictIds
DelegatedFrameHost::CollectSurfaceIdsForEviction() const {
  return {};
}

viz::SurfaceId DelegatedFrameHost::GetPreNavigationSurfaceId() const {
  return viz::SurfaceId();
}

viz::SurfaceId DelegatedFrameHost::GetFallbackSurfaceIdForTesting() const {
  return viz::SurfaceId();
}

viz::SurfaceId DelegatedFrameHost::GetFirstSurfaceIdAfterNavigationForTesting()
    const {
  return viz::SurfaceId();
}

viz::SurfaceId DelegatedFrameHost::GetBFCacheFallbackSurfaceIdForTesting()
    const {
  return viz::SurfaceId();
}

cc::DeadlinePolicy DelegatedFrameHostClient::GetResizeDeadlinePolicy() const {
  return cc::DeadlinePolicy::UseDefaultDeadline();
}

}  // namespace content

// =============================================================================
// Category 18: content::GetContextFactory (1 symbol)
// =============================================================================

namespace content {

ui::ContextFactory* GetContextFactory() {
  LOG(WARNING) << "content::GetContextFactory not implemented on Android";
  return nullptr;
}

}  // namespace content

// =============================================================================
// Category 19: CreateAlloyConstrainedWindowViewsClient,
//              CreateChromeConstrainedWindowViewsClient (2 symbols)
// =============================================================================

std::unique_ptr<constrained_window::ConstrainedWindowViewsClient>
CreateAlloyConstrainedWindowViewsClient(
    std::unique_ptr<constrained_window::ConstrainedWindowViewsClient>
        chrome_client) {
  return nullptr;
}

std::unique_ptr<constrained_window::ConstrainedWindowViewsClient>
CreateChromeConstrainedWindowViewsClient() {
  return nullptr;
}

// =============================================================================
// Category 20: media_router::DualMediaSinkService (3 symbols),
//              MediaSinkServiceBase (1 symbol)
// =============================================================================

namespace media_router {

// static
DualMediaSinkService* DualMediaSinkService::GetInstance() {
  LOG(WARNING) << "DualMediaSinkService::GetInstance not implemented on "
                  "Android";
  return nullptr;
}

// static
bool DualMediaSinkService::HasInstance() {
  return false;
}

void DualMediaSinkService::DiscoverSinksNow() {}

MediaSinkServiceBase::MediaSinkServiceBase(
    const OnSinksDiscoveredCallback& callback) {}

MediaSinkServiceBase::~MediaSinkServiceBase() = default;

}  // namespace media_router

// =============================================================================
// Category 21: permission_prompt::RegisterCreateCallback (1 symbol)
// =============================================================================

namespace permission_prompt {

void RegisterCreateCallback() {}

}  // namespace permission_prompt

// =============================================================================
// Category 22: RenderViewContextMenu (2 static methods)
// =============================================================================

// static
void RenderViewContextMenu::RegisterMenuCreatedCallback(
    MenuCreatedCallback cb) {}

// static
void RenderViewContextMenu::RegisterMenuShowHandlerCallback(
    MenuShowHandlerCallback cb) {}

// =============================================================================
// Category 23: ScopedKeepAlive (2 symbols: ctor, dtor)
// =============================================================================

ScopedKeepAlive::ScopedKeepAlive(KeepAliveOrigin origin,
                                 KeepAliveRestartOption restart)
    : origin_(origin), restart_(restart) {}

ScopedKeepAlive::~ScopedKeepAlive() = default;

// =============================================================================
// Category 24: StartupBrowserCreator::RegisterProcessCommandLineCallback
// =============================================================================

// static
void StartupBrowserCreator::RegisterProcessCommandLineCallback(
    ProcessCommandLineCallback cb) {}

// =============================================================================
// Category 25: ThemeServiceFactory::GetForProfile (1 symbol)
// =============================================================================

// static
ThemeService* ThemeServiceFactory::GetForProfile(Profile* profile) {
  LOG(WARNING) << "ThemeServiceFactory::GetForProfile not implemented on "
                  "Android";
  return nullptr;
}

// =============================================================================
// Category 26: util_linux::PreSandboxStartup (1 symbol)
// =============================================================================

namespace util_linux {

void PreSandboxStartup() {
  LOG(WARNING) << "util_linux::PreSandboxStartup not implemented on Android";
}

}  // namespace util_linux

// =============================================================================
// Category 27: view_util (2 symbols)
// =============================================================================

namespace view_util {

void ConvertPointFromPixels(gfx::Point* point, float device_scale_factor) {
  // No-op on Android; DIP conversion is handled differently.
}

display::Display GetDisplayNearestPoint(const gfx::Point& point,
                                        bool input_pixel_coords) {
  return display::Display();
}

}  // namespace view_util

#pragma clang diagnostic pop

#endif  // BUILDFLAG(IS_ANDROID)
