# 本地取址接口对比

日期：2026-11-24。

使用服务中现有的已注册匿名设备，按当前 App 的本地签名代码与参数请求。
探针没有调用 unidbg 或 frida 签名服务；没有替换、重新注册设备身份。
样本是两个普通短剧分集，结果代表此次设备身份和网络环境。

## 接口结果

| 签名与接口 | 主机 | 两个分集的结果 | 接口响应耗时 |
| --- | --- | --- | --- |
| 本地 V5 POST `/novel/player/multi_video_model/v1/` | `api5-normal-sinfonlineb`、`api5-normal-hl`、`api5-normal-lf` | 全部空响应 | 72～556 ms |
| 本地 V5 GET `/reading/bookapi/video_tab/multi_video_model/v1/` | `api5-normal-sinfonlineb.fqnovel.com` | 均返回 1080P 模型 | 221 / 204 ms |
| 本地六头 GET `/reading/bookapi/video_tab/multi_video_model/v1/` | `api5-normal-sinfonlineb.fqnovel.com` | 均返回 1080P 模型 | 224 / 188 ms |
| 本地六头 GET `/reading/bookapi/video_tab/multi_video_model/v1/` | `reading.snssdk.com` | 均返回 1080P 模型 | 206 / 186 ms |
| 本地六头 GET `/reading/bookapi/video_tab/video_model/v` | 上述两个 GET 主机 | 全部空响应 | 63～127 ms |
| 本地六头 POST `/novel/player/video_model/v1/` | `api5-normal-sinfonlinea.fqnovel.com` | 全部空响应 | 84 / 70 ms |
| 本地六头 POST `/novel/player/multi_video_model/{v1,preload/v1,default/v1,detail/v1}/` | `api5-normal-sinfonlinea.fqnovel.com` | 全部空响应 | 64～88 ms |

模型可用性通过 App 现有解析逻辑判断，包含目标分集匹配、清晰度和兼容片源选择。
接口响应耗时与下文完整取址耗时的计时范围不同，不能直接作为签名计算耗时。

## 取址顺序

本地普通短剧默认先请求 V5 多集 GET，再使用本地六头多集 GET 的主机回退，
之后才尝试 V5 POST 和旧兼容路由。成功接口记忆继续生效。
合集保留原有分组与取址逻辑。

修改后直接运行 App 的 `ContentRepository.stream`，三次均强制刷新模型，结果如下：

| 次数 | 分集 | 网络请求数 | 模型清晰度 | 完整取址耗时（ms） |
| --- | --- | --- | --- | --- |
| 1 | 7675424396831378456 | 1 | 1080P | 511 |
| 2 | 7699712716172184600 | 1 | 1080P | 166 |
| 3 | 7675424396831378456 | 1 | 1080P | 179 |

三次实际请求都是：

`GET https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/`

均未请求前置 POST 接口，也未依赖模型缓存命中；以上时延不代表所有设备和网络环境。
