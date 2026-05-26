package org.dpdns.sylw.videostreamer

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.platform.LocalContext
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
// 🔥 TCP音频配置
private val PREF_TCP_AUDIO_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("tcp_audio_enabled")
private val PREF_TCP_AUDIO_IP = stringPreferencesKey("tcp_audio_ip")
private val PREF_TCP_AUDIO_PORT = intPreferencesKey("tcp_audio_port")

/**
 * 全局推流配置管理器
 */
object StreamConfig {
    private var _currentUrl: String? = null
    private var _videoBitrate: Int? = null
    private var _frameRate: Int? = null
    private var _streamingProtocol: String? = null
    private var _videoMode: String? = "CBR"
    private var _videoQuality: Int? = 70

    /**
     * 获取当前推流 URL（从内存缓存读取）
     */
    fun getCurrentUrl(): String? = _currentUrl

    /**
     * 设置当前推流 URL（更新内存缓存）
     */
    fun setCurrentUrl(url: String?) {
        _currentUrl = url
    }
    fun getVideoBitrate(): Int? = _videoBitrate
    fun setVideoBitrate(bitrate: Int?) {
        _videoBitrate = bitrate
    }
    fun getFrameRate(): Int? = _frameRate
    fun setFrameRate(frameRate: Int?) {
        _frameRate = frameRate
    }
    fun getStreamingProtocol(): String? = _streamingProtocol
    fun setStreamingProtocol(protocol: String?) {
        _streamingProtocol = protocol
    }
    fun getRateMode(): String? = _videoMode
    fun setVideoMode(mode: String?) {
        _videoMode = mode
    }
    fun getCqQuality(): Int? = _videoQuality
    fun setVideoQuality(quality: Int?) {
        _videoQuality = quality
    }
    
    // 🔥 TCP音频配置访问方法
    private var _tcpAudioEnabled: Boolean? = null
    private var _tcpAudioIp: String? = null
    private var _tcpAudioPort: Int? = null
    
    fun getTcpAudioEnabled(): Boolean? = _tcpAudioEnabled
    fun setTcpAudioEnabled(enabled: Boolean?) {
        _tcpAudioEnabled = enabled
    }
    fun getTcpAudioIp(): String? = _tcpAudioIp
    fun setTcpAudioIp(ip: String?) {
        _tcpAudioIp = ip
    }
    fun getTcpAudioPort(): Int? = _tcpAudioPort
    fun setTcpAudioPort(port: Int?) {
        _tcpAudioPort = port
    }
}

suspend fun saveUrl(context: Context, url: String) {
    context.dataStore.edit { settings ->
        settings[PREF_STREAM_URL] = url
    }
}

suspend fun loadUrl(context: Context): String? {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_STREAM_URL]
    }.first()
}

suspend fun saveBitrate(context: Context, bitrate: Int) {
    context.dataStore.edit { settings ->
        settings[PREF_VIDEO_BITRATE] = bitrate
    }
}

suspend fun loadBitrate(context: Context): Int {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_VIDEO_BITRATE] ?: (2500 * 1024)
    }.first()
}

suspend fun saveFrameRate(context: Context, frameRate: Int) {
    context.dataStore.edit { settings ->
        settings[PREF_FRAME_RATE] = frameRate
    }
}

suspend fun loadFrameRate(context: Context): Int {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_FRAME_RATE] ?: 30
    }.first()
}

suspend fun saveProtocol(context: Context, protocol: String) {
    context.dataStore.edit { settings ->
        settings[PREF_STREAMING_PROTOCOL] = protocol
    }
}

suspend fun loadProtocol(context: Context): String {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_STREAMING_PROTOCOL] ?: "RTMP"
    }.first()
}

suspend fun saveVideoMode(context: Context, mode: String) {
    context.dataStore.edit { settings ->
        settings[PREF_VIDEO_MODE] = mode
    }
}

suspend fun loadVideoMode(context: Context): String {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_VIDEO_MODE] ?: "CBR"
    }.first()
}

suspend fun saveVideoQuality(context: Context, quality: Int) {
    context.dataStore.edit { settings ->
        settings[PREF_VIDEO_QUALITY] = quality
    }
}

suspend fun loadVideoQuality(context: Context): Int {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_VIDEO_QUALITY] ?: 70
    }.first()
}

// 🔥 TCP音频配置保存/加载函数
suspend fun saveTcpAudioEnabled(context: Context, enabled: Boolean) {
    context.dataStore.edit { settings ->
        settings[PREF_TCP_AUDIO_ENABLED] = enabled
    }
}

suspend fun loadTcpAudioEnabled(context: Context): Boolean {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_TCP_AUDIO_ENABLED] ?: false
    }.first()
}

suspend fun saveTcpAudioIp(context: Context, ip: String) {
    context.dataStore.edit { settings ->
        settings[PREF_TCP_AUDIO_IP] = ip
    }
}

suspend fun loadTcpAudioIp(context: Context): String {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_TCP_AUDIO_IP] ?: "127.0.0.1"
    }.first()
}

suspend fun saveTcpAudioPort(context: Context, port: Int) {
    context.dataStore.edit { settings ->
        settings[PREF_TCP_AUDIO_PORT] = port
    }
}

suspend fun loadTcpAudioPort(context: Context): Int {
    return context.dataStore.data.map { preferences ->
        preferences[PREF_TCP_AUDIO_PORT] ?: 9999
    }.first()
}

@Composable
fun SettingWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 推流地址
    var url by remember { mutableStateOf("") }
    
    // 视频码率 (kbps)
    var bitrateKbps by remember { mutableIntStateOf(2500) }
    var bitrateInput by remember { mutableStateOf(bitrateKbps.toString()) }

    // 编码模式 (CBR/CQ)
    var videoMode by remember { mutableStateOf("CBR") }
    // 编码质量 (0-100)
    var videoQuality by remember { mutableIntStateOf(70) }
    
    // 帧率
    var frameRate by remember { mutableIntStateOf(30) }
    
    // 🔥 TCP音频配置
    var tcpAudioEnabled by remember { mutableStateOf(false) }
    var tcpAudioIp by remember { mutableStateOf("127.0.0.1") }
    var tcpAudioPort by remember { mutableStateOf("9999") }
    
    // 可选的帧率档位
    val availableFrameRates = listOf(30, 60, 120, 144, 165)
    
    // 推流协议
    var selectedProtocol by remember { mutableStateOf("RTMP") }
    // 🔥 只显示已实现的协议
    val availableProtocols = listOf("RTMP")

    val scrollState = rememberScrollState()

    // 加载保存的配置
    LaunchedEffect(Unit) {
        val savedUrl = loadUrl(context)
        if (savedUrl != null) {
            url = savedUrl
        }
        StreamConfig.setCurrentUrl(savedUrl)

        val savedBitrate = loadBitrate(context)
        StreamConfig.setVideoBitrate(savedBitrate)
        bitrateKbps = savedBitrate / 1024
        bitrateInput = bitrateKbps.toString()

        val savedFrameRate = loadFrameRate(context)
        StreamConfig.setFrameRate(savedFrameRate)
        frameRate = savedFrameRate
        
        val savedProtocol = loadProtocol(context)
        StreamConfig.setStreamingProtocol(savedProtocol)
        selectedProtocol = savedProtocol

        val savedMode = loadVideoMode(context)
        StreamConfig.setVideoMode(savedMode)
        videoMode = savedMode

        val savedQuality = loadVideoQuality(context)
        StreamConfig.setVideoQuality(savedQuality)
        videoQuality = savedQuality
        
        // 🔥 加载TCP音频配置
        val savedTcpAudioEnabled = loadTcpAudioEnabled(context)
        StreamConfig.setTcpAudioEnabled(savedTcpAudioEnabled)
        tcpAudioEnabled = savedTcpAudioEnabled
        
        val savedTcpAudioIp = loadTcpAudioIp(context)
        StreamConfig.setTcpAudioIp(savedTcpAudioIp)
        tcpAudioIp = savedTcpAudioIp
        
        val savedTcpAudioPort = loadTcpAudioPort(context)
        StreamConfig.setTcpAudioPort(savedTcpAudioPort)
        tcpAudioPort = savedTcpAudioPort.toString()
    }

    fun onSaveUrl() {
        scope.launch {
            saveUrl(context, url)
        }
    }
    
    fun onSaveBitrate() {
        val bitrate = bitrateKbps * 1024
        scope.launch {
            saveBitrate(context, bitrate)
        }
    }
    
    fun onSaveFrameRate() {
        scope.launch {
            saveFrameRate(context, frameRate)
        }
    }
    
    fun onSaveProtocol() {
        scope.launch {
            saveProtocol(context, selectedProtocol)
        }
    }

    fun onSaveMode(mode: String) {
        videoMode = mode
        StreamConfig.setVideoMode(mode)
        scope.launch {
            saveVideoMode(context, mode)
        }
    }

    fun onSaveQuality(quality: Int) {
        val clamped = quality.coerceIn(0, 100)
        videoQuality = clamped
        StreamConfig.setVideoQuality(clamped)
        scope.launch {
            saveVideoQuality(context, clamped)
        }
    }
    
    // 🔥 TCP音频配置保存函数
    fun onSaveTcpAudioEnabled() {
        StreamConfig.setTcpAudioEnabled(tcpAudioEnabled)
        scope.launch {
            saveTcpAudioEnabled(context, tcpAudioEnabled)
        }
    }
    
    fun onSaveTcpAudioIp() {
        StreamConfig.setTcpAudioIp(tcpAudioIp)
        scope.launch {
            saveTcpAudioIp(context, tcpAudioIp)
        }
    }
    
    fun onSaveTcpAudioPort() {
        val port = tcpAudioPort.toIntOrNull()
        if (port != null && port in 1..65535) {
            StreamConfig.setTcpAudioPort(port)
            scope.launch {
                saveTcpAudioPort(context, port)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(5.dp, 16.dp, 5.dp, 16.dp)
            .verticalScroll(scrollState)
    ) {
        // 推流协议选择
        Text(
            text = "Streaming Protocol:",
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            availableProtocols.forEach { protocol ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selectedProtocol == protocol,
                        onCheckedChange = { 
                            if (it) {
                                selectedProtocol = protocol
                                onSaveProtocol()
                            }
                        }
                    )
                    Text(
                        text = protocol,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 推流地址设置
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$selectedProtocol Streaming Url:",
                modifier = Modifier.align(Alignment.CenterStart)
            )
            Button(
                onClick = { onSaveUrl() },
                modifier = Modifier
                    .size(80.dp, 40.dp)
                    .align(Alignment.CenterEnd),
                shape = androidx.compose.ui.graphics.RectangleShape,
            ) {
                Text(text = "Save")
            }
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
            
        Spacer(modifier = Modifier.height(24.dp))

        // 编码模式选择 (CBR / CQ)
        Text(
            text = "Video Encoding Mode:",
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("CBR", "CQ").forEach { mode ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = videoMode == mode,
                        onCheckedChange = {
                            if (it) {
                                onSaveMode(mode)
                            }
                        }
                    )
                    Text(
                        text = mode,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // 视频码率输入框 (仅在 CBR 模式下显示)
        if (videoMode == "CBR") {
            Text(
                text = "Video Bitrate:",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = bitrateInput,
                onValueChange = { bitrateInput = it },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                singleLine = true,
                label = { Text("Bitrate (kbps)") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val inputBitrate = bitrateInput.toIntOrNull()
                    if (inputBitrate != null) {
                        bitrateKbps = inputBitrate
                        onSaveBitrate()
                    }
                },
                modifier = Modifier
                    .width(80.dp)
                    .padding(top = 8.dp),
                shape = androidx.compose.ui.graphics.RectangleShape,
            ) {
                Text(text = "Save")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 视频质量设置 (仅在 CQ 模式下显示)
        if (videoMode == "CQ") {
            Text(
                text = "Video Quality: $videoQuality",
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Slider(
                value = videoQuality.toFloat(),
                onValueChange = { onSaveQuality(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { if (videoQuality > 0) onSaveQuality(videoQuality - 1) },
                    modifier = Modifier.size(50.dp, 40.dp),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("-")
                }
                
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = videoQuality.toString()
                    )
                }
                
                Button(
                    onClick = { if (videoQuality < 100) onSaveQuality(videoQuality + 1) },
                    modifier = Modifier.size(50.dp, 40.dp),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+")
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        // 帧率设置
        Text(
            text = "Frame Rate: $frameRate fps",
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Column {
            availableFrameRates.forEach { fps ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = frameRate == fps,
                        onCheckedChange = { 
                            if (it) {
                                frameRate = fps
                                onSaveFrameRate()
                            }
                        }
                    )
                    Text(
                        text = "$fps fps",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun SettingWindowPreview() {
    SettingWindow()
}