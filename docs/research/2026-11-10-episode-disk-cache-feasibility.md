# 播放页相邻集整集落盘缓存（静默预下载）可行性调研

- 日期：2026-11-10
- 版本基线：0.30.10（kotlin-tv，Media3 1.8.0 / OkHttp 4.12.0 / minSdk 26）
- 结论摘要：**可行。工作区已有一份未提交的半成品（约 80% 完成），主要缺口是「接线」「窗口从 3 集扩到 5 集」「过期策略」和「测试」。**

---

## 1. 需求拆解

| 需求原话 | 落地含义 |
|---|---|
| 下一集下载到本地 | 整集密文落盘，播放时本地优先 |
| 缓存池最多 5 条 | 上一集 + 当前集 + 下三集，共 5 个键 |
| 播完就删最早一条 | 窗口滑动时把滚出窗口的条目连同元数据删除 |
| 参考第三方参考实现的离线下载 | 分块 Range 断点续传、失败换 CDN、整集完整性校验 |
| 全程用户不可见、后台静默 | 不新增任何下载 UI / 进度 / 管理页 |
| 下一集快速衔接 | 目标集在本地时，起播不再依赖网络往返 |

---

## 2. 现有基础（决定插入点）

| 事实 | 位置 | 影响 |
|---|---|---|
| 播放字节由 `RemoteVideo` 提供，按绝对偏移分块解密 | `core/.../RemoteVideo.kt:15-213` | 落盘缓存只需在它前面加一层「本地命中」 |
| Media3 通过 `VideoDataSource` 桥接 `RemoteVideo.read()` | `app/.../VideoDataSource.kt:11-33` | **最小插入点就在这里**：读之前先查磁盘 |
| 相邻集地址、解密索引、开头分块已在内存池里复用 | `core/.../EpisodePlaybackCache.kt:13-163` | 落盘缓存可以复用同一套取址与重试，不重复造轮子 |
| 相邻集预加载由 Media3 `DefaultPreloadManager` 负责，只缓冲 3-5 秒 | `app/.../EpisodePlaybackSession.kt:42-138` | 与「整集落盘」是两条独立预取通道，需协调带宽 |
| 播放窗口当前只有 `index-1 .. index+2`（3 集） | `app/.../MainActivity.kt:3486-3495` | 要扩到 5 集 |
| 相邻集预取只处理前后各一集 | `app/.../MainActivity.kt:3548-3555` | 要扩到下一集、下二、下三 |
| 现有缓存 TTL 10 分钟、退出播放页即 `clear()` | `core/.../EpisodePlaybackCache.kt:36-40`、`MainActivity.kt:4679` | 落盘缓存要跨会话保留才有意义 |
| 取流模型里**没有解析文件体积** | `core/.../ContentRepository.kt:531-556` | 可选补 `video_meta.size`，否则只能靠首个 Range 响应得知 `total` |
| 设置页「清理缓存」遍历 `cacheDir` / `externalCacheDir` | `MainActivity.kt:2306`、`2315`、`5144` | 缓存放 `cacheDir` 可被系统与用户清理，符合「不可见」 |

### 2.1 已有的未提交实现

工作区存在以下改动（`git status` 可见，均未提交）：

| 文件 | 状态 | 内容 |
|---|---|---|
| `core/.../EpisodeDiskCache.kt` | **新增 415 行** | 整集落盘池：5 条窗口、区间并集、断点续传、元数据原子落盘、启动清扫 |
| `core/.../RemoteVideo.kt` | 改 | 新增 `preparedSnapshot()`，供落盘缓存拿明文头 + 样本索引 |
| `core/.../ContentRepository.kt` | 改 | `StreamInfo` 增加 `idHint` / `qualityNumber` |
| `core/.../EpisodePlaybackCache.kt` | 改 | 取址完成后回填 `idHint` / `qualityNumber` |
| `app/.../VideoDataSource.kt` | 改 | 读之前先查磁盘，未命中回落在线 |
| `app/.../EpisodePlaybackSession.kt` | 改 | 构造参数接受 `EpisodeDiskCache`，传给 `VideoDataSource` |

**这份实现的设计要点：**
- 盘上存的是与在线字节完全一致的**密文**，不额外解密成明文（`EpisodeDiskCache.kt:15-21`）。相比第三方参考实现「下载密文 → 再解密出第二份明文」的 2 倍峰值空间，这里只占 1 倍。
- 样本索引、明文头、内容密钥随元数据单独存 JSON；播放端按需通过 `MediaCrypto.decrypt` 解密（`EpisodeDiskCache.kt:127-165`）。
- 区间用扁平数组记录，支持部分下载后续传（`EpisodeDiskCache.kt:53-91`）。
- 池上限 500 MB、磁盘可用空间下限 1 GB、低内存时暂停（`EpisodeDiskCache.kt:49-50`、`205-223`）。

**但它还没接线**：`MainActivity` 里没有任何 `EpisodeDiskCache` 的实例化与调用，`EpisodePlaybackSession(this)` 仍是单参数（`MainActivity.kt:3335`），所以 `disk` 恒为 `null`，功能实际关闭。

---

## 3. 与第三方参考实现方案的对比

| 维度 | 第三方参考实现的离线下载 | 本方案（EpisodeDiskCache） |
|---|---|---|
| 落盘内容 | 密文 `.part` + 解密后明文 `.mp4` | 只存密文，播放时解密 |
| 峰值空间 | 约 2 倍单集体积 | 约 1 倍 |
| 断点续传 | `.part` 文件长度即进度，4 MB 分块 | 区间并集，4 MB 分块 |
| 失败处理 | 单块最多 6 次、主备 CDN 轮换 | 整条重试 10 秒退避 + 地址过期整条作废 |
| 完整性校验 | `Content-Range` 三段比对 | 同 |
| 用户可见 | 有下载列表、进度、管理页 | **无任何 UI** |
| 生命周期 | 应用级，退出页面继续下 | 播放页级，随窗口滑动 |

结论：**不必照搬第三方参考实现的「下载 → 解密 → 存明文」两段式**。本项目的播放链路本来就是「读密文块 → 即时解密」，落盘密文后可以直接复用同一条解密路径，省一份空间、省一次全文件重写。

---

## 4. 缺口清单（从 80% 到可用）

### 4.1 必须做

1. **接线**：`MainActivity` 创建 `EpisodeDiskCache`（目录建议 `File(cacheDir, "episode-disk")`），传给 `EpisodePlaybackSession(this, disk)`（`MainActivity.kt:3335`）。
2. **窗口扩到 5 集**：
   - `retainPlaybackWindow()`（`MainActivity.kt:3486`）改为 `episodeIndex-1 .. episodeIndex+3`；
   - `maybePrefetch()`（`MainActivity.kt:3548`）的 neighbors 扩到 `+1 / +2 / +3 / -1`；
   - `prefetchDetailWindow()`（`MainActivity.kt:2974`）同步扩窗，否则详情页进播放时前 3 集没有命中。
3. **调度落盘**：在 `maybePrefetch()` 里调用 `disk.schedule(ordered, quality, retained5, quality)`，优先级建议：
   `下一集 > 下二集 > 下三集 > 上一集`，**当前集不主动整集下载**（播放本身走在线，避免与解码抢磁盘 IO，也避免与在线回退重复拉取同一段）。
4. **退出播放页不删**：`releasePlayer()` 的 `playbackCache.clear()`（`MainActivity.kt:4679`）只清内存；落盘池保留，下一次进同一部剧可直接命中。
5. **单元测试**（当前一条都没有）：`EpisodeDiskCacheTest` 覆盖区间合并 / `covered` / 窗口裁剪 / 元数据往返 / 过期作废，用 MockWebServer 模拟 Range。

### 4.2 建议做

6. **放宽过期作废**：`meta()` 目前只要 URL 的 `expiresAt` 到了就把条目标记为 `done=false`（`EpisodeDiskCache.kt:169-178`）。但本地密文 + 已落盘的密钥不依赖 URL，只有**续传**才需要有效地址。建议：完整条目（`done=true`）不因 URL 过期作废，只在需要补下载时重新取址。
7. **解析单集体积**：`streamFromModel` 补 `video_meta.size`（第三方参考实现的后端同样字段，见 `hongguo/HongguoService.java:302`），用于写入前的空间预检。
8. **带宽协调**：Media3 预加载（3-5 秒）与整集落盘同时跑会争抢带宽。建议整集下载只在「当前集缓冲 ≥ 3 秒 + Media3 预加载已就绪」后启动，且单线程顺序下载。
9. **隐私说明更新**：`kotlin-tv/README.md:439` 现在写的是「媒体只在内存中处理，不下载整部剧到存储」，加落盘后必须改成「仅在应用私有缓存目录保留至多 5 集，可随清理缓存删除，不导出、不长期保存」。

### 4.3 需要拍板

- **设置页「清理缓存」显示的大小会从几十 MB 变成几百 MB**。是接受这个数字变化，还是把 `episode-disk` 目录排除在统计外（那样用户就完全看不到）？
- **缓存目录用 `cacheDir` 还是 `filesDir`**：`cacheDir` 会被系统在空间不足时自动清理，也计入「清理缓存」；`filesDir` 更稳但需要自己做淘汰。建议 `cacheDir`。

---

## 5. 收益与成本

### 收益

| 场景 | 现状 | 落盘后 |
|---|---|---|
| 自动连播下一集 | Media3 已预加载 3-5 秒，通常秒开 | 整集本地，起播无网络等待 |
| 手动回看上一集 | 内存池命中（同一播放会话内） | 跨会话也能秒开 |
| 网络抖动 / 短暂断网 | 缓冲耗尽即卡 | 已下载区间可继续播 |
| 快速连按下一集 | 每次一次取址 + 首块网络 | 取址可省，数据本地 |

### 成本

| 项 | 量级 | 说明 |
|---|---|---|
| 磁盘占用 | 5 集 × 20-80 MB ≈ 100-400 MB | 短剧单集通常 1-3 分钟 |
| 额外流量 | 0 | 预取本来就要拉这些数据，只是多写一次盘 |
| 写盘 | 每集一次整文件写 | 电视 eMMC 有磨损，但 500 MB 级可接受 |
| 磁盘 IO 竞争 | 中低 | 与解码器共用同一闪存；低端电视需实测 |
| 内存 | 可忽略 | 元数据每条约几 KB |

---

## 6. 风险

1. **低端电视 IO 抖动**：本地读 + 解密若比在线分块慢，反而延长起播。需在目标机型上对比 `Playback ready` / `Playback first frame` 埋点（`MainActivity.kt:3354`、`3420`）。
2. **地址过期后的续传**：10 分钟 TTL 内没下完的条目会整条重来，浪费流量。可接受，但要确认日志里不会频繁出现。
3. **清晰度切换**：池以 `(集 ID, 清晰度)` 为键，切档会各存一份；5 条上限会互相挤占。建议切档时清掉旧档条目。
4. **存储权限**：全部落在应用私有 `cacheDir`，不需要任何新增权限。
5. **合规表述**：项目明确「不提供、不存储、不传播视频内容」，落盘缓存必须限定为短期播放加速、应用私有、可清理，不能做成可导出的离线库（与第三方参考实现的离线管理页不同）。

---

## 7. 建议实施顺序

1. 接线 + 窗口扩到 5 集 + 调度（可见效果，约半天）
2. 补 `EpisodeDiskCacheTest`（半天）
3. 目标机型实测起播耗时与磁盘占用（半天）
4. 过期策略与体积解析优化（半天）
5. 隐私说明与设置页文案调整（1 小时）
