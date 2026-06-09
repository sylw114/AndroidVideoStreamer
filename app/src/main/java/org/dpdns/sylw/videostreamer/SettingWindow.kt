package org.dpdns.sylw.videostreamer

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore("settings")

private suspend fun <T> prefSave(context: Context, key: Preferences.Key<T>, value: T) {
    context.dataStore.edit { it[key] = value }
}

private suspend fun <T> prefLoad(context: Context, key: Preferences.Key<T>, default: T): T =
    context.dataStore.data.map { it[key] ?: default }.first()

object StreamConfig {
    private val state = mutableMapOf<String, Any?>()

    fun getCurrentUrl(): String? = state["url"] as? String
    fun setCurrentUrl(url: String?) { state["url"] = url }
    fun getVideoBitrate(): Int? = state["bitrate"] as? Int
    fun setVideoBitrate(v: Int?) { state["bitrate"] = v }
    fun getFrameRate(): Int? = state["frameRate"] as? Int
    fun setFrameRate(v: Int?) { state["frameRate"] = v }
    fun getStreamingProtocol(): String? = state["protocol"] as? String
    fun setStreamingProtocol(v: String?) { state["protocol"] = v }
    fun getRateMode(): String? = state["mode"] as? String
    fun setVideoMode(v: String?) { state["mode"] = v }
    fun getCqQuality(): Int? = state["quality"] as? Int
    fun setVideoQuality(v: Int?) { state["quality"] = v }
    fun getUdpAudioIp(): String? = state["udpIp"] as? String
    fun setUdpAudioIp(v: String?) { state["udpIp"] = v }
    fun getTcpControlPort(): Int? = state["tcpPort"] as? Int
    fun setTcpControlPort(v: Int?) { state["tcpPort"] = v }
    fun getUdpAudioPort(): Int? = state["udpPort"] as? Int
    fun setUdpAudioPort(v: Int?) { state["udpPort"] = v }
    fun getUdpAudioRedundant(): Boolean? = state["redundant"] as? Boolean
    fun setUdpAudioRedundant(v: Boolean?) { state["redundant"] = v }
    fun getLatencyRecordingEnabled(): Boolean? = state["latencyRecording"] as? Boolean
    fun setLatencyRecordingEnabled(v: Boolean?) { state["latencyRecording"] = v }
}

private val PREF_STREAM_URL = stringPreferencesKey("stream_url")
private val PREF_VIDEO_BITRATE = intPreferencesKey("video_bitrate")
private val PREF_FRAME_RATE = intPreferencesKey("frame_rate")
private val PREF_STREAMING_PROTOCOL = stringPreferencesKey("streaming_protocol")
private val PREF_VIDEO_MODE = stringPreferencesKey("video_mode")
private val PREF_VIDEO_QUALITY = intPreferencesKey("video_quality")
private val PREF_UDP_AUDIO_IP = stringPreferencesKey("udp_audio_ip")
private val PREF_TCP_CONTROL_PORT = intPreferencesKey("tcp_control_port")
private val PREF_UDP_AUDIO_PORT = intPreferencesKey("udp_audio_port")
private val PREF_UDP_AUDIO_REDUNDANT = booleanPreferencesKey("udp_audio_redundant")
private val PREF_LATENCY_RECORDING = booleanPreferencesKey("latency_recording")

suspend fun saveUrl(context: Context, url: String) = prefSave(context, PREF_STREAM_URL, url)
suspend fun loadUrl(context: Context): String = prefLoad(context, PREF_STREAM_URL, "")
suspend fun saveBitrate(context: Context, bitrate: Int) = prefSave(context, PREF_VIDEO_BITRATE, bitrate)
suspend fun loadBitrate(context: Context): Int = prefLoad(context, PREF_VIDEO_BITRATE, 2500 * 1024)
suspend fun saveFrameRate(context: Context, frameRate: Int) = prefSave(context, PREF_FRAME_RATE, frameRate)
suspend fun loadFrameRate(context: Context): Int = prefLoad(context, PREF_FRAME_RATE, 30)
suspend fun saveProtocol(context: Context, protocol: String) = prefSave(context, PREF_STREAMING_PROTOCOL, protocol)
suspend fun loadProtocol(context: Context): String = prefLoad(context, PREF_STREAMING_PROTOCOL, "RTMP")
suspend fun saveVideoMode(context: Context, mode: String) = prefSave(context, PREF_VIDEO_MODE, mode)
suspend fun loadVideoMode(context: Context): String = prefLoad(context, PREF_VIDEO_MODE, "CBR")
suspend fun saveVideoQuality(context: Context, quality: Int) = prefSave(context, PREF_VIDEO_QUALITY, quality)
suspend fun loadVideoQuality(context: Context): Int = prefLoad(context, PREF_VIDEO_QUALITY, 70)
suspend fun saveUdpAudioIp(context: Context, ip: String) = prefSave(context, PREF_UDP_AUDIO_IP, ip)
suspend fun loadUdpAudioIp(context: Context): String = prefLoad(context, PREF_UDP_AUDIO_IP, "127.0.0.1")
suspend fun saveTcpControlPort(context: Context, port: Int) = prefSave(context, PREF_TCP_CONTROL_PORT, port)
suspend fun loadTcpControlPort(context: Context): Int = prefLoad(context, PREF_TCP_CONTROL_PORT, 9000)
suspend fun saveUdpAudioPort(context: Context, port: Int) = prefSave(context, PREF_UDP_AUDIO_PORT, port)
suspend fun loadUdpAudioUdpPort(context: Context): Int = prefLoad(context, PREF_UDP_AUDIO_PORT, 9000)
suspend fun saveUdpAudioRedundant(context: Context, enabled: Boolean) = prefSave(context, PREF_UDP_AUDIO_REDUNDANT, enabled)
suspend fun loadUdpAudioRedundant(context: Context): Boolean = prefLoad(context, PREF_UDP_AUDIO_REDUNDANT, false)
suspend fun saveLatencyRecordingEnabled(context: Context, enabled: Boolean) = prefSave(context, PREF_LATENCY_RECORDING, enabled)
suspend fun loadLatencyRecordingEnabled(context: Context): Boolean = prefLoad(context, PREF_LATENCY_RECORDING, false)

@Composable
private fun SectionLabel(text: String) {
    Text(text, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun RowScope.CheckboxOption(checked: Boolean, onChecked: () -> Unit, label: String) {
    Row(
        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { if (it) onChecked() })
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun SettingWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var bitrateKbps by remember { mutableIntStateOf(2500) }
    var bitrateInput by remember { mutableStateOf("2500") }
    var videoMode by remember { mutableStateOf("CBR") }
    var videoQuality by remember { mutableIntStateOf(70) }
    var frameRate by remember { mutableIntStateOf(30) }
    var selectedProtocol by remember { mutableStateOf("RTMP") }
    var udpAudioIp by remember { mutableStateOf("127.0.0.1") }
    var tcpControlPort by remember { mutableStateOf("9998") }
    var udpAudioPort by remember { mutableStateOf("9999") }
    var udpAudioRedundant by remember { mutableStateOf(false) }
    var latencyRecordingEnabled by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val frameRates = listOf(30, 60, 120, 144, 165)
    val protocols = listOf("RTMP")

    fun save(action: suspend () -> Unit) = scope.launch { action() }

    LaunchedEffect(Unit) {
        url = loadUrl(context)
        StreamConfig.setCurrentUrl(url)

        val savedBitrate = loadBitrate(context)
        StreamConfig.setVideoBitrate(savedBitrate)
        bitrateKbps = savedBitrate / 1024
        bitrateInput = bitrateKbps.toString()

        frameRate = loadFrameRate(context)
        StreamConfig.setFrameRate(frameRate)

        selectedProtocol = loadProtocol(context)
        StreamConfig.setStreamingProtocol(selectedProtocol)

        videoMode = loadVideoMode(context)
        StreamConfig.setVideoMode(videoMode)

        videoQuality = loadVideoQuality(context)
        StreamConfig.setVideoQuality(videoQuality)

        udpAudioIp = loadUdpAudioIp(context)
        StreamConfig.setUdpAudioIp(udpAudioIp)

        val tcpPort = loadTcpControlPort(context)
        StreamConfig.setTcpControlPort(tcpPort)
        tcpControlPort = tcpPort.toString()

        val udpPort = loadUdpAudioUdpPort(context)
        StreamConfig.setUdpAudioPort(udpPort)
        udpAudioPort = udpPort.toString()

        udpAudioRedundant = loadUdpAudioRedundant(context)
        StreamConfig.setUdpAudioRedundant(udpAudioRedundant)

        latencyRecordingEnabled = loadLatencyRecordingEnabled(context)
        StreamConfig.setLatencyRecordingEnabled(latencyRecordingEnabled)
    }

    Column(modifier = modifier.fillMaxSize().padding(5.dp, 16.dp, 5.dp, 16.dp).verticalScroll(scrollState)) {
        SectionLabel("Streaming Protocol:")
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            protocols.forEach { p ->
                CheckboxOption(
                    checked = selectedProtocol == p,
                    onChecked = { selectedProtocol = p; save { saveProtocol(context, p) } },
                    label = p
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel("$selectedProtocol Streaming Url:")
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; StreamConfig.setCurrentUrl(it); save { saveUrl(context, it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel("Video Encoding Mode:")
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("CBR", "CQ").forEach { mode ->
                CheckboxOption(
                    checked = videoMode == mode,
                    onChecked = { videoMode = mode; StreamConfig.setVideoMode(mode); save { saveVideoMode(context, mode) } },
                    label = mode
                )
            }
        }

        if (videoMode == "CBR") {
            SectionLabel("Video Bitrate:")
            OutlinedTextField(
                value = bitrateInput,
                onValueChange = { bitrateInput = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
                label = { Text("Bitrate (kbps)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    bitrateInput.toIntOrNull()?.let { input ->
                        bitrateKbps = input
                        val bps = input * 1024
                        StreamConfig.setVideoBitrate(bps)
                        save { saveBitrate(context, bps) }
                    }
                },
                modifier = Modifier.width(80.dp).padding(top = 8.dp),
                shape = androidx.compose.ui.graphics.RectangleShape
            ) { Text("Save") }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (videoMode == "CQ") {
            SectionLabel("Video Quality: $videoQuality")
            Slider(
                value = videoQuality.toFloat(),
                onValueChange = { q ->
                    videoQuality = q.toInt().coerceIn(0, 100)
                    StreamConfig.setVideoQuality(videoQuality)
                    save { saveVideoQuality(context, videoQuality) }
                },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { if (videoQuality > 0) { videoQuality--; StreamConfig.setVideoQuality(videoQuality); save { saveVideoQuality(context, videoQuality) } } },
                    modifier = Modifier.size(50.dp, 40.dp),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    contentPadding = PaddingValues(0.dp)
                ) { Text("-") }
                Text(videoQuality.toString())
                Button(
                    onClick = { if (videoQuality < 100) { videoQuality++; StreamConfig.setVideoQuality(videoQuality); save { saveVideoQuality(context, videoQuality) } } },
                    modifier = Modifier.size(50.dp, 40.dp),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    contentPadding = PaddingValues(0.dp)
                ) { Text("+") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        SectionLabel("Frame Rate: $frameRate fps")
        frameRates.forEach { fps ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = frameRate == fps,
                    onCheckedChange = { if (it) { frameRate = fps; StreamConfig.setFrameRate(fps); save { saveFrameRate(context, fps) } } }
                )
                Text("$fps fps", modifier = Modifier.padding(start = 8.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel("UDP Audio Configuration:")
        OutlinedTextField(
            value = udpAudioIp,
            onValueChange = { udpAudioIp = it; StreamConfig.setUdpAudioIp(it); save { saveUdpAudioIp(context, it) } },
            label = { Text("Server IP") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tcpControlPort,
            onValueChange = {
                tcpControlPort = it
                it.toIntOrNull()?.takeIf { p -> p in 1..65535 }?.let { p ->
                    StreamConfig.setTcpControlPort(p)
                    save { saveTcpControlPort(context, p) }
                }
            },
            label = { Text("TCP Control Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = udpAudioPort,
            onValueChange = {
                udpAudioPort = it
                it.toIntOrNull()?.takeIf { p -> p in 1..65535 }?.let { p ->
                    StreamConfig.setUdpAudioPort(p)
                    save { saveUdpAudioPort(context, p) }
                }
            },
            label = { Text("UDP Data Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = udpAudioRedundant,
                onCheckedChange = { enabled ->
                    udpAudioRedundant = enabled
                    StreamConfig.setUdpAudioRedundant(enabled)
                    save { saveUdpAudioRedundant(context, enabled) }
                }
            )
            Text("Enable Redundant Transmission")
            IconButton(
                onClick = { Toast.makeText(context, "Enabling this will increase network load but improve audio stability.", Toast.LENGTH_LONG).show() },
                modifier = Modifier.size(24.dp)
            ) { Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.Gray) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = latencyRecordingEnabled,
                onCheckedChange = { enabled ->
                    latencyRecordingEnabled = enabled
                    StreamConfig.setLatencyRecordingEnabled(enabled)
                    save { saveLatencyRecordingEnabled(context, enabled) }
                }
            )
            Text("启用延迟记录与导出")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingWindowPreview() { SettingWindow() }
