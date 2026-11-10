# 竖屏播放页对齐葫芦娃红果播放页

## 目标

手机竖屏播放页的 UI 元素、点击交互、弹幕位置、底部元素全部参考葫芦娃 App 的红果播放页
（`workspace/huluwa/android/app/src/main/java/com/huluwa/android/ui/HongguoPlayer.kt`）。
横竖屏严格按「启动方向」设置决定：

- **强制竖屏**：完全按葫芦娃红果播放页的交互（竖屏 UI、轻点暂停、上下滑换集、底部选集行）；
  点全屏会转横屏，换成手机自己的横屏全屏皮肤，**不是**电视播放器。
- **跟随系统**：竖着拿是手机播放页；点全屏转横屏后同样是手机横屏全屏皮肤。
- **强制横屏**：完全走 TV 播放器模式，不做任何手机化处理（手机全屏按钮不会出现在这里）。

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

**全屏（横屏）**：手机横屏全屏皮肤（葫芦娃 fullscreen 布局），由 [PhonePlayer] 同一个类
按 `fullscreen` 标记渲染，与电视播放器严格区分：

- 顶部标题变成「剧名 · 第N集」，返回键即「退出全屏」
- 底部不再有剧名行与简介，换成一行紧凑控制条：
  `▶/⏸  00:00 / 00:00 ……… 选集 · 全N集  ⛶`
- 进度条仍是白色细线
- 轻点画面显隐控件（与葫芦娃 fullscreen 一致）
- 顶栏与控制条按左右安全区（刘海 / 挖孔）让位

## 方向规则实现

- `PhoneUi.shouldUsePlayer(activity)` 直接等于 `shouldUse(activity)`：
  - 强制竖屏 → true（手机播放页）
  - 强制横屏 → false（TV 播放页）
  - 跟随系统 → 按当前实际方向
- `PhoneUi.shouldUsePhoneFullscreen(activity)`：手持设备且不是强制横屏 → 可以进手机横屏全屏皮肤。
- 全屏会话由 `MainActivity.phoneFullscreenPlayback` 记着，`phonePlayerLayout()` =
  `phoneLayout() || (全屏会话 && shouldUsePhoneFullscreen)`，播放页的渲染、刷新、选集、返回
  全部改用它判断，所以锁成横屏后不会翻成 TV 播放器。
- `PhonePlayer.enterFullscreenFromButton()`：标记置位 → 锁横屏 → 方向回调里按标记重建外壳；
  设备本来就横着（不触发回调）时主动 `rebuildPhonePlayerForLayout()` 补一次。
- `PhonePlayer.exitFullscreenSkin()` / 返回键：清标记、交还方向（跟随系统先锁竖屏再交还传感器，
  否则设备横着时系统不会自己转回来），再按竖屏皮肤重建。
- 旋转重建走 `onConfigurationChanged` 的 `player` 分支：会话期间保持横屏皮肤；
  真的转回竖屏则结束会话。

## 选集弹层（PhoneEpisodeSheet）

- 底部升起的 `#171717` 圆角面板，顶部两角 22dp，背后 78% 黑遮罩，点遮罩关闭
- 每行 5 个数字方块，当前集 `#E44B40` 红底白字，其余 `#292929` 深灰
- 打开时自动滚到当前集所在行（上方留两行上下文），高度封顶屏幕 70%
- 仅在手机播放页使用；TV 模式仍走原 `EpisodePanel`

## 已知取舍

- 手机竖屏播放页顶部标题只有一行，简介放到底部（与葫芦娃一致），
  原实现的「标题 + 集号」两行顶栏已去掉。
- 竖屏下控件常驻显示，轻点画面只切播放 / 暂停；横屏全屏才会淡出（葫芦娃同款）。
- 底部没有放「弹幕开关」按钮（葫芦娃也没有），改为放进「更多」菜单里。
