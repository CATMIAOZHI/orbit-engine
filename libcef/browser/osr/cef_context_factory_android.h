// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CEF_LIBCEF_BROWSER_OSR_CEF_CONTEXT_FACTORY_ANDROID_H_
#define CEF_LIBCEF_BROWSER_OSR_CEF_CONTEXT_FACTORY_ANDROID_H_

#include <map>
#include <memory>

#include "components/viz/common/surfaces/subtree_capture_id_allocator.h"
#include "gpu/ipc/client/gpu_channel_host.h"
#include "ui/compositor/compositor.h"

namespace cc {
class SingleThreadTaskGraphRunner;
}

namespace viz {
class HostDisplayClient;
}

// ContextFactory for Android off-screen rendering. Uses Android's
// CompositorDependenciesAndroid for HostFrameSinkManager and FrameSinkId
// allocation, and creates RootCompositorFrameSinks with software rendering
// for CEF's windowless rendering.
//
// This replaces VizProcessTransportFactory (desktop-only) on Android,
// providing the minimum infrastructure for ui::Compositor to function.
class CefContextFactoryAndroid : public ui::ContextFactory {
 public:
  CefContextFactoryAndroid();

  CefContextFactoryAndroid(const CefContextFactoryAndroid&) = delete;
  CefContextFactoryAndroid& operator=(const CefContextFactoryAndroid&) = delete;

  ~CefContextFactoryAndroid() override;

  // ui::ContextFactory:
  void CreateLayerTreeFrameSink(
      base::WeakPtr<ui::Compositor> compositor) override;
  scoped_refptr<viz::RasterContextProvider>
  SharedMainThreadRasterContextProvider() override;
  void RemoveCompositor(ui::Compositor* compositor) override;
  cc::TaskGraphRunner* GetTaskGraphRunner() override;
  viz::FrameSinkId AllocateFrameSinkId() override;
  viz::SubtreeCaptureId AllocateSubtreeCaptureId() override;
  viz::HostFrameSinkManager* GetHostFrameSinkManager() override;

 private:
  void OnEstablishedGpuChannel(
      base::WeakPtr<ui::Compositor> compositor,
      scoped_refptr<gpu::GpuChannelHost> gpu_channel_host);

  struct CompositorData {
    std::unique_ptr<viz::HostDisplayClient> display_client;
  };

  std::map<ui::Compositor*, CompositorData> compositor_data_;
  std::unique_ptr<cc::SingleThreadTaskGraphRunner> task_graph_runner_;
  viz::SubtreeCaptureIdAllocator subtree_capture_id_allocator_;
  base::WeakPtrFactory<CefContextFactoryAndroid> weak_ptr_factory_{this};
};

#endif  // CEF_LIBCEF_BROWSER_OSR_CEF_CONTEXT_FACTORY_ANDROID_H_
