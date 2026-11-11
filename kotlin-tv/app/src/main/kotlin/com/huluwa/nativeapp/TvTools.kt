// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.EpisodeTarget
import com.hongguotv.core.PhoneForm
import com.hongguotv.core.PhoneInputServer
import com.hongguotv.core.QuickTransferServer
import com.hongguotv.core.TransferKind
import com.hongguotv.core.TransferRules
import com.hongguotv.core.LibraryTransferServer
import com.hongguotv.core.BackdropTransferServer
import com.hongguotv.core.BackupData
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.Executors

/** Native modal controls kept separate from catalog/player navigation. */
class TvTools(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    /** 当前可用的局域网 IPv4：只认 Wi-Fi / 有线，手机必须和电视在同一网段才能扫码。 */
    fun lanAddress(): String? {
        val connectivity=activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        if(Build.VERSION.SDK_INT>=23) {
            val network=connectivity.activeNetwork ?: return null
            val capabilities=connectivity.getNetworkCapabilities(network) ?: return null
            if(!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
            return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }
        // API 21/22 没有 Network API，改为直接枚举网卡上的局域网地址
        return runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
    private fun freeBytes()=runCatching { android.os.StatFs(activity.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    /** 快传落盘目录：都在 filesDir 下，卸载应用时随应用数据一起清除。 */
    private fun quickDirectory(kind: TransferKind)=File(activity.filesDir,TransferRules.directoryName(kind))
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(8),dp(4),dp(8),dp(4)) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(4),dp(16),dp(8)) }
    private fun open(next: AlertDialog,anchor: View?,onDismiss: (()->Unit)?=null) {
        dialog?.dismiss(); dialog=next
        next.setOnDismissListener { if(dialog===next) { phone?.close(); phone=null; dialog=null }; if(anchor?.isAttachedToWindow==true) anchor.requestFocus(); onDismiss?.invoke() }
        next.show()
    }
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); worker.shutdownNow() }
    fun info(title: String,message: String,anchor: View?) = open(TvDialog.info(activity,title,message),anchor)
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        open(TvDialog.confirm(activity,title,message,action),anchor)
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={},focusIndex: Int=-1,anchorToButton: Boolean=false) {
        // anchorToButton：弹窗悬浮到 anchor 按钮左侧（题材这类筛选器）；默认仍屏幕居中
        val next=TvDialog.choose(activity,title,labels,onChoice,extra,onExtra,focusIndex,anchorView=if(anchorToButton) anchor else null)
        open(next,anchor)
    }
    fun episodePicker(count: Int,current: Int,anchor: View?,select: (Int)->Unit) {
        val content=column()
        var digits=(current+1).toString(); var replace=true
        val value=label(digits,28f).apply { gravity=Gravity.CENTER; setTextColor(TvStyle.text) }; content.addView(value)
        val error=label("定位到该集，再按确认播放",14f).apply { setTextColor(TvStyle.muted) }; content.addView(error)
        fun digit(key: String) { digits=if(replace) key else (digits+key).take(6); replace=false; value.text=digits; error.text="定位到该集，再按确认播放" }
        fun erase() { digits=if(replace) "" else digits.dropLast(1); replace=false; value.text=digits.ifEmpty { "—" } }
        val buttons=mutableListOf<Button>()
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("清空","0","退格")).forEach { keys ->
            val row=LinearLayout(activity)
            keys.forEach { key ->
                val button=Button(activity).apply {
                    id=View.generateViewId(); text=key; minHeight=dp(48)
                    textSize=15f
                    background=GradientDrawable().apply {
                        setColor(TvStyle.surface)
                        cornerRadius=dp(12).toFloat()
                        setStroke(dp(1),TvStyle.outline)
                    }
                    setTextColor(TvStyle.text)
                    setOnFocusChangeListener { _,focused ->
                        background=GradientDrawable().apply {
                            setColor(if(focused) TvStyle.accent else TvStyle.surface)
                            cornerRadius=dp(12).toFloat()
                            setStroke(dp(1),if(focused) TvStyle.accent else TvStyle.outline)
                        }
                        setTextColor(if(focused) TvStyle.background else TvStyle.text)
                    }
                    setOnClickListener { when(key) { "清空"->{ digits="";replace=false;value.text="—" }; "退格"->erase(); else->digit(key) } }
                }
                buttons+=button
                row.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) })
            }
            content.addView(row)
        }
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 键盘按钮是 weight 横排，自然测宽会把按钮量成 0 宽，必须固定宽度
        TvDialog.styleWindow(activity,dialog,widthDp=300)
        dialog.setOnKeyListener { _,key,event ->
            when {
                key in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { if(event.action==KeyEvent.ACTION_DOWN) digit((key-KeyEvent.KEYCODE_0).toString()); true }
                key==KeyEvent.KEYCODE_DEL -> { if(event.action==KeyEvent.ACTION_DOWN) erase(); true }
                else -> false
            }
        }
        val actions=LinearLayout(activity).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.END
            setPadding(0,dp(14),0,0)
        }
        val cancel=TvDialog.capsuleButton(activity,"取消") { dialog.dismiss() }
        val locate=TvDialog.capsuleButton(activity,"定位") {
            val target=EpisodeTarget.parse(digits,count)
            if(target==null) error.text="请输入 1—$count 之间的集数"
            else { dialog.dismiss(); select(target) }
        }
        actions.addView(cancel,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
        actions.addView(locate,LinearLayout.LayoutParams(-2,-2))
        content.addView(actions)
        open(dialog,anchor)
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=buttons[if(i%3==0) i else i-1].id
            button.nextFocusRightId=buttons[if(i%3==2) i else i+1].id
            button.nextFocusUpId=buttons[if(i<3) i else i-3].id
            button.nextFocusDownId=if(i<9) buttons[i+3].id else locate.id
        }
        locate.nextFocusUpId=buttons[10].id; cancel.nextFocusUpId=buttons[9].id
        buttons.first().requestFocus()
    }
    private fun qr(url: String)=TvDialog.qrView(activity,url)
    /** 搜索剧名场景的手机输入（文案固定为剧名）。 */
    fun phoneInput(anchor: View?,submit: (String)->Unit)=phoneForm(anchor,PhoneForm.search()) { values->submit(values["query"].orEmpty()) }
    /** 通用手机填写页：表单内容由调用方定义，填写完成后回传全部字段值。 */
    fun phoneForm(anchor: View?,form: PhoneForm,onSubmit: (Map<String,String>)->Unit) {
        val address=lanAddress()
        if(address==null) { info("手机填写","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,form.heading))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：自然测宽量不出文字列（0 宽消失），必须固定宽度
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=PhoneInputServer(address,form,{ values -> main.post { if(this@TvTools.dialog===dialog) { dialog.dismiss(); onSubmit(values) } } },{ main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    content.addView(TvDialog.qrRow(activity, qr(session.url),
                        label("手机扫码打开，按提示填写，提交后发送到电视。\n仅限同一局域网，关闭即失效。",16f),
                        TvDialog.urlLabel(activity, session.url)))
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启手机填写，请检查网络后重试。" } }
        }
    }
    /** 收藏与观看记录的手机扫码备份及恢复。 */
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val address=lanAddress()
        if(address==null) { info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,"手机备份与恢复"))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：必须固定宽度（同 phoneForm）
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(this@TvTools.dialog!==dialog) return@post
                    dialog.dismiss()
                    val preview=column()
                    preview.addView(TvDialog.title(activity,"确认恢复本机记录"))
                    preview.addView(label("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后看、${data.hidden.size} 部已隐藏推荐。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。",16f))
                    val settings=CheckBox(activity).apply { text="同时恢复播放设置和内容分类"; isChecked=false; setTextColor(TvStyle.text) }; preview.addView(settings)
                    val confirmDialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,preview)).create()
                    TvDialog.styleWindow(activity,confirmDialog)
                    val actions=LinearLayout(activity).apply {
                        orientation=LinearLayout.HORIZONTAL
                        gravity=Gravity.END
                        setPadding(0,dp(14),0,0)
                    }
                    val cancel=TvDialog.capsuleButton(activity,"取消") { confirmDialog.dismiss() }
                    val ok=TvDialog.capsuleButton(activity,"合并恢复") { confirmDialog.dismiss(); restore(data,settings.isChecked) }
                    actions.addView(cancel,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(12) })
                    actions.addView(ok,LinearLayout.LayoutParams(-2,-2))
                    preview.addView(actions)
                    open(confirmDialog,anchor); cancel.requestFocus()
                } },{ main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    content.addView(TvDialog.qrRow(activity, qr(session.url),
                        label("扫码用浏览器下载备份，或选择备份文件恢复。\n仅限同一局域网，恢复需在电视确认。",16f),
                        TvDialog.urlLabel(activity, session.url)))
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启备份传输，请检查网络后重试。" } }
        }
    }
    fun backdropTransfer(anchor: View?,onImage: (ByteArray,String)->Unit) {
        val address=lanAddress()
        if(address==null) { info("自定义背景","请让电视和手机连接同一无线网后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,"自定义背景"))
        val status=label("正在生成二维码…"); content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：必须固定宽度（同 phoneForm）
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        open(dialog,anchor)
        worker.execute {
            try {
                val session=BackdropTransferServer(address,{ bytes,ext -> main.post { if(this@TvTools.dialog===dialog) dialog.dismiss(); onImage(bytes,ext) } },
                    { main.post { if(this@TvTools.dialog===dialog) { content.removeAllViews(); content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(this@TvTools.dialog!==dialog) { session.close(); return@post }
                    phone=session; content.removeAllViews()
                    content.addView(TvDialog.qrRow(activity, qr(session.url),
                        label("扫码或打开下方地址，上传图片设为电视背景。\n手机与电视需同一无线网。",16f),
                        TvDialog.urlLabel(activity, session.url)))
                }
            } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启图片上传，请检查网络后重试。" } }
        }
    }
    /** 手机快传：扫码直传文件。APK 收到后交给调用方引导安装，其余文件落私有目录。 */
    fun quickTransfer(anchor: View?,onReceived: (File,TransferKind)->Unit) {
        val address=lanAddress()
        if(address==null) { info("手机快传","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return }
        val content=column(); content.addView(TvDialog.title(activity,"手机快传"))
        val status=label("正在生成二维码…").apply { isFocusable=true }; content.addView(status)
        val dialog=AlertDialog.Builder(activity).setView(TvDialog.card(activity,content)).create()
        // 二维码 + weight 文字列：必须固定宽度（同 phoneForm）
        TvDialog.styleWindow(activity,dialog,widthDp=440)
        var views: QuickViews?=null
        var receivedFiles=0
        var session: QuickTransferServer?=null
        var countdown: TextView?=null
        val sessionStart=java.util.concurrent.atomic.AtomicLong(0)
        val TTL_MS=600_000L
        val spaceLabel={ "电视可用 ${TransferRules.humanBytes(freeBytes())}  ·  单个文件不超过 ${TransferRules.humanBytes(TransferRules.MAX_BYTES)}" }
        val countdownTick=object: Runnable {
            override fun run() {
                val cd=countdown ?: return
                if(this@TvTools.dialog!==dialog) return
                val elapsed=(System.nanoTime()-sessionStart.get())/1_000_000L
                val remain=(TTL_MS-elapsed).coerceAtLeast(0)
                val mm=remain/60_000; val ss=(remain%60_000)/1000
                cd.text=if(remain>0) "二维码有效期 · 剩余 %02d:%02d".format(mm,ss) else "二维码已过期，请刷新"
                cd.setTextColor(if(remain>0) TvStyle.accent else TvStyle.muted)
                if(remain>0) main.postDelayed(this,1000L)
            }
        }
        open(dialog,anchor) { main.removeCallbacks(countdownTick) }
        dialog.window?.decorView?.post { status.requestFocus() }
        fun startSession() {
            status.text="正在生成二维码…"
            worker.execute {
                try {
                    val s=QuickTransferServer(address,{ kind -> quickDirectory(kind) },{ freeBytes() },
                        { name,received,total,index,count -> main.post {
                            if(this@TvTools.dialog!==dialog) return@post
                            val current=views ?: return@post
                            current.name.text="$name  ·  第 $index/$count 个"
                            current.bar.progress=((received*100)/total.coerceAtLeast(1)).toInt()
                            current.detail.text="${TransferRules.humanBytes(received)} / ${TransferRules.humanBytes(total)}"
                        } },
                        { file,kind -> main.post {
                            if(this@TvTools.dialog===dialog) {
                                receivedFiles++
                                views?.let { current->
                                    current.received.text="本次已接收 $receivedFiles 个文件"
                                    current.name.text="${file.name} 已保存到电视"
                                    current.bar.progress=100
                                    current.space.text=spaceLabel()
                                }
                            }
                            onReceived(file,kind)
                        } },
                        { main.post { if(this@TvTools.dialog===dialog) { countdown?.text="二维码已过期，请刷新"; countdown?.setTextColor(TvStyle.muted) } } })
                    main.post {
                        if(this@TvTools.dialog!==dialog) { s.close(); return@post }
                        session?.close(); session=s; phone=s; sessionStart.set(System.nanoTime())
                        content.removeAllViews()
                        content.addView(TvDialog.qrRow(activity, qr(s.url),
                            label("手机扫码打开，选择文件发送到电视。\nAPK 会提示安装，视频图片存入电视。",16f),
                            TvDialog.urlLabel(activity, s.url)))
                        countdown=label("二维码有效期 · 剩余 10:00",13f).apply { setTextColor(TvStyle.accent); setPadding(0,dp(6),0,dp(2)) }; content.addView(countdown)
                        val space=label(spaceLabel(),13f).apply { setTextColor(TvStyle.muted) }; content.addView(space)
                        val name=label("等待手机发送…",15f); content.addView(name)
                        val bar=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                            max=100; progress=0
                            progressTintList=android.content.res.ColorStateList.valueOf(TvStyle.accent)
                            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(TvStyle.outline)
                        }
                        content.addView(bar,LinearLayout.LayoutParams(-1,dp(6)))
                        val detail=label("",13f).apply { setTextColor(TvStyle.muted) }; content.addView(detail)
                        val received=label("本次已接收 0 个文件",13f).apply { setTextColor(TvStyle.muted) }; content.addView(received)
                        val actions=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.END; setPadding(0,dp(12),0,0) }
                        val refresh=TvDialog.capsuleButton(activity,"刷新二维码") { startSession() }
                        val close=TvDialog.capsuleButton(activity,"完成") { dialog.dismiss() }
                        refresh.nextFocusRightId=close.id; close.nextFocusLeftId=refresh.id
                        actions.addView(refresh,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(8) })
                        actions.addView(close,LinearLayout.LayoutParams(-2,-2))
                        content.addView(actions)
                        views=QuickViews(name,bar,detail,received,space)
                        main.removeCallbacks(countdownTick); main.post(countdownTick)
                        dialog.window?.decorView?.post { close.requestFocus() }
                    }
                } catch(_: Exception) { main.post { if(this@TvTools.dialog===dialog) status.text="无法开启手机快传，请检查网络后重试。" } }
            }
        }
        startSession()
    }
    private class QuickViews(val name: TextView,val bar: ProgressBar,val detail: TextView,val received: TextView,val space: TextView)
}
