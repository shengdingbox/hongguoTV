// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 手机页面的系统栏安全区。
 *
 * targetSdk 35 起 Android 15 强制 edge-to-edge：窗口内容直接铺到状态栏和手势导航条底下，
 * 老的全屏标志不再能让页面自动避开它们。若不补偿，竖屏顶栏（品牌 + 搜索）会被状态栏的
 * 时钟/信号图标压住，底部导航会被手势条顶掉；键盘弹出时窗口也不会再自动 resize。
 *
 * 这里统一把「状态栏 + 刘海 + 导航条」以及键盘的高度回传给页面，由页面加到固定内边距上。
 * 播放页隐藏系统栏后状态栏 Insets 归零，但刘海（displayCutout）的避让高度仍然保留。
 */
object PhoneInsets {

    private val SAFE_TYPES =
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    /**
     * 监听 [view] 的 Insets。
     *
     * 回调可能被派发多次（旋转、系统栏显隐、键盘开合），调用方必须用「基础内边距 + 偏移」
     * 的写法保证幂等，不能在上一次结果上累加。
     */
    fun listen(view: View, onChange: (topInset: Int, bottomInset: Int, imeInset: Int) -> Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val safe = insets.getInsets(SAFE_TYPES)
            sideIndicator = maxOf(safe.left, safe.right)
            onChange(safe.top, safe.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        request(view)
    }

    /**
     * 最近一次派发的左右安全区（取较大的一边）。横屏播放页的刘海 / 挖孔在侧边，
     * 顶部栏与控制条要按它让位。用「最近一次 listen 回调的值」而不是重新查
     * getRootWindowInsets，是因为播放页在 Insets 回调里立刻就要排版，那一刻
     * 根窗口的 Insets 还不一定是最新的。没有监听者时返回 0。
     */
    var sideIndicator: Int = 0
        private set

    /** 视图可能在 addView 之前就设好监听，attach 时补一次派发。 */
    private fun request(view: View) {
        if (view.isAttachedToWindow) {
            ViewCompat.requestApplyInsets(view)
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = ViewCompat.requestApplyInsets(v)
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }
}
