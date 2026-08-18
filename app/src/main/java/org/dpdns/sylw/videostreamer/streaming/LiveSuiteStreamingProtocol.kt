package org.dpdns.sylw.videostreamer.streaming

import android.view.Surface
import org.dpdns.sylw.videostreamer.streaming.livesuite.LiveSuiteLowLatencyTransport

/** LiveSuite 专属协议选择器；摄像头和录屏入口都可复用同一会话。 */
class LiveSuiteStreamingProtocol(
    onSurfaceReady: (Surface) -> Unit,
    transport: LiveSuiteLowLatencyTransport.Transport
) : TransportStreamingProtocol(
    onSurfaceReady = onSurfaceReady,
    transportFactory = { LiveSuiteLowLatencyTransport(transport) }
)
