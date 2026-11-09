# 通过局域网打包服务在线更新

电视端不再从 GitHub Releases 取更新，改为直连自建的 Android 打包服务（`192.168.3.77:8000`，与签名服务同一台飞牛 NAS）。版本由打包服务分配，签名由打包节点的签名卷提供，与已装版本证书一致。

## 链路

```
电视 App                       打包服务（huluwa 后端）
  │  GET  /api/public/hongguotv/android/releases/latest      → 最新正式版元数据
  │  POST /api/public/hongguotv/android/tasks/{id}/download-ticket → 短时下载票据（10 分钟）
  │  GET  /api/public/hongguotv/android/downloads/{ticket}    → 已签名 APK
  │  本地 SHA-256 / 包名 / 版本 / minSdk / 签名证书校验 → FileProvider → 系统安装器
```

- 免登录。接口只暴露「查最新版」和「下载安装包」，不开放编译、任务列表、日志或其他项目的管理接口，也不签发后台令牌。
- 短时票据只能兑换红果 TV 自己的产物（`AndroidDownloadService.resolve(ticket, expectedProject)`），拿不到葫芦娃或 TerminalBuddy 的文件。
- 开关：`HONGGUOTV_PUBLIC_ENABLED`（默认 `true`），端点是 `HongguoTvPublicBuildController`。

## 打包服务侧

`GET /releases/latest` 返回统一信封，`data.available` 为 `false` 表示还没有成功的正式构建：

```json
{
  "code": 200,
  "data": {
    "available": true,
    "release": {
      "taskId": 6,
      "versionName": "1.0.6",
      "versionCode": 1006,
      "packageName": "com.huluwa.nativeapp",
      "apkSize": 1947386,
      "sha256": "bdf170fb…",
      "signatureSha256": "6a96d222…",
      "minSdk": 26,
      "publishedAt": "2026-11-09T09:24:29+00:00",
      "commitSubject": "feat: 请求签名改走局域网签名服务"
    }
  }
}
```

版本号规则沿用服务端：`major×1000 + minor×100 + patch` 单调递增，失败和取消不回收编号。电视端只比较 `versionCode`，同时要求 `minSdk <= Build.VERSION.SDK_INT`。

## 电视端实现

| 文件 | 职责 |
|---|---|
| `core/.../AppUpdates.kt` | `AppUpdate` 清单解析、`AppUpdateRepository`（同源校验、票据申请、流式下载与校验） |
| `app/.../AppUpdater.kt` | 前台轮询、空闲判定、下载进度弹窗、`validateApk` 装前校验、拉起系统安装器 |

要点：

- **同源约束**：OkHttp 网络拦截器要求每个请求的 scheme/host/port 都等于服务地址，重定向后的地址同样会被拦下，避免服务端把请求引到任意主机。
- **翻墙镜像已移除**：只访问局域网，不再有 `gh-proxy.com` 等公网代理。
- **票据可重试**：下载失败会重新申请一张票据再试一次，票据过期和偶发断流都能自愈。
- **装前校验**：文件长度 + SHA-256、APK 实际包名/versionCode/versionName/minSdk 与元数据一致、APK 签名与已装版本签名相同；任一不符直接阻止安装并给出可读提示。
- **落盘位置**：`cacheDir/updates/update.apk`，下载先写 `.part`，校验通过才原子替换，失败保留旧文件。
- **节奏**：前台浏览页空闲时检查；首次延迟 8 秒；每 6 小时一次；播放中、弹窗中不打扰；自动检查可在「设置 → 版本与更新」关闭。

## 修改服务地址

默认地址是 `AppUpdate.DEFAULT_SERVICE`（`core/.../AppUpdates.kt`），与签名服务的 `RemoteSigner.DEFAULT_BASE_URL` 并列。换机器时改这一处常量即可，接口前缀 `AppUpdate.PUBLIC_PATH` 不用动。

## 注意事项

- 包名从 `com.hongguotv.nativeapp` 改为 `com.huluwa.nativeapp` 之后，**电视上已安装的旧包无法覆盖升级**（Android 按包名识别应用），需要卸载旧版后手动安装一次新包。此后同一包名的版本可以一直在线更新。
- 打包节点从私有 Gitee 仓库 `main` 分支拉源码。改完代码必须先 `git push gitee main` 再触发构建，否则节点会拉到旧代码。
- 节点的 `packageName`（部署机上的 `config.json`，也是 `configure-hongguotv.py` 的默认值）必须与 `applicationId` 一致，**`agent.py` 会用 config 覆盖镜像 ENV**，只改 Dockerfile 不生效；不一致时节点会报「APK 包名不符合构建参数」。
- 电视与打包服务必须在同一局域网（或电视能路由到 `192.168.3.77`）。服务不可达时面板显示「更新服务不可用（192.168.3.77）」，不影响正常播放。
