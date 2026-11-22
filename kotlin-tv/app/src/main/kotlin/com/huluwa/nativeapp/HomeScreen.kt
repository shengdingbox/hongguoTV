// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import androidx.core.graphics.drawable.DrawableCompat
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.Series

/** Fixed text slots keep D-pad movement from resizing the content below the preview. */
class SeriesPreview(context: Context): LinearLayout(context) {
    private fun label(size: Float,color: Int)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val title=label(24f,TvStyle.text).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private val meta=label(12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    init {
        orientation=VERTICAL
        val dp=resources.displayMetrics.density
        setPadding(0,(7*dp).toInt(),0,(10*dp).toInt())
        addView(title)
        addView(meta,LayoutParams(-1,-2).apply { topMargin=(5*dp).toInt() })
        show(null,"")
    }
    fun show(series: Series?,status: String) {
        val nextTitle=series?.title ?: "下一段好故事，从这里开始"
        val nextMeta=if(series==null) "浏览片库，选择你想看的剧" else listOf(status,series.badge,series.tags).flatMap { it.split('·') }.map(String::trim).filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().joinToString("  /  ")
        if(title.text.toString()!=nextTitle) title.text=nextTitle
        if(meta.text.toString()!=nextMeta) meta.text=nextMeta
    }
}

/** TV discovery carousel. The existing library shelves remain its only source of data. */
class HomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,
    private val open: (Series,Boolean)->Unit,
    private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit
): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>,val more: (() -> Unit)?=null)
    data class Position(val focus: String,val shelf: String?,val column: Int,val row: Int,val vertical: Int,val horizontal: Map<String,Int>)

    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply {
        text=value; textSize=size; setTextColor(color); includeFontPadding=false
    }
    private fun button(value: String,tagValue: String,action: ()->Unit)=label(value,14f).apply {
        id=View.generateViewId(); tag=tagValue
        isFocusable=true; isFocusableInTouchMode=true; isClickable=true
        gravity=Gravity.CENTER; maxLines=1; ellipsize=TextUtils.TruncateAt.END
        minHeight=dp(38); setPadding(dp(12),dp(8),dp(12),dp(8))
        setOnClickListener { action() }
        setOnFocusChangeListener { _,focused ->
            if(tag.toString()=="home:refresh" || tag.toString().startsWith("home:feed:")) paintFeedTabs()
            else background=TvVisual.row(context,focused,isSelected)
            if(focused) lastFocus=tag.toString()
        }
        background=TvVisual.row(context,false)
    }
    private fun cover()=ImageView(context).apply {
        scaleType=ImageView.ScaleType.CENTER_CROP
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun shade()=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        if(TvStyle.theme==TvStyle.Theme.NIGHT) intArrayOf(0x1010192a,0xe60b101a.toInt())
        else intArrayOf(0x10fffaf0,0xe6eee4d4.toInt()))

    private var shelves=emptyList<Shelf>()
    private var activeShelfKey="hot"
    private val indices=linkedMapOf<String,Int>()
    private var lastFocus=""
    private var footerMore: (() -> Unit)?=null
    private var revision=0
    var onPreviewSelected: ((Series)->Unit)?=null
    var onPreviewToggle: (() -> Unit)?=null
    var onPreviewActions: (() -> Unit)?=null
    var onPreviewCleared: (() -> Unit)?=null
    /** 首页子菜单：推荐、漫剧、真人剧、换一批。 */
    var onFeedMode: ((String) -> Unit)?=null
    /** 当前列表已到边缘：让上层继续取下一批，而不是在旧列表里循环。 */
    var onFeedEdge: (() -> Unit)?=null
    /** 剩余可看不足 5 条时让上层后台预取下一批（对齐参考 ensureNextWorks 的 lookahead 预取）。 */
    var onFeedPrefetch: (() -> Unit)?=null
    private var feedMode = "hot"
    private var previewView: View?=null
    private var previewSeriesId=""
    private var previewVisible=false
    private var previewPlaying=false
    private var previewFailed=false
    private val selectorLine=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
    private val selectors=HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; addView(selectorLine) }
    private val feedTabs=mutableListOf<TextView>()
    private val carousel=FrameLayout(context).apply { clipChildren=false; clipToPadding=false }
    private val hero=LinearLayout(context).apply {
        orientation=VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(8))
        background=TvVisual.panel(context); clipToOutline=true
    }
    private val stage=FrameLayout(context).apply {
        id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true; isClickable=true
        background=TvVisual.panel(context); clipToOutline=true
    }
    private val backdrop=cover().apply { alpha=.3f }
    private val poster=cover().apply { scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(TvStyle.background) }
    private val previousCover=cover()
    private val nextCover=cover()
    private val previousTitle=label("",14f)
    private val nextTitle=label("",14f)
    private val previousCard=side(previousCover,previousTitle,-1)
    private val nextCard=side(nextCover,nextTitle,1)
    private val title=label("发现下一部好剧",21f).apply {
        typeface=Typeface.DEFAULT_BOLD; maxLines=2; ellipsize=TextUtils.TruncateAt.END
    }
    private val metadata=label("",12f,TvStyle.accent).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    private val intro=label("热门短剧，随心发现",13f,TvStyle.muted).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END }
    private val info=LinearLayout(context).apply { orientation=VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10)) }
    private val empty=label("正在发现好剧…",15f,TvStyle.muted).apply { gravity=Gravity.CENTER; maxLines=3 }
    private val previewStatus=label("",11f).apply {
        gravity=Gravity.CENTER_VERTICAL; maxLines=1; ellipsize=TextUtils.TruncateAt.END
        setPadding(dp(10),dp(5),dp(10),dp(5)); visibility=GONE
        background=TvStyle.shape(context,
            if(TvStyle.theme==TvStyle.Theme.NIGHT) Color.argb(210,11,15,21) else Color.argb(225,255,250,241),radius=6)
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=100; DrawableCompat.setTint(progressDrawable, TvStyle.accent); visibility=GONE
    }
    // 海外视频风格的悬浮操作条：压在画面底部，半透明底不再单独占一栏
    private val actionLine=LinearLayout(context).apply {
        gravity=Gravity.CENTER_VERTICAL; setPadding(dp(6),dp(2),dp(6),dp(2))
        background=TvStyle.shape(context,
            if(TvStyle.theme==TvStyle.Theme.NIGHT) Color.argb(70,11,15,21) else Color.argb(90,255,250,241),radius=20)
    }
    private val play=button("播放全集","home:play") { openCurrent() }
    private val manage=button("收藏 / 更多","home:actions") { openActions(manageAnchor()) }
    private val resume=button("查看详情","home:detail") { openCurrent() }
    private val positionLabel=label("",12f,TvStyle.accent).apply { gravity=Gravity.CENTER }
    val refresh=button("换一批","home:refresh") {}.apply { textSize=14f; minHeight=dp(36); setPadding(dp(16),dp(7),dp(16),dp(7)) }

    init {
        orientation=VERTICAL
        // 固定分类 Tab：推荐 / 真人剧 / 漫剧 / 换一批，保持参考项目的布局。
        listOf("hot" to "推荐", "real" to "真人剧", "comic" to "漫剧").forEach { (key, title) ->
            val tab=button(title, "home:feed:$key") { selectFeed(key) }.apply {
                textSize=14f; minHeight=dp(36); gravity=Gravity.CENTER
                setPadding(dp(14),dp(7),dp(14),dp(7))
                contentDescription=title
            }
            feedTabs+=tab
            selectorLine.addView(tab, LayoutParams(dp(if(key=="real") 86 else 72), dp(36)).apply { rightMargin=dp(8) })
        }
        selectorLine.addView(refresh, LayoutParams(dp(104), dp(36)).apply { leftMargin=dp(4) })
        val selectorViews=feedTabs+refresh
        selectorViews.forEachIndexed { index, tab ->
            tab.nextFocusLeftId=selectorViews[if(index==0) selectorViews.lastIndex else index-1].id
            tab.nextFocusRightId=selectorViews[if(index==selectorViews.lastIndex) 0 else index+1].id
        }
        feedTabs.forEach { it.nextFocusDownId=stage.id }
        refresh.nextFocusRightId=refresh.id
        paintFeedTabs()
        addView(selectors, LayoutParams(-1, dp(42)))

        carousel.addView(previousCard,FrameLayout.LayoutParams(dp(112),-1,Gravity.START).apply { topMargin=dp(16); bottomMargin=dp(16) })
        carousel.addView(nextCard,FrameLayout.LayoutParams(dp(112),-1,Gravity.END).apply { topMargin=dp(16); bottomMargin=dp(16) })
        stage.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
        stage.addView(View(context).apply { background=shade() },FrameLayout.LayoutParams(-1,-1))
        stage.addView(poster,FrameLayout.LayoutParams(-1,-1))
        stage.addView(empty,FrameLayout.LayoutParams(-1,-1))
        info.background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            if(TvStyle.theme==TvStyle.Theme.NIGHT) intArrayOf(0xe60b101a.toInt(),0x550b101a)
            else intArrayOf(0xe6eee4d4.toInt(),0x55eee4d4))
        info.addView(title)
        info.addView(metadata,LayoutParams(-1,-2).apply { topMargin=dp(5) })
        info.addView(intro,LayoutParams(-1,-2).apply { topMargin=dp(5) })
        stage.addView(info,FrameLayout.LayoutParams(dp(260),-2,Gravity.START or Gravity.BOTTOM).apply {
            leftMargin=dp(10); bottomMargin=dp(10)
        })
        // 状态条抬到悬浮操作条上方（条占底部 10~54dp），避免互相遮挡
        stage.addView(previewStatus,FrameLayout.LayoutParams(-1,dp(28),Gravity.BOTTOM).apply {
            leftMargin=dp(8); rightMargin=dp(8); bottomMargin=dp(60)
        })
        stage.addView(progress,FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM))
        // 画面占满整个 hero，操作条悬浮其上，视频延伸到原按钮栏区域
        hero.addView(stage,LayoutParams(-1,-1))
        listOf(play,manage,resume).forEachIndexed { index,view ->
            actionLine.addView(view,LayoutParams(-2,dp(40)).apply { if(index<2) rightMargin=dp(8) })
            view.nextFocusUpId=stage.id; view.nextFocusDownId=view.id
        }
        play.nextFocusLeftId=play.id; play.nextFocusRightId=manage.id
        manage.nextFocusLeftId=play.id; manage.nextFocusRightId=resume.id
        resume.nextFocusLeftId=manage.id; resume.nextFocusRightId=resume.id
        stage.addView(actionLine,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin=dp(10)
        })
        carousel.addView(hero,FrameLayout.LayoutParams(-1,-1).apply {
            leftMargin=dp(76); rightMargin=dp(76); topMargin=dp(4); bottomMargin=dp(4)
        })
        addView(carousel,LayoutParams(-1,0,1f))
        addView(positionLabel,LayoutParams(-1,dp(23)))
        stage.nextFocusLeftId=stage.id; stage.nextFocusRightId=stage.id
        stage.nextFocusUpId=top.id; stage.nextFocusDownId=play.id
        stage.setOnFocusChangeListener { _,focused ->
            hero.background=if(focused) TvVisual.card(context,true) else TvVisual.panel(context)
            if(focused) rememberCurrent()
        }
        stage.setOnClickListener { togglePreview() }
        stage.setOnLongClickListener { openActions(stage); true }
        stage.setOnKeyListener { _,key,event ->
            when(key) {
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) move(if(key==KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if(event.action==KeyEvent.ACTION_UP) openActions(stage)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_SPACE -> {
                    if(event.action==KeyEvent.ACTION_UP) togglePreview()
                    true
                }
                else -> false
            }
        }
        refresh.nextFocusUpId=top.id; refresh.nextFocusDownId=stage.id
    }

    private fun side(image: ImageView,caption: TextView,delta: Int)=FrameLayout(context).apply {
        background=TvVisual.panel(context); clipToOutline=true; alpha=.82f
        addView(image,FrameLayout.LayoutParams(-1,-1))
        addView(View(context).apply { background=shade() },FrameLayout.LayoutParams(-1,-1))
        caption.maxLines=3; caption.ellipsize=TextUtils.TruncateAt.END
        caption.setPadding(dp(10),dp(10),dp(10),dp(16))
        addView(caption,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        addView(label(if(delta<0) "‹" else "›",32f).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
        isClickable=true; isFocusable=false
        contentDescription=if(delta<0) "上一部" else "下一部"
        setOnClickListener { move(delta); stage.requestFocus() }
    }

    private fun activeShelf()=shelves.firstOrNull { it.key==activeShelfKey }
    private fun currentIndex()=(indices[activeShelfKey] ?: 0).coerceIn(0,(activeShelf()?.entries?.lastIndex ?: 0).coerceAtLeast(0))
    private fun current()=activeShelf()?.entries?.getOrNull(currentIndex())
    private fun currentKey()=current()?.let { "$activeShelfKey:${it.series.id}" }.orEmpty()
    private fun manageAnchor(): View=manage
    private fun rememberCurrent() {
        current()?.let { entry ->
            lastFocus=currentKey(); remember(lastFocus); selected(entry.series,entry.label)
        }
    }
    private fun openCurrent() { current()?.let { rememberCurrent(); open(it.series,false) } }
    private fun togglePreview() {
        if(current()==null) return
        rememberCurrent()
        onPreviewToggle?.invoke() ?: openCurrent()
    }
    private fun openActions(anchor: View) {
        current()?.let { entry ->
            rememberCurrent(); onPreviewActions?.invoke(); actions(entry.series,anchor)
        }
    }

    /** Keeps the TV playback surface below the cover and all focusable browsing controls. */
    fun attachPreview(view: View) {
        if(previewView===view) return
        previewView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        (view.parent as? ViewGroup)?.removeView(view)
        previewView=view
        view.isFocusable=false; view.isFocusableInTouchMode=false; view.isClickable=false
        view.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        (view as? ViewGroup)?.descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        view.visibility=VISIBLE
        stage.addView(view,stage.indexOfChild(poster),FrameLayout.LayoutParams(-1,-1))
    }

    /** MainActivity installs the callbacks after constructing the page, then selects its first work. */
    fun selectPreview() {
        val entry=current()
        if(entry!=null) onPreviewSelected?.invoke(entry.series) else onPreviewCleared?.invoke()
    }

    /** 换剧预取候选：当前焦点所在行之后的若干部剧，供首页播放器后台提前准备。 */
    fun upcomingAfterFocus(count: Int): List<Series> {
        val entries=activeShelf()?.entries ?: return emptyList()
        return entries.drop(currentIndex()+1).take(count).map { it.series }
    }

    fun showPreview(seriesId: String,label: String,showCover: Boolean,playing: Boolean,failed: Boolean=false) {
        if(current()?.series?.id!=seriesId) return
        previewSeriesId=seriesId; previewVisible=!showCover; previewPlaying=playing; previewFailed=failed
        poster.visibility=if(showCover) VISIBLE else GONE
        previewView?.visibility=VISIBLE
        previewStatus.text=label
        previewStatus.setTextColor(if(failed) TvStyle.accent else TvStyle.text)
        previewStatus.visibility=if(label.isBlank()) GONE else VISIBLE
        applyPreviewInfo()
        describeStage()
    }

    private fun applyPreviewInfo() {
        val compact=height>0 && height<dp(300)
        intro.visibility=if(compact || previewVisible) GONE else VISIBLE
        title.maxLines=if(compact || previewVisible) 1 else 2
        title.textSize=if(compact || previewVisible) 17f else 21f
        // 悬浮操作条占住底部约 54dp：信息块抬到条上方，状态条可见时再抬高一个状态条高度
        val margin=if(previewStatus.visibility==VISIBLE) dp(94) else dp(60)
        (info.layoutParams as? FrameLayout.LayoutParams)?.takeIf { it.bottomMargin!=margin }?.let {
            it.bottomMargin=margin; info.layoutParams=it
        }
    }

    private fun describeStage() {
        stage.contentDescription=current()?.let {
            val action=if(onPreviewToggle==null) "确定看全集" else if(previewFailed) "确定重试播放" else if(previewPlaying) "确定暂停" else "确定继续播放"
            "${it.series.title}，${it.label}，${previewStatus.text}，左右换剧，$action，长按打开片单操作"
        } ?: "暂无剧集，请刷新"
    }
    private fun continueWatching() { openCurrent() }
    private fun move(delta: Int) {
        val shelf=activeShelf() ?: return
        if(shelf.entries.isEmpty()) { onFeedEdge?.invoke(); return }
        val next=currentIndex()+delta
        if(next !in shelf.entries.indices) {
            // 对齐参考项目：向左到头只提示，不触发加载；向右触边提示加载中，取回后由上层跳到新批次。
            if(delta<0) { positionLabel.text="已到本次浏览的最早作品"; return }
            positionLabel.text="正在加载更多…"
            onFeedEdge?.invoke()
            return
        }
        indices[activeShelfKey]=next
        paintSelection(); rememberCurrent()
    }
    fun setFeedMode(key: String) { feedMode = key; paintFeedTabs() }

    private fun selectFeed(key: String) {
        feedMode=key
        onFeedMode?.invoke(key)
        paintFeedTabs()
        stage.requestFocus()
    }

    private fun paintFeedTabs() {
        feedTabs.forEach { tab ->
            val selected=tab.tag=="home:feed:$feedMode"
            tab.isSelected=selected
            tab.background=TvVisual.card(context,tab.hasFocus(),selected)
            tab.setTextColor(if(selected) TvStyle.accent else if(tab.hasFocus()) TvStyle.text else TvStyle.muted)
            tab.setTypeface(null,if(selected || tab.hasFocus()) Typeface.BOLD else Typeface.NORMAL)
        }
        refresh.isSelected=false
        refresh.background=TvVisual.card(context,refresh.hasFocus(),false)
        refresh.setTextColor(if(refresh.hasFocus()) TvStyle.text else TvStyle.muted)
        refresh.setTypeface(null,if(refresh.hasFocus()) Typeface.BOLD else Typeface.NORMAL)
    }

    fun capturePosition(): Position {
        val row=shelves.indexOfFirst { it.key==activeShelfKey }.coerceAtLeast(0)
        val focus=findFocus()?.tag?.toString()?.takeIf { it.startsWith("home:") } ?: currentKey().ifBlank { lastFocus }
        return Position(focus,activeShelfKey,currentIndex(),row,0,indices.toMap())
    }
    fun updateShelves(next: List<Shelf>,requestFocus: Boolean=hasFocus()) {
        val position=capturePosition()
        render(next,position.focus,requestFocus,footerMore,position)
    }
    fun updateEntry(shelfKey: String,entry: Entry): Boolean {
        if(shelves.none { it.key==shelfKey && it.entries.any { item -> item.series.id==entry.series.id } }) return false
        shelves=shelves.map { shelf -> if(shelf.key==shelfKey) shelf.copy(entries=shelf.entries.map { if(it.series.id==entry.series.id) entry else it }) else shelf }
        paintSelection()
        return true
    }
    fun refreshSelection() { current()?.let { selected(it.series,it.label) } }
    /** 触边提示（正在加载更多…/已到最早作品）落空时复位到底部状态行。 */
    fun clearEdgeHint() { paintSelection() }
    fun reloadArtwork() { loadArtwork() }

    /** 冷启动直接呈现主页，后台记录尚未就绪时不显示加载或空记录提示。 */
    fun showStartup(retry: Boolean=false) {
        render(emptyList(),"",false,null)
        empty.text=""
        positionLabel.text=""
        stage.isFocusable=false; stage.isClickable=false
        refresh.text=if(retry) "重试" else "换一批"
        refresh.nextFocusDownId=refresh.id
    }

    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: Position?=null) {
        val previous=position ?: if(this.shelves.isNotEmpty()) capturePosition() else null
        this.shelves=shelves; footerMore=more; revision++
        restoreSelection(focus,previous)
        paintFeedTabs()
        top.nextFocusDownId=firstId()
        paintSelection()
        if(requestFocus) focusTarget(focus.ifBlank { previous?.focus.orEmpty() }).requestFocus()
        val ticket=revision
        post { if(ticket==revision) loadArtwork() }
    }

    private fun restoreSelection(focus: String,position: Position?) {
        position?.horizontal?.let { indices.putAll(it) }
        val requested=focus.ifBlank { position?.focus.orEmpty() }
        val match=shelves.firstNotNullOfOrNull { shelf ->
            shelf.entries.indexOfFirst { "${shelf.key}:${it.series.id}"==requested }.takeIf { it>=0 }?.let { shelf.key to it }
        }
        if(match!=null) { activeShelfKey=match.first; indices[match.first]=match.second }
        else {
            activeShelfKey=position?.shelf?.takeIf { key -> shelves.any { it.key==key && it.entries.isNotEmpty() } }
                ?: activeShelfKey.takeIf { key -> shelves.any { it.key==key && it.entries.isNotEmpty() } }
                ?: shelves.firstOrNull { it.key=="hot" && it.entries.isNotEmpty() }?.key
                ?: shelves.firstOrNull { it.entries.isNotEmpty() }?.key ?: "hot"
            if(position?.shelf==activeShelfKey) indices[activeShelfKey]=position.column
        }
        indices[activeShelfKey]=currentIndex()
    }
    fun restorePosition(position: Position,requestFocus: Boolean=true) {
        restoreSelection(position.focus,position); paintSelection()
        if(requestFocus) focusTarget(position.focus).requestFocus()
    }
    private fun focusTarget(key: String): View=when(key) {
        "home:refresh" -> refresh
        "home:play" -> play
        "home:actions" -> manage
        "home:resume" -> resume
        else -> feedTabs.firstOrNull { it.tag==key } ?: if(current()!=null) stage else refresh
    }
    private fun paintSelection() {
        val shelf=activeShelf(); val entry=current(); val entries=shelf?.entries.orEmpty(); val index=currentIndex()
        stage.tag=currentKey()
        val nextId=entry?.series?.id.orEmpty()
        if(previewSeriesId!=nextId) {
            previewSeriesId=nextId; previewVisible=false; previewPlaying=false; previewFailed=false
            poster.visibility=VISIBLE; previewView?.visibility=VISIBLE
            previewStatus.text=""; previewStatus.visibility=GONE
        }
        title.text=entry?.series?.title ?: "发现下一部好剧"
        metadata.text=entry?.let { listOf(it.label,it.series.tags).filter(String::isNotBlank).distinct().joinToString("  ·  ") }.orEmpty()
        intro.text=entry?.series?.description?.takeIf(String::isNotBlank) ?: "左右换剧，挑选喜欢的故事"
        info.visibility=if(entry==null) GONE else VISIBLE
        empty.visibility=if(entry==null) VISIBLE else GONE
        empty.text="还没有可展示的剧集\n按右上方刷新发现更多好剧"
        play.text="播放随心看"
        play.isEnabled=entry!=null; manage.isEnabled=entry!=null
        play.alpha=if(entry==null) .4f else 1f; manage.alpha=play.alpha
        val more=shelf?.more
        resume.text=if(more!=null) "换一批" else "查看详情"
        resume.isEnabled=entry!=null || more!=null
        resume.alpha=if(resume.isEnabled) 1f else .4f
        progress.progress=entry?.progress ?: 0; progress.visibility=if(entry?.progress==null) GONE else VISIBLE
        previousTitle.text=if(entries.size>1) entries[(index-1+entries.size)%entries.size].series.title else ""
        nextTitle.text=if(entries.size>1) entries[(index+1)%entries.size].series.title else ""
        previousCard.visibility=if(entries.size>1) VISIBLE else INVISIBLE
        nextCard.visibility=if(entries.size>1) VISIBLE else INVISIBLE
        previousCard.contentDescription="上一部，${previousTitle.text}"; nextCard.contentDescription="下一部，${nextTitle.text}"
        positionLabel.text=if(entry!=null) "${index+1} / ${entries.size}  ·  ${shelf?.title.orEmpty()}" else "好故事，即将开始"
        describeStage()
        stage.nextFocusUpId=feedTabs.firstOrNull { it.tag=="home:feed:$feedMode" }?.id ?: top.id
        applyPreviewInfo()
        loadArtwork()
        selectPreview()
        // 剩余不足 5 条时通知上层预取；post 到下一帧，避免在布局/绘制中途发起加载回调链。
        if(entry!=null && entries.size-index-1<=5) post { onFeedPrefetch?.invoke() }
    }
    private fun loadArtwork() {
        val entries=activeShelf()?.entries.orEmpty(); val index=currentIndex(); val entry=entries.getOrNull(index)
        requestCover(backdrop,entry?.series?.cover.orEmpty())
        requestCover(poster,entry?.series?.cover.orEmpty())
        requestCover(previousCover,if(entries.size>1) entries[(index-1+entries.size)%entries.size].series.cover else "")
        requestCover(nextCover,if(entries.size>1) entries[(index+1)%entries.size].series.cover else "")
    }
    private fun requestCover(image: ImageView,url: String) {
        // 图片替换、布局尺寸与空地址清理由 Glide 统一处理。
        loadCover(image,url)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { loadArtwork() } }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(w==oldw && h==oldh) return
        val compact=w<dp(700)
        val edge=if(compact) dp(42) else dp(76)
        (hero.layoutParams as FrameLayout.LayoutParams).apply { leftMargin=edge; rightMargin=edge }.also { hero.layoutParams=it }
        val sideWidth=if(compact) dp(70) else dp(112)
        listOf(previousCard,nextCard).forEach { it.layoutParams=it.layoutParams.apply { width=sideWidth } }
        info.layoutParams=info.layoutParams.apply { width=minOf(dp(260),(w-edge*2-dp(34)).coerceAtLeast(dp(140))) }
        applyPreviewInfo()
        post { loadArtwork() }
    }
    fun firstId()=if(current()!=null) stage.id else feedTabs.firstOrNull()?.id ?: refresh.id
}
