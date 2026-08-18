# LiveSuite QUIC 音频协议

Android 端通过 ALPN `livesuite-audio-v1` 建立 QUIC 连接。配置、音频、
时钟同步和统计共用可靠、有序的双向 QUIC Stream。

每条音频消息携带 8 字节会话 ID、循环的 1 字节序号、原始发送时间、长度和
PCM 分片或裸 Opus access unit。QUIC Stream 自身完成可靠传输、重排和去重，
QUIC 模式不做应用层冗余。UDP 回退模式可单独启用重复发送。

客户端每 500 ms 用四时间戳同步估算两端时钟偏移区间。接收端每 250 ms
反馈单向网络延迟上下界，界面和导出日志沿用“最小-最大 ms”的显示方式。

完整字段定义及桌面启动参数见 LiveSuite `udp/QuicAudioProtocol.md`。
