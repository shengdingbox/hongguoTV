# 竖屏播放页对齐葫芦娃红果播放页

## 目标

手机竖屏播放页的 UI 元素、点击交互、弹幕位置、底部元素全部参考葫芦娃 App 的红果播放页
（`workspace/huluwa/android/app/src/main/java/com/huluwa/android/ui/HongguoPlayer.kt`）。
横竖屏严格按「启动方向」设置决定：

- **强制竖屏**：完全按葫芦娃红果播放页的交互（竖屏 UI、轻点暂停、上下滑换集、底部选集行）。
- **强制横屏 / 跟随系统且横过来**：完全走 TV 播放器模式，不做任何手机化处理。
- **跟随系统**：竖着拿是手机播放页；点全屏转横屏后自动切成 TV 播放器。

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `PhonePlayer.kt` | 按葫芦娃红果播放页重做竖屏播放页外壳 |
| `PhoneEpisodeSheet.kt` | 新增：底部数字宫格选集弹层（葫芦娃 `HongguoEpisodeSheet`） |
| `PlaybackSeekBar.kt` | 新增轨道颜色 / 粗细 / 圆点半径参数，手机页用白色细线 |
| `PhoneUi.kt` | `shouldUsePlayer` 去掉「全屏仍是手机界面」特例，严格跟随启动方向 |
| `MainActivity.kt` | 方向/全屏/选集/更多菜单/桥接方法配套 |
| `res/drawable/ic_*.xml` | 新增 chevron / 键盘上箭头 / 收藏心 / 倍速时钟图标 |

## 布局对应关系（葫芦娃 → 本项目）

**顶部状态栏**（`HongguoPlayer` 顶部 Row）：

- 返回键（ChevronLeft）→ `ic_back`
- 标题：竖屏「第N集」，全屏「剧名 · 第N集」→ `titleText`
- 倍速：时钟图标 + `1.0x` 文字，点开下拉倍速 → `ic_speed` + `speedLabel` + `showSpeedPicker`
- 更多：竖点菜单（选集 / 选择分集离线 / 重新加载）→ `ic_more` + `showPhoneMoreMenu`

**点击交互**：

- 竖屏轻点画面 = 播放 / 暂停（暂停时画面正中浮一个半透明大播放键）
- 上下滑动 / 快速轻扫 = 上一集 / 下一集（沿用原实现）
- 全屏（真正横过来）轻点 = 显隐控件

**弹幕位置**：

- 弹幕层上沿 = 标题栏高度（52dp）+ 状态栏高度，即「标题栏下方」开始滚动，
  不再从屏幕最顶端开始、被返回键与倍速按钮压住。

**底部元素**（竖屏）：

1. 剧名 + `›`（点击打开选集）— 葫芦娃 `HongguoPlayerInfo` 第一行
2. `第N集 | 简介`（`展开 / 收起` 最多 4 行）— 第二行
3. 白色细线进度条 + 小圆点 — 葫芦娃 `HongguoSeekBar`
4. `[选集 · 全N集        ↑]  ♡  ⛶` 一整行 — 葫芦娃的 `Surface(选集)` + 收藏 + 全屏

**全屏（横屏）**：本项目的 TV 播放器接管（`phoneLayout()` 为 false），
即电视版 HUD 控制条，不做任何手机化元素。

## 方向规则实现

- `PhoneUi.shouldUsePlayer(activity)` 直接等于 `shouldUse(activity)`：
  - 强制竖屏 → true（手机播放页）
  - 强制横屏 → false（TV 播放页）
  - 跟随系统 → 按当前实际方向
- `PhonePlayer.toggleFullscreen()`：
  - 强制竖屏：`enterFullscreenPhone()` 只隐藏系统栏、不转屏，界面保持竖屏那套
  - 跟随系统 / 强制横屏：锁横屏 → `onConfigurationChanged` 重建 → `phoneLayout()` 变 false → TV 播放器
- `onConfigurationChanged` 的 `player` 分支不再保留「全屏播放器仍是手机界面」的特例，
  直接 `playEpisode(...)` 按新方向重挂。

## 选集弹层（PhoneEpisodeSheet）

- 底部升起的 `#171717` 圆角面板，顶部两角 22dp，背后 78% 黑遮罩，点遮罩关闭
- 每行 5 个数字方块，当前集 `#E44B40` 红底白字，其余 `#292929` 深灰
- 打开时自动滚到当前集所在行（上方留两行上下文），高度封顶屏幕 70%
- 仅在手机竖屏播放页使用；TV 模式仍走原 `EpisodePanel`

## 已知取舍

- 手机竖屏播放页顶部标题只有一行，简介放到底部（与葫芦娃一致），
  原实现的「标题 + 集号」两行顶栏已去掉。
- 竖屏下控件常驻显示，轻点画面只切播放 / 暂停；不再有 3 秒自动淡出
  （全屏才会淡出，但全屏已经交给 TV 播放器）。
- 底部没有放「弹幕开关」按钮（葫芦娃也没有），改为放进「更多」菜单里。
