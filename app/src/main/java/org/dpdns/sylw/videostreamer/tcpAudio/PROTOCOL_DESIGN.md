# TCP 音频直传协议设计文档 v2.0

## 📋 设计理念

**核心思想**: TCP是面向连接的可靠字节流，配置信息在握手阶段协商一次，后续数据流为纯PCM裸数据，零额外开销。

```
TCP Connection = [Config Handshake] + [Raw PCM Stream]
                  (5 bytes, once)    (continuous, no header)
```

---

## 🎯 协议架构

### 阶段一：配置握手（连接建立后立即执行）

```
Client/Server 连接成功后:
┌─────────────────────────────────────┐
│  Config Packet (5 bytes, fixed)     │
│  [Type][SR_IDX][CHANS][RSV][RSV]   │
└─────────────────────────────────────┘
         ↓
   双方确认配置
         ↓
   进入数据传输阶段
```

### 阶段二：纯数据流传输

```
┌──────────┬──────────┬──────────┬──────────┐
│ PCM Data │ PCM Data │ PCM Data │ PCM Data │
│ (N bytes)│ (N bytes)│ (N bytes)│ (N bytes)│
└──────────┴──────────┴──────────┴──────────┘
     ↑           ↑           ↑           ↑
  无包头      无包头      无包头      无包头
  
  连续字节流，按固定包大小解析
```

---

## 📦 数据包格式详解

### 1️⃣ 配置握手包（仅发送一次）

**格式**（固定5字节）:
```
Byte 0: Type       - 消息类型标识
Byte 1: SR_IDX     - 采样率索引
Byte 2: CHANS      - 声道数
Byte 3: RSV_0      - 保留
Byte 4: RSV_1      - 保留
```

**字段定义**:

| 字节 | 字段 | 取值说明 |
|------|------|---------|
| 0 | Type | `0x01` = Config（固定值） |
| 1 | SR_IDX | `0x00`=44100Hz<br>`0x01`=48000Hz<br>`0xFF`=不支持 |
| 2 | CHANS | `1`=Mono<br>`2`=Stereo |
| 3-4 | RSV | 保留字段，填`0x00` |

**示例**:
```kotlin
// 48kHz 立体声
val config = byteArrayOf(0x01, 0x01, 0x02, 0x00, 0x00)

// 44.1kHz 单声道
val config = byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00)
```

---

### 2️⃣ 音频数据包（无包头，纯PCM）

**关键特性**:
- ✅ **无包头**: 直接发送PCM原始数据
- ✅ **固定长度**: 每包大小由AudioRecord缓冲区决定（约15360字节）
- ✅ **连续流**: TCP保证顺序和完整性

**数据格式**:
```
[PCM Sample 0][PCM Sample 1]...[PCM Sample N]
   2 bytes        2 bytes          2 bytes
   
   每个采样点 = 声道数 × 位深(2字节)
   例如: 立体声16-bit = 4 bytes/frame
```

---

## 🔄 完整通信流程

### 场景：Android采集端 → PC接收端

```
Android (Sender)                    PC (Receiver)
     |                                     |
     |--- TCP Connect (ip:port) --------->|
     |                                     |
     |<-- TCP Accept ---------------------|
     |                                     |
     |--- Config: [0x01,0x01,0x02,0,0] -->|
     |     (48kHz, Stereo)                 |
     |                                     |
     |<-- ACK (optional) -----------------|
     |                                     |
     |=== Raw PCM Stream ================>|
     |    [15360 bytes PCM]                |
     |    [15360 bytes PCM]                |
     |    [15360 bytes PCM]                |
     |         ...                         |
     |                                     |
     |<-- Disconnect ---------------------|
```