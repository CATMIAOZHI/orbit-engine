// Cefrium — CEF Android headless test harness.
// Uses the C API directly to avoid C++20/C++23 module conflicts.

#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "include/capi/cef_app_capi.h"
#include "include/capi/cef_browser_capi.h"
#include "include/capi/cef_client_capi.h"
#include "include/internal/cef_types.h"

#ifdef ANDROID
#include <android/log.h>
#define LOG_TAG "Cefrium"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) printf(__VA_ARGS__), printf("\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n")
#endif

int main(int argc, char* argv[]) {
  LOGI("Cefrium test starting");

  cef_main_args_t main_args;
  main_args.argc = argc;
  main_args.argv = argv;

  // Try CefExecuteProcess first.
  int exit_code = cef_execute_process(&main_args, NULL, NULL);
  if (exit_code >= 0) {
    return exit_code;
  }

  LOGI("CefExecuteProcess returned %d, continuing as browser process",
       exit_code);

  // Configure settings.
  cef_settings_t settings;
  memset(&settings, 0, sizeof(settings));
  settings.size = sizeof(settings);
  settings.no_sandbox = 1;
  settings.windowless_rendering_enabled = 1;
  settings.multi_threaded_message_loop = 0;
  settings.log_severity = LOGSEVERITY_INFO;

  // Initialize CEF.
  LOGI("Calling cef_initialize...");
  int result = cef_initialize(&main_args, &settings, NULL, NULL);
  if (!result) {
    LOGE("cef_initialize FAILED");
    return 1;
  }
  LOGI("cef_initialize OK");

  LOGI("Calling cef_shutdown...");
  cef_shutdown();

  LOGI("Cefrium test complete");
  return 0;
}
