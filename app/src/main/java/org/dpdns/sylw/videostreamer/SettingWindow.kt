package org.dpdns.sylw.videostreamer

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore("settings")
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

object StreamConfig {
    private var _currentUrl: String? = null
    private var _videoBitrate: Int? = null
    private var _frameRate: Int? = null
    private var _streamingProtocol: String? = null
    private var _videoMode: String? = "CBR"
    private var _videoQuality: Int? = 70
    private var _udpAudioIp: String? = null
    private var _tcpControlPort: Int? = null
    private var _udpAudioPort: Int? = null
    private var _udpAudioRedundant: Boolean? = false
    
    fun getCurrentUrl(): String? = _currentUrl
    fun setCurrentUrl(url: String?) { _currentUrl = url }
    fun getVideoBitrate(): Int? = _videoBitrate
    fun setVideoBitrate(bitrate: Int?) { _videoBitrate = bitrate }
    fun getFrameRate(): Int? = _frameRate
    fun setFrameRate(frameRate: Int?) { _frameRate = frameRate }
    fun getStreamingProtocol(): String? = _streamingProtocol
    fun setStreamingProtocol(protocol: String?) { _streamingProtocol = protocol }
    fun getRateMode(): String? = _videoMode
    fun setVideoMode(mode: String?) { _videoMode = mode }
    fun getCqQuality(): Int? = _videoQuality
    fun setVideoQuality(quality: Int?) { _videoQuality = quality }
    fun getUdpAudioIp(): String? = _udpAudioIp
    fun setUdpAudioIp(ip: String?) { _udpAudioIp = ip }
    fun getTcpControlPort(): Int? = _tcpControlPort
    fun setTcpControlPort(port: Int?) { _tcpControlPort = port }
    fun getUdpAudioPort(): Int? = _udpAudioPort
    fun setUdpAudioPort(port: Int?) { _udpAudioPort = port }
    fun getUdpAudioRedundant(): Boolean? = _udpAudioRedundant
    fun setUdpAudioRedundant(enabled: Boolean?) { _udpAudioRedundant = enabled }
}

suspend fun saveUrl(context: Context, url: String) { context.dataStore.edit { it[PREF_STREAM_URL] = url } }
suspend fun loadUrl(context: Context): String? = context.dataStore.data.map { it[PREF_STREAM_URL] }.first()
suspend fun saveBitrate(context: Context, bitrate: Int) { context.dataStore.edit { it[PREF_VIDEO_BITRATE] = bitrate } }
suspend fun loadBitrate(context: Context): Int = context.dataStore.data.map { it[PREF_VIDEO_BITRATE] ?: (2500 * 1024) }.first()
suspend fun saveFrameRate(context: Context, frameRate: Int) { context.dataStore.edit { it[PREF_FRAME_RATE] = frameRate } }
suspend fun loadFrameRate(context: Context): Int = context.dataStore.data.map { it[PREF_FRAME_RATE] ?: 30 }.first()
suspend fun saveProtocol(context: Context, protocol: String) { context.dataStore.edit { it[PREF_STREAMING_PROTOCOL] = protocol } }
suspend fun loadProtocol(context: Context): String = context.dataStore.data.map { it[PREF_STREAMING_PROTOCOL] ?: "RTMP" }.first()
suspend fun saveVideoMode(context: Context, mode: String) { context.dataStore.edit { it[PREF_VIDEO_MODE] = mode } }
suspend fun loadVideoMode(context: Context): String = context.dataStore.data.map { it[PREF_VIDEO_MODE] ?: "CBR" }.first()
suspend fun saveVideoQuality(context: Context, quality: Int) { context.dataStore.edit { it[PREF_VIDEO_QUALITY] = quality } }
suspend fun loadVideoQuality(context: Context): Int = context.dataStore.data.map { it[PREF_VIDEO_QUALITY] ?: 70 }.first()
suspend fun saveUdpAudioIp(context: Context, ip: String) { context.dataStore.edit { it[PREF_UDP_AUDIO_IP] = ip } }
suspend fun loadUdpAudioIp(context: Context): String = context.dataStore.data.map { it[PREF_UDP_AUDIO_IP] ?: "127.0.0.1" }.first()
suspend fun saveTcpControlPort(context: Context, port: Int) { context.dataStore.edit { it[PREF_TCP_CONTROL_PORT] = port } }
suspend fun loadTcpControlPort(context: Context): Int = context.dataStore.data.map { it[PREF_TCP_CONTROL_PORT] ?: 9000 }.first()
suspend fun saveUdpAudioPort(context: Context, port: Int) { context.dataStore.edit { it[PREF_UDP_AUDIO_PORT] = port } }
suspend fun loadUdpAudioUdpPort(context: Context): Int = context.dataStore.data.map { it[PREF_UDP_AUDIO_PORT] ?: 9000 }.first()
suspend fun saveUdpAudioRedundant(context: Context, enabled: Boolean) { context.dataStore.edit { it[PREF_UDP_AUDIO_REDUNDANT] = enabled } }
suspend fun loadUdpAudioRedundant(context: Context): Boolean = context.dataStore.data.map { it[PREF_UDP_AUDIO_REDUNDANT] ?: false }.first()

@Composable
fun SettingWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var bitrateKbps by remember { mutableIntStateOf(2500) }
    var bitrateInput by remember { mutableStateOf(bitrateKbps.toString()) }
    var videoMode by remember { mutableStateOf("CBR") }
    var videoQuality by remember { mutableIntStateOf(70) }
    var frameRate by remember { mutableIntStateOf(30) }
    var udpAudioIp by remember { mutableStateOf("127.0.0.1") }
    var tcpControlPort by remember { mutableStateOf("9998") }
    var udpAudioPort by remember { mutableStateOf("9999") }
    var udpAudioRedundant by remember { mutableStateOf(false) }
    val availableFrameRates = listOf(30, 60, 120, 144, 165)
    var selectedProtocol by remember { mutableStateOf("RTMP") }
    val availableProtocols = listOf("RTMP")
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        url = loadUrl(context) ?: ""
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
        tcpControlPort = loadTcpControlPort(context).toString()
        StreamConfig.setTcpControlPort(tcpControlPort.toInt())
        udpAudioPort = loadUdpAudioUdpPort(context).toString()
        StreamConfig.setUdpAudioPort(udpAudioPort.toInt())
        udpAudioRedundant = loadUdpAudioRedundant(context)
        StreamConfig.setUdpAudioRedundant(udpAudioRedundant)
    }

    fun onSaveUrl() { scope.launch { saveUrl(context, url) } }
    fun onSaveBitrate() { scope.launch { saveBitrate(context, bitrateKbps * 1024) } }
    fun onSaveFrameRate() { scope.launch { saveFrameRate(context, frameRate) } }
    fun onSaveProtocol() { scope.launch { saveProtocol(context, selectedProtocol) } }
    fun onSaveMode(mode: String) { videoMode = mode; StreamConfig.setVideoMode(mode); scope.launch { saveVideoMode(context, mode) } }
    fun onSaveQuality(quality: Int) { videoQuality = quality.coerceIn(0, 100); StreamConfig.setVideoQuality(videoQuality); scope.launch { saveVideoQuality(context, videoQuality) } }
    fun onSaveUdpAudioIp() { StreamConfig.setUdpAudioIp(udpAudioIp); scope.launch { saveUdpAudioIp(context, udpAudioIp) } }
    fun onSaveTcpControlPort() { val port = tcpControlPort.toIntOrNull(); if (port != null && port in 1..65535) { StreamConfig.setTcpControlPort(port); scope.launch { saveTcpControlPort(context, port) } } }
    fun onSaveUdpAudioPort() { val port = udpAudioPort.toIntOrNull(); if (port != null && port in 1..65535) { StreamConfig.setUdpAudioPort(port); scope.launch { saveUdpAudioPort(context, port) } } }
    fun onSaveUdpAudioRedundant(enabled: Boolean) { udpAudioRedundant = enabled; StreamConfig.setUdpAudioRedundant(enabled); scope.launch { saveUdpAudioRedundant(context, enabled) } }

    Column(modifier = modifier.fillMaxSize().padding(5.dp, 16.dp, 5.dp, 16.dp).verticalScroll(scrollState)) {
        Text("Streaming Protocol:", modifier = Modifier.padding(bottom = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            availableProtocols.forEach { p ->
                Row(modifier = Modifier.weight(1f).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = selectedProtocol == p, onCheckedChange = { if (it) { selectedProtocol = p; onSaveProtocol() } })
                    Text(p, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Text("$selectedProtocol Streaming Url:", modifier = Modifier.align(Alignment.CenterStart))
            Button(onClick = { onSaveUrl() }, modifier = Modifier.size(80.dp, 40.dp).align(Alignment.CenterEnd), shape = androidx.compose.ui.graphics.RectangleShape) { Text("Save") }
        }
        OutlinedTextField(value = url, onValueChange = { url = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done), singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(24.dp))
        Text("Video Encoding Mode:", modifier = Modifier.padding(bottom = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("CBR", "CQ").forEach { mode ->
                Row(modifier = Modifier.weight(1f).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = videoMode == mode, onCheckedChange = { if (it) onSaveMode(mode) })
                    Text(mode, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (videoMode == "CBR") {
            Text("Video Bitrate:", modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(value = bitrateInput, onValueChange = { bitrateInput = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), singleLine = true, label = { Text("Bitrate (kbps)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { val input = bitrateInput.toIntOrNull(); if (input != null) { bitrateKbps = input; onSaveBitrate() } }, modifier = Modifier.width(80.dp).padding(top = 8.dp), shape = androidx.compose.ui.graphics.RectangleShape) { Text("Save") }
            Spacer(modifier = Modifier.height(24.dp))
        }
        if (videoMode == "CQ") {
            Text("Video Quality: $videoQuality", modifier = Modifier.padding(bottom = 8.dp))
            Slider(value = videoQuality.toFloat(), onValueChange = { onSaveQuality(it.toInt()) }, valueRange = 0f..100f, modifier = Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Button(onClick = { if (videoQuality > 0) onSaveQuality(videoQuality - 1) }, modifier = Modifier.size(50.dp, 40.dp), shape = androidx.compose.ui.graphics.RectangleShape, contentPadding = PaddingValues(0.dp)) { Text("-") }
                Text(videoQuality.toString())
                Button(onClick = { if (videoQuality < 100) onSaveQuality(videoQuality + 1) }, modifier = Modifier.size(50.dp, 40.dp), shape = androidx.compose.ui.graphics.RectangleShape, contentPadding = PaddingValues(0.dp)) { Text("+") }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
        Text("Frame Rate: $frameRate fps", modifier = Modifier.padding(bottom = 8.dp))
        availableFrameRates.forEach { fps ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = frameRate == fps, onCheckedChange = { if (it) { frameRate = fps; onSaveFrameRate() } })
                Text("$fps fps", modifier = Modifier.padding(start = 8.dp))
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("UDP Audio Configuration:", modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(value = udpAudioIp, onValueChange = { udpAudioIp = it; onSaveUdpAudioIp() }, label = { Text("Server IP") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tcpControlPort, onValueChange = { tcpControlPort = it; onSaveTcpControlPort() }, label = { Text("TCP Control Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = udpAudioPort, onValueChange = { udpAudioPort = it; onSaveUdpAudioPort() }, label = { Text("UDP Data Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = udpAudioRedundant, onCheckedChange = { onSaveUdpAudioRedundant(it) })
            Text(text = "Enable Redundant Transmission")
            IconButton(onClick = { Toast.makeText(context, "Enabling this will increase network load but improve audio stability.", Toast.LENGTH_LONG).show() }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.Gray) }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun SettingWindowPreview() { SettingWindow() }
