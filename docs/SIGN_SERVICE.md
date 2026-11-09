# 签名服务接入

更新日期：2026-11-09。

红果官方接口（搜索、联想、分类筛选、播放取址、弹幕、账号）都要求请求携带签名头。本版起电视端**不再内置签名算法**，统一通过 FRP 公网入口调用自建签名服务。服务使用 openhongguo SDK，部署容器名为 `hongguo-signer-portable`，与葫芦娃项目共用同一套（`huluwa/hg/portable-signer`）。

## 默认地址

```text
http://123.57.172.104:8901
```

地址定义在 `core` 的 `RemoteSigner.DEFAULT_BASE_URL`。签名服务不可用时，搜索会回退到官网 `hongguoduanju.com` 的公开搜索接口，播放取址会报错（「签名服务返回 HTTP …」「未配置签名服务地址」）。剧集详情与分集读取官网公开数据：普通短剧不额外请求多季补全；有明确季号或已知其它季的系列剧，补全最多等待 5 秒，超时后保留已取得的分集与季列表。签名请求也随所属页面或补全任务一起取消，避免离开页面后继续占用网络。

## FRP 映射

使用飞牛上已有的 `frpc` 容器，持久配置为宿主机 `/vol1/1000/docker/frpc/frpc.ini`（容器内 `/etc/frp/frpc.ini`）。服务端地址为 `123.57.172.104:7000`，沿用已有认证配置。

```ini
[红果sign服务]
type = tcp
local_ip = 192.168.3.77
local_port = 8901
remote_port = 8901
```

公网入口转发到飞牛 `192.168.3.77:8901` 的 openhongguo 容器；原来指向 `192.168.3.83:8901` 的目标已改为 `.77`。配置变更使用 `frpc reload -c /etc/frp/frpc.ini` 热重载。

客户端默认使用公网入口，因此 4G/5G 下不再要求接入家中局域网；本地部署仍可显式指定 `http://192.168.3.77:8901`。已安装的旧 APK 需更新到包含公网地址的版本，地址才会切换。

## 接口

| 接口 | 用途 |
| --- | --- |
| `GET /info` | 返回已注册的匿名设备档案：`deviceProfile.params`（`device_id` / `iid` / 版本号 / 机型等）与 `user_agent` |
| `POST /sign` | 请求体 `{"url": "完整 URL"}`，返回 `X-Gorgon`、`X-Khronos`、`X-Argus`、`X-Ladon`、`X-Medusa`、`X-Helios` |
| `POST /recover` | 设备身份被平台拒绝时，换一台匿名设备 |
| `GET /health` | 健康检查，会真实签一次名 |

`/sign` 只覆盖 URL（查询串 + 主机），不覆盖 POST body。体签名由红果服务端按 URL 校验，实测 POST 播放接口可用。

## 客户端约定

- **身份以档案为准**：签名服务会拒绝与档案不符的 `device_id` / `iid`。因此 `RemoteSigner.sign()` 一律用 `/info` 下发的参数拼查询串：版本号、机型、渠道等身份字段由服务决定；`query`、`offset`、`video_id` 等业务参数由客户端提供。
- **UA 同步**：受保护接口的 `User-Agent` 必须与档案声明的一致（例如 `com.phoenix.read/73932 (...)`），`RemoteSigner.profile().userAgent` 提供该值。
- **空响应含义**：平台对未注册或已被限制的设备只回 `HTTP 200` + 0 字节。播放链路遇到空体会调用 `/recover` 换设备再试；搜索接口空响应则回退到官网搜索。
- **取址接口的配额差异（2026-11-09 实测）**：单集接口 `GET /reading/bookapi/video_tab/video_model/v`（参数 `video_id` / `source` / `need_all_video_definition`）对同一匿名设备可连续请求，实测 12/12 全部返回模型；多集接口 `video_tab/multi_video_model/v1/` 对匿名设备近乎一次性配额（第 1 次成功后持续空响应，换主机也只能再成功一次），首主机空时换 `api5-normal-sinfonlinea` 还能再取到一次，说明配额按「设备 + 主机」计。
  因此单集取址优先走前者，多集接口仅作兜底；批量预热也只走单集接口，不再消耗多集配额。改动前曾因把多集接口当主路由，导致界面长时间停在「正在获取播放地址…」。
- **设备身份不做本地持久化**：由签名服务持有；电视端每次启动重新取一次档案并缓存，`recover` 后缓存失效。

## 验证方法

`core` 里有联网探针，可在打包机上直接跑真实链路：

```bash
cd kotlin-tv
./gradlew :core:installDist
core/build/install/core/bin/core --signer=http://123.57.172.104:8901          # 搜索 / 首页 / 详情 / 播放
core/build/install/core/bin/core --stream-only --signer=http://...         # 只验证详情与播放
core/build/install/core/bin/core --signer=http://... --comic --warm       # 漫剧 + 批量预热
```

探针会先打印 `signer=<地址>; device=<device_id>`，用于确认取到的是签名服务的设备档案。
