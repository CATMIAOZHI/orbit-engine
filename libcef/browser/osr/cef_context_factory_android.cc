// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "cef/libcef/browser/osr/cef_context_factory_android.h"

#include "cc/mojo_embedder/async_layer_tree_frame_sink.h"
#include "cc/raster/single_thread_task_graph_runner.h"
#include "content/browser/compositor/surface_utils.h"
#include "content/browser/gpu/browser_gpu_channel_host_factory.h"
#include "content/browser/renderer_host/compositor_dependencies_android.h"
#include "components/viz/common/features.h"
#include "components/viz/host/host_display_client.h"
#include "components/viz/host/renderer_settings_creation.h"
#include "gpu/ipc/client/gpu_channel_host.h"
#include "services/viz/privileged/mojom/compositing/frame_sink_manager.mojom.h"
#include "ui/compositor/compositor.h"

CefContextFactoryAndroid::CefContextFactoryAndroid()
    : task_graph_runner_(
          std::make_unique<cc::SingleThreadTaskGraphRunner>()) {
  task_graph_runner_->Start("CefTileWorker",
                            base::SimpleThread::Options());
}

CefContextFactoryAndroid::~CefContextFactoryAndroid() {
  task_graph_runner_->Shutdown();
}

void CefContextFactoryAndroid::CreateLayerTreeFrameSink(
    base::WeakPtr<ui::Compositor> compositor) {
  // Establish a GPU channel asynchronously. Even with proxy output device,
  // a GPU channel is needed for SharedImageInterface.
  content::BrowserGpuChannelHostFactory::instance()->EstablishGpuChannel(
      base::BindOnce(&CefContextFactoryAndroid::OnEstablishedGpuChannel,
                     weak_ptr_factory_.GetWeakPtr(), compositor));
}

void CefContextFactoryAndroid::OnEstablishedGpuChannel(
    base::WeakPtr<ui::Compositor> compositor_weak,
    scoped_refptr<gpu::GpuChannelHost> gpu_channel_host) {
  ui::Compositor* compositor = compositor_weak.get();
  if (!compositor) {
    return;
  }

  // Ensure the viz connection is established. On Android, this is normally
  // triggered by CompositorImpl::OnGpuChannelEstablished, but CEF doesn't
  // use CompositorImpl.
  content::CompositorDependenciesAndroid::Get()
      .TryEstablishVizConnectionIfNeeded();

  if (!gpu_channel_host) {
    LOG(ERROR) << "CefContextFactoryAndroid: GPU channel not available";
    return;
  }

  auto& data = compositor_data_[compositor];

  auto root_params = viz::mojom::RootCompositorFrameSinkParams::New();

  // Create mojo interfaces for a root CompositorFrameSink.
  mojo::PendingAssociatedRemote<viz::mojom::CompositorFrameSink> sink_remote;
  root_params->compositor_frame_sink =
      sink_remote.InitWithNewEndpointAndPassReceiver();
  mojo::PendingReceiver<viz::mojom::CompositorFrameSinkClient> client_receiver =
      root_params->compositor_frame_sink_client
          .InitWithNewPipeAndPassReceiver();
  mojo::AssociatedRemote<viz::mojom::DisplayPrivate> display_private;
  root_params->display_private =
      display_private.BindNewEndpointAndPassReceiver();

  // CEF always sets the compositor delegate (CefRenderWidgetHostViewOSR)
  // which provides the display client for off-screen rendering.
  CHECK(compositor->delegate());
  data.display_client =
      compositor->delegate()->CreateHostDisplayClient();
  root_params->use_proxy_output_device =
      compositor->delegate()->UseProxyOutputDevice();
  root_params->display_client =
      data.display_client->GetBoundRemote(
          base::SingleThreadTaskRunner::GetCurrentDefault());

  root_params->frame_sink_id = compositor->frame_sink_id();
  root_params->gpu_compositing = true;
  root_params->renderer_settings = viz::CreateRendererSettings();

  GetHostFrameSinkManager()->CreateRootCompositorFrameSink(
      std::move(root_params));

  // Create LayerTreeFrameSink with the browser end of CompositorFrameSink.
  cc::mojo_embedder::AsyncLayerTreeFrameSink::InitParams params;
  params.compositor_task_runner = compositor->task_runner();
  params.pipes.compositor_frame_sink_associated_remote =
      std::move(sink_remote);
  params.pipes.client_receiver = std::move(client_receiver);

  scoped_refptr<gpu::SharedImageInterface> shared_image_interface =
      gpu_channel_host->CreateClientSharedImageInterface();

  auto frame_sink =
      std::make_unique<cc::mojo_embedder::AsyncLayerTreeFrameSink>(
          /*context_provider=*/nullptr, /*worker_context_provider=*/nullptr,
          std::move(shared_image_interface), &params);
  compositor->SetLayerTreeFrameSink(std::move(frame_sink),
                                    std::move(display_private));
}

scoped_refptr<viz::RasterContextProvider>
CefContextFactoryAndroid::SharedMainThreadRasterContextProvider() {
  return nullptr;
}

void CefContextFactoryAndroid::RemoveCompositor(ui::Compositor* compositor) {
  compositor_data_.erase(compositor);
}

cc::TaskGraphRunner* CefContextFactoryAndroid::GetTaskGraphRunner() {
  return task_graph_runner_.get();
}

viz::FrameSinkId CefContextFactoryAndroid::AllocateFrameSinkId() {
  return content::CompositorDependenciesAndroid::Get().AllocateFrameSinkId();
}

viz::SubtreeCaptureId CefContextFactoryAndroid::AllocateSubtreeCaptureId() {
  return subtree_capture_id_allocator_.NextSubtreeCaptureId();
}

viz::HostFrameSinkManager*
CefContextFactoryAndroid::GetHostFrameSinkManager() {
  return content::CompositorDependenciesAndroid::Get()
      .host_frame_sink_manager();
}
