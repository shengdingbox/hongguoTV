# Android 5 HEIC 图片兼容模块

仅由应用的 `android5` 变体依赖，标准版不包含本模块及其原生库。基于 libheif 与 libde265
软件解码 HEIC，返回 Android Bitmap，不依赖系统 HEVC 解码器，也不需要外部转换服务。

## Glide 接入

应用为两种变体分别提供 `ArtworkCompatibility`。兼容版在原有系统位图解码器之后追加
ByteBuffer、InputStream 两个 HEIC 解码器。先按文件头判断格式，再进入原生库，排除 AVIF；
普通 JPEG/PNG/WebP 继续沿用原来的链路。软件解码返回的 Bitmap 进入 Glide 位图池，
现有 `DiskCacheStrategy.ALL` 缓存原图和解码结果，避免重复处理。

文件大小限制 8 MiB，解码像素限制 8 × 1024 × 1024，原生总内存预算 96 MiB，输出边长不超过
2048，并按控件目标尺寸等比缩小。单进程串行软件解码，关闭并行瓦片解码；保留 HEIF 旋转、
裁剪与透明度处理。原生异常转换为 IOException，由 Glide 展示原有失败状态。

## 固定源码和构建

- libheif **1.23.7**、libde265 **1.1.3**；源码 URL 和 SHA-256 位于 `sources.json`。
- Android NDK **27.1.12297006**、CMake **3.22.1**、Python **3.12+**、curl 和 make。
- API 21 的 `armeabi-v7a`、`arm64-v8a`，输出 `libheif_native.so`。
- 仅启用 libde265 的 HEVC 解码；关闭编码器、AVIF、JPEG/JPEG2000、VVC、FFmpeg 和动态插件。
- C++ 运行时静态链接，只导出 JNI，链接器裁掉未引用函数，并设置 16 KiB 页对齐。

Gradle 在兼容版的 `preBuild` 调用 `tools/build_native.py`，下载固定源码、验证校验值，
依次构建静态 libde265、静态 libheif 和 JNI。中间库保留在 `build/native-work`，按源码和
构建脚本指纹缓存。离线构建可提前把准确的两个源码归档放到 `build/sources`。

```bash
./kotlin-tv/gradlew -p kotlin-tv -PHONGGUOTV_BUILD_ANDROID5=true :app:assembleRelease
```

此命令同时生成标准包和兼容包，并保留标准包原有的导出路径。只构建标准包不需要本模块的
NDK、CMake 或源码下载。

## 来源和授权

Glide 接入方式参考 [GlideHeifDecoder](https://github.com/msisuzney/GlideHeifDecoder)，
本模块重新编写有限输入、异常释放和变体隔离逻辑，不依赖该项目的旧版 AAR。

- [libheif](https://github.com/strukturag/libheif) 与 [libde265](https://github.com/strukturag/libde265)
  的完整授权分别位于 `LICENSE-libheif`、`LICENSE-libde265`，APK 同时包含授权文本。
- 本项目的 JNI、Java 包装和构建脚本沿用仓库 GPL-3.0 授权。
- 分发兼容 APK 时，应提供 `sources.json` 中的对应源码归档及本模块的包装源码、构建脚本和授权，
  用于重新编译及链接；构建目录中的中间静态库也可用于定位和重新链接。
