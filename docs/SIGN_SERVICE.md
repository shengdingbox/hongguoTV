# 请求签名与匿名设备

更新日期：2026-11-16。

红果TV 默认使用本地算法。在「设置 → 算法模式」中可打开选择弹框，选择本地计算、远程计算-unidbg
或远程计算-frida，设置页与弹框只展示算法名称。选择会保存，切换后立即生效。播放取址还支持自动切换：当前算法连续三次取地址失败时切换到备用算法，本地计算切换为远程计算-unidbg，远程计算切换为本地计算；真实播放取址成功会重置连续失败计数，并同步保存设置。自动切换不会中断已经缓冲的播放，下一次取址使用新算法。
本文说明本地算法模式：注册、普通接口签名和播放 V5 签名均在本机完成；远程计算-frida 模式
使用 Android 签名服务的设备档案及 `video_tab` 请求；远程计算-unidbg 模式把签名交给
openhongguo-signer（unidbg 内运行 `libmetasec_ml.so`），设备档案仍由本机注册。
两种远程模式都跳过本机 V5 播放取址路由（`usesLocalPlaybackAlgorithm = false`）。

## 组件

| 文件 | 作用 |
| --- | --- |
| `SignPrimitives.kt` | SM3、GF(2^8)、protobuf 编码、IV 派生、旧 AES S 盒 |
| `SignAlgorithms.kt` | 普通接口的 Gorgon / Ladon / Argus / Medusa / Helios |
| `PlaybackV5Signer.kt` | 播放专用 V5 摘要、Helios、Medusa，以及进程稳定的签名上下文 |
| `PlaybackV5Aes.kt` / `PlaybackV5Constants.kt` | V5 Medusa 的专用 S 盒、位映射、密钥及协议常量 |
| `MobilePlayback.kt` | 手机端 V5 参数、gzip POST、指定集模型解析 |
| `PlaybackSigning.kt` | 兼容旧播放路由的 Argus 551 / Ladon |
| `AesV3.kt` | 普通接口 Medusa 的旧 AES-V3 载波 |
| `DeviceRegister.kt` | 匿名设备注册（GZIP + ttEncrypt），注册版本 7.4.3.32 |
| `RemoteSigner.kt` | 共用匿名设备档案与普通接口签名 |
| `UnidbgSigner.kt` | 远程计算-unidbg：把 URL 与请求体交给 openhongguo-signer 签名 |
| `SharedPrefsSignerStore.kt`（app） | 本机匿名设备档案持久化 |

V5 代码移植自 GPL-3.0-only 授权的开源实现，摘要与密钥处理保持原样。其他签名算法来源与
许可证见根目录 [README.md](../README.md) 的「来源与声明」。两套签名的 AES 常量及载波各自保存。

## 工作方式

1. **注册与复用身份**：首次访问向 `log5-applog-lf.fqnovel.com/service/2/device_register/`
   注册匿名设备，取得 `device_id` / `install_id`。档案存入
   `SharedPreferences("native-signer-v1")`，后续启动复用。内容、播放、弹幕与账号共用同一
   `RemoteSigner`，V5 请求也从这份档案读取身份。
2. **普通接口签名**：内容、搜索、弹幕、账号继续走各自现有签名路径。
3. **V5 播放取址**：原生模式优先请求
   `POST https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/v1/`。
   请求只包含当前集，使用 `mixed_video_id_map["1004"]`、`dr_scene=player`、
   `video_platform=1024`、`device_level=1`。查询串使用上游手机端模板
   `74332 / 7.4.3.32 / vivo_8662_64 / PGT-AN10 / Android 12`；UA 沿用该协议模板原值。
4. **压缩与签名绑定**：先将 JSON gzip，再对实际发送的压缩字节计算摘要及 `X-SS-STUB`。
   查询串不添加 `ts`，通过调整 `_rticket` 选中 `iv & 15 == 12` 分支；核心签名头只保留
   `X-Khronos`、`X-Helios`、`X-Medusa`。V5 请求不混入旧 Argus / Ladon / Gorgon /
   `X-SS-Req-Ticket`，也不携带注册或账号 Cookie。进程 nonce、进程启动时间在所有请求间保持稳定。
5. **模型与播放**：精确读取 `data["1004"][视频ID].video_model` 或平铺的
   `data[视频ID].video_model`，兼容模型对象与 JSON 字符串。地址解析兼容明文、Base64、
   `key_seed`，密钥解析兼容嵌套 `encrypt_info.spade_a` 与平铺 `spade_a`。
   继续过滤 ByteVC2，调用原有 `MediaCrypto` 解包内容密钥，再交给既有 `RemoteVideo` /
   Media3 CENC 播放链路。预热、正式播放和离线缓存共用此模型缓存。
6. **兼容与恢复**：V5 空响应先有限次重读，失败后尝试旧播放路由；只有 V5 和兼容路由
   都为空时才有限次重新注册匿名设备，并从 V5 开始重试。

## 播放器兼容性

本次变化发生在播放模型接口与签名协议。返回的仍是 MP4 视频地址和 CENC AES-CTR
密钥材料，现有播放器、DataSource、分段下载及离线解密继续复用。具体清晰度仍由设备
对 AVC / HEVC 的解码能力决定。

## 已知边界

- HTTP 200 空体也可能表示协议已变更，不能直接认定当前设备配额用尽。
- V5 算法内置在 App 中，平台改协议后需要跟进源码并发布新包。
- 匿名设备仍可能受到平台限制；更换身份不保证解决平台整体收紧访问的问题。

## 手动探针

需要验证真实链路时，可在构建环境显式执行：

```bash
cd kotlin-tv
./gradlew :core:installDist
core/build/install/core/bin/core --stream-only
core/build/install/core/bin/core --stream-only --warm
```

以上探针使用原生算法模式，不经过 Android 签名服务。
