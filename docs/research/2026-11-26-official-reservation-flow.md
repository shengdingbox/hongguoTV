# 红果官方预约详情流程对照

依据工作区「红果官方源码」中的红果短剧 7.4.5.32 APK 反编译结果，整理本次预约详情改动的协议依据。

## 官方代码依据

- `output/jadx/dex18/sources/com/dragon/read/component/shortvideo/impl/seriesdetail/SubscribeDetailFragment.java`：预约使用独立详情页面，读取 `onlineSubscribed` 更新预约按钮。
- 同目录 `BaseSeriesDetailFragment.java` 的 `loadData` 和 `li`：使用 `GetVideoDetailRequest`，保留 `series_id` 与 `video_content_type` 导航参数对应的 `content_type`；业务参数为 `video_id_type=1`、`source=FromDetailPage`，主加载请求使用 `detail_page_version=1`。读取到可用预取详情时优先消费预取数据。
- `output/jadx/dex15/sources/seriessdk/com/dragon/read/saas/rpc/rpc/f.java`：`getVideoDetailRxJava` 对应 `POST /novel/player/video_detail/v1/`，返回 `data.video_data`。
- `output/jadx/dex04/sources/de5/t0.java` 的 `k0 → o2`：最终调用 `getVideoDetailRxJava`，确认页面请求落到单部详情接口。
- `output/jadx/dex15/sources/seriessdk/com/dragon/read/saas/rpc/model/VideoContentType.java`：正式短剧为 `7`，预约短剧 `UnrealShortPlay` 为 `1002`；正式漫剧为 `1004`，预约漫剧 `UnrealMotionComic` 为 `1007`。
- 同一模型目录的 `VideoDetailSource.java`：`FromDetailPage=5`。
- 同一模型目录的 `VideoDetailInfo.java`：携带 `content_type`、`season_index`、`online_time`、`online_subscribed`、`video_list`。`SaaSVideoDetailData.java` 将预告素材单独放在 `preview_material_list`，不应将预告素材当成正式分集。
- `output/jadx/dex09/sources/com/dragon/read/rpc/model/SubscribeTypeEnum.java`：预约列表的 `item_type=1/3` 为短剧及短剧查询，`14` 为漫剧。
- 同目录 `SubscribeItem.java`：预约条目携带 `item_id`、`item_type`、`is_online`、`subscribe_data`、`detail_schema` 和排期等信息。

## 当前客户端对应处理

1. 从预约列表打开或通过季标签切换到待上线作品，先使用已有卡片和系列快照显示封面、简介、排期及预约按钮。
2. 后台按官方单部详情接口和作品类型刷新主信息。进入页面不再等待完整辅助详情、搜索和标题匹配。
3. 主信息返回后先更新详情，再继续异步补系列等辅助信息；离开页面或切季会取消过时请求。短时间重复进入复用主详情缓存，手动检查预约始终请求最新信息。
4. 保留官方作品类型和季序到本机收藏、日历及系列快照。没有类型的旧缓存不猜测类型，省略可选的 `content_type`，由服务端按 ID 识别；缺少官方季序的旧条目继续按标题排序。
5. 未上线内容仍显示预约；后台查到分集后继续留在预约分类，只有用户进入详情看到实际分集才转入收藏。

本次对齐的是预约详情的导航、请求与展示流程。本机预约继续沿用现有保存方式；官方账号预约增删属于另外的账号写入接口，不在本次改动范围。
