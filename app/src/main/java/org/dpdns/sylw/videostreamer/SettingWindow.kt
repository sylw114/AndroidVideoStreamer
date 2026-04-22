package org.dpdns.sylw.videostreamer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

import org.dpdns.sylw.videostreamer.streaming.StreamManager

var streaming: StreamManager? = null
val Context.dataStore by preferencesDataStore("settings")
private val PREF_STREAM_URL = stringPreferencesKey("stream_url")
private val PREF_VIDEO_BITRATE = intPreferencesKey("video_bitrate")
private val PREF_FRAME_RATE = intPreferencesKey("frame_rate")
private val PREF_STREAMING_PROTOCOL = stringPreferencesKey("streaming_protocol")

/**
 * 全局推流配置管理器
 */
object StreamConfig {
    private var _currentUrl: String? = null
    
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

@Composable
fun SettingWindow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 推流地址
    var url by remember { mutableStateOf("") }
    
    // 视频码率 (kbps)
    var bitrateKbps by remember { mutableIntStateOf(2500) }
    
    // 帧率
    var frameRate by remember { mutableIntStateOf(30) }
    var bitrateInput by remember { mutableStateOf(bitrateKbps.toString()) }
    
    // 可选的帧率档位
    val availableFrameRates = listOf(30, 60, 120, 144, 165)
    
    // 推流协议
    var selectedProtocol by remember { mutableStateOf("RTMP") }
    // 🔥 只显示已实现的协议
    val availableProtocols = listOf("RTMP")

    // 加载保存的配置
    LaunchedEffect(Unit) {
        val savedUrl = loadUrl(context)
        if (savedUrl != null) {
            url = savedUrl
        }
        
        val savedBitrate = loadBitrate(context)
        bitrateKbps = savedBitrate / 1024
        bitrateInput = bitrateKbps.toString()
        val savedFrameRate = loadFrameRate(context)
        frameRate = savedFrameRate
        
        val savedProtocol = loadProtocol(context)
        selectedProtocol = savedProtocol
    }

    fun onSaveUrl() {
        streaming?.switchUrl(url)
        scope.launch {
            saveUrl(context, url)
        }
    }
    
    fun onSaveBitrate() {
        val bitrate = bitrateKbps * 1024
        scope.launch {
            saveBitrate(context, bitrate)
            // 🔥 关键修复：立即更新到 StreamManager
            streaming?.updateBitrate(bitrate)
        }
    }
    
    fun onSaveFrameRate() {
        scope.launch {
            saveFrameRate(context, frameRate)
            // 🔥 关键修复：立即更新到 StreamManager
            streaming?.updateFrameRate(frameRate)
        }
    }
    
    fun onSaveProtocol() {
        scope.launch {
            saveProtocol(context, selectedProtocol)
            // 🔥 切换协议（如果正在推流，需要重启）
            streaming?.switchProtocol(selectedProtocol)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(5.dp, 16.dp, 5.dp, 16.dp)
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
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart)
            )
            Button(
                onClick = { onSaveUrl() },
                modifier = Modifier
                    .size(80.dp, 40.dp)
                    .align(androidx.compose.ui.Alignment.CenterEnd),
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
        
        // 视频码率输入框
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