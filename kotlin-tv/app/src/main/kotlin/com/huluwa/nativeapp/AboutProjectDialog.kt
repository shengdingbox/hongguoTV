// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 「关于本项目」弹框：展示作者与 GitHub 信息，手机竖屏与电视端共用同一份数据。
 * 仓库地址和问题反馈可以点击，交给系统浏览器打开；电视端用遥控器移到对应行按确认。
 */
object AboutProjectDialog {
    const val AUTHOR = "superhuluwa"
    const val REPOSITORY = "https://github.com/superhuluwa/hongguoTV"
    private const val SHORT_REPOSITORY = "superhuluwa/hongguoTV"
    private const val ISSUES = "$REPOSITORY/issues"

    /** [anchor] 只在电视端使用：弹框关闭后把焦点还给设置页里触发它的那一行。 */
    fun show(activity: Activity, anchor: View?) {
        if (PhoneDialog.shouldUse(activity)) {
            PhoneDialog.sheet(activity, "关于本项目", content = phoneContent(activity)).show()
            return
        }
        val (content, firstLink) = tvContent(activity)
        val dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
        // 首焦点放在第一条链接上；styleWindow 会接管 OnShowListener，所以走它的 onShown 回调
        TvDialog.styleWindow(activity, dialog, widthDp = 360) { firstLink.requestFocus() }
        dialog.setOnDismissListener { anchor?.takeIf { it.isAttachedToWindow }?.requestFocus() }
        dialog.show()
    }

    /** 打开外部链接：电视盒子常常没有浏览器，这里只提示，不中断当前页面。 */
    private fun open(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "设备上没有可用的浏览器，可手动访问：$url", Toast.LENGTH_LONG).show()
        }
    }

    /** 手机端：信息按设置页的卡片行展示，仓库与反馈两行可点击。 */
    private fun phoneContent(activity: Activity): LinearLayout {
        PortraitStyle.refresh(activity)
        val column = PhoneUi.column(activity)
        val group = PhoneUi.column(activity).apply {
            background = PortraitStyle.shape(activity, PhoneDialog.groupBackground(activity), radius = 14)
            clipToOutline = true
        }
        addPhoneRow(group, "作者", AUTHOR, null)
        addPhoneRow(group, "GitHub", SHORT_REPOSITORY, REPOSITORY)
        addPhoneRow(group, "问题反馈", "提交 Issue", ISSUES)
        addPhoneRow(group, "许可协议", "GPL-3.0-only", null)
        column.addView(group, LinearLayout.LayoutParams(-1, -2))
        column.addView(PhoneUi.text(activity,
            "本项目为非官方客户端，仅用于技术交流与学习，不提供、不存储、不传播任何视频内容；内容版权归原平台所有。",
            13f, PortraitStyle.muted).apply {
            setLineSpacing(PhoneUi.dp(activity, 3).toFloat(), 1f)
            setPadding(PhoneUi.dp(activity, 6), PhoneUi.dp(activity, 14), PhoneUi.dp(activity, 6), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return column
    }

    private fun addPhoneRow(parent: LinearLayout, label: String, value: String, link: String?) {
        val activity = parent.context as Activity
        if (parent.childCount > 0) parent.addView(View(activity).apply {
            setBackgroundColor(PortraitStyle.outline)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, 1).apply {
            leftMargin = PhoneUi.dp(activity, 16)
            rightMargin = PhoneUi.dp(activity, 16)
        })
        val row = PhoneUi.row(activity).apply {
            isBaselineAligned = false
            minimumHeight = PhoneUi.dp(activity, 56)
            setPadding(PhoneUi.dp(activity, 16), PhoneUi.dp(activity, 12), PhoneUi.dp(activity, 16), PhoneUi.dp(activity, 12))
            background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 0)
            isClickable = link != null
            isFocusable = link != null
            if (link != null) {
                contentDescription = "$label $value，点击用浏览器打开"
                setOnClickListener { open(activity, link) }
            }
        }
        row.addView(PhoneUi.text(activity, label, 16f, PortraitStyle.text),
            LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        row.addView(PhoneUi.text(activity, value, 15f,
            if (link != null) PortraitStyle.navigationSelected else PortraitStyle.secondaryText).apply {
            maxLines = 1
            maxWidth = PhoneUi.dp(activity, 180)
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = PhoneUi.dp(activity, 10) })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    /** 电视端：说明文字 + 两行可聚焦链接，遥控器上下移动、确认键打开。
     *  返回首行链接，供调用方在弹框显示后挂上初始焦点。 */
    private fun tvContent(activity: Activity): Pair<LinearLayout, TextView> {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TvDialog.title(activity, "关于本项目"))
        column.addView(TvDialog.body(activity,
            "非官方红果短剧、漫剧客户端，使用 Kotlin + Android 原生 View + Media3 开发，一个 APK 同时适配电视与手机。"))
        column.addView(TvDialog.body(activity, "作者　$AUTHOR"))
        column.addView(TvDialog.body(activity, "本项目仅用于技术交流与学习，内容版权归原平台所有。"))
        val links = listOf(
            "GitHub 仓库  ·  $SHORT_REPOSITORY" to REPOSITORY,
            "问题反馈  ·  提交 Issue" to ISSUES,
        ).map { (label, url) -> tvButton(activity, label) { open(activity, url) } }
        links.forEach { button ->
            column.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        column.addView(TvDialog.body(activity, "许可协议　GPL-3.0-only"))
        return column to links.first()
    }

    /** 电视端链接行：整行可聚焦，焦点时换 accent 行背景。 */
    private fun tvButton(activity: Activity, label: String, onClick: () -> Unit): TextView {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return TextView(activity).apply {
            text = label
            textSize = 15f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(46)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setTextColor(TvStyle.text)
            background = TvVisual.row(activity, false)
            isFocusable = true
            isFocusableInTouchMode = true
            // 与设置页的可聚焦行一致：只换背景，文字保持主题正文色
            setOnFocusChangeListener { _, focused ->
                background = TvVisual.row(activity, focused)
                invalidate()
            }
            setOnClickListener { onClick() }
        }
    }
}
