# 本地播放签名迁移 openhongguo PR #25

参考：https://github.com/beilunyang/openhongguo/pull/25（提交 `09a0160`）。

PR 要求播放取址同时保留 `x-medusa` 和真实 Argus/Ladon。TV 原有通用签名仍是
v04.06.04 格式，仅保留头部或修改 field21 无法复现当前 SDK 的请求。

本次普通短剧优先使用 SDK 的请求形态：

- `POST https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/`。
- 复用本机注册的匿名身份，查询顺序对齐 `DeviceQueryPairs`，统一追加 `ts`、`_rticket`。
- 请求体对齐 `PlaybackBody`，只发送 `video_id` 和含 `need_all_video_definition=true`、
  `video_id_type=0`、`video_platform=3` 的 `biz_param`；签名和发送共用原始字节。
- V5 Medusa 使用 v04.10.03 的信封、设备环境、field21=369、04/05 标志和 F13 分支 12。
  配套生成 Gorgon/Helios，并使用同一份最终查询串、时间戳、请求体补齐真实 Argus/Ladon。
- 保留 74532 请求、GET 和旧 POST 的失败回退；低清接口的成功记忆不能跳过优先请求。
  合集仍按 PUGC 分组取址，远程算法仍使用各自的签名器。
- 签名测速、模型预热和正式播放使用同一份本地签名与请求体。

2026-11-25 在本地 JVM 上直接使用修改后的 App core 源码实测，未调用远程签名服务。
通过 `ContentRepository.stream(maxQuality=1080, refreshModel=true, playback=true)` 取址，
并用 `Range: bytes=0-1023` 读取媒体地址：

| 分集 ID | 清晰度 | 取址耗时 | 内容密钥 | 媒体读取 |
| --- | --- | --- | --- | --- |
| 7675424396831378456 | 1080P | 727 ms | 16 字节 | HTTP 206，1024 字节，MP4 `ftyp` |
| 7699712716172184600 | 1080P | 155 ms | 16 字节 | HTTP 206，1024 字节，MP4 `ftyp` |

两集均由优先请求直接返回模型。另有 20 条 SDK F13 固定向量及 102 个相关 core
回归用例通过，覆盖签名信封、清晰度上限、兼容编码过滤、回退、路由记忆和远程算法隔离。
独立探针位于工作区 `openhongguo-test/hgtv-pr25-probe`，没有加入项目源码或 APK。

上述结果代表这两个样本在本次设备身份及网络环境下的表现；实际清晰度仍由片源和平台下发模型决定。
