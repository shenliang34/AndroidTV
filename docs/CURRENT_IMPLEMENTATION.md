# 当前工程结构与功能盘点

盘点基于当前 `main` 分支（`e981873`）。这里把源码中已有功能和需求文档中规划但尚未接通的功能分开记录。

## 工程结构

```text
AndroidTV/
├── phone-app/                         # Android 手机端
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/phonetv/phone/
│       │   ├── MainActivity.kt         # 共享控制台、文件夹设置、授权设备管理
│       │   ├── MediaCatalog.kt         # MediaStore 扫描、缩略图和视频 JSON
│       │   ├── PairingStore.kt          # 一次性配对码、Token 摘要与授权撤销
│       │   ├── MediaServerService.kt   # 前台服务、NSD 广播
│       │   └── LanHttpServer.kt        # HTTP API、缩略图和视频流
│       └── res/                        # 应用图标
├── tv-app/                             # Android TV / Google TV 端
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/phonetv/tv/
│       │   └── MainActivity.kt         # 设备发现、片库、文件夹、播放器
│       └── res/                        # 应用图标
├── docs/                               # 构建说明、计划和本盘点
├── settings.gradle.kts                 # 两个独立 APK 模块
└── build.gradle.kts / gradle/          # Gradle 配置与 Wrapper
```

手机端和电视端是两个独立 APK；当前没有共享的 `common`/`protocol` 模块。两个 `MainActivity` 都承载了较多界面状态和业务调用。

## 页面清单

| 端 | 页面/界面 | 当前内容与操作 | 状态 |
|---|---|---|---|
| 手机 | 共享控制台 | 共享开关、扫描到的本机文件夹、单文件夹/全部文件夹开关、设备区、重新扫描入口 | 主页面已实现 |
| 手机 | 配对与授权设备 | 显示短时配对码、已授权 TV、撤销授权 | 一次性 6 位码；授权设备持有独立 Bearer Token |
| TV | 发现手机 | 展示 NSD 发现的手机、连接入口、重新扫描 | 已实现基本发现；没有自动重连和手动 IP 输入 |
| TV | 片库 | 展示连接状态和按视频数量排序的文件夹卡片 | 已实现；当前不是独立的“全部视频”列表 |
| TV | 文件夹视频页 | 横向海报列表，显示缩略图、名称和时长；点击直接播放 | 已实现；一次展示已加载到内存的视频 |
| TV | 播放器 | 全屏 Media3、播放/暂停、前后跳片、±10 秒、倍速、带宽估算、手势拖动快进/快退 | 基本播放已实现；进度条目前只是显示条 |

TV 页面状态由 `selected`、`folder`、`playing` 驱动，在一个 Activity 中切换。返回键依次退出播放器、文件夹、片库并回到设备列表。

## 已接通的功能

### 手机端

- 通过 MediaStore 读取视频名称、大小、时长、分辨率、MIME 类型、文件夹和修改时间。
- 查询并缓存视频缩略图；文件夹开关保存在 SharedPreferences。
- 共享开关可启动/停止前台服务；服务启动时扫描媒体并广播 `_phonevideo._tcp.`。
- HTTP 服务监听 TCP 8080，提供 `/api/device`、分页 `/api/videos`、`/api/folders`、视频缩略图和视频流。
- 视频流按 64 KiB 缓冲传输，并返回 `Accept-Ranges`、`Content-Range` 等头部。

### TV 端

- 使用 NSD 发现 `_phonevideo._tcp.` 服务，支持手动重新扫描。
- 首次连接时输入手机上的配对码；后续自动使用本地保存的 Token 连接。
- 按服务端分页逐页读取完整片库，再在客户端按文件夹分组；每页最多 100 个视频。
- 使用带授权请求头的 Coil 加载缩略图，并用 Media3/ExoPlayer 播放带授权请求头的视频流。
- 播放界面支持 0.75x、1x、1.25x、1.5x、2x 倍速，±10 秒、同文件夹上一部/下一部和横向手势 seek。
- TV Activity 固定横屏；海报卡片、按钮和设备行提供焦点反馈。

## 需求中尚未实现或仅有占位的部分

- **设备在线状态尚未实现。**手机可以查看并撤销授权设备，但在线/离线字段尚未接入 TV 心跳。
- **设备发现信息仍公开，媒体接口已要求授权。**`/api/device` 用于发现时公开返回基本信息；视频列表、文件夹、缩略图和视频流要求 Bearer Token。局域网传输仍使用明文 HTTP。
- TV 已读取所有服务端分页；搜索参数仍未接入 TV UI。
- 服务端没有实际排序参数实现；TV 没有搜索、排序、最近播放、播放历史或继续播放界面。
- 播放器没有可拖动的 SeekBar、缓冲/错误状态页、重连流程、音轨选择和字幕 UI。
- 手机没有设备名编辑页；当前服务名默认取设备型号。手机端 FGS 的类型声明为 `mediaPlayback`，应结合实际后台 LAN 服务用途复核。
- 大文件 Range seek 仍使用 `InputStream.skip` 跳过前置数据；对可随机定位的内容描述符还未优化为直接 seek。

## 优化建议（按优先级）

### P0：先修正确性和访问边界

1. **已完成：**stream 和 thumbnail 路由复用“已启用共享文件夹”过滤；关闭文件夹的视频按 ID 请求也返回 404。
2. **已完成：**手机展示 5 分钟有效的一次性配对码，TV 配对后获得随机 Bearer Token；媒体 API 校验 Token，手机端可撤销授权，并限制错误配对尝试。
3. **已完成：**实现开放区间、后缀区间、非法和越界区间的校验；不可满足的请求返回 `416 Range Not Satisfiable` 与 `Content-Range: bytes */长度`，并支持 HEAD 与正确的 Content-Range。
4. Range seek 应尽量直接定位 Content Uri 对应的文件描述符，避免大文件从头跳读造成长时间等待；对不能随机定位的 Provider 再使用受控跳读。

### P1：避免卡顿和假状态

1. 将 MediaStore 扫描从 Activity 主线程移到 IO dispatcher/Repository，并由 ViewModel 持有界面状态；避免每次 `onResume` 同步扫描全库。
2. 将“已连接设备”做成真实协议：使用稳定的 TV deviceId 上报心跳/断开，按最后心跳显示在线/离线；否则先隐藏当前占位设备管理 UI。
3. TV 连接 API 增加连接/读取超时、取消、重试和清晰错误状态；发现相同手机时以 deviceId 去重，并在 IP 改变后更新地址。
4. 限制 HTTP 并发和请求体/请求头大小，管理 socket 线程池；服务先成功监听端口后再发布 NSD，减少广播已发现但端口未就绪的竞态。
5. 用准确的前台服务类型和通知说明承载后台 LAN 服务，并在 Android 13+ 按需请求通知权限。

### P2：补齐 TV 浏览和遥控体验

1. 增加“全部视频/最近添加”入口、搜索和排序，并将连接时完整读取分页改为滚动时按需加载；目前会把分页结果全部放入 Activity 状态。
2. 将进度显示改为可拖动 SeekBar；明确处理遥控器左/右、确认、返回、长按和播放结束事件。
3. 增加播放器 Loading、错误、重新连接和“从头/继续播放”状态，保存播放位置和本地历史。
4. 增加手机名称设置、授权设备管理和扫描权限状态提示。

### P3：拆分代码结构

保持两个 App 独立，新增共享的模型/协议模块；把 `MainActivity` 中的 API、NSD、播放器、MediaStore 和 UI 状态拆到各自的 data/repository、ViewModel、discovery、player、ui 层。先拆业务边界和可验证协议，再考虑更大的多模块化，避免一次性重写。

## 建议实施顺序

先修复共享目录访问过滤和 Range 边界，再加入配对授权；随后把扫描与网络请求移出主线程，并打通设备心跳与错误恢复；最后补分页、搜索、SeekBar 和播放历史。每阶段用两台设备或 TV 模拟器验证，并覆盖大文件 seek、锁屏和 Wi-Fi 切换。
