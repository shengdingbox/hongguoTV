// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat

/** 开心果 iOS 的 sheet、分组选项与绿色强调色，只用于手机竖屏。 */
object PhoneDialog {
    fun shouldUse(activity: Activity): Boolean = PhoneUi.shouldUse(activity)
    private fun dp(activity: Activity, value: Int) = PhoneUi.dp(activity, value)
    private fun lp(width: Int = -1, height: Int = -2) = LinearLayout.LayoutParams(width, height)
    private val sheetColor get() = if (PortraitStyle.isDark) Color.rgb(28, 28, 30) else Color.rgb(242, 242, 247)
    private val groupColor get() = if (PortraitStyle.isDark) Color.rgb(44, 44, 46) else Color.WHITE
    private val destructiveColor get() = if (PortraitStyle.isDark) Color.rgb(255, 105, 97) else Color.rgb(220, 49, 46)

    fun close(dialog: Dialog) = dialog.dismiss()

    /** 足够大的触摸按钮，不使用 TV 的焦点描边或横排胶囊。 */
    fun button(activity: Activity, label: String, primary: Boolean = false,
        destructive: Boolean = false, onClick: () -> Unit): TextView =
        PhoneUi.text(activity, label, 17f, when {
            destructive -> destructiveColor
            primary -> PortraitStyle.onAccent
            else -> PortraitStyle.navigationSelected
        }).apply {
            gravity = Gravity.CENTER
            minHeight = dp(activity, 50)
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
            setTypeface(null, if (primary) Typeface.BOLD else Typeface.NORMAL)
            background = PortraitStyle.selectable(activity, if (primary) PortraitStyle.accent else groupColor, radius = 14)
            isFocusable = true
            setOnClickListener { onClick() }
        }

    /**
     * 返回未显示的弹框，调用方仍可绑定原来的关闭与业务回调。
     * 标题固定，内容按剩余高度滚动；异步内容、键盘和系统栏变化都会重新参与测量。
     */
    fun sheet(activity: Activity, title: String, subtitle: String? = null, content: View): AlertDialog {
        PortraitStyle.refresh(activity)
        val dialog = object : AlertDialog(activity, R.style.PhoneAlertDialog) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                // 先让 AlertDialog 安装内容，再配置窗口，避免提前创建 decor 干扰系统初始化。
                window?.let { window ->
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.setBackgroundDrawableResource(android.R.color.transparent)
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = Color.TRANSPARENT
                    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.setDimAmount(.4f)
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                    window.setLayout(minOf(activity.resources.displayMetrics.widthPixels, dp(activity, 560)),
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                    window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                }
            }
        }
        var safeTop = 0
        val root = object : LinearLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val screenHeight = resources.displayMetrics.heightPixels
                val screenLimit = minOf((screenHeight * .9f).toInt(),
                    screenHeight - safeTop - dp(activity, 12)).coerceAtLeast(1)
                val parentLimit = View.MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: screenLimit
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
                    minOf(screenLimit, parentLimit), View.MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(sheetColor)
                val radius = dp(activity, 28).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
            setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 20))
        }
        val handleHost = FrameLayout(activity)
        handleHost.addView(View(activity).apply {
            background = PortraitStyle.shape(activity, PortraitStyle.muted, radius = 2)
            alpha = .4f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(activity, 36), dp(activity, 4), Gravity.CENTER))
        root.addView(handleHost, lp(-1, dp(activity, 24)))
        var dragStart = 0f
        handleHost.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { root.animate().cancel(); dragStart = event.rawY; true }
                MotionEvent.ACTION_MOVE -> { root.translationY = (event.rawY - dragStart).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP -> {
                    if (root.translationY > dp(activity, 64)) dialog.dismiss()
                    else root.animate().translationY(0f).setDuration(160).start()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { root.animate().translationY(0f).setDuration(160).start(); true }
                else -> false
            }
        }

        val header = PhoneUi.row(activity).apply { setPadding(0, dp(activity, 2), 0, dp(activity, 16)) }
        header.addView(PhoneUi.text(activity, title, 20f, PortraitStyle.text).apply {
            setTypeface(null, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        }, lp(0, -2).apply { weight = 1f; rightMargin = dp(activity, 8) })
        val dismiss = PhoneUi.text(activity, "✕", 16f, PortraitStyle.muted).apply {
            gravity = Gravity.CENTER
            contentDescription = "关闭"
            background = PortraitStyle.selectable(activity, groupColor, radius = 22)
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(dismiss, lp(dp(activity, 44), dp(activity, 44)))
        root.addView(header, lp())
        val scrollingContent = PhoneUi.column(activity)
        if (!subtitle.isNullOrBlank()) scrollingContent.addView(PhoneUi.text(activity, subtitle, 14f, PortraitStyle.muted).apply {
            setLineSpacing(dp(activity, 3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(activity, 16))
        }, lp())
        scrollingContent.addView(content, lp())
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(scrollingContent, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroll, lp().apply { weight = 1f })
        dialog.setView(root, 0, 0, 0, 0)
        dialog.setCanceledOnTouchOutside(true)
        PhoneInsets.listen(root) { top, bottom, ime ->
            if (safeTop != top) {
                safeTop = top
                root.requestLayout()
            }
            val padding = dp(activity, 20) + maxOf(bottom, ime)
            if (root.paddingBottom != padding) root.setPadding(dp(activity, 20), 0, dp(activity, 20), padding)
        }
        dialog.setOnShowListener {
            TvDialog.register(dialog)
            // 系统栏控制器只在窗口已经挂载后访问。
            dialog.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightNavigationBars = !PortraitStyle.isDark
                    isAppearanceLightStatusBars = !PortraitStyle.isDark
                }
            }
        }
        return dialog
    }

    /** 当前值以系统 Picker 的勾选展示，不保留电视文案中的“· 当前”。 */
    fun choose(activity: Activity, title: String, labels: List<String>, onChoice: (Int) -> Unit,
        extra: String? = null, onExtra: () -> Unit = {}, focusIndex: Int = -1,
        anchorView: View? = null): AlertDialog {
        PortraitStyle.refresh(activity)
        val content = PhoneUi.column(activity)
        val group = PhoneUi.column(activity).apply {
            background = PortraitStyle.shape(activity, groupColor, radius = 14)
            clipToOutline = true
        }
        lateinit var dialog: AlertDialog
        val rows = labels.mapIndexed { index, label ->
            val selected = index == focusIndex
            val clean = label.replace(Regex("\\s*·\\s*当前\\s*$"), "")
            PhoneUi.row(activity).apply {
                minimumHeight = dp(activity, 52)
                setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
                background = PortraitStyle.selectable(activity, Color.TRANSPARENT, radius = 0)
                isFocusable = true
                isSelected = selected
                contentDescription = clean + if (selected) "，已选择" else ""
                addView(PhoneUi.text(activity, clean, 17f, PortraitStyle.text), lp(0, -2).apply { weight = 1f })
                addView(PhoneUi.text(activity, if (selected) "✓" else "", 20f, PortraitStyle.navigationSelected).apply {
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, lp(dp(activity, 28), -2).apply { leftMargin = dp(activity, 12) })
                setOnClickListener { dialog.dismiss(); onChoice(index) }
            }
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) group.addView(View(activity).apply {
                setBackgroundColor(PortraitStyle.outline)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp(-1, 1).apply { leftMargin = dp(activity, 16) })
            group.addView(row, lp())
        }
        content.addView(group, lp())
        if (extra != null) content.addView(button(activity, extra) { onExtra() }, lp().apply { topMargin = dp(activity, 12) })
        content.addView(button(activity, "取消") { dialog.dismiss() }, lp().apply { topMargin = dp(activity, 12) })
        dialog = sheet(activity, title, content = content)
        if (focusIndex in rows.indices) rows[focusIndex].post {
            rows[focusIndex].requestRectangleOnScreen(android.graphics.Rect(0, 0, rows[focusIndex].width, rows[focusIndex].height), true)
        }
        return dialog
    }

    fun info(activity: Activity, title: String, message: String): AlertDialog {
        PortraitStyle.refresh(activity)
        val content = PhoneUi.column(activity)
        content.addView(messageView(activity, message), lp())
        lateinit var dialog: AlertDialog
        content.addView(button(activity, "知道了", primary = true) { dialog.dismiss() }, lp().apply { topMargin = dp(activity, 20) })
        dialog = sheet(activity, title, content = content)
        return dialog
    }

    fun confirm(activity: Activity, title: String, message: String, action: () -> Unit): AlertDialog {
        PortraitStyle.refresh(activity)
        val content = PhoneUi.column(activity)
        content.addView(messageView(activity, message), lp())
        lateinit var dialog: AlertDialog
        content.addView(button(activity, "确认", primary = true) { dialog.dismiss(); action() }, lp().apply { topMargin = dp(activity, 20) })
        content.addView(button(activity, "取消") { dialog.dismiss() }, lp().apply { topMargin = dp(activity, 10) })
        dialog = sheet(activity, title, content = content)
        return dialog
    }

    private fun messageView(activity: Activity, value: String) =
        PhoneUi.text(activity, value, 15f, PortraitStyle.secondaryText).apply {
            setLineSpacing(dp(activity, 4).toFloat(), 1f)
            setTextIsSelectable(true)
        }
}
