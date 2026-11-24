# Android 5 AAC 音频兼容模块

本模块移植自 `hongguoTV-N3urda-0.16.11/kotlin-tv/decoder-ffmpeg`，只由应用的
`android5` 变体依赖。标准版不依赖本模块，不包含原生库，也不需要安装 NDK。

Android 5.0/5.1 的部分系统 AAC 解码器会在有输入的情况下持续不输出。本模块通过
Media3 的 FFmpeg 音频扩展绕过该问题，并提高老系统的 PCM 缓冲下限。只有 API 21–22
优先启用扩展渲染器；API 23 及以上继续使用系统音频渲染器。首页试看与全集播放共用配置。

只编译 AAC 解码和 PCM 重采样，不包含视频解码、网络协议、媒体解复用或 HEIC 图片解码。
生成 `armeabi-v7a` 和 `arm64-v8a` 两份 `libffmpegJNI.so`，使用 API 21 原生接口、16 KiB
页对齐，并静态链接 C++ 运行时。兼容版面向双 ARM 电视与盒子；x86 设备请使用标准版。

## 构建环境

- JDK 17、Android SDK 36、Build-Tools 36.0.0。
- Android NDK **27.1.12297006**。
- Python **3.12+**、curl、make、支持 xz 的 Python 标准库。
- Linux 或 macOS；Windows 使用 WSL。

在仓库根目录构建双包：

```bash
./kotlin-tv/gradlew -p kotlin-tv -PHONGGUOTV_BUILD_ANDROID5=true :app:assembleRelease
```

正式包沿用应用原有签名配置。开发双包使用 `:app:assembleDebug`。只生成兼容版可以使用
`:app:assembleAndroid5Release`，同样需要 `-PHONGGUOTV_BUILD_ANDROID5=true`。

Gradle 的 `preBuild` 调用 `tools/build_native.py`。脚本首次下载固定源码并校验 SHA-256，
后续按构建脚本、JNI 源码和工具链版本缓存结果。需要离线构建时，将准确的源码归档提前放到
`kotlin-tv/decoder-ffmpeg/build/ffmpeg-6.1.6.tar.xz`。

## 来源与授权

- Java 接口、JNI 桥接和混淆规则来自 AndroidX Media **1.8.0** 的 `decoder_ffmpeg`，
  采用 Apache-2.0；完整授权见 `LICENSE-AndroidX`，安装包中保留相应授权文本。
- FFmpeg **6.1.6** 采用 LGPL-2.1-or-later；完整授权见 `LICENSE-FFmpeg`。
  未启用 GPL、nonfree 或任何外部编解码库。
- 固定源码地址：<https://ffmpeg.org/releases/ffmpeg-6.1.6.tar.xz>。
- SHA-256：`d4fcb164028dd3beee5d92c0ac72e46aac6973c75ea12dc14de07bf8f407370a`。

相对上游，保留了参考项目中输出缓冲扩容后恢复 PCM 追加位置的修改；源码说明改为中文，
格式注册仅保留 AAC，并按重采样实际返回的样本数累计输出长度。完整 JNI、Java 包装、
固定源码和构建参数均可用于替换及重新链接库。发布兼容 APK 时应随附对应 FFmpeg 源码归档、
本模块全部源码及授权文件；主应用继续遵循仓库原有 GPL-3.0 授权。

本模块不解决老设备的视频硬解能力、HEIC 封面解码或 ROM 特有的安装问题。
