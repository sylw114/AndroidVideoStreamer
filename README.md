# VideoStreamer

VideoStreamer 是一个专为 Android 设备设计的视频流媒体应用，旨在提供高质量的屏幕镜像和流媒体体验。

## 主要特性

- **实时屏幕镜像**：无缝地将 Android 设备屏幕镜像到其他设备。
- **高分辨率支持**：默认原生分辨率
- **易于集成**：兼容 OBS 等流媒体软件，便于直播和录制。
- **支持的输入**： 屏幕捕获，摄像头录制

## 为什么不选择 scrcpy

虽然 scrcpy 是一个广受欢迎的开源 Android 屏幕镜像工具，但我在开发 VideoStreamer 时选择不依赖它，主要基于以下原因：

- **容易崩溃**：scrcpy 在某些网络条件不佳、设备兼容性问题或长时间运行时容易出现崩溃，导致用户体验中断。
- **OBS 集成不便**：要将 scrcpy 的输出集成到 OBS 等流媒体软件中，必须通过窗口采集方式获取，这会导致原始分辨率丢失，无法充分利用高清画面的优势。
- **流畅度不足**：scrcpy 在处理高分辨率内容或复杂动画时，流畅度不够理想，容易出现画面卡顿或帧率下降。

值得注意的是，本项目也存在延迟方面的缺点。这是缓冲机制的问题，但缓冲过小反而可能引发卡顿。

## 安装和使用

### 要求

- Android API 级别 29 或更高
- 一个 RTMP 流服务器

### 使用指南

1. 启动应用。
2. 连接目标设备。
3. 开始流媒体传输。

### 搭配obs

你可以自行实现服务端，也可以参考这个Node.js的简单实现：

```javascript
import NodeMediaServer from 'node-media-server';

const config = {
  rtmp: {
    port: 1935,
    chunk_size: 30000,
    gop_cache: false,
    ping: 30,
    ping_timeout: 60
  }
};

const nms = new NodeMediaServer(config);
nms.run();

nms.on('postPublish', (id, StreamPath, args) => {
});

nms.on('donePublish', (id, StreamPath, args) => {
});

```

而后，在OBS中添加媒体源，链接填入对应的流链接。例如，设备推流为rtmp://<serverIP>/device/stream ，OBS中添加媒体源为rtmp://<serverIP>/device/stream

## 已知问题
1. 屏幕录制下帧率设置可能不生效

## 声明
本项目主要由AI编写，代码质量可能存在问题 ~~250 warnings~~ ，如有大佬发现问题，请提issue，万分感谢。