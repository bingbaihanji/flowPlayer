<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_logo.png" width="150" alt="FlowPlayer 图标" />
</p>

<h1 align="center">FlowPlayer</h1>

<p align="center">
  本地视频 / 音频 / 图片播放器 · Media3 + LibVLC 双引擎 · WiFi 局域网文件传输
</p>

<p align="center">
  <a href="https://github.com/bingbaihanji/flowPlayer/releases">下载安装包</a>
</p>

---

FlowPlayer 是一款 Kotlin + Jetpack Compose 实现的 Android 本地媒体播放器，支持双播放引擎、文件库管理、播放列表与历史、图片查看以及局域网文件互传。

> 本项目为个人学习与本地使用，与原版应用及其公司无关联，不包含原版代码、资源或网页。

## 功能

### 本地媒体

- 视频 / 图片 / 音频分类浏览，支持 MediaStore 专辑与文件系统目录
- 搜索、排序（名称 / 时间 / 大小 / 类型，升降序）、显示隐藏文件
- 列表 / 网格切换，面包屑导航
- 重命名、删除；多选后批量删除、批量加入播放列表
- 图片查看器：横滑翻页、双指缩放、双击放大 / 复位

### 播放器

- 双引擎：Media3（MP4 / MKV / WebM 等）与 LibVLC（AVI / TS / RMVB / WMV / APE 等旧格式自动切换）
- 播放 / 暂停、进度拖动、上一集 / 下一集、播放模式（顺序 / 单曲 / 随机）
- 手势：单击显隐控件、双击播放暂停、长按 3 倍速、横向拖动进度、左侧亮度、右侧音量、屏幕锁定
- 倍速（常规 / 细分）、外挂字幕（SRT / VTT / ASS）、内嵌字幕、音视频轨切换
- 画面比例、PiP、通知栏控制、后台播放、定时停止
- 播放历史与续播；Media3 失败时可切换 LibVLC 兼容模式

### 播放列表 / 历史 / 设置

- 创建、重命名、删除播放列表；加入 / 移除条目
- 播放记录续播、清空
- 播放模式、倍数模式、界面风格（跟随系统 / 浅色 / 深色），设置持久化

### WiFi 局域网传输

- 手机端内嵌 Ktor HTTP 服务，端口 8080–8099 自动回退
- 网页端支持上传、下载、删除、文件列表与上传进度
- 访问地址携带 token，错误 token 返回 403；拦截路径穿越与非法文件名；单文件上限 4GB
- 支持 HTTP Range 下载；重名文件自动追加 `(1)`
- App 内展示二维码与接收列表；文件保存至 `Download/FlowPlayer/Transfer`（失败时回退应用私有目录）
- 需 Android 8.0+，手机与电脑处于同一局域网

### 更多

- 设置、接收文件、关于
- 关于页展示版本信息、开源组件许可、隐私说明与作者信息

## 技术栈

| 类别 | 使用 |
| --- | --- |
| 语言 / UI | Kotlin 2.3.21、Jetpack Compose（BOM 2025.09.00）、Material 3 |
| 构建 | AGP 9.3.0、Gradle 9.5.1、JDK 21 |
| SDK | minSdk 24 / targetSdk 36 / compileSdk 36，Java / Kotlin target 11 |
| 播放 | AndroidX Media3 1.10.0、LibVLC 3.7.0 |
| 数据 | Room 2.8.5（KAPT）、DataStore 1.1.7 |
| 网络 | Ktor 3.6.2（CIO） |
| 图片 | Coil 2.7.0（含 GIF / 视频缩略图） |
| 其他 | Navigation Compose 2.9.3、ZXing 3.5.4、Core Library Desugaring 2.1.5 |

## 构建

环境要求：JDK 21、Android SDK 36、Android Studio（可选）。

```bash
git clone https://github.com/bingbaihanji/flowPlayer.git
cd flowPlayer

# Debug
./gradlew :app:assembleDebug

# Release
./gradlew :app:assembleRelease
```

产物路径：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`

当前仅打包 `arm64-v8a`。Release 构建目前复用本机 debug 签名，便于覆盖安装且保留数据；正式上架应用商店前请配置独立的 release keystore。

## WiFi 传输使用

1. 手机与电脑连接同一 Wi-Fi。
2. App → 更多 → 接收文件 → 启动接收服务。
3. 页面显示 `http://<手机IP>:<端口>/?t=<token>` 与二维码，在电脑浏览器打开该地址。
4. 网页可拖拽 / 多选上传，也可下载或删除文件。
5. 接收的文件默认保存在 `Download/FlowPlayer/Transfer`。

## 项目结构

```text
app/src/main/java/com/bingbaihanji/flowplayer/
├── core/
│   ├── common/        # 权限、通用工具
│   ├── data/          # Room、DataStore、媒体扫描与仓库
│   ├── player/        # 播放引擎抽象、Media3 / LibVLC、队列、历史
│   ├── transfer/      # Ktor 服务、传输存储、token
│   └── ui/            # 主题与通用组件
├── feature/
│   ├── library/       # 本地媒体
│   ├── playlist/      # 播放列表
│   ├── history/       # 播放记录
│   ├── player/        # 播放页、手势与控制菜单
│   ├── viewer/        # 图片查看
│   ├── transfer/      # WiFi 传输
│   ├── settings/      # 设置
│   └── more/          # 更多 / 关于
└── navigation/        # 底部导航与页面路由
```

## 已知限制

- 仅打包 `arm64-v8a`（与原版应用一致）。
- WiFi 传输要求 Android 8.0+；Android 7.0 / 7.1 仅支持本地播放。
- 冷门格式由 LibVLC 兜底；AVI 使用软件解码以规避部分设备的硬件解码异常。
- 不使用 SAF，依赖「所有文件访问权限」或旧版存储权限。
- 不含账号云、DLNA、视频裁剪、私密相册、相机扫码与悬浮窗。

## 作者

- 作者：bingbaihanji
- 邮箱：bingbaihanji@gmail.com
- QQ：2761868857
- GitHub：<https://github.com/bingbaihanji/flowPlayer>

## 声明

本项目仅供个人学习、研究与本地使用；与原版应用及其公司无关联，不包含原版代码、资源或网页。仓库未附带开源许可证，未经许可请勿用于商业用途。
