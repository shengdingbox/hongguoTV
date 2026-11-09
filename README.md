# hongguoTV

非官方的红果短剧 Android TV 客户端，Kotlin 原生实现。电视安装一个 APK，联网即可浏览和播放，无需部署 Node、Docker 或填写服务地址。

**[下载最新版 APK](https://github.com/N3urda/hongguoTV-updates/releases/latest)** · [原生版说明与构建](kotlin-tv/README.md)

- 最低 Android 8.0（API 26），目标 API 36；适配电视遥控器。
- 短剧 / 漫剧浏览、搜索、排行榜、详情与分集选集。
- 本地收藏、最近观看、断点续播、全局清晰度与倍速、自动连播。
- 应用内更新：前台浏览时检查并下载已签名的正式版，校验后由系统确认安装。见 [自动更新说明](kotlin-tv/AUTO_UPDATES.md)。

## 工程结构

| 路径 | 用途 |
| --- | --- |
| `kotlin-tv/app/` | 电视应用：界面、焦点、播放生命周期、本机记录 |
| `kotlin-tv/core/` | 内容接入、请求签名、媒体解密与分段读取，可独立在 JVM 上测试 |
| `kotlin-tv/tools/` | 开发期工具：签名对照数据生成与验证记录校验 |
| `kotlin-tv/vendor/` | GPL-3.0 上游源码副本，仅作 Kotlin 移植的对照，不参与构建 |
| `kotlin-tv/docs/` | 原生版调查文档 |
| `scripts/` | 构建与公开仓库同步脚本 |

## 构建

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0。Gradle 8.14.3 包装器已包含，不需要 npm 或 Node。

```sh
# 开发包：直接可安装，无需发布私钥
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintDebug :app:assembleDebug
adb install -r kotlin-tv/app/build/outputs/apk/debug/app-debug.apk

# 正式包：需要签名配置，见 kotlin-tv/README.md
./scripts/build-kotlin.sh
```

Android 8.0 的最低版本配置不等于目标电视已经验收；实体电视的解码、音频、遥控器和系统安装入口仍需实机确认。

## 许可证

GPL-3.0-only，全文见 [LICENSE](LICENSE)。核心的请求签名与媒体处理移植自 GPL-3.0 的 drpys 项目，见 [kotlin-tv/NOTICE.md](kotlin-tv/NOTICE.md)。本项目不是内容平台官方客户端，内容接口无平台授权依据，接口或算法变化会导致功能失效。
