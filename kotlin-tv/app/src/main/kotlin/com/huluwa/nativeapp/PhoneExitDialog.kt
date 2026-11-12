// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import com.hongguotv.core.Series

/** 手机退出挽留：沿用竖屏海报和外观，推荐来源与退出行为由主页面提供。 */
object PhoneExitDialog {
    fun create(
        activity: Activity,
        items: List<Series>,
        fromUpdates: Boolean,
        badge: (Series) -> String,
        loadCover: (ImageView, String) -> Unit,
        onOpen: (Series) -> Unit,
        onExit: () -> Unit,
    ): AlertDialog {
        PortraitStyle.refresh(activity)
        fun dp(value: Int) = PhoneUi.dp(activity, value)
        val content = PhoneUi.column(activity)
        lateinit var dialog: AlertDialog
        if (items.isNotEmpty()) {
            val cards = PhoneUi.row(activity).apply {
                gravity = Gravity.TOP
                isBaselineAligned = false
            }
            items.take(3).forEachIndexed { index, item ->
                val card = PortraitUi.PosterCard(activity).apply {
                    title.text = item.title
                    title.maxLines = 2
                    title.minLines = 2
                    this.badge.text = badge(item)
                    background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 10)
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${item.title}，${badge(item)}，查看详情"
                    setOnClickListener { dialog.dismiss(); onOpen(item) }
                }
                loadCover(card.image, item.cover)
                cards.addView(card, PhoneUi.lp(0, -2).apply {
                    weight = 1f
                    if (index > 0) leftMargin = dp(10)
                })
            }
            // 少于三部时仍保持与页面一致的海报尺寸，避免单张海报被拉成整屏宽。
            repeat((3 - items.size).coerceAtLeast(0)) {
                cards.addView(View(activity), PhoneUi.lp(0, 0).apply { weight = 1f; leftMargin = dp(10) })
            }
            content.addView(cards, PhoneUi.lp().apply { bottomMargin = dp(24) })
        } else {
            val icon = ImageView(activity).apply {
                setImageResource(R.drawable.ic_phone_home)
                setColorFilter(PortraitStyle.accent)
                background = PortraitStyle.shape(activity, PortraitStyle.surface, radius = 24)
                setPadding(dp(18), dp(18), dp(18), dp(18))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            content.addView(icon, PhoneUi.lp(dp(80), dp(80)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
                bottomMargin = dp(24)
            })
        }
        content.addView(PhoneDialog.button(activity, "再看一会儿", primary = true) {
            dialog.dismiss()
        }, PhoneUi.lp())
        content.addView(PhoneDialog.button(activity, "退出不看了") {
            dialog.dismiss()
            onExit()
        }, PhoneUi.lp().apply { topMargin = dp(8) })
        val title = when {
            fromUpdates -> "收藏更新了，再看一会儿？"
            items.isNotEmpty() -> "好剧还在，留下来看看？"
            else -> "要离开红果了吗？"
        }
        val subtitle = when {
            fromUpdates -> "你收藏的短剧有新内容，点开继续看吧。"
            items.isNotEmpty() -> "为你挑了几部好剧，精彩故事还在继续。"
            else -> "收藏和观看进度会保留，下次回来接着看。"
        }
        dialog = PhoneDialog.sheet(activity, title, subtitle, content)
        return dialog
    }
}
