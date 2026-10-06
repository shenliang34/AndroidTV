# PhoneTvVideo 开发说明

项目包含两个独立 APK：`phone-app` 安装到 Android 手机，`tv-app` 安装到 Android TV / Google TV。最低 Android 版本为 8.0（API 26）。

## 在 Android Studio 中运行

1. 使用 Android Studio 打开本目录。
2. 安装 Android SDK Platform 35，并使用 JDK 17。
3. 等待 Gradle 同步完成。
4. 选择 `phone-app` 或 `tv-app` 运行配置，在对应设备或模拟器上启动。
5. 手机首次启动时授予视频读取权限，然后点击“开始共享”；电视与手机连接同一个 Wi-Fi 后打开“手机媒体”。

手机服务在 TCP 8080 提供 `/api/device`、分页 `/api/videos`、`/api/folders`、缩略图和支持单区间 Range 的视频流。手机扫描只通过 MediaStore 和 ContentResolver 访问媒体，不依赖文件系统路径；视频以 64 KiB 缓冲分段发送。

## 当前 MVP 范围

- 手机：MediaStore 视频扫描、前台共享服务、NSD 广播、分页/关键字/文件夹 API、缩略图和 Range 流。
- 电视：NSD 发现、视频列表和缩略图、D-pad 可聚焦列表、Media3 播放控制。
- 当前尚未完成配对授权与 Bearer Token、设备连接状态回报、继续播放历史、电视分页加载、搜索/文件夹 UI 和断线自动重连。当前局域网 HTTP API 未加密且未做访问授权，只应在可信网络测试。
- 视频兼容性由电视设备的 Media3/系统解码器决定；未实现实时转码。

本执行环境未检测到 Gradle、Android SDK 平台包或 JDK 17，因此尚不能在这里编译 APK。原始需求文档 `readme.md` 保留不变。
