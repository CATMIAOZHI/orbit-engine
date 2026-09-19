// Copyright (c) 2026 The Cefrium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_NET_SERVICE_ANDROID_ASSET_RESPONSE_DELEGATE_H_
#define CEF_LIBCEF_BROWSER_NET_SERVICE_ANDROID_ASSET_RESPONSE_DELEGATE_H_

#include <memory>
#include <string>

#include "components/embedder_support/android/util/android_stream_reader_url_loader.h"
#include "url/gurl.h"

namespace cef {

// True for file:///android_asset/... and file:///android_res/... URLs, which
// load web content bundled in the host APK's assets/ and res/ folders
// (FEATURE-061). These are served by CefAndroidAssetResponseDelegate via the
// app's AssetManager, not by the normal file: loader (which the sandboxed
// renderer cannot use for these).
bool IsAndroidSpecialFileUrl(const GURL& url);

// Serves an APK asset/resource URL by calling the Java CefAndroidProtocolHandler
// (which opens an InputStream from the host app's AssetManager/Resources) and
// feeding it to an embedder_support::AndroidStreamReaderURLLoader.
class CefAndroidAssetResponseDelegate
    : public embedder_support::AndroidStreamReaderURLLoader::ResponseDelegate {
 public:
  explicit CefAndroidAssetResponseDelegate(const GURL& url);

  CefAndroidAssetResponseDelegate(const CefAndroidAssetResponseDelegate&) =
      delete;
  CefAndroidAssetResponseDelegate& operator=(
      const CefAndroidAssetResponseDelegate&) = delete;

  ~CefAndroidAssetResponseDelegate() override;

  // AndroidStreamReaderURLLoader::ResponseDelegate:
  std::unique_ptr<embedder_support::InputStream> OpenInputStream(
      JNIEnv* env) override;
  bool OnInputStreamOpenFailed() override;
  bool GetMimeType(JNIEnv* env,
                   const GURL& url,
                   embedder_support::InputStream* stream,
                   std::string* mime_type) override;
  void GetCharset(JNIEnv* env,
                  const GURL& url,
                  embedder_support::InputStream* stream,
                  std::string* charset) override;
  void AppendResponseHeaders(JNIEnv* env,
                             net::HttpResponseHeaders* headers) override;

 private:
  const GURL url_;
};

}  // namespace cef

#endif  // CEF_LIBCEF_BROWSER_NET_SERVICE_ANDROID_ASSET_RESPONSE_DELEGATE_H_
