# 请求签名与匿名设备

更新日期：2026-11-09。

红果官方接口（搜索、联想、分类筛选、播放取址、弹幕、账号）都要求请求携带签名头，
且只答**平台注册过的匿名设备**。本版起这两件事都在 App 本机完成，**不再依赖任何签名服务**。

## 组件

| 文件 | 作用 |
| --- | --- |
| `SignPrimitives.kt` | SM3、GF(2^8)、protobuf 编码、IV 派生、AES S 盒 |
| `SignAlgorithms.kt` | X-Gorgon / X-Ladon / X-Argus / X-Medusa / X-Helios 与 f13 hash 分支 |
| `AesV3.kt` | 魔改 AES-V3（X-Medusa 封装用） |
| `DeviceRegister.kt` | 匿名设备注册（ttEncrypt = GZIP + AES-CBC） |
| `RemoteSigner.kt` | 对外接口：`profile()` / `sign()` / `recover()` |
| `SharedPrefsSignerStore.kt`（app） | 设备档案持久化到应用私有目录 |

算法移植自 [openhongguo](https://github.com/beilunyang/openhongguo) 的
`internal/protocol/sign/`，用其 `sign_test.go` 的官方测试向量逐项校验
（`LocalSignerTest.kt`）。SM3 另外用 `"abc"` 的标准摘要校验，AES S 盒用 FIPS-197 校验。

## 工作方式

1. **注册**：首次播放/搜索时向 `log5-applog-lf.fqnovel.com/service/2/device_register/`
   发一次匿名注册请求，拿到 `device_id` / `install_id`。平台对未注册设备只回
   `HTTP 200 + 0 字节`，因此这一步不可省。
2. **持久化**：档案写进 `SharedPreferences("native-signer-v1")`，冷启动直接复用，
   不必每次开机重新注册。
3. **签名**：每次请求本地算出六个签名头；`_rticket` 从当前毫秒开始递增，
   直到 f13 的 hash 分支落在可直接计算的一支上（分支 0 / 1 / 2 均支持）。
4. **恢复**：持续空响应说明当前身份被限制，`recover()` 重新注册一台匿名设备。

所有模块（内容、播放、弹幕、账号）共用同一个 `RemoteSigner` 实例——平台按
`device_id + iid` 放行，各模块各注册一台会被当成不同设备。

## 与旧版本的区别

旧版（0.30.x 早期）通过 FRP 公网入口调用自建签名服务
（`http://123.57.172.104:8901`），签名与设备身份都由服务端决定。这样做的代价是：

- 签名服务是单点：FRP 断线或容器重启，App 全部受保护接口失效；
- 每次请求多一次 `/sign` 往返（实测约 110ms）；
- 4G/5G 下必须能访问公网入口。

现在这些都不需要了。签名服务仓库与容器仍可保留给其他项目使用，
但红果TV 不再依赖它；`--signer=` 探针参数仅为兼容旧命令行保留，传入不生效。

## 已知边界

- **接口配额与签名方式无关**：`multi_video_model` 对匿名设备近乎一次性配额
  （见 `docs/SIGN_SERVICE.md` 旧记录与 `ContentRepository` 注释）。本版取址主路由是
  `GET video_tab/video_model/v`，同一设备可重复请求；多集接口仅作兜底。
- **平台改算法时需要发版**：内置算法不像服务端那样可以热更新。若红果升级风控，
  需要跟进 openhongguo 的上游实现后重新发版。
- **匿名设备仍可能被限制**：注册是无成本的匿名流程，平台若整体收紧匿名访问，
  本地注册也绕不过去（openhongguo README 同样标注了该限制）。

## 验证方法

`core` 里有联网探针，可在打包机上直接跑真实链路（不需要任何签名服务）：

```bash
cd kotlin-tv
./gradlew :core:installDist
core/build/install/core/bin/core --stream-only          # 只验证详情与播放
core/build/install/core/bin/core --stream-only --warm   # 附带批量预热
```

探针会先打印 `本机注册设备 device=<device_id>`，用于确认注册成功。
