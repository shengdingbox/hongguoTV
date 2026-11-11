// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.FocusGrid
import com.hongguotv.core.Series

/** Fixed text slots keep D-pad movement from resizing the content below the preview. */
class SeriesPreview(context: Context): LinearLayout(context) {
    private fun label(size: Float,color: Int)=TextView(context).apply { textSize=size; setTextColor(color); includeFontPadding=false }
    private val title=label(27f,TvStyle.text).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private val meta=label(12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END }
    init {
        orientation=VERTICAL
        val dp=resources.displayMetrics.density
        setPadding(0,(7*dp).toInt(),0,(12*dp).toInt())
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

/** 新版首页：当前剧集预览 + 分区剧架。 */
private class NewHomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,
    private val open: (Series,Boolean)->Unit,
    private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit
): LinearLayout(context) {
    private data class Card(val key: String,val view: LinearLayout,val image: ImageView?,val title: TextView,val label: TextView,val progress: ProgressBar?,var entry: HomeScreen.Entry?,var pending: Boolean=false)
    private data class Row(val key: String,val block: LinearLayout,val horizontal: HorizontalScrollView,val cards: List<Card>)
    private val accent=TvStyle.accent
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(focus: Boolean)=TvStyle.shape(context,
        if(focus) TvStyle.raised else Color.TRANSPARENT,
        if(focus) accent else Color.TRANSPARENT,radius=12)
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private val scroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
    private val contents=LinearLayout(context).apply { orientation=VERTICAL }
    private val hero=FrameLayout(context).apply {
        background=TvStyle.shape(context,TvStyle.surface,radius=16)
        clipToOutline=true
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val heroImage=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val heroEyebrow=label("为你发现",11f,accent)
    private val heroTitle=label("下一段好故事，从这里开始",30f).apply {
        maxLines=1; ellipsize=TextUtils.TruncateAt.END
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    private val heroMeta=label("浏览片库，选择你想看的剧",13f,TvStyle.muted).apply {
        maxLines=1; ellipsize=TextUtils.TruncateAt.END
    }
    private val heroAction=label("确认查看详情   ·   长按管理片单",12f,accent)
    private var heroEntry: HomeScreen.Entry?=null
    private val rows=mutableListOf<Row>()
    private val cards=linkedMapOf<String,Card>()
    private var shelves=emptyList<HomeScreen.Shelf>()
    private var footerMore: (() -> Unit)?=null
    private var moreButton: View?=null
    private var lastFocus=""
    private var lastRow: String?=null
    private var restoring=false
    private var revision=0
    val refresh=label("正在更新热门…",14f,TvStyle.muted).apply { id=View.generateViewId(); tag="home:refresh"; isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER; minHeight=dp(44); setPadding(dp(16),dp(10),dp(16),dp(10)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f); setTextColor(if(f) TvStyle.text else TvStyle.muted); if(f) lastFocus=tag.toString() } }
    init {
        orientation=VERTICAL
        val artworkWidth=(resources.displayMetrics.widthPixels*.38f).toInt()
        hero.addView(heroImage,FrameLayout.LayoutParams(artworkWidth,-1,Gravity.END))
        hero.addView(View(context).apply {
            background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(TvStyle.surface,TvStyle.surface,Color.TRANSPARENT))
        },FrameLayout.LayoutParams(-1,-1))
        val information=LinearLayout(context).apply {
            orientation=VERTICAL; gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(22),dp(10),0,dp(10))
            addView(heroEyebrow)
            addView(heroTitle,LayoutParams(-1,-2).apply { topMargin=dp(6) })
            addView(heroMeta,LayoutParams(-1,-2).apply { topMargin=dp(6) })
            addView(heroAction,LayoutParams(-1,-2).apply { topMargin=dp(10) })
        }
        hero.addView(information,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*.54f).toInt(),-1))
        scroll.addView(contents)
        addView(scroll,LayoutParams(-1,0,1f))
    }

    private fun showSelection(entry: HomeScreen.Entry,shelfTitle: String) {
        val previousCover=heroEntry?.series?.cover
        heroEntry=entry
        heroEyebrow.text=shelfTitle
        heroTitle.text=entry.series.title
        heroMeta.text=listOf(entry.label,entry.series.badge,entry.series.tags)
            .flatMap { it.split('·') }.map(String::trim)
            .filter { it.isNotBlank() && !it.all(Char::isDigit) }.distinct().joinToString("  ·  ")
        heroAction.text=if(entry.resume) "确认继续观看   ·   长按管理片单" else "确认查看详情   ·   长按管理片单"
        if(previousCover!=entry.series.cover) heroImage.setImageDrawable(null)
        if(heroImage.isAttachedToWindow) loadCover(heroImage,entry.series.cover)
        selected(entry.series,entry.label)
    }

    fun capturePosition(): HomeScreen.Position {
        val key=findFocus()?.tag?.toString()?.takeIf { it in cards || it=="home:refresh" || it=="home:more" } ?: lastFocus
        val row=rows.indexOfFirst { item -> item.cards.any { it.key==key } }
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==key } ?: 0
        return HomeScreen.Position(key,rows.getOrNull(row)?.key,column.coerceAtLeast(0),row.coerceAtLeast(0),scroll.scrollY,rows.associate { it.key to it.horizontal.scrollX })
    }

    /** Rebuild only this bounded shelf area, preserving the old card or its nearest neighbour. */
    fun updateShelves(next: List<HomeScreen.Shelf>,requestFocus: Boolean=hasFocus()) {
        val position=capturePosition()
        render(next,position.focus,requestFocus,footerMore,position)
    }

    /** Update an existing card without replacing views or moving focus. Structural changes use updateShelves. */
    fun updateEntry(shelfKey: String,entry: HomeScreen.Entry): Boolean {
        val card=cards["$shelfKey:${entry.series.id}"] ?: return false
        if(card.entry?.series?.cover!=entry.series.cover) { card.image?.setImageDrawable(null) }
        card.entry=entry; card.title.text=entry.series.title; card.label.text=entry.label
        card.progress?.apply { progress=entry.progress ?: 0; visibility=if(entry.progress==null) INVISIBLE else VISIBLE }
        card.view.contentDescription="${shelves.firstOrNull { it.key==shelfKey }?.title.orEmpty()}，${entry.series.title}，${entry.label}"
        shelves=shelves.map { shelf -> if(shelf.key==shelfKey) shelf.copy(entries=shelf.entries.map { if(it.series.id==entry.series.id) entry else it }) else shelf }
        if(lastFocus==card.key) refreshSelection()
        loadNearbySelection()
        return true
    }

    fun refreshSelection() {
        val card=cards[lastFocus] ?: return
        card.entry?.let { entry -> showSelection(entry,shelves.firstOrNull { "${it.key}:${entry.series.id}"==card.key }?.title.orEmpty()) }
    }

    /** Retry visible cover requests after the owner cancels work for a page or lifecycle change. */
    fun reloadArtwork() {
        heroEntry?.let { loadCover(heroImage,it.series.cover) }
        loadNearbySelection()
    }

    fun render(shelves: List<HomeScreen.Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: HomeScreen.Position?=null) {
        val previous=position ?: if(rows.isNotEmpty()) capturePosition() else null
        this.shelves=shelves; footerMore=more; revision++; val ticket=revision; restoring=true
        contents.removeAllViews(); rows.clear(); cards.clear(); moreButton=null; lastRow=null
        val display=shelves.filter { it.entries.isNotEmpty() || it.more!=null }
        // 预览跟随卡片焦点，不额外增加遥控器需要经过的焦点层级。
        val heroHeight=(resources.displayMetrics.heightPixels*.22f).toInt().coerceIn(dp(132),dp(176))
        contents.addView(hero,LayoutParams(-1,heroHeight).apply { bottomMargin=dp(18) })
        hero.visibility=if(display.isEmpty()) GONE else VISIBLE
        if(display.isEmpty()) { heroEntry=null; heroImage.setImageDrawable(null) }
        display.forEach { shelf ->
            val block=LinearLayout(context).apply { orientation=VERTICAL }
            val heading=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(2),dp(4),dp(2),dp(9)) }
            heading.addView(label(shelf.title,18f).apply { setTypeface(null,Typeface.BOLD) },LayoutParams(-2,-2))
            heading.addView(label("${shelf.entries.size} 部",12f,TvStyle.muted),LayoutParams(0,-2,1f).apply { leftMargin=dp(10) })
            heading.addView(label(if(shelf.key=="resume") "确认继续观看" else "左右选剧",12f,TvStyle.muted))
            block.addView(heading)
            val horizontal=HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false; setPadding(dp(2),dp(2),dp(2),dp(2)) }
            val line=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.TOP }
            horizontal.addView(line); block.addView(horizontal)
            val rowCards=mutableListOf<Card>()
            fun addCard(entry: HomeScreen.Entry?,moreAction: (() -> Unit)?=null) {
                val resumeShelf=shelf.key=="resume"
                val columns=if(resumeShelf) 4.2f else 7f
                val width=((resources.displayMetrics.widthPixels*.9f-dp(16)*(columns-1))/columns).toInt().coerceAtLeast(dp(96))
                val key=if(entry!=null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
                val view=LinearLayout(context).apply { orientation=VERTICAL; id=View.generateViewId(); tag=key; isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(4),dp(4),dp(4),dp(8)); background=shape(false); clipChildren=false }
                val image=if(entry!=null) ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO } else null
                // 接着看用宽卡突出续播，发现剧架保留竖版海报比例。
                val artwork=object: FrameLayout(context) {
                    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                        val w=MeasureSpec.getSize(widthMeasureSpec)
                        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((w/(if(resumeShelf) 1.65f else TvStyle.POSTER_ASPECT)).toInt(),MeasureSpec.EXACTLY))
                    }
                }
                artwork.background=TvStyle.shape(context,TvStyle.surface,radius=8)
                artwork.clipToOutline=true
                artwork.addView(image ?: label("›",36f).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
                view.addView(artwork,LayoutParams(-1,-2))
                val title=label(entry?.series?.title ?: "查看全部",15f).apply { gravity=Gravity.TOP; includeFontPadding=true; minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) }
                val badge=label(entry?.label ?: shelf.title,12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) }
                val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=entry?.progress ?: 0; visibility=if(entry?.progress==null) INVISIBLE else VISIBLE; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(TvStyle.outline) }
                view.addView(title); view.addView(badge); view.addView(progress,LayoutParams(-1,dp(3)).apply { topMargin=dp(4) })
                val card=Card(key,view,image,title,badge,progress,entry)
                view.contentDescription=entry?.let { "${shelf.title}，${it.series.title}，${it.label}" } ?: "查看全部${shelf.title}"
                view.setOnClickListener { lastFocus=key; remember(key); card.entry?.let { open(it.series,it.resume) } ?: moreAction?.invoke() }
                if(entry!=null) {
                    view.setOnLongClickListener { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) }; true }
                    view.setOnKeyListener { _,code,event -> if(code==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) } }; true } else false }
                }
                view.setOnFocusChangeListener { _,focused ->
                    view.background=shape(focused)
                    title.setTextColor(if(focused) accent else TvStyle.text)
                    badge.setTextColor(if(focused) TvStyle.text else TvStyle.muted)
                    if(focused) {
                        lastFocus=key; remember(key); card.entry?.let { entry -> showSelection(entry,shelf.title) }
                        if(!restoring) {
                            loadNearbySelection()
                            if(lastRow!=shelf.key) scroll.post { if(revision==ticket && view.hasFocus()) reveal(block) }
                        }
                        lastRow=shelf.key
                    }
                }
                line.addView(view,LayoutParams(width,-1).apply { rightMargin=dp(16) }); rowCards+=card; cards[key]=card
            }
            shelf.entries.forEach { addCard(it) }
            shelf.more?.let { action -> addCard(null,action) }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=dp(22) }); rows+=Row(shelf.key,block,horizontal,rowCards)
        }
        if(cards.isEmpty()) contents.addView(label("还没有本机记录，热门剧加载后可选择观看。",15f).apply { setPadding(0,dp(16),0,dp(16)) })
        contents.addView(refresh,LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val footer=mutableListOf<View>(refresh)
        if(more!=null) {
            val next=label("更多热门  ›",14f,TvStyle.muted).apply { id=View.generateViewId(); tag="home:more"; isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER; minHeight=dp(44); setPadding(dp(16),dp(10),dp(16),dp(10)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f); setTextColor(if(f) TvStyle.text else TvStyle.muted); if(f) lastFocus=tag.toString() }; setOnClickListener { more() } }
            contents.addView(next,LayoutParams(-1,-2).apply { topMargin=dp(8) }); footer+=next; moreButton=next
        }
        // 行列目标统一由 FocusGrid 计算，避界规则不再散落在各处；越出首/末行交给顶部导航与页脚。
        val grid=FocusGrid(rows.map { it.cards.size })
        rows.forEachIndexed { i,row -> row.cards.forEachIndexed { j,card ->
            card.view.nextFocusLeftId=row.cards[grid.left(i,j).col].view.id
            card.view.nextFocusRightId=row.cards[grid.right(i,j).col].view.id
            card.view.nextFocusUpId=grid.up(i,j)?.let { rows[it.row].cards[it.col].view.id } ?: top.id
            card.view.nextFocusDownId=grid.down(i,j)?.let { rows[it.row].cards[it.col].view.id } ?: refresh.id
        } }
        footer.forEachIndexed { i,v -> v.nextFocusUpId=if(i==0) rows.lastOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id else footer[i-1].id; v.nextFocusDownId=footer.getOrNull(i+1)?.id ?: v.id }
        top.nextFocusDownId=firstId()
        val preferred=focus.ifBlank { previous?.focus.orEmpty() }
        val target=target(preferred,previous)
        val initialCard=target.tag?.toString()?.let { cards[it] } ?: cards.values.firstOrNull()
        if(initialCard!=null) {
            initialCard.entry?.let { entry -> showSelection(entry,display.firstOrNull { "${it.key}:${entry.series.id}"==initialCard.key }?.title.orEmpty()) }
        }
        if(requestFocus) target.requestFocus()
        post layout@{
            if(revision!=ticket) return@layout
            alignTitleHeights()
            post restore@{
                if(revision!=ticket) return@restore
                if(previous!=null) restoreOffsets(previous)
                restoring=false
                if(requestFocus && previous==null) rows.firstOrNull { row -> row.cards.any { it.view===target } }?.let { revealSelection(it) }
                loadNearbySelection()
            }
        }
    }

    fun restorePosition(position: HomeScreen.Position,requestFocus: Boolean=true) {
        restoring=true
        if(requestFocus) target(position.focus,position).requestFocus()
        post { restoreOffsets(position); restoring=false; loadNearbySelection() }
    }

    private fun target(key: String,position: HomeScreen.Position?): View {
        if(key=="home:refresh") return refresh
        if(key=="home:more") moreButton?.let { return it }
        cards[key]?.let { return it.view }
        if(position!=null) {
            val row=rows.firstOrNull { it.key==position.shelf } ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: refresh
    }
    private fun restoreOffsets(position: HomeScreen.Position) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0,0) }
        scroll.scrollTo(0,position.vertical)
        // A removed row can leave the nearest surviving card outside the old viewport.
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }
    private fun revealSelection(row: Row) {
        row.cards.firstOrNull { it.view.hasFocus() }?.view?.let { card ->
            val horizontal=row.horizontal
            val left=horizontal.scrollX
            val right=left+horizontal.width-horizontal.paddingRight
            val target=when { card.left<left -> card.left; card.right>right -> card.right-horizontal.width+horizontal.paddingRight; else -> left }
            if(target!=left) horizontal.scrollTo(target.coerceAtLeast(0),0)
        }
        reveal(row.block,false)
    }
    private fun reveal(block: View,smooth: Boolean=true) {
        val showHero=rows.firstOrNull()?.block===block && block.bottom<=scroll.height
        val target=if(showHero) 0 else if(smooth) block.top else when { block.top<scroll.scrollY -> block.top; block.bottom>scroll.scrollY+scroll.height -> if(block.height>=scroll.height) block.top else block.bottom-scroll.height; else -> scroll.scrollY }.coerceAtLeast(0)
        if(target!=scroll.scrollY) { if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target) }
    }
    private fun loadNearbySelection() {
        if(restoring) return
        val row=rows.indexOfFirst { it.cards.any { card -> card.key==lastFocus } }.coerceAtLeast(0)
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items -> for(index in (column-1).coerceAtLeast(0)..(column+6).coerceAtMost(items.lastIndex)) requestCover(items[index]) }
        rows.getOrNull(row+1)?.cards?.take(6)?.forEach(::requestCover)
    }
    private fun requestCover(card: Card) {
        val image=card.image ?: return
        if(card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket=revision
        fun start() {
            card.pending=false
            if(ticket!=revision || !image.isAttachedToWindow || image.width<=0 || image.height<=0) return
            loadCover(image,card.entry!!.series.cover)
        }
        if(image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending=true
            image.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                    image.removeOnLayoutChangeListener(this); start()
                }
            })
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { reloadArtwork() } }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); if(w!=oldw || h!=oldh) post { alignTitleHeights(); loadNearbySelection() } }
    private fun alignTitleHeights() {
        rows.forEach { row ->
            // 部分机型单行/两行标题的实测行高不一致（minLines 保底失效），统一为行内最高标题，保证集数与进度条对齐
            val titleHeight=row.cards.maxOf { item -> item.title.measuredHeight }
            if(titleHeight>0) row.cards.forEach { card -> if(card.title.layoutParams.height!=titleHeight) card.title.layoutParams=card.title.layoutParams.apply { height=titleHeight } }
        }
    }
    fun firstId()=rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: refresh.id
}

/** Bounded horizontal shelves with stable D-pad selection and per-row scroll positions. */
private class LegacyHomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView,String)->Unit,
    private val selected: (Series,String)->Unit,
    private val open: (Series,Boolean)->Unit,
    private val actions: (Series,View)->Unit,
    private val remember: (String)->Unit
): LinearLayout(context) {
    private data class Card(val key: String,val view: LinearLayout,val image: ImageView?,val title: TextView,val label: TextView,val progress: ProgressBar?,var entry: HomeScreen.Entry?,var pending: Boolean=false)
    private data class Row(val key: String,val block: LinearLayout,val horizontal: HorizontalScrollView,val cards: List<Card>)
    private val accent=TvStyle.accent
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun shape(focus: Boolean)=TvStyle.card(context,focus)
    private fun label(value: String,size: Float,color: Int=TvStyle.text)=TextView(context).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private val scroll=ScrollView(context).apply { isVerticalScrollBarEnabled=false; clipToPadding=false }
    private val contents=LinearLayout(context).apply { orientation=VERTICAL }
    private val rows=mutableListOf<Row>()
    private val cards=linkedMapOf<String,Card>()
    private var shelves=emptyList<HomeScreen.Shelf>()
    private var footerMore: (() -> Unit)?=null
    private var moreButton: View?=null
    private var lastFocus=""
    private var lastRow: String?=null
    private var restoring=false
    private var revision=0
    val refresh=label("正在更新热门…",14f,TvStyle.muted).apply { id=View.generateViewId(); tag="home:refresh"; isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER; minHeight=dp(44); setPadding(dp(16),dp(10),dp(16),dp(10)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f); setTextColor(if(f) TvStyle.text else TvStyle.muted); if(f) lastFocus=tag.toString() } }
    init { orientation=VERTICAL; scroll.addView(contents); addView(scroll,LayoutParams(-1,0,1f)) }

    fun capturePosition(): HomeScreen.Position {
        val key=findFocus()?.tag?.toString()?.takeIf { it in cards || it=="home:refresh" || it=="home:more" } ?: lastFocus
        val row=rows.indexOfFirst { item -> item.cards.any { it.key==key } }
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==key } ?: 0
        return HomeScreen.Position(key,rows.getOrNull(row)?.key,column.coerceAtLeast(0),row.coerceAtLeast(0),scroll.scrollY,rows.associate { it.key to it.horizontal.scrollX })
    }

    /** Rebuild only this bounded shelf area, preserving the old card or its nearest neighbour. */
    fun updateShelves(next: List<HomeScreen.Shelf>,requestFocus: Boolean=hasFocus()) {
        val position=capturePosition()
        render(next,position.focus,requestFocus,footerMore,position)
    }

    /** Update an existing card without replacing views or moving focus. Structural changes use updateShelves. */
    fun updateEntry(shelfKey: String,entry: HomeScreen.Entry): Boolean {
        val card=cards["$shelfKey:${entry.series.id}"] ?: return false
        if(card.entry?.series?.cover!=entry.series.cover) { card.image?.setImageDrawable(null) }
        card.entry=entry; card.title.text=entry.series.title; card.label.text=entry.label
        card.progress?.apply { progress=entry.progress ?: 0; visibility=if(entry.progress==null) INVISIBLE else VISIBLE }
        card.view.contentDescription="${shelves.firstOrNull { it.key==shelfKey }?.title.orEmpty()}，${entry.series.title}，${entry.label}"
        shelves=shelves.map { shelf -> if(shelf.key==shelfKey) shelf.copy(entries=shelf.entries.map { if(it.series.id==entry.series.id) entry else it }) else shelf }
        if(lastFocus==card.key) refreshSelection()
        loadNearbySelection()
        return true
    }

    fun refreshSelection() {
        cards[lastFocus]?.entry?.let { entry ->
            selected(entry.series,entry.label)
        }
    }

    /** Retry visible cover requests after the owner cancels work for a page or lifecycle change. */
    fun reloadArtwork() {
        loadNearbySelection()
    }

    fun render(shelves: List<HomeScreen.Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: HomeScreen.Position?=null) {
        val previous=position ?: if(rows.isNotEmpty()) capturePosition() else null
        this.shelves=shelves; footerMore=more; revision++; val ticket=revision; restoring=true
        contents.removeAllViews(); rows.clear(); cards.clear(); moreButton=null; lastRow=null
        val display=shelves.filter { it.entries.isNotEmpty() || it.more!=null }
        display.forEach { shelf ->
            val block=LinearLayout(context).apply { orientation=VERTICAL }
            val heading=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(2),dp(4),dp(2),dp(9)) }
            heading.addView(View(context).apply { background=TvStyle.shape(context,accent,radius=2) },LayoutParams(dp(3),dp(14)).apply { rightMargin=dp(8) })
            heading.addView(label(shelf.title,16f).apply { setTypeface(null,Typeface.BOLD) },LayoutParams(0,-2,1f))
            heading.addView(label("左右选剧  ·  上下切换分区",10f,TvStyle.muted))
            block.addView(heading)
            val horizontal=HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false; setPadding(dp(2),dp(2),dp(2),dp(2)) }
            val line=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.TOP }
            horizontal.addView(line); block.addView(horizontal)
            val rowCards=mutableListOf<Card>()
            fun addCard(entry: HomeScreen.Entry?,moreAction: (() -> Unit)?=null) {
                val width=((resources.displayMetrics.widthPixels*.9f-dp(70))/6).toInt().coerceAtLeast(dp(100))
                val key=if(entry!=null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
                val view=LinearLayout(context).apply { orientation=VERTICAL; id=View.generateViewId(); tag=key; isFocusable=true; isFocusableInTouchMode=true; setPadding(dp(4),dp(4),dp(4),dp(8)); background=shape(false) }
                val image=if(entry!=null) ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO } else null
                // 与排行榜一致：按实际宽度固定海报比例，横屏高度不足时由首页滚动承载。
                val artwork=object: FrameLayout(context) {
                    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                        val w=MeasureSpec.getSize(widthMeasureSpec)
                        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((w/TvStyle.POSTER_ASPECT).toInt(),MeasureSpec.EXACTLY))
                    }
                }
                artwork.addView(image ?: label("›",36f).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
                view.addView(artwork,LayoutParams(-1,-2))
                val title=label(entry?.series?.title ?: "查看全部",14f).apply { gravity=Gravity.TOP; includeFontPadding=true; minLines=2; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(5),dp(3),0) }
                val badge=label(entry?.label ?: shelf.title,12f,TvStyle.muted).apply { minLines=1; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(2),0,0) }
                val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=entry?.progress ?: 0; visibility=if(entry?.progress==null) INVISIBLE else VISIBLE; progressTintList=android.content.res.ColorStateList.valueOf(accent) }
                view.addView(title); view.addView(badge); view.addView(progress,LayoutParams(-1,dp(3)).apply { topMargin=dp(4) })
                val card=Card(key,view,image,title,badge,progress,entry)
                view.contentDescription=entry?.let { "${shelf.title}，${it.series.title}，${it.label}" } ?: "查看全部${shelf.title}"
                view.setOnClickListener { lastFocus=key; remember(key); card.entry?.let { open(it.series,it.resume) } ?: moreAction?.invoke() }
                if(entry!=null) {
                    view.setOnLongClickListener { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) }; true }
                    view.setOnKeyListener { _,code,event -> if(code==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { lastFocus=key; remember(key); card.entry?.let { actions(it.series,view) } }; true } else false }
                }
                view.setOnFocusChangeListener { _,focused ->
                    view.background=shape(focused)
                    if(focused) {
                        lastFocus=key; remember(key); card.entry?.let { entry -> selected(entry.series,entry.label) }
                        if(!restoring) {
                            loadNearbySelection()
                            if(lastRow!=shelf.key) scroll.post { if(revision==ticket && view.hasFocus()) reveal(block) }
                        }
                        lastRow=shelf.key
                    }
                }
                line.addView(view,LayoutParams(width,-1).apply { rightMargin=dp(14) }); rowCards+=card; cards[key]=card
            }
            shelf.entries.forEach { addCard(it) }
            shelf.more?.let { action -> addCard(null,action) }
            contents.addView(block,LayoutParams(-1,-2).apply { bottomMargin=dp(16) }); rows+=Row(shelf.key,block,horizontal,rowCards)
        }
        if(cards.isEmpty()) contents.addView(label("还没有本机记录，热门剧加载后可选择观看。",15f).apply { setPadding(0,dp(16),0,dp(16)) })
        contents.addView(refresh,LayoutParams(-1,-2).apply { topMargin=dp(10) })
        val footer=mutableListOf<View>(refresh)
        if(more!=null) {
            val next=label("更多热门  ›",14f,TvStyle.muted).apply { id=View.generateViewId(); tag="home:more"; isFocusable=true; isFocusableInTouchMode=true; gravity=Gravity.CENTER; minHeight=dp(44); setPadding(dp(16),dp(10),dp(16),dp(10)); background=shape(false); setOnFocusChangeListener { _,f -> background=shape(f); setTextColor(if(f) TvStyle.text else TvStyle.muted); if(f) lastFocus=tag.toString() }; setOnClickListener { more() } }
            contents.addView(next,LayoutParams(-1,-2).apply { topMargin=dp(8) }); footer+=next; moreButton=next
        }
        // 行列目标统一由 FocusGrid 计算，避界规则不再散落在各处；越出首/末行交给顶部导航与页脚。
        val grid=FocusGrid(rows.map { it.cards.size })
        rows.forEachIndexed { i,row -> row.cards.forEachIndexed { j,card ->
            card.view.nextFocusLeftId=row.cards[grid.left(i,j).col].view.id
            card.view.nextFocusRightId=row.cards[grid.right(i,j).col].view.id
            card.view.nextFocusUpId=grid.up(i,j)?.let { rows[it.row].cards[it.col].view.id } ?: top.id
            card.view.nextFocusDownId=grid.down(i,j)?.let { rows[it.row].cards[it.col].view.id } ?: refresh.id
        } }
        footer.forEachIndexed { i,v -> v.nextFocusUpId=if(i==0) rows.lastOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id else footer[i-1].id; v.nextFocusDownId=footer.getOrNull(i+1)?.id ?: v.id }
        top.nextFocusDownId=firstId()
        val preferred=focus.ifBlank { previous?.focus.orEmpty() }
        val target=target(preferred,previous)
        if(requestFocus) target.requestFocus()
        post layout@{
            if(revision!=ticket) return@layout
            alignTitleHeights()
            post restore@{
                if(revision!=ticket) return@restore
                if(previous!=null) restoreOffsets(previous)
                restoring=false
                if(requestFocus && previous==null) rows.firstOrNull { row -> row.cards.any { it.view===target } }?.let { revealSelection(it) }
                loadNearbySelection()
            }
        }
    }

    fun restorePosition(position: HomeScreen.Position,requestFocus: Boolean=true) {
        restoring=true
        if(requestFocus) target(position.focus,position).requestFocus()
        post { restoreOffsets(position); restoring=false; loadNearbySelection() }
    }

    private fun target(key: String,position: HomeScreen.Position?): View {
        if(key=="home:refresh") return refresh
        if(key=="home:more") moreButton?.let { return it }
        cards[key]?.let { return it.view }
        if(position!=null) {
            val row=rows.firstOrNull { it.key==position.shelf } ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: refresh
    }
    private fun restoreOffsets(position: HomeScreen.Position) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0,0) }
        scroll.scrollTo(0,position.vertical)
        // A removed row can leave the nearest surviving card outside the old viewport.
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }
    private fun revealSelection(row: Row) {
        row.cards.firstOrNull { it.view.hasFocus() }?.view?.let { card ->
            val horizontal=row.horizontal
            val left=horizontal.scrollX
            val right=left+horizontal.width-horizontal.paddingRight
            val target=when { card.left<left -> card.left; card.right>right -> card.right-horizontal.width+horizontal.paddingRight; else -> left }
            if(target!=left) horizontal.scrollTo(target.coerceAtLeast(0),0)
        }
        reveal(row.block,false)
    }
    private fun reveal(block: View,smooth: Boolean=true) {
        val target=if(smooth) block.top else when { block.top<scroll.scrollY -> block.top; block.bottom>scroll.scrollY+scroll.height -> if(block.height>=scroll.height) block.top else block.bottom-scroll.height; else -> scroll.scrollY }.coerceAtLeast(0)
        if(target!=scroll.scrollY) { if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target) }
    }
    private fun loadNearbySelection() {
        if(restoring) return
        val row=rows.indexOfFirst { it.cards.any { card -> card.key==lastFocus } }.coerceAtLeast(0)
        val column=rows.getOrNull(row)?.cards?.indexOfFirst { it.key==lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items -> for(index in (column-1).coerceAtLeast(0)..(column+6).coerceAtMost(items.lastIndex)) requestCover(items[index]) }
        rows.getOrNull(row+1)?.cards?.take(6)?.forEach(::requestCover)
    }
    private fun requestCover(card: Card) {
        val image=card.image ?: return
        if(card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket=revision
        fun start() {
            card.pending=false
            if(ticket!=revision || !image.isAttachedToWindow || image.width<=0 || image.height<=0) return
            loadCover(image,card.entry!!.series.cover)
        }
        if(image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending=true
            image.addOnLayoutChangeListener(object: View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View,left: Int,top: Int,right: Int,bottom: Int,oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                    image.removeOnLayoutChangeListener(this); start()
                }
            })
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post { reloadArtwork() } }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); if(w!=oldw || h!=oldh) post { alignTitleHeights(); loadNearbySelection() } }
    private fun alignTitleHeights() {
        rows.forEach { row ->
            // 部分机型单行/两行标题的实测行高不一致（minLines 保底失效），统一为行内最高标题，保证集数与进度条对齐
            val titleHeight=row.cards.maxOf { item -> item.title.measuredHeight }
            if(titleHeight>0) row.cards.forEach { card -> if(card.title.layoutParams.height!=titleHeight) card.title.layoutParams=card.title.layoutParams.apply { height=titleHeight } }
        }
    }
    fun firstId()=rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: refresh.id
}

/** 首页版本偏好：新版与旧版共用同一套数据与控制器 API。 */
enum class HomeVersion(val label: String) {
    NEW("新版首页"),
    LEGACY("旧版首页");
    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.name == raw } ?: NEW
    }
}

/** 首页门面：按设置选择新版或旧版视觉，不改变收藏、历史与打开剧集的交互。 */
class HomeScreen(
    context: Context,
    top: View,
    loadCover: (ImageView,String)->Unit,
    selected: (Series,String)->Unit,
    open: (Series,Boolean)->Unit,
    actions: (Series,View)->Unit,
    remember: (String)->Unit,
    version: HomeVersion
): LinearLayout(context) {
    data class Entry(val series: Series,val label: String,val resume: Boolean=false,val progress: Int?=null)
    data class Shelf(val key: String,val title: String,val entries: List<Entry>,val more: (() -> Unit)?=null)
    data class Position(val focus: String,val shelf: String?,val column: Int,val row: Int,val vertical: Int,val horizontal: Map<String,Int>)
    private val content: LinearLayout = when(version) {
        HomeVersion.NEW -> NewHomeScreen(context,top,loadCover,selected,open,actions,remember)
        HomeVersion.LEGACY -> LegacyHomeScreen(context,top,loadCover,selected,open,actions,remember)
    }.apply { layoutParams=LayoutParams(-1,0,1f) }
    private val modern get()=content is NewHomeScreen
    fun style(): HomeVersion = if(modern) HomeVersion.NEW else HomeVersion.LEGACY
    val refresh get()=if(modern) (content as NewHomeScreen).refresh else (content as LegacyHomeScreen).refresh
    fun capturePosition()=if(modern) (content as NewHomeScreen).capturePosition() else (content as LegacyHomeScreen).capturePosition()
    fun updateShelves(next: List<Shelf>,requestFocus: Boolean=hasFocus()) {
        if(modern) (content as NewHomeScreen).updateShelves(next,requestFocus) else (content as LegacyHomeScreen).updateShelves(next,requestFocus)
    }
    fun updateEntry(shelfKey: String,entry: Entry): Boolean =
        if(modern) (content as NewHomeScreen).updateEntry(shelfKey,entry) else (content as LegacyHomeScreen).updateEntry(shelfKey,entry)
    fun refreshSelection() { if(modern) (content as NewHomeScreen).refreshSelection() else (content as LegacyHomeScreen).refreshSelection() }
    fun reloadArtwork() { if(modern) (content as NewHomeScreen).reloadArtwork() else (content as LegacyHomeScreen).reloadArtwork() }
    fun render(shelves: List<Shelf>,focus: String,requestFocus: Boolean,more: (() -> Unit)?,position: Position?=null) {
        if(modern) (content as NewHomeScreen).render(shelves,focus,requestFocus,more,position) else (content as LegacyHomeScreen).render(shelves,focus,requestFocus,more,position)
    }
    fun restorePosition(position: Position,requestFocus: Boolean=true) {
        if(modern) (content as NewHomeScreen).restorePosition(position,requestFocus) else (content as LegacyHomeScreen).restorePosition(position,requestFocus)
    }
    fun firstId()=if(modern) (content as NewHomeScreen).firstId() else (content as LegacyHomeScreen).firstId()
    init { orientation=VERTICAL; addView(content) }
}
