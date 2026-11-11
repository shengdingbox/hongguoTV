# 原生版第三方声明

`core` 中 Spade URL / key 处理、MP4 CENC 处理是以下 GPL-3.0 源码的 Kotlin 移植：

- drpys, `spider/js/红果果[短].js`
- https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715
- 本仓库对照源：`../server/vendor/hongguo.cjs`，取回于 2026-09-21。
- 本版改动：完整 Kotlin 实现；加密媒体通过 Media3 DataSource 直接读取；增加样本边界检查、子样本处理和 AVC 原始编码恢复。

`core` 中的请求签名算法（`SignPrimitives.kt`、`SignAlgorithms.kt`、`AesV3.kt`，含 SM3、
X-Gorgon / X-Ladon / X-Argus / X-Medusa / X-Helios 与魔改 AES-V3）与匿名设备注册协议
（`DeviceRegister.kt`）是以下开源源码的 Kotlin 移植，均在设备本地离线计算，
不依赖任何外部签名服务：

- openhongguo, `internal/protocol/sign/`、`internal/protocol/register.go`
- https://github.com/beilunyang/openhongguo
- 对照版本见 `docs/SIGN_SERVICE.md`；测试向量取自其 `sign_test.go`，见 `LocalSignerTest.kt`。
- 本版改动：仅保留实际在用的签名路径；hash 分支通过调整 `_rticket` 选取；设备档案落在应用私有目录。

`PlaybackSigning.kt` 的 Argus 551 / Ladon 实现移植自 openhongguo PR #7：

- https://github.com/beilunyang/openhongguo/pull/7
- 固定提交：`bed14595d35b02e0d550270621812ca7386c4108`。
- 原始 Go 实现与测试向量以 Apache-2.0 发布；本仓库改写为 Kotlin，保留相同的信封、轮函数和请求绑定字段。
- 上游许可证随 APK 打包于 `app/src/main/assets/licenses/openhongguo-LICENSE.txt`。

手机推荐流（`Recommendations.kt`、`ContentRepository.recommendations`）对照开心果 iOS 所用
openhongguo `02437b6286956e8239d54581e3cdaccda817a7fb` 的 `Discover`、
`SignedDiscoverPairsIn`、`SignedCellChangePairs`、`ParseDiscoverTab` 和 `ParseCellChange`（Apache-2.0）：

- https://github.com/kadaliao/openhongguo/tree/02437b6286956e8239d54581e3cdaccda817a7fb
- Kotlin 实现合并推荐频道预加载与猜你喜欢首批卡片，按服务端会话游标分页并跨页去重；
  匿名注册 Cookie 单独持久化，推荐缓存与网站榜单、分类缓存分别保存。

新剧日历接口的请求参数与响应字段（`NewReleases.kt`、`ContentRepository.newReleases`）参考：

- 开心果 iOS：https://github.com/kadaliao/kaixinguo-ios ，`KaixinguoKit/Sources/KaixinguoKit/AppModel.swift` 中的 `newFeed`。
- openhongguo：https://github.com/beilunyang/openhongguo ，`SignedNewReleasePairs` / `ParseNewReleases`（Apache-2.0）。
- 本仓库以 Kotlin 接入相同的公开日历协议，复用本机匿名设备签名；日历、预告和分页在安卓端展示。

手机热搜与排行榜（`MobileDiscovery.kt`、`ContentRepository.hotSearch/mobileRanking`）的请求参数和响应字段参考
openhongguo 的 `SignedHotSearchPairs` / `ParseHotSearch`、`ShortRank` / `ParseShortRank`（Apache-2.0）。
安卓端按服务端目录展示热搜分组、内容类型和子榜，游标独立于电视榜单。

`kotlin-tv/` 原生应用源码和派生实现以 GPL-3.0-only 发布，许可证全文见 `LICENSE`。发布 APK 时同时提供本分支对应源码、Gradle 包装器和构建说明。APK 不包含 Node、React Native、JavaScript 引擎或该 JavaScript 源文件。

其他依赖：

- ZXing core（本机生成手机输入与更新二维码）：https://github.com/zxing/zxing — Apache-2.0
- Kotlin 标准库：https://github.com/JetBrains/kotlin — Apache-2.0
- AndroidX Media3：https://github.com/androidx/media — Apache-2.0
- OkHttp / Okio：https://github.com/square/okhttp / https://github.com/square/okio — Apache-2.0
- jsoup（仅解析官网 HTML，不执行网页脚本）：https://jsoup.org/ — MIT
- Android desugar_jdk_libs：https://github.com/google/desugar_jdk_libs — GPL-2.0 with Classpath Exception
- org.json（仅 JVM 工具，APK 使用 Android 内置实现）：https://github.com/stleary/JSON-java — 公有领域声明
- JUnit（仅测试）：https://github.com/junit-team/junit4 — EPL-1.0

应用图标和 TV banner 沿用本仓库素材。这里的应用不是内容平台官方客户端。
