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
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dpdns.sylw.videostreamer.udpAudio.OpusFrameDurationResolver

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
    fun getUdpAudioOpusEnabled(): Boolean? = state["udpOpusEnabled"] as? Boolean
    fun setUdpAudioOpusEnabled(v: Boolean?) { state["udpOpusEnabled"] = v }
    fun getUdpAudioOpusBitrate(): Int? = state["udpOpusBitrate"] as? Int
    fun setUdpAudioOpusBitrate(v: Int?) { state["udpOpusBitrate"] = v }
    fun getUdpAudioOpusFrameMs(): Int? = state["udpOpusFrameMs"] as? Int
    fun setUdpAudioOpusFrameMs(v: Int?) { state["udpOpusFrameMs"] = v }
    fun getLatencyRecordingEnabled(): Boolean? = state["latencyRecording"] as? Boolean
    fun setLatencyRecordingEnabled(v: Boolean?) { state["latencyRecording"] = v }
    fun getAppLanguage(): String? = state["language"] as? String
    fun setAppLanguage(v: String?) { state["language"] = v }
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
private val PREF_UDP_AUDIO_OPUS_ENABLED = booleanPreferencesKey("udp_audio_opus_enabled")
private val PREF_UDP_AUDIO_OPUS_BITRATE = intPreferencesKey("udp_audio_opus_bitrate")
private val PREF_UDP_AUDIO_OPUS_FRAME_MS = intPreferencesKey("udp_audio_opus_frame_ms")
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
suspend fun saveUdpAudioOpusEnabled(context: Context, enabled: Boolean) = prefSave(context, PREF_UDP_AUDIO_OPUS_ENABLED, enabled)
suspend fun loadUdpAudioOpusEnabled(context: Context): Boolean = prefLoad(context, PREF_UDP_AUDIO_OPUS_ENABLED, false)
suspend fun saveUdpAudioOpusBitrate(context: Context, bitrate: Int) = prefSave(context, PREF_UDP_AUDIO_OPUS_BITRATE, bitrate)
suspend fun loadUdpAudioOpusBitrate(context: Context): Int = prefLoad(context, PREF_UDP_AUDIO_OPUS_BITRATE, 32000)
suspend fun saveUdpAudioOpusFrameMs(context: Context, frameMs: Int) = prefSave(context, PREF_UDP_AUDIO_OPUS_FRAME_MS, frameMs)
suspend fun loadUdpAudioOpusFrameMs(context: Context): Int = prefLoad(context, PREF_UDP_AUDIO_OPUS_FRAME_MS, 20)
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
fun SettingWindow(
    modifier: Modifier = Modifier,
    selectedLanguage: String = resolveDefaultAppLanguage(),
    onLanguageChange: (String) -> Unit = {}
) {
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
    var udpAudioOpusEnabled by remember { mutableStateOf(false) }
    var udpAudioOpusBitrate by remember { mutableIntStateOf(32) }
    var udpAudioOpusBitrateInput by remember { mutableStateOf("32") }
    var udpAudioOpusFrameMs by remember { mutableIntStateOf(20) }
    var latencyRecordingEnabled by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val frameRates = listOf(30, 60, 120, 144, 165)
    val protocols = listOf("RTMP")

    fun save(action: suspend () -> Unit) = scope.launch { action() }

    fun applyOpusFrameMs(frameMs: Int) {
        scope.launch {
            val supportedFrameMs = withContext(Dispatchers.Default) {
                OpusFrameDurationResolver.resolveSupportedFrameMs(
                    requestedFrameMs = frameMs,
                    bitrate = udpAudioOpusBitrate * 1000
                )
            }
            udpAudioOpusFrameMs = supportedFrameMs
            StreamConfig.setUdpAudioOpusFrameMs(supportedFrameMs)
            saveUdpAudioOpusFrameMs(context, supportedFrameMs)
            if (supportedFrameMs != frameMs) {
                Toast.makeText(
                    context,
                    context.getString(R.string.settings_opus_frame_duration_adjusted, frameMs, supportedFrameMs),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        // 🔥 WindowForSelecting 已预加载到 StreamConfig，这里只需读内存
        url = StreamConfig.getCurrentUrl() ?: ""
        StreamConfig.setCurrentUrl(url)

        val savedBitrate = StreamConfig.getVideoBitrate() ?: 2500 * 1024
        StreamConfig.setVideoBitrate(savedBitrate)
        bitrateKbps = savedBitrate / 1024
        bitrateInput = bitrateKbps.toString()

        frameRate = StreamConfig.getFrameRate() ?: 30
        StreamConfig.setFrameRate(frameRate)

        selectedProtocol = StreamConfig.getStreamingProtocol() ?: "RTMP"
        StreamConfig.setStreamingProtocol(selectedProtocol)

        videoMode = StreamConfig.getRateMode() ?: "CBR"
        StreamConfig.setVideoMode(videoMode)

        videoQuality = StreamConfig.getCqQuality() ?: 70
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

        udpAudioOpusEnabled = loadUdpAudioOpusEnabled(context)
        StreamConfig.setUdpAudioOpusEnabled(udpAudioOpusEnabled)

        val opusBitrate = loadUdpAudioOpusBitrate(context)
        StreamConfig.setUdpAudioOpusBitrate(opusBitrate)
        udpAudioOpusBitrate = opusBitrate / 1000
        udpAudioOpusBitrateInput = udpAudioOpusBitrate.toString()

        udpAudioOpusFrameMs = loadUdpAudioOpusFrameMs(context)
        StreamConfig.setUdpAudioOpusFrameMs(udpAudioOpusFrameMs)

        latencyRecordingEnabled = loadLatencyRecordingEnabled(context)
        StreamConfig.setLatencyRecordingEnabled(latencyRecordingEnabled)
    }

    Column(modifier = modifier.fillMaxSize().padding(5.dp, 16.dp, 5.dp, 16.dp).verticalScroll(scrollState)) {
        SectionLabel(stringResource(R.string.settings_language))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            APP_LANGUAGE_OPTIONS.forEach { option ->
                val languageCode = option.code
                CheckboxOption(
                    checked = selectedLanguage == languageCode,
                    onChecked = {
                        val normalizedLanguage = normalizeAppLanguage(languageCode)
                        StreamConfig.setAppLanguage(normalizedLanguage)
                        onLanguageChange(normalizedLanguage)
                        save { saveAppLanguage(context, normalizedLanguage) }
                    },
                    label = stringResource(option.labelResId)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.settings_streaming_protocol))
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
        SectionLabel(stringResource(R.string.settings_stream_url, selectedProtocol))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; StreamConfig.setCurrentUrl(it); save { saveUrl(context, it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.settings_video_encoding_mode))
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
            SectionLabel(stringResource(R.string.settings_video_bitrate))
            OutlinedTextField(
                value = bitrateInput,
                onValueChange = { bitrateInput = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_bitrate_kbps)) },
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
            ) { Text(stringResource(R.string.common_save)) }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (videoMode == "CQ") {
            SectionLabel(stringResource(R.string.settings_video_quality_value, videoQuality))
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

        SectionLabel(stringResource(R.string.settings_frame_rate_value, frameRate))
        frameRates.forEach { fps ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = frameRate == fps,
                    onCheckedChange = { if (it) { frameRate = fps; StreamConfig.setFrameRate(fps); save { saveFrameRate(context, fps) } } }
                )
                Text(stringResource(R.string.fps_value, fps), modifier = Modifier.padding(start = 8.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.settings_udp_audio_configuration))
        OutlinedTextField(
            value = udpAudioIp,
            onValueChange = { udpAudioIp = it; StreamConfig.setUdpAudioIp(it); save { saveUdpAudioIp(context, it) } },
            label = { Text(stringResource(R.string.settings_server_ip)) },
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
            label = { Text(stringResource(R.string.settings_tcp_control_port)) },
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
            label = { Text(stringResource(R.string.settings_udp_data_port)) },
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
            Text(stringResource(R.string.settings_enable_redundant_transmission))
            IconButton(
                onClick = { Toast.makeText(context, context.getString(R.string.settings_redundant_transmission_tip), Toast.LENGTH_LONG).show() },
                modifier = Modifier.size(24.dp)
            ) { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.settings_info_content_description), tint = Color.Gray) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = udpAudioOpusEnabled,
                onCheckedChange = { enabled ->
                    udpAudioOpusEnabled = enabled
                    StreamConfig.setUdpAudioOpusEnabled(enabled)
                    save { saveUdpAudioOpusEnabled(context, enabled) }
                    if (enabled) applyOpusFrameMs(udpAudioOpusFrameMs)
                }
            )
            Text(stringResource(R.string.settings_enable_opus_compression))
        }
        if (udpAudioOpusEnabled) {
            SectionLabel(stringResource(R.string.settings_opus_frame_duration_value, udpAudioOpusFrameMs))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                listOf(10, 20, 40).forEach { frameMs ->
                    CheckboxOption(
                        checked = udpAudioOpusFrameMs == frameMs,
                        onChecked = {
                            applyOpusFrameMs(frameMs)
                        },
                        label = stringResource(R.string.settings_milliseconds_value, frameMs)
                    )
                }
            }
            OutlinedTextField(
                value = udpAudioOpusBitrateInput,
                onValueChange = { udpAudioOpusBitrateInput = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_opus_bitrate_kbps)) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    udpAudioOpusBitrateInput.toIntOrNull()?.let { input ->
                        udpAudioOpusBitrate = input.coerceIn(8, 256)
                        udpAudioOpusBitrateInput = udpAudioOpusBitrate.toString()
                        val bps = udpAudioOpusBitrate * 1000
                        StreamConfig.setUdpAudioOpusBitrate(bps)
                        save { saveUdpAudioOpusBitrate(context, bps) }
                    }
                },
                modifier = Modifier.width(80.dp).padding(top = 8.dp),
                shape = androidx.compose.ui.graphics.RectangleShape
            ) { Text(stringResource(R.string.common_save)) }
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
            Text(stringResource(R.string.settings_latency_recording))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingWindowPreview() { SettingWindow() }
