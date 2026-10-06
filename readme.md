手机视频电视播放系统 V1.0 功能需求

一、项目目标

开发一套 Android 手机端 + Android TV 端应用。

核心目标：

用户手机中已经下载了视频文件，手机和电视连接在同一个局域网后，电视可以直接浏览手机中的视频列表，并使用电视遥控器选择视频进行播放。

本项目不是屏幕镜像，也不是传统意义上的手机投屏。

播放模式为：

手机作为局域网媒体服务器，电视作为播放器。

基本流程：

手机视频文件
↓
手机 App 提供媒体列表和视频数据
↓
局域网 Wi-Fi
↓
电视 App 获取手机视频列表
↓
用户使用遥控器选择视频
↓
电视本地解码播放

---

二、支持平台

手机端

第一阶段：

Android 手机

建议最低版本：

Android 8.0+

后续可以考虑：

- iOS
- Android 平板

电视端

第一阶段：

Android TV / Google TV

例如：

- 小米电视
- 小米盒子
- Google TV
- Android TV 盒子
- TCL Android TV
- Sony Android TV
- 其他支持安装 APK 的 Android TV

暂时不考虑：

- Samsung Tizen
- LG webOS
- Apple TV

这些后续单独适配。

---

三、使用条件

手机和电视必须处于：

同一个 Wi-Fi / 同一个局域网。

不依赖：

- 云服务器
- 用户账号
- 视频上传
- 外网
- 云存储

视频只在用户自己的局域网中传输。

---

四、产品核心体验

理想使用流程：

1. 手机安装手机端 App。
2. 电视安装 TV 端 App。
3. 手机打开 App。
4. 手机 App 自动扫描本机视频。
5. 手机启动“视频共享”。
6. 电视打开 App。
7. 电视自动发现手机。
8. 电视显示：

可用设备

亮亮的小米手机
在线

其他手机
在线

9. 用户使用遥控器选择：

亮亮的小米手机

10. 电视显示该手机中的视频列表：

手机视频

最近添加

[缩略图] 电影01.mp4
2:15:21[README.md](../CodexGrokPlugins/README.md)
4.2 GB

[缩略图] 视频02.mp4
00:32:15
850 MB

[缩略图] 测试视频.mkv
1:42:30
3.1 GB

11. 用户使用遥控器选择一个视频。

12. 电视全屏播放。

13. 播放过程中可以：

- 暂停
- 继续
- 快进
- 快退
- 拖动进度
- 停止
- 返回视频列表

14. 手机不需要播放视频。

15. 手机屏幕关闭后，尽可能继续提供视频。

---

五、整体架构

系统包含两个独立 App：

PhoneServer
手机端

TVPlayer
电视端

整体关系：

┌──────────────────────┐
│ Android 手机         │
│                      │
│ MediaStore           │
│ ↓                    │
│ 视频数据库           │
│ ↓                    │
│ HTTP Media Server    │
│                      │
│ Device Discovery     │
└──────────┬───────────┘
│
│ LAN / Wi-Fi
│
▼
┌──────────────────────┐
│ Android TV           │
│                      │
│ Device Discovery     │
│ ↓                    │
│ 视频列表             │
│ ↓                    │
│ Media3 / ExoPlayer   │
└──────────────────────┘

---

六、手机端功能

6.1 首页

首页显示：

手机视频共享

状态：
● 服务已开启

设备名称：
亮亮的小米手机

本机视频：
126 个

连接设备：
客厅电视

[停止共享]

未开启状态：

手机视频共享

当前未开启

本机视频：
126 个

[开始共享]

---

七、手机视频扫描

手机 App 自动读取 Android MediaStore 中的视频。

需要读取：

- 视频 ID
- 文件名称
- Uri
- 文件大小
- 视频时长
- 视频宽度
- 视频高度
- 创建时间
- 修改时间
- 所属目录
- MIME Type
- 缩略图

例如：

ID: 10235

Name:
Interstellar.mp4

Duration:
10140000

Size:
8350000000

Resolution:
3840x2160

Folder:
Download

Uri:
content://media/external/video/media/10235

不要求扫描整个文件系统。

第一阶段优先使用：

Android MediaStore。

---

八、视频分类

电视端需要支持以下分类。

全部视频

显示手机所有视频。

最近添加

按照创建时间 / 修改时间排序。

文件夹

例如：

Download
Movies
DCIM
Camera
Telegram
WhatsApp
其他

进入文件夹后显示其中的视频。

---

九、视频列表排序

至少支持：

- 最新优先
- 最旧优先
- 文件名
- 文件大小
- 视频时长

第一版默认：

最新优先。

---

十、视频搜索

电视端支持搜索视频。

搜索范围：

视频文件名。

例如输入：

蜘蛛侠

显示：

蜘蛛侠1.mp4
蜘蛛侠2.mkv
蜘蛛侠：英雄无归.mp4

MVP 可以暂时不做输入法优化。

---

十一、视频缩略图

电视端列表尽量显示视频缩略图。

手机端负责提供缩略图。

例如：

GET /api/videos/10235/thumbnail

要求：

不直接传输原始大图。

建议生成适合 TV 的缓存缩略图。

例如：

320x180
640x360

---

十二、设备发现

电视需要自动发现手机。

手机也可以知道有哪些电视正在访问。

推荐方式：

mDNS / DNS-SD / Android NSD。

手机启动服务后广播：

Service Type:

_phonevideo._tcp

设备信息包括：

deviceId

deviceName

IP

port

protocolVersion

例如：

亮亮的小米手机

192.168.1.25

8080

---

十三、设备 ID

每个手机第一次启动 App 时生成唯一：

deviceId

例如：

3d4a7926-ea13-465c-8df9-xxxx

以后保持不变。

不能直接使用 IP 地址作为设备唯一标识。

因为：

IP 地址可能变化。

---

十四、手机名称

默认名称可以使用：

设备型号 + “手机”

例如：

小米14手机
Pixel 10手机
三星S26手机

用户可以修改：

亮亮的手机
我的小米
卧室手机

电视显示用户设置后的名称。

---

十五、手机 HTTP 服务

手机启动一个局域网 HTTP Server。

例如：

http://192.168.1.25:8080

提供：

- 手机信息
- 视频列表
- 文件夹列表
- 缩略图
- 视频文件

---

十六、建议 API

获取手机信息

GET /api/device

返回：

{
"deviceId": "xxx",
"deviceName": "亮亮的小米手机",
"videoCount": 126,
"version": "1.0"
}

---

获取视频列表

GET /api/videos

支持：

page
pageSize
sort
folder
keyword

例如：

GET /api/videos?page=1&pageSize=50

---

视频对象

建议格式：

{
"id": "10235",
"name": "Interstellar.mp4",
"duration": 10140000,
"size": 8350000000,
"width": 3840,
"height": 2160,
"mimeType": "video/mp4",
"folder": "Download",
"modifiedTime": 1791160000000,
"thumbnailUrl": "/api/videos/10235/thumbnail",
"streamUrl": "/api/videos/10235/stream"
}

---

十七、获取文件夹

GET /api/folders

返回：

[
{
"id": "download",
"name": "Download",
"videoCount": 18
},
{
"id": "movies",
"name": "Movies",
"videoCount": 12
}
]

---

十八、视频播放接口

例如：

GET /api/videos/{id}/stream

例如：

GET /api/videos/10235/stream

该接口必须支持：

HTTP Range。

这是核心要求。

---

十九、HTTP Range

电视播放视频时必须能够按区间读取文件。

例如电视请求：

Range: bytes=10000000-

手机返回：

206 Partial Content

以及：

Accept-Ranges: bytes
Content-Range
Content-Length
Content-Type

必须支持：

- 从中间开始播放
- Seek
- 快进
- 快退
- 大文件
- 播放恢复

这是整个项目最重要的底层功能之一。

---

二十、大文件支持

必须重点测试：

- 500 MB
- 2 GB
- 4 GB
- 10 GB
- 20 GB+

不能把完整视频加载到内存。

必须采用：

流式读取。

---

二十一、电视首页

TV App 首页：

手机媒体

附近设备

┌─────────────────────┐
│ 📱 亮亮的小米手机    │
│    在线              │
│    126 个视频        │
└─────────────────────┘

┌─────────────────────┐
│ 📱 Pixel 手机        │
│    在线              │
│    32 个视频         │
└─────────────────────┘

遥控器可以上下选择。

---

二十二、连接手机

点击：

亮亮的小米手机

进入：

亮亮的小米手机

最近添加

全部视频

文件夹

搜索

---

二十三、电视视频列表

建议优先使用横向海报 / 网格布局。

例如：

亮亮的小米手机

最近添加

┌──────────┐ ┌──────────┐ ┌──────────┐
│          │ │          │ │          │
│ thumbnail│ │ thumbnail│ │ thumbnail│
│          │ │          │ │          │
└──────────┘ └──────────┘ └──────────┘
电影01       电影02        电影03
2:15:21      1:30:12       00:45:33

遥控器焦点必须明显。

---

二十四、视频详情页

点击视频后可以：

方案 A：

直接播放。

方案 B：

先进入详情页。

第一版推荐：

直接播放。

长按 / 菜单键后续可以增加详情。

---

二十五、电视播放器

推荐使用：

AndroidX Media3 / ExoPlayer。

播放器功能：

- 播放
- 暂停
- Seek
- 快进
- 快退
- 播放进度
- 总时长
- Loading
- 播放错误提示

---

二十六、遥控器操作

播放界面：

方向左：

快退。

方向右：

快进。

OK：

显示控制栏 / 暂停。

返回：

退出播放，返回视频列表。

建议：

左 / 右

每次 ±10 秒

长按：

连续 Seek。

---

二十七、继续播放

电视保存：

deviceId

videoId

position

duration

例如：

Interstellar.mp4

上次看到：
01:24:32

再次点击时提示：

继续播放

01:24:32

[继续播放]

[从头播放]

第一版可实现。

---

二十八、播放历史

电视保存最近播放的视频。

例如：

继续观看

Interstellar
36%

Avengers
72%

Video001
15%

注意：

历史记录保存在电视端。

不需要手机保存。

---

二十九、手机锁屏

手机锁屏后：

尽可能保证媒体服务继续运行。

Android 手机端需要：

Foreground Service。

状态栏显示：

手机视频共享正在运行

已连接：
客厅电视

用户可以：

点击“停止”。

---

三十、网络断开处理

如果播放过程中手机掉线：

电视显示：

连接已断开

正在重新连接手机...

自动尝试重新连接。

例如：

1 秒
2 秒
5 秒
10 秒

如果仍然失败：

无法连接手机

[重试]

[返回]

---

三十一、手机 App 被杀掉

电视发现媒体服务器消失后：

显示：

手机已离线

不能一直 Loading。

---

三十二、Wi-Fi 切换

如果手机 IP 发生变化：

电视不能继续使用旧 IP。

应该通过：

mDNS / NSD

重新发现同一个：

deviceId

然后更新：

IP + Port。

---

三十三、安全

第一版虽然只在 LAN 工作，但不能完全裸奔。

至少设计：

首次连接确认。

电视第一次连接手机时：

手机显示：

客厅电视请求访问你的视频

设备：
客厅电视

[允许]

[拒绝]

允许以后保存 TV deviceId。

以后无需再次询问。

---

三十四、访问 Token

手机允许电视后生成 Token。

之后电视访问 API：

Authorization: Bearer xxxxxx

避免同一个局域网里的任意设备直接读取视频。

---

三十五、已连接设备

手机可以查看：

已允许设备

客厅电视
已连接

卧室电视
离线

小米盒子
离线

可以：

取消授权

---

三十六、隐私

默认：

不上传任何文件。

不上传：

- 视频
- 文件名称
- 视频缩略图
- 使用记录

所有内容仅：

局域网传输。

---

三十七、视频格式

第一版尽量支持：

- MP4
- MKV
- MOV
- AVI
- WEBM
- TS

实际解码能力由：

Android TV + Media3 + 系统 Codec

决定。

---

三十八、编码格式

主要考虑：

视频：

- H.264
- H.265 / HEVC
- VP9
- AV1

音频：

- AAC
- MP3
- AC3
- EAC3

第一版不做实时转码。

如果电视不支持某编码：

提示：

当前电视不支持该视频格式

不要让手机实时转码。

转码属于后续高级功能。

---

三十九、字幕

MVP 第一版：

支持视频内嵌字幕。

后续支持：

video.mkv
video.srt

自动关联外挂字幕。

外挂字幕不是第一阶段必要功能。

---

四十、多音轨

如果视频存在多个音轨：

播放器能够选择音轨。

例如：

音轨

中文
英语
日语

使用 Media3 原生能力。

---

四十一、分页

手机存在几千个视频时：

禁止一次返回全部视频。

使用：

page
pageSize

例如：

pageSize = 50

电视滚动到末尾后加载下一页。

---

四十二、缓存

电视可以缓存：

- 视频列表
- 缩略图
- 最近播放

但不能默认缓存完整视频。

视频数据：

边播放边读取。

---

四十三、手机性能要求

播放过程中：

手机主要负责：

文件读取 + 网络发送。

不进行：

视频解码。

不进行：

视频编码。

不进行：

屏幕录制。

不进行：

实时转码。

所以理论上：

功耗和性能消耗应显著低于屏幕镜像。

---

四十四、电视性能要求

解码工作：

由电视完成。

尽量使用：

硬件解码。

---

四十五、网络要求

1080P：

一般 Wi-Fi 足够。

4K 高码率视频：

建议：

5 GHz Wi-Fi / Wi-Fi 6。

软件本身无需限制分辨率。

---

四十六、第一阶段 MVP

第一阶段不要做太复杂。

必须完成以下功能：

手机端

- 扫描本机视频
- 获取视频信息
- 开启/关闭共享
- HTTP Server
- HTTP Range
- mDNS/NSD 广播
- 视频列表 API
- 缩略图 API
- 视频 Stream API
- Foreground Service
- 简单连接授权

电视端

- 自动发现手机
- 显示手机列表
- 连接手机
- 获取视频列表
- 显示缩略图
- 遥控器导航
- 视频播放
- 暂停
- Seek
- 快进/快退
- 播放错误处理
- 继续播放

---

四十七、MVP 暂时不要实现

第一阶段暂时不要：

- 用户账号
- 云服务
- 外网播放
- 实时转码
- NAS
- SMB
- WebDAV
- DLNA
- AirPlay
- Chromecast
- 手机投屏
- 屏幕镜像
- iPhone
- Tizen
- webOS

核心目标先做好：

Android 手机

↓

Android TV

↓

浏览手机视频

↓

遥控器直接播放

---

四十八、第二阶段功能

MVP 稳定之后增加：

- 外挂字幕
- 多音轨 UI
- 视频收藏
- 视频搜索
- 文件夹分类
- 更多排序方式
- 手机主动控制电视
- 播放历史同步
- 多手机切换
- 多电视
- TV 端漂亮海报墙
- 自动重连
- 自动恢复播放

---

四十九、第三阶段

后续可以考虑：

手机主动发送视频到电视：

手机选择视频

↓

播放到

↓

客厅电视

即：

手机和电视都可以发起播放。

此时产品同时支持：

模式 A：

电视 → 浏览手机 → 播放

模式 B：

手机 → 选择视频 → 选择电视 → 播放

依然不是屏幕镜像。

---

五十、推荐项目结构

PhoneTvVideo/
│
├── phone-app/
│
│   ├── media/
│   │   ├── MediaScanner
│   │   ├── MediaRepository
│   │   └── ThumbnailManager
│   │
│   ├── server/
│   │   ├── HttpServer
│   │   ├── VideoApi
│   │   ├── VideoStreamHandler
│   │   └── AuthManager
│   │
│   ├── discovery/
│   │   └── NsdPublisher
│   │
│   ├── service/
│   │   └── MediaServerService
│   │
│   └── ui/
│
├── tv-app/
│
│   ├── discovery/
│   │   └── NsdDiscovery
│   │
│   ├── api/
│   │   └── PhoneApiClient
│   │
│   ├── player/
│   │   └── VideoPlayer
│   │
│   ├── data/
│   │
│   └── ui/
│       ├── DeviceScreen
│       ├── HomeScreen
│       ├── VideoScreen
│       └── PlayerScreen
│
└── common/
├── models/
└── protocol/

---

五十一、技术选择建议

开发语言：

Kotlin

UI：

手机：

Jetpack Compose

电视：

推荐：

Compose for TV

播放器：

AndroidX Media3
ExoPlayer

局域网发现：

Android NSD
mDNS
DNS-SD

HTTP：

可以选择成熟轻量框架，也可以实现专用 Server。

关键不是具体库，而是必须稳定支持：

HTTP Range
大文件
并发请求
Content Uri
Seek

---

五十二、最关键技术要求

Codex 实现时优先保证以下几点。

第一优先级

HTTP Range 正确。

否则：

Seek 和大视频播放都会有问题。

第二优先级

Android Content Uri。

不能假设所有视频都有传统：

/storage/emulated/0/xxx

路径。

第三优先级

Foreground Service。

避免锁屏后服务器立即停止。

第四优先级

设备重新发现。

不能永久缓存 IP。

第五优先级

电视遥控器 Focus。

TV App 必须真正适合遥控器操作。

不能直接把手机 UI 搬到电视。

---

五十三、验收测试

MVP 完成后至少验证以下场景。

场景 1

手机和电视同 Wi-Fi。

电视可以发现手机。

场景 2

电视可以查看手机视频列表。

场景 3

电视可以打开 MP4 视频。

场景 4

播放 10 GB 视频。

不能崩溃。

场景 5

视频播放到 50%。

Seek 到 80%。

能够快速继续播放。

场景 6

手机锁屏。

视频继续播放。

场景 7

手机切换 Wi-Fi 后重新连接。

电视重新发现手机。

场景 8

手机 App 服务停止。

电视能够识别离线。

场景 9

播放完成。

自动回到视频列表，或者显示重播。

场景 10

电视退出播放。

手机服务继续运行。

---

五十四、最终产品定义

这个产品本质上是：

手机端轻量 NAS / Media Server

+

电视端视频播放器

最核心的用户价值：

手机里已经下载的视频

不复制到 U 盘

不上传云盘

不投屏

不镜像