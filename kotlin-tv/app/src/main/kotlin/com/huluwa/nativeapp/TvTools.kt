// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.EpisodeTarget

/** Native modal controls kept separate from catalog/player navigation. */
class TvTools(private val activity: Activity) {
    private var dialog: AlertDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=17f)=TextView(activity).apply { text=value; textSize=size; setTextColor(TvStyle.text); setPadding(dp(8),dp(4),dp(8),dp(4)) }
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(4),dp(16),dp(8)) }
    private fun open(next: AlertDialog,anchor: View?,onDismiss: (()->Unit)?=null) {
        dialog?.dismiss(); dialog=next
        next.setOnDismissListener { if(dialog===next) { dialog=null }; if(anchor?.isAttachedToWindow==true) anchor.requestFocus(); onDismiss?.invoke() }
        next.show()
    }
    fun close() { dialog?.dismiss() }
    fun destroy() { close() }
    fun info(title: String,message: String,anchor: View?) = open(TvDialog.info(activity,title,message),anchor)
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        open(TvDialog.confirm(activity,title,message,action),anchor)
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={},focusIndex: Int=-1,anchorToButton: Boolean=false,message: String?=null) {
        // anchorToButton：弹窗悬浮到 anchor 按钮左侧（题材这类筛选器）；默认仍屏幕居中
        val next=TvDialog.choose(activity,title,labels,onChoice,extra,onExtra,focusIndex,anchorView=if(anchorToButton) anchor else null,message=message)
        open(next,anchor)
    }
    /** 算法检测随弹框关闭一起取消，避免页面离开后继续占用取址请求。 */
    fun algorithmPicker(anchor: View?,message: String?,resolveEpisode: ()->String,
        probe: (SigningSettings.Mode,String)->com.hongguotv.core.StreamInfo,
        onChoice: (SigningSettings.Mode)->Unit) {
        val picker=PlaybackAlgorithmDialog(activity,message,resolveEpisode,probe,onChoice)
        open(picker.dialog,anchor,picker::cancel)
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
}
