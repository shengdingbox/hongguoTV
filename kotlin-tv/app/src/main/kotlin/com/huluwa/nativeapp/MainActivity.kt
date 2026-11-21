// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import androidx.core.graphics.drawable.DrawableCompat
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.*
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.Locale
import java.util.concurrent.Executors

private const val TYPE_CARD=0
private const val TYPE_FOOTER=1
private const val TYPE_HEADER=2
private const val FOOTER_NONE=0
private const val FOOTER_LOADING=1
private const val FOOTER_END=2
private const val FOOTER_ERROR=3
private const val FOOTER_MORE_HOT=4
// 上新区的时间窗，id 直接对应服务端 online_time 维度的选项；循环切换只在这四个里转
private val NEW_RELEASE_WINDOWS=listOf("days_7" to "近7天","days_14" to "近14天","days_30" to "近30天","days_90" to "近90天")
/** 退出全屏后把方向交还传感器前的等待（毫秒）：给转屏动画留时间，太快会被新方向覆盖。 */
private const val ORIENTATION_RESTORE_DELAY_MS=300L

/**
 * 切换系统风格 / 界面缩放必须 recreate() 让新主题和新密度生效，而重建后的 Activity
 * 是一份全新实例，页面状态全部归零（会退回首页）。这里在重建前记下当前页签和焦点所在行，
 * 由新实例在 onCreate 里取走一次；正常冷启动不会命中，不影响桌面图标进来的行为。
 */
private object PageRestore {
    var tab=-1
    var focusKey: String?=null
    /** 手机设置页里切了界面缩放：重建后要回到手机设置页，而不是首页。 */
    var phoneSettings=false
    // 冷启动主页上的操作延后到记录就绪后执行，设置加载引起 recreate 时继续保留。
    var startupTab: Int?=null
    var startupType: ContentType?=null
    var startupHomeAction: String?=null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    /** 界面整体缩放：包一层调过密度的 Context，dp/sp 等比生效（见 UiScale 注释）。 */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(UiScale.wrap(base))
    }
    private val bg by lazy { TvStyle.background }; private val surface by lazy { TvStyle.surface }
    private val accent by lazy { TvStyle.accent }; private val white by lazy { TvStyle.text }; private val muted by lazy { TvStyle.muted }
    private val gold by lazy { TvStyle.gold }
    private var activityInitialized=false
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    /** 焦点触发封面重载的防抖 tag 键（View.setTag 用），避免使用 R.id 与 Glide 冲突。 */
    private val focusReloadTagKey = 0x7f0e0001
    // 页面请求独占线程，不排在随机推荐补池、云同步等后台任务之后。
    private val pageIo=Executors.newFixedThreadPool(1)
    /** 前台心跳：首次立即发送，之后每 60 秒一次；onPause 停止。 */
    private val heartbeatIntervalMs=60_000L
    private val heartbeatRunnable=object: Runnable {
        override fun run() {
            if(!foreground || isFinishing || isDestroyed) return
            InstallStatsPing.heartbeat(applicationContext)
            main.postDelayed(this, heartbeatIntervalMs)
        }
    }
    // 联想独立于云同步等后台任务；新输入会取消旧请求并清掉队列。
    private val suggestIo=Executors.newFixedThreadPool(1)
    private var suggestJob: java.util.concurrent.Future<*>?=null
    private var suggestScope: RequestScope?=null
    private var searchArtworkJob: java.util.concurrent.Future<*>?=null
    private var searchArtworkScope: RequestScope?=null
    // 默认使用本地算法，复用本机持久化的匿名设备档案；也可在设置中选择远程算法。
    // 注意：这些必须用 by lazy。Activity 的字段初始化器在构造阶段执行，
    // 那时 attachBaseContext 还没跑，this 作为 Context 是空的，
    // 直接 getSharedPreferences 会 NPE 导致启动即闪退。
    private val signerStore by lazy { SharedPrefsSignerStore(this) }
    /** 播放页、首页预览与离线下载共用同一份算法策略与设备档案。 */
    private val playbackAlgorithms by lazy { PlaybackAlgorithms.shared(this, signerStore) }
    private val playbackAlgorithmListener: (PlaybackAlgorithmChange) -> Unit = ::applyPlaybackAlgorithm
    private val repository by lazy { ContentRepository(signer = playbackAlgorithms.signer, playbackAlgorithms = playbackAlgorithms) }
    val offlineDownloads by lazy { OfflineDownloads.get(this) }
    @Volatile private var offlineOnly = false
    val mineOfflineSelected get() = StartupSettings.string("mineContentTab") == "offline"
    fun selectMineOfflineTab(offline: Boolean) {
        StartupSettings.set("mineContentTab", if (offline) "offline" else "favorites")
        renderPhoneTab()
    }
    fun showOfflineLibraryPhone() {
        StartupSettings.set("mineContentTab", "offline")
        if (screen == "player") returnToDetail()
        offlineOnly = false
        switchPhoneTab("mine")
    }
    /** 使用原始分集表保留集号；播放来源始终来自手动离线库。 */
    fun openOfflinePhone(item: OfflineDownloads.Item, index: Int, position: Long? = null, autoplay: Boolean = true) {
        if (item.downloaded.none { it.number == index + 1 }) { PhoneUi.toast(this, "该集尚未离线"); return }
        rememberPhoneCatalogPosition()
        restoringPhoneDetail = null
        favoriteMonitor.stop()
        invalidatePage(); releaseCatalogViews(); ensurePlaybackCache().clear()
        offlineOnly = true
        phoneDetailReturn = "mine"
        pendingDetail = item.series
        detail = withCachedRating(Detail(item.series, item.episodeIds))
        phoneTab = "detail"; screen = "detail"; directPlayback = true
        val progress = library.progress(item.series.id)?.takeIf { it.episodeId == item.episodeIds[index] && !it.completed }
        playEpisodePhone(index, position ?: progress?.position ?: 0, autoplay)
    }
    private fun adjacentEpisodeIndex(direction: Int): Int? {
        val data = detail ?: return null
        val indices = if (direction > 0) (episodeIndex + 1..data.episodes.lastIndex) else (episodeIndex - 1 downTo 0)
        return indices.firstOrNull { !offlineOnly || offlineDownloads.lookup(data.episodes[it]) != null }
    }
    private val touchPlayback by lazy { TouchControls.isHandheld(this) }
    private val randomRecommender by lazy {
        val raw=getSharedPreferences("random_pool", MODE_PRIVATE).getString("pool", null)
        val items=raw?.let { RandomPoolSnapshot.decode(it, System.currentTimeMillis()) } ?: emptyList()
        RandomRecommender(repository, items)
    }
    private var randomReplenishing=false
    private var randomDialog: AlertDialog?=null
    private var exitDialog: AlertDialog?=null
    private var phoneExitArtwork: GlideArtwork?=null
    private var killOnDestroy=false
    /** 播放器自己是否要求常亮。显式持有位，不靠 screen/requestedAutoplay 反推。 */
    private var playerScreenOn=false
    private var homePreviewScreenOn=false
    /**
     * 顶栏空间吃紧（特大缩放 + 高密度屏，有效宽度可能只剩 700dp 出头）：
     * 品牌去掉版本号、工具入口改用短文案，否则「设置」会被挤出屏幕外、
     * 方向键也走不过去（模拟器 320dpi / 特大档实测）。由 showCatalog 每次重建时赋值。
     */
    private var navTight=false
    // 复用同一台设备身份：弹幕与内容/播放必须签同一份档案，否则平台会当成两台设备。
    private val danmakuRepo by lazy { DanmakuRepository(repository.http, signer=repository.activeSigner) }
    private var playbackCache: EpisodePlaybackCache? = null
    private fun ensurePlaybackCache(): EpisodePlaybackCache = playbackCache ?: run {
        val current = repositoryForPlayback()
        EpisodePlaybackCache(current,cachedEpisode={ id,q ->
            val cache=episodeDiskCache
            cache?.playbackMeta(id,q) ?: cache?.readableMeta(id,q)?.takeIf {
                !it.done && (it.expiresAt<=0 || it.expiresAt>System.currentTimeMillis()+60_000L)
            }
        },localEpisode={ offlineDownloads.lookup(it) },localOnly={ offlineOnly },onPrepared={ _,_ ->
            // 地址和索引一就绪便交给 Media3，不再等下一次一秒定时刷新。
            main.post { if(!isDestroyed && screen=="player") maybePrefetch() }
        }).also { playbackCache = it }
    }
    /** 切换算法只更新取址仓库；正在播放和已缓冲的视频继续，不会因切换被中断。 */
    private fun rebuildPlaybackCacheForAlgorithm() {
        val fresh = repositoryForPlayback()
        playbackCache?.rebind(fresh)
    }
    /** 当前算法对应的播放仓库；缓存命中时也使用同一个 signer。 */
    private fun repositoryForPlayback(): ContentRepository =
        repository.rebind(playbackAlgorithms.signer, playbackAlgorithms)
    private var playbackSession: EpisodePlaybackSession?=null
    /** 落盘池始终供播放读取；缓存开关只控制后台下载，关闭时保留已有文件。 */
    @Volatile private var episodeDiskCache: EpisodeDiskCache?=null
    private var episodeCacheOn=false
    private var episodeCacheLoading=false
    private var episodeCacheParallelism=0
    /** 上一次记录过的「调度为什么没跑」：同一条原因只写一次日志，避免每秒 tick 刷屏。 */
    private var lastScheduleIdle=""
    /** 最近一次的调度阻塞原因（不受去重影响）：日志弹窗的现场摘要直接读它。 */
    private var lastIdleForSummary=""
    /** 三段缓存条的高度（dp）：轨道与容器共用，避免只改一处导致填充条被裁掉。 */
    private val cacheStripHeightDp=0.5f
    /** 上一次按 onProgress 重画条带的时间（elapsedRealtime）：三集并发时用来节流。 */
    @Volatile private var cacheStripPaintedAt=0L
    /** 屏幕最底部的缓存状态条：三段依次对应下 1/2/3 集，null 表示当前页面未挂。 */
    private var cacheStripSegments: List<CacheProgressSegment>?=null
    private var cacheStripView: View?=null
    // 与页面取址状态分开：等待新集就绪时，旧媒体源仍可能在播放器线程中读取。
    private var playerVideo: RemoteVideo?=null
    private val switchingVideos=mutableSetOf<RemoteVideo>()
    private var prefetchMemoryPausedUntil=0L
    private var nextPrefetchMemoryCheckAt=0L
    // 已提前挂进 ExoPlayer 播放列表的下一集；资源继续归相邻集缓存持有，手动切集也能复用。
    private var queuedNextId=""
    private var queuedIndex=-1
    private var queuedVideo: RemoteVideo?=null
    private var queuedFromIndex=-1
    private var queuedFromDuration=0L
    private var readySince=0L
    private lateinit var artwork: GlideArtwork
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private val phoneSessionStore by lazy { PhoneSessionStore(this) }
    private val phoneSessionPositions=mutableMapOf<String,Int>()
    private val phoneSessionHeaderOffsets=mutableMapOf<String,Int>()
    private val pendingPhoneScrolls=mutableMapOf<String,Int>()
    private var restoringPhoneDetail: PhoneSessionSnapshot?=null
    private var phoneSessionInitialized=false
    private var phoneConfiguration: android.content.res.Configuration?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    /** 启动崩溃提示只弹一次：进入前台时检测，弹过就不再查（recreate 会再走 onResume）。 */
    private var crashPromptDone=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var libraryLoadError: Throwable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var detailExtrasScope: RequestScope?=null
    private var detailExtrasJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    private val gridColumns=6
    private val waterfallMaxPages=10
    /** 榜单条目上限：官方接口每页约 10 条，按页数收尾会把榜单截短，所以按条数兜底。 */
    private val rankMaxItems=200
    /** 当前榜单按条数判断是否还能继续加载；非榜单页沿用按页数的口径。 */
    private fun rankCanLoadMore(items: Int, pageNo: Int)=items<rankMaxItems && pageNo<rankMaxPages
    private val rankMaxPages=20
    private var catalogRecycler: RecyclerView?=null
    private var catalogAdapter: CatalogAdapter?=null
    private var catalogFocusPos=RecyclerView.NO_POSITION
    /** 方向键尚未落焦的目标；滚动回收旧卡片时不能用旧位置覆盖它。 */
    private var pendingCatalogFocusPos=RecyclerView.NO_POSITION
    /** 焦点当前是否在网格卡片内：用于在 Activity 层拦截下键，即使焦点被回收清空也按记住的位置继续下移 */
    private var gridFocusOwned=false
    private var loadingMore=false
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private var homeScreen: HomeScreen?=null
    private var homePlayback: TvHomePlayback?=null
    private var catalogSwitcher: android.widget.ViewSwitcher?=null
    private var catalogContainer: ViewGroup?=null
    private var selectionPreview: SeriesPreview?=null
    private var homeFromCache=false
    /** 榜单页当前内容是否来自缓存：影响联网回调要不要打断展示。 */
    private var rankFromCache=false
    /** 电视榜单页自己的分页会话：与首页漫剧、随机荐剧互不影响。 */
    private val rankSession by lazy { repository.rankSession(RankBoard.COMIC) }
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var accountManager: AccountManager
    // 手机页面只读访问（PortraitPages/PortraitDetail 直接读库与账号状态）
    val phoneLibrary get() = library
    val phoneAccount get() = accountManager
    private var accountButton: TextView?=null
    private lateinit var tvTools: TvTools
    private lateinit var extras: PlaybackExtras
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private var danmakuView: DanmakuView?=null
    private var danmakuLoader: DanmakuLoader?=null
    private lateinit var favoriteMonitor: FavoriteMonitor
    val phoneFavoriteMonitor get() = favoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var favoriteGroupFilter: String?=null
    private var favoriteFilterFirst: TextView?=null
    private var favoriteFilterButtons: List<TextView> =emptyList()
    private var favoriteFilterFocus: Int=-1
    private var homeUpdates: TextView?=null
    private val resumeCards=mutableListOf<View>()
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    /** 本次播放连续跳过的不兼容集数：成功后归零，最多自动跳 5 集。 */
    private var incompatibleSkips=0
    /** 排着队的「不兼容自动跳下一集」定时任务：用户抢先点了上一集/选集时必须撤掉。 */
    private var skipUnplayableJob: Runnable?=null
    private var startPosition=0L
    /**
     * 本次会话里每一集看到的位置，键为「剧 id:集 id」。
     * 换集后返回上一集要接着播，不能因为换集时把整部剧只有一条的进度记录覆盖掉就从头开始。
     */
    private val episodePositions=mutableMapOf<String,Long>()
    private var outroSkipSeconds=0
    private var outroTriggered=false
    private var ffActive=false
    private var ffSpeedBefore=1f
    private var retryPosition=0L
    /** 本轮「正在获取播放地址」的起始时刻，用于在界面上显示已经等待了多久。 */
    private var addressRequestedAt=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    /**
     * 用户是否主动按了暂停（只由用户操作与定时停止改写）。
     *
     * 自动连播不能读 [requestedAutoplay]：那个标记会被 onPause / onStop 后的原地续播
     * 置 false，而用户没手动点播放时没有任何路径把它恢复，结果一集播完就停住不切下一集。
     * 这里只记录「用户本人的意图」，生命周期打断不会污染它，回到前台继续播时也不会。
     */
    private var userPausedIntentionally=false
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        // 只在真正断网时取消预取：移动网络同样允许缓存，不再按计费网络停用
        private fun changed() { main.post { if(!networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var backdropView: ImageView?=null
    private var backdropBitmap: Bitmap?=null
    private var generation=0
    private var screen="catalog"
    private var tab=0
    /** TV 首页当前是否处于随心看来源；独立于 tab，保留 0..6 的历史页面编号。 */
    private var suixinMode=false
    private var suixinCursor: SuixinCursor?=null
    private var suixinTrialHints: Map<String,SuixinTrialHint> = emptyMap()
    private var suixinSource: String?=null
    private var suixinLoading=false
    private var suixinFailed=false

    /** 手机设置与播放弹窗共用的焦点锚点，不挂载到页面。 */
    private val pickerAnchor by lazy { TextView(this).apply { id=View.generateViewId() } }
    private var page=1
    private var query=""
    // TV 列表与手机列表共用同一份当前数据：两侧页面互斥渲染，切换方向时各自重新加载，
    // 不会同时展示；手机翻页/搜索也写这里（PortraitPages 只读）。
    var catalog=emptyList<Series>()
        private set
    var hasMore=false
        private set
    private var catalogFocus=""
    // 播放中长按菜单键呼出快捷面板：短按仍在抬起时执行用户设定行为
    private var menuLongFired=false
    private val menuLongRunnable=Runnable { menuLongFired=true; showQuickPanel() }
    // 收藏排序：update 最近更新 / watch 最近观看 / name 名称；管理模式支持多选批量取消收藏
    private var favoriteManage=false
    private val favoriteSelected=linkedSetOf<String>()
    private var favoriteSortButtons=emptyList<TextView>()
    private var favoriteSortFirst: TextView?=null
    private var favoriteSelectAll: TextView?=null
    private var favoriteRemove: TextView?=null
    private var favoriteManageButton: TextView?=null
    // 高级搜索：与关键词搜索共用结果网格，但条件、分页游标和筛选面板单独维护
    private var advancedSearch=false
    private var discoverySelection=DiscoverySelection()
    private var discoveryGroups=emptyList<DiscoveryGroup>()
    private var discoveryCursor: DiscoveryCursor?=null
    private var filterToggle: TextView?=null
    private var filterSummary: TextView?=null
    private var filterExpanded=true
    private var filterPanel: LinearLayout?=null
    private var filterGroupsScroll: View?=null
    private var filterChips=linkedMapOf<Pair<String,String>,TextView>()
    private var filterRows=mutableListOf<List<TextView>>()
    private var filterCollapse: TextView?=null
    private var filterReset: TextView?=null
    private var filterRefresh: TextView?=null
    // 条件变更后重建页面时，把焦点送回刚刚操作的那个条件
    private var pendingFilterFocus: Pair<String,String>?=null
    // cursor/discovery 一并缓存：分类接口的续页靠服务器 session，切走再回来若沿用别的页签的条件就会拉到错内容
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?,val board: RankBoard?=null,val genre: Genre?=null,val aiCatalog: Boolean=false,val aiGenre: Genre?=null,val newRelease: Boolean=false,val newReleaseWindow: String?=null,val cursor: DiscoveryCursor?=null,val discovery: DiscoverySelection?=null)
    private var ranking: ComicRanking?=null
    private var rankBoard=RankBoard.COMIC
    // 榜单按枚举顺序循环切换，新增榜单只需在 RankBoard 里加一项
    private fun nextRankBoard()=RankBoard.entries[(rankBoard.ordinal+1)%RankBoard.entries.size]
    /** 上新区跟随当前榜单的内容类型：漫剧榜看漫剧上新、短剧榜看真人剧上新，条件固定「最新上架 + 时间窗」。 */
    private fun newReleaseSelection(): DiscoverySelection {
        val genreId=when(rankBoard) { RankBoard.COMIC -> "comic_series"; RankBoard.REAL -> "short_play"; RankBoard.AI -> "ai_series" }
        // choose("genre") 会清空其它维度，所以必须先选 genre，再叠排序和时间窗
        return DiscoverySelection().choose("genre",genreId).choose("sort","online_time").choose("online_time",newReleaseWindow)
    }
    private fun newReleaseLabel()=NEW_RELEASE_WINDOWS.firstOrNull { it.first==newReleaseWindow }?.second ?: NEW_RELEASE_WINDOWS[0].second
    /** 时间窗一次选到位置，不再四档轮转：弹层里标出当前档，确认即切换。 */
    private fun showNewReleaseWindowPicker() {
        val anchor=newReleaseWindowButton ?: return
        val current=NEW_RELEASE_WINDOWS.indexOfFirst { it.first==newReleaseWindow }
        val labels=NEW_RELEASE_WINDOWS.map { (id,name) -> if(id==newReleaseWindow) "$name · 当前" else name }
        tvTools.choose("上新时间范围",labels,anchor,{ index ->
            val next=NEW_RELEASE_WINDOWS.getOrNull(index)?.first ?: return@choose
            if(next==newReleaseWindow) return@choose
            newReleaseWindow=next; library.newReleaseWindow=next
            page=1; catalog=emptyList(); hasMore=false; catalogFocus=""; discoveryCursor=null; discoverySelection=newReleaseSelection()
            tabState.remove(2); tabFocus.remove(2); pendingNewReleaseFocus="window"; showCatalog(true)
        },focusIndex=current,anchorToButton=true)
    }
    /** 上新区重建后把焦点还给用户刚按的那个按钮；没有待处理请求时返回 false。 */
    private fun consumePendingNewReleaseFocus(): Boolean {
        val target=when(pendingNewReleaseFocus) {
            "back" -> newReleaseEntry
            "window" -> newReleaseWindowButton
            "refresh" -> rankRefresh
            else -> null
        }
        pendingNewReleaseFocus=null
        return target?.takeIf { it.isAttachedToWindow }?.requestFocus()==true
    }
    private var rankSwitch: View?=null
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    // AI 剧分类是排行榜页内的子视图，不占用首页内容类型页签，也不参与搜索口径
    private var aiCatalog=false
    private var aiGenre: Genre?=null
    private var aiEntry: View?=null
    private var aiBack: View?=null
    private var aiGenreButton: View?=null
    // 上新区：也挂在排行榜页下，条件固定「最新上架 + 时间窗」，数据走分类接口而不是网页榜单
    private var newRelease=false
    private var newReleaseWindow=NEW_RELEASE_WINDOWS[0].first
    private var newReleaseEntry: View?=null
    private var newReleaseBack: View?=null
    private var newReleaseWindowButton: View?=null
    // 切换条件后整页重建，异步回调的落焦会抢走按钮焦点：记下用户刚按的是哪个按钮，加载完再送回
    private var pendingNewReleaseFocus: String?=null
    private var genre: Genre?=null
    private var genreButton: View?=null
    private var genreClear: View?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    // 二级分段首项：导航 ↓ 先落到分段，再进内容
    private var segmentFirst: View?=null
    private val typeButtons=mutableMapOf<ContentType,View>()
    /** 首页/排行各保留一套已显示的目录视图，普通页签切换不再销毁 ImageView。 */
    private data class RetainedCatalog(
        val page: View,
        val contentType: ContentType,
        val home: HomeScreen?,
        val recycler: RecyclerView?,
        val adapter: CatalogAdapter?,
        val preview: SeriesPreview?,
        val nav: List<View>,
        val types: Map<ContentType,View>,
        val rankSwitch: View?,
        val rankRefresh: View?,
        val rankSubtitle: TextView?,
        val focus: View?,
        val focusOwned: Boolean,
        val focusPosition: Int,
        val pendingFocusPosition: Int,
    )
    private val retainedCatalogs=mutableMapOf<Int,RetainedCatalog>()
    private var retainedTheme=TvStyle.theme
    private var retainedWidthDp=0
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var azFirst: View?=null
    private val azLastRow=mutableListOf<View>()
    private var suggestRow: LinearLayout?=null
    private var suggestFirst: View?=null
    private var suggestTicket=0
    private var suggestVertical=false
    private var suggestFocusIndex=-1
    private var searchBody: LinearLayout?=null
    private var searchColumns=6
    private var keyboardLastLabel: String?=null
    private var keyboardKeys=emptyMap<String,TextView>()
    private var keyboardRows=emptyList<List<TextView>>()
    private var historyFirst: TextView?=null
    private var historyItems=emptyList<TextView>()
    private var suggestScrollRef: ScrollView?=null
    private var fullSearchColumns=6
    private var lastSuggestWords=emptyList<String>()
    private var lastSuggestKey=""
    private var resultFirstCard: View?=null
    private var searchFocusPending=false
    private val pendingSuggest=Runnable { fetchSuggest() }
    private var historyManage: View?=null
    private var historySync: View?=null
    private var historyRefresh: View?=null
    /** 云端记录卡片点击后的续播进度：只用于起播，落地仍由播放中的 saveProgress 写本机。 */
    private var pendingCloudResume: CloudProgress?=null
    /** 设置页重建后要恢复焦点的设置项标签前缀。 */
    private var settingsFocusKey: String?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var detailEpisodePage=0
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var playbackFirstFrameRendered=false
    private var tvVideoFrameWaitStartedAt=0L
    private var pausedForLifecycle=false
    /** 这次 onStop 只停不拆：播放器与媒体源留着，回到前台原地续播。
     *  正在播放时切到别的 App（或锁屏/下拉通知栏）会走 onStop，以前一律 releasePlayer()，
     *  回来只能重新取址、重新 prepare、重新缓冲，用户看到的就是「切出去再回来又加载一遍」。
     *  真正的拆除仍由离开播放页（returnToDetail 等）或页面回到详情/列表时触发。 */
    private var playbackKeptOnStop=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var playbackStatus: TextView
    private lateinit var progressBar: PlaybackSeekBar
    /** 自动重试时的转圈，单独可见；手动面板态切回 controls。 */
    private var recoverySpinner: ProgressBar?=null
    private var tvPauseButton: ImageButton?=null
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var tvPlaybackMenu: TvPlaybackMenu?=null
    private var tvPlaybackHeader: LinearLayout?=null
    private var sleepButton: TextView?=null
    /** 手机始终显示退出按钮；电视在使用鼠标时显示，见 [TvPointer]。 */
    private var playerExit: TextView?=null
    /** 最近一次操作是不是鼠标做的：遥控一按就翻回 false，按钮随之收起。 */
    private var mouseSeen=false
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    /** 本次播放会话内的临时降档，不改用户全局设置；重进播放页或手动改清晰度时清除。 */
    private var sessionQuality: Int?=null
    /**
     * 播放界面里的「播放设置」只作用于当前这部剧，不写回「设置 → 播放」里的全局默认。
     * 用户看这一部时想把倍速调到 1.5×、画面调成铺满，不该顺手改掉明天看别的剧时的默认值。
     */
    private var sessionSpeed: Float?=null
    private var sessionFrame: VideoFrameMode?=null
    private var downgradeEpisode=""
    private var downgradeCount=0
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(phonePlayerLayout()) refreshPhonePlayer() else {
                    refreshTvPlaybackLoading()
                    if(hud.visibility==View.VISIBLE) updatePlaybackText()
                }
                // 三段缓存条带也要跟着每秒刷新：切集后它对应的「下 1/2/3 集」变了，
                // 下载进度也在持续变化。只在条带已挂载时做，其他页面零开销。
                if(cacheStripSegments!=null) refreshCacheStrip()
                danmakuLoader?.let { loader -> if(player?.isPlaying==true) { val position=currentPosition(); io.execute { runCatching { loader.loadUpTo(position) } } } }
                checkTvVideoFirstFrame()
                maybePrefetch()
                val p=player; val data=detail
                if(p!=null && data!=null && playbackReady && !playError && outroSkipSeconds>0 && !outroTriggered && p.isPlaying) {
                    val remaining=p.duration-p.currentPosition
                    if(p.duration>0 && remaining in 0..outroSkipSeconds*1000L) {
                        outroTriggered=true
                        // 播放器已经自动交接到预挂的下一集时不要再走 playEpisode：
                        // 那条路会 pauseForSwitch + dropQueuedNext，与交接撞在一起就是「播到下一集却卡住」。
                        val alreadyHandedOff=queuedNextId.isNotEmpty() && p.currentMediaItem?.mediaId==queuedNextId
                        if(alreadyHandedOff) { /* onMediaItemTransition 正在接管 */ }
                        else if(library.autoNext && adjacentEpisodeIndex(1) != null) {
                            Toast.makeText(this@MainActivity,"已跳过片尾，播放下一集",Toast.LENGTH_SHORT).show()
                            adjacentEpisodeIndex(1)?.let { playEpisode(it,savedPosition(it)) }
                        } else if(continueToNextSeason()) { /* season jump in progress */ }
                    }
                }
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            savePhoneSession()
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen=="player" && canAutoHideHud() && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    private fun heightDp()=resources.displayMetrics.heightPixels/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    /** 顶栏纯文字 tab：无按钮背景，选中/聚焦用强调色 */
    private fun tabLabel(label: String,selected: Boolean,onClick: ()->Unit): TextView = TextView(this).apply {
        text=label; textSize=15f; isFocusable=true; isClickable=true
        setTextColor(if(selected) accent else muted); includeFontPadding=false
        setPadding(dp(14),dp(8),dp(14),dp(8))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _,f -> setTextColor(if(f||selected) accent else muted) }
    }
    /** 顶栏图标按钮：emoji 图标，无背景，聚焦变强调色 */
    private fun iconLabel(icon: String,desc: String,onClick: ()->Unit): TextView = TextView(this).apply {
        text=icon; textSize=19f; isFocusable=true; isClickable=true
        contentDescription=desc; setTextColor(muted); includeFontPadding=false
        setPadding(dp(10),dp(6),dp(10),dp(6))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _,f -> setTextColor(if(f) accent else muted) }
    }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        TouchControls.focusOnTouch(view,touchPlayback)
        fun paint(focused: Boolean) { view.background=if(phoneLayout()) rounded(if(focused) TvStyle.raised else if(selected) TvStyle.selectedSurface else surface,if(focused) accent else Color.TRANSPARENT) else TvVisual.card(this,focused,selected) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun paintPillNav(view: TextView,selected: Boolean,focused: Boolean) {
        view.isSelected=selected
        view.background=TvVisual.tab(this,focused,selected)
        view.setTextColor(if(selected) accent else if(focused) white else muted)
        view.setTypeface(null,if(selected || focused) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        TvStyle.apply(StartupSettings.string("theme", "NIGHT"))
        super.onCreate(state)
        if(!StartupSettings.isLoaded) {
            val tvStartup=!touchPlayback
            val status=if(tvStartup) null else TextView(this).apply {
                text="正在读取本机设置…"; textSize=18f; gravity=Gravity.CENTER
                setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(18,18,18))
            }
            if(tvStartup) {
                requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                root=FrameLayout(this)
                setContentView(root)
            } else setContentView(status!!)
            fun loadSettings() {
                if(tvStartup) showTvStartupHome()
                else { status?.text="正在读取本机设置…"; status?.setOnClickListener(null) }
                StartupSettings.loadAsync(this) { result ->
                    if(!isDestroyed && !isFinishing) result.onSuccess {
                        playbackAlgorithms.addListener(playbackAlgorithmListener)
                        recreate()
                    }.onFailure { problem ->
                        if(tvStartup) {
                            android.util.Log.e("HongguoTV","Startup settings failed to load",problem)
                            showTvStartupHome(retry={ loadSettings() })
                        } else status?.apply {
                            text="本机设置读取失败，点击重试"
                            isFocusable=true; isFocusableInTouchMode=true; requestFocus()
                            setOnClickListener { loadSettings() }
                        }
                    }
                }
            }
            loadSettings()
            return
        }
        PortraitStyle.refresh(this)
        // recreate 首帧窗口背景跟随即将显示的页面配色：手机浅色外观下不再闪过深夜影院黑底
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
            if(phoneLayout()) PortraitStyle.background else TvStyle.background))
        phoneConfiguration=android.content.res.Configuration(resources.configuration)
        // 系统风格 / 界面缩放切换触发的重建：把原页面和焦点带回来
        if(PageRestore.tab>=0) {
            tab=PageRestore.tab
            settingsFocusKey=PageRestore.focusKey
            PageRestore.tab=-1; PageRestore.focusKey=null
        }
        // 手机设置页里切了界面缩放：重建后等库加载完，直接回到手机设置页
        if(PageRestore.phoneSettings) { PageRestore.phoneSettings=false; pendingPhoneSettings=true }
        // 系统栏策略：电视维持原有沉浸全屏标志；手机竖屏要正常显示状态栏（时钟/信号），
        // Android 15 起 edge-to-edge 是强制的，低版本显式打开统一行为——内容铺到系统栏
        // 下面，由 PhoneInsets 在各页面让位，避免 14- 上状态栏被沉浸标志整个藏掉。
        if(TouchControls.isHandheld(this)) {
            WindowCompat.setDecorFitsSystemWindows(window,false)
            window.statusBarColor=Color.TRANSPARENT
            window.navigationBarColor=Color.TRANSPARENT
            applyPhoneSystemBars()
        } else {
            window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        // 方向策略：电视锁横屏；手机按「启动方向」偏好，跟随系统时竖屏自动切到手机版页面
        requestedOrientation=PhoneUi.requestedOrientation(this)
        library=Library(this); artwork=GlideArtwork(this); tvTools=TvTools(this); extras=PlaybackExtras(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        // 上新区沿用上次选择的时间窗（只在本机记，识别不了的值回落到默认档）
        newReleaseWindow=library.newReleaseWindow.takeIf { id -> NEW_RELEASE_WINDOWS.any { it.first==id } } ?: NEW_RELEASE_WINDOWS[0].first
        setupMouseGestures()
        accountManager=AccountManager(this,library,repository.activeSigner)
        accountManager.onAccountChanged={
            accountButton?.text=accountManager.accountLabel
            // 账号状态变化后，正在展示的记录页立即重建（登录后拉取云端历史、退出后清空云端区）。
            // isLoaded 必须查：recreate()（换主题/缩放）会新建 AccountManager 并可能立刻回调，
            // 此时本机记录还在异步加载，页面一读取 state() 就会崩溃。
            if(library.isLoaded && screen=="catalog" && (tab==4 || collection=="resume")) showCatalog()
            // 手机竖屏的「我的 / 收藏」页直接展示账号与云端数据，登录/退出后要立即重建
            if(library.isLoaded && phoneLayout() && screen=="catalog" && (phoneTab=="mine" || phoneTab=="fav")) renderPhone()
            // 手机设置页里登录/退出：昵称与云端数量就是行标题，整页重画一次
            if(library.isLoaded && screen=="settings") refreshPhoneSettings()
        }
        backdropView=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        loadBackdrop()
        connectivity=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // registerDefaultNetworkCallback 要 API 24，低版本退回全量回调
        runCatching {
            if(Build.VERSION.SDK_INT>=24) connectivity.registerDefaultNetworkCallback(networkCallback)
            else connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback)
            networkRegistered=true
        }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        activityInitialized=true
        if(phoneLayout()) phoneLibraryLoading() else showTvStartupHome()
        // 更新检查已在 Application 中立即发起。发布即弹：结果一落地就尝试消费，
        // 页面未就绪时结果保留在中转站，由 markPageShown()/showCatalog() 等就绪点补弹。
        PendingAppUpdate.setListener { main.post { drainPendingUpdate() } }
        if(PendingAppUpdate.hasPending()) main.post { drainPendingUpdate() }
        loadLibrary()
    }

    /**
     * 在线更新检查。冷启动失败静默；设置页手动检查失败给出提示。
     * 结果统一走 PendingAppUpdate 发布即弹：监听器回调到主线程消费，
     * 手动检查附带锚点（关闭弹框后焦点还给「检查更新」按钮）。
     */
    private fun checkAppUpdate(manual: Boolean, anchor: View?) {
        if(manual) pendingManualAnchor=anchor
        io.execute {
            val result=runCatching { AppUpdateChecker().check(BuildConfig.VERSION_CODE) }
            if(manual) result.onSuccess { PendingAppUpdate.publishManual(it) }
                .onFailure { PendingAppUpdate.publishManualError(it.message ?: "网络不可用") }
        }
    }

    /** 手动检查更新时记录锚点，消费完（弹框或 Toast）后清空。 */
    private var pendingManualAnchor: View?=null

    /** 发布即弹的回调入口：只看页面是否就绪，不就绪则保留结果等下一个就绪点。 */
    private fun drainPendingUpdate() {
        if(!activityInitialized || !foreground || isDestroyed || isFinishing) return
        // 过渡页（正在读取本机记录）上不盖更新弹框，等首个真实页面就绪。
        if(initialCatalogPending && !library.isLoaded) return
        consumePendingUpdate(manual = PendingAppUpdate.peekManual())
    }

    /** 消费进程级静默检查结果；没有结果时什么也不做，避免影响启动。 */
    private fun consumePendingUpdate(manual: Boolean, anchor: View?=null) {
        if(isDestroyed || isFinishing) return
        val pending=PendingAppUpdate.consume(manual) ?: return
        val focusAnchor=anchor ?: pendingManualAnchor
        pendingManualAnchor=null
        when {
            pending.error!=null -> Toast.makeText(this,
                "检查更新失败：${pending.error}",Toast.LENGTH_LONG).show()
            pending.update!=null -> AppUpdateDialog.show(this, pending.update, focusAnchor)
            pending.manual -> Toast.makeText(this,"当前已经是最新版本",Toast.LENGTH_SHORT).show()
        }
    }
    /** 手机端读取本机记录的过渡页：配色跟随手机外观，重建（如切换算法模式）时不再闪过深夜影院黑屏。 */
    private fun phoneLibraryLoading() {
        root.removeAllViews()
        root.setBackgroundColor(PortraitStyle.background)
        root.addView(text("正在读取本机记录…",16f,PortraitStyle.muted).apply { gravity=Gravity.CENTER },
            FrameLayout.LayoutParams(-1,-1))
    }
    /** 读取记录期间先展示首页布局，不接触未加载的 Library，也不写入空记录。 */
    private fun showTvStartupHome(retry: (() -> Unit)?=null) {
        val container=base()
        val compact=widthDp()<800
        val horizontal=dp(if(compact) 16 else 24)
        container.setPadding(horizontal,dp(if(heightDp()<450) 10 else 16),horizontal,dp(if(heightDp()<450) 10 else 16))
        root.background=TvVisual.backdrop(this)
        nav.clear(); typeButtons.clear()
        val selectedTab=PageRestore.startupTab ?: tab
        val header=TvCatalogHeader(this,selectedTab) { next ->
            PageRestore.startupTab=next; PageRestore.startupHomeAction=null
            showTvStartupHome(retry)
        }
        nav.addAll(header.navigation)
        container.addView(header)
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { topMargin=dp(8); bottomMargin=dp(6) })
        val home=HomeScreen(this,header,{ _, _ -> },{ _, _ -> },{ _, _ -> },{ _, _ -> },{})
        container.addView(home,lp(-1,0).apply { weight=1f })
        home.showStartup(retry!=null)
        home.refresh.setOnClickListener {
            if(retry!=null) retry()
            else { tab=0; suixinMode=true; showCatalog(load=true) }
        }
        nav.forEach { it.nextFocusDownId=home.refresh.id }
        nav[TvCatalogHeader.normalize(selectedTab)].requestFocus()
    }
    private fun showInitialTvPage() {
        PageRestore.startupType?.let { library.contentType=it }; PageRestore.startupType=null
        PageRestore.startupTab?.let { tab=it }; PageRestore.startupTab=null
        val action=PageRestore.startupHomeAction; PageRestore.startupHomeAction=null
        if(tab==0) {
            page=1; catalog=emptyList(); hasMore=false
            suixinMode=true
            showCatalog(load=true)
        } else {
            showCatalog(load=tab==2 || (tab==1 && query.isNotBlank()))
        }
        if(tab==0 && action!=null) when(action) {
            "genre" -> showGenrePicker()
            "random" -> showRandomPicks()
        }
    }
    private fun showLibraryError(problem: Throwable) {
        if(!phoneLayout()) {
            android.util.Log.e("HongguoTV","Local records failed to load",problem)
            showTvStartupHome(retry={ showTvStartupHome(); loadLibrary() })
            return
        }
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(isDestroyed || isFinishing) return@loadAsync
            // 本机记录就绪后补齐首页数据，并按偏好异步创建缓存池。
            applyEpisodeCacheSetting()
            if(!isDestroyed && foreground) {
                // 手机竖屏使用手机首页，电视与横屏使用电视目录。
                if(pendingPhoneSettings && phoneLayout()) { pendingPhoneSettings=false; showPhoneSettings(); favoriteMonitor.check() }
                else if(phoneLayout()) { showInitialPhonePage(); favoriteMonitor.check() }
                else { showInitialTvPage(); favoriteMonitor.check() }
                maybePromptCrash()
                main.post { drainPendingUpdate() }
            }
        }
    }
    private fun base(title: String?=null): LinearLayout {
        retainedPhoneCatalogs.clear()
        catalogSwitcher=null; catalogContainer=null; retainedCatalogs.clear()
        root.removeAllViews(); root.setBackgroundColor(bg)
        backdropView?.takeIf { backdropBitmap!=null }?.let { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical)
        root.addView(container,FrameLayout.LayoutParams(-1,-1))
        catalogContainer=container
        if(title!=null) { val heading=row(); PageBackButton.addTo(heading) { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        return container
    }
    private fun loadCover(view: ImageView,url: String) = loadCoverFrom(view,url,"other")
    private fun loadCoverFrom(view: ImageView,url: String,src: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        val viewId = Integer.toHexString(System.identityHashCode(view))
        RuntimeLog.add("IMG call src=$src v=$viewId focused=$focused url=…${artwork.identity(url).takeLast(20)}")
        artwork.load(view,url,if(focused) Priority.HIGH else Priority.NORMAL)
    }
    /** 卡片差异和图片绑定共用封面身份，CDN 临时签名变化不参与比较。 */
    fun phoneCoverIdentity(url: String): String = artwork.identity(url)
    /** 同一剧集更新封面地址时保留旧图，等新图就绪后直接替换。 */
    fun updatePhoneCover(view: ImageView,url: String) = artwork.load(view,url,keepCurrentImage=true)
    /** 真正回收卡片时由 Glide 解除绑定并回收资源。 */
    fun recyclePhoneCover(view: ImageView) = artwork.clear(view)

    private fun cancelWork() {
        cancelSearchSuggestions(); main.removeCallbacks(pendingSuggest)
        cancelSearchArtwork()
        phoneRecommendationRetry?.let { main.removeCallbacks(it) }; phoneRecommendationRetry=null
        phoneHomeLoading=false
        phoneRankLoading=false
        // 取消的热搜刷新回到页面后应继续请求，已有缓存保留展示。
        if(phoneHotLoading) phoneHotAttempted=false
        phoneHotLoading=false
        phoneSearching=false
        phoneNewReleaseLoading=false
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (pageIo as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        phoneCoverTransition?.cancel(); phoneCoverTransition=null
        generation++; cancelWork(); cancelDetailExtras(); artwork.clearPage()
    }
    private fun releaseCatalogViews() {
        stopTvHomePlayback()
        retainedPhoneCatalogs.clear()
        retainedCatalogs.clear(); catalogSwitcher=null; catalogContainer=null
        homeScreen?.let { homePosition=it.capturePosition() }
        (catalogRecycler?.layoutManager as? GridLayoutManager)?.let { scrollPositions[catalogPageKey]=it.findFirstVisibleItemPosition() }
        homeScreen=null; selectionPreview=null; catalogRecycler=null; catalogAdapter=null; loadingMore=false
        // 这里只清「列表页」的视图引用。整集预载状态条属于播放页：手机竖屏切集走 reuse
        // 复用播放器、不会重建条子，在这里置空会让条子永远停在上一集画出的宽度上
        //（表现为切集后第三根一上来就是橙色、看不到下载过程）。条子改由 releasePlayer() 清理。
        catalogFocusPos=RecyclerView.NO_POSITION; pendingCatalogFocusPos=RecyclerView.NO_POSITION; gridFocusOwned=false
        nav.clear(); segmentFirst=null; typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        azFirst=null; azLastRow.clear(); suggestRow=null; suggestFirst=null; cancelSearchSuggestions(); main.removeCallbacks(pendingSuggest)
        suggestVertical=false; searchBody=null; keyboardKeys=emptyMap(); keyboardRows=emptyList(); historyFirst=null; historyItems=emptyList(); suggestScrollRef=null; resultFirstCard=null
        historyManage=null; historySync=null; historyRefresh=null; rankSwitch=null; rankRefresh=null; rankSubtitle=null; genreButton=null; genreClear=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; favoriteFilterFirst=null; favoriteFilterButtons=emptyList(); homeUpdates=null; resumeCards.clear()
        favoriteSortFirst=null; favoriteSortButtons=emptyList(); favoriteSelectAll=null; favoriteRemove=null; favoriteManageButton=null
        filterToggle=null; filterSummary=null; filterPanel=null; filterGroupsScroll=null; filterChips=linkedMapOf(); filterRows=mutableListOf()
        filterCollapse=null; filterReset=null; filterRefresh=null
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=pageIo.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket || scope.isCancelled) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    // 快速切集/切页会取消上一次的取流，取消本身不是播放失败：
                    // 否则 cancelled 的 IOException 会被当成播放错误，暂停画面并弹重试面板。
                    if(!isDestroyed && generation==ticket && !scope.isCancelled) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    /** 主界面显示后再提示上次异常；手机不自动打开导致上次崩溃的弹框通道。 */
    private fun maybePromptCrash() {
        if(crashPromptDone || !foreground || isFinishing || isDestroyed || !library.isLoaded || initialCatalogPending) return
        crashPromptDone=true
        io.execute {
            val records=runCatching { CrashReporter.reports(this) }.onFailure {
                RuntimeLog.add("读取异常日志失败：${it.javaClass.simpleName}")
            }.getOrNull() ?: return@execute
            val prompted=CrashReporter.promptedAt(this)
            if(records.none { it.at>prompted }) return@execute
            main.post {
                if(isDestroyed || isFinishing) return@post
                if(!foreground || initialCatalogPending) { crashPromptDone=false; return@post }
                if(phoneLayout()) {
                    CrashReporter.markPrompted(this,records)
                    PhoneUi.toast(this,"上次运行异常退出，可在设置 → 异常日志查看详情")
                } else CrashDialog.showList(this@MainActivity,records,fromStartup=true)
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    private fun switchTab(next: Int) {
        // 8 是随心看、7 是搜索；其余入口保持在原有 0..6 页面编号。
        if(next==8) { showSuixin(); return }
        if(next !in 0..6) return
        suixinMode=false
        leaveCollection()
        if(next==tab) { nav.getOrNull(TvCatalogHeader.normalize(tab))?.requestFocus(); return }
        if(retainableCatalog() && canReuseCatalogs() && restoreRetainedCatalog(next)) return
        // 离开首页/排行互切场景后，旧视图不再保留，后续一律按原流程重建。
        retainedCatalogs.clear()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking,rankBoard,genre,aiCatalog,aiGenre,newRelease,newReleaseWindow,discoveryCursor,discoverySelection); tabFocus[tab]=catalogFocus
        // 从其他模块切回搜索页时重置为全新搜索状态，不保留上次的搜索词与结果
        if(next==1) {
            query=""; lastSuggestWords=emptyList<String>(); lastSuggestKey=""; suggestFocusIndex=-1; keyboardLastLabel=null
            advancedSearch=false; discoverySelection=DiscoverySelection(); discoveryGroups=emptyList(); discoveryCursor=null; filterExpanded=true
            tabState.remove(1); tabFocus.remove(1)
        }
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        rankBoard=saved?.board ?: rankBoard; genre=saved?.genre ?: genre
        aiCatalog=saved?.aiCatalog ?: false; aiGenre=saved?.aiGenre
        newRelease=saved?.newRelease ?: false; newReleaseWindow=saved?.newReleaseWindow ?: newReleaseWindow
        // 续页游标与分类条件跟着页签一起存：上新/高级筛选的 session 不能跨页签复用，否则续页拉到的是别的条件
        discoveryCursor=saved?.cursor; discoverySelection=saved?.discovery ?: discoverySelection
        showCatalog(load=(next<=2 && catalog.isEmpty() && (next!=1 || query.isNotBlank())))
        // 切回搜索页时焦点落输入框，避免页面重建后焦点丢失
        if(next==1) searchInput?.requestFocus()
    }
    private fun retainableCatalog(tabIndex: Int=tab)=screen=="catalog" && collection==null && genre==null && when(tabIndex) {
        0 -> page==1 && catalog.isNotEmpty()
        2 -> !newRelease && !aiCatalog && page==1 && catalog.isNotEmpty()
        else -> false
    }

    /** 记录目标页签的普通状态；特殊子视图不进入双页保留路径。 */
    private fun canReuseCatalogs()=when {
        tab==0 -> tabState[2]?.let(::reusableSavedCatalog)==true
        tab==2 -> tabState[0]?.let(::reusableSavedCatalog)==true
        else -> false
    }

    private fun reusableSavedCatalog(saved: CatalogState)=saved.page==1 && saved.items.isNotEmpty() && !saved.newRelease && !saved.aiCatalog &&
        saved.cursor==null && (!saved.hasMore || (saved.board==rankBoard && saved.items.size<rankMaxItems))

    /** 两个页面始终留在同一个窗口，只切可见性，避免 detach 触发清图和重新解码。 */
    private fun retainCurrentCatalog() {
        if(!retainableCatalog()) return
        val focused=root.findFocus()
        val switcher=catalogSwitcher ?: android.widget.ViewSwitcher(this).also { created ->
            val current=catalogContainer ?: return
            root.removeView(current)
            created.addView(current,FrameLayout.LayoutParams(-1,-1))
            root.addView(created,FrameLayout.LayoutParams(-1,-1))
            catalogSwitcher=created
        }
        val pageView=switcher.currentView ?: return
        retainedCatalogs[tab]=RetainedCatalog(pageView,library.contentType,homeScreen,catalogRecycler,catalogAdapter,selectionPreview,
            nav.toList(),typeButtons.toMap(),rankSwitch,rankRefresh,rankSubtitle,focused,gridFocusOwned,catalogFocusPos,pendingCatalogFocusPos)
    }

    private fun restoreRetainedCatalog(next: Int): Boolean {
        val state=retainedCatalogs[next] ?: return false
        if(state.contentType!=library.contentType || retainedTheme!=TvStyle.theme || retainedWidthDp!=widthDp().toInt()) {
            retainedCatalogs.clear()
            return false
        }
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking,rankBoard,genre,aiCatalog,aiGenre,newRelease,newReleaseWindow,discoveryCursor,discoverySelection)
        tabFocus[tab]=catalogFocus
        // 已展示的页签保留视图；未完成的目录请求取消，回调不得重建隐藏页。
        stopTvHomePlayback()
        generation++; cancelWork()
        retainCurrentCatalog()
        // retainCurrentCatalog 会按需创建窗口；这里必须引用成员，局部作用域的 switcher 在另一个方法里不存在。
        val switcher=catalogSwitcher ?: return false
        val saved=tabState[next]
        if(saved==null) { retainedCatalogs.clear(); return false }
        tab=next; page=saved.page; catalog=saved.items; hasMore=saved.hasMore; ranking=saved.ranking; catalogFocus=tabFocus[next].orEmpty()
        rankBoard=saved.board ?: rankBoard; genre=saved.genre
        aiCatalog=saved.aiCatalog; aiGenre=saved.aiGenre; newRelease=saved.newRelease; newReleaseWindow=saved.newReleaseWindow ?: newReleaseWindow
        discoveryCursor=saved.cursor; discoverySelection=saved.discovery ?: discoverySelection
        screen="catalog"
        homeScreen=state.home; catalogRecycler=state.recycler; catalogAdapter=state.adapter; selectionPreview=state.preview
        nav.clear(); nav.addAll(state.nav); typeButtons.clear(); typeButtons.putAll(state.types)
        rankSwitch=state.rankSwitch; rankRefresh=state.rankRefresh; rankSubtitle=state.rankSubtitle
        // 复用时保留 switcher 与子页面的挂载关系，避免列表和 Glide 目标反复解绑。
        switcher.displayedChild=switcher.indexOfChild(state.page)
        // 只刷新导航选中态，不碰内容区的卡片和图片。
        nav.forEachIndexed { index,item ->
            val selected=index==TvCatalogHeader.tabForIndex(tab)
            (item as? TextView)?.let { paintPillNav(it,selected,false) }
        }
        gridFocusOwned=state.focusOwned; catalogFocusPos=state.focusPosition; pendingCatalogFocusPos=state.pendingFocusPosition
        (state.focus?.takeIf { it.isAttachedToWindow } ?: nav.getOrNull(TvCatalogHeader.normalize(tab)))?.requestFocus()
        homeScreen?.let { home -> home.reloadArtwork(); startTvHomePlayback(home) }
        if(state.focus==null) restoreCatalogFocus()
        return true
    }

    private fun switchContentType(type: ContentType) {
        retainedCatalogs.clear()
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        // 切换内容类型后旧联想不再适用，清空回显记忆
        lastSuggestWords=emptyList<String>(); lastSuggestKey=""
        for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null; genre=null
        showCatalog(load=(tab==0 || query.isNotBlank()),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    private fun localCatalog()=tab==3 || tab==4 || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { library.favoriteUpdate(it.id)?.hasUpdate == true }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> if(tab==3) {
            val base=library.favorites().filter { favoriteGroupFilter==null || library.groupOf(it.id)==favoriteGroupFilter }
            when(library.favoriteSort) {
                "watch" -> base.sortedByDescending { library.progress(it.id)?.updatedAt ?: 0L }
                "name" -> base.sortedBy { it.title }
                else -> base.sortedByDescending { library.favoriteUpdate(it.id)?.checkedAt ?: 0L }
            }
        } else library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false,focusFilter: Boolean=false) {
        retainedCatalogs.clear()
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null; pendingCloudResume=null
        if(tab !in 0..6) tab=0
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=when {
                tab==0 && !suixinMode && page==1 && genre==null -> library.cachedHome(library.contentType)
                // 榜单首页缓存：三个榜各自独立；上新与 AI 分类走别的数据通路，不缓存
                tab==2 && page==1 && !newRelease && !aiCatalog -> library.cachedRank(rankBoard)
                else -> null
            }
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=cached?.ranking
            homeFromCache=tab==0 && cached!=null
            rankFromCache=tab==2 && cached!=null
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        retainedTheme=TvStyle.theme; retainedWidthDp=widthDp().toInt()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        // 页面就绪点：若更新检查结果先于首屏到达，在这里补弹（平时由发布即弹直接触发）。
        if(root.isAttachedToWindow && foreground) main.post { drainPendingUpdate() }
        // TV 与手机竖屏使用独立容器；这里只调整 TV 页面的间距和背景。
        val compact=widthDp()<800
        val horizontal=dp(if(compact) 16 else 24)
        val vertical=dp(if(heightDp()<450) 10 else 16)
        container.setPadding(horizontal,vertical,horizontal,vertical)
        root.background=TvVisual.backdrop(this)
        navTight=widthDp()<1000
        val header=TvCatalogHeader(this,tab,::switchTab)
        nav.addAll(header.navigation)
        container.addView(header)
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { topMargin=dp(8); bottomMargin=dp(6) })
        if(tab==6) {
            container.setPadding(horizontal,vertical,horizontal,0)
            val body=column()
            val footerHost=column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(6),0,0) }
            container.addView(body,lp(-1,0).apply { weight=1f })
            container.addView(footerHost,lp(-1,-2))
            settings(body,footerHost); if(focusNav) nav[TvCatalogHeader.normalize(tab)].requestFocus(); return
        }
        if(tab==1 || (tab==0 && !suixinMode && collection==null)) {
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(tab==1 && advancedSearch) {
                // 高级搜索模式不建内容类型页签，类型统一由筛选面板的服务端选项决定
                types.addView(text("高级搜索",14f).apply { setTypeface(null,Typeface.BOLD); setPadding(dp(2),0,0,0) },lp(-2,-2))
                types.addView(Space(this),lp(0,1).apply { weight=1f })
                filterToggle=chip("‹ 返回关键词搜索",false) { switchAdvanced(false) }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                types.addView(filterToggle,lp(-2,-2))
                container.addView(types)
                nav.forEach { it.nextFocusDownId=filterToggle?.id ?: nav[TvCatalogHeader.normalize(tab)].id }
            } else {
            // 内容类型 tab：漫剧 / 短剧（互斥切换），chip 样式强调 tab 属性
            listOf(ContentType.COMIC,ContentType.SHORT).forEach { type ->
                typeButtons[type]=chip(type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id; textSize=14f; minHeight=dp(38); setPadding(dp(16),dp(8),dp(16),dp(8)) }
                types.addView(typeButtons[type],lp(if(tab==0) 0 else -2,-2).apply { if(tab==0) weight=1f; rightMargin=dp(8) })
            }
            if(tab==0) {
                genreButton=chip(genre?.let { "题材 · ${it.name}" } ?: "题材筛选",genre!=null) { showGenrePicker() }.apply {
                    nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id; textSize=13f; minHeight=dp(36); maxLines=1; ellipsize=TextUtils.TruncateAt.END
                }
                types.addView(genreButton,lp(0,-2).apply { weight=1f; rightMargin=dp(8) })
                val random=chip("随机荐剧",false) { showRandomPicks() }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id; textSize=13f; minHeight=dp(36) }
                types.addView(random,lp(0,-2).apply { weight=1f })
            }
            if(tab==1) {
                types.addView(Space(this),lp(0,1).apply { weight=1f })
                filterToggle=chip("高级搜索",false) { switchAdvanced(true) }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                types.addView(filterToggle,lp(-2,-2))
            }
            // 内容类型（漫剧/短剧/题材/随机）统一放在内容区首行，不挤进顶栏
            typeButtons.values.forEach { view -> (view as TextView).apply { textSize=13f; minHeight=dp(36); setPadding(dp(14),dp(7),dp(14),dp(7)) } }
            container.addView(types)
            nav.forEach { it.nextFocusDownId=(if(tab==1) filterToggle else typeButtons[library.contentType] ?: typeButtons.values.firstOrNull())?.id ?: nav[TvCatalogHeader.normalize(tab)].id }
            }
        }
        if(tab==1) {
            // 机型分级：≥900dp 三栏（键盘+历史 / 联想 / 结果），700-900dp 两栏，<700dp 回退纵向堆叠
            val mode=when { widthDp()>=900 -> 3; widthDp()>=700 -> 2; else -> 0 }
            val tall=heightDp()>=520
            val roomy=widthDp()>=1100
            val keyboardWidth=if(roomy) 250 else 220
            val suggestionWidth=if(roomy) 170 else 145
            val availableWidth=(widthDp()-48f).coerceAtLeast(0f)
            searchColumns=when(mode) {
                3 -> ((availableWidth-keyboardWidth-suggestionWidth-36f)/118f).toInt().coerceIn(2,gridColumns)
                2 -> ((availableWidth-keyboardWidth-24f)/118f).toInt().coerceIn(2,gridColumns)
                else -> gridColumns
            }
            // 无联想词时中栏整列消失，结果网格按可用宽度重新算列数。
            fullSearchColumns=if(mode==3) ((availableWidth-keyboardWidth-24f)/118f).toInt().coerceIn(2,gridColumns) else searchColumns
            val searchRow=row().apply { setPadding(0,dp(4),0,dp(10)) }
            val input=EditText(this).apply {
                id=View.generateViewId(); hint="剧名、关键词或拼音首字母"; setText(query); textSize=15f
                setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1
                setPadding(dp(14),dp(8),dp(14),dp(8)); background=TvVisual.card(this@MainActivity,false)
                filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            }
            searchRow.addView(input,lp(0,dp(45)).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }; input.nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[TvCatalogHeader.normalize(tab)].id; searchButton?.nextFocusUpId=input.nextFocusUpId
            // 输入框会吞掉方向键：↑ 显式交回分段/内容类型，避免焦点困在搜索框里
            input.setOnKeyListener { _,key,event ->
                if(key==KeyEvent.KEYCODE_DPAD_UP && event.action==KeyEvent.ACTION_DOWN) {
                    (segmentFirst ?: typeButtons[library.contentType] ?: nav.getOrNull(TvCatalogHeader.normalize(tab)))?.requestFocus(); true
                } else false
            }
            if(mode==0) {
                lateinit var recent: TextView
                recent=addButton(searchRow,"历史") { showSearchHistory(recent) }; recent.nextFocusUpId=input.nextFocusUpId
                searchActions.addAll(listOf(recent))
            }
            typeButtons.values.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }
            // 高级搜索改用筛选面板，不再占一行关键词输入，把高度留给筛选和结果网格
            if(!advancedSearch) container.addView(searchRow)
            input.addTextChangedListener(object: android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    scheduleSuggest()
                }
            })
            if(advancedSearch) {
                // 高级搜索不占键盘/联想/历史三栏，筛选面板与结果网格各占整行
                buildFilterPanel(container)
            } else {
            val keyHeight=if(tall) dp(36) else dp(30)
            fun azKey(label: String): TextView {
                val view=button(label) { onAzKey(label) }
                view.textSize=13f; view.minHeight=keyHeight; view.setPadding(dp(4),dp(4),dp(4),dp(4))
                view.background=TvVisual.card(this,false)
                view.setOnFocusChangeListener { _,focused -> view.background=TvVisual.card(this,focused); if(focused) keyboardLastLabel=label }
                return view
            }
            // 统一键盘：6 列×7 行（字母数字混排），末行删除和清空各占三列
            val keyboard=column()
            val keyLines=mutableListOf<List<TextView>>()
            val keyViews=linkedMapOf<String,TextView>()
            listOf("ABCDEF","GHIJKL","MNOPQR","STUVWX","YZ0123","456789").forEach { keys ->
                val line=row().apply { setPadding(0,0,0,dp(4)) }
                val views=keys.map { key -> azKey(key.toString()).also { line.addView(it,lp(0,-2).apply { weight=1f; rightMargin=dp(6) }); keyViews[key.toString()]=it } }
                keyboard.addView(line); keyLines+=views
            }
            val lastLine=row().apply { setPadding(0,0,0,dp(4)) }
            val del=azKey("删除").also { lastLine.addView(it,lp(0,-2).apply { weight=3f; rightMargin=dp(6) }); keyViews["删除"]=it }
            val clear=azKey("清空").also { lastLine.addView(it,lp(0,-2).apply { weight=3f }); keyViews["清空"]=it }
            keyboard.addView(lastLine)
            val lastViews=listOf(del,clear); keyLines+=lastViews
            keyLines.forEachIndexed { r,views ->
                views.forEachIndexed { c,key ->
                    key.nextFocusLeftId=views[if(c==0) 0 else c-1].id
                    key.nextFocusRightId=views[if(c==views.size-1) c else c+1].id
                    val up=keyLines.getOrNull(r-1); val down=keyLines.getOrNull(r+1)
                    // 末行两键各覆盖三列：上下键按覆盖列数映射，保持同列直觉
                    key.nextFocusUpId=up?.get(if(r==keyLines.size-1) minOf(c*3,up.size-1) else minOf(c,up.size-1))?.id ?: input.id
                    key.nextFocusDownId=down?.get(if(r==5) minOf(c/3,down.size-1) else minOf(c,down.size-1))?.id ?: key.id
                }
            }
            azFirst=keyLines.first().first(); azLastRow.clear(); azLastRow.addAll(lastViews)
            keyboardKeys=keyViews; keyboardRows=keyLines.toList()
            var catalogBody: LinearLayout?=null
            if(mode==0) {
                container.addView(keyboard)
                val suggestScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFocusable=false }
                val suggestLine=row(); suggestScroll.addView(suggestLine); suggestRow=suggestLine; suggestVertical=false
                container.addView(suggestScroll,lp(-1,-2).apply { bottomMargin=dp(4) })
            } else {
                // 顶部对齐：左栏随历史增多变高时，键盘顶边保持固定，不挤压搜索框间距
                val contentRow=row().apply { gravity=Gravity.TOP }
                // 与参考 TV 页一致，键盘、联想和结果各自滚动；窄屏保留双栏回退。
                val leftScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false; background=TvVisual.panel(this@MainActivity) }
                val leftColumn=column().apply { setPadding(dp(12),dp(10),dp(8),dp(12)) }
                leftScroll.addView(leftColumn)
                leftColumn.addView(text("遥控器搜索",16f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,0,0,dp(10)) })
                leftColumn.addView(keyboard)
                leftColumn.addView(text("方向键选字 · 确定输入",11f,muted).apply { setPadding(0,dp(4),0,dp(10)) })
                // 左栏底部搜索历史：内嵌最新 4 条 + 全部历史入口，空历史整块隐藏
                val history=library.searches()
                if(tall && history.isNotEmpty()) {
                    val block=column()
                    val head=row().apply { setPadding(0,dp(2),0,dp(4)) }
                    head.addView(text("搜索历史",13f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
                    lateinit var clearHistory: TextView
                    clearHistory=button("清空") { tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",clearHistory) { library.clearSearches(); showCatalog(); main.post { searchInput?.requestFocus() } } }
                        .apply { textSize=12f; minHeight=dp(28); setPadding(dp(10),dp(4),dp(10),dp(4)) }
                    head.addView(clearHistory,lp(-2,-2))
                    block.addView(head)
                    val items=history.take(4).map { entry ->
                        button("${entry.type.label} · ${entry.query}") { runSearch(entry.query,entry.type) }.apply {
                            textSize=13f; minHeight=dp(32); setPadding(dp(10),dp(4),dp(10),dp(4)); gravity=Gravity.CENTER_VERTICAL
                            maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
                        }
                    }
                    items.forEachIndexed { index,item ->
                        block.addView(item,lp(-1,-2).apply { bottomMargin=dp(4) })
                        item.nextFocusUpId=if(index==0) clearHistory.id else items[index-1].id
                    }
                    historyItems=items
                    if(history.size>4) {
                        lateinit var all: TextView
                        all=button("全部历史（${history.size}）›") { showSearchHistory(all) }.apply { textSize=12f; minHeight=dp(30); setPadding(dp(10),dp(4),dp(10),dp(4)) }
                        block.addView(all,lp(-1,-2)); historyItems=items+all
                    }
                    leftColumn.addView(block)
                    historyFirst=clearHistory
                    // 历史↑回到离开键盘时的按键，保持焦点记忆
                    clearHistory.nextFocusUpId=(keyboardLastLabel?.let { keyViews[it] } ?: del).id
                }
                val midScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false; background=TvVisual.panel(this@MainActivity) }
                val midColumn=column().apply { setPadding(dp(10),dp(10),dp(10),dp(10)) }
                val words=column()
                midScroll.addView(midColumn)
                midColumn.addView(text("联想词",16f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,0,0,dp(10)) })
                midColumn.addView(words)
                val rightColumn=column().apply { setPadding(dp(6),dp(2),0,0) }
                rightColumn.addView(text(if(query.isBlank()) "搜索结果" else "搜索结果 · $query",17f).apply {
                    setTypeface(null,Typeface.BOLD); maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(4),dp(5),0,dp(10))
                })
                if(mode==3) {
                    suggestRow=words; suggestVertical=true; suggestScrollRef=midScroll
                    contentRow.addView(leftScroll,lp(dp(keyboardWidth),-1).apply { rightMargin=dp(12) })
                    contentRow.addView(midScroll,lp(dp(suggestionWidth),-1).apply { rightMargin=dp(12) })
                    contentRow.addView(rightColumn,lp(0,-1).apply { weight=1f })
                } else {
                    contentRow.addView(leftScroll,lp(dp(keyboardWidth),-1).apply { rightMargin=dp(12) })
                    contentRow.addView(rightColumn,lp(0,-1).apply { weight=1f })
                    val suggestScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFocusable=false }
                    val suggestLine=row(); suggestScroll.addView(suggestLine); suggestRow=suggestLine; suggestVertical=false
                    rightColumn.addView(suggestScroll,lp(-1,-2).apply { bottomMargin=dp(4) })
                }
                container.addView(contentRow,lp(-1,0).apply { weight=1f })
                catalogBody=column().also { rightColumn.addView(it,lp(-1,0).apply { weight=1f }) }
            }
            searchBody=catalogBody
            // 键盘末行先指向历史（结果区建立后由 wireSearchPanel 补全联想与网格指向）
            azLastRow.forEach { it.nextFocusDownId=historyFirst?.id ?: View.NO_ID }
            }
        } else if(tab==2) {
            val titleRow=row().apply { setPadding(0,dp(4),0,dp(8)) }
            titleRow.addView(text(when { newRelease -> "新剧上新"; aiCatalog -> "AI剧 · 全部分类"; else -> "${rankBoard.label}热播榜" },22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(widthDp()>=850) titleRow.addView(text("方向键选剧 · 确定看详情 · 长按更多",12f,muted))
            container.addView(titleRow)
            val heading=row().apply { setPadding(0,0,0,dp(4)) }
            aiEntry=null; aiBack=null; aiGenreButton=null
            newReleaseEntry=null; newReleaseBack=null; newReleaseWindowButton=null
            // 这三个按钮只在部分子视图里存在，重建时先清空，避免焦点链指向已经拆掉的旧按钮
            rankSwitch=null; rankRefresh=null
            if(newRelease) {
                newReleaseBack=PageBackButton.addTo(heading,"返回${rankBoard.label}榜") {
                    newRelease=false; page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
                    discoveryCursor=null; discoverySelection=DiscoverySelection()
                    tabState.remove(2); tabFocus.remove(2); pendingNewReleaseFocus="back"; showCatalog(true)
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                newReleaseWindowButton=addButton(heading,"时间 · ${newReleaseLabel()}") { showNewReleaseWindowPicker() }
                    .apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                rankRefresh=addButton(heading,"刷新") {
                    page=1; catalogFocus=""; discoveryCursor=null; discoverySelection=newReleaseSelection()
                    pendingNewReleaseFocus="refresh"; showCatalog(true)
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
            } else if(aiCatalog) {
                aiBack=PageBackButton.addTo(heading,"返回AI剧榜") {
                    aiCatalog=false; aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                    aiBack?.post { if(aiBack?.isAttachedToWindow==true) aiBack?.requestFocus() }
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                aiGenreButton=addButton(heading,aiGenre?.let { "题材 · ${it.name}" } ?: "题材") { showGenrePicker() }
                    .apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
            } else {
                val boards=RankBoard.entries.map { board ->
                    chip(board.label,rankBoard==board) {
                        if(rankBoard!=board) {
                            // 榜单切换是一条新会话：旧游标属于上一个榜，必须丢掉。
                            rankSession.reset()
                            rankBoard=board; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                            rankSwitch?.post { if(rankSwitch?.isAttachedToWindow==true) rankSwitch?.requestFocus() }
                        }
                    }.apply {
                        nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id; minHeight=dp(36); setPadding(dp(16),dp(7),dp(16),dp(7))
                        heading.addView(this,lp(-2,-2).apply { rightMargin=dp(8) })
                        if(rankBoard==board) rankSwitch=this
                    }
                }
                boards.forEachIndexed { index,item ->
                    item.nextFocusLeftId=boards.getOrNull(index-1)?.id ?: item.id
                    item.nextFocusRightId=boards.getOrNull(index+1)?.id ?: item.id
                }
                heading.addView(Space(this),lp(0,1).apply { weight=1f })
                // 上新视图用分类接口的「最新上架 + 时间窗」，与网页榜单是两条数据通路，因此单独给入口
                newReleaseEntry=addButton(heading,"上新") {
                    newRelease=true; aiCatalog=false; aiGenre=null
                    page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
                    discoveryCursor=null; discoverySelection=newReleaseSelection(); pendingNewReleaseFocus=null
                    tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                    newReleaseEntry?.post { if(newReleaseEntry?.isAttachedToWindow==true) newReleaseEntry?.requestFocus() }
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                if(rankBoard==RankBoard.AI) {
                    // 只有 AI 剧榜提供分类浏览入口，其余榜单保持原有切换/刷新两个按钮
                    aiEntry=addButton(heading,"全部分类") {
                        aiCatalog=true; aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true)
                        aiEntry?.post { if(aiEntry?.isAttachedToWindow==true) aiEntry?.requestFocus() }
                    }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                }
                rankRefresh=addButton(heading,"刷新榜单") {
                    // 官方榜单靠游标续页；手动刷新必须丢掉旧会话，从第 1 页重新建立。
                    rankSession.reset()
                    page=1; catalogFocus=""; showCatalog(true)
                    rankRefresh?.post { if(rankRefresh?.isAttachedToWindow==true) rankRefresh?.requestFocus() }
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
            }
            container.addView(heading)
            val rankControls=(0 until heading.childCount).mapNotNull { heading.getChildAt(it) as? TextView }
            rankControls.forEachIndexed { index,item ->
                item.nextFocusLeftId=rankControls.getOrNull(index-1)?.id ?: item.id
                item.nextFocusRightId=rankControls.getOrNull(index+1)?.id ?: item.id
            }
            val defaultSource=when {
                newRelease -> "来源：红果${rankBoard.label} · 最新上架 · ${newReleaseLabel()}"
                aiCatalog -> "来源：红果AI剧分类"
                else -> "来源：红果${rankBoard.label}热播榜"
            }
            val loadingText=when { newRelease -> "正在读取最新上架…"; aiCatalog -> "正在读取AI剧分类…"; else -> "正在读取最新榜单…" }
            rankSubtitle=text(when {
                // 缓存已在展示：副标题说明正在后台更新，而不是假装从零加载
                load && rankFromCache -> "已显示上次榜单 · 正在更新…"
                load -> loadingText
                else -> ranking?.updatedText?.ifBlank { defaultSource } ?: defaultSource
            },12f,muted)
                .apply { setPadding(dp(2),dp(3),0,dp(8)); maxLines=1; ellipsize=TextUtils.TruncateAt.END }
            container.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=(when { newRelease -> newReleaseBack ?: newReleaseWindowButton; aiCatalog -> aiBack ?: aiGenreButton; else -> rankSwitch ?: newReleaseEntry ?: aiEntry })?.id ?: View.NO_ID }
        } else if(tab>2) {
            if(tab!=3 && favoriteManage) { favoriteManage=false; favoriteSelected.clear() }
            val heading=row().apply { setPadding(0,dp(4),0,dp(6)) }
            heading.addView(text(if(tab==3) if(favoriteManage) "管理收藏" else "我的收藏" else "观看历史",22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==3 && !favoriteManage) {
                favoriteCheck=addButton(heading,"检查更新") {
                    if(favoriteMonitor.running) Toast.makeText(this,"正在检查收藏更新…",Toast.LENGTH_SHORT).show()
                    else if(library.favorites().isEmpty()) Toast.makeText(this,"先收藏几部剧，再来检查更新",Toast.LENGTH_SHORT).show()
                    else { favoriteMonitor.check(force=true); Toast.makeText(this,"开始检查收藏更新…",Toast.LENGTH_SHORT).show() }
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                favoriteManageButton=addButton(heading,"管理") {
                    favoriteManage=true; favoriteSelected.clear(); showCatalog()
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
            }
            if(tab==3 && favoriteManage) {
                favoriteSelectAll=addButton(heading,if(favoriteSelected.size==catalog.size && catalog.isNotEmpty()) "取消全选" else "全选") {
                    if(favoriteSelected.size==catalog.size) favoriteSelected.clear() else catalog.forEach { favoriteSelected+=it.id }
                    catalogAdapter?.notifyDataSetChanged(); refreshFavoriteManageBar()
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
                favoriteRemove=addButton(heading,"取消收藏所选（${favoriteSelected.size}）") {
                    val targets=catalog.filter { it.id in favoriteSelected }
                    if(targets.isEmpty()) return@addButton
                    tvTools.confirm("取消收藏","将取消收藏选中的 ${targets.size} 部剧，观看记录会保留。",favoriteRemove) {
                        targets.forEach { library.toggle(it) }
                        favoriteSelected.clear(); favoriteManage=false; catalogFocus=""; showCatalog()
                    }
                }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id; isEnabled=favoriteSelected.isNotEmpty(); alpha=if(isEnabled) 1f else .4f }
                addButton(heading,"退出管理") { favoriteManage=false; favoriteSelected.clear(); showCatalog() }.apply { nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
            }
            if(tab==4 && accountManager.signedIn) {
                // 手动刷新：只重新拉取云端记录，不写本机；云端记录只读，本机记录用右侧按钮上传
                historyRefresh=addButton(heading,"刷新云端记录") {
                    if(accountManager.isBusy) Toast.makeText(this,"账号操作进行中，请稍候",Toast.LENGTH_SHORT).show()
                    else {
                        Toast.makeText(this,"正在刷新云端记录…",Toast.LENGTH_SHORT).show()
                        accountManager.refreshHistory {
                            historyRefresh?.takeIf { it.isAttachedToWindow }?.requestFocus()
                        }
                        main.postDelayed({ historyRefresh?.takeIf { it.isAttachedToWindow }?.requestFocus() }, 600)
                    }
                }.apply {
                    id=View.generateViewId()
                    nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id
                    if(accountManager.isBusy) { isEnabled=false; alpha=.4f }
                }
                // 手动同步：本机记录推到云端；云端记录只读，不在这里提供删除
                historySync=addButton(heading,"同步到云端") {
                    accountManager.syncLocalToCloud { pushed,skipped,failed ->
                        val message=buildString {
                            append(if(pushed>0) "已上传 $pushed 部到云端" else "云端已是最新，无需上传")
                            if(skipped>0) append("；$skipped 部云端已是最新")
                            if(failed>0) append("；$failed 部上传失败，可稍后重试")
                        }
                        tvTools.info("同步完成",message,historySync)
                    }
                }.apply {
                    id=View.generateViewId()
                    nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id
                    if(accountManager.isBusy) { isEnabled=false; alpha=.4f }
                }
            }
            if(tab==4 && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录",onExtra={
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    })
                }.apply { id=View.generateViewId(); nextFocusUpId=nav[TvCatalogHeader.normalize(tab)].id }
            }
            container.addView(heading)
            container.addView(text(if(tab==3) "本机收藏 · ${library.favorites().size} 部作品 · 长按剧集可管理片单" else "本机记录 · ${library.history().size} 部作品 · 确定继续上次播放",12f,muted).apply {
                setPadding(dp(2),0,0,dp(10)); maxLines=1; ellipsize=TextUtils.TruncateAt.END
            })
            if(tab==3 && !favoriteManage) {
                // 排序行：管理模式隐藏；收藏页任意位置按菜单键也可循环切换排序
                val sortRow=row().apply { setPadding(dp(10),dp(6),dp(10),dp(6)); background=TvVisual.panel(this@MainActivity) }
                sortRow.addView(text("排序",13f,muted).apply { setPadding(dp(4),0,dp(6),0) })
                val sortOptions=listOf("update" to "最近更新","watch" to "最近观看","name" to "名称")
                favoriteSortButtons=sortOptions.mapIndexed { index,(key,label) ->
                    addButton(sortRow,label,library.favoriteSort==key) {
                        if(library.favoriteSort!=key) { library.favoriteSort=key; showCatalog(); favoriteSortButtons.getOrNull(index)?.let { btn -> btn.post { if(btn.isAttachedToWindow) btn.requestFocus() } } }
                    }.apply { nextFocusUpId=favoriteCheck?.id ?: nav[TvCatalogHeader.normalize(tab)].id; minHeight=dp(36); setPadding(dp(12),dp(6),dp(12),dp(6)) }
                }
                // 行内左右显式接线：最右不再被默认焦点搜索带到 tab 栏
                favoriteSortButtons.forEachIndexed { index,btn ->
                    btn.nextFocusLeftId=favoriteSortButtons[(index-1).coerceAtLeast(0)].id
                    btn.nextFocusRightId=favoriteSortButtons[(index+1).coerceAtMost(favoriteSortButtons.lastIndex)].id
                }
                favoriteSortFirst=favoriteSortButtons.firstOrNull()
                container.addView(sortRow)
            }
            if(tab==3) {
                favoriteStatus=text(if(favoriteManage) "已选 ${favoriteSelected.size} 部 · 确认键勾选/取消 · 返回键退出管理" else favoriteMonitor.status,12f,muted).apply { setPadding(dp(2),dp(6),0,dp(6)); maxLines=1; ellipsize=TextUtils.TruncateAt.END }; container.addView(favoriteStatus)
            }
        }
        // 三栏/两栏搜索页的结果区已在 contentRow 内挂载，其余页面走全宽纵向容器
        val body=searchBody ?: column().also { container.addView(it,lp(-1,0).apply { weight=1f }) }
        if(tab==3) {
            val groups=library.groups()
            if(favoriteGroupFilter!=null && favoriteGroupFilter !in groups) favoriteGroupFilter=null
            if(groups.isNotEmpty() && !favoriteManage) {
                val filterRow=row().apply { setPadding(0,dp(6),0,dp(6)) }
                val options=listOf<String?>(null)+groups
                favoriteFilterButtons=options.mapIndexed { index,group ->
                    addButton(filterRow,group ?: "全部",favoriteGroupFilter==group) {
                        favoriteGroupFilter=group; favoriteFilterFocus=index; showCatalog(focusFilter=true)
                    }.apply { nextFocusUpId=favoriteSortFirst?.id ?: favoriteCheck?.id ?: nav[TvCatalogHeader.normalize(tab)].id }
                }
                favoriteFilterFirst=favoriteFilterButtons.first()
                val filterScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false; addView(filterRow) }
                container.addView(filterScroll,container.indexOfChild(favoriteStatus))
            }
        }
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=1; hasMore=false
        }
        if(load && tab==1 && advancedSearch) {
            loadDiscovery(body)
        } else if(load && tab==2 && newRelease) {
            loadNewRelease(body)
        } else if(load) {
            if(tab==0) catalogGrid(body,focusNav,focusType,loading=true)
            // 榜单缓存已带内容：直接渲染上次榜单，联网结果回来再提示换新
            else if(tab==2 && catalog.isNotEmpty()) {
                catalogGrid(body,focusNav,focusType)
                // 从上新退回时焦点落回「上新」入口而不是首张卡片，与联网成功路径同一处理
                if(pendingNewReleaseFocus!=null) catalogRecycler?.postDelayed({ consumePendingNewReleaseFocus() },80)
            }
            else { message(body,"正在加载…"); if(focusType) typeButtons[library.contentType]?.requestFocus() else nav[TvCatalogHeader.normalize(tab)].requestFocus() }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
            val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
            val requestAi=if(requestTab==2) aiCatalog else false; val requestAiGenre=if(requestAi) aiGenre else null
            work({
                val result=when(requestTab) {
                    0 -> if(suixinMode) suixinPage(requestPage) else repository.home(requestPage,requestType,requestGenre)
                    2 -> if(requestAi) repository.home(requestPage,ContentType.AI,requestAiGenre) else repository.ranking(requestBoard,requestPage,rankSession)
                    else -> repository.search(requestQuery,requestPage,requestType)
                }
                result
            }, { result ->
                if(requestTab==0 && requestPage==1 && requestGenre==null) library.cacheHome(requestType,result.items,result.hasMore)
                // 榜单第 1 页到手先落缓存，无论界面是否立即换新
                if(requestTab==2 && requestPage==1 && !requestAi) library.cacheRank(requestBoard,result.items,result.hasMore,result.ranking)
                if(requestTab==0 && requestPage==1 && requestGenre==null && (homeFromCache || tvTools.showing)) {
                    homeScreen?.refresh?.text="查看新推荐"
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else if(requestTab==2 && requestPage==1 && !requestAi && rankFromCache) {
                    // 缓存刷新保留正在看的旧榜；手动换新时走差异更新，不重建网格和图片控件。
                    (rankRefresh as? TextView)?.text="榜单已更新 · 按确认查看"
                    rankRefresh?.setOnClickListener {
                        // 缓存展示期间可能已翻页，换新后从第 1 页重新起算，避免续页跳页
                        rememberCatalogFocus()
                        catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; page=1; rankFromCache=false
                        rankSubtitle?.text=ranking?.updatedText?.ifBlank { "来源：红果${rankBoard.label}热播榜" } ?: "来源：红果${rankBoard.label}热播榜"
                        val current=catalogAdapter
                        if(current!=null && current.cols==gridColumns && catalogRecycler?.parent===body) {
                            current.replaceItems(result.items,if(hasMore) FOOTER_NONE else FOOTER_END)
                            restoreCatalogFocus()
                        } else {
                            body.removeAllViews(); catalogGrid(body,focusNav=true,focusType=false)
                        }
                        rankRefresh?.post { if(rankRefresh?.isAttachedToWindow==true) rankRefresh?.requestFocus() }
                    }
                } else {
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    // 排行榜/新剧网格：先并发预加载首屏所有封面进 Glide 缓存，再创建视图，
                    // RecyclerView onBind 时全部命中缓存秒开，视觉效果跟推荐页 loadArtwork 一致。
                    if(requestTab==2) artwork.preload(result.items.map { it.cover })
                    // 手机输入的精确剧名搜索零结果时自动恢复联想，帮助用户纠正剧名
                    if(requestTab==1 && result.items.isEmpty()) { scheduleSuggest() }
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    if(requestTab==2) rankSubtitle?.text=if(requestAi) "来源：红果AI剧分类" else ranking?.updatedText?.ifBlank { "来源：红果${rankBoard.label}热播榜" } ?: "来源：红果${rankBoard.label}热播榜"
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                    if(requestTab==1) loadSearchArtwork()
                    // 从「上新」退回榜单后，焦点要落回「上新」入口而不是首张卡片
                    if(requestTab==2 && pendingNewReleaseFocus!=null) catalogRecycler?.postDelayed({ consumePendingNewReleaseFocus() },80)
                    // 搜索完成后焦点落结果首卡；零结果回输入框，避免页面重建后焦点丢失
                    if(requestTab==1 && searchFocusPending) {
                        searchFocusPending=false
                        if(result.items.isEmpty()) searchInput?.requestFocus()
                        else catalogRecycler?.post { (resultFirstCard ?: searchInput)?.requestFocus() }
                    }
                }
            }, { problem ->
                if(requestTab==0 && requestPage==1 && requestGenre==null) {
                    homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "更新失败 · 重试" else "加载失败 · 重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                // 上次榜单还在展示：保留内容不清空，副标题提示联网失败，按钮保持可重试
                if(requestTab==2 && !requestAi && rankFromCache) {
                    rankSubtitle?.text="榜单更新失败 · 当前显示上次榜单"
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text=if(requestAi) "AI剧分类加载失败" else "榜单加载失败"; if(requestTab==2) pendingNewReleaseFocus=null
                // 搜索失败时焦点回输入框，避免页面重建后焦点丢失
                if(requestTab==1 && searchFocusPending) { searchFocusPending=false; searchInput?.requestFocus() }
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                }
            })
        } else catalogGrid(body,focusNav,focusType,focusFilter=focusFilter)
        // 页面重建后初始化联想区：回显点击联想词前的列表，或无词时收起中栏
        if(tab==1 && !advancedSearch) fetchSuggest()
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,focusFilter: Boolean=false) {
        if(tab==0 && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
        collectionBack=null; genreClear=null
        if(collection!=null) {
            val heading=row()
            collectionBack=PageBackButton.addTo(heading,"返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[TvCatalogHeader.normalize(tab)].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
        }
        val focusGenre=if(tab==2 && aiCatalog) aiGenre else genre
        catalogPageKey="$tab:${if(aiCatalog) "ai" else ""}:${if(newRelease) "new-${newReleaseWindow}" else ""}:${collection.orEmpty()}:${focusGenre?.slug.orEmpty()}:$page:$query${if(tab==1 && advancedSearch) ":discovery:${discoverySelection.values}" else ""}"
        if(tab==0 && genre!=null) {
            val bar=row().apply { setPadding(0,0,0,dp(6)) }
            bar.addView(text("题材 · ${genre?.name}",16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            genreClear=addButton(bar,"全部题材") { genre=null; page=1; catalogFocus=""; tabState.remove(0); tabFocus.remove(0); showCatalog(true) }
                .apply { nextFocusUpId=genreButton?.id ?: typeButtons[library.contentType]?.id ?: nav[TvCatalogHeader.normalize(tab)].id }
            body.addView(bar)
            typeButtons.values.forEach { it.nextFocusDownId=genreClear!!.id }
            genreButton?.nextFocusDownId=genreClear!!.id
        }
        if(tab==2 && aiCatalog && aiGenre!=null) {
            val bar=row().apply { setPadding(0,0,0,dp(6)) }
            bar.addView(text("题材 · ${aiGenre?.name}",16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            genreClear=addButton(bar,"全部题材") { aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true) }
                .apply { nextFocusUpId=aiGenreButton?.id ?: nav[TvCatalogHeader.normalize(tab)].id }
            body.addView(bar)
            aiGenreButton?.nextFocusDownId=genreClear!!.id
        }
        selectionPreview=null
        val displayed=mutableListOf<Series>()
        displayed+=if(localCatalog()) catalog else if(tab==0) catalog.filterNot { it.id in library.hidden() } else catalog
        // 最近观看页：本机记录在前；登录且有云端记录时追加「云端记录」区，同剧可在两区各出现一次
        val cloudRows=if(tab==4 && collection==null && accountManager.signedIn) accountManager.cloudRows() else emptyList()
        val localCount=displayed.size
        if(cloudRows.isNotEmpty()) displayed+=cloudRows.map { it.series }
        if(displayed.isEmpty()) {
            message(body,if(loading) "正在加载推荐…" else when(tab) { 1 -> if(advancedSearch) "当前条件下没有内容，可以换个条件或点「重置」" else if(query.isBlank()) "输入名称关键词，或用字母键拼首拼选择联想词" else "没有找到相关${library.contentType.label}，换个关键词试试"; 2 -> if(newRelease) "${newReleaseLabel()}内暂无${rankBoard.label}上新，按「时间」换成更长的范围" else "本页没有更多内容"; 3 -> if(favoriteGroupFilter!=null) "「$favoriteGroupFilter」分组暂无收藏" else "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 4 -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" })
            wireSearchPanel(null)
            if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else nav[TvCatalogHeader.normalize(tab)].requestFocus()
            return
        }
        val cols=if(tab==1 && !advancedSearch) searchColumns else gridColumns
        val adapter=CatalogAdapter(displayed,cols)
        if(cloudRows.isNotEmpty()) {
            adapter.cloudRows=cloudRows
            adapter.cloudStart=localCount
            adapter.headers=buildMap {
                if(localCount>0) put(0,"本机记录")
                put(localCount+if(localCount>0) 1 else 0,"云端记录")
            }
        }
        val grid=GridLayoutManager(this,cols)
        grid.spanSizeLookup=object: GridLayoutManager.SpanSizeLookup() { override fun getSpanSize(position: Int)=if(adapter.isFooter(position) || adapter.isHeader(position)) cols else 1 }
        val recycler=RecyclerView(this).apply {
            id=View.generateViewId(); clipToPadding=false; isVerticalScrollBarEnabled=false; itemAnimator=null
            layoutManager=grid; this.adapter=adapter
            // Focus entering the grid lands on the first visible card, keeping the user's place.
            isFocusable=true; isFocusableInTouchMode=true
            setOnFocusChangeListener { _,focused -> if(focused) {
                post {
                    // 布局期间网格会短暂拿到焦点；卡片已获焦后不能再被旧回调拉到首行。
                    if(catalogRecycler===this && isFocused && pendingCatalogFocusPos==RecyclerView.NO_POSITION) {
                        gridFocusOwned=true; restoreCatalogFocus()
                    }
                }
            } }
            addOnItemTouchListener(object: RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView,event: MotionEvent): Boolean {
                    if(event.actionMasked==MotionEvent.ACTION_DOWN && catalogRecycler===rv) {
                        // 手势滚动接管位置，分页回调不能再把焦点补回手势前的卡片。
                        gridFocusOwned=false; pendingCatalogFocusPos=RecyclerView.NO_POSITION
                    }
                    return false
                }
            })
            // 下键统一在 MainActivity.dispatchKeyEvent 里处理：网格重排/分页时 View 层收不到按键
        }
        catalogRecycler=recycler; catalogAdapter=adapter; catalogFocusPos=RecyclerView.NO_POSITION
        pendingCatalogFocusPos=RecyclerView.NO_POSITION; gridFocusOwned=false
        val focusListener=ViewTreeObserver.OnGlobalFocusChangeListener { o,n ->
            if(catalogRecycler===recycler && screen=="catalog") {
                if(n!=null && inCatalogGrid(n,recycler)) {
                    val pos=recycler.findContainingViewHolder(n)?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                    if(pos!=RecyclerView.NO_POSITION && !adapter.isHeader(pos) && !adapter.isFooter(pos)) {
                        gridFocusOwned=true
                        if(pendingCatalogFocusPos==RecyclerView.NO_POSITION || pendingCatalogFocusPos==pos) {
                            catalogFocusPos=pos; pendingCatalogFocusPos=RecyclerView.NO_POSITION
                        }
                    }
                } else if(pendingCatalogFocusPos!=RecyclerView.NO_POSITION && gridFocusOwned) {
                    // 显式移行尚未完成，保留目标，由 moveGridFocus 在布局后落焦。
                } else if((o==null || n==null) && gridFocusOwned) restoreCatalogFocus()
                else if(o!=null && inCatalogGrid(o,recycler)) gridFocusOwned=false
            }
        }
        recycler.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        recycler.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) {
                if(view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            }
        })
        adapter.footer=when {
            localCatalog() -> if(collection=="hot" && collectionReturn?.hasMore==true) FOOTER_MORE_HOT else FOOTER_NONE
            hasMore -> FOOTER_NONE
            else -> FOOTER_END
        }
        body.addView(recycler,lp(-1,0).apply { weight=1f })
        nav.forEach {
            it.nextFocusDownId=segmentFirst?.id ?: when {
                tab==2 -> (when { newRelease -> newReleaseBack ?: newReleaseWindowButton ?: rankRefresh; aiCatalog -> aiBack ?: aiGenreButton ?: rankRefresh; else -> rankSwitch ?: newReleaseEntry ?: aiEntry ?: rankRefresh })?.id ?: recycler.id
                tab<=1 -> (if(tab==1) filterToggle else typeButtons[library.contentType])?.id ?: recycler.id
                else -> favoriteCheck?.id ?: historyRefresh?.id ?: historySync?.id ?: historyManage?.id ?: recycler.id
            }
        }
        rankSwitch?.nextFocusDownId=recycler.id; rankRefresh?.nextFocusDownId=recycler.id; historyManage?.nextFocusDownId=recycler.id
        historyRefresh?.nextFocusDownId=recycler.id; historySync?.nextFocusDownId=recycler.id
        historyRefresh?.nextFocusRightId=historySync?.id ?: historyManage?.id ?: View.NO_ID
        historySync?.nextFocusLeftId=historyRefresh?.id ?: View.NO_ID
        historySync?.nextFocusRightId=historyManage?.id ?: View.NO_ID
        historyManage?.nextFocusLeftId=historySync?.id ?: historyRefresh?.id ?: View.NO_ID
        // 收藏页头部按钮行：检查更新/管理左右互连，↓ 回排序行；管理模式按钮 ↓ 直接进网格
        favoriteCheck?.nextFocusDownId=favoriteSortFirst?.id ?: favoriteFilterFirst?.id ?: recycler.id
        favoriteCheck?.nextFocusRightId=favoriteManageButton?.id ?: View.NO_ID
        favoriteManageButton?.nextFocusLeftId=favoriteCheck?.id ?: View.NO_ID
        favoriteManageButton?.nextFocusDownId=favoriteSortFirst?.id ?: favoriteFilterFirst?.id ?: recycler.id
        favoriteSelectAll?.nextFocusDownId=recycler.id; favoriteRemove?.nextFocusDownId=recycler.id
        aiEntry?.nextFocusDownId=recycler.id; aiBack?.nextFocusDownId=recycler.id
        newReleaseEntry?.nextFocusDownId=recycler.id; newReleaseBack?.nextFocusDownId=recycler.id
        newReleaseWindowButton?.nextFocusDownId=recycler.id
        // 排行榜页头按钮行按真实排列顺序左右互连（上新 / 全部分类 / 看X榜 / 刷新，上新页则是 返回 / 时间 / 刷新）。
        // 只靠框架 focusSearch 时，最左侧的「上新」常被跳过，从网格 ↑ 回按钮后摸不回去；
        // 两端的键设成自己，避免按到头时焦点乱跳到右上角工具区或直接钻进网格。
        val rankControls=(rankSwitch?.parent ?: newReleaseBack?.parent ?: aiBack?.parent) as? LinearLayout
        val rankButtons=if(rankControls!=null) (0 until rankControls.childCount).mapNotNull { rankControls.getChildAt(it) as? TextView }
            else listOfNotNull(if(newRelease) newReleaseBack else newReleaseEntry,newReleaseWindowButton,aiEntry ?: aiBack,rankSwitch,rankRefresh)
        rankButtons.forEachIndexed { index,item ->
            item.nextFocusLeftId=rankButtons.getOrNull(index-1)?.id ?: item.id
            item.nextFocusRightId=rankButtons.getOrNull(index+1)?.id ?: item.id
            item.nextFocusDownId=recycler.id
        }
        favoriteFilterButtons.forEach { it.nextFocusDownId=recycler.id }
        collectionBack?.nextFocusDownId=recycler.id; genreClear?.nextFocusDownId=recycler.id
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: genreClear?.id ?: recycler.id }
        genreButton?.nextFocusDownId=genreClear?.id ?: recycler.id
        aiGenreButton?.nextFocusDownId=genreClear?.id ?: recycler.id
        searchInput?.nextFocusDownId=azFirst?.id ?: recycler.id
        searchButton?.nextFocusDownId=azFirst?.id ?: recycler.id
        searchActions.forEach { it.nextFocusDownId=azFirst?.id ?: recycler.id }
        wireSearchPanel(recycler)
        wireFilterPanelToGrid(recycler)
        if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) nav[TvCatalogHeader.normalize(tab)].requestFocus()
        else if(focusFilter && favoriteFilterButtons.isNotEmpty()) favoriteFilterButtons[favoriteFilterFocus.coerceIn(0,favoriteFilterButtons.size-1)].requestFocus()
        else {
            val found=displayed.indexOfFirst { it.id==catalogFocus }
            val target=if(found>=0) found else scrollPositions[catalogPageKey]?.coerceIn(0,displayed.size-1) ?: 0
            val targetAdapter=adapter.adapterPosition(target)
            grid.scrollToPositionWithOffset(targetAdapter,0)
            recycler.post {
                if(catalogRecycler===recycler && catalogFocusPos==RecyclerView.NO_POSITION && pendingCatalogFocusPos==RecyclerView.NO_POSITION) {
                    recycler.findViewHolderForAdapterPosition(targetAdapter)?.itemView?.requestFocus()
                }
            }
        }
    }
    private fun inCatalogGrid(view: View,recycler: RecyclerView): Boolean {
        var cur: View?=view
        while(cur!=null) { if(cur===recycler) return true; cur=cur.parent as? View }
        return false
    }
    /**
     * 网格内的下键统一在这里处理：算下一行同列格子并直接 requestFocus。
     * 交给框架 focusSearch 时，分页/重排会把被聚焦卡回收，焦点被静默清空后回落到窗口第一个可聚焦项（顶栏「首页」），
     * 表现为「首页 ↔ 卡片」来回跳。按记住的位置下移则焦点始终留在网格里。
     */
    private fun handleCatalogDown(event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN && screen=="catalog" && gridFocusOwned
            && randomDialog?.isShowing!=true && exitDialog?.isShowing!=true) {
            val recycler=catalogRecycler; val adapter=catalogAdapter
            if(recycler!=null && adapter!=null) {
                val focused=recycler.focusedChild
                var pos=pendingCatalogFocusPos
                if(pos==RecyclerView.NO_POSITION && focused!=null) pos=recycler.getChildAdapterPosition(focused)
                if(pos==RecyclerView.NO_POSITION) pos=catalogFocusPos
                if(pos!=RecyclerView.NO_POSITION && !adapter.isHeader(pos) && !adapter.isFooter(pos)) {
                    val index=adapter.displayedIndex(pos)
                    if(index in 0 until adapter.displayed.size) {
                        val cols=(recycler.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                        val target=index+cols
                        if(target<=adapter.displayed.size-1) moveGridFocus(adapter.adapterPosition(target))
                        return true
                    }
                }
            }
        }
        return false
    }
    /**
     * 网格内上键：和 [handleCatalogDown] 对称，按「上一行同列」移动。
     *
     * 之前上键交给框架 focusSearch：最近观看页的「本机记录」分节标题插在卡片前面、本身不可聚焦，
     * RecyclerView 从首行卡片往上搜只能搜到它，于是按键被吞掉、按了毫无反应。
     * 这里首行显式跳到页头按钮（刷新云端记录 / 同步到云端 / 管理等）。
     */
    private fun handleCatalogUp(event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_DPAD_UP && screen=="catalog" && gridFocusOwned
            && randomDialog?.isShowing!=true && exitDialog?.isShowing!=true) {
            val recycler=catalogRecycler; val adapter=catalogAdapter
            if(recycler!=null && adapter!=null) {
                val focused=recycler.focusedChild
                var pos=pendingCatalogFocusPos
                if(pos==RecyclerView.NO_POSITION && focused!=null) pos=recycler.getChildAdapterPosition(focused)
                if(pos==RecyclerView.NO_POSITION) pos=catalogFocusPos
                if(pos!=RecyclerView.NO_POSITION && !adapter.isHeader(pos) && !adapter.isFooter(pos)) {
                    val index=adapter.displayedIndex(pos)
                    if(index in 0 until adapter.displayed.size) {
                        val cols=(recycler.layoutManager as? GridLayoutManager)?.spanCount ?: 1
                        val target=index-cols
                        if(target>=0 && target in adapter.displayed.indices) moveGridFocus(adapter.adapterPosition(target))
                        else {
                            // 首行：先回本页的菜单按钮（排行榜的看榜/刷新、搜索的输入行），
                            // 再按键才回顶栏；不再一步跳到顶部菜单栏。
                            val up=when(tab) {
                                2 -> (when { newRelease -> newReleaseBack ?: newReleaseWindowButton ?: rankRefresh; aiCatalog -> aiEntry ?: aiGenreButton; else -> rankSwitch ?: rankRefresh })
                                    ?: nav.getOrNull(TvCatalogHeader.normalize(tab))
                                1 -> searchInput ?: searchButton ?: searchActions.firstOrNull()?.takeIf { it.isFocusable }
                                    ?: typeButtons[library.contentType] ?: nav.getOrNull(TvCatalogHeader.normalize(tab))
                                else -> historyRefresh ?: historySync ?: historyManage ?: favoriteCheck ?: favoriteSortFirst
                                    ?: genreClear ?: collectionBack ?: typeButtons[library.contentType]
                                    ?: segmentFirst ?: nav.getOrNull(TvCatalogHeader.normalize(tab))
                            }
                            if(up==null) return false
                            pendingCatalogFocusPos=RecyclerView.NO_POSITION; gridFocusOwned=false
                            up.requestFocus()
                        }
                        return true
                    }
                }
            }
        }
        return false
    }
    /** 显式移行先记下目标，回收和分页期间的补焦点只能服从这个目标。 */
    private fun moveGridFocus(pos: Int) {
        val recycler=catalogRecycler ?: return
        val adapter=catalogAdapter ?: return
        if(pos !in 0 until adapter.itemCount || adapter.isHeader(pos) || adapter.isFooter(pos)) return
        pendingCatalogFocusPos=pos; catalogFocusPos=pos; gridFocusOwned=true
        fun focusTarget() {
            if(screen=="catalog" && recycler===catalogRecycler && gridFocusOwned && pendingCatalogFocusPos==pos) {
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
            }
        }
        if(recycler.findViewHolderForAdapterPosition(pos)!=null) { focusTarget(); return }
        // 优先只滚一行，让目标进入视口；目标已布局就同步落焦，不留下抢焦点的旧 post。
        val focused=recycler.focusedChild
        if(focused!=null) {
            val margin=(focused.layoutParams as? RecyclerView.LayoutParams)?.let { it.topMargin+it.bottomMargin } ?: 0
            val step=focused.height+margin
            val current=recycler.getChildAdapterPosition(focused)
            val downward=current!=RecyclerView.NO_POSITION && pos>current
            recycler.scrollBy(0,if(downward) step else -step)
        }
        if(recycler.findViewHolderForAdapterPosition(pos)!=null) { focusTarget(); return }
        // 焦点已被回收或快速连按跨了多行时，等目标真正完成布局再落焦。
        recycler.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                view.removeOnLayoutChangeListener(this)
                focusTarget()
            }
        })
        recycler.scrollToPosition(pos)
    }
    /** 分页只记住有效的当前焦点，不能覆盖尚未落焦的移动目标。 */
    private fun rememberCatalogFocus() {
        val recycler=catalogRecycler ?: return
        if(!gridFocusOwned || pendingCatalogFocusPos!=RecyclerView.NO_POSITION) return
        val pos=recycler.focusedChild?.let { recycler.getChildAdapterPosition(it) } ?: RecyclerView.NO_POSITION
        if(pos!=RecyclerView.NO_POSITION) catalogFocusPos=pos
    }
    /** 补焦点只在当前视口内进行，绝不为恢复已回收的旧卡片而反向滚动列表。 */
    private fun restoreCatalogFocus() {
        val recycler=catalogRecycler ?: return
        recycler.post {
            if(screen!="catalog" || recycler!==catalogRecycler || !gridFocusOwned || !recycler.hasWindowFocus()
                || recycler.scrollState!=RecyclerView.SCROLL_STATE_IDLE || pendingCatalogFocusPos!=RecyclerView.NO_POSITION
                || recycler.focusedChild!=null) return@post
            val grid=recycler.layoutManager as? GridLayoutManager ?: return@post
            val adapter=catalogAdapter ?: return@post
            val first=grid.findFirstVisibleItemPosition()
            val last=grid.findLastVisibleItemPosition()
            if(first==RecyclerView.NO_POSITION || last==RecyclerView.NO_POSITION) return@post
            val preferred=catalogFocusPos
            val target=if(preferred in first..last && !adapter.isHeader(preferred) && !adapter.isFooter(preferred)) preferred
                else (first..last).firstOrNull { !adapter.isHeader(it) && !adapter.isFooter(it) } ?: return@post
            recycler.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        }
    }
    private fun maybeLoadMore() {
        val adapter=catalogAdapter ?: return
        if(randomDialog?.isShowing==true || loadingMore || !hasMore || localCatalog() || screen!="catalog") return
        if((tab==1 && advancedSearch) || (tab==2 && newRelease)) { loadMoreDiscovery(adapter); return }
        // 榜单走官方接口，每页约 10 条：按条数收尾，避免页数上限把榜单截短。
        val rankTab=tab==2 && !aiCatalog
        val capped=if(rankTab) !rankCanLoadMore(catalog.size,page) else page>=waterfallMaxPages || catalog.size>=waterfallMaxPages*20
        if(capped) { hasMore=false; rememberCatalogFocus(); adapter.updateFooter(FOOTER_END); restoreCatalogFocus(); return }
        val requestTab=tab; val requestPage=page+1; val requestQuery=query; val requestType=library.contentType
        val requestBoard=rankBoard; val requestGenre=if(requestTab==0) genre else null
        val requestAi=if(requestTab==2) aiCatalog else false; val requestAiGenre=if(requestAi) aiGenre else null
        loadingMore=true; rememberCatalogFocus(); adapter.updateFooter(FOOTER_LOADING); restoreCatalogFocus()
        work({
            when(requestTab) {
                0 -> if(suixinMode) suixinPage(requestPage) else repository.home(requestPage,requestType,requestGenre)
                2 -> if(requestAi) repository.home(requestPage,ContentType.AI,requestAiGenre) else repository.ranking(requestBoard,requestPage,rankSession)
                else -> repository.search(requestQuery,requestPage,requestType)
            }
        }, { result ->
            loadingMore=false
            page=requestPage
            hasMore=result.hasMore
            result.ranking?.let { r -> ranking=ComicRanking((ranking?.positions.orEmpty())+r.positions,r.totalPages,r.updatedText.ifBlank { ranking?.updatedText.orEmpty() }) }
            if(requestTab==2 && !requestAi) rankSubtitle?.text=ranking?.updatedText?.ifBlank { "来源：红果${rankBoard.label}热播榜" } ?: "来源：红果${rankBoard.label}热播榜"
            val known=catalog.mapTo(HashSet()) { it.id }
            val fresh=result.items.filter { it.id !in known }
            // 续页只追加新条目：与服务端 filter_ids 去重口径一致，也保证 catalog 与 displayed 同步。
            catalog=catalog+fresh
            val current=catalogAdapter
            if(current!=null) {
                val visible=fresh.filter { tab!=0 || it.id !in library.hidden() }
                val insertAt=current.displayed.size
                current.displayed.addAll(visible)
                if(visible.isNotEmpty()) current.notifyItemRangeInserted(insertAt,visible.size)
                val canContinue=if(requestTab==2 && !requestAi) rankCanLoadMore(catalog.size,page)
                    else page<waterfallMaxPages && catalog.size<waterfallMaxPages*20
                current.updateFooter(if(hasMore && canContinue) FOOTER_NONE else FOOTER_END)
                restoreCatalogFocus()
            }
            if(requestTab==1) loadSearchArtwork()
        }, { problem ->
            loadingMore=false
            if(problem is SearchSessionExpiredException) {
                page=1; catalogFocus=""
                Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                showCatalog(true)
            } else {
                Toast.makeText(this,"加载失败，继续浏览会自动重试",Toast.LENGTH_SHORT).show()
                catalogAdapter?.updateFooter(FOOTER_ERROR)
                restoreCatalogFocus()
            }
        })
    }
    private inner class CardHolder(val card: LinearLayout,val artwork: FrameLayout,val image: ImageView,val rank: TextView,val title: TextView,val badge: TextView): RecyclerView.ViewHolder(card) {
        var boundId=""
    }
    private inner class CatalogAdapter(val displayed: MutableList<Series>,val cols: Int): RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var footer=FOOTER_NONE
        /** 分节标题：key 为 adapter 位置；仅最近观看页的本机/云端双区使用，其余页面为空。 */
        var headers: Map<Int,String> = emptyMap()
        /** displayed 中云端区的起始下标；云端卡角标、徽标与点击续播都走云端进度。 */
        var cloudStart=Int.MAX_VALUE
        var cloudRows: List<CloudProgress> = emptyList()
        /** 电视榜单刷新复用已有卡片；相同条目不重绑，封面控件不会被整页销毁。 */
        fun replaceItems(next: List<Series>, nextFooter: Int) {
            val previous=displayed.toList()
            val previousFooter=footer
            val diff=DiffUtil.calculateDiff(object: DiffUtil.Callback() {
                override fun getOldListSize()=previous.size+headers.size+if(previousFooter!=FOOTER_NONE) 1 else 0
                override fun getNewListSize()=next.size+headers.size+if(nextFooter!=FOOTER_NONE) 1 else 0
                override fun areItemsTheSame(oldPosition: Int,newPosition: Int): Boolean {
                    val oldFooter=oldPosition==previous.size+headers.size
                    val newFooter=newPosition==next.size+headers.size
                    return oldFooter==newFooter && (oldFooter || isHeader(oldPosition)==isHeader(newPosition) &&
                        (isHeader(oldPosition) || previous[displayedIndex(oldPosition)].id==next[displayedIndex(newPosition)].id))
                }
                override fun areContentsTheSame(oldPosition: Int,newPosition: Int): Boolean {
                    if(isHeader(oldPosition) || isHeader(newPosition)) return headers[oldPosition]==headers[newPosition]
                    if(isFooter(oldPosition) || isFooter(newPosition)) return previousFooter==nextFooter
                    val before=previous[displayedIndex(oldPosition)]; val after=next[displayedIndex(newPosition)]
                    return before==after && ranking?.positions?.get(before.id)==ranking?.positions?.get(after.id)
                }
            })
            displayed.clear(); displayed.addAll(next); footer=nextFooter
            diff.dispatchUpdatesTo(this)
        }
        fun isHeader(position: Int)=headers.containsKey(position)
        fun isFooter(position: Int)=footer!=FOOTER_NONE && position==displayed.size+headers.size
        /** adapter 位置 → displayed 下标（前面每有一个分节标题就减一）。 */
        fun displayedIndex(position: Int): Int {
            var index=position
            for(key in headers.keys) if(key<position) index--
            return index
        }
        /** displayed 下标 → adapter 位置。 */
        fun adapterPosition(index: Int): Int {
            var position=index
            for(key in headers.keys.sorted()) if(key<=position) position++
            return position
        }
        override fun getItemCount()=displayed.size+headers.size+if(footer!=FOOTER_NONE) 1 else 0
        override fun getItemViewType(position: Int)=if(isHeader(position)) TYPE_HEADER else if(isFooter(position)) TYPE_FOOTER else TYPE_CARD
        fun updateFooter(next: Int) {
            val footerPos=displayed.size+headers.size
            if(footer==next) { if(next!=FOOTER_NONE) notifyItemChanged(footerPos); return }
            val had=footer!=FOOTER_NONE; footer=next
            if(next==FOOTER_NONE) notifyItemRemoved(footerPos)
            else if(had) notifyItemChanged(footerPos)
            else notifyItemInserted(footerPos)
        }
        override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
            if(viewType==TYPE_HEADER) {
                val label=text("",16f,muted).apply { setTypeface(null,Typeface.BOLD); setPadding(dp(4),dp(10),0,dp(6)) }
                label.layoutParams=RecyclerView.LayoutParams(-1,-2)
                return object: RecyclerView.ViewHolder(label) {}
            }
            if(viewType==TYPE_FOOTER) {
                val frame=FrameLayout(this@MainActivity)
                frame.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
                return object: RecyclerView.ViewHolder(frame) {}
            }
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(6)); focusStyle(card)
            // 海报容器按自身实际测量宽度定高，回收复用、列宽变化都不会出现同屏高低不一
            val artwork=object: FrameLayout(this@MainActivity) {
                override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                    val w=MeasureSpec.getSize(widthMeasureSpec)
                    super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((w/TvStyle.POSTER_ASPECT).toInt(),MeasureSpec.EXACTLY))
                }
            }
            artwork.background=TvStyle.shape(this@MainActivity,surface,radius=7)
            artwork.clipToOutline=true
            val image=ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            artwork.addView(image,FrameLayout.LayoutParams(-1,-1))
            val rank=TextView(this@MainActivity).apply { textSize=11f; setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(4),dp(8),dp(4)) }
            artwork.addView(rank,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
            // 部分机型单行/两行标题实测行高不一致，minLines 保底失效；用换行探针实测两行高度并固定，热度/集数标签才能对齐
            val title=text("测\n测",14f).apply { gravity=Gravity.TOP; includeFontPadding=true; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) }
            title.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            val titleHeight=title.measuredHeight
            val badge=text("",12f).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
            card.addView(artwork,lp(-1,-2))
            card.addView(title,lp(-1,titleHeight))
            card.addView(badge,lp(-1,-2))
            card.layoutParams=RecyclerView.LayoutParams(-1,-2).apply { rightMargin=dp(14); bottomMargin=dp(10) }
            return CardHolder(card,artwork,image,rank,title,badge)
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
            if(getItemViewType(position)==TYPE_HEADER) { (holder.itemView as TextView).text=headers[position]; return }
            if(getItemViewType(position)==TYPE_FOOTER) { bindFooter(holder.itemView as FrameLayout); return }
            holder as CardHolder
            val di=displayedIndex(position)
            val item=displayed[di]
            val sameSeries=holder.boundId==item.id
            holder.boundId=item.id
            val cloudRow=if(di>=cloudStart) cloudRows.getOrNull(di-cloudStart) else null
            if(di==0) resultFirstCard=holder.card
            val card=holder.card
            card.tag=item.id; card.contentDescription=item.title
            // 排名或文案变化只更新文字；同一部剧的旧图保留到新封面就绪。
            if(sameSeries) updatePhoneCover(holder.image,item.cover) else loadCoverFrom(holder.image,item.cover,"bind")
            // AI 剧分类复用的是片库卡片，没有名次与热度，按普通片库卡片展示
            val ranked=tab==2 && ranking!=null
            val rankPosition=if(ranked) ranking?.positions?.get(item.id) else null
            if(ranked) {
                val rank=rankPosition?.rank
                val rankLabel=if(rank!=null) "第 $rank 名" else "名次暂无"
                val styled=android.text.SpannableString(rankLabel)
                if(rank!=null) {
                    val start=rankLabel.indexOf(' ')+1; val end=rankLabel.lastIndexOf(' ')
                    styled.setSpan(android.text.style.RelativeSizeSpan(1.45f),start,end,0)
                    styled.setSpan(android.text.style.StyleSpan(Typeface.BOLD),start,end,0)
                }
                holder.rank.text=styled
                holder.rank.setTextColor(if(rank!=null && rank<=3) TvStyle.accent else Color.WHITE)
                holder.rank.background=TvStyle.shape(this@MainActivity,Color.argb(205,15,18,24),radius=6)
                holder.rank.visibility=View.VISIBLE
                card.contentDescription="$rankLabel，${item.title}，${rankPosition?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
            } else if(cloudRow!=null) {
                // 云端记录角标：与本机记录同剧并存时一眼可辨
                holder.rank.text="云端"
                holder.rank.setTextColor(white)
                holder.rank.background=rounded(accent,6)
                holder.rank.visibility=View.VISIBLE
                card.contentDescription="云端记录，${item.title}，第 ${cloudRow.episodeIndex+1} 集"
            } else holder.rank.visibility=View.GONE
            holder.title.text=item.title
            val progress=if(tab==4 || collection=="resume") library.progress(item.id) else null
            // 收藏管理模式：角标前缀 ✓ 表示已勾选，确认键只切换勾选不进详情
            val managePicked=tab==3 && favoriteManage && item.id in favoriteSelected
            holder.badge.text=if(cloudRow!=null) "第 ${cloudRow.episodeIndex+1} 集 · ${formatTime(cloudRow.position)} · 云端" else if(tab==3) (if(favoriteManage && managePicked) "✓ " else "")+library.favoriteLabel(item.id) else if(ranked) rankPosition?.heat?.ifBlank { "热度暂无" } ?: "热度暂无" else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge
            holder.badge.setTextColor(if(managePicked) gold else if(ranked || tab==3) accent else muted)
            if(tab==3 && !favoriteManage) favoriteBadges[item.id]=holder.badge
            card.setOnClickListener {
                catalogFocus=item.id
                if(tab==3 && favoriteManage) {
                    if(!favoriteSelected.remove(item.id)) favoriteSelected+=item.id
                    catalogAdapter?.notifyItemChanged(position); refreshFavoriteManageBar()
                } else if(cloudRow!=null) openDetail(item,true,cloud=cloudRow) else openDetail(item,tab==4 || collection=="resume" || collection=="updates")
            }
            card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
            card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
            card.background=TvVisual.card(this@MainActivity,card.hasFocus(),managePicked)
            card.setOnFocusChangeListener { _,focused ->
                card.background=TvVisual.card(this@MainActivity,focused,managePicked)
                if(focused) {
                    catalogFocus=item.id
                    // 焦点抖动（进出详情、跨卡片快速滑动）会反复触发 loadCover；
                    // 相同 identity 在 300ms 内不重复发起请求，彻底阻断 Glide 重新 into(view)。
                    val now=android.os.SystemClock.uptimeMillis()
                    val last=holder.image.getTag(focusReloadTagKey) as? Long ?: 0L
                    if(now-last>=300L) {
                        holder.image.setTag(focusReloadTagKey,now)
                        loadCoverFrom(holder.image,item.cover,"focus")
                    } else {
                        val viewId = Integer.toHexString(System.identityHashCode(holder.image))
                        RuntimeLog.add("IMG call src=focus v=$viewId 防抖跳过(${now-last}ms)")
                    }
                    selectionPreview?.show(item,selectionStatus(item))
                }
            }
            // 高级搜索下 searchInput 未挂载但非空，会让 Elvis 链提前命中导致 ↑ 回不到筛选面板，故面板分支必须放最前
            card.nextFocusUpId=if(di<cols) (if(tab==1 && advancedSearch) filterRows.lastOrNull()?.firstOrNull()?.id else null) ?: suggestFirst?.id ?: historyFirst?.id ?: azLastRow.firstOrNull()?.id ?: searchInput?.id ?: collectionBack?.id ?: genreClear?.id ?: typeButtons[library.contentType]?.id ?: rankSwitch?.id ?: rankRefresh?.id ?: favoriteSelectAll?.id ?: favoriteSortFirst?.id ?: favoriteFilterFirst?.id ?: favoriteCheck?.id ?: historySync?.id ?: historyManage?.id ?: nav[TvCatalogHeader.normalize(tab)].id else View.NO_ID
            if(di>=displayed.size-12) catalogRecycler?.post { maybeLoadMore() }
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            (holder as? CardHolder)?.let { h -> if(h.boundId.isNotEmpty() && favoriteBadges[h.boundId]===h.badge) favoriteBadges.remove(h.boundId) }
        }
        private fun bindFooter(frame: FrameLayout) {
            frame.removeAllViews()
            if(footer==FOOTER_MORE_HOT) {
                frame.addView(button("下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
                return
            }
            val label=when(footer) {
                FOOTER_LOADING -> "正在加载…"
                FOOTER_ERROR -> "加载失败，继续浏览会自动重试"
                else -> if(tab==2) "已展示全部 ${displayed.size} 部" else "已加载全部，试试搜索"
            }
            val hint=text(label,14f,muted).apply {
                gravity=Gravity.CENTER; setPadding(0,dp(8),0,dp(8))
            }
            frame.addView(hint,FrameLayout.LayoutParams(-1,-2))
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        homeUpdateCount=library.updatedFavorites()
        val top=typeButtons.getValue(library.contentType)
        // 首页改由 Hero 展示当前焦点剧集（图、剧名、进度），不再额外占用一行文字预览
        val home=HomeScreen(this,top,::loadCover,{ _, _-> },
            { item,_ ->
                homePlayback?.checkpoint()
                val loaded=homePlayback?.currentDetail(item.id)
                openDetail(item,true,preloaded=loaded)
            },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition)
        startTvHomePlayback(home)
        home.refresh.text=if(loading) "正在加载随心看…" else "换一批"
        home.refresh.setOnClickListener { suixinCursor=null; page=1; showCatalog(true) }
        val categories=typeButtons.values.firstOrNull()?.parent as? LinearLayout
        val controls=categories?.let { parent -> (0 until parent.childCount).mapNotNull { parent.getChildAt(it) as? TextView } }.orEmpty()
        controls.forEachIndexed { index,view ->
            view.nextFocusDownId=home.firstId()
            view.nextFocusLeftId=controls.getOrNull(index-1)?.id ?: view.id
            view.nextFocusRightId=controls.getOrNull(index+1)?.id ?: view.id
        }
        if(focusNav) nav[TvCatalogHeader.normalize(tab)].requestFocus()
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    /** 首页使用独立的单路播放器；离开首页时先记录进度再释放，全集可接着播放。 */
    private fun startTvHomePlayback(home: HomeScreen) {
        stopTvHomePlayback()
        val playback=TvHomePlayback(this,{ repository },
            quality={ library.maxQuality },speed={ library.playbackSpeed },frame={ library.frameMode },autoNext={ library.autoNext },
            progress=library::progress,save={ if(library.isLoaded) library.save(it) },
            status={ state -> if(homeScreen===home) home.showPreview(state.seriesId,state.label,state.showCover,state.playing,state.failed) },
            screenOn={ playing -> homePreviewScreenOn=playing && foreground; refreshScreenOn() })
        homePlayback=playback
        home.attachPreview(playback.view)
        home.onPreviewSelected={ series ->
            val hint=trialHintFor(series.id)
            playback.select(series,hint?.first,hint?.second ?: 0)
        }
        home.onPreviewToggle=playback::toggle
        home.onPreviewActions=playback::pauseForActions
        home.onPreviewCleared=playback::clearSelection
        playback.setActive(foreground && hasWindowFocus())
        home.selectPreview()
    }
    private fun stopTvHomePlayback() {
        homePlayback?.close(); homePlayback=null
        homePreviewScreenOn=false; refreshScreenOn()
    }
    /** 首页和“随心看”入口共用同一套接口：优先服务端沉浸流，失败才回退旧片库。 */
    private fun suixinPage(requestPage: Int): CatalogPage {
        if(requestPage<=1) {
            suixinLoading=true
            val page=try { repository.suixinInitial() } catch(_: Exception) {
                if(suixinSource==null) suixinSource="fallback"
                return repository.home(requestPage,library.contentType,null)
            }
            suixinCursor=page.cursor
            suixinTrialHints=page.trialHints
            suixinSource=if(page.cursor?.readyToContinue==true) "tab+cell" else "tab"
            suixinLoading=false; suixinFailed=false
            return CatalogPage(page.items,page.cursor!=null)
        }
        val cursor=suixinCursor ?: return CatalogPage(emptyList(),false)
        val page=try { repository.suixinNext(cursor) } catch(_: Exception) {
            suixinFailed=true
            return CatalogPage(emptyList(),false)
        }
        suixinCursor=page.cursor
        if(page.trialHints.isNotEmpty()) suixinTrialHints=suixinTrialHints+page.trialHints
        return CatalogPage(page.items,page.cursor!=null)
    }

    /** 随心看入口：复用首页容器，但不从“热门发现/接着看”生成内容。 */
    private fun showSuixin() {
        suixinMode=true
        tab=0; screen="catalog"; collection=null; collectionReturn=null
        suixinCursor=null; suixinFailed=false; suixinSource=null
        page=1; catalog=emptyList(); hasMore=false; catalogFocus=""
        showCatalog(load=true,focusNav=true)
    }

    /** 首页试播集提示：当前作品没有服务端 ID 时回退详情首集。 */
    private fun trialHintFor(id: String): Pair<String?,Int>? = suixinTrialHints[id]?.let { it.episodeId to it.episodeCount }

    private fun homeShelves(): List<HomeScreen.Shelf> {
        val hot=catalog.filterNot { it.id in library.hidden() }
        return listOf(HomeScreen.Shelf("hot","随心看",hot.take(20).map { HomeScreen.Entry(it,it.badge.ifBlank { "随心看 · 为你推荐" }) }))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        val adapter=catalogAdapter
        if(removed && (localCatalog() || tab==0) && adapter!=null) {
            val index=adapter.displayed.indexOfFirst { it.id==changedId }
            if(localCatalog()) catalog=localItems()
            if(index>=0) {
                adapter.displayed.removeAt(index)
                adapter.notifyItemRemoved(index)
                if(adapter.displayed.isEmpty()) { catalogFocus=""; showCatalog(); return }
                val next=minOf(index,adapter.displayed.size-1)
                catalogFocus=adapter.displayed[next].id
                val recycler=catalogRecycler
                recycler?.post { if(catalogRecycler===recycler) recycler.findViewHolderForAdapterPosition(next)?.itemView?.requestFocus() }
            }
        } else {
            catalog.firstOrNull { it.id==changedId }?.let { selectionPreview?.show(it,selectionStatus(it)) }
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val grouped=library.favorite(series.id)
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(grouped) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看")
        if(grouped) labels+="设置分组 · ${library.groupOf(series.id) ?: "未分组"}"
        labels+="查看剧集详情"
        if(tab==0) labels+="不在热门推荐中显示"
        if(tab==4 || collection=="resume") labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            val detailIndex=if(grouped) 4 else 3
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> if(grouped) showGroupPicker(series,anchor) else openDetail(series)
                else -> if(choice==detailIndex) openDetail(series) else if(tab==4 || collection=="resume") manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun showGroupPicker(series: Series,anchor: View) {
        val options=listOf("未分组")+library.groups()+listOf("＋ 新建分组…")
        fun apply() { if(tab==3) showCatalog() else refreshLibraryCards(series.id) }
        tvTools.choose("选择分组 · ${series.title}",options,anchor,{ index ->
            when {
                index==0 -> { library.setGroup(series.id,null); apply() }
                index==options.lastIndex -> {
                    val input=EditText(this).apply {
                        hint="分组名称，最多 12 个字"; isSingleLine=true
                        filters=arrayOf(android.text.InputFilter.LengthFilter(12))
                        setTextColor(white); setHintTextColor(muted)
                    }
                    val content=column().apply {
                        addView(TvDialog.title(this@MainActivity,"新建收藏分组"))
                        addView(input,lp(-1,-2))
                    }
                    val dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
                    val create=TvDialog.capsuleButton(this,"创建") {
                        val name=input.text.toString().trim()
                        if(name.isBlank()) input.error="请输入分组名称"
                        else if(!library.setGroup(series.id,name)) input.error="分组数量已达上限或名称无效"
                        else { dialog.dismiss(); apply() }
                    }
                    content.addView(create,lp(-1,-2).apply { topMargin=dp(12) })
                    TvDialog.styleWindow(this,dialog,widthDp=360)
                    dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
                    dialog.show(); input.requestFocus()
                }
                else -> { library.setGroup(series.id,options[index]); apply() }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        if(!favoriteManage) favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
        val home=homeScreen
        if(screen=="catalog" && tab==0 && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    /** 管理模式就地刷新：勾选数、全选/删除按钮状态，不重建页面避免焦点丢失。 */
    private fun refreshFavoriteManageBar() {
        val count=favoriteSelected.size
        favoriteSelectAll?.text=if(count==catalog.size && catalog.isNotEmpty()) "取消全选" else "全选"
        favoriteRemove?.let { it.text="取消收藏所选（$count）"; it.isEnabled=count>0; it.alpha=if(it.isEnabled) 1f else .4f }
        if(favoriteManage) favoriteStatus?.text="已选 $count 部 · 确认键勾选/取消 · 返回键退出管理"
    }
    /**
     * 系统风格与界面缩放要整页重建才生效：记下当前页签与设置行，重建后回到同一处设置项，
     * 不再被扔回首页。（[focusKey] 与 settingRows 的文案前缀一致）
     */
    private fun recreateKeepingPage(focusKey: String) {
        PageRestore.tab=tab; PageRestore.focusKey=focusKey
        recreate()
    }
    private fun settings(parent: LinearLayout, footerHost: LinearLayout) {
        parent.addView(Space(this),lp(1,dp(if(heightDp()<480) 8 else 18)))
        // 左右分栏：左侧类别与常用入口，右侧只渲染当前类别；页脚仍在滚动区之外
        val shell=row().apply { gravity=Gravity.TOP }
        parent.addView(shell,lp(-1,0).apply { weight=1f })
        val railScroll=ScrollView(this).apply {
            isVerticalScrollBarEnabled=false; clipToPadding=false
            setPadding(0,dp(2),0,dp(2))
        }
        val rail=column()
        railScroll.addView(rail)
        val rightScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
        val rightPane=column().apply { background=TvVisual.panel(this@MainActivity) }
        rightScroll.addView(rightPane)
        val railWidth=if(widthDp()<700) 136 else 170
        shell.addView(railScroll,lp(dp(railWidth),-1))
        shell.addView(rightScroll,lp(0,-1).apply { weight=1f; leftMargin=dp(if(widthDp()<700) 14 else 22) })

        fun pane()=column().apply { setPadding(dp(20),dp(14),dp(20),dp(14)) }
        val playback=pane()
        val look=pane()
        val device=pane()
        val about=pane()
        val settingRows=mutableListOf<TextView>()
        val groupRows=linkedMapOf<LinearLayout,MutableList<TextView>>()

        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,20f).apply {
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(2),0,0)
            })
            group.addView(text(subtitle,12f,muted).apply { setPadding(0,dp(6),0,dp(12)) })
            group.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1).coerceAtLeast(1)).apply { bottomMargin=dp(10) })
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val view=TvSettingsValueView(this).apply {
                text=label; textSize=15f; includeFontPadding=false
                gravity=Gravity.CENTER_VERTICAL; minHeight=dp(46)
                setPadding(dp(20),dp(10),dp(18),dp(10))
                focusStyle(this)
                background=TvVisual.row(this@MainActivity,false)
                setOnFocusChangeListener { _,focused ->
                    background=TvVisual.row(this@MainActivity,focused)
                    invalidate()
                }
                setOnClickListener { action() }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(4) })
            settingRows+=view
            groupRows.getOrPut(group){mutableListOf()}+=view
            return view
        }
        val categories=listOf(
            Triple("播放与观看","所有剧集共用，播放中也可调整",playback),
            Triple("外观与界面","主题与背景",look),
            Triple("设备与数据","账号、本机记录与缓存",device),
            Triple("关于红果","版本信息、接口自检与许可",about))
        var active=0
        val railButtons=categories.map { entry ->
            button(entry.first,onClick={}).apply {
                gravity=Gravity.CENTER_VERTICAL; textSize=if(widthDp()<700) 14f else 16f; minHeight=dp(52)
                maxLines=1; ellipsize=TextUtils.TruncateAt.END
                setPadding(dp(22),dp(10),dp(12),dp(10))
            }
        }
        railButtons.forEachIndexed { i,item -> rail.addView(item,lp(-1,-2).apply { bottomMargin=dp(8) }) }
        nav[TvCatalogHeader.normalize(6)].nextFocusDownId=railButtons.first().id
        var showCategory: (Int,Boolean)->Unit = { _,_-> }
        var rechainSettings: ()->Unit = {}
        fun paintRail(index: Int,focused: Boolean) {
            val item=railButtons[index]
            item.setTextColor(if(focused || index==active) accent else muted)
            item.background=TvVisual.row(this@MainActivity,focused,index==active,category=true)
            item.setTypeface(null,if(focused || index==active) Typeface.BOLD else Typeface.NORMAL)
        }

        heading(playback,"播放与观看",categories[0].second)
        lateinit var signingModeButton: TextView
        signingModeButton=setting(playback,"算法模式  ·  ${SigningSettings.algorithmLabel}") {
            showSigningModePicker(signingModeButton)
        }
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var autoNextButton: TextView
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        lateinit var autoSeasonButton: TextView
        autoSeasonButton=setting(playback,"系列剧连播  ·  ${if(library.autoSeason) "开启" else "关闭"}") {
            library.autoSeason=!library.autoSeason
            autoSeasonButton.text="系列剧连播  ·  ${if(library.autoSeason) "开启" else "关闭"}"
        }
        lateinit var episodeCacheButton: TextView
        fun episodeCacheLabel()="缓存后三集  ·  ${if(library.episodeCache) "开启" else "关闭"}"
        episodeCacheButton=setting(playback,episodeCacheLabel()) {
            library.episodeCache=!library.episodeCache
            episodeCacheButton.text=episodeCacheLabel()
            applyEpisodeCacheSetting()
        }
        lateinit var keySettings: TextView
        keySettings=setting(playback,"遥控器按键  ·  快进 ${extras.seekStepSeconds} 秒") { showKeySettings(keySettings) }
        lateinit var autoSkipButton: TextView
        autoSkipButton=setting(playback,"不兼容片源自动跳过  ·  ${if(extras.autoSkipUnplayable) "开启" else "关闭"}") {
            extras.autoSkipUnplayable=!extras.autoSkipUnplayable
            autoSkipButton.text="不兼容片源自动跳过  ·  ${if(extras.autoSkipUnplayable) "开启" else "关闭"}"
        }
        heading(look,"外观与界面",categories[1].second)
        lateinit var themeButton: TextView
        themeButton=setting(look,"系统风格  ·  ${TvStyle.theme.label}") {
            library.theme=TvStyle.toggle().name
            recreateKeepingPage("系统风格")
        }
        lateinit var scaleButton: TextView
        scaleButton=setting(look,"界面缩放  ·  ${UiScale.label(this)}") {
            // 档位多了之后循环切换体验差（要按好几次才知道自己到哪一档），改成弹窗直接选
            val current=UiScale.index(this)
            tvTools.choose("界面缩放",UiScale.LEVELS.mapIndexed { index,level ->
                level.first+(if(index==current) "   ·   当前" else "")
            },scaleButton,{ choice ->
                if(choice!=current) { UiScale.set(this,choice); recreateKeepingPage("界面缩放") }
            },focusIndex=current)
        }
        lateinit var backdropButton: TextView
        fun backdropLabel()="背景风格  ·  ${if(backdropFile()!=null) "自定义图片" else if(backdropStyle()=="forced") "强制欣赏" else "默认风格"}"
        backdropButton=setting(look,backdropLabel()) {
            tvTools.choose("背景风格",listOf("强制欣赏","默认风格"),backdropButton,{ style ->
                resetBackdrop(if(style==0) "forced" else "plain")
                backdropButton.text=backdropLabel()
            })
        }
        // 电视/盒子本身锁定横屏，这一项只在手机等触屏设备上才有意义
        if(TouchControls.isHandheld(this)) {
            setting(look,"启动方向  ·  ${OrientationMode.of(library.orientationMode).label}") {
                showOrientationPicker(pickerAnchor)
            }
        }

        heading(device,"设备与数据",categories[2].second)
        accountButton=setting(device,accountManager.accountLabel) { accountManager.showLoginDialog(this) }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        lateinit var statsButton: TextView
        statsButton=setting(device,"观看统计  ·  本机累计") { showWatchStats(statsButton) }
        fun cacheBytes()=listOfNotNull(cacheDir,externalCacheDir).sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        fun cacheLabel(bytes: Long?)="清理缓存  ·  ${when {
            bytes==null -> "计算中…"
            bytes in 0..1023 -> "$bytes B"; bytes in 1024..1048575 -> "${bytes/1024} KB"; else -> "%.1f MB".format(bytes/1048576f)
        }}"
        lateinit var clearCache: TextView
        clearCache=setting(device,cacheLabel(null)) {
            TvDialog.confirm(this,"清理缓存","清除封面图片和已下载的安装包等缓存数据，不影响收藏和观看记录。") {
                io.execute {
                    listOfNotNull(cacheDir,externalCacheDir).forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
                    // 统计同样放后台，避免删完立刻在主线程遍历目录
                    val remaining=cacheBytes()
                    main.post {
                        if(isDestroyed) return@post
                        clearCache.text=cacheLabel(remaining)
                        Toast.makeText(this,"缓存已清理",Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
        }
        // 大小统计会遍历缓存目录，放到后台算完再回填，避免设置页构建时阻塞主线程
        io.execute {
            val bytes=cacheBytes()
            main.post { if(!isDestroyed) clearCache.text=cacheLabel(bytes) }
        }
        heading(about,"关于红果",categories[3].second)
        lateinit var checkButton: TextView
        checkButton=setting(about,"接口自检  ·  首页 / 搜索 / 播放") { showInterfaceCheck(checkButton) }
        setting(about,"运行日志  ·  预载缓存 / 一键复制") { RuntimeLogDialog.show(this, ::runtimeLogSummary) }
        val crashLogs=setting(about,"异常日志  ·  ${CrashReporter.count(this)} 份") {
            io.execute {
                val records=CrashReporter.reports(this)
                main.post { if(!isDestroyed) { if(records.isEmpty()) Toast.makeText(this,"应用启动以来未发生崩溃",Toast.LENGTH_SHORT).show() else CrashDialog.showList(this@MainActivity,records) } }
            }
        }
        lateinit var aboutProject: TextView
        aboutProject=setting(about,"关于本项目  ·  作者与 GitHub") { AboutProjectDialog.show(this@MainActivity, aboutProject) }
        lateinit var checkUpdate: TextView
        checkUpdate=setting(about,"检查更新  ·  ${BuildConfig.VERSION_NAME}") {
            checkAppUpdate(manual = true, anchor = checkUpdate)
        }
        // 页脚固定在滚动区之外（屏幕底部居中）：始终可见，因此不再需要抢焦点
        val settingsFooter=text("红果  /  ${BuildConfig.VERSION_NAME}    ·    收藏与本机记录保存在设备 · 登录后可同步到红果账号",12f,muted).apply {
            id=View.generateViewId(); isFocusable=false; gravity=Gravity.CENTER; maxLines=2
            setPadding(dp(10),dp(6),dp(10),dp(3))
        }
        val settingsDisclaimer=text("本应用仅用于技术交流与学习，不提供、不存储、不传播任何视频内容，内容版权归原平台所有。",11f,muted).apply {
            gravity=Gravity.CENTER; setPadding(dp(10),0,dp(10),dp(6))
        }
        rechainSettings={
            val group=categories[active].third
            val items=groupRows[group].orEmpty().filter { it.visibility==View.VISIBLE }
            items.forEachIndexed { index,view ->
                view.nextFocusUpId=items.getOrNull(index-1)?.id ?: railButtons[active].id
                // 页脚固定在屏幕上，不再作为焦点链末端，最后一项按下不再移动焦点
                view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
                view.nextFocusLeftId=railButtons[active].id
                view.nextFocusRightId=view.id
            }
            railButtons.forEachIndexed { index,item ->
                item.nextFocusUpId=railButtons.getOrNull(index-1)?.id ?: nav[TvCatalogHeader.normalize(tab)].id
                item.nextFocusDownId=railButtons.getOrNull(index+1)?.id ?: item.id
                item.nextFocusLeftId=item.id
                item.nextFocusRightId=if(index==active) items.firstOrNull()?.id ?: item.id else item.id
            }
            nav.forEach { it.nextFocusDownId=railButtons.first().id }
        }
        showCategory={ index,focusFirst ->
            if(index in categories.indices) {
                val changed=index!=active
                active=index
                if(changed) {
                    rightPane.removeAllViews()
                    rightPane.addView(categories[index].third,lp(-1,-2))
                    rightScroll.scrollTo(0,0)
                }
                railButtons.forEachIndexed { i,item -> paintRail(i,item.hasFocus()) }
                rechainSettings()
                if(focusFirst) {
                    (groupRows[categories[index].third].orEmpty().firstOrNull { it.visibility==View.VISIBLE } ?: railButtons[index]).requestFocus()
                }
            }
        }
        rightPane.addView(playback,lp(-1,-2))
        railButtons.forEachIndexed { i,item ->
            paintRail(i,false)
            item.setOnFocusChangeListener { _,focused -> paintRail(i,focused); if(focused) showCategory(i,false) }
            item.setOnClickListener { showCategory(i,true) }
        }
        rechainSettings()
        footerHost.addView(settingsFooter,lp(-2,-2))
        footerHost.addView(settingsDisclaimer,lp(-2,-2))
        // 页面重建后按标签恢复焦点，避免跳回顶部导航
        val wanted=settingsFocusKey.also { settingsFocusKey=null }
        val target=wanted?.let { key -> settingRows.firstOrNull { it.text.toString().startsWith(key) } }
        if(target!=null) {
            val index=categories.indexOfFirst { it.third===(target.parent as? View) }
            if(index>=0) showCategory(index,false)
            target.requestFocus()
        } else nav[TvCatalogHeader.normalize(tab)].requestFocus()
    }
    private fun runSearch(value: String,type: ContentType=library.contentType) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        cancelSearchSuggestions(); main.removeCallbacks(pendingSuggest)
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        // 提交关键词即切回关键词搜索，高级筛选条件不继承（下次进入高级搜索仍保留）
        advancedSearch=false
        if(library.contentType!=type) { library.contentType=type; for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) } }
        // 手机输入的精确剧名默认不再联想，纯首拼或编辑输入后恢复
        library.rememberSearch(clean,type); query=clean; tab=1; page=1; catalogFocus=""; searchFocusPending=true; showCatalog(true)
    }
    private fun onAzKey(key: String) {
        val input=searchInput as? EditText ?: return
        when(key) {
            "删除" -> { val length=input.text.length; if(length>0) input.text.delete(length-1,length) }
            "清空" -> input.setText("")
            else -> if(input.text.length<20) input.append(key.lowercase())
        }
        input.setSelection(input.text.length)
        scheduleSuggest()
    }
    fun cancelSearchSuggestions() {
        suggestTicket++
        suggestScope?.cancel(); suggestScope=null
        suggestJob?.cancel(true); suggestJob=null
        (suggestIo as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun scheduleSuggest() {
        cancelSearchSuggestions()
        main.removeCallbacks(pendingSuggest)
        main.postDelayed(pendingSuggest,300)
    }
    private fun fetchSuggest() {
        if(isDestroyed || tab!=1 || screen!="catalog") return
        val key=(searchInput as? EditText)?.text?.toString()?.trim().orEmpty()
        if(key.isEmpty() || key.length>40 || key==query && (catalog.isNotEmpty() || searchFocusPending)) {
            cancelSearchSuggestions()
            val keep=if(lastSuggestWords.isNotEmpty() && key==lastSuggestKey && key!=query) lastSuggestWords else emptyList()
            renderSuggest(keep); return
        }
        loadSuggestions(key) { words ->
            if(tab==1 && screen=="catalog" && (searchInput as? EditText)?.text?.toString()?.trim()==key) renderSuggest(words,key)
        }
    }
    private fun loadSuggestions(prefix: String, done: (List<String>) -> Unit) {
        cancelSearchSuggestions()
        val ticket=suggestTicket
        val scope=RequestScope(); suggestScope=scope
        suggestJob=suggestIo.submit {
            val words=runCatching { scope.run { repository.suggest(prefix) } }.getOrDefault(emptyList())
            main.post { if(!isDestroyed && !scope.isCancelled && ticket==suggestTicket) done(words) }
        }
    }

    private fun cancelSearchArtwork() {
        searchArtworkScope?.cancel(); searchArtworkScope=null
        searchArtworkJob?.cancel(true); searchArtworkJob=null
    }
    /** 结果已上屏后补封面；仅更新相同查询的卡片，不移动电视焦点或重新执行搜索。 */
    private fun loadSearchArtwork() {
        cancelSearchArtwork()
        val items=catalog.toList()
        if(items.none { it.cover.isBlank() }) return
        val phone=phoneLayout(); val keyword=if(phone) phoneQuery else query
        val type=library.contentType; val ticket=generation
        val scope=RequestScope(); searchArtworkScope=scope
        searchArtworkJob=io.submit {
            val result=runCatching { scope.run { repository.searchArtwork(items) } }.getOrNull() ?: return@submit
            main.post {
                if(isDestroyed || scope.isCancelled || ticket!=generation || screen!="catalog" || library.contentType!=type) return@post
                if(phone) { if(!phoneLayout() || phoneTab!="search" || phoneQuery!=keyword) return@post }
                else if(phoneLayout() || tab!=1 || advancedSearch || query!=keyword) return@post
                val covers=result.filter { it.cover.isNotBlank() }.associateBy { it.id }
                val updated=catalog.map { item -> covers[item.id]?.let { item.copy(cover=it.cover,badge=item.badge.ifBlank { it.badge }) } ?: item }
                if(updated==catalog) return@post
                catalog=updated
                if(phone) renderPhone() else catalogAdapter?.let { adapter ->
                    for(index in adapter.displayed.indices) {
                        val item=adapter.displayed[index]
                        val next=updated.firstOrNull { it.id==item.id } ?: continue
                        if(next!=item) { adapter.displayed[index]=next; adapter.notifyItemChanged(adapter.adapterPosition(index)) }
                    }
                }
            }
        }
    }
    private fun renderSuggest(words: List<String>,key: String="") {
        val container=suggestRow ?: return
        val refocus=container.hasFocus()
        val restore=suggestFocusIndex.takeIf { it in words.indices }
        container.removeAllViews(); suggestFirst=null
        // 记录最近的联想列表，供点击联想词搜索后回显；空列表同时隐藏中栏整列
        lastSuggestWords=words; lastSuggestKey=if(words.isEmpty()) "" else key
        if(words.isEmpty()) {
            suggestFocusIndex=-1
            syncSuggestPanel(false)
            wireSearchPanel(catalogRecycler)
            return
        }
        syncSuggestPanel(true)
        // setSingleLine 开启横向滚动模式，marquee 才按全文宽度布局滚得起来；仅 maxLines=1 只会截断省略
        val views=words.map { word -> button(word) { runSearch(word) }.apply { textSize=14f; minHeight=dp(34); setSingleLine(true); marqueeRepeatLimit=-1; ellipsize=android.text.TextUtils.TruncateAt.END; setPadding(dp(10),dp(6),dp(10),dp(6)) } }
        views.forEachIndexed { index,view ->
            if(suggestVertical) container.addView(view,lp(-1,-2).apply { bottomMargin=dp(4) })
            else container.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) })
            // 竖排联想：左回键盘离开位置、右/下进结果区（结果首卡优先，否则指向结果网格，避免原地自锁）；横排保持词间左右互连
            view.nextFocusLeftId=if(suggestVertical) (keyboardLastLabel?.let { keyboardKeys[it] } ?: azFirst)?.id ?: historyFirst?.id ?: View.NO_ID else views[if(index==0) 0 else index-1].id
            view.nextFocusRightId=if(suggestVertical) resultFirstCard?.id ?: catalogRecycler?.id ?: view.id else views[if(index==views.size-1) index else index+1].id
            view.nextFocusUpId=if(index==0) searchInput?.id ?: View.NO_ID else views[index-1].id
            view.nextFocusDownId=if(index==views.size-1) resultFirstCard?.id ?: catalogRecycler?.id ?: view.id else views[index+1].id
            // 保留 focusStyle 的焦点绘制，追加词条索引记忆；超长联想词获得焦点时跑马灯滚动全名，失焦回退省略号
            val paint=view.onFocusChangeListener
            view.setOnFocusChangeListener { v,focused ->
                paint?.onFocusChange(v,focused)
                if(focused) suggestFocusIndex=index
                view.ellipsize=if(focused) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
                view.isSelected=focused
                view.marqueeRepeatLimit=-1
            }
        }
        suggestFirst=views.firstOrNull()
        wireSearchPanel(catalogRecycler)
        // 联想刷新后优先回到记忆中的词条，避免焦点跳回第一条
        val target=restore?.let { views[it] } ?: suggestFirst
        if(refocus) (target ?: azLastRow.firstOrNull() ?: searchInput)?.requestFocus()
    }
    /** 联想栏动态显隐：无词时中栏整列消失，结果网格按全宽列数重新分布。 */
    private fun syncSuggestPanel(hasWords: Boolean) {
        val scroll=suggestScrollRef ?: return
        val target=if(hasWords) View.VISIBLE else View.GONE
        if(scroll.visibility!=target) {
            scroll.visibility=target
            (catalogRecycler?.layoutManager as? GridLayoutManager)?.spanCount=if(hasWords) searchColumns else fullSearchColumns
            catalogAdapter?.notifyDataSetChanged()
        }
    }
    /** 统一接线：键盘末列右移出键盘、键盘末行 → 历史 → 联想 → 结果网格，历史区可整体缺省。 */
    private fun wireSearchPanel(recycler: View?) {
        // 键盘每行末列按右移出键盘：有联想词进联想，否则直达结果网格
        keyboardRows.forEach { row -> row.last().nextFocusRightId=suggestFirst?.id ?: recycler?.id ?: row.last().id }
        azLastRow.forEach { it.nextFocusDownId=historyFirst?.id ?: suggestFirst?.id ?: recycler?.id ?: searchInput?.id ?: View.NO_ID }
        historyFirst?.let { first -> first.nextFocusUpId=(keyboardLastLabel?.let { keyboardKeys[it] } ?: azLastRow.firstOrNull())?.id ?: searchInput?.id ?: View.NO_ID }
        historyItems.forEachIndexed { index,item ->
            if(index>0) item.nextFocusUpId=historyItems[index-1].id
            item.nextFocusDownId=historyItems.getOrNull(index+1)?.id ?: recycler?.id ?: searchInput?.id ?: View.NO_ID
            item.nextFocusRightId=suggestFirst?.id ?: recycler?.id ?: View.NO_ID
        }
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史",onExtra={
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        })
    }
    // ---- 高级搜索：服务端条件筛选与关键词搜索双态切换 ----
    private fun chip(label: String,selected: Boolean,onClick: ()->Unit): TextView = text(label,12f).apply {
        gravity=Gravity.CENTER; setPadding(dp(10),dp(6),dp(10),dp(6)); minHeight=dp(30)
        focusStyle(this,selected)
        if(!phoneLayout()) {
            isSelected=selected
            setTextColor(if(selected) accent else white)
            setTypeface(null,if(selected) Typeface.BOLD else Typeface.NORMAL)
        }
        setOnClickListener { onClick() }
    }
    private fun switchAdvanced(value: Boolean) {
        if(advancedSearch==value) return
        advancedSearch=value
        query=""; (searchInput as? EditText)?.setText("")
        page=1; catalog=emptyList(); hasMore=false; catalogFocus=""; discoveryCursor=null; pendingFilterFocus=null
        // 每次进入高级搜索都展开筛选，收起状态只在本次会话内保持
        if(value) filterExpanded=true
        tabState.remove(1); tabFocus.remove(1); searchFocusPending=false
        showCatalog(load=value)
        main.post { if(advancedSearch==value) (if(value) filterToggle ?: filterPanel else searchInput)?.requestFocus() }
    }
    private fun reloadDiscovery() {
        page=1; catalog=emptyList(); hasMore=false; catalogFocus=""; discoveryCursor=null
        tabState.remove(1); tabFocus.remove(1)
        showCatalog(load=true)
    }
    private fun chooseFilter(type: String,id: String) {
        val next=discoverySelection.choose(type,id)
        if(next==discoverySelection) return
        discoverySelection=next
        // 选项改变后旧游标作废，必须重取第 1 页；焦点回到刚操作的条件
        pendingFilterFocus=type to id
        reloadDiscovery()
    }
    private fun resetFilters() {
        if(discoverySelection.values.isEmpty()) return
        discoverySelection=DiscoverySelection(); pendingFilterFocus=null
        reloadDiscovery()
    }
    private fun buildFilterPanel(container: LinearLayout) {
        val host=column().apply { setPadding(0,0,0,dp(6)) }
        filterPanel=host; container.addView(host)
        renderFilterPanel()
    }
    private fun filterSummaryText(): String {
        val lookup=discoveryGroups.associate { group -> group.type to group.options.associate { it.id to it.label } }
        val genre=discoverySelection.values["genre"].orEmpty()
        val genreText=if(genre.isEmpty()) "全部类型" else lookup["genre"]?.get(genre) ?: genre
        val others=DiscoverySelection.LABELS.keys.filter { it!="genre" }.mapNotNull { type ->
            discoverySelection.values[type]?.takeIf { it.isNotEmpty() }?.let { lookup[type]?.get(it) ?: it }
        }
        return if(others.isEmpty()) "$genreText · 全部条件" else "$genreText · ${others.joinToString(" · ")}"
    }
    private fun renderFilterPanel() {
        val host=filterPanel ?: return
        host.removeAllViews(); filterChips=linkedMapOf()
        val typeGroup=discoveryGroups.firstOrNull { it.type=="genre" }
            ?: DiscoveryGroup("genre","类型",listOf(DiscoveryFilter("short_play","真人剧"),DiscoveryFilter("comic_series","漫剧"),DiscoveryFilter("ai_series","AI剧")))
        val others=discoveryGroups.filter { it.type!="genre" }
        fun optionRow(group: DiscoveryGroup): List<TextView> {
            val views=mutableListOf<TextView>()
            views+=chip(if(group.type=="genre") "全部类型" else "全部",discoverySelection.values[group.type].isNullOrEmpty()) { chooseFilter(group.type,"") }
            group.options.forEach { option ->
                views+=chip(option.label,discoverySelection.values[group.type]==option.id) { chooseFilter(group.type,option.id) }
            }
            views.forEachIndexed { index,view ->
                filterChips[group.type to (if(index==0) "" else group.options[index-1].id)]=view
            }
            return views
        }
        fun lineOf(views: List<TextView>,label: String?): View {
            val line=row()
            if(label!=null) line.addView(text(label,12f,muted),lp(dp(40),-2))
            val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFocusable=false }
            val chips=row()
            views.forEach { chips.addView(it,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(6) }) }
            scroll.addView(chips)
            line.addView(scroll,lp(0,-2).apply { weight=1f })
            return line
        }
        // 类型行在页头，其余 7 组条件两列排布：单列会把结果网格挤出屏幕
        val typeChips=optionRow(typeGroup)
        val header=row()
        header.addView(lineOf(typeChips,null),lp(0,-2).apply { weight=1f })
        lateinit var collapse: TextView
        collapse=chip(if(filterExpanded) "收起筛选" else "展开筛选",false) {
            filterExpanded=!filterExpanded
            renderFilterPanel()
            // 收起/展开改变了上下焦点链，需重新把面板与结果网格串起来，否则会跳到已隐藏的选项
            catalogRecycler?.let { wireFilterPanelToGrid(it) }
            filterCollapse?.requestFocus()
        }
        filterCollapse=collapse
        val reset=chip("重置",false) { resetFilters() }
        val refresh=chip("刷新",false) { reloadDiscovery() }
        filterReset=reset; filterRefresh=refresh
        listOf(collapse,reset,refresh).forEach { view -> header.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; leftMargin=dp(8) }) }
        host.addView(header)
        host.addView(text(filterSummaryText(),12f,muted).apply { setPadding(dp(2),dp(2),0,dp(4)) }.also { filterSummary=it })
        val groupsHost=column()
        // 视觉行：每行左右两组条件（右组可为空），焦点必须按视觉行而非按组上下移动
        val linePairs=mutableListOf<Pair<List<TextView>,List<TextView>>>()
        if(others.isEmpty()) {
            groupsHost.addView(text(if(discoveryGroups.isEmpty()) "正在读取筛选条件…" else "该类型没有更多筛选条件",12f,muted).apply { setPadding(dp(2),dp(4),0,dp(4)) })
        } else {
            others.chunked(2).forEach { pair ->
                val line=row()
                val pairRows=mutableListOf<List<TextView>>()
                pair.forEach { group ->
                    val chips=optionRow(group)
                    line.addView(lineOf(chips,group.label),lp(0,-2).apply { weight=1f; rightMargin=dp(10) })
                    pairRows+=chips
                }
                if(pair.size==1) line.addView(Space(this),lp(0,1).apply { weight=1f })
                linePairs+=pairRows[0] to pairRows.getOrElse(1) { emptyList() }
                groupsHost.addView(line,lp(-1,-2).apply { bottomMargin=dp(4) })
            }
        }
        val visualLines=(others.size+1)/2
        val groupsScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false; visibility=if(filterExpanded) View.VISIBLE else View.GONE }
        groupsScroll.addView(groupsHost)
        host.addView(groupsScroll,lp(-1,if(others.isEmpty()) -2 else dp(minOf(visualLines,4)*34)))
        filterGroupsScroll=groupsScroll
        // 焦点严格按视觉行走线：首行是类型选项+操作按钮，其余每行左右两组条件。
        // ←/→ 跨组相连，↑/↓ 在同一半区内按位置落到上/下一行，告别按↓却平移到同行另一组的跳动感
        val headerButtons=listOf(collapse,reset,refresh)
        val visualRows=(listOf(typeChips to headerButtons)+linePairs)
        filterRows=visualRows.map { it.first+it.second }.toMutableList()
        fun linkRow(chips: List<TextView>) {
            chips.forEachIndexed { index,view ->
                view.nextFocusLeftId=chips[if(index==0) 0 else index-1].id
                view.nextFocusRightId=chips[if(index==chips.lastIndex) chips.lastIndex else index+1].id
            }
        }
        visualRows.forEach { (left,right) ->
            linkRow(left); linkRow(right)
            if(left.isNotEmpty() && right.isNotEmpty()) {
                left.last().nextFocusRightId=right.first().id
                right.first().nextFocusLeftId=left.last().id
            }
        }
        fun aim(row: Pair<List<TextView>,List<TextView>>?,rightHalf: Boolean,pos: Int): Int {
            if(row==null) return View.NO_ID
            val chips=(if(rightHalf) row.second else row.first).ifEmpty { row.first }
            return chips.getOrNull(minOf(pos,chips.lastIndex))?.id ?: View.NO_ID
        }
        visualRows.forEachIndexed { rowIndex,(left,right) ->
            val up=visualRows.getOrNull(rowIndex-1)
            val down=visualRows.getOrNull(rowIndex+1)
            left.forEachIndexed { index,view ->
                view.nextFocusUpId=if(up!=null) aim(up,false,index) else filterToggle?.id ?: View.NO_ID
                view.nextFocusDownId=aim(down,false,index)
            }
            right.forEachIndexed { index,view ->
                view.nextFocusUpId=if(up!=null) aim(up,true,index) else filterToggle?.id ?: View.NO_ID
                view.nextFocusDownId=aim(down,true,index)
            }
        }
    }
    private fun wireFilterPanelToGrid(recycler: RecyclerView) {
        if(tab!=1 || !advancedSearch) return
        val first=filterRows.firstOrNull().orEmpty()
        val last=filterRows.lastOrNull().orEmpty()
        val target=first.firstOrNull()?.id ?: recycler.id
        filterToggle?.nextFocusDownId=target
        searchInput?.nextFocusDownId=target
        searchButton?.nextFocusDownId=target
        // 展开时末行（左右两组）↓ 都进结果网格；收起时条件组隐藏，页头 ↓ 直接进网格
        if(filterExpanded && filterRows.size>1) last.forEach { it.nextFocusDownId=recycler.id }
        else first.forEach { it.nextFocusDownId=recycler.id }
    }
    private fun focusFilterChip(key: Pair<String,String>): Boolean {
        val view=filterChips[key] ?: return false
        if(!view.isAttachedToWindow) return false
        view.requestFocus()
        return true
    }
    /**
     * 上新区：数据不走网页榜单，而是分类接口里「最新上架 + 时间窗」这组固定条件。
     * 首页需要 need_selector_panel 才能拿到续页 session，续页复用 loadMoreDiscovery。
     */
    private fun loadNewRelease(body: LinearLayout) {
        if(catalog.isEmpty()) message(body,"正在读取最新上架…") else catalogGrid(body,focusNav=false,focusType=false)
        val selection=newReleaseSelection()
        discoverySelection=selection
        work({ repository.filteredDiscovery(selection,null) }, { result ->
            rememberCatalogFocus()
            catalog=result.items; hasMore=result.next!=null; discoveryCursor=result.next; page=1; ranking=null
            rankSubtitle?.text="来源：红果${rankBoard.label} · 最新上架 · ${newReleaseLabel()}"
            val keepNav=nav.any { it.hasFocus() }
            val current=catalogAdapter
            if(current!=null && current.cols==gridColumns && catalogRecycler?.parent===body) {
                // 后台更新只移动或修改变化条目，不能移除整页再让所有封面重新加载。
                current.replaceItems(result.items,if(hasMore) FOOTER_NONE else FOOTER_END)
                restoreCatalogFocus()
            } else {
                // 首屏先并发预加载所有封面，避免 RecyclerView 逐行 onBind 时一张张依次加载。
                artwork.preload(result.items.map { it.cover })
                body.removeAllViews(); catalogGrid(body,keepNav,false)
            }
            main.post {
                // 网格自己会在 layout 后把焦点放到卡片上，这里排到它之后再把焦点还回按钮
                if(pendingNewReleaseFocus!=null) { catalogRecycler?.postDelayed({ consumePendingNewReleaseFocus() },80); return@post }
                if(result.items.isEmpty()) {
                    // 空态别让用户盯着空白：副标题直接告诉他下一步按哪儿，焦点也落在时间按钮上
                    rankSubtitle?.text="${newReleaseLabel()}内暂无${rankBoard.label}上新，按「时间」放宽范围"
                    newReleaseWindowButton?.requestFocus(); return@post
                }
                if(keepNav) nav[TvCatalogHeader.normalize(tab)].requestFocus() else if(current==null) (resultFirstCard ?: newReleaseBack)?.requestFocus()
            }
        }, { problem ->
            if(catalog.isEmpty()) {
                catalog=emptyList(); hasMore=false; discoveryCursor=null; pendingNewReleaseFocus=null
                rankSubtitle?.text="上新加载失败"
                body.removeAllViews(); error(body,problem) { showCatalog(true) }
            } else {
                rankSubtitle?.text="上新更新失败 · 当前显示上次结果"
                catalogAdapter?.updateFooter(FOOTER_ERROR)
                restoreCatalogFocus()
            }
        })
    }
    private fun loadDiscovery(body: LinearLayout) {
        message(body,"正在读取筛选条件…")
        val selection=discoverySelection
        work({ repository.filteredDiscovery(selection,null) }, { result ->
            catalog=result.items; hasMore=result.next!=null; discoveryGroups=result.groups; discoveryCursor=result.next; page=1
            val keepNav=nav.any { it.hasFocus() }
            renderFilterPanel()
            body.removeAllViews(); catalogGrid(body,keepNav,false)
            main.post {
                val key=pendingFilterFocus.also { pendingFilterFocus=null }
                if(key!=null && focusFilterChip(key)) return@post
                if(result.items.isEmpty()) { filterToggle?.requestFocus(); return@post }
                if(keepNav) nav[TvCatalogHeader.normalize(tab)].requestFocus() else (resultFirstCard ?: filterToggle)?.requestFocus()
            }
        }, { problem ->
            catalog=emptyList(); hasMore=false
            body.removeAllViews(); error(body,problem) { showCatalog(true) }
        })
    }
    private fun loadMoreDiscovery(adapter: CatalogAdapter) {
        val cursor=discoveryCursor ?: return
        if(page>=waterfallMaxPages) { hasMore=false; rememberCatalogFocus(); adapter.updateFooter(FOOTER_END); restoreCatalogFocus(); return }
        val selection=discoverySelection
        loadingMore=true; rememberCatalogFocus(); adapter.updateFooter(FOOTER_LOADING); restoreCatalogFocus()
        work({ repository.filteredDiscovery(selection,cursor) }, { result ->
            loadingMore=false; page+=1
            hasMore=result.next!=null; discoveryCursor=result.next
            val known=catalog.mapTo(HashSet()) { it.id }
            val fresh=result.items.filter { it.id !in known }
            catalog=catalog+fresh
            val current=catalogAdapter
            if(current!=null) {
                val insertAt=current.displayed.size
                current.displayed.addAll(fresh)
                if(fresh.isNotEmpty()) current.notifyItemRangeInserted(insertAt,fresh.size)
                current.updateFooter(if(hasMore && page<waterfallMaxPages) FOOTER_NONE else FOOTER_END)
                restoreCatalogFocus()
            }
        }, { _ ->
            loadingMore=false
            Toast.makeText(this,"加载失败，继续浏览会自动重试",Toast.LENGTH_SHORT).show()
            catalogAdapter?.updateFooter(FOOTER_ERROR)
            restoreCatalogFocus()
        })
    }
    private fun showRandomPicks() {
        if(randomDialog?.isShowing==true) return
        val picks=randomRecommender.pick(12)
        if(picks.isNotEmpty()) { showRandomDialog(picks); return }
        // 池空或近期都展示过：先补充再展示
        showRandomDialog(emptyList(), loading = true)
        replenishRandomPool {
            val next=randomRecommender.pick(12)
            if(next.isNotEmpty()) showRandomDialog(next) else randomDialog?.dismiss().also { Toast.makeText(this,"暂时无法随机荐剧，请稍后重试",Toast.LENGTH_SHORT).show() }
        }
    }

    private fun replenishRandomPool(onDone: () -> Unit) {
        if(randomReplenishing) return
        randomReplenishing=true
        io.execute {
            val added=runCatching { randomRecommender.replenish() }.getOrDefault(0)
            if(added>0) {
                val encoded=RandomPoolSnapshot.encode(randomRecommender.snapshot(), System.currentTimeMillis())
                getSharedPreferences("random_pool", MODE_PRIVATE).edit().putString("pool", encoded).apply()
            }
            main.post { randomReplenishing=false; if(!isDestroyed) onDone() }
        }
    }

    private fun showRandomDialog(items: List<Series>, loading: Boolean=false) {
        randomDialog?.dismiss()
        val body=column().apply { setPadding(0,0,0,0) }
        val title=TvDialog.title(this,if(loading) "正在随机挑选…" else "随机荐剧")
        body.addView(title)
        val content=FrameLayout(this)
        // 内容区高度按屏幕算：固定 420dp 在 1080p / 高密度面板上会连标题和按钮一起顶出屏幕
        // （实测模拟器直接超屏）。留出标题、按钮行与卡片内边距，最多 420dp。
        val contentHeight=minOf(dp(420),resources.displayMetrics.heightPixels-dp(230)).coerceAtLeast(dp(200))
        body.addView(content,lp(-1,contentHeight).apply { bottomMargin=dp(10) })
        val dlg=AlertDialog.Builder(this).setView(TvDialog.card(this,body)).create()
        // 海报网格 4 列异步填充，宽度必须固定，不能收缩到内容
        TvDialog.styleWindow(this,dlg,widthDp=520)
        randomDialog=dlg
        dlg.show()
        if(items.isEmpty() && !loading) {
            content.addView(text("候选池为空，正在补充…",15f,muted).apply { setPadding(0,dp(20),0,0) })
        } else if(items.isNotEmpty()) {
            // 弹窗专用固定尺寸卡片：宽104dp，上图（3:4固定高）下名称+集数，小图拉伸撑满
            val cardWidth=dp(104)
            val posterHeight=(cardWidth/TvStyle.POSTER_ASPECT).toInt()
            val randomAdapter=object: RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount()=items.size
                override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
                    val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
                    val image=ImageView(this@MainActivity).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    card.addView(image,lp(-1,posterHeight))
                    val title=text("",13f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(6),dp(3),0) }
                    val badge=text("",11f,muted).apply { maxLines=1; minLines=1; minHeight=dp(17); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                    card.addView(title,lp(-1,-2)); card.addView(badge,lp(-1,-2))
                    card.layoutParams=RecyclerView.LayoutParams(cardWidth,-2).apply { rightMargin=dp(8); bottomMargin=dp(10) }
                    return object: RecyclerView.ViewHolder(card) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
                    val item=items[position]; val card=holder.itemView as LinearLayout
                    val image=card.getChildAt(0) as ImageView
                    val title=card.getChildAt(1) as TextView
                    val badge=card.getChildAt(2) as TextView
                    card.contentDescription=item.title
                    loadCover(image,item.cover)
                    title.text=item.title; badge.text=item.badge
                    card.setOnClickListener { randomDialog?.dismiss(); openDetail(item,false) }
                    card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this@MainActivity,focused); if(focused) loadCover(image,item.cover) }
                }
            }
            val grid=GridLayoutManager(this,4)
            val recycler=RecyclerView(this).apply { clipToPadding=false; itemAnimator=null }
            recycler.layoutManager=grid; recycler.adapter=randomAdapter
            content.addView(recycler,lp(-1,-1))
        }
        // 「换一批」按钮
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val change=TvDialog.capsuleButton(this,"换一批") {
            dlg.dismiss()
            val picks=randomRecommender.pick(12)
            if(picks.isNotEmpty()) showRandomDialog(picks)
            else { showRandomDialog(emptyList(), loading = true); replenishRandomPool { val n=randomRecommender.pick(12); if(n.isNotEmpty()) showRandomDialog(n) } }
        }
        val close=TvDialog.capsuleButton(this,"关闭") { dlg.dismiss() }
        buttons.addView(change,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(close,lp(-2,-2))
        body.addView(buttons)
        change.requestFocus()
        dlg.setOnDismissListener { randomDialog=null }
    }
    // 退出前二次确认：收藏有更新优先展示，否则从随机荐剧池取 3 部；都没有就直接退出
    private fun showExitConfirm() {
        if(exitDialog?.isShowing==true) return
        val updates=library.updates()
        val updated=library.favorites().filter { (updates[it.id]?.added ?: 0) > 0 }.take(3)
        val fromUpdates=updated.isNotEmpty()
        // 收藏更新优先，其次随机荐剧池，最后退化到首页 7 天缓存随机抽 3 部，全部为本地数据
        val items=if(fromUpdates) updated else randomRecommender.pick(3).ifEmpty { library.cachedHome(library.contentType)?.items?.shuffled()?.take(3) ?: emptyList() }
        if(phoneLayout()) {
            // 弹框会跨后台恢复保留，封面不能随主页面清理或重建而解绑。
            val dialogArtwork=GlideArtwork(this)
            phoneExitArtwork=dialogArtwork
            val dlg=PhoneExitDialog.create(this,items,fromUpdates,
                badge={ item -> if(fromUpdates) library.favoriteLabel(item.id) else item.badge },
                loadCover={ view,url -> dialogArtwork.load(view,url) },
                onOpen={ item -> openDetailPhone(item,false) },
                onExit={ killOnDestroy=true; library.flush(); finish() })
            exitDialog=dlg
            dlg.setOnDismissListener {
                dialogArtwork.close()
                if(phoneExitArtwork===dialogArtwork) phoneExitArtwork=null
                if(exitDialog===dlg) exitDialog=null
            }
            dlg.show()
            return
        }
        val body=column().apply { setPadding(0,0,0,0) }
        val heading=TvDialog.title(this,if(fromUpdates) "收藏更新，看完再走？" else if(items.isNotEmpty()) "猜你喜欢，再看一会儿？" else "确定要退出红果吗？")
        heading.gravity=Gravity.CENTER; heading.setPadding(0,0,0,dp(14))
        body.addView(heading,lp(-1,-2))
        val cardWidth=dp(120)
        val posterHeight=(cardWidth/TvStyle.POSTER_ASPECT).toInt()
        val cards=row()
        items.forEach { item ->
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card)
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_XY; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            card.addView(image,lp(-1,posterHeight))
            val cardTitle=text("",13f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(6),dp(3),0) }
            val badge=text("",11f,muted).apply { maxLines=1; minLines=1; minHeight=dp(17); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
            card.addView(cardTitle,lp(-1,-2)); card.addView(badge,lp(-1,-2))
            card.contentDescription=item.title
            loadCover(image,item.cover)
            cardTitle.text=item.title; badge.text=if(fromUpdates) library.favoriteLabel(item.id) else item.badge
            card.setOnClickListener { exitDialog?.dismiss(); openDetail(item,false) }
            card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused); if(focused) loadCover(image,item.cover) }
            cards.addView(card,lp(cardWidth,-2).apply { rightMargin=dp(12) })
        }
        if(items.isNotEmpty()) body.addView(cards,lp(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL })
        else body.addView(ImageView(this).apply { setImageResource(R.drawable.exit_placeholder); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            lp(dp(200),dp(200)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(8) })
        val dlg=AlertDialog.Builder(this).setView(TvDialog.card(this,body)).create()
        TvDialog.styleWindow(this,dlg)
        val buttons=row().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(14),0,0) }
        val exitButton=TvDialog.capsuleButton(this,"退出不看了") { killOnDestroy=true; library.flush(); dlg.dismiss(); finish() }
        val stayButton=TvDialog.capsuleButton(this,"再看一会儿") { dlg.dismiss() }
        buttons.addView(exitButton,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(stayButton,lp(-2,-2))
        body.addView(buttons,lp(-2,-2).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(14); bottomMargin=dp(6) })
        exitDialog=dlg
        dlg.show()
        stayButton.requestFocus()
        dlg.setOnDismissListener { exitDialog=null }
    }
    private fun showGenrePicker() {
        val aiMode=tab==2 && aiCatalog
        val anchor=if(aiMode) aiGenreButton ?: return else genreButton ?: return
        val type=if(aiMode) ContentType.AI else library.contentType
        val current=if(aiMode) aiGenre else genre
        io.execute {
            val result=runCatching { repository.genres(type) }
            main.post {
                if(isDestroyed || !foreground || screen!="catalog") return@post
                if(aiMode) { if(tab!=2 || !aiCatalog) return@post } else if(tab!=0) return@post
                result.onSuccess { genres ->
                    tvTools.choose("选择${type.label}题材",listOf("全部题材")+genres.map { it.name },anchor,{ index ->
                        val next=if(index==0) null else genres[index-1]
                        if(next!=current) {
                            if(aiMode) aiGenre=next else genre=next
                            page=1; catalogFocus=""; tabState.remove(tab); tabFocus.remove(tab); showCatalog(true)
                        }
                    },anchorToButton=true)
                }.onFailure { tvTools.info("题材暂不可用","题材列表加载失败，请稍后重试。",anchor) }
            }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    /** 日历排期随 Series 存入收藏与导航现场，重开详情时也能展示。 */
    private fun scheduledSeries(series: Series): Series? {
        val cached=library.cachedSeasons(series.id)?.items?.firstOrNull { it.id==series.id }
        if(cached!=null) return cached.takeIf { it.release!=null }
        return phoneNewReleasePages.values.asSequence().flatMap { it.items.asSequence() }
            .firstOrNull { it.series.id==series.id && !it.online }?.series ?: series.takeIf { it.release!=null }
    }

    private fun withSeasonRows(data: Detail, rows: List<Series>): Detail {
        val current=rows.firstOrNull { it.id==data.series.id }
        val series=if(data.episodes.isEmpty() && current?.release!=null)
            data.series.copy(badge=current.badge,release=current.release) else data.series
        return data.copy(series=series,sameName=rows.filter { it.id!=data.series.id },seasonsLoaded=true)
    }

    /** 先用本机评分补齐首屏，联网评分返回后再由详情辅助刷新覆盖。 */
    private fun withCachedRating(data: Detail): Detail {
        val current=data.social
        if(RatingSnapshot.validRating(current.rating)) return data
        val cached=library.cachedRating(data.series.id) ?: return data
        return data.copy(social=current.copy(rating=cached.rating,ratingCount=cached.ratingCount))
    }

    private fun withCachedSeasons(data: Detail): Detail = library.cachedSeasons(data.series.id)
        ?.let { withCachedRating(withSeasonRows(data,it.items)) } ?: withCachedRating(data)

    private fun withReleaseSchedule(data: Detail, scheduled: Series?): Detail =
        if(data.episodes.isEmpty() && data.series.release?.isSpecified!=true && scheduled?.release!=null)
            data.copy(series=data.series.copy(badge=scheduled.badge,release=scheduled.release))
        else data

    /**
     * @param preloaded 已经取到的详情，在进入详情页时复用，避免重复请求。
     */
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true,cloud: CloudProgress?=null,preloaded: Detail?=null) {
        offlineOnly = false
        favoriteMonitor.stop()
        pendingDetail=series; detailAutoplay=autoplay; pendingCloudResume=cloud
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false; detailEpisodePage=-1
        val body=base(series.title); message(body,"正在加载剧集…")
        val scheduled=scheduledSeries(series)
        val apply:(Detail)->Unit=loaded@{ loaded ->
            val result=withCachedSeasons(withReleaseSchedule(loaded,scheduled))
            detail=result
            if(result.episodes.isEmpty()) {
                pendingCloudResume=null; episodeIndex=0; group=0; directPlayback=false
                showDetail(false)
                return@loaded
            }
            library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val cloudResume=pendingCloudResume.takeIf { it?.series?.id==series.id }; pendingCloudResume=null
            val target=if(cloudResume!=null) {
                // 云端进度续播：episodeId 对不上（换源/集数更新）时退回云端记录的集号；不写本机记录，起播后自然落本机
                val index=result.episodes.indexOf(cloudResume.episodeId).takeIf { it>=0 } ?: cloudResume.episodeIndex.coerceIn(0,result.episodes.lastIndex)
                ResumeTarget(index,cloudResume.position.coerceAtLeast(0))
            } else {
                val progress=library.progress(series.id)
                ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            }
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay)
            else {
                showDetail(false)
                // 详情页停留期间就把「开始观看」要播的那一集及其下一集预热好：
                // 用户点下去时地址、索引和首块已在缓存里，起播不再等两跳网络。
                prefetchDetailWindow(target.index)
            }
        }
        val cached=if(cloud==null) repository.cachedDetail(series.id) else null
        if(preloaded!=null) apply(preloaded)
        else if(cached?.episodes?.isNotEmpty()==true) apply(cached)
        else if(scheduled?.release?.isPending()==true) apply(Detail(scheduled,emptyList()))
        else if(cached!=null) apply(cached)
        else work({ repository.seriesDetail(series.id) }, apply, { problem ->
            if(scheduled!=null) apply(Detail(scheduled,emptyList()))
            else error(body,problem) { openDetail(series,resume,detailAutoplay,cloud) }
        })
    }
    private fun cancelDetailExtras() {
        detailExtrasScope?.cancel(); detailExtrasScope=null
        detailExtrasJob?.cancel(true); detailExtrasJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    /** 先显示本地分季，再后台刷新；分季返回后立即更新界面和缓存，不等待相关推荐或评分。 */
    fun loadDetailExtras(data: Detail, render: (Detail)->Unit = {}) {
        if (offlineOnly) return
        cancelDetailExtras()
        val initial=withCachedSeasons(data)
        if(detail?.series?.id==data.series.id) detail=initial
        if(initial!=data && screen=="detail") render(initial)
        val ticket=generation
        val scope=RequestScope(); detailExtrasScope=scope
        fun active()=!isDestroyed && generation==ticket && !scope.isCancelled && detailExtrasScope===scope &&
            (screen=="detail" || screen=="player") && detail?.series?.id==data.series.id
        detailExtrasJob=io.submit {
            val result=runCatching { scope.run {
                repository.detail(data.series.id,refreshSeasons=true,onSeasons={ rows ->
                    main.post {
                        if(!active()) return@post
                        library.cacheSeasons(data.series.id,rows)
                        val current=detail ?: return@post
                        val updated=withSeasonRows(current,rows)
                        detail=updated
                        if(screen=="detail" && updated!=current) render(updated)
                    }
                })
            } }.getOrNull()
            main.post {
                // 即使页面已经离开，也把刚取回的评分落入本机，下一次进入可直接展示。
                if(result!=null && RatingSnapshot.validRating(result.social.rating)) {
                    library.cacheRating(data.series.id,result.social.rating,result.social.ratingCount)
                }
                if(!active()) return@post
                detailExtrasScope=null; detailExtrasJob=null
                if(result!=null) {
                    val current=detail ?: return@post
                    val updated=withCachedSeasons(current.copy(related=result.related,social=result.social))
                    detail=updated
                    if(screen=="detail" && updated!=current) render(updated)
                }
            }
        }
    }
    /** 详情页驻留期间预热「即将播放的那集 + 下一集」。
     *
     *  只放进 EpisodePlaybackCache 的窗口，不启动播放；用户点「开始观看」时直接命中，
     *  起播前不再需要两次网络往返（取模型 + 取清晰度）。预取失败由缓存自己退避，不影响页面。 */
    private fun prefetchDetailWindow(vararg extra: Int) {
        if (offlineOnly) return
        val data=detail ?: return
        if(data.episodes.isEmpty() || !prefetchMemoryReady()) return
        val quality=effectiveQuality()
        // 「开始观看」实际会播的集，加上调用方明确关心的集（如跳转集数选中的那集）；
        // 两者都预热，避免焦点所在集与按钮行为不一致时落空。
        val starts=buildList {
            add(startEpisodeIndex())
            extra.forEach { add(it) }
            add(episodeIndex)
        }.map { it.coerceIn(0,data.episodes.lastIndex) }.distinct()
        val ids=buildList {
            starts.forEach { from ->
                data.episodes.getOrNull(from)?.let(::add)
                data.episodes.getOrNull(from+1)?.let(::add)
            }
        }.distinct()
        if(ids.isEmpty()) return
        // retain 决定哪些集允许预取；详情页还没有播放页的窗口，这里先自己设一个。
        ensurePlaybackCache().retain(ids.toSet(),quality)
        val progress=library.progress(data.series.id)
        ids.forEach { id ->
            val index=data.episodes.indexOf(id)
            val position=if(progress?.episodeId==id && !progress.completed) progress.position else savedPosition(index)
            ensurePlaybackCache().prefetch(id,quality,warmStart=episodeStartPosition(index,position)==0L)
        }
    }
    /** 「开始观看」按钮实际会播的集号：与 showDetail 里播放按钮的取值口径一致。
     *  已看完 → 第 1 集；有未完成进度 → 该集；否则第 1 集。 */
    private fun startEpisodeIndex(): Int {
        val data=detail ?: return 0
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        return when {
            library.watched(data.series.id) -> 0
            progress!=null && !progress.completed -> resumeIndex
            progress?.completed==true -> (resumeIndex+1).coerceAtMost(data.episodes.lastIndex)
            else -> resumeIndex
        }
    }
    /** focusPager：翻页后焦点留在翻页按钮上（-1=‹，1=›），0=按 focusEpisode 决定，见函数末尾。 */
    private fun showDetail(focusEpisode: Boolean, focusPager: Int = 0) {
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        val hasEpisodes=data.episodes.isNotEmpty()
        val body=base("剧集详情")
        val compact=widthDp()<700
        val inset=dp(if(compact) 16 else 24)
        body.setPadding(inset,dp(if(heightDp()<450) 10 else 16),inset,dp(16))
        root.background=TvVisual.backdrop(this)
        val back=(body.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? TextView
        back?.apply {
            setTextColor(white); compoundDrawables.forEach { it?.let { icon -> DrawableCompat.setTint(DrawableCompat.wrap(icon).mutate(), white) } }
            setOnFocusChangeListener { _,focused -> background=TvVisual.row(this@MainActivity,focused) }
        }
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
        val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row().apply {
            gravity=Gravity.TOP; setPadding(dp(16),dp(14),dp(16),dp(14))
            background=TvVisual.panel(this@MainActivity)
        }
        val posterWidth=dp(if(compact) 100 else 123)
        val image=ImageView(this).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            background=TvStyle.shape(this@MainActivity,surface,radius=8); clipToOutline=true
        }
        hero.addView(image,lp(posterWidth,(posterWidth/TvStyle.POSTER_ASPECT).toInt()).apply { rightMargin=dp(if(compact) 16 else 24) })
        loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,if(compact) 22f else 26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        val episodeLabel=if(hasEpisodes) "${data.episodes.size} 集" else if(data.series.release!=null) "待上线" else data.series.badge
        val metadata=listOf(episodeLabel,data.series.tags,library.progress(data.series.id)?.let { "看到第 ${it.episodeIndex+1} 集" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        info.addView(text(metadata,13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        val releaseView=text(data.series.release?.label.orEmpty(),15f,accent).apply {
            setPadding(0,0,0,dp(8)); visibility=if(!hasEpisodes && data.series.release!=null) View.VISIBLE else View.GONE
        }
        info.addView(releaseView)
        // 主详情先展示热度与收藏，网页评分异步补齐后原位更新。
        val metricsView=TextView(this).apply {
            textSize=13f; setTextColor(muted); includeFontPadding=false
            maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(0,0,0,dp(6))
        }
        info.addView(metricsView)
        fun renderMetrics(current: Detail) {
            metricsView.visibility=if(current.social.isEmpty) View.GONE else View.VISIBLE
            val metrics=mutableListOf<String>()
            val score=if(current.social.rating>0) String.format(Locale.CHINA,"%.1f 分",current.social.rating) else ""
            if(score.isNotEmpty()) metrics+=score+current.social.ratingCount.takeIf { it.isNotBlank() }?.let { "（$it 人评分）" }.orEmpty()
            if(current.social.favoriteText.isNotBlank()) metrics+=current.social.favoriteText
            if(current.social.heatText.isNotBlank()) metrics+=current.social.heatText
            val line=SpannableString(metrics.joinToString("  ·  "))
            if(score.isNotEmpty()) {
                line.setSpan(ForegroundColorSpan(accent),0,score.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                line.setSpan(StyleSpan(Typeface.BOLD),0,score.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            metricsView.text=line
        }
        val description=data.series.description.ifBlank { if(hasEpisodes) "选择剧集开始观看" else "暂无简介" }
        val synopsis=text(description,14f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END; setLineSpacing(dp(3).toFloat(),1f) }
        info.addView(synopsis)
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(actions)
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        val play=addButton(actions,if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { playEpisode(if(watched) 0 else if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,if(watched) 0 else resumePosition) }
            .apply { visibility=if(hasEpisodes) View.VISIBLE else View.GONE; isEnabled=hasEpisodes }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        val secondary=row().apply { setPadding(0,dp(8),0,0) }
        info.addView(secondary)
        play.setTextColor(accent)
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(secondary,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        lateinit var jump: TextView
        jump=addButton(secondary,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; detailEpisodePage=target/20; showDetail(true); prefetchDetailWindow(target) } }
            .apply { visibility=if(hasEpisodes) View.VISIBLE else View.GONE; isEnabled=hasEpisodes }
        val firstAction=if(hasEpisodes) play else favorite
        val lastAction=if(hasEpisodes) jump else synopsisButton
        listOf(play,favorite,synopsisButton,jump).forEach { view ->
            view.textSize=if(compact) 13f else 14f; view.minHeight=dp(38)
            view.maxLines=1; view.ellipsize=TextUtils.TruncateAt.END
            view.setPadding(dp(14),dp(8),dp(14),dp(8))
        }
        synopsisButton.setTextColor(muted); jump.setTextColor(muted)
        content.addView(hero,lp(-1,-2).apply { topMargin=dp(8); bottomMargin=dp(10) })
        // 与参考 TV 详情页一致，季条和选集独立占满内容宽度。
        val seasonHost=column()
        content.addView(seasonHost)
        if(!hasEpisodes) info.addView(text(if(data.series.release!=null) "上线后即可观看，可先收藏" else "暂时没有可看的剧集",15f,muted).apply { setPadding(0,dp(12),0,0) })
        val seasonButtons=mutableListOf<TextView>()
        var renderedSeasons: List<Series>?=null
        var renderedSeasonStrip: HorizontalScrollView?=null
        fun renderSeasons(current: Detail) {
            val seasons=(listOf(current.series)+current.sameName).distinctBy { it.id }
                .sortedBy { val number=seasonNumber(it.title); if(number==Int.MAX_VALUE) 0 else number }
            if(renderedSeasons==seasons) return
            val focusedId=seasonButtons.firstOrNull { it.hasFocus() }?.tag as? String
            val oldScrollX=renderedSeasonStrip?.scrollX ?: 0
            renderedSeasons=seasons
            seasonHost.removeAllViews(); seasonButtons.clear(); renderedSeasonStrip=null
            if(seasons.size<=1) {
                if(focusedId!=null) lastAction.requestFocus()
                return
            }
            val seasonRow=row(); seasonRow.setPadding(0,dp(10),0,dp(2))
            seasonRow.addView(text("系列 ${seasons.size} 季",13f,accent).apply { gravity=Gravity.CENTER },lp(dp(78),dp(42)))
            val strip=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
            val line=row(); strip.addView(line)
            seasons.forEach { season ->
                val number=seasonNumber(season.title)
                val seen=library.progress(season.id)?.let { " · 看到${it.episodeIndex+1}集" }.orEmpty()
                val title=if(number==Int.MAX_VALUE) season.title.take(12) else "第 ${number} 季"
                seasonButtons+=addButton(line,title+seen,season.id==current.series.id) { if(season.id!=current.series.id) openDetail(season) }
                    .apply { tag=season.id; contentDescription=listOf(season.title,season.release?.label.orEmpty()).filter { it.isNotBlank() }.joinToString("，") }
            }
            seasonRow.addView(strip,lp(0,dp(42)).apply { weight=1f })
            seasonHost.addView(seasonRow)
            renderedSeasonStrip=strip
            strip.post {
                if(renderedSeasonStrip!==strip || !strip.isAttachedToWindow) return@post
                if(focusedId!=null) (seasonButtons.firstOrNull { it.tag==focusedId }
                    ?: seasonButtons.firstOrNull { it.tag==current.series.id })?.requestFocus()
                strip.scrollTo(oldScrollX,0)
            }
        }
        // 每组 20 集，列数随可用宽度调整；只渲染当前组，长剧也保持轻量。
        val episodeCols=when { widthDp()<600 -> 5; widthDp()<850 -> 8; else -> 10 }
        val pageSize=20
        val totalPages=((data.episodes.size+pageSize-1)/pageSize).coerceAtLeast(1)
        detailEpisodePage=if(detailEpisodePage<0) (episodeIndex/pageSize).coerceIn(0,totalPages-1) else detailEpisodePage.coerceIn(0,totalPages-1)
        var prevPager: TextView?=null; var nextPager: TextView?=null
        if(hasEpisodes) {
            val pager=row().apply { setPadding(0,dp(6),0,dp(6)); gravity=Gravity.CENTER_VERTICAL }
            pager.addView(text("选集",16f).apply { setTypeface(null,Typeface.BOLD) },lp(-2,-2).apply { rightMargin=dp(12) })
            pager.addView(text("第 ${detailEpisodePage*pageSize+1}—${minOf((detailEpisodePage+1)*pageSize,data.episodes.size)} 集 / 共 ${data.episodes.size} 集",12f,muted).apply {
                maxLines=1; ellipsize=TextUtils.TruncateAt.END
            },lp(0,-2).apply { weight=1f })
            if(totalPages>1) {
                prevPager=button("‹ 上一组") { if(detailEpisodePage>0){detailEpisodePage--;showDetail(true,-1)} }.apply {
                    isEnabled=detailEpisodePage>0; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f
                    minHeight=dp(36); textSize=12f; setPadding(dp(10),dp(6),dp(10),dp(6))
                }
                pager.addView(prevPager,lp(-2,-2))
                pager.addView(text("${detailEpisodePage+1}/${totalPages}",12f,muted).apply { setPadding(dp(8),0,dp(8),0) })
                nextPager=button("下一组 ›") { if(detailEpisodePage<totalPages-1){detailEpisodePage++;showDetail(true,1)} }.apply {
                    isEnabled=detailEpisodePage<totalPages-1; isFocusable=isEnabled; alpha=if(isEnabled) 1f else .4f
                    minHeight=dp(36); textSize=12f; setPadding(dp(10),dp(6),dp(10),dp(6))
                }
                pager.addView(nextPager,lp(-2,-2))
            }
            content.addView(pager)
            content.addView(text("橙色为当前集 · 上次为续播位置 · 方向键选择，确定播放",11f,muted).apply { setPadding(0,0,0,dp(8)) })
        }
        val pageStart=detailEpisodePage*pageSize
        val pageEnd=minOf(pageStart+pageSize,data.episodes.size)
        val episodes=mutableListOf<View>()
        (pageStart until pageEnd).chunked(episodeCols).forEach { numbers ->
            val line=row()
            numbers.forEach { index ->
                val seen=watched || index<episodeIndex
                val marker=when {
                    index==episodeIndex -> "当前"
                    index==resumeIndex && progress!=null -> if(progress.completed) "已看" else "上次"
                    else -> ""
                }
                val item=button("${index+1}"+(if(marker.isNotBlank()) "\n$marker" else ""),index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else savedPosition(index)) }
                item.textSize=13f; item.maxLines=2; item.minHeight=dp(46); item.setPadding(dp(2),dp(4),dp(2),dp(4))
                item.contentDescription="第 ${index+1} 集"+(if(index==episodeIndex) "，当前" else if(seen) "，已看" else if(index==resumeIndex && progress!=null && !progress.completed) "，看到 ${formatTime(progress.position)}" else "")
                if(seen && index!=episodeIndex) item.alpha=.6f
                line.addView(item,lp(0,dp(46)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item
            }
            repeat(episodeCols-numbers.size) { line.addView(Space(this),lp(0,dp(46)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(52)))
        }
        val recommendationHost=column()
        content.addView(recommendationHost,lp(-1,-2))
        fun renderExtras(current: Detail) {
            releaseView.text=current.series.release?.label.orEmpty()
            releaseView.visibility=if(!hasEpisodes && current.series.release!=null) View.VISIBLE else View.GONE
            renderMetrics(current)
            renderSeasons(current)
            recommendationHost.removeAllViews()
            val extraRows=mutableListOf<MutableList<View>>()
            fun recommendationRow(title: String,items: List<Series>) {
                if(items.isEmpty()) return
                val section=column()
                val heading=row(); heading.setPadding(0,dp(20),0,dp(9))
                heading.addView(View(this).apply { background=TvStyle.shape(this@MainActivity,TvStyle.accent,radius=2) },lp(dp(3),dp(14)).apply { rightMargin=dp(8) })
                heading.addView(text(title,16f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
                heading.addView(text("左右查看更多",10f,muted))
                section.addView(heading)
                val horizontal=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
                val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
                val width=((resources.displayMetrics.widthPixels*.9f-dp(70))/6).toInt().coerceAtLeast(dp(100))
                val posterHeight=((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()
                val cards=mutableListOf<View>()
                items.take(12).forEach { series ->
                    val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(4),dp(4),dp(4),dp(8)); background=TvVisual.card(this@MainActivity,false); contentDescription="$title，${series.title}" }
                    val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                    card.addView(image,lp(-1,posterHeight))
                    card.addView(text(series.title,14f).apply { minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) })
                    card.addView(text(series.badge.ifBlank { series.tags },12f,muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) })
                    card.setOnFocusChangeListener { _,focused -> card.background=TvVisual.card(this@MainActivity,focused); if(focused) loadCover(image,series.cover) }
                    card.setOnClickListener { openDetail(series) }
                    if(cards.size<6) loadCover(image,series.cover)
                    line.addView(card,lp(width,-2).apply { rightMargin=dp(14) })
                    cards+=card
                }
                horizontal.addView(line); section.addView(horizontal)
                recommendationHost.addView(section,lp(-1,-2).apply { topMargin=dp(4) })
                extraRows+=cards
            }
            recommendationRow("系列剧推荐",current.sameName)
            recommendationRow("相关推荐",current.related)
            val lastRowStart=((episodes.size-1)/episodeCols)*episodeCols
            val firstRow=extraRows.firstOrNull()
            val pagerRow: View?=prevPager?.takeIf { it.isFocusable } ?: nextPager?.takeIf { it.isFocusable }
            val aboveEpisodes: View=pagerRow ?: seasonButtons.lastOrNull() ?: lastAction
            episodes.forEachIndexed { i,v ->
                v.nextFocusLeftId=when {
                    i%episodeCols==0 -> prevPager?.takeIf { it.isFocusable }?.id ?: v.id
                    else -> episodes[i-1].id
                }
                v.nextFocusRightId=when {
                    i==episodes.lastIndex -> nextPager?.takeIf { it.isFocusable }?.id ?: v.id
                    i%episodeCols==episodeCols-1 -> v.id
                    else -> episodes[i+1].id
                }
                v.nextFocusUpId=if(i>=episodeCols) episodes[i-episodeCols].id else aboveEpisodes.id
                v.nextFocusDownId=when {
                    i+episodeCols<episodes.size -> episodes[i+episodeCols].id
                    firstRow!=null && i>=lastRowStart -> firstRow[(i-lastRowStart).coerceAtMost(firstRow.lastIndex)].id
                    else -> v.id
                }
            }
            // 翻页按钮（集数上方）：↑ 回季条/操作行，↓ 进集数第一行；左右互连
            val abovePager: View=seasonButtons.lastOrNull() ?: lastAction
            prevPager?.let { it.nextFocusUpId=abovePager.id; it.nextFocusDownId=episodes.firstOrNull()?.id ?: it.id; it.nextFocusLeftId=it.id; it.nextFocusRightId=nextPager?.takeIf { next -> next.isFocusable }?.id ?: it.id }
            nextPager?.let { it.nextFocusUpId=abovePager.id; it.nextFocusDownId=episodes.firstOrNull()?.id ?: it.id; it.nextFocusLeftId=prevPager?.takeIf { previous -> previous.isFocusable }?.id ?: it.id; it.nextFocusRightId=it.id }
            // 季条 ↑ 回操作行、↓ 进翻页/集数；操作行 ↓ 优先进季条，否则直达翻页或第一集
            seasonButtons.forEachIndexed { index,view ->
                view.nextFocusUpId=lastAction.id
                view.nextFocusDownId=pagerRow?.id ?: episodes.getOrNull(index.coerceAtMost(episodes.lastIndex))?.id ?: View.NO_ID
            }
            val down=seasonButtons.firstOrNull()?.id ?: pagerRow?.id ?: episodes.firstOrNull()?.id ?: firstRow?.firstOrNull()?.id ?: View.NO_ID
            val primary=listOf(play,favorite).filter { it.visibility==View.VISIBLE }
            val secondaryActions=listOf(synopsisButton,jump).filter { it.visibility==View.VISIBLE }
            primary.forEachIndexed { index,view ->
                view.nextFocusLeftId=primary.getOrNull(index-1)?.id ?: view.id
                view.nextFocusRightId=primary.getOrNull(index+1)?.id ?: view.id
                view.nextFocusUpId=back?.id ?: view.id
                view.nextFocusDownId=secondaryActions.getOrNull(index.coerceAtMost(secondaryActions.lastIndex))?.id ?: down
            }
            secondaryActions.forEachIndexed { index,view ->
                view.nextFocusLeftId=secondaryActions.getOrNull(index-1)?.id ?: view.id
                view.nextFocusRightId=secondaryActions.getOrNull(index+1)?.id ?: view.id
                view.nextFocusUpId=primary.getOrNull(index.coerceAtMost(primary.lastIndex))?.id ?: back?.id ?: view.id
                view.nextFocusDownId=down
            }
            back?.nextFocusDownId=firstAction.id
            extraRows.forEachIndexed { rowIndex,cards ->
                cards.forEachIndexed { i,v ->
                    v.nextFocusLeftId=cards[(i-1).coerceAtLeast(0)].id
                    v.nextFocusRightId=cards[(i+1).coerceAtMost(cards.lastIndex)].id
                    v.nextFocusUpId=if(rowIndex>0) extraRows[rowIndex-1].getOrNull(i.coerceAtMost(extraRows[rowIndex-1].lastIndex))?.id ?: (pagerRow?.id ?: firstAction.id)
                        else episodes.getOrNull((lastRowStart+i).coerceAtMost(episodes.lastIndex))?.id ?: (pagerRow?.id ?: firstAction.id)
                    v.nextFocusDownId=if(rowIndex<extraRows.lastIndex) extraRows[rowIndex+1].getOrNull(i.coerceAtMost(extraRows[rowIndex+1].lastIndex))?.id ?: v.id else v.id
                }
            }
        }
        renderExtras(data)
        loadDetailExtras(data,::renderExtras)
        // 翻页后焦点留在翻页按钮上，方便连按继续翻；翻到头/尾时落到另一个仍能用的按钮，
        // 避免焦点停在一个按下没反应的按钮上。
        val pagerTarget=when {
            focusPager<0 -> if(detailEpisodePage<=0) nextPager ?: prevPager else prevPager ?: nextPager
            focusPager>0 -> if(detailEpisodePage>=totalPages-1) prevPager ?: nextPager else nextPager ?: prevPager
            else -> null
        }
        when {
            pagerTarget!=null -> pagerTarget.requestFocus()
            focusEpisode && episodes.isNotEmpty() -> episodes.getOrNull((episodeIndex-pageStart).coerceIn(0,episodes.lastIndex))?.requestFocus()
            else -> firstAction.requestFocus()
        }
    }
    /** 撤掉还没执行的「不兼容自动跳下一集」：用户已经自己动手换集了，不能再被拖回去。 */
    private fun cancelSkipUnplayable() {
        skipUnplayableJob?.let { main.removeCallbacks(it) }; skipUnplayableJob=null
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
        val data=detail ?: return
        if(data.episodes.isEmpty()) {
            Toast.makeText(this,"暂时没有可看的剧集",Toast.LENGTH_SHORT).show()
            return
        }
        if (offlineOnly && offlineDownloads.lookup(data.episodes[index.coerceIn(0, data.episodes.lastIndex)]) == null) {
            PhoneUi.toast(this, "该集尚未离线，请在离线缓存中下载")
            return
        }
        cancelSkipUnplayable()
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        // 切集先撤掉后台整集下载的带宽占用，首帧和当前集缓冲就绪后逐步恢复。
        playbackFirstFrameRendered=false; tvVideoFrameWaitStartedAt=0L
        episodeCacheParallelism=0
        episodeDiskCache?.setDownloadParallelism(0)
        // 从详情页重新进播放器时丢弃上一轮的临时设置；自动连播保持在同一条降档链上。
        if(screen!="player") { resetPlaybackSession(); downgradeEpisode=""; downgradeCount=0 }
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val restarting=screen=="player" && selectedIndex==episodeIndex
        val retrying=recovering || playError
        val prefetched=if(!retrying) ensurePlaybackCache().peek(data.episodes[selectedIndex],effectiveQuality()) else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        stopDanmaku(preserveActive=reuse && selectedIndex==episodeIndex+1)
        if(reuse) {
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            cancelRecovery(); dropQueuedNext(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            // 相邻集直接交接 Media3 已预加载的媒体源，不 stop/clear 破坏样本队列。
            playbackSession?.pauseForSwitch()
            video?.let { android.util.Log.w("HongguoTV","Prefetch episode=${episodeIndex+1} ${it.stats()}") }
            video?.setPlaybackActive(false); video=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer(preservePlaybackCache=true)
        if(retrying) ensurePlaybackCache().invalidate(data.episodes[selectedIndex])
        // 播放页不展示海报，但返回详情仍要复用同一张位图；清理解绑即可，位图由内存回调统一回收。
        artwork.clearPage()
        video=null
        var firstReady=true; var firstFrame=true; var rebufferStart=0L
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        // 主动切集/重进播放页按这次传入的意图重置；自动连播走的 playEpisode 永远是 autoplay=true，
        // 所以连播链不会被上一次的用户暂停状态卡住。
        userPausedIntentionally=!autoplay
        val skipMark=library.skipMark(data.series.id)
        outroSkipSeconds=skipMark?.outroSeconds ?: 0; outroTriggered=false; ffActive=false
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        markPageShown()
        // 走进播放页就是一次正常的挂载/切集：之前 onStop 留下的「原地续播」意图作废
        playbackKeptOnStop=false
        refreshCacheStrip()
        // 手机播放页切集走相邻集交接（reuse），UI 不会重建；标题与集号必须手动刷新，
        // 否则第 2 集起顶部一直停在第 1 集的编号。
        if(phonePlayerLayout()) phonePlayer?.updateEpisode(data)
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        tvPauseButton?.visibility=View.GONE
        retainPlaybackWindow()
        playerScreenOn=true; refreshScreenOn()
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            // 横竖屏共用竖屏已采用的 TextureView，视频输出不再随页面方向切换类型。
            val view=(layoutInflater.inflate(R.layout.playback_player_view,root,false) as PlayerView).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK)
                // 加载圈由页面按首帧与缓冲回调统一控制，沿用竖屏的加载流程。
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
            playerView=view
            if(phonePlayerLayout()) {
                // 手机播放页（竖屏或横屏全屏）：仿第三方参考实现，视频铺满整屏，控件浮在画面上
                val playerUi=phonePlayerOrCreate
                phonePlayerRenderedFullscreen=phoneFullscreenPlayback
                playerUi.render(root,data,fullscreen=phoneFullscreenPlayback)
                // 弹幕层覆盖视频区，但在控件之下；PhonePlayer 会把它的上沿压到标题栏下面。
                // PlayerView 以索引 0 插入视频区最底层，所以先挂弹幕再挂视频也没问题。
                val danmakuLayer=DanmakuView(this)
                danmakuView=danmakuLayer
                danmakuLayer.setBlockedFilter { text -> extras.blocks(text) }
                danmakuLayer.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
                playerUi.attachDanmaku(danmakuLayer)
                playerUi.attachPlayerView(view)
                // 屏幕最底部缓存状态条：三段对应下 1/2/3 集
                cacheStripSegments=buildCacheStrip()
                playerUi.attachCacheStrip(cacheStripHost())
                refreshCacheStrip()
                // 手机版不需要 TV 的 hud/controls/转圈，但仍需初始化引用避免 tick 空指针
                hud=column().apply { visibility=View.GONE }
                playbackTitle=text("",15f).apply { visibility=View.GONE }
                playbackStatus=text("",13f).apply { visibility=View.GONE }
                playbackText=text("",13f).apply { visibility=View.GONE }
                controls=column().apply { visibility=View.GONE }
                progressBar=PlaybackSeekBar(this).apply { max=1000; visibility=View.GONE }
                recoverySpinner=ProgressBar(this,null,android.R.attr.progressBarStyle).apply { DrawableCompat.setTint(indeterminateDrawable,accent); visibility=View.GONE }
                playerScreenOn=true; refreshScreenOn()
            } else {
            root.addView(view,FrameLayout.LayoutParams(-1,-1))
            val danmakuLayer=DanmakuView(this)
            danmakuView=danmakuLayer
            danmakuLayer.setBlockedFilter { text -> extras.blocks(text) }
            danmakuLayer.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
            root.addView(danmakuLayer,FrameLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.38f).toInt(),Gravity.TOP).apply { topMargin=dp(12) })
            tvPauseButton=ImageButton(this).apply {
                setImageResource(R.drawable.ic_pause)
                scaleType=ImageView.ScaleType.FIT_CENTER
                setPadding(dp(18),dp(18),dp(18),dp(18))
                background=GradientDrawable().apply {
                    shape=GradientDrawable.OVAL
                    setColor(Color.argb(150,11,15,24))
                    setStroke(dp(1),Color.argb(110,255,255,255))
                }
                contentDescription="已暂停，点击继续播放"
                isFocusable=false; isFocusableInTouchMode=false
                setOnClickListener { requestPlayback(true) }
                visibility=View.GONE
            }.also { root.addView(it,FrameLayout.LayoutParams(dp(80),dp(80),Gravity.CENTER)) }
            // 参考 TV 播放器的底部渐变信息区，颜色独立于浏览页主题，保证画面上的文字清晰。
            val chromeInset=dp(if(widthDp()<700) 20 else 36)
            hud=column().apply {
                setPadding(chromeInset,dp(14),chromeInset,dp(if(heightDp()<450) 10 else 14))
                background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT,Color.argb(220,11,15,24),Color.argb(250,11,15,24)))
            }
            playbackTitle=text("",if(widthDp()<700) 19f else 23f,Color.WHITE).apply {
                maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD)
            }
            val titleRow=row().apply { gravity=Gravity.CENTER_VERTICAL }
            titleRow.addView(playbackTitle,lp(0,-2).apply { weight=1f })
            val exitPlay=text("✕ 退出",13f,Color.rgb(188,198,214)).apply {
                isFocusable=false; isClickable=true
                setPadding(dp(12),dp(6),dp(12),dp(6))
                background=TvVisual.row(this@MainActivity,false)
                setOnClickListener { returnToDetail() }
                visibility=View.GONE
            }
            playerExit=exitPlay
            titleRow.addView(exitPlay,lp(-2,-2))
            // 剧名和当前集数独立放在顶部，底部仅保留播放进度与操作菜单。
            tvPlaybackHeader=column().apply {
                setPadding(chromeInset,dp(if(heightDp()<450) 12 else 16),chromeInset,dp(20))
                background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(215,11,15,24),Color.argb(150,11,15,24),Color.TRANSPARENT))
                addView(titleRow)
                visibility=View.GONE
            }.also { root.addView(it,FrameLayout.LayoutParams(-1,-2,Gravity.TOP)) }
            val stateRow=row().apply { setPadding(0,dp(4),0,dp(3)) }
            playbackText=text("",13f,Color.rgb(188,198,214)).apply { maxLines=2 }
            stateRow.addView(playbackText,lp(0,-2).apply { weight=1f; rightMargin=dp(14) })
            playbackStatus=text("",12f,Color.rgb(255,183,133)).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
            stateRow.addView(playbackStatus,lp(-2,-2))
            hud.addView(stateRow)
            // 保留宽触摸命中区，轨道按参考 TV 样式使用细线与暖橙定位点。
            progressBar=PlaybackSeekBar(this,trackColor=Color.argb(70,255,255,255),fillColor=TvStyle.accent,
                thumbRadiusDp=4f,trackHeightDp=3f).apply { max=1000 }
            progressBar.onScrubPreview=preview@{ ratio ->
                val p=player ?: return@preview
                val duration=p.duration
                if(duration<=0 || screen!="player") return@preview
                main.removeCallbacks(hideHud)
                playbackText.text="定位到 ${formatTime((duration*ratio).toLong())} / ${formatTime(duration)}"
            }
            progressBar.onScrub=scrub@{ ratio,final ->
                val p=player ?: return@scrub
                val duration=p.duration
                if(duration<=0 || !playbackReady) return@scrub
                if(final) seekTo((duration*ratio).toLong())
            }
            progressBar.onScrollStep={ delta -> seekTo(currentPosition()+delta) }
            hud.addView(progressBar,lp(-1,dp(26)))
            // 鼠标一动就显示退出按钮；遥控一按（见 dispatchKeyEvent）再收回去
            TvPointer.onHover(root) { if(!mouseSeen) { mouseSeen=true; refreshPointerUi() } }
            controls=column().apply { visibility=View.GONE }
            hud.addView(controls,lp(-1,-2).apply { topMargin=dp(4) })
            // 转圈单独挂在根布局正中央，不占底部 HUD 空间；播放失败自动重试时才可见。
            recoverySpinner=ProgressBar(this,null,android.R.attr.progressBarStyle).apply { DrawableCompat.setTint(indeterminateDrawable,accent); visibility=View.GONE }
            root.addView(recoverySpinner,FrameLayout.LayoutParams(dp(56),dp(56),Gravity.CENTER))
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
            // 屏幕最底部缓存状态条：三段对应下 1/2/3 集
            cacheStripSegments=buildCacheStrip()
            root.addView(cacheStripHost(),FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
            refreshCacheStrip()
            }
        }
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        addressRequestedAt=android.os.SystemClock.elapsedRealtime()
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        // hud 默认隐藏，取址期间屏幕是全黑的：单独挂一个转圈，用户至少知道没死机。
        recoverySpinner?.visibility=View.VISIBLE
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null; tvPlaybackMenu=null
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=effectiveQuality()
        // 每集最多自动降两档，换集后重新计数，避免一集失败就把整部剧拖到最低画质。
        if(requestedId!=downgradeEpisode) { downgradeEpisode=requestedId; downgradeCount=0 }
        val beginPlayback: (EpisodePlaybackCache.PreparedEpisode)->Unit={ prepared ->
            val remote=prepared.video
            val diskMeta=prepared.diskMeta
            val sourceKind=when { remote.isLocal -> "离线缓存"; diskMeta==null -> "在线"; diskMeta.done -> "完整缓存"; else -> "部分缓存" }
            RuntimeLog.add("Playback source episode=${selectedIndex+1} source=$sourceKind elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
            video=remote; quality=remote.info.quality
            // 与竖屏一样等首帧后再结束起播加载；横屏沿用页面自己的加载圈。
            if(phonePlayerLayout()) recoverySpinner?.visibility=View.GONE
            val session=preparePlaybackSession()
            val p=session.player
            updatePhoneVideoPosition(p.videoSize)
            session.select(requestedId,selectedIndex,resetSource=restarting)
            p.setPlaybackSpeed(effectiveSpeed())
            var episodeStartedAt=playbackStarted
            var videoPrepareStartedAt=android.os.SystemClock.elapsedRealtime()
            val listener=object: Player.Listener {
                // 页面请求的 generation 在 onStop 时会变，但保留下来的播放器仍使用这个监听器。
                // 按当前监听器身份判定归属，切集或释放后旧回调失效，原地续播后的回调继续有效。
                private fun active()=!isDestroyed && screen=="player" && player===p && playerListener===this
                private fun markReady() {
                    if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                        playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                        return
                    }
                    playbackReady=true; incompatibleSkips=0
                    // 新媒体源已被播放器接管，旧集才允许从过期资源列表回收。
                    switchingVideos.clear(); protectPlaybackVideos()
                    if(firstReady) {
                        firstReady=false; readySince=android.os.SystemClock.elapsedRealtime()
                        episodeDiskCache?.onPlaybackStarted(data.series.id,data.episodes,episodeIndex)
                        android.util.Log.w("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-episodeStartedAt}")
                        startDanmaku()
                        maybePrefetch()
                        // 起播后再补系列信息，继续观看同样保留跨季连播。
                        detail?.let { loadDetailExtras(it) }
                    } else if(rebufferStart>0) {
                        android.util.Log.w("HongguoTV","Rebuffer end episode=${episodeIndex+1} stalledMs=${android.os.SystemClock.elapsedRealtime()-rebufferStart}")
                        rebufferStart=0
                    }
                    if(hud.visibility==View.VISIBLE) updatePlaybackText()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) markReady()
                    refreshTvPlaybackLoading()
                    if(state==Player.STATE_BUFFERING || state==Player.STATE_IDLE) scheduleDiskCache()
                    if(state==Player.STATE_BUFFERING && !firstReady && rebufferStart==0L) { rebufferStart=android.os.SystemClock.elapsedRealtime(); android.util.Log.w("HongguoTV","Rebuffer start episode=${episodeIndex+1} positionMs=${p.currentPosition}") }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        // 自动连播只看用户本人的意图：userPausedIntentionally 只由「用户按暂停」
                        // 与「定时停止」改写，生命周期打断（下拉通知栏/来电/切后台）不会污染它。
                        // 以前读 requestedAutoplay，那个标记被 onPause 置 false 后没有任何路径
                        // 会恢复，所以一集播完就直接停住、不切下一集。
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(userPausedIntentionally) { requestedAutoplay=false; p.pause(); showEndedPanel() }
                        else if(foreground && library.autoNext && adjacentEpisodeIndex(1) != null) adjacentEpisodeIndex(1)?.let { playEpisode(it,savedPosition(it)) }
                        else if(foreground && continueToNextSeason()) { /* season jump in progress */ }
                        else { requestedAutoplay=false; p.pause(); showEndedPanel() }
                    }
                }
                /** 播放器自动切到预挂的下一集：原地交接状态，不再 stop/clearMediaItems，避免重新解析片头与重建解码器。 */
                override fun onMediaItemTransition(mediaItem: MediaItem?,reason: Int) {
                    if(!active() || reason!=Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                    val nextId=mediaItem?.mediaId ?: return
                    if(queuedNextId.isEmpty() || nextId!=queuedNextId) return
                    val fromIndex=queuedFromIndex; val fromDuration=queuedFromDuration
                    if(fromIndex in data.episodes.indices) {
                        library.save(WatchProgress(data.series,data.episodes[fromIndex],fromIndex,fromDuration,fromDuration,true,System.currentTimeMillis()))
                    }
                    val nextVideo=queuedVideo
                    val oldVideo=video
                    episodeIndex=queuedIndex.coerceIn(0,data.episodes.lastIndex); group=episodeIndex/20
                    video=nextVideo; playerVideo=nextVideo; quality=nextVideo?.info?.quality.orEmpty()
                    queuedNextId=""; queuedIndex=-1; queuedVideo=null; queuedFromIndex=-1; queuedFromDuration=0
                    stopDanmaku(preserveActive=true)
                    if(p.currentMediaItemIndex>0) runCatching { p.removeMediaItem(0) }
                    // 为新一集重置所有逐集状态；解码器与播放器实例保持不变。
                    firstReady=true; firstFrame=true; rebufferStart=0; episodeStartedAt=android.os.SystemClock.elapsedRealtime(); videoPrepareStartedAt=episodeStartedAt; readySince=0
                    playbackReady=false; playError=false; incompatibleSkips=0
                    recovery.reset(); autoRecovery=false; cancelRecovery()
                    downgradeEpisode=nextId; downgradeCount=0
                    startPosition=0; pendingSeek=null; outroTriggered=false; ffActive=false
                    outroSkipSeconds=library.skipMark(data.series.id)?.outroSeconds ?: 0
                    // 交接只更新逐集状态，保留用户暂停、生命周期暂停与定时停止的播放意图。
                    library.setWatched(data.series.id,false)
                    playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
                    // 自然连播沿用视频输出，Media3 不保证为每集再次发送首帧回调。
                    // 同步标题与集号时保留连续画面，避免重新盖上封面后一直等不到回调。
                    if(phonePlayerLayout()) phonePlayer?.updateEpisode(data,waitForFirstFrame=false)
                    progressBar.progress=0
                    oldVideo?.setPlaybackActive(false)
                    session.select(nextId,episodeIndex)
                    retainPlaybackWindow()
                    if(foreground && requestedAutoplay && !userPausedIntentionally && !sleepStopped && !pausedForLifecycle && episodePanel==null) p.play()
                    android.util.Log.w("HongguoTV","Playback seamless switch episode=${fromIndex+1}->${episodeIndex+1} previousDurationMs=$fromDuration")
                    val intro=library.skipMark(data.series.id)?.introSeconds ?: 0
                    if(intro>0) {
                        p.seekTo(intro*1000L)
                        Toast.makeText(this@MainActivity,"已跳过片头 $intro 秒",Toast.LENGTH_SHORT).show()
                    }
                    if(p.playbackState==Player.STATE_READY) markReady() else if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    syncMediaSession()
                    maybePrefetch()
                    if(sleepTimer.episodeEnded()) { stopForSleep(false); return }
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if(active()) updatePhoneVideoPosition(videoSize)
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        playbackFirstFrameRendered=true
                        if(phonePlayerLayout()) phonePlayer?.onFirstFrame()
                        refreshTvPlaybackLoading()
                        maybePrefetch()
                        android.util.Log.w("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-episodeStartedAt} prepareMs=${android.os.SystemClock.elapsedRealtime()-videoPrepareStartedAt}")
                    }
                }
                override fun onEvents(player: Player,events: Player.Events) {
                    // 播放状态变化立即同步手机控件，不等待每秒一次的进度刷新。
                    if(active() && events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_PLAYER_ERROR,
                    )) {
                        refreshPhonePlayerNow()
                        refreshTvPauseButton()
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(playing) maybePrefetch(); if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                    if(!active()) return
                    syncMediaSession()
                    // 自动切集的进度回调先于媒体交接，必须先保留屏上弹幕，不能按普通跳转清空。
                    if(reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION && oldPosition.mediaItem?.mediaId!=newPosition.mediaItem?.mediaId) {
                        stopDanmaku(preserveActive=true)
                    } else danmakuView?.seekTo(newPosition.positionMs)
                }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val introSkip=episodeStartPosition(selectedIndex,position)
            val source=session.mediaSource(EpisodePlaybackSession.Episode(requestedId,selectedIndex,maxQuality,remote,introSkip))
            playerVideo?.let { switchingVideos+=it }
            playerVideo=remote; protectPlaybackVideos()
            p.setMediaSource(source,introSkip); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
            if(introSkip>0 && position<=0) Toast.makeText(this,"已跳过片头 ${skipMark!!.introSeconds} 秒",Toast.LENGTH_SHORT).show()
            // 地址到手即准备相邻集；媒体样本仍等当前集积累三秒缓冲后再预加载。
            maybePrefetch()
        }
        // 命中相邻集缓存时直接交接，省掉后台线程排队和再投递一次主线程。
        if(prefetched!=null) {
            try { beginPlayback(prefetched) } catch(problem: Exception) { playerFailure(problem) }
        } else {
            work({ ensurePlaybackCache().load(requestedId,maxQuality) },beginPlayback,{ problem -> playerFailure(problem) })
            // 取址在后台进行时并行初始化播放器；先让播放外壳进入主线程队列。
            val ticket=generation
            main.post { if(!isDestroyed && foreground && screen=="player" && generation==ticket) preparePlaybackSession() }
        }
    }
    /** 暂停标记独立于底部 HUD，遥控焦点继续留给原有按键与操作菜单。 */
    private fun refreshTvPauseButton() {
        val button=tvPauseButton ?: return
        val p=player
        val paused=screen=="player" && !phonePlayerLayout() && foreground && !playError &&
            playbackReady && playbackFirstFrameRendered && !requestedAutoplay &&
            p!=null && p.playbackState==Player.STATE_READY && !p.playWhenReady && episodePanel==null
        button.visibility=if(paused) View.VISIBLE else View.GONE
    }
    /** 横屏与竖屏一样用首帧、缓冲状态驱动加载提示，取到地址后仍等待视频画面。 */
    private fun refreshTvPlaybackLoading() {
        refreshTvPauseButton()
        if(screen!="player" || phonePlayerLayout() || playError) return
        val p=player
        val loading=video==null || !playbackFirstFrameRendered || p==null ||
            p.playbackState==Player.STATE_BUFFERING || p.playbackState==Player.STATE_IDLE
        recoverySpinner?.visibility=if(loading) View.VISIBLE else View.GONE
    }
    /** 慢首帧单独记录；是否降档由播放器实际报告的解码错误决定。 */
    private fun checkTvVideoFirstFrame() {
        val p=player
        val view=playerView
        val surfaceReady=when(val output=view?.videoSurfaceView) {
            is TextureView -> output.isAvailable
            is SurfaceView -> output.holder.surface.isValid
            else -> false
        }
        val waiting=!phonePlayerLayout() && foreground && !playError && !playbackFirstFrameRendered &&
            p!=null && p.playWhenReady && !pausedForLifecycle && view?.isShown==true && surfaceReady &&
            p.currentMediaItem?.mediaId==detail?.episodes?.getOrNull(episodeIndex) &&
            (p.playbackState==Player.STATE_BUFFERING || p.playbackState==Player.STATE_READY) &&
            p.videoFormat!=null && (p.bufferedPosition-p.currentPosition>=1_000L || p.isPlaying)
        if(!waiting) { tvVideoFrameWaitStartedAt=0L; return }
        val now=android.os.SystemClock.elapsedRealtime()
        if(tvVideoFrameWaitStartedAt==0L) { tvVideoFrameWaitStartedAt=now; return }
        if(tvVideoFrameWaitStartedAt<0L || now-tvVideoFrameWaitStartedAt<15_000L) return
        tvVideoFrameWaitStartedAt=-1L
        android.util.Log.w("HongguoTV","Video first frame delayed episode=${episodeIndex+1} format=${p?.videoFormat?.sampleMimeType} bufferedMs=${p?.let { it.bufferedPosition-it.currentPosition }}")
    }
    private fun preparePlaybackSession(): EpisodePlaybackSession {
        val session=playbackSession ?: EpisodePlaybackSession(this,disk={ remote ->
            episodeDiskCache?.takeIf { ensurePlaybackCache().canReadDisk(remote.info.idHint,remote.info.qualityNumber) }
        }).also { playbackSession=it }
        player=session.player
        if(playerView?.player!==session.player) playerView?.player=session.player
        return session
    }
    private fun episodeStartPosition(index: Int,position: Long=savedPosition(index)): Long =
        if(position>0) position else (detail?.series?.id?.let { library.skipMark(it)?.introSeconds } ?: 0)*1000L
    private fun protectPlaybackVideos() {
        ensurePlaybackCache().protect(switchingVideos+listOfNotNull(playerVideo,queuedVideo))
    }
    private fun pausePrefetchForMemory(delayMillis: Long) {
        prefetchMemoryPausedUntil=maxOf(prefetchMemoryPausedUntil,android.os.SystemClock.elapsedRealtime()+delayMillis)
        cancelPrefetch()
    }
    /** 冷却期覆盖所有自动预取入口，恢复前再检查系统内存，避免一秒后又重新占满。 */
    private fun prefetchMemoryReady(): Boolean {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now<prefetchMemoryPausedUntil) return false
        if(now>=nextPrefetchMemoryCheckAt) {
            val manager=getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            nextPrefetchMemoryCheckAt=now+5_000L
            val info=ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            if(info.lowMemory) { pausePrefetchForMemory(60_000L); return false }
        }
        return true
    }
    /** 取消相邻集预取，但不打断当前视频的数据源。 */
    private fun cancelPrefetch() {
        dropQueuedNext(preserveCurrent=true)
        playbackSession?.setPreloadingEnabled(false)
        video?.setPlaybackActive(false)
        // 后台下载只停不删：切界面 / 退到后台 / 播放失败都走这里，
        // 用 schedule(emptyList(), …, 只留当前集) 会把已下好的下三集连文件一起清掉。
        episodeDiskCache?.pause()
        val current=detail?.episodes?.getOrNull(episodeIndex)
        if(current==null) {
            // 启动阶段网络/内存回调可能早于本机设置加载；空窗口不读取清晰度设置。
            playbackSession?.retain(emptySet(),0)
            ensurePlaybackCache().clear()
            return
        }
        val ids=setOf(current)
        playbackSession?.retain(ids,effectiveQuality())
        ensurePlaybackCache().retain(ids,effectiveQuality())
    }
    private fun retainPlaybackWindow() {
        val data=detail ?: return
        val from=(episodeIndex-1).coerceAtLeast(0)
        val to=(episodeIndex+2).coerceAtMost(data.episodes.size)
        val ids=data.episodes.subList(from,to).toSet()
        protectPlaybackVideos()
        playbackSession?.retain(ids,effectiveQuality())
        ensurePlaybackCache().retain(ids,effectiveQuality())
    }
    /** 开关只控制后三集下载；缓存扫描走后台，不阻塞本机记录加载后的首屏。 */
    private fun applyEpisodeCacheSetting(force: Boolean=false) {
        val want=library.episodeCache
        if(!force && want==episodeCacheOn && episodeDiskCache!=null) return
        if(episodeDiskCache==null) {
            if(episodeCacheLoading) return
            episodeCacheLoading=true
            val application=applicationContext
            val cacheDirectory=cacheDir
            val directory=java.io.File(cacheDirectory,"episode-cache")
            val http=repository.http
            io.execute {
                if(isDestroyed || isFinishing) return@execute
                val result=runCatching {
                    EpisodeDiskCache(http,directory,
                        freeBytes={ runCatching { android.os.StatFs(cacheDirectory.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE) },
                        lowMemory={ (application.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.let { it.isLowRamDevice || it.memoryClass<=128 } ?: false },
                        resolver={ id,q -> ensurePlaybackCache().resolveStream(id,q) },
                        onState={ reason -> RuntimeLog.add(if(reason.isEmpty()) "cache state: 调度正常" else "cache state: 未下载 · $reason") },
                        onLog={ message -> RuntimeLog.add(message) }).also { it.setDownloadParallelism(0) }
                }
                if(isDestroyed || isFinishing) { result.getOrNull()?.close(); return@execute }
                main.post {
                    episodeCacheLoading=false
                    if(isDestroyed || isFinishing) { result.getOrNull()?.close(); return@post }
                    result.onSuccess { cache ->
                        // 三集并发时节流刷新条带；库与播放器均可在缓存尚未就绪时正常使用。
                        cache.onProgress={ _,_,_ ->
                            val now=android.os.SystemClock.elapsedRealtime()
                            if(now-cacheStripPaintedAt>=200) {
                                cacheStripPaintedAt=now
                                main.post { if(!isDestroyed) refreshCacheStrip() }
                            }
                        }
                        episodeCacheParallelism=0
                        episodeDiskCache=cache
                        ensurePlaybackCache().onInvalidate={ id -> cache.invalidate(id) }
                        // 扫描期间用户可能已经切过开关，按此刻的偏好重新应用。
                        applyEpisodeCacheSetting(force=true)
                    }.onFailure { failure ->
                        RuntimeLog.add("视频缓存初始化失败：${failure.javaClass.simpleName}，本机记录与首页仍可使用")
                    }
                }
            }
            return
        }
        episodeCacheOn=want
        // 数据源始终可读取落盘池；停用预缓存只暂停网络任务。
        if(want) scheduleDiskCache() else {
            episodeDiskCache?.pause()
            episodeCacheParallelism=0
        }
        refreshCacheStrip()
    }
    /** 屏幕最底部缓存状态条：三段对应下 1/2/3 集，半透明强调色=已落盘比例，淡灰色=未离线。
     *  全屏时常驻底部，不随播放控件隐藏。 */
    private fun refreshCacheStrip() {
        val strip=cacheStripView ?: return
        val segments=cacheStripSegments ?: return
        val data=detail
        val visible=!offlineOnly && library.episodeCache && data!=null && screen=="player"
        strip.visibility=if(visible) View.VISIBLE else View.GONE
        // 隐藏的缓存条不会参与测量；不再为零宽度反复向主线程投递刷新任务。
        if(!visible || data==null) return
        val quality=effectiveQuality()
        val cache=episodeDiskCache
        // 切集后三段会左移，先保存各集的显示进度，避免已缓存的相邻集重新从零播放动画。
        val displayed=segments.mapNotNull { segment ->
            segment.cacheKey?.let { it to segment.displayedProgress }
        }.toMap()
        segments.forEachIndexed { offset,segment ->
            val id=data.episodes.getOrNull(episodeIndex+offset+1)
            if(id==null) {
                segment.visibility=View.GONE
            } else {
                val key=id to quality
                val ratio=cache?.progress(id,quality) ?: 0f
                segment.updateProgress(key,ratio,displayed[key] ?: 0f)
                segment.visibility=View.VISIBLE
            }
        }
    }
    /** 构建缓存状态条（尚未挂载）：三段各自平滑追赶真实下载进度。 */
    private fun buildCacheStrip(): List<CacheProgressSegment> {
        val strip=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
        }
        val segments=(0 until 3).map { CacheProgressSegment(this) }
        segments.forEachIndexed { index,segment ->
            strip.addView(segment,LinearLayout.LayoutParams(0,dp(cacheStripHeightDp).coerceAtLeast(1),1f).apply {
                if(index>0) leftMargin=dp(1)
            })
        }
        cacheStripView=strip
        return segments
    }
    /** 状态条挂进播放页：条本身贴底且不挡控件，上方不再有任何文字提示。 */
    private fun cacheStripHost(): View {
        val strip=cacheStripView ?: LinearLayout(this)
        return column().apply {
            gravity=Gravity.BOTTOM
            addView(strip,LinearLayout.LayoutParams(-1,dp(cacheStripHeightDp).coerceAtLeast(1)))
        }
    }
    /** 仅主动下载下三集；上一集和当前集保留已有缓存，避免与当前在线读取重复下载。 */
    private fun scheduleDiskCache() {
        if (offlineOnly || video?.isLocal == true) { episodeDiskCache?.pause(); return }
        // 提前返回的每一种情况都记一笔（同一原因只记一次）：用户导出的日志里必须能看到
        // 「为什么三条进度条一直不填」，而不是只有一片空白
        fun idle(reason: String) {
            lastIdleForSummary=reason
            if(lastScheduleIdle==reason) return
            lastScheduleIdle=reason
            RuntimeLog.add("cache idle: $reason")
        }
        if(!episodeCacheOn) { idle("缓存后三集开关关闭"); return }
        val data=detail
        if(data==null) { idle("没有剧集详情"); return }
        val quality=effectiveQuality()
        val cache=episodeDiskCache
        if(cache==null) { idle("缓存池尚未创建，可在设置里开关一次"); return }
        if(!networkAvailable()) { cache.setDownloadParallelism(0); episodeCacheParallelism=0; idle("当前无网络"); return }
        if(!prefetchMemoryReady()) { cache.setDownloadParallelism(0); episodeCacheParallelism=0; idle("内存紧张，预载暂停"); return }
        val p=player
        val currentId=data.episodes.getOrNull(episodeIndex)
        val ready=p!=null && p.currentMediaItem?.mediaId==currentId && playbackReady && playbackFirstFrameRendered && !playError && p.playbackState==Player.STATE_READY
        val bufferedMs=if(ready) ((p!!.bufferedPosition-p.currentPosition).coerceAtLeast(0).toDouble()/p.playbackParameters.speed.coerceAtLeast(.1f)).toLong() else 0L
        val parallelism=when {
            !ready -> 0
            p!!.bufferedPercentage==100 || bufferedMs>=12_000L -> EpisodeDiskCache.MAX_PARALLEL
            episodeCacheParallelism==EpisodeDiskCache.MAX_PARALLEL && bufferedMs>=8_000L -> EpisodeDiskCache.MAX_PARALLEL
            bufferedMs>=5_000L || episodeCacheParallelism>0 && bufferedMs>=3_000L -> 1
            else -> 0
        }
        episodeCacheParallelism=parallelism
        if(parallelism==0) { cache.setDownloadParallelism(0); idle("优先加载当前集，缓冲充足后继续缓存后三集"); return }
        val ordered=listOf(episodeIndex+1,episodeIndex+2,episodeIndex+3)
            .mapNotNull { data.episodes.getOrNull(it) }
        // 排上调度：清掉上次的 idle 记录，之后若再卡住还能重新记一笔
        lastScheduleIdle=""
        lastIdleForSummary=""
        // 新窗口与并发预算一起生效，避免恢复时先为上一集的旧队列发起网络请求。
        cache.schedule(ordered,quality,parallelism,seriesId=data.series.id)
    }
    /** 移除自动连播队列时保留相邻集的媒体源，手动切集继续复用。 */
    private fun dropQueuedNext(preserveCurrent: Boolean=false) {
        val p=player
        if(queuedNextId.isNotEmpty() && p!=null) {
            for(i in 0 until p.mediaItemCount) {
                if(p.getMediaItemAt(i).mediaId==queuedNextId) {
                    // 自动交接可能已经把当前索引移到 1；只能删除尚未播放的下一项。
                    // 已交接时保留元数据，交给 onMediaItemTransition 完成集号与资源所有权更新。
                    if(preserveCurrent && i==p.currentMediaItemIndex) return
                    if(i>p.currentMediaItemIndex) p.removeMediaItem(i)
                    break
                }
            }
        }
        if(queuedVideo!==video) queuedVideo?.setPlaybackActive(false)
        queuedVideo=null
        queuedNextId=""; queuedIndex=-1; queuedFromIndex=-1; queuedFromDuration=0
        protectPlaybackVideos()
    }
    /** 自然连播沿用队列；有续播位置或片头跳过时交给同一预加载媒体源按目标位置起播。 */
    private fun handOffPrefetched(id: String,quality: Int) {
        if(isDestroyed || screen!="player" || playError || queuedNextId.isNotEmpty()) return
        val p=player ?: return; val data=detail ?: return
        val session=playbackSession ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || sleepStopped || pausedForLifecycle || episodePanel!=null) return
        if(episodeIndex>=data.episodes.lastIndex || episodeStartPosition(episodeIndex+1)>0) return
        if(data.episodes[episodeIndex]!=p.currentMediaItem?.mediaId || data.episodes[episodeIndex+1]!=id) return
        if(effectiveQuality()!=quality) return
        val state=p.playbackState
        if(state==Player.STATE_IDLE || state==Player.STATE_ENDED) return
        val remote=ensurePlaybackCache().peek(id,quality)?.video ?: return
        val source=session.mediaSource(EpisodePlaybackSession.Episode(id,episodeIndex+1,quality,remote,0))
        queuedNextId=id; queuedIndex=episodeIndex+1; queuedVideo=remote
        queuedFromIndex=episodeIndex; queuedFromDuration=p.duration.coerceAtLeast(0)
        protectPlaybackVideos()
        p.addMediaSource(source)
        android.util.Log.i("HongguoTV","Next episode queued episode=${queuedIndex+1} quality=$quality")
    }
    private fun maybePrefetch() {
        // 离线入口仅由结束/切集事件加载下一份本地文件，不发起在线预取。
        if (offlineOnly) return
        // 整集缓存根据当前集缓冲量恢复 1～3 路下载；起播和再次缓冲时先让出带宽。
        if(foreground && screen=="player") scheduleDiskCache()
        val p=player ?: return; val data=detail ?: return
        val session=playbackSession ?: return
        if(queuedNextId.isNotEmpty() && (!library.autoNext || sleepStopped)) dropQueuedNext(preserveCurrent=true)
        if(!foreground || !p.playWhenReady || playError || sleepStopped || pausedForLifecycle || video==null) return
        // 电视先让当前集出画面并积累三秒缓冲，再准备相邻集的地址、索引和媒体样本。
        // 仅限制 Media3 样本预载仍会提前发起取址与分块读取，在低性能电视上挤占起播资源。
        if(!touchPlayback) {
            if(!playbackReady || !playbackFirstFrameRendered || p.playbackState!=Player.STATE_READY) return
            val bufferedMs=(p.bufferedPosition-p.currentPosition).coerceAtLeast(0).toDouble()/p.playbackParameters.speed.coerceAtLeast(.1f)
            if(p.bufferedPercentage<100 && bufferedMs<3_000L) return
        }
        if(!networkAvailable()) return
        if(!prefetchMemoryReady()) return
        if(data.episodes.getOrNull(episodeIndex)!=p.currentMediaItem?.mediaId) return
        retainPlaybackWindow()
        val quality=effectiveQuality()
        val next=data.episodes.getOrNull(episodeIndex+1)
        val previous=data.episodes.getOrNull(episodeIndex-1)
        // 队列已持有的下一集保持原地址与媒体源，不因十分钟缓存时限到达而重取、关闭。
        // 其他相邻集仍优先下一集，并按真实续播/跳片头位置预加载。
        if(next!=null && next!=queuedNextId) ensurePlaybackCache().prefetch(next,quality)
        if(previous!=null && (next==null || next==queuedNextId || !ensurePlaybackCache().isLoading(next,quality))) {
            ensurePlaybackCache().prefetch(previous,quality)
        }
        video?.setPlaybackActive(true)
        session.setPreloadingEnabled(true)
        val neighbors=listOf(episodeIndex+1,episodeIndex-1).mapNotNull { index ->
            val id=data.episodes.getOrNull(index) ?: return@mapNotNull null
            val remote=(if(id==queuedNextId) queuedVideo else ensurePlaybackCache().peek(id,quality)?.video) ?: return@mapNotNull null
            EpisodePlaybackSession.Episode(id,index,quality,remote,episodeStartPosition(index))
        }
        session.updatePreloads(neighbors)
        if(next!=null && library.autoNext && PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) {
            handOffPrefetched(next,quality)
        }
    }
    private fun networkAvailable(): Boolean {
        if(Build.VERSION.SDK_INT>=23) return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
        @Suppress("DEPRECATION") return connectivity.activeNetworkInfo?.isConnected==true
    }
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        // 预算耗尽：自动重试彻底走不通，把手动面板摆出来让用户决定。
        if(!recovery.remaining) {
            autoRecovery=false
            playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"
            showManualRecoveryPanel(incompatible=false,decoding=false)
            return
        }
        if (offlineOnly) {
            autoRecovery=false
            playbackText.text="离线文件无法播放，请删除后重新下载"
            showManualRecoveryPanel(incompatible=false,decoding=false)
            return
        }
        // 网络断开：保持转圈等联网，联网回调会重新调度，不弹按钮打扰用户。
        if(!networkAvailable()) {
            playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"
            return
        }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    /** 播放取流用的档位：本会话降过档就用降档值，否则用用户设置值。 */
    private fun effectiveQuality(): Int = sessionQuality ?: library.maxQuality
    /** 播放中实际生效的倍速与画面：本会话改过就用会话值，否则回落到全局默认。 */
    private fun effectiveSpeed(): Float = sessionSpeed ?: library.playbackSpeed
    private fun effectiveFrame(): VideoFrameMode = sessionFrame ?: library.frameMode
    /** 退出播放页时把「只对当前这部剧生效」的临时设置全部丢掉，回到全局默认。 */
    private fun resetPlaybackSession() { sessionQuality=null; sessionSpeed=null; sessionFrame=null }
    /** 临时降档只影响本次播放，不写回用户设置，避免一集片源坏了就把全局画质永久拉低。 */
    private fun applySessionDowngrade(target: Int) {
        sessionQuality=target; downgradeCount++
        android.util.Log.w("HongguoTV","Auto downgrade to ${target}P episode=${episodeIndex+1} attempt=$downgradeCount actual=$quality")
        playEpisode(episodeIndex,retryPosition,retryAutoplay)
    }
    /** 每集最多自动降两档：解码失败立即降，其余错误等同档重试预算用尽再降。 */
    private fun autoDowngradeTarget(decoding: Boolean,retryable: Boolean): Int? {
        if(downgradeCount>=2) return null
        if(!decoding && !(retryable && !recovery.remaining)) return null
        return QualityDowngrade.next(QualityDowngrade.parse(quality),effectiveQuality())
    }
    private fun playerFailure(problem: Throwable) {
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        // 失败时默认先把按钮都藏起来，只留转圈；只有「自动重试彻底走不通」才把手动面板摆出来。
        playError=true; player?.pause(); refreshTvPauseButton(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE
        tvPlaybackHeader?.visibility=View.VISIBLE
        controls.removeAllViews(); controls.visibility=View.GONE; recoverySpinner?.visibility=View.GONE; panel=true
        val incompatible=problem is UnplayableVideoException
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=!incompatible && (if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException)
        playbackText.text=when {
            incompatible -> "本集片源暂不兼容：平台未提供电视可解码的清晰度，可播放下一集。"
            decoding -> "电视无法解码当前视频，可尝试更低清晰度。"
            else -> "播放失败，可重试或切换清晰度。"
        }
        // 解码失败同档重试没有意义，同档重试预算又已用尽时也不该再空转：两种情况都自动退一档。
        val lower=if(incompatible || sleepStopped) null else autoDowngradeTarget(decoding,retryable)
        when {
            lower!=null -> {
                // 自动降档：600ms 后直接换清晰度重放，期间用转圈提示，不打扰用户。
                playbackText.text="当前清晰度无法播放，正在自动切换到 ${lower}P"
                recoverySpinner?.visibility=View.VISIBLE
                autoRecovery=false
                main.postDelayed({ if(foreground && screen=="player" && playError) applySessionDowngrade(lower) },600)
            }
            retryable && !sleepStopped -> {
                // 可重试：静默自动重试，界面只显示转圈 + 倒计时，不弹按钮。
                // 用户按确认键可随时取消并切到手动面板（见 dispatchKeyEvent）。
                playbackText.text="准备恢复播放…"
                recoverySpinner?.visibility=View.VISIBLE
                autoRecovery=true; scheduleRecovery()
            }
            else -> showManualRecoveryPanel(incompatible,decoding)
        }
        android.util.Log.e("HongguoTV","Playback failure: ${problem.javaClass.simpleName} msg=${problem.message}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else "")+(if(problem is com.hongguotv.core.UnplayableVideoException && problem.diagnostic.isNotEmpty()) " "+problem.diagnostic else ""), problem)
    }
    /** 自动重试走不通（预算耗尽 / 网络异常 / 不兼容 / 解码失败）时，才把手动操作按钮摆出来。 */
    private fun showManualRecoveryPanel(incompatible: Boolean,decoding: Boolean) {
        recoverySpinner?.visibility=View.GONE
        controls.removeAllViews(); controls.visibility=View.VISIBLE
        val actions=row(); controls.addView(actions)
        if(incompatible) {
            val hasNext=episodeIndex<detail?.episodes.orEmpty().lastIndex
            val hasPrev=episodeIndex>0
            // 本集放不出来时用户最想做的往往是退回上一集，只给「下一集」等于把人往前逼。
            // 上一集按钮放在最前：点错了也能原路退回去，自动跳集期间同样可用来打断。
            val prev=if(hasPrev) addButton(actions,"播放上一集") { playEpisode(episodeIndex-1,savedPosition(episodeIndex-1)) } else null
            if(hasNext && extras.autoSkipUnplayable && incompatibleSkips<5) {
                // 整批集数可能都是不可解码片源：最多连跳 5 集，避免无限空转
                incompatibleSkips++
                playbackText.text="本集片源暂不兼容，自动跳过 · 已跳过 $incompatibleSkips 集"
                addButton(actions,"返回选集") { returnToDetail(true) }
                val job=Runnable { skipUnplayableJob=null; if(foreground && screen=="player" && playError) playEpisode(episodeIndex+1) }
                skipUnplayableJob=job
                main.postDelayed(job,1200)
            } else {
                val next=if(hasNext) addButton(actions,"播放下一集") { playEpisode(episodeIndex+1) } else null
                val back=addButton(actions,"返回选集") { returnToDetail(true) }
                // 焦点优先仍给「下一集」；没有下一集时才退到上一集，最后才是返回选集。
                (next ?: prev ?: back).requestFocus()
            }
        } else {
            addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
            // 手动降一档：档位按当前实际播放档位推算，不再写死 720P。
            val lower=QualityDowngrade.next(QualityDowngrade.parse(quality),effectiveQuality())
            if(lower!=null) addButton(actions,"切换到 ${lower}P") {
                // 只改本次播放的档位，不动用户的全局默认
                sessionQuality=lower; downgradeCount=0
                playEpisode(episodeIndex,retryPosition,retryAutoplay)
            }
            // 已经是 480P（最低档）时上面那个按钮不会出现，但恰恰是这种片源，
            // 用户最需要一个「换一档再试」的入口——所以固定摆一个能选全部档位的按钮。
            lateinit var pick: TextView
            pick=addButton(actions,"切换清晰度") { showQualityPicker(pick,true) }
            if(lower==null) pick.requestFocus()
            addButton(actions,"返回选集") { returnToDetail(true) }
        }
    }
    /** 用户在自动重试转圈时主动打断：停掉定时器，切到手动面板让用户决定下一步。 */
    private fun stopAutoRecoveryAndShowPanel() {
        cancelRecovery()
        playbackText.text="已取消自动重试，观看位置已保留。"
        showManualRecoveryPanel(incompatible=false,decoding=false)
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
        tvPlaybackHeader?.visibility=View.GONE
    }
    /** 播放屏鼠标手势：垂直滑动换集（仿红果原版，不弹选集面板），单击呼出/收起控制条。挂在根布局上，非播放屏直接放行。 */
    private fun setupMouseGestures() {
        val gestures=object: GestureDetector.SimpleOnGestureListener() {
            private var locked=false; private var total=0f
            fun beginStroke() { locked=false; total=0f }
            override fun onDown(e: MotionEvent)=true
            override fun onScroll(e1: MotionEvent?,e2: MotionEvent,dx: Float,dy: Float): Boolean {
                if(screen!="player" || locked) return true
                total+=dy
                // 累计位移超过屏幕高 20% 触发一次，本手势内不再重复触发（防慢慢拖动连切）。
                // 注意 onScroll 的 dy 是「上次坐标−当前坐标」（AOSP 反号约定）：指针上移 dy>0，
                // 所以 total>0 才是上滑=下一集；onFling 用的是速度原始符号（上滑为负），两条路不要混。
                if(Math.abs(total)>resources.displayMetrics.heightPixels*0.2f) {
                    locked=true
                    skipEpisode(if(total>0) 1 else -1)
                }
                return true
            }
            override fun onFling(e1: MotionEvent?,e2: MotionEvent,vx: Float,vy: Float): Boolean {
                if(screen!="player" || locked) return false
                // 快速轻扫位移可能不够 20%，用速度兜底；要求纵向明显占优，避免误伤横向拖动
                if(Math.abs(vy)>Math.abs(vx)*1.4f && Math.abs(vy)>2000f) {
                    locked=true
                    skipEpisode(if(vy<0) 1 else -1)
                }
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if(screen!="player" || playError) return false
                if(touchPlayback) {
                    if(hud.visibility==View.VISIBLE && panel) hidePlaybackOverlay() else showPanel()
                } else if(hud.visibility==View.VISIBLE && !panel) hidePlaybackOverlay() else showHud()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if(!touchPlayback || screen!="player" || playError) return false
                togglePlayback()
                return true
            }
        }
        val detector=GestureDetector(this,gestures)
        root.setOnTouchListener { _,e ->
            if(screen!="player") return@setOnTouchListener false
            // 鼠标点一下就记住：控制条上的退出按钮跟着出现
            if(TvPointer.isPointer(e) && !mouseSeen) { mouseSeen=true; refreshPointerUi() }
            // 鼠标右键退出播放。TV 上很多遥控器把右键映射成 BACK（那条走 dispatchKeyEvent），
            // 这里接的是真正的鼠标次级键：插着鼠标的人下意识右键，不该什么反应都没有。
            if(e.actionMasked==MotionEvent.ACTION_DOWN && (e.buttonState and MotionEvent.BUTTON_SECONDARY)!=0) {
                returnToDetail()
                return@setOnTouchListener true
            }
            when(e.actionMasked) {
                // 只在按下时重置：抬起后 onFling 才回调，若在 UP 重置会解锁导致 fling 再触发一次（一次滑动切两集）
                MotionEvent.ACTION_DOWN -> gestures.beginStroke()
            }
            detector.onTouchEvent(e)
        }
    }

    /** 沿用原有鼠标退出入口，不为手机额外显示按钮。 */
    private fun refreshPointerUi() {
        playerExit?.visibility=if(mouseSeen) View.VISIBLE else View.GONE
    }

    private fun canAutoHideHud(): Boolean {
        // 电视沿用原来的控制条显隐条件，触屏拖动与弹窗保护仅作用于手机。
        if(!touchPlayback) return !panel
        return (!panel || transportPlay?.isAttachedToWindow==true) && !progressBar.scrubbing && TvDialog.topmostDialog()==null
    }
    private fun scheduleHideHud() {
        main.removeCallbacks(hideHud)
        val allow=if(touchPlayback) canAutoHideHud() && !playError else !panel
        if(screen=="player" && allow && !ffActive && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(!activityInitialized) return super.dispatchTouchEvent(event)
        if(!touchPlayback) return super.dispatchTouchEvent(event)
        if(screen=="player" && event.actionMasked==MotionEvent.ACTION_DOWN) main.removeCallbacks(hideHud)
        val handled=super.dispatchTouchEvent(event)
        if(screen=="player" && hud.visibility==View.VISIBLE && (event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)) scheduleHideHud()
        return handled
    }
    private fun showHud() {
        if(screen!="player") return
        refreshPointerUi()
        hud.visibility=View.VISIBLE
        tvPlaybackHeader?.visibility=View.VISIBLE
        // 显示播放控件时同步最新缓存进度。
        refreshCacheStrip()
        updatePlaybackText(); scheduleHideHud()
    }
    private fun playButtonLabel(): String {
        val playing=if(touchPlayback && !playbackReady) requestedAutoplay else player?.playWhenReady==true
        return if(playing) "暂停" else "播放"
    }
    private fun updatePlaybackText() {
        refreshTvPauseButton()
        val tvMenu=tvPlaybackMenu?.takeIf { it.parent===controls }
        if(tvMenu!=null) {
            val danmakuOn=library.danmaku
            tvMenu.update(PlaybackSpeed.label(effectiveSpeed()),quality.ifBlank { "${effectiveQuality()}P" },danmakuOn,player?.playWhenReady ?: requestedAutoplay)
        } else transportPlay?.text=playButtonLabel()
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!="player" || playError) return
        // 拖动进度条时别用播放位置盖掉「定位到 12:34」：手指还按着，文字每秒跳回去像坏了
        if(progressBar.scrubbing) return
        val danmakuOn=library.danmaku
        playbackStatus.text=listOfNotNull(quality.ifBlank { null },PlaybackSpeed.label(player?.playbackParameters?.speed ?: effectiveSpeed()),"弹幕 "+(if(danmakuOn) "开" else "关")).joinToString("   ·   ")
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) {
            // 取址失败后的自动重试每轮都要重走一遍接口，界面上必须看得见「还在动」，
            // 否则和真的卡死无法区分：带上重试次数与耗时。
            val elapsed=if(addressRequestedAt<=0) 0L else (android.os.SystemClock.elapsedRealtime()-addressRequestedAt)/1000
            val pauseHint=if(!requestedAutoplay) "准备后保持暂停 · " else ""
            val fetching=when {
                video!=null -> "正在缓冲…"
                recovery.attempts>0 -> "正在获取播放地址…（自动重试 ${recovery.attempts}/3 · 已等 ${elapsed} 秒）"
                elapsed>=8 -> "正在获取播放地址…（已等 ${elapsed} 秒，网络或片源较慢）"
                else -> "正在获取播放地址…"
            }
            playbackText.text=pauseHint+fetching
            return
        }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { ffActive -> "3× 快进中"; p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        // 用户亲手按的播放/暂停：只有这里（和定时停止）能改写自动连播的判定依据。
        userPausedIntentionally=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        refreshPhonePlayerNow()
        playerScreenOn=play; refreshScreenOn()
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null && tvPlaybackMenu?.isAttachedToWindow!=true) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    /** 返回是否真的切换了集数（越界返回 false），切换成功时短暂显示 hud 反馈。 */
    private fun skipEpisode(direction: Int): Boolean {
        if(!foreground || screen!="player") return false
        val target=adjacentEpisodeIndex(direction) ?: return false
        playEpisode(target,savedPosition(target)); showHud(); return true
    }
    private fun syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),effectiveSpeed(),state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep(saveCurrent: Boolean=true) {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        // 定时停止是用户设定的「到点就停」，等同于主动暂停：不能再自动连播下一集。
        userPausedIntentionally=true
        cancelRecovery(); player?.pause(); cancelPrefetch(); if(saveCurrent) saveProgress()
        playerScreenOn=false; refreshScreenOn()
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(effectiveFrame()) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        if(PhoneDialog.shouldUse(this)) {
            val current=if(inPlayer) effectiveFrame() else library.frameMode
            PhoneDialog.choose(this,if(inPlayer) "本剧画面模式" else "画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),{ index ->
                if(inPlayer) sessionFrame=VideoFrameMode.entries[index] else library.frameMode=VideoFrameMode.entries[index]
                playerView?.resizeMode=frameResizeMode()
                phoneRefreshSettingsValues()
                if(inPlayer) refreshPhonePlayerNow()
            },focusIndex=VideoFrameMode.entries.indexOf(current)).show()
            return
        }
        tvTools.choose(if(inPlayer) "本剧画面模式" else "全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            // 播放中只改这一部剧，全局默认留给设置页
            if(inPlayer) sessionFrame=VideoFrameMode.entries[index] else library.frameMode=VideoFrameMode.entries[index]
            playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+effectiveFrame().label
        })
    }
    private fun applyDanmakuPrefs()=danmakuView?.applyPrefs(library.danmakuOpacity,library.danmakuFont,library.danmakuSpeed,library.danmakuRows)
    private fun resetDanmakuPrefs() {
        library.danmakuOpacity=DanmakuPrefs.OPACITY_DEFAULT; library.danmakuFont=DanmakuPrefs.FONT_DEFAULT
        library.danmakuSpeed=DanmakuPrefs.SPEED_DEFAULT; library.danmakuRows=DanmakuPrefs.ROWS_DEFAULT
        applyDanmakuPrefs()
    }
    private fun showDanmakuOpacityPicker(anchor: View?,done: (()->Unit)?=null) {
        val options=listOf(100,90,80,70,60,50,40,30,21)
        val current=options.indexOf(library.danmakuOpacity).coerceAtLeast(0)
        // 与清晰度选择器同一样式：当前值行内标注「· 当前」，开窗焦点直接落在它上面
        tvTools.choose("弹幕不透明度",options.map { "$it%"+(if(it==library.danmakuOpacity) "   ·   当前" else "") },anchor,{ choice ->
            library.danmakuOpacity=options[choice]; applyDanmakuPrefs()
            if(anchor is TextView && anchor.tag!="playback-settings") anchor.text="弹幕不透明度  ·  ${library.danmakuOpacity}%"
            done?.invoke()
        },focusIndex=current)
    }
    private fun showDanmakuFontPicker(anchor: View?,done: (()->Unit)?=null) {
        val current=library.danmakuFont.coerceIn(0,DanmakuPrefs.FONT_LABELS.lastIndex)
        tvTools.choose("弹幕字体大小",DanmakuPrefs.FONT_LABELS.mapIndexed { index,label -> label+(if(index==current) "   ·   当前" else "") },anchor,{ choice ->
            library.danmakuFont=choice; applyDanmakuPrefs()
            if(anchor is TextView && anchor.tag!="playback-settings") anchor.text="弹幕字体大小  ·  ${DanmakuPrefs.fontLabel(library.danmakuFont)}"
            done?.invoke()
        },focusIndex=current)
    }
    private fun showDanmakuSpeedPicker(anchor: View?,done: (()->Unit)?=null) {
        val current=library.danmakuSpeed.coerceIn(0,DanmakuPrefs.SPEED_LABELS.lastIndex)
        tvTools.choose("弹幕移动速度",DanmakuPrefs.SPEED_LABELS.mapIndexed { index,label -> label+(if(index==current) "   ·   当前" else "") },anchor,{ choice ->
            library.danmakuSpeed=choice; applyDanmakuPrefs()
            if(anchor is TextView && anchor.tag!="playback-settings") anchor.text="弹幕移动速度  ·  ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}"
            done?.invoke()
        },focusIndex=current)
    }
    private fun showDanmakuRowsPicker(anchor: View?,done: (()->Unit)?=null) {
        val rows=(DanmakuPrefs.ROWS_MIN..DanmakuPrefs.ROWS_MAX).toList()
        val current=(library.danmakuRows-DanmakuPrefs.ROWS_MIN).coerceIn(0,rows.lastIndex)
        tvTools.choose("弹幕展示行数",rows.map { "$it 行"+(if(it==library.danmakuRows) "   ·   当前" else "") },anchor,{ choice ->
            library.danmakuRows=choice+DanmakuPrefs.ROWS_MIN; applyDanmakuPrefs()
            if(anchor is TextView && anchor.tag!="playback-settings") anchor.text="弹幕展示行数  ·  ${library.danmakuRows} 行"
            done?.invoke()
        },focusIndex=current)
    }
    /** 播放页复用全局弹幕设置，参数调整后返回列表，所有剧集共用。 */
    private fun showDanmakuPrefsDialog(anchor: View?) {
        if(screen!="player" || anchor?.isAttachedToWindow==false) return
        fun labels()=listOf("不透明度 ${library.danmakuOpacity}%","字体大小 ${DanmakuPrefs.fontLabel(library.danmakuFont)}","移动速度 ${DanmakuPrefs.speedLabel(library.danmakuSpeed)}","展示行数 ${library.danmakuRows} 行","屏蔽词 ${extras.blockedWords.size} 个","恢复默认设置")
        tvTools.choose("弹幕设置",labels(),anchor,{ choice ->
            main.post {
                if(screen!="player" || anchor?.isAttachedToWindow==false) return@post
                when(choice) {
                    0 -> showDanmakuOpacityPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    1 -> showDanmakuFontPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    2 -> showDanmakuSpeedPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    3 -> showDanmakuRowsPicker(anchor) { showDanmakuPrefsDialog(anchor) }
                    4 -> showBlockedWordDialog(anchor)
                    5 -> { resetDanmakuPrefs(); showDanmakuPrefsDialog(anchor) }
                }
            }
        })
    }
    /** 弹幕屏蔽词：常用词多选 + 手动添加自定义词；确认后立即对正在播放的弹幕生效。 */
    private fun showBlockedWordDialog(anchor: View?) {
        if(PhoneDialog.shouldUse(this)) { showPhoneBlockedWordDialog(); return }
        val presets=listOf("加群","微信","公众号","QQ群","QQ","广告","私聊","代看","抖音","快手")
        val current=extras.blockedWords
        // words/checked 用可变列表：手动添加后要就地插入一行，索引也跟着变长
        val words=(presets+current.filterNot { it in presets }).distinct().toMutableList()
        val checked=words.map { it in current }.toMutableList()
        val content=column()
        content.addView(TvDialog.title(this,"弹幕屏蔽词"))
        // 选项用可聚焦的按钮行：多选 ListView 的 performClick 触发不了 OnItemClickListener，
        // 确认键点不动；这里和清晰度选择器同一套做法，行内自绘勾选态。
        fun paint(row: Button,index: Int,focus: Boolean) {
            row.text=(if(checked[index]) "✓ " else "　")+words[index]
            row.background=GradientDrawable().apply {
                setColor(when { checked[index]&&focus -> TvStyle.accent; focus -> TvStyle.raised; checked[index] -> TvStyle.selectedSurface; else -> TvStyle.surface })
                cornerRadius=dp(14).toFloat()
                setStroke(dp(1),if(focus||checked[index]) TvStyle.accent else TvStyle.outline)
            }
            row.setTextColor(when { checked[index]&&focus -> TvStyle.background; focus -> TvStyle.text; checked[index] -> TvStyle.accent; else -> TvStyle.muted })
            row.setTypeface(null,if(focus||checked[index]) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        val dialog=AlertDialog.Builder(this).create()
        // rows 与 bottomFocusId 要在 clear 之前声明：清空回调要按索引重绘每一行，
        // 末行的向下焦点则要等底部按钮建好后才知道
        var rows: List<Button> = emptyList()
        var bottomFocusId=View.NO_ID
        val clear=TvDialog.capsuleButton(this,"清空") {
            extras.blockedWords=emptySet(); applyBlockedWords()
            if(anchor is TextView && anchor.tag!="playback-settings") anchor.text="弹幕屏蔽词  ·  0 个"
            for(i in checked.indices) checked[i]=false
            rows.forEachIndexed { i,r -> paint(r,i,r.isFocused) }
        }
        // 手动添加：输入框 + 添加按钮。加完只重建列表，不动已勾选的其他词。
        val input=EditText(this).apply {
            id=View.generateViewId()
            hint="输入要屏蔽的词，如「刷屏」"
            textSize=16f
            setTextColor(white); setHintTextColor(muted)
            isSingleLine=true; maxLines=1
            filters=arrayOf(android.text.InputFilter.LengthFilter(20))
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background=GradientDrawable().apply {
                setColor(TvStyle.surface); cornerRadius=dp(12).toFloat(); setStroke(dp(1),TvStyle.outline)
            }
            setPadding(dp(14),dp(10),dp(14),dp(10))
        }
        val inputRow=row()
        inputRow.addView(input,lp(0,dp(46)).apply { weight=1f; rightMargin=dp(10) })
        val add=TvDialog.capsuleButton(this,"添加") {}
        inputRow.addView(add,lp(-2,-2))
        content.addView(inputRow,lp(-1,-2).apply { bottomMargin=dp(10) })
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }
        val list=column()
        fun rebuild() {
            list.removeAllViews()
            rows=words.mapIndexed { index,word ->
                Button(this).apply {
                    id=View.generateViewId()
                    textSize=16f
                    minHeight=dp(46)
                    gravity=Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(dp(20),0,dp(20),0)
                    paint(this,index,false)
                    setOnFocusChangeListener { _,has -> paint(this,index,has) }
                    setOnClickListener { checked[index]=!checked[index]; paint(this,index,isFocused) }
                }
            }
            rows.forEachIndexed { index,row ->
                row.nextFocusUpId=rows.getOrNull(index-1)?.id ?: input.id
                row.nextFocusDownId=rows.getOrNull(index+1)?.id ?: bottomFocusId
                list.addView(row,lp(-1,-2).apply { bottomMargin=dp(8) })
            }
            // 输入框 ↔ 添加按钮 ↔ 首行列表之间的上下焦点接上，遥控器不至于卡在输入框里
            add.nextFocusDownId=rows.firstOrNull()?.id ?: bottomFocusId
            input.nextFocusDownId=add.id
        }
        fun commitInput() {
            val word=input.text.toString().trim()
            if(word.isEmpty()) { Toast.makeText(this@MainActivity,"请输入要屏蔽的词",Toast.LENGTH_SHORT).show(); return }
            val existing=words.indexOfFirst { it.equals(word,ignoreCase=true) }
            if(existing>=0) {
                // 已存在：直接勾上并提示，不重复插入
                if(!checked[existing]) { checked[existing]=true; paint(rows[existing],existing,rows[existing].isFocused) }
                Toast.makeText(this@MainActivity,"「${words[existing]}」已在列表中",Toast.LENGTH_SHORT).show()
            } else {
                words.add(word); checked.add(true)
                rebuild()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
                // 新词追加在列表末尾，滚到底才能看到它已勾选
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                Toast.makeText(this@MainActivity,"已添加「$word」，点「保存」生效",Toast.LENGTH_SHORT).show()
            }
            input.setText("")
        }
        add.setOnClickListener { commitInput() }
        input.setOnEditorActionListener { _,_,_-> commitInput(); true }
        // EditText 自己会吃掉方向键（移动光标），遥控器按 ↓ 出不去；这里显式交给「添加」按钮
        input.setOnKeyListener { _,key,event ->
            if(key==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_DOWN) { add.requestFocus(); true } else false
        }
        add.nextFocusUpId=input.id
        rebuild()
        scroll.addView(list,lp(-1,-2))
        TvDialog.capHeight(this,scroll)
        content.addView(scroll,lp(-1,-2))
        val buttons=row().apply { gravity=Gravity.END; setPadding(0,dp(14),0,0) }
        val save=TvDialog.capsuleButton(this,"保存") {
            extras.blockedWords=words.filterIndexed { index,_ -> checked[index] }.toSet()
            applyBlockedWords()
            if(anchor is TextView && anchor.tag!="playback-settings") anchor.text="弹幕屏蔽词  ·  ${extras.blockedWords.size} 个"
            Toast.makeText(this@MainActivity,"屏蔽词已保存，立即生效",Toast.LENGTH_SHORT).show()
        }
        buttons.addView(clear,lp(-2,-2).apply { rightMargin=dp(12) })
        buttons.addView(save,lp(-2,-2))
        content.addView(buttons)
        // 底部按钮建好后才确定末行的向下焦点，回填到已建好的行上
        bottomFocusId=clear.id
        rows.forEachIndexed { index,row -> row.nextFocusDownId=rows.getOrNull(index+1)?.id ?: bottomFocusId }
        add.nextFocusDownId=rows.firstOrNull()?.id ?: bottomFocusId
        dialog.setView(TvDialog.card(this,content)) // setView 必须在 styleWindow 前：自然测宽要读到卡片
        // 输入框用 weight 占满剩余宽度，自然测宽会把它量成 0 宽，这里固定弹窗宽度
        TvDialog.styleWindow(this,dialog,widthDp=440) { rows.firstOrNull()?.requestFocus() }
        // 初始不弹软键盘（默认焦点在列表首行）；点输入框弹起时整体上移，避免遮住「添加」
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        dialog.setOnDismissListener { if(anchor?.isAttachedToWindow==true) anchor.requestFocus() }
        dialog.show()
    }
    /** 手机屏蔽词：使用竖屏分组列表，输入与勾选保留在弹层内，保存后统一生效。 */
    private fun showPhoneBlockedWordDialog() {
        val presets=listOf("加群","微信","公众号","QQ群","QQ","广告","私聊","代看","抖音","快手")
        val current=extras.blockedWords
        val words=(presets+current.filterNot { it in presets }).distinct().toMutableList()
        val selected=current.toMutableSet()
        val content=PhoneUi.column(this)
        val input=EditText(PortraitStyle.popupContext(this)).apply {
            hint="输入要屏蔽的词"
            textSize=17f
            setTextColor(PortraitStyle.text)
            setHintTextColor(PortraitStyle.muted)
            isSingleLine=true
            filters=arrayOf(android.text.InputFilter.LengthFilter(20))
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background=PortraitStyle.shape(this@MainActivity,PortraitStyle.surface,12)
            setPadding(dp(14),dp(13),dp(14),dp(13))
        }
        val inputRow=PhoneUi.row(this)
        inputRow.addView(input,lp(0,-2).apply { weight=1f; rightMargin=dp(10) })
        val add=PhoneDialog.button(this,"添加") {}
        inputRow.addView(add,lp(-2,-2))
        content.addView(inputRow,lp(-1,-2).apply { bottomMargin=dp(18) })
        val list=PhoneUi.column(this).apply {
            background=PortraitStyle.shape(this@MainActivity,PortraitStyle.surface,12)
            clipToOutline=true
        }
        content.addView(list,lp(-1,-2))
        fun rebuild() {
            list.removeAllViews()
            words.forEachIndexed { index,word ->
                if(index>0) list.addView(View(this).apply {
                    setBackgroundColor(PortraitStyle.outline)
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },lp(-1,1).apply { leftMargin=dp(16) })
                val line=PhoneUi.row(this).apply {
                    minimumHeight=dp(50)
                    setPadding(dp(16),dp(12),dp(16),dp(12))
                    background=PortraitStyle.selectable(this@MainActivity,Color.TRANSPARENT,0)
                    isFocusable=true
                    isClickable=true
                }
                line.addView(PhoneUi.text(this,word,17f,PortraitStyle.text),lp(0,-2).apply { weight=1f })
                val check=PhoneUi.text(this,"✓",19f,PortraitStyle.navigationSelected)
                line.addView(check,lp(dp(25),-2))
                fun paint() {
                    check.visibility=if(word in selected) View.VISIBLE else View.INVISIBLE
                    line.isSelected=word in selected
                    line.contentDescription="$word，${if(word in selected) "已屏蔽" else "未屏蔽"}"
                }
                paint()
                line.setOnClickListener {
                    if(!selected.add(word)) selected.remove(word)
                    paint()
                }
                list.addView(line,lp(-1,-2))
            }
        }
        fun commitInput(): Boolean {
            val word=input.text.toString().trim()
            if(word.isEmpty()) return false
            val existing=words.firstOrNull { it.equals(word,ignoreCase=true) }
            if(existing==null) { words.add(word); selected.add(word) } else selected.add(existing)
            input.setText("")
            input.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
            rebuild()
            return true
        }
        add.setOnClickListener { if(!commitInput()) PhoneUi.toast(this,"请输入要屏蔽的词") }
        input.setOnEditorActionListener { _,_,_ -> commitInput(); true }
        rebuild()
        lateinit var dialog: AlertDialog
        content.addView(PhoneDialog.button(this,"保存",primary=true) {
            commitInput()
            extras.blockedWords=selected.toSet()
            applyBlockedWords()
            phoneRefreshSettingsValues()
            dialog.dismiss()
            PhoneUi.toast(this,"屏蔽词已保存，立即生效")
        },lp(-1,-2).apply { topMargin=dp(20) })
        content.addView(PhoneDialog.button(this,"清空屏蔽词",destructive=true) {
            selected.clear()
            extras.blockedWords=emptySet()
            applyBlockedWords()
            phoneRefreshSettingsValues()
            rebuild()
        },lp(-1,-2).apply { topMargin=dp(10) })
        dialog=PhoneDialog.sheet(this,"弹幕屏蔽词","勾选要屏蔽的内容，也可以添加自定义词。",content)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }
    private fun applyBlockedWords() = danmakuView?.setBlockedFilter { text -> extras.blocks(text) }

    /** 本机观看统计：直接按最近观看记录汇总，不额外存储、不产生任何网络请求。 */
    private fun showWatchStats(anchor: TextView) {
        val rows=library.history()
        if(rows.isEmpty()) { tvTools.info("观看统计 · 本机","还没有观看记录，播放几集后再来看。",anchor); return }
        val totalMs=rows.sumOf { it.position.coerceAtLeast(0) }
        val minutes=totalMs/60_000
        val finished=rows.count { it.completed }
        val tags=rows.flatMap { it.series.tags.split(" · ").map(String::trim).filter(String::isNotEmpty) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
        val body=buildString {
            append("累计观看 ${rows.size} 集，共 ${minutes} 分钟（约 ${minutes/60} 小时 ${minutes%60} 分钟）。\n")
            append("完整看完 $finished 集，涉及 ${rows.map { it.series.id }.distinct().size} 部剧。\n")
            if(tags.isNotEmpty()) {
                append("\n题材分布\n")
                tags.forEach { (tag,count) -> append("· $tag  $count 集\n") }
            }
            if(accountManager.signedIn) {
                val cloudCount=accountManager.cloudRows().size
                if(cloudCount>0) append("\n云端另有 $cloudCount 部观看记录（只读展示，不合并统计）")
            }
            append("\n统计来自本机观看记录，不上传、不联网。")
        }
        tvTools.info("观看统计 · 本机",body,anchor)
    }

    /** 接口自检：用户逐项点击，单次串行请求，失败只提示不重试，避免给站点增加并发压力。
     *  状态圆点内嵌在按钮文字前：灰=未检测，橙=检测中，绿=通，红=不通；3 秒内重复点击直接忽略。 */
    private fun showInterfaceCheck(anchor: TextView) {
        val phone=PhoneDialog.shouldUse(this)
        val content=column()
        val status=text("选择一项开始检测",14f,if(phone) PortraitStyle.secondaryText else muted).apply { setPadding(dp(8),0,dp(8),dp(10)) }
        content.addView(status)
        lateinit var dialog: AlertDialog
        // 6 项拆两行各 3 个：弹窗收窄后单行 6 个 weight 均分会把按钮压到逐字竖排换行
        val rows=List(if(phone) 6 else 2) { row() }
        val buttons=mutableListOf<TextView>()
        val dotIdle=if(phone) PortraitStyle.outline else TvStyle.outline
        val dotOk=Color.rgb(86,196,122)
        val dotFail=Color.rgb(235,95,95)
        // 每个按钮独立的冷却与在检状态，互不影响
        val lastAt=mutableMapOf<String,Long>()
        val busy=mutableSetOf<String>()
        fun dotLabel(name: String,color: Int): CharSequence {
            val span=android.text.SpannableString("●  $name")
            span.setSpan(object: android.text.style.ReplacementSpan() {
                override fun getSize(paint: android.graphics.Paint,text: CharSequence?,start: Int,end: Int,fm: android.graphics.Paint.FontMetricsInt?): Int =
                    (paint.textSize*0.55f).toInt()
                override fun draw(canvas: android.graphics.Canvas,text: CharSequence?,start: Int,end: Int,x: Float,top: Int,y: Int,bottom: Int,paint: android.graphics.Paint) {
                    val r=paint.textSize*0.22f
                    val cy=(top+bottom)/2f
                    val oldColor=paint.color
                    paint.color=color
                    canvas.drawCircle(x+r,cy,r,paint)
                    paint.color=oldColor
                }
            },0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }
        fun check(name: String,btn: TextView,probe: ()->String) {
            val now=System.currentTimeMillis()
            if(name in busy || now-(lastAt[name] ?: 0L)<3_000) { status.text="$name 请稍候再试"; return }
            lastAt[name]=now
            busy+=name; status.text="正在检测 $name…"
            btn.text=dotLabel(name,if(phone) PortraitStyle.navigationSelected else accent)
            val started=android.os.SystemClock.elapsedRealtime()
            io.execute {
                val result=runCatching(probe)
                main.post {
                    if(!dialog.isShowing) return@post
                    val elapsed=android.os.SystemClock.elapsedRealtime()-started
                    result.fold(
                        { detail -> status.text="$name · 正常 · $detail · ${elapsed}ms"; btn.text=dotLabel(name,dotOk) },
                        { problem -> status.text="$name · 失败 · ${(problem.message ?: problem.javaClass.simpleName).take(80)}"; btn.text=dotLabel(name,dotFail) })
                    busy-=name
                }
            }
        }
        fun add(name: String,probe: ()->String): TextView {
            lateinit var btn: TextView
            btn=if(phone) PhoneDialog.button(this,name) { check(name,btn,probe) }
                else button(name) { check(name,btn,probe) }
            btn.text=dotLabel(name,dotIdle)
            buttons+=btn
            rows[if(phone) buttons.size-1 else (buttons.size-1)/3].addView(btn,LinearLayout.LayoutParams(0,-2,1f).apply { if(!phone) rightMargin=dp(6) })
            return btn
        }
        val phoneRecommendationCheck=phoneLayout()
        add(if(phoneRecommendationCheck) "推荐" else "首页") {
            if(phoneRecommendationCheck) {
                val result=repository.recommendations()
                RuntimeLog.add("recommendation check ${result.diagnostic}")
                "返回 ${result.items.size} 条"
            } else {
                val result=repository.home(1,library.contentType)
                "返回 ${result.items.size} 条"
            }
        }
        add("搜索") { val page=repository.search("修仙",1,library.contentType); "返回 ${page.items.size} 条" }
        add("播放") {
            val progress=library.history().firstOrNull() ?: throw IllegalStateException("暂无观看记录，先播放一集")
            val info=repository.stream(progress.episodeId,library.maxQuality); "获取成功 · ${info.quality}"
        }
        add("联想") { val words=repository.suggest("修仙"); "联想 ${words.size} 个" }
        add("账号") { accountManager.checkAccountHealth() }
        add("云端") { accountManager.checkCloudHistory() }
        rows.forEachIndexed { index,line -> content.addView(line,lp(-1,-2).apply { if(index>0) topMargin=dp(8) }) }
        content.addView(text("说明：每项只检测一次；账号与云端检测需要先登录，未登录会跳过。",12f,if(phone) PortraitStyle.muted else muted).apply { setPadding(dp(8),dp(10),dp(8),0) })
        dialog=if(phone) PhoneDialog.sheet(this,"接口自检",content=content)
            else AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        if(!phone) {
            TvDialog.styleWindow(this,dialog,widthDp=340)
            dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        }
        dialog.show()
        if(!phone) buttons.first().requestFocus()
    }

    /** 遥控器按键：就地循环切换取值，避免多层弹窗来回跳焦点。 */
    private fun showKeySettings(anchor: TextView) {
        val content=column()
        fun sync(anchorText: TextView) { anchorText.text="遥控器按键  ·  快进 ${extras.seekStepSeconds} 秒" }
        lateinit var step: TextView
        step=button("快进步长  ·  ${extras.seekStepSeconds} 秒") {
            val options=PlaybackExtras.SEEK_STEPS
            extras.seekStepSeconds=options[(options.indexOf(extras.seekStepSeconds)+1)%options.size]
            step.text="快进步长  ·  ${extras.seekStepSeconds} 秒"; sync(anchor)
        }
        lateinit var speed: TextView
        speed=button("长按快进  ·  ${PlaybackExtras.FAST_SPEEDS.first { it==extras.longPressSpeed }.toInt()}×") {
            val options=PlaybackExtras.FAST_SPEEDS
            extras.longPressSpeed=options[(options.indexOf(extras.longPressSpeed)+1)%options.size]
            speed.text="长按快进  ·  ${extras.longPressSpeed.toInt()}×"
        }
        fun menuLabel()=when(extras.menuKeyAction) {
            PlaybackExtras.MENU_EPISODES -> "选集"
            PlaybackExtras.MENU_DANMAKU -> "弹幕开关"
            else -> "播放设置"
        }
        lateinit var menu: TextView
        menu=button("菜单键  ·  ${menuLabel()}") {
            extras.menuKeyAction=when(extras.menuKeyAction) {
                PlaybackExtras.MENU_EPISODES -> PlaybackExtras.MENU_DANMAKU
                PlaybackExtras.MENU_DANMAKU -> PlaybackExtras.MENU_PANEL
                else -> PlaybackExtras.MENU_EPISODES
            }
            menu.text="菜单键  ·  ${menuLabel()}"
        }
        val fixedTvMenu=!phonePlayerLayout()
        fun upDownLabel()=if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) {
            if(fixedTvMenu) "上一集" else "切换集数"
        } else if(fixedTvMenu) "操作菜单" else "选集面板"
        val upDownTitle=if(fixedTvMenu) "遥控上键" else "遥控上下键"
        lateinit var upDown: TextView
        upDown=button("$upDownTitle  ·  ${upDownLabel()}") {
            extras.dpadUpDownAction=if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) PlaybackExtras.UPDOWN_PANEL else PlaybackExtras.UPDOWN_SWITCH
            upDown.text="$upDownTitle  ·  ${upDownLabel()}"
        }
        if(fixedTvMenu) content.addView(text("菜单键 / 下键  ·  打开播放操作菜单",13f,muted).apply { setPadding(dp(8),0,dp(8),dp(12)) })
        val items=if(fixedTvMenu) listOf(step,speed,upDown) else listOf(step,speed,menu,upDown)
        items.forEachIndexed { index,view ->
            view.gravity=Gravity.START or Gravity.CENTER_VERTICAL // 弹窗选项一律左对齐，Button 默认居中
            content.addView(view,lp(-1,dp(48)).apply { bottomMargin=dp(8) })
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: view.id
        }
        val dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        TvDialog.styleWindow(this,dialog)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show(); step.requestFocus()
    }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else extras.seekStepSeconds*1000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun startFastForward() {
        val p=player ?: return
        if(ffActive || !playbackReady || p.duration<=0) return
        ffActive=true; ffSpeedBefore=p.playbackParameters.speed
        p.setPlaybackSpeed(extras.longPressSpeed); showHud(); updatePlaybackText()
    }
    private fun stopFastForward() {
        if(!ffActive) return
        ffActive=false; player?.setPlaybackSpeed(ffSpeedBefore)
        updatePlaybackText(); showHud()
    }
    /** TV 底部操作菜单与横向选集栏，菜单键和下键共用同一入口。 */
    private fun showPanel() {
        if(screen!="player" || playError || phonePlayerLayout()) return
        val data=detail ?: return
        stopFastForward()
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val menu=TvPlaybackMenu(this,episodeIndex,data.episodes.size,
            onNext={ skipEpisode(1) },
            onPlay={ togglePlayback() },
            onSpeed={ showSpeedPicker(it) },
            onQuality={ showQualityPicker(it,true) },
            onDanmaku={ showTvDanmakuMenu(it) },
            onMore={ showTvPlaybackSettings(it) },
            onEpisode={ selected ->
                if(selected==episodeIndex) hidePlaybackOverlay()
                else { playEpisode(selected,savedPosition(selected)); showHud() }
            })
        tvPlaybackMenu=menu; transportPlay=menu.playButton
        controls.addView(menu,lp(-1,-2))
        updatePlaybackText()
        menu.focusPrimary()
        if(touchPlayback) scheduleHideHud()
    }
    private fun showTvDanmakuMenu(anchor: TextView) {
        val on=library.danmaku
        tvTools.choose("弹幕",listOf(if(on) "关闭弹幕" else "开启弹幕","弹幕设置"),anchor,{ choice ->
            main.post {
                if(screen!="player" || !anchor.isAttachedToWindow) return@post
                if(choice==0) toggleDanmaku() else showDanmakuPrefsDialog(anchor)
            }
        })
    }
    private fun showTvPlaybackSettings(anchor: TextView) {
        val seriesId=detail?.series?.id.orEmpty()
        val mark=library.skipMark(seriesId)
        fun skipLabel(seconds: Int)=if(seconds>0) "$seconds 秒" else "关闭"
        val options=listOf("全部选集 / 跳转集数","画面 ${effectiveFrame().label}（仅本剧）","定时 ${sleepTimer.label()}",
            "跳过片头 ${skipLabel(mark?.introSeconds ?: 0)}","跳过片尾 ${skipLabel(mark?.outroSeconds ?: 0)}","从头播放","上一集")
        tvTools.choose("播放设置",options,anchor,{ choice ->
            main.post {
                if(screen!="player" || !anchor.isAttachedToWindow) return@post
                when(choice) {
                    0 -> showEpisodePanel(anchor)
                    1 -> showFramePicker(anchor,true)
                    2 -> showSleepPicker(anchor)
                    3 -> showSkipPicker(anchor,true)
                    4 -> showSkipPicker(anchor,false)
                    5 -> { seekTo(0); hidePanel() }
                    6 -> if(!skipEpisode(-1)) Toast.makeText(this,"已是第一集",Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
    /** 播放快捷面板：长按菜单键呼出。清晰度/倍速/弹幕/更多四列，列内上下循环，改动立即生效，播放不中断。 */
    private fun showQuickPanel() {
        if(screen!="player" || playError) return
        if(!phonePlayerLayout()) { showPanel(); return }
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val data=detail
        val phone=phonePlayerLayout()
        controls.addView(text("播放快捷面板",16f,if(phone) white else Color.WHITE).apply { setTypeface(null,Typeface.BOLD) },lp(-2,-2))
        controls.addView(text(if(phone) "上下选择 · 确认更改 · 返回或菜单键关闭 · 短按菜单仍执行你设置的行为" else "上下选择 · 确认更改 · 返回隐藏界面",12f,if(phone) muted else Color.rgb(155,169,190)).apply { setPadding(0,dp(2),0,dp(10)) })
        val columnsRow=row().apply { gravity=Gravity.TOP }
        controls.addView(columnsRow)
        val columns=mutableListOf<List<TextView>>()
        fun optionColumn(title: String,options: List<String>,current: Int,onPick: (Int,TextView)->Unit): List<TextView> {
            val col=column()
            col.addView(text(title,13f,gold).apply { setPadding(dp(10),0,0,dp(6)) })
            val items=options.mapIndexed { index,label ->
                TextView(this).apply {
                    text=if(index==current) "$label · 当前" else label; textSize=if(!phone && widthDp()<700) 12f else 14f
                    setTextColor(if(index==current) accent else if(phone) white else Color.rgb(227,233,243)); includeFontPadding=false
                    gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),dp(7),dp(12),dp(7)); minHeight=dp(36); minWidth=if(phone) dp(150) else 0
                    focusStyle(this,index==current)
                    if(!phone) {
                        maxLines=2; ellipsize=TextUtils.TruncateAt.END
                        fun paint(focused: Boolean) {
                            background=TvVisual.row(this@MainActivity,focused,index==current)
                            setTextColor(if(focused || index==current) accent else Color.rgb(227,233,243))
                        }
                        setOnFocusChangeListener { _,focused -> paint(focused) }; paint(false)
                    }
                    setOnClickListener { onPick(index,this) }
                    col.addView(this,lp(if(phone) -2 else -1,-2).apply { bottomMargin=dp(4) })
                }
            }
            columnsRow.addView(col,lp(if(phone) -2 else 0,-2).apply { if(!phone) weight=1f; rightMargin=dp(if(phone) 22 else 8) })
            return items
        }
        // 清晰度：切换后按当前进度重新取流，面板随之关闭（playEpisode 会清空 controls）
        columns+=optionColumn("清晰度",PlaybackQuality.options.map { "${it}P" },PlaybackQuality.options.indexOf(effectiveQuality())) { index,_ ->
            val target=PlaybackQuality.options[index]
            if(effectiveQuality()!=target) {
                // 只改本次播放的档位，不写回全局默认
                sessionQuality=target; downgradeEpisode=""; downgradeCount=0
                playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            } else showQuickPanel()
        }
        // 倍速：立即生效并停留在面板
        columns+=optionColumn("倍速",PlaybackSpeed.options.map(PlaybackSpeed::label),PlaybackSpeed.options.indexOf(effectiveSpeed())) { index,_ ->
            sessionSpeed=PlaybackSpeed.options[index]; player?.setPlaybackSpeed(PlaybackSpeed.options[index])
            updatePlaybackText(); showQuickPanel()
        }
        // 弹幕开关与参数统一对全部剧集生效。
        val danmakuOn=library.danmaku
        columns+=optionColumn("弹幕",listOf(if(danmakuOn) "关闭弹幕" else "开启弹幕","弹幕设置"),-1) { index,anchor ->
            if(index==0) { toggleDanmaku(); showQuickPanel() } else showDanmakuPrefsDialog(anchor)
        }
        // 更多：跳过片头片尾 / 画面 / 定时 / 从头播放
        val mark=data?.series?.id?.let { library.skipMark(it) }
        fun skipLabel(seconds: Int)=if(seconds>0) "$seconds 秒" else "关闭"
        columns+=optionColumn("更多",listOf("跳过片头 ${skipLabel(mark?.introSeconds ?: 0)}","跳过片尾 ${skipLabel(mark?.outroSeconds ?: 0)}","画面 ${effectiveFrame().label}","定时 ${sleepTimer.label()}","从头播放本集"),-1) { index,anchor ->
            when(index) {
                0 -> showSkipPicker(anchor,true)
                1 -> showSkipPicker(anchor,false)
                2 -> showFramePicker(anchor,true)
                3 -> showSleepPicker(anchor)
                4 -> { seekTo(0); hidePanel() }
            }
        }
        // 列内上下循环，列间左右按行号对齐（越界夹到末项），遥控器不会跳出面板的网格
        val maxRows=columns.maxOf { it.size }
        columns.forEachIndexed { c,items ->
            items.forEachIndexed { i,item ->
                item.nextFocusUpId=items[(i-1+items.size)%items.size].id
                item.nextFocusDownId=items[(i+1)%items.size].id
                if(c>0) item.nextFocusLeftId=columns[c-1][i.coerceAtMost(columns[c-1].lastIndex)].id
                if(c<columns.lastIndex) item.nextFocusRightId=columns[c+1][i.coerceAtMost(columns[c+1].lastIndex)].id
            }
        }
        if(maxRows>0) columns[1].getOrNull(0)?.requestFocus() ?: columns[0].firstOrNull()?.requestFocus()
    }
    /** Season finale auto-continues into the next season only when both the master autoplay and the season switch are on. */
    private fun continueToNextSeason(): Boolean {
        if (offlineOnly) return false
        val data=detail ?: return false
        if(!library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) return false
        val next=data.nextSeason ?: return false
        Toast.makeText(this,"即将播放《${next.title}》",Toast.LENGTH_SHORT).show()
        openDetail(next,resume=true,autoplay=true)
        return true
    }
    private fun showEndedPanel() {
        val data=detail
        if(playError || data==null || !library.autoNext || !library.autoSeason || episodeIndex<data.episodes.lastIndex) {
            playbackText.text="本集已结束"; showPanel(); return
        }
        val cached=library.cachedHome(library.contentType)?.items.orEmpty().filter { it.id!=data.series.id }
        if(cached.isNotEmpty()) { showRecommendationPanel(cached); return }
        playbackText.text="本集已结束"; showPanel()
        // The home cache can be empty on a cold start that went straight into playback; fetch once and upgrade the panel.
        work({ repository.home(1,library.contentType) }, { page ->
            if(screen=="player" && player?.playbackState==Player.STATE_ENDED && detail?.series?.id==data.series.id) {
                val items=page.items.filter { it.id!=data.series.id }
                if(items.isNotEmpty()) showRecommendationPanel(items)
            }
        }, { /* keep the plain ended panel */ })
    }
    private fun showRecommendationPanel(items: List<Series>) {
        if(playError || screen!="player") return
        panel=true; controls.visibility=View.VISIBLE; controls.removeAllViews()
        controls.addView(text("《${detail?.series?.title}》已播完 · 为你推荐热门${library.contentType.label}",14f,muted).apply { setPadding(dp(4),0,0,dp(8)) })
        val strip=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false }
        val line=row(); strip.addView(line); controls.addView(strip)
        items.take(10).forEach { item ->
            val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(8)); focusStyle(card)
            val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            card.addView(image,lp(dp(96),(dp(96)/TvStyle.POSTER_ASPECT).toInt()))
            loadCover(image,item.cover)
            card.addView(text(item.title,12f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(2),dp(4),dp(2),0) },lp(dp(96),-2))
            card.contentDescription=item.title
            card.setOnClickListener { openDetail(item) }
            line.addView(card,lp(-2,-2).apply { rightMargin=dp(10) })
        }
        val actions=row().apply { setPadding(0,dp(10),0,0) }; controls.addView(actions)
        addButton(actions,"返回详情") { returnToDetail(true) }
        line.getChildAt(0)?.requestFocus()
        showHud()
    }
    private fun showSkipPicker(anchor: TextView,intro: Boolean) {
        val data=detail ?: return
        val options=listOf(0,15,30,45,60,90,120)
        val current=library.skipMark(data.series.id)?.let { if(intro) it.introSeconds else it.outroSeconds } ?: 0
        tvTools.choose((if(intro) "跳过片头" else "跳过片尾")+" · 仅对本剧生效",options.map { if(it==0) "关闭" else "$it 秒" },anchor,{ index ->
            val old=library.skipMark(data.series.id) ?: SkipMark(0,0)
            val next=if(intro) old.copy(introSeconds=options[index]) else old.copy(outroSeconds=options[index])
            library.setSkipMark(data.series.id,next)
            if(!intro) { outroSkipSeconds=next.outroSeconds; outroTriggered=false }
            Toast.makeText(this,"已保存，仅对《${data.series.title}》生效",Toast.LENGTH_SHORT).show()
            showHud()
        },"当前 ${if(current>0) "$current 秒" else "关闭"}",onExtra={})
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val current=if(inPlayer) effectiveSpeed() else library.playbackSpeed
        if(PhoneDialog.shouldUse(this)) {
            val dialog=PhoneDialog.choose(this,if(inPlayer) "本剧倍速" else "默认倍速",speeds.map(PlaybackSpeed::label),{ index ->
                val speed=speeds[index]
                if(inPlayer) sessionSpeed=speed else library.playbackSpeed=speed
                if(inPlayer) player?.setPlaybackSpeed(speed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(speed)
                if(inPlayer) { updatePlaybackText(); refreshPhonePlayerNow() }
                phoneRefreshSettingsValues()
            },focusIndex=speeds.indexOf(current))
            speedDialog=dialog
            dialog.setOnDismissListener { speedDialog=null }
            dialog.show()
            return
        }
        val content=column()
        content.addView(TvDialog.title(this,if(inPlayer) "本剧倍速" else "全局默认倍速"))
        if(inPlayer) content.addView(text("只对当前这部剧生效，不改「设置 → 播放」里的默认倍速",12f,muted).apply { setPadding(dp(8),0,dp(8),dp(8)) })
        lateinit var dialog: AlertDialog
        // 同清晰度选择器：按钮比 ListView 更可靠，电视上不会出现「按了没反应」
        val items=mutableListOf<TextView>()
        speeds.forEach { speed ->
            val isCurrent=speed==current
            val item=button(PlaybackSpeed.label(speed)+(if(isCurrent) "   ·   当前" else ""),isCurrent) {
                // 播放中就只改本次会话：用户临时调个倍速，不该改掉明天看别的剧的默认值
                if(inPlayer) sessionSpeed=speed else library.playbackSpeed=speed
                if(inPlayer) player?.setPlaybackSpeed(speed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(speed)
                if(inPlayer) updatePlaybackText()
                dialog.dismiss()
            }
            items+=item
            // 选项只是文字行，居中排版既费横向空间又难扫读；左对齐后弹窗可以收得很窄
            item.gravity=Gravity.CENTER_VERTICAL
            item.setPadding(dp(16),dp(10),dp(16),dp(10))
            content.addView(item,lp(-1,dp(46)).apply { bottomMargin=dp(8) })
        }
        items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: view.id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        }
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        // 播放中靠左小窗：只是换个倍速，没必要居中盖住大半个画面；
        // 设置页没有播放画面要护，全局默认值是正式决定，居中更像「对话框」
        TvDialog.styleWindow(this,dialog,maxWidthDp=380,
            gravity=if(inPlayer) Gravity.START else Gravity.CENTER,marginDp=48)
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show()
        (items.getOrNull(speeds.indexOf(current)) ?: items.firstOrNull())?.requestFocus()
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val current=if(inPlayer) effectiveQuality() else library.maxQuality
        if(PhoneDialog.shouldUse(this)) {
            PhoneDialog.choose(this,if(inPlayer) "本剧清晰度" else "默认清晰度",options.map { "${it}P" },{ index ->
                val quality=options[index]
                if(inPlayer) sessionQuality=quality else { library.maxQuality=quality; sessionQuality=null }
                downgradeEpisode=""; downgradeCount=0
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${quality}P"
                phoneRefreshSettingsValues()
                if(inPlayer && quality!=current) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
                if(inPlayer) refreshPhonePlayerNow()
            },focusIndex=options.indexOf(current)).show()
            return
        }
        val content=column()
        content.addView(TvDialog.title(this,if(inPlayer) "本剧清晰度" else "全局默认清晰度"))
        if(inPlayer) content.addView(text("只对当前这部剧生效，不改「设置 → 播放」里的默认清晰度",12f,muted).apply { setPadding(dp(8),0,dp(8),dp(8)) })
        lateinit var dialog: AlertDialog
        // 用按钮而不是 ListView：弹窗里的 ListView 在部分电视上收不到遥控确认键，
        // 用户按了没反应却看不出原因。按钮的点击路径最稳。
        val items=mutableListOf<TextView>()
        options.forEach { quality ->
            val isCurrent=quality==current
            val item=button("${quality}P"+(if(isCurrent) "   ·   当前" else ""),isCurrent) {
                if(inPlayer) {
                    // 播放中只切这一部剧的档位，不动用户设置的全局默认
                    sessionQuality=quality
                } else {
                    library.maxQuality=quality; sessionQuality=null
                }
                // 用户手动选定档位即视为放弃本轮自动降档，后续失败重新从选定档位起算。
                downgradeEpisode=""; downgradeCount=0
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${quality}P"
                // 选完就收窗：播放中它会挡住画面，而用户下一步要看的是「到底能不能播」。
                dialog.dismiss()
                if(inPlayer && !isCurrent) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }
            items+=item
            // 与倍速选择器一致：行左对齐、弹窗靠左收窄，不遮播放画面
            item.gravity=Gravity.CENTER_VERTICAL
            item.setPadding(dp(16),dp(10),dp(16),dp(10))
            content.addView(item,lp(-1,dp(46)).apply { bottomMargin=dp(8) })
        }
        items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: view.id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        }
        dialog=AlertDialog.Builder(this).setView(TvDialog.card(this,content)).create()
        // 与倍速选择器同一规则：播放中靠左小窗，设置页居中
        TvDialog.styleWindow(this,dialog,maxWidthDp=380,
            gravity=if(inPlayer) Gravity.START else Gravity.CENTER,marginDp=48)
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
        (items.getOrNull(options.indexOf(current)) ?: items.firstOrNull())?.requestFocus()
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        // 手机竖屏播放页按第三方参考实现的交互：选集只弹「数字宫格」底部弹层，不再套 TV 的
        // 侧边面板（那边有跳转集数/上一组等遥控向操作，手机上一没必要二挡画面）。
        if(phonePlayerLayout()) { showPhoneEpisodeSheet(data); return }
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        val lastWatched=library.progress(data.series.id)
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected,savedPosition(selected)) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    // 手机页没有焦点导航，anchor 只是占位；不要强行 requestFocus，
                    // 否则会从触摸控件上抢走焦点，弹层关掉后第一个点击失效。
                    if(!phonePlayerLayout() && anchor.isAttachedToWindow) anchor.requestFocus()
                    // 手机播放页没有 HUD，弹层关闭后不必（也不能）去显示它
                    if(phonePlayerLayout()) { /* PhonePlayer 常驻控制面板无需恢复 */ }
                    else if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            },lastWatched=lastWatched?.episodeIndex,lastCompleted=lastWatched?.completed==true)
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun backdropFile()=filesDir.listFiles()?.firstOrNull { it.name.startsWith("backdrop.") }
    /** Scale [raw] to fill the screen and lay a theme-colored scrim over it so text stays readable. */
    private fun frameBackdrop(raw: Bitmap): Bitmap? {
        val width=resources.displayMetrics.widthPixels; val height=resources.displayMetrics.heightPixels
        if(width<=0 || height<=0) return null
        // 界面缩放（UiScale）时背景内容同倍率放大、超出部分裁边：背景跟 UI 一起变大，
        // 而不是其他元素都放大了背景还停在原尺寸；切档 recreate 后 loadBackdrop 重解码即生效
        val scale=maxOf(width.toFloat()/raw.width,height.toFloat()/raw.height)*UiScale.factor(this)
        val drawWidth=(raw.width*scale).toInt(); val drawHeight=(raw.height*scale).toInt()
        val scaled=if(drawWidth==raw.width && drawHeight==raw.height) raw else Bitmap.createScaledBitmap(raw,drawWidth,drawHeight,true)
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawBitmap(scaled,(width-drawWidth)/2f,(height-drawHeight)/2f,null)
            drawColor(Color.argb(205,Color.red(bg),Color.green(bg),Color.blue(bg)))
        }
        if(scaled!==raw) raw.recycle()
        return output
    }
    private fun decodeBackdrop(bytes: ByteArray): Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
        var sample=1
        val width=resources.displayMetrics.widthPixels; val height=resources.displayMetrics.heightPixels
        while(bounds.outWidth/(sample*2)>=width && bounds.outHeight/(sample*2)>=height) sample*=2
        val raw=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return null
        return frameBackdrop(raw)
    }
    /** The bundled "强制欣赏" backdrop: first-launch default, selectable from 恢复默认背景. */
    private fun decodeForcedBackdrop(): Bitmap? =
        BitmapFactory.decodeResource(resources,R.drawable.backdrop_forced)?.let { frameBackdrop(it) }
    /** TV defaults to the drawn backdrop; existing explicit picture choices and phone defaults remain. */
    private fun backdropStyle(): String {
        val default=if(phoneLayout()) "forced" else "plain"
        return StartupSettings.string("backdropStyle",default)
    }
    private fun loadBackdrop() {
        io.execute {
            val bitmap=backdropFile()?.let { runCatching { decodeBackdrop(it.readBytes()) }.getOrNull() }
                ?: if(backdropStyle()=="forced") runCatching { decodeForcedBackdrop() }.getOrNull() else null
            main.post {
                if(isDestroyed) return@post
                backdropBitmap=bitmap
                backdropView?.let {
                    if(bitmap==null) { (it.parent as? android.view.ViewGroup)?.removeView(it); it.setImageDrawable(null) }
                    else { if(it.parent==null && screen!="player" && !phoneLayout()) root.addView(it,0,FrameLayout.LayoutParams(-1,-1)); it.setImageBitmap(bitmap) }
                }
            }
        }
    }
    /** Drop any uploaded picture and switch back to one of the built-in styles. */
    private fun resetBackdrop(style: String) {
        io.execute { filesDir.listFiles()?.filter { it.name.startsWith("backdrop.") }?.forEach { it.delete() } }
        StartupSettings.set("backdropStyle",style)
        loadBackdrop()
        Toast.makeText(this,if(style=="forced") "已恢复为强制欣赏" else "已恢复默认背景",Toast.LENGTH_SHORT).show()
    }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        val ended=completed || p.playbackState==Player.STATE_ENDED
        val position=p.currentPosition.coerceAtLeast(0)
        // 逐集记录：换集后再回来能接着播；看完的集不留记录，回来时从头开始。
        episodeKey(episodeIndex)?.let { key -> if(ended) episodePositions.remove(key) else episodePositions[key]=position }
        val progress=WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,position,p.duration.coerceAtLeast(0),ended,System.currentTimeMillis())
        library.save(progress)
    }
    private fun episodeKey(index: Int): String? {
        val data=detail ?: return null
        val episode=data.episodes.getOrNull(index) ?: return null
        return "${data.series.id}:$episode"
    }
    /** 切集时该集上次看到的坐标；没有记录（没看过或已看完）返回 0，即从头播。 */
    private fun savedPosition(index: Int): Long = episodeKey(index)?.let { episodePositions[it] } ?: 0L
    /** Starts the official-comment overlay for online or local playback; safe to call repeatedly. */
    private fun startDanmaku() {
        val layer=danmakuView ?: return
        val data=detail ?: return
        val p=player
        if(!library.danmaku || p==null || !playbackReady || playError || p.duration<=0) return
        danmakuLoader=null
        val loader=DanmakuLoader(danmakuRepo,data.series.id,data.episodes[episodeIndex],p.duration)
        danmakuLoader=loader
        layer.setBlockedFilter { text -> extras.blocks(text) }
        layer.bind(loader,{ currentPosition() })
        // bind 会把弹幕层置为 VISIBLE；横屏全屏只有沉浸观看才该看到它，这里按控件显隐再压一次
        if(phonePlayerLayout()) phonePlayer?.refreshDanmakuVisibility()
        val startPosition=currentPosition()
        io.execute { runCatching { loader.loadUpTo(startPosition) } }
    }
    /** 所有播放入口共用弹幕总开关，切换剧集后继续沿用。 */
    private fun toggleDanmaku() {
        val on=!library.danmaku
        library.danmaku=on
        if(on) startDanmaku() else stopDanmaku()
        updatePlaybackText()
        refreshPhonePlayerNow()
        Toast.makeText(this,"已为全部剧集${if(on) "开启" else "关闭"}弹幕",Toast.LENGTH_SHORT).show()
    }
    private fun stopDanmaku(preserveActive: Boolean=false) {
        danmakuLoader=null
        danmakuView?.unbind(preserveActive)
    }
    private fun releasePlayer(preservePlaybackCache: Boolean=false) {
        tvPauseButton?.visibility=View.GONE; tvPauseButton=null
        tvPlaybackHeader?.visibility=View.GONE; tvPlaybackHeader=null
        cancelRecovery()
        dropQueuedNext()
        if(!preservePlaybackCache) cancelPrefetch()
        mediaSession.deactivate(); tvTools.close()
        main.removeCallbacks(menuLongRunnable); menuLongFired=false
        ffActive=false; outroSkipSeconds=0; outroTriggered=false
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        // 手机竖屏选集弹层同样随播放器一起收掉，否则会浮在详情页上
        phoneEpisodeSheet?.dismiss(); phoneEpisodeSheet=null
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.let { android.util.Log.w("HongguoTV","Prefetch end ${it.stats()}") }
        video?.pauseReadAhead(cancelReads=true)
        tvVideoFrameWaitStartedAt=0L
        playerView?.player=null; playerView=null
        playbackSession?.release(); playbackSession=null; player=null; playbackReady=false; playbackFirstFrameRendered=false; transportPlay=null; sleepButton=null; tvPlaybackMenu=null
        playerVideo=null; switchingVideos.clear(); protectPlaybackVideos()
        // 手机播放面板的控件引用随页面一起作废，避免 tick 写到已移除的视图上
        // （旋转重建时由调用方先 takeFullscreenState，release 不会再抢方向）
        phonePlayer?.release()
        // 外壳已拆：它不再是横屏皮肤了。全屏会话标记本身不在这里清——
        // 「从全屏播放进设置页再回来」要先按全屏标记重挂，标记由 exitFullscreenPhone /
        // returnToDetail 负责复位。
        phonePlayerRenderedFullscreen=false
        video=null
        if(!preservePlaybackCache) ensurePlaybackCache().clear()
        // 整集落盘缓存不随播放器一起清：它是磁盘池，进程重启后本来就会重新扫描复用，
        // 离开播放页（进设置、旋转重建、回详情再进来）立刻清空只会让下好的三集白下。
        // 真正的清理只在关闭开关（applyEpisodeCacheSetting）与窗口滑动逐出时发生。
        episodeDiskCache?.pause()
        // 三段状态条随播放页一起作废：releaseCatalogViews() 已经不再清这两处引用
        //（切集场景要保留，否则复用播放器时条子会停在上一次画出的宽度）。
        cacheStripSegments=null; cacheStripView=null
        stopDanmaku(); danmakuView=null
        playerScreenOn=false; refreshScreenOn()
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        // 真正离开播放页才算一次播放会话结束：「只对当前这部剧生效」的档位、倍速、
        // 画面到这里全部作废。不能放进 releasePlayer()——换清晰度重播也会走那条路，
        // 会把用户刚选的档位当场清掉。
        // 标记先于 releasePlayer 复位：release 里的方向交还要看它判断这次是不是全屏会话
        val wasFullscreen=phoneFullscreenPlayback
        phoneFullscreenPlayback=false
        resetPlaybackSession()
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        // 全屏播放中直接退出播放页：屏幕还横着，先把方向交还「启动方向」，
        // 否则详情页会以横屏形态画出来、而手机页面本身只有竖屏那一套。
        // 跟随系统同样先锁竖屏再交还传感器，否则设备横着时系统不会自己转回来。
        if(wasFullscreen) {
            val mode=PhoneUi.orientationMode(this)
            requestedOrientation = if(mode==OrientationMode.AUTO) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else PhoneUi.requestedOrientation(this)
            if(mode==OrientationMode.AUTO) main.postDelayed({
                if(!isFinishing && !isDestroyed) requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }, ORIENTATION_RESTORE_DELAY_MS)
            WindowCompat.getInsetsController(window,window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        if (offlineOnly) { offlineOnly = false; goBackPhone(); return }
        if(phoneLayout()) {
            // 手机竖屏：回到手机版详情页（resume 进来的回首页）
            if(directPlayback && !forceDetail) goBackPhone()
            else { directPlayback=false; screen="detail"; cacheStripSegments=null; cacheStripView=null; detail?.let { phoneTab="detail"; renderPhoneDetail(it) } ?: goBackPhone(); prefetchDetailWindow(episodeIndex) }
            return
        }
        if(directPlayback && !forceDetail) showCatalog()
        else { directPlayback=false; showDetail(true); prefetchDetailWindow(episodeIndex) }
    }
    private fun refreshScreenOn() {
        if(playerScreenOn || homePreviewScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ================= 手机竖屏页面 =================
    // 与 TV 原页面并行的一套纵向 UI：竖屏手持设备走这里，横屏与电视一律走原有页面。
    // 页面状态独立保存（phoneTab/phoneQuery/...），切回横屏再回来不丢位置。

    /** 当前是否由手机竖屏页面接管渲染；播放页遵循「启动方向」：竖屏 = 手机播放页，横屏 = 电视播放器。 */
    private fun phoneLayout()=PhoneUi.shouldUsePlayer(this)

    /**
     * 播放页是否应该由手机播放页（含横屏全屏皮肤）接管。
     *
     * 比 [phoneLayout] 多一种情况：手机播放页点全屏锁成横屏后，皮肤不能翻成电视播放器，
     * 而是换成 [PhonePlayer] 的横屏全屏那套（见 [PhoneUi.shouldUsePhoneFullscreen]）。
     * 是否全屏以本次播放会话的 [phoneFullscreenPlayback] 为准，不读实际方向——
     * 这样强制竖屏下旋转到一半、或系统还没回调方向变化时，页面不会来回跳皮肤。
     */
    private fun phonePlayerLayout() =
        phoneLayout() || (phoneFullscreenPlayback && PhoneUi.shouldUsePhoneFullscreen(this))

    /**
     * 首屏已经画出来了。
     *
     * `initialCatalogPending` 原来只在电视版 showCatalog() 里清，手机竖屏启动走的是
     * showPhoneHome()/playEpisode()/openDetailPhone()，标记永远是 true —— onResume()
     * 每次都命中最前面的「首屏还没渲染」分支，showPhoneHome(load=true) 直接把页面
     * 顶回首页，restoreStoppedPage 的恢复分支永远轮不到。所以手机侧的每个渲染入口
     * 都要调一次，切后台再回来才能按最后所在页面恢复。
     *
     * 只清这个标记，不碰 restoreStoppedPage：后者由 onStop 置位、由 onResume 的恢复
     * 分支自己消费。后台期间若有异步回调（账号状态变化等）触发重渲染，不能把待恢复
     * 标记提前吃掉，否则回来照样回首页。
     */
    private fun markPageShown() {
        initialCatalogPending=false
        // 页面就绪点：补弹先到的更新检查结果；结果晚于首屏时由发布即弹直接触发。
        if(foreground && !isDestroyed && !isFinishing) {
            main.post { drainPendingUpdate() }
        }
    }

    /** 冷启动只消费一次现场；切后台回来继续走原有的内存恢复流程。 */
    private fun showInitialPhonePage() {
        if(!phoneSessionInitialized) {
            phoneSessionInitialized=true
            val saved=phoneSessionStore.load()
            if(saved!=null) {
                phoneSessionPositions.putAll(saved.scrollPositions)
                phoneSessionHeaderOffsets.putAll(saved.headerOffsets)
                pendingPhoneScrolls.putAll(saved.scrollPositions)
                phoneQuery=saved.query; phoneSearchDraft=saved.searchText
                restorePhoneRankSelection(saved.rankContent,saved.rankBoard)
                restorePhoneHotBoard(saved.hotBoard)
                library.newReleaseDay=saved.newDate; phoneNewReleaseDay=saved.newDate
                phoneTab=saved.tab; phoneDetailReturn=saved.originTab
                phoneScrollY=saved.scrollPositions[saved.tab] ?: 0
                // 先结束首屏等待，异步加载详情期间 onResume 也不会再跳回推荐。
                markPageShown()
                val series=saved.series
                if(series!=null && (saved.screen in listOf("detail","player","settings"))) {
                    if (saved.offlineOnly) {
                        StartupSettings.set("mineContentTab", "offline")
                        phoneTab = "mine"
                        switchPhoneTab("mine")
                    } else {
                        restoringPhoneDetail=saved
                        phoneTab=saved.originTab
                        openDetailPhone(series)
                    }
                } else if(saved.screen=="settings") {
                    showPhoneSettings()
                } else {
                    restorePhoneCatalog(saved.tab,saved.searchText)
                }
                return
            }
        }
        showPhoneHome(load=true)
    }

    private fun restorePhoneCatalog(tab: String,draft: String=phoneSearchDraft) {
        phoneTab=tab
        if(tab=="search" && phoneQuery.isNotBlank()) {
            runPhoneSearch(phoneQuery)
            phoneSearchDraft=draft
            retainedPhoneCatalogs.remove("search")?.frame?.let(root::removeView)
            renderPhone()
        } else switchPhoneTab(tab)
    }

    private fun rememberPhoneCatalogPosition() {
        if(screen=="catalog" && !phoneDetailCollection && phoneTab in PhoneSessionStore.TABS && phoneTab !in pendingPhoneScrolls) {
            val retained=retainedPhoneCatalogs[phoneTab]
            phoneSessionPositions[phoneTab]=retained?.position ?: phoneScrollY
            retained?.pager?.let { phoneSessionHeaderOffsets[phoneTab]=it.headerOffset }
        }
    }

    /** 保存导航和播放器位置，播放器的界面与交互不参与现场存储。 */
    private fun savePhoneSession() {
        if(!::library.isInitialized || !library.isLoaded || initialCatalogPending ||
            !(phoneLayout() || screen=="player" && phonePlayerLayout())) return
        restoringPhoneDetail?.let { phoneSessionStore.save(it); return }
        rememberPhoneCatalogPosition()
        val baseTab=phoneTab.takeIf { it in PhoneSessionStore.TABS } ?: phoneDetailReturn
        val playing=screen=="player" || screen=="settings" && phoneSettingsFromPlayer
        val data=detail
        val detailPage=screen=="detail" || screen=="settings" && phoneTab=="detail"
        val series=if(playing) data?.series else if(detailPage) pendingDetail ?: data?.series else null
        phoneSessionStore.save(PhoneSessionSnapshot(
            screen=if(playing) "player" else screen,
            tab=baseTab, originTab=phoneDetailReturn, offlineOnly=offlineOnly,
            query=phoneQuery, searchText=phoneSearchDraft,
            rankContent=phoneRankContentId, rankBoard=phoneRankBoardId, hotBoard=phoneHotBoardName,
            newDate=newReleaseDatePhone, series=series,
            episodeId=if(playing) data?.episodes?.getOrNull(episodeIndex).orEmpty() else "",
            episodeIndex=if(playing) episodeIndex else 0,
            position=if(playing) currentPosition() else 0L,
            scrollPositions=phoneSessionPositions.toMap(), headerOffsets=phoneSessionHeaderOffsets.toMap(),
            detailScroll=if(screen=="settings" && detailPage) phoneSettingsDetailScroll else if(detailPage) portraitDetail.captureScrollY() else 0))
    }

    /** 数据回来并完成布局后恢复滚动，避免空列表首帧把保存的位置夹回零。 */
    private fun restorePhoneCatalogScroll(tab: String,scroll: PortraitListPage?,pager: PortraitFeedPager?) {
        val y=pendingPhoneScrolls[tab] ?: return
        val ready=when(tab) {
            "home" -> phoneRecommendationItems.isNotEmpty()
            "rank" -> rankPagePhone(mobileRankSelectedKeyPhone)?.items?.isNotEmpty()==true
            "new" -> newReleaseItemsPhone.isNotEmpty()
            "search" -> if(phoneQuery.isBlank()) phoneHotSearch!=null else catalog.isNotEmpty() && phoneCatalogSource=="search"
            else -> true
        }
        if(!ready) return
        val view: View=pager ?: scroll ?: return
        val observer=view.viewTreeObserver
        observer.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if(observer.isAlive) observer.removeOnPreDrawListener(this)
                if(phoneTab==tab && screen=="catalog" && view.isShown) {
                    if(pager!=null) {
                        pager.restorePosition(y,headerOffset=phoneSessionHeaderOffsets[tab] ?: 0,expandTitle=true)
                        phoneSessionHeaderOffsets[tab]=pager.headerOffset
                    } else scroll?.restorePosition(y)
                    phoneScrollY=pager?.position ?: scroll?.position ?: y
                    phoneSessionPositions[tab]=phoneScrollY
                    pendingPhoneScrolls.remove(tab)
                }
                return true
            }
        })
        view.invalidate()
    }

    private fun redrawPhoneTheme() {
        if(!::library.isInitialized || !library.isLoaded || !phoneLayout() || screen=="player") return
        rememberPhoneCatalogPosition()
        retainedPhoneCatalogs.clear()
        when(screen) {
            "detail" -> {
                val data=detail
                if(data!=null) {
                    val position=portraitDetail.captureScrollY()
                    renderPhoneDetail(data); portraitDetail.restoreScrollY(position)
                } else pendingDetail?.let { series ->
                    portraitDetail.renderState(root,"正在加载剧集…") { openDetailPhone(series) }
                }
            }
            "settings" -> phoneSettings.render(root)
            else -> if(phoneDetailCollection) collection?.let(::renderPhoneCollection) else renderPhone()
        }
    }

    /** 手机竖屏页签：推荐 / 排行 / 新剧 / 我的 / 搜索，以及我的内的收藏页。 */
    private var phoneTab="home"
    /** 手机页面滚动位置：切页签/横竖屏后恢复。 */
    private var phoneScrollY=0
    /** 搜索页状态。PortraitPages 只读展示，写入仍集中在 runPhoneSearch。 */
    private var phoneQuery=""
    private var phoneSearching=false
    var phoneSearchDraft=""
    private var phoneSearchError=""
    val phoneSearchErrorPhone get()=phoneSearchError
    val phoneQueryText get() = phoneQuery
    val phoneSearchActive get() = phoneSearching
    /** 排行/搜索共用 catalog，按归属恢复；推荐持有独立的卡片和游标。 */
    private var phoneCatalogSource=""
    /** 打开手机合集页时的来源页签，关闭时回到原处。 */
    private var phoneCollectionTab="home"
    /** 打开手机详情页前的页签，返回时回到原处（首页/排行/收藏/搜索）。 */
    private var phoneDetailReturn="home"
    val phoneDetailOriginTab get() = phoneDetailReturn
    // TV 的 RankBoard 保留原口径；手机目录和子榜由官方接口返回。
    var rankBoardPhone=RankBoard.COMIC
        private set
    var rankingPhone: ComicRanking?=null
        private set
    private var phoneRankLoading=false
    private var phoneRankReady=false
    private var phoneRankContent=""
    private var phoneRankBoard=""
    private var phoneRankContents=emptyList<MobileRankContent>()
    private val phoneMobileRankPages=mutableMapOf<String,MobileRankPage>()
    private val phoneMobileRankErrors=mutableMapOf<String,String>()
    private var phoneMobileRankCacheReady=false
    /** SP 只有展示快照，每个榜首次进入仍需联网建立本进程的分页会话。 */
    private val phoneMobileRankLoadedKeys=mutableSetOf<String>()
    private var phoneRankFailedMore=false
    val phoneRankContentId get()=phoneRankContent
    val phoneRankBoardId get()=phoneRankBoard
    val mobileRankContentsPhone get()=phoneRankContents
    val mobileRankBoardsPhone get()=phoneRankContents.firstOrNull { it.id==phoneRankContent }?.boards.orEmpty()
    val mobileRankSelectedKeyPhone get()=phoneRankBoard.ifBlank { "_default" }
    val rankLoadingPhone get()=phoneRankLoading
    private fun mobileRankCacheKey(content: String=phoneRankContent,board: String=phoneRankBoard)="$content|$board"
    fun rankPagePhone(board: String): MobileRankPage?=phoneMobileRankPages[mobileRankCacheKey(board=board.takeUnless { it=="_default" }.orEmpty())]
    fun rankErrorPhone(board: String)=phoneMobileRankErrors[mobileRankCacheKey(board=board.takeUnless { it=="_default" }.orEmpty())].orEmpty()
    /** 仅恢复选择；实际请求由恢复页签时发起，游标不跨进程复用。 */
    fun restorePhoneRankSelection(contentId: String,boardId: String) {
        phoneRankContent=contentId.take(100); phoneRankBoard=boardId.take(100); phoneRankReady=false
    }
    private var phoneHotSearch: HotSearchPage?=null
    private var phoneHotLoading=false
    private var phoneHotError=""
    private var phoneHotAttempted=false
    private var phoneHotCacheReady=false
    private var phoneHotBoard=""
    val hotSearchPhone get()=phoneHotSearch
    val hotSearchLoadingPhone get()=phoneHotLoading
    val hotSearchErrorPhone get()=phoneHotError
    val phoneHotBoardName get()=phoneHotBoard
    fun restorePhoneHotBoard(name: String) { phoneHotBoard=name.take(100) }
    fun chooseHotBoardPhone(name: String) { phoneHotBoard=name; renderPhone() }
    /** 新剧日历独立于首页/榜单的 catalog，按服务端日期缓存列表和游标。 */
    private val phoneNewReleasePages=mutableMapOf<String,NewReleasePage>()
    /** SP 快照只用于展示，每个日期首次进入仍联网更新列表和游标。 */
    private val phoneNewReleaseLoadedDates=mutableSetOf<String>()
    private var phoneNewReleaseDay=""
    private var phoneNewReleaseCalendarDay=""
    private var phoneNewReleaseDates=emptyList<String>()
    private var phoneNewReleaseLoading=false
    private var phoneNewReleaseError=""
    private var phoneNewReleaseFailedMore=false
    val newReleaseDatePhone get() = phoneNewReleaseDay.ifBlank { NewReleaseCalendar.today() }
    val newReleaseDatesPhone get() = phoneNewReleaseDates
    val newReleaseItemsPhone get() = phoneNewReleasePages[newReleaseDatePhone]?.items.orEmpty()
    val newReleaseHasMorePhone get() = phoneNewReleasePages[newReleaseDatePhone]?.cursor!=null
    val newReleaseLoadingPhone get() = phoneNewReleaseLoading
    val newReleaseErrorPhone get() = phoneNewReleaseError
    fun newReleasePagePhone(date: String): NewReleasePage? =
        phoneNewReleasePages[date] ?: library.cachedNewReleases(date)

    private fun prepareNewReleaseCalendarPhone() {
        val today=NewReleaseCalendar.today()
        if(phoneNewReleaseCalendarDay==today) return
        val saved=if(phoneNewReleaseCalendarDay.isEmpty()) library.newReleaseDay else phoneNewReleaseDay
        val nearbyDates=NewReleaseCalendar.nearbyDates()
        phoneNewReleaseDay=saved.takeIf { it in nearbyDates }.orEmpty()
        val date=newReleaseDatePhone
        // 首屏直接复用快照的日期栏，避免联网后从本地兜底日期切换导致整页重排。
        phoneNewReleaseDates=library.cachedNewReleases(date)?.dates?.takeIf { it.isNotEmpty() } ?: nearbyDates
        if(date !in phoneNewReleaseDates) phoneNewReleaseDates=(phoneNewReleaseDates+date).sorted()
        phoneNewReleaseCalendarDay=today
        phoneNewReleasePages.clear(); phoneNewReleaseLoadedDates.clear(); phoneNewReleaseError=""
    }

    fun chooseNewReleaseDayPhone(date: String) {
        if(!phoneLayout() || screen!="catalog" || phoneTab!="new" || phoneDetailCollection) return
        if(date !in phoneNewReleaseDates || date==newReleaseDatePhone) return
        pendingPhoneScrolls.remove("new")
        cancelWork()
        phoneNewReleaseDay=date; library.newReleaseDay=date
        phoneNewReleaseError=""
        renderPhone()
    }

    /** 刷新替换当前日，分页只追加；切页取消后，下次渲染会补发尚未完成的首屏请求。 */
    private fun loadNewReleasesPhone(more: Boolean=false) {
        if(!phoneLayout() || screen!="catalog" || phoneTab!="new" || phoneNewReleaseLoading) return
        val date=phoneNewReleaseDay
        val previous=phoneNewReleasePages[newReleaseDatePhone]
        val cursor=if(more) previous?.cursor ?: return else null
        val ticket=generation
        phoneNewReleaseError=""; phoneNewReleaseFailedMore=more
        work({ repository.newReleases(date,cursor) }, { result ->
            if(phoneLayout() && ticket==generation && screen=="catalog" && phoneTab=="new" &&
                phoneNewReleaseDay==date && !phoneDetailCollection) {
                phoneNewReleaseLoading=false
                val items=(if(more && previous?.date==result.date) (previous.items+result.items).distinctBy { it.series.id } else result.items).take(PortraitUi.PHONE_LIST_LIMIT)
                val loadedPage=result.copy(items=items,cursor=result.cursor.takeIf { items.size<PortraitUi.PHONE_LIST_LIMIT })
                phoneNewReleasePages[result.date]=loadedPage
                phoneNewReleaseLoadedDates+=result.date
                phoneNewReleaseDay=result.date; library.newReleaseDay=result.date
                if(result.dates.isNotEmpty()) phoneNewReleaseDates=result.dates
                if(result.date !in phoneNewReleaseDates) phoneNewReleaseDates=(phoneNewReleaseDates+result.date).sorted()
                phoneNewReleasePages.keys.retainAll(phoneNewReleaseDates.toSet())
                phoneNewReleaseLoadedDates.retainAll(phoneNewReleaseDates.toSet())
                library.cacheNewReleases(loadedPage.copy(dates=phoneNewReleaseDates))
                renderPhone()
            }
        }, { problem ->
            if(phoneLayout() && ticket==generation && phoneTab=="new" && phoneNewReleaseDay==date) {
                phoneNewReleaseLoading=false
                val reason=problem.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(160)
                phoneNewReleaseError=buildString {
                    append(if(more) "更多新剧加载失败" else "新剧加载失败")
                    if(!reason.isNullOrBlank()) append("：").append(reason)
                    append("\n点此重试")
                }
                renderPhone()
                android.util.Log.w("HongguoTV","Phone new releases failed (date=${date.ifBlank { "default" }}, offset=${cursor?.offset ?: 0})",problem)
            }
        })
        // work 会先取消上一请求；在它完成取消后再标记，避免新请求被当作空闲而重复加载。
        phoneNewReleaseLoading=true
        renderPhone()
    }

    fun loadMoreNewReleasesPhone() {
        if(!phoneNewReleaseLoading && phoneNewReleaseError.isBlank() && newReleaseHasMorePhone) loadNewReleasesPhone(more=true)
    }

    fun retryNewReleasesPhone() {
        loadNewReleasesPhone(more=phoneNewReleaseFailedMore && newReleaseHasMorePhone)
    }

    /** 手机收藏分组筛选。 */
    var favoriteGroupFilterPhone: String?=null
    /** 手机播放页的集号镜像（PortraitDetail/PhonePlayer 读取，同步自 episodeIndex）。 */
    val phoneEpisodeIndex get() = episodeIndex
    val phonePlaybackReady get() = playbackReady
    /** 手机播放页当前剧集信息（PhonePlayer 收藏 / 切集后刷新用）。 */
    val phoneCurrentSeries get() = detail?.series
    /** PhonePlayer 进度条用的总时长（毫秒）；未就绪返回 0。 */
    val playerDuration get() = player?.duration?.takeIf { it>0 } ?: 0L
    /** 手机播放页 UI 实例（单例复用，控件引用由 render 重建时刷新）。 */
    private var phonePlayer: PhonePlayer?=null
    private val phonePlayerOrCreate get() = phonePlayer ?: PhonePlayer(this,::loadCover).also { phonePlayer=it }
    /** 当前手机播放页实例；PhonePlayer 用它确认自己没有被旋转重建替换掉。 */
    val currentPhonePlayer: PhonePlayer? get() = phonePlayer
    /**
     * 当前播放会话是否处于「手机横屏全屏皮肤」。
     *
     * 手机播放页点全屏会锁横屏，但皮肤不能跟着 phoneLayout() 变成电视播放器：
     * 这个标记跟着播放会话走，直到用户退出全屏 / 离开播放页才复位。
     * 旋转重建时也是它决定新外壳长成竖屏还是横屏那套。
     */
    private var phoneFullscreenPlayback=false
    /** 当前挂着的 PhonePlayer 外壳是不是横屏全屏那套（判断要不要补重建，见 [phonePlayerIsFullscreenSkin]）。 */
    private var phonePlayerRenderedFullscreen=false
    // 手机页面实例：首次使用时创建后缓存（lazy 不能修饰 var）
    private val portraitPages by lazy { PortraitPages(this,::loadCover) }
    private var phoneCoverTransition: PosterSharedTransition?=null
    private var phoneCoverReturnName: String?=null
    private var phoneCoverReturnScrollState: android.os.Parcelable?=null
    private val portraitDetail by lazy {
        PortraitDetail(this,
            loadCover = { view, url -> artwork.load(view, url, keepCurrentImage = true) },
            onPosterReady = { view -> phoneCoverTransition?.bind(view,seedCover=view.drawable==null) })
    }

    /** 所有手机竖屏详情统一使用新版界面，播放页继续使用原生 PhonePlayer。 */
    private fun renderPhoneDetail(data: Detail) {
        portraitDetail.render(root,data)
    }
    /** 手机端完整设置页（竖屏可调全部设置，不必再转到横屏）。 */
    private val phoneSettings by lazy { PhoneSettings(this) }
    /** 播放中点进手机设置页时记下的进度与全屏态，退出设置后原样恢复。 */
    private var phoneSettingsDetailScroll=0
    private var phoneSettingsFromPlayer=false
    private var phoneSettingsPosition=0L
    private var phoneSettingsAutoplay=true
    /** 进来前是否处于全屏播放：退出设置后原样恢复。 */
    private var phoneSettingsFullscreen=false
    /** 界面缩放重建后待回到手机设置页（见 PageRestore.phoneSettings）。 */
    private var pendingPhoneSettings=false

    /** 推荐、排行、新剧和我的各保留列表与滚动容器，切页复用已显示的封面。 */
    private data class RetainedPhoneCatalog(
        val frame: FrameLayout,
        val nav: LinearLayout,
        val scroll: PortraitListPage?,
        val refreshLayout: PortraitUi.RefreshLayout?,
        val update: (Boolean) -> Unit,
        var state: CatalogState,
        val pager: PortraitFeedPager?=null,
    ) {
        val position get() = pager?.position ?: scroll?.position ?: 0
        fun finishRefresh() { refreshLayout?.finishRefresh() }
    }
    private val retainedPhoneCatalogs=mutableMapOf<String,RetainedPhoneCatalog>()
    private var phoneNavigationTab: String?=null
    private var retainedPhoneType: ContentType?=null
    private var retainedPhoneTheme=TvStyle.theme
    private var retainedPhoneConfiguration: android.content.res.Configuration?=null

    private fun phoneCatalogViewsValid() = retainedPhoneType==library.contentType &&
        retainedPhoneTheme==TvStyle.theme && retainedPhoneConfiguration==resources.configuration &&
        retainedPhoneCatalogs.values.all { it.frame.parent===root }

    private fun capturePhoneCatalog()=if(phoneTab=="home")
        CatalogState(1,phoneRecommendationItems,phoneRecommendationCursor!=null,null)
        else CatalogState(if(phoneTab=="rank") phoneRankPage else page,catalog,hasMore,rankingPhone,rankBoardPhone)

    private fun phoneFeedLoading()=when(phoneTab) {
        "home" -> phoneHomeLoading
        "rank" -> phoneRankLoading
        "new" -> phoneNewReleaseLoading
        "search" -> phoneHotLoading || phoneSearching
        else -> false
    }

    /** 手机底部导航渲染总入口：按 phoneTab 渲染对应页面。 */
    private fun renderPhone() {
        if(!phoneLayout() || !library.isLoaded) return
        // 设置页是一整屏独立页面，不能被页签重渲染顶掉
        if(screen=="settings") return
        // detail/player 有各自的渲染入口，误调进来会把正在看的页面清成空骨架
        if(phoneTab=="detail" || phoneTab=="player") return
        screen="catalog"
        if(phoneTab=="home") {
            preparePhoneRecommendations()
            if(!phoneRecommendationStarted && !phoneHomeLoading && phoneRecommendationError.isBlank()) {
                loadPhoneRecommendations()
                return
            }
        }
        if(phoneTab=="rank") {
            prepareMobileRankCachePhone()
            val key=mobileRankCacheKey()
            if(!phoneRankLoading && key !in phoneMobileRankLoadedKeys && phoneMobileRankErrors[key].isNullOrBlank()) {
                showPhoneRank()
                return
            }
        }
        if(phoneTab=="new") {
            prepareNewReleaseCalendarPhone()
            val date=newReleaseDatePhone
            if(phoneNewReleasePages[date]==null) library.cachedNewReleases(date)?.let { phoneNewReleasePages[date]=it }
            if(!phoneNewReleaseLoading && date !in phoneNewReleaseLoadedDates && phoneNewReleaseError.isBlank()) {
                loadNewReleasesPhone()
                return
            }
        }
        if(phoneTab=="search" && phoneQuery.isBlank()) {
            prepareHotSearchPhone()
            if(!phoneHotAttempted && !phoneHotLoading) {
                loadHotSearchPhone()
                return
            }
        }
        markPageShown()
        // 从播放页（黑底白图标）返回或切换主题后，状态栏图标外观跟当前主题对齐
        phoneStatusBarFollowTheme()
        if(!phoneCatalogViewsValid()) retainedPhoneCatalogs.clear()
        val previousNavigation=retainedPhoneCatalogs[phoneNavigationTab]?.nav as? PortraitGlassNavigation
        retainedPhoneCatalogs[phoneTab]?.let { retained ->
            previousNavigation?.let { (retained.nav as? PortraitGlassNavigation)?.continueFrom(it) }
            PortraitUi.paintNav(retained.nav,phoneTab,phoneNavigationTab)
            phoneNavigationTab=phoneTab
            // 联网刷新和翻页只更新差异卡片；页签切换只改变可见性，不触发 detach。
            retained.update(phoneHomeLoading)
            retained.refreshLayout?.updateLoading(phoneFeedLoading())
            retained.state=capturePhoneCatalog()
            retainedPhoneCatalogs.forEach { (key,value) ->
                value.frame.visibility=if(key==phoneTab) View.VISIBLE else View.GONE
                if(key!=phoneTab) value.finishRefresh()
            }
            phoneScrollY=retained.position
            restorePhoneCatalogScroll(phoneTab,retained.scroll,retained.pager)
            return
        }
        val renderedTab=phoneTab
        // 在新视图回调滚动位置前，先固定本次要恢复的列表与标题状态。
        val restoreY=pendingPhoneScrolls[renderedTab] ?: phoneScrollY
        val restoreHeaderOffset=phoneSessionHeaderOffsets[renderedTab] ?: 0
        val (frame,content,nav)=PortraitUi.scaffold(this,phoneTab,::switchPhoneTab,phoneNavigationTab)
        previousNavigation?.let { (nav as? PortraitGlassNavigation)?.continueFrom(it) }
        val feedPager=when(phoneTab) {
            "rank" -> portraitPages.rankPager()
            "new" -> portraitPages.newReleasesPager()
            else -> null
        }
        val scroll=if(feedPager!=null) null else PortraitListPage(this)
        var refreshLayout: PortraitUi.RefreshLayout?=null
        val update: ((Boolean) -> Unit)?
        if(feedPager!=null) {
            // 与推荐页使用相同的外层刷新容器，分页容器负责转发标题消费后的剩余滚动。
            refreshLayout=PortraitUi.RefreshLayout(this,feedPager,::refreshPortraitFeed,minDurationMillis=600L).apply {
                setOnChildScrollUpCallback { _, _ -> feedPager.canScrollUp }
            }
            content.addView(refreshLayout,lp(-1,0).apply { weight=1f })
            feedPager.onPositionChanged={ y ->
                if(feedPager.isShown && phoneTab==renderedTab && screen=="catalog") phoneScrollY=y
            }
            update={ feedPager.update() }
        } else {
            val feedScroll=checkNotNull(scroll)
            update=when(phoneTab) {
                "home" -> portraitPages.home(feedScroll)
                "fav" -> portraitPages.favorites(feedScroll)
                "mine" -> portraitPages.mine(feedScroll)
                "search" -> portraitPages.search(feedScroll,::runPhoneSearch)
                else -> null
            }
            refreshLayout=PortraitUi.RefreshLayout(this,feedScroll,::refreshPortraitFeed,
                maxDurationMillis=if(phoneTab=="home") 3_000L else 0L)
            content.addView(refreshLayout ?: feedScroll,lp(-1,0).apply { weight=1f })
        }
        update?.invoke(phoneHomeLoading)
        if(update!=null) {
            if(retainedPhoneCatalogs.isEmpty()) {
                root.removeAllViews()
                retainedPhoneType=library.contentType; retainedPhoneTheme=TvStyle.theme
                retainedPhoneConfiguration=android.content.res.Configuration(resources.configuration)
            }
            retainedPhoneCatalogs.values.forEach {
                it.frame.visibility=View.GONE
                it.finishRefresh()
            }
            retainedPhoneCatalogs[phoneTab]=RetainedPhoneCatalog(frame,nav,scroll,refreshLayout,update,capturePhoneCatalog(),feedPager)
        } else {
            retainedPhoneCatalogs.clear()
            root.removeAllViews()
        }
        root.addView(frame,android.widget.FrameLayout.LayoutParams(-1,-1))
        phoneNavigationTab=phoneTab
        restorePhoneCatalogScroll(renderedTab,scroll,feedPager)
        // 冷启动由数据就绪后的现场恢复处理，避免重复恢复把已展开的标题再次收起。
        if(feedPager!=null && renderedTab !in pendingPhoneScrolls) feedPager.post {
            if(feedPager.isShown && phoneTab==renderedTab && screen=="catalog") {
                feedPager.restorePosition(restoreY,headerOffset=restoreHeaderOffset)
            }
        }
        scroll?.let { feedScroll ->
            feedScroll.post { if(feedScroll.isShown && phoneTab==renderedTab && feedScroll.position!=restoreY) feedScroll.restorePosition(restoreY) }
            feedScroll.onPositionChanged={ y ->
                if(feedScroll.isShown && phoneTab==renderedTab && screen=="catalog") phoneScrollY=y
            }
        }
    }

    /** 手机推荐与 iOS 一样是独立的推荐流，不受本地内容类型或 TV catalog 影响。 */
    private var phoneHomeLoading=false
    private var phoneRecommendationItems=emptyList<Series>()
    private var phoneRecommendationCursor: RecommendationCursor?=null
    private var phoneRecommendationCacheReady=false
    private var phoneRecommendationStarted=false
    private var phoneRecommendationError=""
    private var phoneRecommendationFailedMore=false
    private var phoneRecommendationRetry: Runnable?=null
    private var phoneRecommendationRetries=0
    private var phoneRecommendationDiagnostic="尚未请求"
    val recommendationItemsPhone get() = phoneRecommendationItems
    val recommendationHasMorePhone get() = phoneRecommendationCursor!=null
    val recommendationErrorPhone get() = phoneRecommendationError

    private fun preparePhoneRecommendations() {
        if(phoneRecommendationCacheReady) return
        phoneRecommendationItems=library.cachedRecommendations()
        phoneRecommendationCacheReady=true
    }

    fun switchPhoneTab(next: String) {
        phoneCoverTransition?.cancel(); phoneCoverTransition=null
        rememberPhoneCatalogPosition()
        restoringPhoneDetail=null
        if(next==phoneTab && !phoneDetailCollection && !initialCatalogPending) {
            screen="catalog"
            when {
                next=="rank" && !phoneRankReady -> showPhoneRank()
                next=="search" && phoneQuery.isNotBlank() && !phoneSearching &&
                    (phoneCatalogSource!="search" || catalog.isEmpty() && phoneSearchError.isBlank()) -> runPhoneSearch(phoneQuery)
                else -> renderPhone()
            }
            return
        }
        if(phoneDetailCollection) { phoneDetailCollection=false; leaveCollection(); retainedPhoneCatalogs.clear() }
        // 页签共用 work 管线：离开时取消旧请求，并释放分页锁，避免回调串写另一页。
        cancelWork(); loadingMore=false
        if(!phoneCatalogViewsValid()) retainedPhoneCatalogs.clear()
        val retained=retainedPhoneCatalogs[next]
        phoneTab=next; phoneScrollY=retained?.position ?: phoneSessionPositions[next] ?: 0; screen="catalog"
        if(next=="rank") {
            prepareMobileRankCachePhone()
            phoneCatalogSource="rank"
            val cached=rankPagePhone(mobileRankSelectedKeyPhone)
            catalog=cached?.items.orEmpty(); hasMore=cached?.cursor!=null; rankingPhone=null
            if(cached!=null) { phoneRankReady=true; renderPhone() } else showPhoneRank()
            return
        }
        // 推荐、新剧和我的各自取数，避免恢复其他页签的共用 catalog。
        if(next=="home" || next=="mine") { renderPhone(); return }
        if(retained!=null) {
            val saved=retained.state
            catalog=saved.items; hasMore=saved.hasMore; rankingPhone=saved.ranking; phoneCatalogSource=next
            page=saved.page
            renderPhone()
            // 首次加载被快速切页取消时，回来仍要继续请求。
            return
        }
        when(next) {
            "fav" -> { favoriteGroupFilterPhone=null; renderPhone() }
            "search" -> if(phoneQuery.isNotBlank() && (phoneCatalogSource!="search" || catalog.isEmpty())) runPhoneSearch(phoneQuery)
                else { if(phoneQuery.isBlank()) { catalog=emptyList(); hasMore=false }; renderPhone() }
            else -> renderPhone()
        }
    }

    /** 回到手机推荐页；刷新合并新卡片，已有分页会话与滚动位置继续保留。 */
    private fun showPhoneHome(load: Boolean=false) {
        if(!phoneLayout()) return
        screen="catalog"; phoneTab="home"
        preparePhoneRecommendations()
        if(load) {
            cancelWork()
            loadPhoneRecommendations()
        } else renderPhone()
    }

    private fun loadPhoneRecommendations(more: Boolean=false) {
        if(!phoneLayout() || screen!="catalog" || phoneTab!="home" || phoneHomeLoading || phoneDetailCollection) return
        preparePhoneRecommendations()
        val previous=phoneRecommendationItems
        val previousCursor=phoneRecommendationCursor
        val cursor=if(more) previousCursor ?: return else null
        val keepCursor=!more && phoneRecommendationStarted && phoneRecommendationError.isBlank()
        val ticket=generation
        phoneRecommendationError=""; phoneRecommendationFailedMore=more
        fun applyPage(result: RecommendationPage, finished: Boolean) {
            if(!phoneLayout() || ticket!=generation || screen!="catalog" || phoneTab!="home" || phoneDetailCollection) return
            // 已有内容时等刷新批次完整返回，再一次提交差异，避免多批数据反复移动封面。
            if(!finished && !more && previous.isNotEmpty()) return
            phoneHomeLoading=!finished
            // 每个阶段都是本批累计结果，相对加载前的快照合并，追加分页不会重排已显示的海报。
            val combined=if(more) previous+result.items else result.items+previous
            phoneRecommendationItems=combined.distinctBy { it.id }.take(PortraitUi.PHONE_LIST_LIMIT)
            phoneRecommendationCursor=when {
                phoneRecommendationItems.size>=PortraitUi.PHONE_LIST_LIMIT -> null
                finished && more && phoneRecommendationItems.size==previous.size -> null
                keepCursor -> previousCursor
                else -> result.cursor
            }
            phoneRecommendationStarted=true
            phoneRecommendationRetries=0
            phoneRecommendationDiagnostic=result.diagnostic
            RuntimeLog.add("recommendations ${result.diagnostic} total=${phoneRecommendationItems.size} " +
                "visible=${phoneRecommendationItems.count { it.id !in library.hidden() }} finished=$finished")
            // 分批返回和追加分页都写入 SP；离页取消时仍保留已经展示的数据。
            library.cacheRecommendations(phoneRecommendationItems)
            renderPhone()
            // 首批已合并频道与猜你喜欢两个接口，展示后即可收起刷新圈，后续批量预取继续。
            retainedPhoneCatalogs["home"]?.refreshLayout?.finishRefresh()
        }
        work({
            val scope=RequestScope.current()
            repository.recommendationBatch(cursor) { partial ->
                main.post {
                    if(!isDestroyed && foreground && scope?.isCancelled!=true) applyPage(partial,finished=false)
                }
            }
        }, { result ->
            applyPage(result,finished=true)
        }, { problem ->
            if(phoneLayout() && ticket==generation && screen=="catalog" && phoneTab=="home" && !phoneDetailCollection) {
                phoneHomeLoading=false
                phoneRecommendationDiagnostic=if(problem is EmptyRecommendationException) problem.diagnostic
                    else problem.javaClass.simpleName
                RuntimeLog.add("recommendations failed ${phoneRecommendationDiagnostic}")
                val reason=problem.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(160)
                phoneRecommendationError=buildString {
                    append(if(more) "更多推荐加载失败" else "推荐加载失败")
                    if(!reason.isNullOrBlank()) append("：").append(reason)
                    append("\n点此重试")
                }
                renderPhone()
                android.util.Log.w("HongguoTV","Phone recommendations failed",problem)
                // 与 iOS 空列表退避一致：2/4/8/16 秒各重试一次，离页立即取消。
                if(phoneRecommendationItems.isEmpty() && problem is EmptyRecommendationException && phoneRecommendationRetries<4) {
                    val delay=2_000L shl phoneRecommendationRetries
                    phoneRecommendationRetries++
                    val retry=Runnable {
                        phoneRecommendationRetry=null
                        if(foreground && screen=="catalog" && phoneTab=="home") loadPhoneRecommendations(more=more)
                    }
                    phoneRecommendationRetry=retry
                    main.postDelayed(retry,delay)
                }
            }
        })
        // work 先取消旧请求；完成取消后才标记本次加载，防止重渲染重复发起首屏。
        phoneHomeLoading=true
        renderPhone()
    }

    fun retryRecommendationsPhone() {
        phoneRecommendationRetries=0
        loadPhoneRecommendations(more=phoneRecommendationFailedMore && phoneRecommendationCursor!=null)
    }

    /** 冷启动先从 SP 恢复最新目录与各子榜展示数据，分页游标只取联网结果。 */
    private fun prepareMobileRankCachePhone() {
        if(phoneMobileRankCacheReady) return
        phoneMobileRankCacheReady=true
        val pages=library.cachedMobileRanks()
        val latest=pages.firstOrNull() ?: return
        phoneRankContents=latest.contents
        pages.forEach { cached ->
            val valid=phoneRankContents.any { content ->
                content.id==cached.contentId && content.boards.any { it.id==cached.boardId }
            }
            if(valid) phoneMobileRankPages[mobileRankCacheKey(cached.contentId,cached.boardId)]=cached.copy(contents=phoneRankContents,cursor=null)
        }
        val content=phoneRankContents.firstOrNull { it.id==phoneRankContent }
            ?: phoneRankContents.firstOrNull { it.id==latest.contentId } ?: phoneRankContents.firstOrNull() ?: return
        phoneRankContent=content.id
        phoneRankBoard=content.boards.firstOrNull { it.id==phoneRankBoard }?.id
            ?: pages.firstOrNull { it.contentId==content.id && content.boards.any { board -> board.id==it.boardId } }?.boardId
            ?: content.boards.firstOrNull { it.selected }?.id ?: content.boards.firstOrNull()?.id.orEmpty()
        phoneMobileRankPages[mobileRankCacheKey()]?.let { cached ->
            catalog=cached.items; hasMore=false; rankingPhone=null; phoneCatalogSource="rank"
            phoneRankReady=true
        }
    }

    /** 手机榜单按内容与子榜缓存；每批按真实游标逐页补足，续页保留已显示的顺序。 */
    private fun showPhoneRank(more: Boolean=false) {
        if(phoneRankLoading || !phoneLayout() || phoneTab!="rank") return
        prepareMobileRankCachePhone()
        val content=phoneRankContent; val board=phoneRankBoard
        val key=mobileRankCacheKey(content,board)
        val previous=phoneMobileRankPages[key]
        val cursor=if(more) previous?.cursor ?: return else null
        val ticket=generation
        var activeContent=content; var activeBoard=board
        phoneCatalogSource="rank"; phoneRankFailedMore=more
        phoneMobileRankErrors.remove(key)
        fun applyPage(result: MobileRankPage, finished: Boolean) {
            if(!phoneLayout() || ticket!=generation || screen!="catalog" || phoneTab!="rank" ||
                phoneRankContent!=activeContent || phoneRankBoard!=activeBoard || phoneDetailCollection) return
            // 缓存或旧榜单继续显示到刷新完成，避免首批较短的列表先移除、后补回已有卡片。
            if(!finished && !more && !previous?.items.isNullOrEmpty()) return
            phoneRankLoading=!finished; phoneRankReady=true
            if(result.contents.isNotEmpty()) phoneRankContents=result.contents
            phoneRankContent=result.contentId; phoneRankBoard=result.boardId
            activeContent=result.contentId; activeBoard=result.boardId
            // 分阶段回调是本批累计结果，始终相对加载前的快照追加，避免重复插入。
            val items=(if(more) (previous!!.items+result.items).distinctBy { it.id } else result.items).take(PortraitUi.PHONE_LIST_LIMIT)
            val loaded=result.copy(items=items,contents=phoneRankContents,
                cursor=result.cursor.takeIf { items.size<PortraitUi.PHONE_LIST_LIMIT })
            phoneMobileRankLoadedKeys+=key
            phoneMobileRankLoadedKeys+=mobileRankCacheKey()
            phoneMobileRankPages[key]=loaded
            phoneMobileRankPages[mobileRankCacheKey()]=loaded
            phoneMobileRankErrors.remove(mobileRankCacheKey())
            catalog=items; hasMore=loaded.cursor!=null; rankingPhone=null
            library.cacheMobileRank(loaded)
            renderPhone()
        }
        work({
            val scope=RequestScope.current()
            repository.mobileRankingBatch(content,board,cursor) { partial ->
                main.post {
                    if(!isDestroyed && foreground && scope?.isCancelled!=true) applyPage(partial,finished=false)
                }
            }
        }, { result ->
            applyPage(result,finished=true)
        }, { problem ->
            if(phoneLayout() && ticket==generation && screen=="catalog" && phoneTab=="rank" &&
                phoneRankContent==activeContent && phoneRankBoard==activeBoard && !phoneDetailCollection) {
                phoneRankLoading=false
                phoneMobileRankErrors[mobileRankCacheKey()]=(if(more) "更多榜单加载失败" else "榜单加载失败")+
                    "："+problem.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(120)+"\n点此重试"
                renderPhone()
            }
        })
        phoneRankLoading=true
        renderPhone()
    }

    // CatalogState 的历史字段仍供电视和合集返回使用，手机分页使用 MobileRankCursor。
    private var phoneRankPage=1
    fun switchRankContentPhone(contentId: String) {
        if(!phoneLayout() || screen!="catalog" || phoneTab!="rank" || contentId==phoneRankContent) return
        changeMobileRankPhone(contentId,"")
    }
    fun switchRankBoardPhone(board: String) {
        val id=board.takeUnless { it=="_default" }.orEmpty()
        if(!phoneLayout() || screen!="catalog" || phoneTab!="rank" || id==phoneRankBoard) return
        changeMobileRankPhone(phoneRankContent,id)
    }
    private fun changeMobileRankPhone(content: String,board: String) {
        pendingPhoneScrolls.remove("rank")
        cancelWork(); loadingMore=false
        phoneRankContent=content
        phoneRankBoard=board.ifBlank {
            val boards=phoneRankContents.firstOrNull { it.id==content }?.boards.orEmpty()
            boards.firstOrNull { it.selected }?.id ?: boards.firstOrNull()?.id.orEmpty()
        }
        phoneCatalogSource="rank"
        val cached=phoneMobileRankPages[mobileRankCacheKey()]
        catalog=cached?.items.orEmpty(); hasMore=cached?.cursor!=null; rankingPhone=null
        phoneRankReady=cached!=null
        if(cached!=null) renderPhone() else showPhoneRank()
    }
    fun retryRankPhone()=showPhoneRank(more=phoneRankFailedMore && rankPagePhone(mobileRankSelectedKeyPhone)?.cursor!=null)

    /** SP 快照只用于首帧展示，每次进程的首次进入仍联网刷新。 */
    private fun prepareHotSearchPhone() {
        if(phoneHotCacheReady) return
        phoneHotCacheReady=true
        phoneHotSearch=library.cachedHotSearch()
        phoneHotSearch?.let { cached ->
            if(cached.boards.none { it.name==phoneHotBoard }) phoneHotBoard=cached.boards.firstOrNull()?.name.orEmpty()
        }
    }

    private fun loadHotSearchPhone() {
        if(!phoneLayout() || screen!="catalog" || phoneTab!="search" || phoneQuery.isNotBlank() || phoneHotLoading) return
        prepareHotSearchPhone()
        val ticket=generation
        phoneHotError=""
        work({ repository.hotSearch() }, { result ->
            if(phoneLayout() && ticket==generation && screen=="catalog" && phoneTab=="search" && phoneQuery.isBlank()) {
                phoneHotLoading=false; phoneHotAttempted=true; phoneHotSearch=result
                library.cacheHotSearch(result)
                if(result.boards.none { it.name==phoneHotBoard }) phoneHotBoard=result.boards.firstOrNull()?.name.orEmpty()
                renderPhone()
            }
        }, { problem ->
            if(phoneLayout() && ticket==generation && screen=="catalog" && phoneTab=="search" && phoneQuery.isBlank()) {
                phoneHotLoading=false; phoneHotAttempted=true
                phoneHotError="热搜加载失败："+problem.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(120)+"\n点此重试"
                renderPhone()
            }
        })
        phoneHotLoading=true
        renderPhone()
    }
    fun retryHotSearchPhone()=loadHotSearchPhone()

    /** 「我的」页统计格跳转到收藏页签（switchPhoneTab 是私有的，这里给页面一个公开入口）。 */
    fun openFavoritesPhone() = switchPhoneTab("fav")

    /** 手机收藏分组：collection 视图复用 TV 的 localItems 口径。 */
    fun openCollectionPhone(kind: String) {
        // 记住来源页签：从「我的」打开的合集返回时不该被丢回首页
        phoneCollectionTab=when(phoneTab) {
            "mine" -> "mine"
            "fav" -> "fav"
            else -> "home"
        }
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collection=kind
        phoneDetailCollection=true
        renderPhoneCollection(kind)
    }

    private var phoneDetailCollection=false

    /** 手机版合集页（接着看/稍后看/收藏有更新）：统一使用新版三列网格。 */
    private fun renderPhoneCollection(kind: String) {
        screen="catalog"
        markPageShown()
        // 合集页属于它是从哪个页签打开的，底部导航高亮要跟着走
        val active=phoneCollectionTab
        phoneStatusBarFollowTheme()
        val (frame,content,_)=PortraitUi.scaffold(this,active,::switchPhoneTab)
        val scroll=PortraitListPage(this)
        val body=column()
        scroll.addBlock(body)
        val title=when(kind) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "resume" -> "接着看"; else -> "接着看" }
        val head=PortraitUi.row(this).apply { setPadding(dp(14),dp(8),dp(14),dp(8)) }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_page_back)
            setColorFilter(PortraitStyle.text)
            setPadding(dp(14),dp(14),dp(14),dp(14))
            contentDescription="返回"
            isFocusable=true
            setOnClickListener { closePhoneCollection() }
        },lp(dp(48),dp(48)))
        head.addView(PortraitUi.text(this,title,24f).apply { setTypeface(null,Typeface.BOLD) })
        body.addView(head,lp(-1,-2))
        val items=localItems()
        if(items.isEmpty()) body.addView(PortraitUi.emptyHint(this,"这里暂时没有内容"))
        else {
            val adapter=PortraitUi.PosterGrid(this,::loadCover,{ series, cover -> openDetailPhone(series, cover = cover) },null)
            scroll.addGrid(adapter)
            adapter.submitItems(items,false)
        }
        content.addView(scroll,lp(-1,0).apply { weight=1f })
        root.removeAllViews(); root.addView(frame,android.widget.FrameLayout.LayoutParams(-1,-1))
    }

    /** 关闭手机合集页：恢复来源页签的数据并回渲。 */
    private fun closePhoneCollection() {
        val back=phoneCollectionTab
        phoneDetailCollection=false; leaveCollection()
        // leaveCollection 已把 catalog 还原成进入合集前的数据；回哪个页签由打开时的来源决定。
        phoneTab=back; screen="catalog"
        when {
            back=="home" -> showPhoneHome()
            back=="rank" && (catalog.isEmpty() || phoneCatalogSource!="rank") -> { phoneRankPage=1; renderPhone(); showPhoneRank() }
            else -> renderPhone()
        }
    }

    /** 手机搜索执行。 */
    private fun runPhoneSearch(value: String) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        cancelSearchSuggestions()
        val sameQuery=clean==phoneQuery
        val retained=retainedPhoneCatalogs["search"]
        val previous=if(sameQuery) {
            if(phoneCatalogSource=="search") catalog else retained?.state?.items.orEmpty()
        } else emptyList()
        val previousHasMore=sameQuery && (if(phoneCatalogSource=="search") hasMore else retained?.state?.hasMore==true)
        if(!sameQuery) {
            pendingPhoneScrolls.remove("search"); phoneSessionPositions["search"]=0; phoneScrollY=0
            retained?.scroll?.restorePosition(0)
            phoneSearchPage=1
        }
        // 相同搜索词的刷新沿用视图与结果，加载和失败期间都保留已有封面。
        phoneQuery=clean; phoneSearchDraft=clean; phoneSearchError=""; phoneSearching=true; catalog=previous; hasMore=previousHasMore
        // 从其它页签发起搜索时，先把界面切到搜索页签，否则结果回来也看不见
        if(phoneTab!="search") { phoneTab="search"; phoneScrollY=0 }
        screen="catalog"
        phoneCatalogSource="search"
        library.rememberSearch(clean,library.contentType)
        renderPhone()
        val ticket=generation; val type=library.contentType; val query=clean
        work({ repository.search(query,1,type) }, { result ->
            if(phoneLayout() && ticket==generation) {
                phoneSearching=false
                if(phoneTab=="search" && phoneQuery==query) {
                    catalog=result.items; hasMore=result.hasMore; phoneCatalogSource="search"; phoneSearchPage=1
                    renderPhone()
                    loadSearchArtwork()
                }
            }
        }, { problem ->
            if(phoneLayout() && ticket==generation) {
                phoneSearching=false
                if(phoneTab=="search" && phoneQuery==query) { phoneSearchError="搜索失败："+problem.message.orEmpty().take(120); renderPhone() }
                android.util.Log.w("HongguoTV","Phone search failed: ${problem.message}")
            }
        })
        phoneSearching=true
        renderPhone()
    }

    private fun refreshPortraitFeed() {
        if(!PortraitUi.shouldUse(this) || screen!="catalog") return
        cancelWork(); loadingMore=false
        when(phoneTab) {
            "home" -> showPhoneHome(load=true)
            "rank" -> showPhoneRank()
            "new" -> { prepareNewReleaseCalendarPhone(); loadNewReleasesPhone() }
            "search" -> if(phoneQuery.isNotBlank()) runPhoneSearch(phoneQuery) else loadHotSearchPhone()
            else -> renderPhone()
        }
    }

    fun clearPortraitSearch() {
        if(!PortraitUi.shouldUse(this) || phoneTab!="search") return
        cancelWork(); loadingMore=false
        retainedPhoneCatalogs["search"]?.scroll?.restorePosition(0)
        pendingPhoneScrolls.remove("search"); phoneSessionPositions["search"]=0
        phoneQuery=""; phoneSearchDraft=""; phoneSearchError=""; phoneSearching=false; phoneScrollY=0
        catalog=emptyList(); hasMore=false
        renderPhone()
    }

    fun loadPortraitSuggestions(prefix: String, done: (List<String>) -> Unit) {
        if(!PortraitUi.shouldUse(this)) return
        loadSuggestions(prefix) { words ->
            if(PortraitUi.shouldUse(this) && screen=="catalog" && phoneTab=="search") done(words)
        }
    }

    /** 手机搜索结果翻页页码。 */
    private var phoneSearchPage=1

    /** 推荐与手机排行榜按服务端游标续页；搜索保留自身页码。 */
    fun loadMorePhone() {
        if(phoneTab=="home") {
            if(phoneRecommendationError.isBlank()) loadPhoneRecommendations(more=true)
            return
        }
        if(phoneTab=="rank") {
            if(!phoneRankLoading && rankErrorPhone(mobileRankSelectedKeyPhone).isBlank()) showPhoneRank(more=true)
            return
        }
        if(loadingMore || !hasMore || phoneSearchError.isNotBlank() || screen!="catalog" || phoneDetailCollection || phoneTab!="search") return
        val ticket=generation; val keyword=phoneQuery; val type=library.contentType
        val nextPage=phoneSearchPage+1
        work({ repository.search(keyword,nextPage,type) }, { result ->
            if(phoneLayout() && ticket==generation && screen=="catalog" && phoneTab=="search" &&
                library.contentType==type && phoneQuery==keyword && !phoneDetailCollection) {
                loadingMore=false; phoneSearchPage=nextPage
                catalog=(catalog+result.items).distinctBy { it.id }.take(PortraitUi.PHONE_LIST_LIMIT); hasMore=result.hasMore && result.items.isNotEmpty() && catalog.size<PortraitUi.PHONE_LIST_LIMIT
                renderPhone()
                loadSearchArtwork()
            }
        }, { problem ->
            loadingMore=false
            if(phoneLayout() && ticket==generation && phoneTab=="search" && phoneQuery==keyword && !phoneDetailCollection) {
                if(problem is SearchSessionExpiredException) {
                    runPhoneSearch(keyword)
                    return@work
                }
                phoneSearchError="更多搜索结果加载失败："+problem.message.orEmpty().take(120)
                renderPhone()
            }
            android.util.Log.w("HongguoTV","Phone loadMore failed: ${problem.message}")
        })
        loadingMore=true
    }

    /** 内容类型用于搜索与电视片库；手机推荐始终使用与 iOS 相同的综合推荐频道。 */
    fun switchContentTypePhone(type: com.hongguotv.core.ContentType) {
        if(library.contentType==type) return
        cancelWork(); loadingMore=false; retainedPhoneCatalogs.clear()
        library.contentType=type
        catalog=emptyList(); hasMore=false; phoneScrollY=0
        showPhoneHome(load=true)
    }

    /** 手机详情入口：复用 openDetail 的数据流，统一由 PortraitDetail 渲染。 */
    fun openDetailPhone(series: com.hongguotv.core.Series,resume: Boolean=false,cover: ImageView?=null) {
        offlineOnly = false
        if(!phoneLayout()) { openDetail(series,resume); return }
        phoneCoverTransition?.cancel(); phoneCoverTransition=null
        val sharedCover=if(!resume && cover!=null) PosterSharedTransition.capture(root,cover) else null
        rememberPhoneCatalogPosition()
        if(restoringPhoneDetail?.series?.id!=series.id) restoringPhoneDetail=null
        // 记住从哪个页签进来的：详情返回时回到原处（从排行/收藏点进去不该被丢回首页）。
        // 同剧切季（openDetailPhone 在详情页内再次调用）沿用原来的返回目标。
        if(phoneTab!="detail") {
            phoneDetailReturn=phoneTab
            phoneCoverReturnName=cover?.let { it.transitionName ?: PosterSharedTransition.posterName(series.id) }
            phoneCoverReturnScrollState=if(cover!=null) retainedPhoneCatalogs[phoneTab]?.scroll?.captureLayoutState() else null
        }
        favoriteMonitor.stop()
        pendingDetail=series; detailAutoplay=true
        invalidatePage(); releaseCatalogViews(); screen="detail"; detail=null; synopsisExpanded=false; phoneTab="detail"
        markPageShown()
        sharedCover?.let { phoneCoverTransition=PosterSharedTransition(root,it) }
        portraitDetail.renderState(root,"正在加载剧集…",series=series)
        val scheduled=scheduledSeries(series)
        val apply: (Detail) -> Unit = { loaded ->
            val result=withCachedSeasons(withReleaseSchedule(loaded,scheduled))
            // done lambda 同样是非内联的，不能 return：用 if/else 表达「已转回横屏就交给 TV 版」
            if(!phoneLayout()) {
                openDetail(series,resume,preloaded=result)
            } else {
                detail=result
                val restored=restoringPhoneDetail?.takeIf { it.series?.id==result.series.id }
                if(restored!=null) {
                    restoringPhoneDetail=null
                    if(restored.screen=="player" && result.episodes.isNotEmpty()) {
                        val savedIndex=result.episodes.indexOf(restored.episodeId)
                        val index=if(savedIndex>=0) savedIndex else restored.episodeIndex.coerceIn(0,result.episodes.lastIndex)
                        playEpisodePhone(index,restored.position,autoplay=false)
                    } else {
                        renderPhoneDetail(result)
                        portraitDetail.restoreScrollY(restored.detailScroll)
                        if(restored.screen=="settings") {
                            showPhoneSettings()
                            phoneSettingsDetailScroll=restored.detailScroll
                        }
                    }
                } else if(result.episodes.isEmpty()) {
                    episodeIndex=0; group=0
                    renderPhoneDetail(result)
                } else {
                    library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
                    val progress=library.progress(series.id)
                    val target=ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
                    episodeIndex=target.index; group=episodeIndex/20
                    if(resume) playEpisodePhone(target.index,target.position)
                    else {
                        renderPhoneDetail(result)
                        prefetchDetailWindow(target.index)
                    }
                }
            }
        }
        val cached=repository.cachedDetail(series.id)
        if(cached?.episodes?.isNotEmpty()==true) { apply(cached); return }
        // 预计时间到达后重新取分集；只有日期、尚未定档或取详情失败时仍保留排期。
        if(scheduled?.release?.isPending()==true) { apply(Detail(scheduled,emptyList())); return }
        cached?.let(apply) ?: work({ repository.seriesDetail(series.id) }, apply, { problem ->
            if(scheduled!=null) apply(Detail(scheduled,emptyList()))
            else if(phoneLayout()) {
                portraitDetail.renderState(root,problem.message ?: "加载失败",series=series,retry={ openDetailPhone(series,resume) })
            }
        })
    }

    /** 同剧切季保留页面外壳，只在主详情返回后更新已有控件。 */
    fun switchDetailSeasonPhone(series: Series) {
        val previous = detail
        if (!phoneLayout() || screen != "detail" || phoneTab != "detail" || previous == null || !portraitDetail.hasPage(root)) {
            openDetailPhone(series)
            return
        }
        if (series.id == previous.series.id && pendingDetail?.id == series.id) return
        favoriteMonitor.stop()
        // 取消旧季的数据请求，保留海报绑定，避免整页失效造成封面闪白。
        generation++; cancelWork(); cancelDetailExtras()
        pendingDetail = series
        portraitDetail.setSeasonLoading(root, series.id)
        val scheduled = scheduledSeries(series)
        val apply: (Detail) -> Unit = { loaded ->
            val result = withCachedSeasons(withReleaseSchedule(loaded, scheduled))
            if (!phoneLayout()) {
                openDetail(series, preloaded = result)
            } else if (screen == "detail" && phoneTab == "detail") {
                detail = result
                if (result.episodes.isNotEmpty()) {
                    library.observeFavorite(series.id, result.episodes.size, System.currentTimeMillis(), acknowledge = true)
                    val progress = library.progress(series.id)
                    val target = ResumePlayback.target(result.episodes, progress?.episodeId, progress?.position ?: 0, progress?.completed == true)
                    episodeIndex = target.index
                } else episodeIndex = 0
                group = episodeIndex / 20
                if (!portraitDetail.updateSeason(root, result)) renderPhoneDetail(result)
                if (result.episodes.isNotEmpty()) prefetchDetailWindow(episodeIndex)
            }
        }
        val cached = repository.cachedDetail(series.id)
        if (cached?.episodes?.isNotEmpty() == true) { apply(cached); return }
        if (scheduled?.release?.isPending() == true) { apply(Detail(scheduled, emptyList())); return }
        cached?.let(apply) ?: work({ repository.seriesDetail(series.id) }, apply, {
            if (scheduled != null) apply(Detail(scheduled, emptyList()))
            else if (phoneLayout() && screen == "detail" && phoneTab == "detail") {
                pendingDetail = previous.series
                portraitDetail.setSeasonLoading(root, null)
                PhoneUi.toast(this, "切换失败，请重试")
            }
        })
    }

    /** 手机播放入口：复用 playEpisode 全部播放管线，播放界面换手机版。 */
    fun playEpisodePhone(index: Int,position: Long=0,autoplay: Boolean=true) {
        phoneCoverTransition?.cancel(); phoneCoverTransition=null
        playEpisode(index,position,autoplay)
    }

    /** 手机详情页返回：回到进详情前的那个页签。 */
    fun goBackPhone() {
        val returnName=phoneCoverReturnName
        val returnScrollState=phoneCoverReturnScrollState
        val poster=if(returnName!=null) portraitDetail.sharedPoster() else null
        val sharedCover=if(phoneLayout() && screen=="detail") {
            phoneCoverTransition?.captureForReturn(poster) ?: PosterSharedTransition.capture(root,poster)
        } else null
        // 返回时停止详情请求，防止加载回调再次覆盖正在恢复的列表。
        invalidatePage()
        phoneCoverReturnName=null; phoneCoverReturnScrollState=null
        offlineOnly = false
        val back=phoneDetailReturn
        restoringPhoneDetail=null
        phoneTab=back; phoneDetailCollection=false; leaveCollection(); screen="catalog"
        phoneScrollY=phoneSessionPositions[back] ?: 0
        cacheStripSegments=null; cacheStripView=null
        when {
            // 只有原始列表已经不在时才重新拉，否则直接退回原列表（保留滚动位置）
            back=="home" -> showPhoneHome()
            back=="rank" && (catalog.isEmpty() || phoneCatalogSource!="rank") -> {
                phoneRankPage=1; renderPhone(); showPhoneRank()
            }
            back=="search" && phoneQuery.isNotBlank() && (phoneCatalogSource!="search" || catalog.isEmpty()) -> runPhoneSearch(phoneQuery)
            // 从搜索结果点进详情：返回搜索页时结果还在，直接重画即可
            else -> renderPhone()
        }
        returnScrollState?.let { retainedPhoneCatalogs[back]?.scroll?.restoreLayoutState(it) }
        sharedCover?.let { snapshot ->
            val transition=PosterSharedTransition(root,snapshot)
            phoneCoverTransition=transition
            transition.returnTo(returnName) {
                val retained=retainedPhoneCatalogs[back]
                back !in pendingPhoneScrolls && retained!=null && !retained.frame.isLayoutRequested &&
                    retained.scroll?.isRestoringPosition!=true && retained.pager?.isRestoringPosition!=true
            }
        }
    }

    /** 手机播放页返回详情。 */
    fun returnToDetailPhone() {
        returnToDetail(true)
    }

    /** 手机播放控制桥接：由 PhonePlayer 调用，复用既有播放操作。 */
    fun togglePlaybackPhone()=togglePlayback()
    fun seekToPhone(position: Long)=seekTo(position)
    fun skipEpisodePhone(direction: Int): Boolean = skipEpisode(direction).also { if(!it) PhoneUi.toast(this,"没有更多集数") }
    fun showPhoneEpisodePicker() { if(detail!=null) showEpisodePanel(pickerAnchor) }

    /** 手机竖屏选集：底部数字宫格弹层（对齐第三方参考实现），选中后直接切集。 */
    private fun showPhoneEpisodeSheet(data: com.hongguotv.core.Detail) {
        // 已开着就先收掉旧的，避免叠两层
        if(phoneEpisodeSheet!=null) { phoneEpisodeSheet?.dismiss(); phoneEpisodeSheet=null }
        val playerUi=phonePlayer
        var sheet: PhoneEpisodeSheet?=null
        sheet=PhoneEpisodeSheet(this,data,episodeIndex,{ selected ->
            // 弹层已经先关掉了；回调里只负责切集
            if(selected!=episodeIndex) playEpisode(selected,savedPosition(selected))
        },{
            // 只清当前这一个实例的引用：用户重新打开后会换成新实例
            if(phoneEpisodeSheet===sheet) {
                phoneEpisodeSheet=null
                playerUi?.setEpisodeSheetTop(null)
            }
        },previewAspectRatio=playerUi?.episodePreviewAspectRatio(),onPanelTopChanged={ top ->
            if(phoneEpisodeSheet===sheet) playerUi?.setEpisodeSheetTop(top)
        },onAnimationProgress={ progress ->
            if(phoneEpisodeSheet===sheet) playerUi?.setEpisodeSheetProgress(progress)
        },availableEpisodes=if (offlineOnly) data.episodes.indices.filter { offlineDownloads.lookup(data.episodes[it]) != null }.toSet() else null)
        phoneEpisodeSheet=sheet
        sheet.show()
    }
    private var phoneEpisodeSheet: PhoneEpisodeSheet?=null
    private fun updatePhoneVideoPosition(size: VideoSize) {
        phonePlayer?.updateVideoPosition(size)
        phoneEpisodeSheet?.updatePreviewAspectRatio(phonePlayer?.episodePreviewAspectRatio())
    }
    fun showPhoneSpeedPicker() { showSpeedPicker(pickerAnchor,true) }
    fun showPhoneQualityPicker() { showQualityPicker(pickerAnchor,true) }

    /** 手机播放页「重新加载」：从当前位置重取地址重播。 */
    fun retryPlaybackPhone() {
        if(screen!="player") return
        playEpisode(episodeIndex,currentPosition(),true)
    }

    /** 手机播放页「更多」菜单：弹幕开启后在开关下方显示全局弹幕设置。 */
    fun showPhoneMoreMenu(anchor: View?) {
        val options=buildList<Pair<String,()->Unit>> {
            add("选集" to { showPhoneEpisodePicker() })
            add("清晰度  ·  ${effectiveQuality()}P" to { showPhoneQualityPicker() })
            add("画面  ·  ${effectiveFrame().label}" to { showFramePicker(pickerAnchor,true) })
            add("弹幕  ·  ${if(library.danmaku) "开" else "关"}" to {
                toggleDanmaku()
                showPhoneMoreMenu(anchor)
            })
            if(library.danmaku) add("弹幕设置" to { showDanmakuPrefsDialog(anchor) })
            add("重新加载" to { retryPlaybackPhone() })
            add("播放设置" to { showPhoneSettings() })
            add("退出播放" to { returnToDetailPhone() })
        }
        tvTools.choose("更多播放操作",options.map { it.first },anchor,{ choice ->
            main.post {
                if(screen!="player" || anchor?.isAttachedToWindow==false) return@post
                options.getOrNull(choice)?.second?.invoke()
            }
        })
    }

    /** 手机状态栏图标外观：浅色顶栏要配深色图标，否则暖玉白主题下白色图标看不清。 */
    fun phoneStatusBarLight(light: Boolean) {
        WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars=light
    }

    /**
     * 手机横竖屏切换时同步系统栏：横屏走电视版界面，维持沉浸全屏；
     * 竖屏回手机页面恢复常规系统栏（内容由 PhoneInsets 让位）。全屏播放的
     * 系统栏由 enter/exitFullscreenPhone 专管，这里不能抢。
     */
    private fun applyPhoneSystemBars() {
        if(!TouchControls.isHandheld(this)) return
        if(screen=="player" && phonePlayer?.isFullscreen()==true) return
        val controller=WindowCompat.getInsetsController(window,window.decorView)
        if(phoneLayout()) {
            controller.show(WindowInsetsCompat.Type.systemBars())
            phoneStatusBarFollowTheme()
        } else {
            controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 手机内容与设置跟随系统配色，播放器仍沿用原有主题策略。 */
    fun phoneStatusBarFollowTheme()=phoneStatusBarLight(
        if(PortraitUi.shouldUse(this) && screen!="player") !PortraitStyle.isDark
        else TvStyle.theme==TvStyle.Theme.PORCELAIN)

    /**
     * 进入手机全屏播放：隐藏系统栏并锁成横屏。
     *
     * 强制竖屏与跟随系统都转横屏（「强制竖屏」是对竖屏界面的偏好，不是禁止全屏，
     * 否则全屏按钮点了没反应）；皮肤由 [phoneFullscreenPlayback] + [phonePlayerLayout]
     * 决定 —— 手机上不会切成电视播放器，而是 [PhonePlayer] 的横屏全屏那套。
     */
    fun enterFullscreenPhone() {
        // 标记必须先于 requestOrientation 置位：方向回调/重建时就要按全屏皮肤渲染。
        // 只有手机播放页会调到这里（电视播放器没有这个按钮），所以不再按偏好二次判断。
        if(screen=="player") phoneFullscreenPlayback=true
        requestedOrientation = when(PhoneUi.orientationMode(this)) {
            // 强制横屏：手机本来就走电视播放器，理论上到不了这里，保持横屏
            OrientationMode.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            // 强制竖屏 / 跟随系统：全屏都锁横屏（SENSOR_LANDSCAPE 允许横屏两个方向，
            // 用户把手机掉个头也能正常看；固定 LANDSCAPE 会卡在一个方向）
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        // Android 15 起老的 systemUiVisibility 全屏标志被忽略，必须走 InsetsController；
        // 滑动临时呼出对应原 IMMERSIVE_STICKY 行为
        val controller=WindowCompat.getInsetsController(window,window.decorView)
        controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        phoneStatusBarLight(false)
    }

    /**
     * 退出手机全屏：转回竖屏并恢复常规系统栏，皮肤也回到竖屏那套。
     *
     * [rebuild] 为 false 时只恢复系统栏、不重挂播放页——离开播放页（release）
     * 时用，否则会在刚拆掉的页面上又拉一次 playEpisode。
     * 方向则只有在 [restoreOrientation] 为 true 时才交还：旋转重建同样会走 release，
     * 那次绝不能把「全屏会话」的方向改回竖屏。
     */
    fun exitFullscreenPhone(rebuild: Boolean = true, restoreOrientation: Boolean = true) {
        val wasFullscreenSkin=phoneFullscreenPlayback
        // 只有真正的「退出全屏 / 离开播放页」才结束会话；旋转重建（restoreOrientation=false）
        // 必须保留会话标记，否则横屏外壳刚挂上就会被判定成非全屏
        if(restoreOrientation) phoneFullscreenPlayback=false
        if(restoreOrientation) {
            val mode=PhoneUi.orientationMode(this)
            if(mode==OrientationMode.AUTO) {
                // 跟随系统：先锁一次竖屏把界面掰回来，转屏动画结束后再交还传感器。
                // 设备此刻还横着时只设 UNSPECIFIED 系统不会转，退出全屏会卡在横屏皮肤上
                // （第三方参考实现同样在退出时主动锁回进入前的方向）。postDelayed 是给转屏动画留时间。
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                // 正在退出 App 时不要再排这个回调：窗口都拆了，交还方向也没有意义
                if(!isFinishing && !isDestroyed) main.postDelayed({
                    if(!isFinishing && !isDestroyed) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }, ORIENTATION_RESTORE_DELAY_MS)
            } else {
                // 强制横屏 / 强制竖屏：直接按偏好锁死
                requestedOrientation = when(mode) {
                    OrientationMode.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
        WindowCompat.getInsetsController(window,window.decorView).show(WindowInsetsCompat.Type.systemBars())
        phoneStatusBarFollowTheme()
        // 只要这次真的结束了全屏会话，就必须用竖屏皮肤重挂：设备本来就竖着时不会触发
        // 方向回调，光清标记会让界面一直停在横屏皮肤上
        if(rebuild && wasFullscreenSkin && screen=="player") rebuildPhonePlayerForLayout()
    }

    /** PhonePlayer 内部状态变化后立即刷新一帧。 */
    fun refreshPhonePlayerNow() { if(screen=="player" && phonePlayerLayout()) refreshPhonePlayer() }

    /** 当前外壳是不是已经长成手机横屏全屏皮肤（方向回调没来时用来判断要不要补一次重建）。 */
    fun phonePlayerIsFullscreenSkin() = phonePlayerRenderedFullscreen

    /**
     * 按当前全屏标记与方向重挂播放页外壳。设备已经横着时点全屏不会触发
     * onConfigurationChanged，需要调用方主动走一次和方向变化完全一致的路径。
     */
    fun rebuildPhonePlayerForLayout() {
        if(screen!="player") return
        val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle
        phonePlayer=null; playerView=null
        playEpisode(episodeIndex,position,autoplay)
    }
    /**
     * PhonePlayer 切换全屏后的收尾：全屏状态变了、界面可能刚换了皮肤，
     * 立刻按新状态刷一次进度与控件，避免要等下一秒 tick 才更新。
     */
    fun applyPhoneFullscreen(fullscreen: Boolean) {
        if(screen!="player") return
        applyPhoneSystemBars()
        if(phonePlayerLayout()) refreshPhonePlayer()
        else if(hud.visibility==View.VISIBLE) updatePlaybackText()
    }
    private fun refreshPhonePlayer() {
        val ui=phonePlayer ?: return
        val p=player
        val failure=phoneFailureText()
        val playing=p?.isPlaying==true
        val buffering=(p==null || p.playbackState==Player.STATE_BUFFERING || p.playbackState==Player.STATE_IDLE || !playbackReady) && failure==null
        val duration=p?.duration?.coerceAtLeast(0) ?: 0L
        val position=pendingSeek ?: p?.currentPosition ?: 0L
        ui.refresh(
            playing=playing,
            position=position,
            duration=duration,
            bufferingNow=buffering,
            error=failure,
            speed=com.hongguotv.core.PlaybackSpeed.label(p?.playbackParameters?.speed ?: effectiveSpeed()))
    }

    /** 手机播放页的失败/恢复提示；一切正常返回 null。 */
    private fun phoneFailureText(): String? {
        if(!playError) return null
        // playbackText 只在真正进入播放页后才初始化；退出后再 tick 一次不能崩
        val detailText=if(::playbackText.isInitialized) playbackText.text.toString() else ""
        return detailText.takeIf { it.isNotBlank() && !it.startsWith("正在获取播放地址") }
            ?: "播放失败，可重试或切换清晰度"
    }

    /** 手机设置入口：竖屏下打开一整套纵向设置页（[PhoneSettings]），
     * 不再像以前那样只给七项、把其余设置推给「旋转到横屏」。
     * 目录页与播放页「更多」都走这里，保证同一个入口看到同一份设置。
     */
    fun showPhoneSettings() {
        rememberPhoneCatalogPosition()
        if(screen=="detail") phoneSettingsDetailScroll=portraitDetail.captureScrollY()
        // 播放中点进来时记下进度：退出设置页要回到播放画面，不能把播放丢掉
        if(screen=="player") {
            phoneSettingsFromPlayer=true
            phoneSettingsPosition=currentPosition()
            phoneSettingsAutoplay=player?.playWhenReady==true
            // 全屏标记先取走再退出全屏：留着会让方向/皮肤判定在设置页里仍按全屏处理，
            // 退出设置时再用记下的值恢复
            phoneSettingsFullscreen=phonePlayer?.takeFullscreenState()==true
            // 设置页是竖屏单列，全屏会话在这里先结束；退出设置页再按记下的值重建
            phoneFullscreenPlayback=false
            // 和设置页转横屏再转回来一样：先收掉播放浮层，否则「退出播放」回详情后
            // 焦点可能落在已被移除的控件上；播放本身不停，退出设置时按记录的进度重挂
            hidePlaybackOverlay()
        } else {
            phoneSettingsFromPlayer=false
            phoneSettingsFullscreen=false
        }
        // 设置页自己是一整屏，先把可能开着的弹窗收掉，避免它盖在新页面上
        tvTools.close()
        screen="settings"
        markPageShown()
        cacheStripSegments=null; cacheStripView=null
        phoneSettings.render(root)
        if(phoneSettingsFromPlayer) {
            // 设置页是竖屏单列：先转回竖屏并恢复系统栏。screen 已改成 settings，
            // 后续的 onConfigurationChanged 会留在设置页，而不是把播放器重新拉起来
            requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            val controller=WindowCompat.getInsetsController(window,window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            phoneStatusBarFollowTheme()
        }
    }

    /** 退出手机设置页：回到进来时的页面（播放页要重新挂回播放器，进度接着走）。 */
    fun closePhoneSettings() {
        if(screen!="settings") return
        val fromPlayer=phoneSettingsFromPlayer
        val position=phoneSettingsPosition
        val autoplay=phoneSettingsAutoplay
        val fullscreen=phoneSettingsFullscreen
        // 立刻复位标记：下面调用的 playEpisode → releasePlayer → phonePlayer.release() 里
        // 会按旧的全屏状态收尾；标记留在 true 会让下一次返回误判
        phoneSettingsFromPlayer=false; phoneSettingsFullscreen=false
        if(fromPlayer && detail!=null) {
            // 与横竖屏重建同一条路：先拆掉旧外壳，再按记录的进度重新挂载，播放不断流
            phonePlayer=null; playerView=null
            screen="player"
            // 设置页是全屏横屏时进来的，回播放页仍是全屏皮肤，标记要先于方向设置复位
            phoneFullscreenPlayback=fullscreen
            // 进设置页时为了竖屏单列临时锁了竖屏，回播放页要交还「启动方向」偏好
            requestedOrientation=if(fullscreen) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else PhoneUi.requestedOrientation(this)
            playEpisode(episodeIndex,position,autoplay)
            if(fullscreen) {
                // 设置页显示过系统栏，回全屏要重新隐藏并锁回全屏方向
                phonePlayer?.restoreFullscreenState(true)
                enterFullscreenPhone()
            }
            return
        }
        screen="catalog"
        when {
            phoneTab=="detail" && detail!=null -> { screen="detail"; renderPhoneDetail(detail!!); portraitDetail.restoreScrollY(phoneSettingsDetailScroll) }
            phoneLayout() -> renderPhone()
            // 横屏下没有手机设置页：落到电视版设置页签，用户不会「转完就找不到设置」
            else -> { tab=5; showCatalog() }
        }
    }

    // ---- 手机设置页（PhoneSettings）用的桥接：复用既有的弹窗、开关与恢复逻辑 ----

    /** 手机设置页整页重画（只在行结构本身变化时才用，例如切主题、登录状态变化）。 */
    fun refreshPhoneSettings() { if(screen=="settings") phoneSettings.render(root) }
    /** 只刷新各行右侧的取值（不重建页面，滚动位置与焦点都保留）。 */
    fun phoneRefreshSettingsValues() { if(screen=="settings") phoneSettings.refreshValues() }

    /** 手机外观独立保存，立即重画设置页并淘汰旧配色的竖屏内容缓存。 */
    fun phoneSetAppearance(appearance: PortraitStyle.Appearance) {
        if(screen!="settings" || !phoneLayout()) return
        PortraitStyle.setAppearance(this,appearance)
        retainedPhoneCatalogs.clear()
        refreshPhoneSettings()
    }

    val phoneSigningModeLabel get() = SigningSettings.algorithmLabel
    fun phoneSigningModePicker() = showSigningModePicker(pickerAnchor)

    private fun showSigningModePicker(anchor: View?) {
        val modes=SigningSettings.modes
        val selected=modes.indexOf(SigningSettings.mode).coerceAtLeast(0)
        val labels=modes.mapIndexed { index, mode ->
            mode.label+if(index==selected) "   ·   当前" else ""
        }
        tvTools.choose("算法模式",labels,anchor,{ choice ->
            modes.getOrNull(choice)?.let(::setSigningMode)
        },focusIndex=selected)
    }

    private fun setSigningMode(mode: SigningSettings.Mode) {
        if(mode==SigningSettings.mode) return
        SigningSettings.mode=mode
        // 当前 Activity 立即换用新签名器；重建让内容、弹幕、云账号也同步到新身份。
        runCatching { playbackAlgorithms.select(SigningSettings.toPlaybackAlgorithm(mode)) }
        rebuildPlaybackCacheForAlgorithm()
        if(screen=="settings") PageRestore.phoneSettings=true
        recreateKeepingPage("算法模式")
    }

    /** 自动切换由播放线程触发；设置、缓存与提示都回主线程。 */
    private fun applyPlaybackAlgorithm(change: PlaybackAlgorithmChange) {
        main.post {
            SigningSettings.mode = SigningSettings.modeOf(change.to)
            rebuildPlaybackCacheForAlgorithm()
            if(!isFinishing && !isDestroyed && change.reason != "用户选择") {
                Toast.makeText(this, "已自动切换到" + SigningSettings.algorithmLabel, Toast.LENGTH_SHORT).show()
                RuntimeLog.add("算法自动切换 " + (change.from?.name ?: "未知") + " -> " + change.to.name + ": " + change.reason)
                if(screen=="settings") phoneRefreshSettingsValues()
            }
        }
    }

    fun phoneOrientationPicker() = showOrientationPicker(pickerAnchor)
    fun phoneQualityPicker() = showQualityPicker(pickerAnchor,false)
    fun phoneSpeedPicker() = showSpeedPicker(pickerAnchor,false)
    fun phoneFramePicker() = showFramePicker(pickerAnchor,false)
    fun phoneWatchStats() = showWatchStats(pickerAnchor)
    fun phoneInterfaceCheck() = showInterfaceCheck(pickerAnchor)
    fun phoneRuntimeLogs() = RuntimeLogDialog.show(this, ::runtimeLogSummary)
    fun phoneCheckUpdate() = checkAppUpdate(manual = true, anchor = pickerAnchor)

    /** 复制到日志里的现场状态：排查「三条进度条全灰」时不依赖日志有没有打到。 */
    private fun runtimeLogSummary(): String = buildString {
        val data = detail
        val cache = episodeDiskCache
        appendLine("页面: $screen / 页签 $phoneTab" + if (data != null) " · 第 ${episodeIndex + 1}/${data.episodes.size} 集" else "")
        appendLine("推荐: ${phoneRecommendationItems.size} 条 · 可见 ${phoneRecommendationItems.count { it.id !in library.hidden() }} 条" +
            " · 已请求 $phoneRecommendationStarted · 加载中 $phoneHomeLoading · 可翻页 ${phoneRecommendationCursor!=null}")
        appendLine("推荐接口: $phoneRecommendationDiagnostic")
        if(phoneRecommendationError.isNotBlank()) appendLine("推荐错误: ${phoneRecommendationError.lineSequence().firstOrNull().orEmpty()}")
        appendLine("缓存后三集: " + when {
            !library.episodeCache -> "开关关闭"
            cache == null -> "已开启但缓存池未创建（设置里开关一次即可重建）"
            else -> "运行中 · 画质 ${effectiveQuality()}P · 队列 ${cache.queuedCount} 条"
        })
        appendLine("网络: " + when {
            !networkAvailable() -> "无连接"
            connectivity.isActiveNetworkMetered -> "移动网络（同样缓存）"
            else -> "可用"
        } + " · 内存预载" + if (prefetchMemoryReady()) "正常" else "已暂停")
        if (cache != null) {
            appendLine("调度阻塞: " + lastIdleForSummary.ifBlank { "无（调度正常）" })
            appendLine("跳过原因: " + cache.skipReason.ifBlank { "无（调度正常）" })
            if (data != null) {
                val quality = effectiveQuality()
                // 三根进度条对应的就是下 1/2/3 集：把比例与是否已完整落盘一并写出来
                (1..3).mapNotNull { offset ->
                    val id = data.episodes.getOrNull(episodeIndex + offset) ?: return@mapNotNull null
                    val ratio = cache.progress(id, quality)
                    "  下 $offset 集: ${(ratio * 100).toInt()}%" + if (cache.meta(id, quality) != null) "（已完整缓存）" else ""
                }.forEach(::appendLine)
            }
        }
    }
    fun phoneCrashLogs() {
        io.execute {
            val records=CrashReporter.reports(this)
            main.post {
                if(isDestroyed) return@post
                if(records.isEmpty()) PhoneUi.toast(this,"应用启动以来未发生崩溃") else CrashDialog.showList(this@MainActivity,records)
            }
        }
    }

    /** 缓存开关变更后立即更新播放缓存，取值由 Library 保存。 */
    fun phoneApplyEpisodeCache() = applyEpisodeCacheSetting()
    val phoneAutoSkip get() = extras.autoSkipUnplayable
    fun phoneSetAutoSkip(value: Boolean) { extras.autoSkipUnplayable = value }

    /** 手机设置页切主题：手机页签原地重画即可，不必 recreate 整个 Activity。 */
    fun phoneThemeToggle() {
        library.theme = TvStyle.toggle().name
        refreshPhoneSettings()
    }

    /** 界面缩放会改 density，只能 recreate；重建后按标记回到手机设置页。 */
    fun phoneUiScalePicker() {
        val current = UiScale.index(this)
        tvTools.choose("界面缩放", UiScale.LEVELS.mapIndexed { index, level ->
            level.first + (if(index==current) "   ·   当前" else "")
        }, pickerAnchor, { choice ->
            if(choice != current) {
                UiScale.set(this, choice)
                PageRestore.phoneSettings = true
                recreate()
            }
        }, focusIndex = current)
    }

    fun phoneUnhideAll() {
        library.unhideAll()
        PhoneUi.toast(this, "热门推荐已恢复")
        refreshPhoneSettings()
    }

    fun phoneClearCache() {
        val dirs = listOfNotNull(cacheDir, externalCacheDir)
        TvDialog.confirm(this, "清理缓存", "清除封面图片和已下载的安装包等缓存数据，不影响收藏和观看记录。") {
            retainedPhoneCatalogs.clear()
            artwork.clearPage(clearMemory=true)
            io.execute {
                Glide.get(this).clearDiskCache()
                dirs.forEach { dir -> dir.listFiles()?.forEach { it.deleteRecursively() } }
                main.post { if(!isDestroyed) PhoneUi.toast(this, "缓存已清理") }
            }
        }.show()
    }

    /**
     * 启动方向选择：跟随系统 / 强制横屏 / 强制竖屏。
     * 选定后立即生效——锁定 Activity 朝向，再按新方向重建当前页面（手机竖屏页与电视页是两套皮肤）。
     */
    private fun showOrientationPicker(anchor: android.view.View?) {
        val current=OrientationMode.of(library.orientationMode)
        tvTools.choose("启动方向",OrientationMode.entries.map { mode ->
            mode.label+(if(mode==current) "   ·   当前" else "")
        },anchor,{ choice ->
            val next=OrientationMode.entries.getOrNull(choice) ?: return@choose
            if(next!=current) {
                library.orientationMode=next.name
                // 改启动方向即退出手机全屏会话：全屏皮肤只属于「竖屏界面点全屏」这一种情况，
                // 这时候强切到横向的电视界面（或强制竖屏直接回竖屏播放页）都不该再保留它
                phoneFullscreenPlayback=false
                // 锁朝向：系统会按需要回调 onConfigurationChanged，由那里重建对应皮肤
                requestedOrientation=PhoneUi.requestedOrientation(this)
                // 朝向没真的变化时不会触发重建（比如已经横着的设备选「强制横屏」），这里主动刷新一次；
                // 电视设置页还要把焦点送回「启动方向」这一行。
                // 播放页正开着时不能重画目录：方向变化由 onConfigurationChanged 重建播放外壳，
                // 全屏与否也由 enter/exitFullscreenPhone 负责。
                when {
                    // 手机设置页：方向真变了由 onConfigurationChanged 重建皮肤；
                    // 没变就把「启动方向」这一行的值就地刷出来
                    screen=="settings" -> phoneRefreshSettingsValues()
                    // 播放页：方向真变了走 onConfigurationChanged 重建外壳；方向没变
                    // （常见于强制竖屏，刚才全屏锁的就是横屏，切到强制竖屏才真转）时
                    // 这里主动按新偏好重挂一次，否则横屏全屏皮肤会一直留着
                    screen=="player" -> rebuildPhonePlayerForLayout()
                    phoneLayout() -> if(phoneTab!="detail") renderPhone() else detail?.series?.let { openDetailPhone(it) }
                    screen=="catalog" -> { settingsFocusKey="启动方向"; showCatalog() }
                }
                applyPhoneSystemBars()
            }
        },focusIndex=OrientationMode.entries.indexOf(current))
    }

    /** 手机页签重渲染入口（收藏分组筛选变化时用）。只在目录页生效，
     *  详情/播放页有各自的渲染入口，不能被这里覆盖掉。 */
    fun renderPhoneTab() { if(phoneLayout() && screen=="catalog" && !phoneDetailCollection) renderPhone() }

    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        // 手机设置页：先退回进来时的页面（播放页会重新挂回播放器接着放）
        if(screen=="settings") { closePhoneSettings(); return }
        // 手机竖屏页面：按手机导航栈退出（合集→来源页、详情→来源页、搜索结果→历史）
        if(phoneLayout() && screen!="player") {
            if(phoneDetailCollection) { closePhoneCollection(); return }
            when(phoneTab) {
                "detail" -> { goBackPhone(); return }
                // 手机没有遥控器：搜索结果页的返回先退到搜索页签的初始态，
                // 再按一次才走退出确认，和用户的预期一致。
                "search" -> {
                    if(phoneQuery.isNotBlank()) {
                        clearPortraitSearch()
                        return
                    }
                    switchPhoneTab("home"); return
                }
                "home","rank","new","fav","mine" -> { showExitConfirm(); return }
                else -> {}
            }
        }
        when(screen) {
            // 手机全屏播放：返回键先退出全屏回到竖屏播放页（与第三方参考实现相同），
            // 再按一次才退出播放。电视播放器那套仍按原逻辑走。
            "player" -> when {
                phonePlayer?.isFullscreen()==true -> phonePlayer?.exitFullscreenFromBack()
                phonePlayerLayout() -> returnToDetail()
                episodePanel!=null -> episodePanel?.dismiss()
                !playError && (hud.visibility==View.VISIBLE || controls.visibility==View.VISIBLE) -> hidePlaybackOverlay()
                else -> returnToDetail()
            }
            "detail" -> showCatalog()
            else -> if(tab==1 && advancedSearch) { switchAdvanced(false); filterToggle?.post { filterToggle?.requestFocus() } } else if(tab==3 && favoriteManage) { favoriteManage=false; favoriteSelected.clear(); showCatalog() } else if(tab==2 && newRelease) { newRelease=false; page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; discoveryCursor=null; discoverySelection=DiscoverySelection(); tabState.remove(2); tabFocus.remove(2); pendingNewReleaseFocus="back"; showCatalog(true) } else if(tab==2 && aiCatalog) { aiCatalog=false; aiGenre=null; page=1; catalogFocus=""; tabState.remove(2); tabFocus.remove(2); showCatalog(true); aiEntry?.post { if(aiEntry?.isAttachedToWindow==true) aiEntry?.requestFocus() } } else if(collection!=null) { leaveCollection(); showCatalog() } else if(nav.any { it.hasFocus() }) { if(tab!=0) { switchTab(0); nav[0].requestFocus() } else showExitConfirm() } else if(nav.getOrNull(TvCatalogHeader.normalize(tab))?.requestFocus()!=true) showExitConfirm()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed() { if(activityInitialized) goBack() else finish() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(!activityInitialized || !library.isLoaded) return super.dispatchKeyEvent(event)
        // 遥控一按就把鼠标专属的东西收起来：最近一次操作已经换人了
        if(mouseSeen && screen=="player") { mouseSeen=false; refreshPointerUi() }
        if(handleCatalogDown(event)) return true
        if(handleCatalogUp(event)) return true
        // 收藏页：菜单键循环切换排序（卡片上的快捷菜单改用长按确认键，与设计稿按键表一致）
        if(screen=="catalog" && tab==3 && collection==null && event.keyCode==KeyEvent.KEYCODE_MENU) {
            if(event.action==KeyEvent.ACTION_UP && !favoriteManage) {
                val order=listOf("update","watch","name")
                library.favoriteSort=order[(order.indexOf(library.favoriteSort)+1)%order.size]
                showCatalog()
                favoriteSortButtons.getOrNull(order.indexOf(library.favoriteSort))?.let { btn -> btn.post { if(btn.isAttachedToWindow) btn.requestFocus() } }
            }
            return true
        }
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            // TV 菜单键固定打开/收起底部操作菜单，不受旧版自定义菜单键设置影响。
            if(key==KeyEvent.KEYCODE_MENU && !phonePlayerLayout()) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 && !playError) {
                    if(panel) hidePlaybackOverlay() else showPanel()
                }
                return true
            }
            // 手机沿用原有短按自定义行为与长按快捷面板。
            if(key==KeyEvent.KEYCODE_MENU) {
                if(event.action==KeyEvent.ACTION_DOWN) {
                    if(event.repeatCount==0 && !playError) { menuLongFired=false; main.postDelayed(menuLongRunnable,500) }
                } else if(event.action==KeyEvent.ACTION_UP) {
                    main.removeCallbacks(menuLongRunnable)
                    if(menuLongFired) { menuLongFired=false; return true }
                    if(playError) return true
                    if(panel) { hidePlaybackOverlay(); return true }
                    when(extras.menuKeyAction) {
                        PlaybackExtras.MENU_EPISODES -> showEpisodePanel(hud)
                        PlaybackExtras.MENU_DANMAKU -> toggleDanmaku()
                        else -> showPanel()
                    }
                }
                return true
            }
            // 自动重试转圈时：确认键打断并切到手动面板；其余键忽略，避免误触切集。
            if(playError && autoRecovery) {
                if((key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER) && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) stopAutoRecoveryAndShowPanel()
                return true
            }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if(key==KeyEvent.KEYCODE_DPAD_RIGHT && event.action==KeyEvent.ACTION_UP) { stopFastForward(); return true }
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if(event.repeatCount==0) seek(1,0) else startFastForward()
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) {
                            if(!phonePlayerLayout()) showPanel()
                            else if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) skipEpisode(1) else showEpisodePanel(hud)
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> if(event.repeatCount==0) { if(extras.dpadUpDownAction==PlaybackExtras.UPDOWN_SWITCH) skipEpisode(-1) else showPanel() }
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        if(!activityInitialized) { super.onConfigurationChanged(newConfig); return }
        val previous=phoneConfiguration
        super.onConfigurationChanged(newConfig)
        phoneConfiguration=android.content.res.Configuration(newConfig)
        val themeChanged=PortraitStyle.refresh(newConfig)
        if(themeChanged) retainedPhoneCatalogs.clear()
        val systemAppearanceChanged=previous!=null &&
            (previous.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)!=(newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
        val appearanceOnly=systemAppearanceChanged && previous!=null && previous.orientation==newConfig.orientation &&
            previous.screenWidthDp==newConfig.screenWidthDp && previous.screenHeightDp==newConfig.screenHeightDp
        // 固定手机外观时忽略系统深浅色变化；纯外观切换不重新挂载播放页。
        if(appearanceOnly) {
            if(themeChanged && screen!="player") { applyPhoneSystemBars(); redrawPhoneTheme() }
            return
        }
        // 系统栏同步不依赖数据加载，先于库就绪判断处理（内部自带手机/全屏守卫）
        applyPhoneSystemBars()
        if(!library.isLoaded) return
        // 手机竖屏 ⇄ 横屏（TV 版界面）切换：manifest 不再锁死方向，两侧各按当前方向重建；
        // 播放中的重建沿用原有「按进度重挂」逻辑（手机/电视播放内核一致，只是皮肤不同）。
        // 播放页全屏会话期间横屏仍是手机横屏皮肤（见 phonePlayerLayout），不是电视界面。
        // 离开竖屏时 phoneTab 会残留 detail/player，横屏若跟着它走会画出空目录页，先复位；
        // 全屏会话例外，它随时可能转回竖屏继续走手机页面。
        if(!phoneLayout() && !phoneFullscreenPlayback && phoneTab in listOf("detail","player")) phoneTab="home"
        when(screen) {
            // 手机设置页跟着方向重建：竖屏保持手机设置页；转到横屏时如果是从播放页进来的，
            // 要回到播放画面（全屏皮肤），否则落回电视版设置页签
            "settings" -> if(phoneLayout() || phoneFullscreenPlayback) phoneSettings.render(root) else closePhoneSettings()
            // 旋转会让播放页外壳尺寸彻底改变，必须重建 UI；
            // playerView 置空让 playEpisode 走 !reuse 分支重新挂载，播放本身不断流。
            // 皮肤由 phonePlayerLayout() 决定：全屏会话留在手机横屏皮肤，否则按启动方向。
            "player" -> {
                // 全屏会话还没结束就真的转回竖屏（例如设备姿态被系统改回）：会话结束，
                // 用竖屏皮肤重建；强制横屏偏好下没有手机全屏皮肤，也在这里收掉标记。
                if(phoneFullscreenPlayback &&
                    (newConfig.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT ||
                        !PhoneUi.shouldUsePhoneFullscreen(this))) {
                    phoneFullscreenPlayback=false
                }
                val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle
                phonePlayer=null; playerView=null
                playEpisode(episodeIndex,position,autoplay)
            }
            // 详情页要按新方向重建成对应的皮肤；这里必须先判断方向，不能沿用旧的 phoneTab
            "detail" -> when {
                detail==null -> if(phoneLayout()) showPhoneHome(load=!phoneRecommendationStarted) else showCatalog()
                phoneLayout() -> openDetailPhone(detail!!.series)
                else -> showDetail(false)
            }
            else -> if(phoneLayout()) renderPhone() else showCatalog()
        }
    }
    override fun onResume() {
        if(!activityInitialized) { super.onResume(); return }
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        main.removeCallbacks(heartbeatRunnable)
        // 冷启动只先调更新接口（同时计启动）；心跳至少等待一分钟再开始。
        main.postDelayed(heartbeatRunnable, heartbeatIntervalMs)
        if(PortraitStyle.refresh(this)) redrawPhoneTheme()
        phoneConfiguration=android.content.res.Configuration(resources.configuration)
        if(library.isLoaded) {
            if(initialCatalogPending) { if(phoneLayout()) showInitialPhonePage() else showInitialTvPage() }
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                // 后台期间只暂停、没拆播放器：原地续播，不重建页面也不重新取址。
                // 保持暂停态（与之前「回来先停住」的手感一致），按播放即可继续。
                val kept = resumeKeptPlayback()
                if(!kept) {
                    if(phoneLayout()) {
                        // 手机竖屏回来：按最后所在页面重建（播放页由 playEpisode 自己恢复）
                        when {
                            screen=="settings" -> phoneSettings.render(root)
                            screen=="player" && detail!=null -> playEpisode(episodeIndex,startPosition,autoplay=false)
                            screen=="detail" && (detail!=null || pendingDetail!=null) -> openDetailPhone(pendingDetail ?: detail!!.series)
                            else -> switchPhoneTab(phoneTab)
                        }
                    } else when(screen) {
                        "settings" -> { tab=5; showCatalog() }
                        "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                        "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                        else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==0 || tab==2 || (tab==1 && query.isNotBlank())))
                    }
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
        // Glide 随 Activity 生命周期恢复弹框和列表中尚未完成的图片请求。
        maybePromptCrash()
        // 后台期间到达的更新结果（发布即弹时不在前台被跳过），回前台时页面已就绪，补弹。
        main.post { drainPendingUpdate() }
        // 升级弹框跳「允许安装未知应用」设置页后返回：已授权则直接续装，
        // 并把弹框从「正在安装…」恢复成「升级」（用户取消安装时可再点，APK 已落盘复用）。
        main.post {
            if(!isDestroyed && AppUpdateInstaller.resumePendingInstall(this)) AppUpdateDialog.notifyInstallResumed()
        }
        homePlayback?.setActive(foreground && hasWindowFocus() && screen=="catalog" && tab==0 && !phoneLayout())
    }
    /**
     * 从后台回来时原地续播：onStop 已经保留了播放器与媒体源，这里只把界面重新挂回去。
     * 返回 true 表示已处理（调用方跳过整页重建），false 表示这次的播放器没保住、走原有重建流程。
     *
     * 播放器、媒体源、已缓冲样本、解码器都还是原来的实例，所以不需要重新取址、重新 prepare，
     * 回来直接停在暂停位置，用户按播放就从原处继续。
     */
    private fun resumeKeptPlayback(): Boolean {
        if(!playbackKeptOnStop) return false
        // 无论这次能不能原地恢复，标记都要消费掉：下次 onStop 重新判断
        playbackKeptOnStop=false
        if(screen!="player" || playError || !::hud.isInitialized) return false
        val p=player ?: return false
        val view=playerView ?: return false
        // onStop 期间页面结构没动过，不重建外壳：把播放器接回视图，再刷新一遍文案与状态条
        if(view.player!==p) view.player=p
        detail?.let { data -> if(phonePlayerLayout()) phonePlayer?.updateEpisode(data) }
        playbackReady=p.playbackState==Player.STATE_READY
        refreshCacheStrip()
        // 保持暂停态：与以往「切回来先停住、按播放继续」的手感一致。
        requestedAutoplay=false; pausedForLifecycle=true
        p.pause()
        // 手机播放面板的控件不随 onStop 销毁，直接刷新一遍；电视版重画 HUD。
        if(phonePlayerLayout()) phonePlayer?.refresh(
            playing=false,
            position=p.currentPosition,
            duration=p.duration.coerceAtLeast(0),
            bufferingNow=p.playbackState==Player.STATE_BUFFERING || !playbackReady,
            error=null,
            speed=com.hongguotv.core.PlaybackSpeed.label(p.playbackParameters.speed))
        else showHud()
        syncMediaSession()
        return true
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if(!activityInitialized) { super.onWindowFocusChanged(hasFocus); return }
        super.onWindowFocusChanged(hasFocus)
        homePlayback?.setActive(foreground && hasFocus && screen=="catalog" && tab==0 && !phoneLayout())
        // 手机设置页的取值都改在独立弹窗里：弹窗关闭、窗口重新拿到焦点时刷新一遍右侧文案，
        // 比在每个弹窗回调里各挂一次可靠（弹窗是别的窗口，不会重建主窗口）
        if(hasFocus && screen=="settings") phoneRefreshSettingsValues()
        // 拿到窗口焦点时补弹：发布即弹若发生在窗口无焦点期间，弹框可能无法展示。
        if(hasFocus && foreground && !isDestroyed) {
            drainPendingUpdate()
        }
    }
    override fun onPause() {
        if(!activityInitialized) { super.onPause(); return }
        tvVideoFrameWaitStartedAt=0L
        homePlayback?.setActive(false)
        main.removeCallbacks(heartbeatRunnable)
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush()
        savePhoneSession()
        super.onPause()
    }
    override fun onStop() {
        if(!activityInitialized) { super.onStop(); return }
        phoneCoverTransition?.cancel(); phoneCoverTransition=null
        restoreStoppedPage=true; generation++; cancelWork(); cancelDetailExtras(); tvTools.close()
        // 播放页留在屏幕上但被切到后台（切 App / 锁屏 / 下拉通知栏）：只暂停，不拆播放器。
        // 以前这里无条件 releasePlayer()，回来要重新取址、重新 prepare、重新缓冲。
        // 播放器的解码器与已缓冲的媒体样本都还在，onResume 可以直接原地续播。
        // view 已经挂不住了（Surface 被收回）的极端情况由播放器自行重建输出，不影响数据源。
        if(screen=="player") {
            startPosition=currentPosition(); saveProgress()
            playbackKeptOnStop=player!=null && playerView!=null && !playError
            if(!playbackKeptOnStop) releasePlayer()
            else {
                // 只暂停：媒体源、解码器、已缓冲样本都留在播放器里，
                // 回来走 onResume 的原地续播分支，不再重新取址。
                player?.pause()
                cancelPrefetch()
            }
        } else playbackKeptOnStop=false
        retainedPhoneCatalogs.clear()
        // 页面解绑由 Glide 释放资源，缓存与内存压力由 Glide 的应用生命周期回调管理。
        artwork.clearPage(); library.flush()
        super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        if(!activityInitialized) { super.onTrimMemory(level); return }
        super.onTrimMemory(level)
        val memoryPressure=level in android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level>=android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if(memoryPressure) {
            val severe=level==android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level>=android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE
            pausePrefetchForMemory(if(severe) 120_000L else 60_000L)
        }
    }
    override fun onDestroy() {
        PendingAppUpdate.setListener(null)
        if(!activityInitialized) {
            main.removeCallbacksAndMessages(null)
            suggestIo.shutdownNow(); pageIo.shutdownNow(); io.shutdownNow()
            super.onDestroy(); return
        }
        stopTvHomePlayback()
        playbackAlgorithms.removeListener(playbackAlgorithmListener)
        generation++; cancelWork(); cancelDetailExtras()
        exitDialog?.dismiss(); exitDialog=null
        phoneExitArtwork?.close(); phoneExitArtwork=null
        // 全屏播放中点着返回退出 App：方向已经交给「启动方向」，别把横屏留给下一次启动
        if(phoneFullscreenPlayback) requestedOrientation=PhoneUi.requestedOrientation(this)
        phoneFullscreenPlayback=false; phonePlayerRenderedFullscreen=false
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); saveProgress(); releasePlayer(); accountManager.destroy(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        cancelSearchSuggestions(); suggestIo.shutdownNow(); pageIo.shutdownNow(); io.shutdownNow(); playbackCache?.close(); playbackCache=null; episodeDiskCache?.close(); episodeDiskCache=null
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
        if(killOnDestroy) android.os.Process.killProcess(android.os.Process.myPid())
    }
}
