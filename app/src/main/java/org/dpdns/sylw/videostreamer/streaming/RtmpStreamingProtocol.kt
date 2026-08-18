package org.dpdns.sylw.videostreamer.streaming

import android.view.Surface

/** RTMP 仅选择传输实现；生命周期由通用会话统一管理。 */
class RtmpStreamingProtocol(
    onSurfaceReady: (Surface) -> Unit
) : TransportStreamingProtocol(
    onSurfaceReady = onSurfaceReady,
    transportFactory = ::RtmpStreamingTransport
)
