// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.RequestScope
import com.hongguotv.core.StreamInfo
import java.util.concurrent.Executors

/** 算法选择与并行取址检测共用一个弹框；检测结果就地显示，不改变用户选择。 */
internal class PlaybackAlgorithmDialog(
    private val activity: Activity,
    message: String?,
    private val resolveEpisode: () -> String,
    private val probe: (SigningSettings.Mode, String) -> StreamInfo,
    onChoice: (SigningSettings.Mode) -> Unit,
) {
    private val phone = PhoneDialog.shouldUse(activity)
    private val modes = SigningSettings.modes
    private val selected = SigningSettings.mode
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(modes.size) { action ->
        Thread(action, "playback-algorithm-check").apply { isDaemon = true }
    }
    private var running: RequestScope? = null
    private val results = mutableMapOf<SigningSettings.Mode, TextView>()
    private val summary: TextView
    private val checkButton: TextView
    lateinit var dialog: AlertDialog
        private set

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Float, color: Int) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color)
        includeFontPadding = false
    }
    private val textColor get() = if (phone) PortraitStyle.text else TvStyle.text
    private val mutedColor get() = if (phone) PortraitStyle.muted else TvStyle.muted

    init {
        if (phone) PortraitStyle.refresh(activity)
        summary = label("点击“检测算法”，比较同一分集的取址耗时。", 14f, mutedColor).apply {
            setPadding(0, 0, 0, dp(12))
        }
        val options = column()
        val rows = modes.map { mode ->
            column().apply {
                id = View.generateViewId()
                isFocusable = true; isClickable = true
                setPadding(dp(16), dp(10), dp(16), dp(10))
                if (phone) {
                    background = PortraitStyle.selectable(activity,
                        PhoneDialog.groupBackground(activity), radius = 14)
                } else {
                    fun paint(focused: Boolean) {
                        background = TvStyle.shape(activity,
                            if (focused) TvStyle.raised else if (mode == selected) TvStyle.selectedSurface else TvStyle.surface,
                            if (focused) TvStyle.accent else Color.TRANSPARENT)
                    }
                    paint(false)
                    setOnFocusChangeListener { _, focused -> paint(focused) }
                }
                addView(label(mode.label + if (mode == selected) "   ·   当前" else "", 16f, textColor))
                val result = label("尚未检测", 13f, mutedColor).apply {
                    setPadding(0, dp(4), 0, 0)
                    minLines = 2; maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.15f)
                }
                addView(result)
                results[mode] = result
                setOnClickListener { dialog.dismiss(); onChoice(mode) }
            }.also { row ->
                options.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        checkButton = if (phone) PhoneDialog.button(activity, "检测算法", primary = true) { startCheck() }
            else TvDialog.capsuleButton(activity, "检测算法") { startCheck() }
        val closeButton = if (phone) PhoneDialog.button(activity, "取消") { dialog.dismiss() }
            else TvDialog.capsuleButton(activity, "取消") { dialog.dismiss() }
        checkButton.id = View.generateViewId()
        closeButton.id = View.generateViewId()
        val actions = column()
        if (phone) {
            actions.addView(checkButton, LinearLayout.LayoutParams(-1, -2))
            actions.addView(closeButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            val content = column().apply { addView(summary); addView(options) }
            dialog = PhoneDialog.sheet(activity, "算法模式", subtitle = message, content = content, footer = actions)
        } else {
            actions.orientation = LinearLayout.HORIZONTAL
            actions.gravity = Gravity.END
            actions.setPadding(0, dp(12), 0, 0)
            actions.addView(checkButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
            actions.addView(closeButton, LinearLayout.LayoutParams(-2, -2))
            val content = column().apply {
                addView(TvDialog.title(activity, "算法模式"))
                if (!message.isNullOrBlank()) addView(TvDialog.body(activity, message))
                addView(summary)
                addView(ScrollView(activity).apply {
                    isVerticalScrollBarEnabled = false
                    addView(options)
                })
                addView(actions)
            }
            dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
            TvDialog.styleWindow(activity, dialog, widthDp = 420,
                capPx = (activity.resources.displayMetrics.heightPixels * .85f).toInt()) {
                rows[modes.indexOf(selected).coerceAtLeast(0)].requestFocus()
            }
            rows.forEachIndexed { index, row ->
                row.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: row.id
                row.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: checkButton.id
            }
            checkButton.nextFocusUpId = rows.last().id
            checkButton.nextFocusDownId = checkButton.id
            checkButton.nextFocusLeftId = checkButton.id
            checkButton.nextFocusRightId = closeButton.id
            closeButton.nextFocusUpId = rows.last().id
            closeButton.nextFocusDownId = closeButton.id
            closeButton.nextFocusLeftId = checkButton.id
            closeButton.nextFocusRightId = closeButton.id
        }
    }

    /** 先确定同一检测分集，再让每种算法在独立线程和取消作用域中实际获取地址。 */
    private fun startCheck() {
        if (running != null || !dialog.isShowing) return
        val session = RequestScope()
        running = session
        checkButton.isEnabled = false
        checkButton.alpha = .5f
        checkButton.text = "检测中…"
        summary.text = "正在准备检测分集…"
        results.values.forEach { it.text = "等待检测…"; it.setTextColor(mutedColor) }
        val prepareScope = session.child(12_000)
        workers.execute {
            val episode = runCatching { prepareScope.run { resolveEpisode() } }
            main.post {
                if (running !== session || !dialog.isShowing) return@post
                episode.fold({ id -> checkModes(session, id) }, { problem ->
                    summary.text = "无法准备检测分集：${failureReason(problem)}"
                    results.values.forEach { it.text = "未检测" }
                    finishCheck(session)
                })
            }
        }
    }

    private fun checkModes(session: RequestScope, episodeId: String) {
        var remaining = modes.size
        var available = 0
        summary.text = "正在检测 ${modes.size} 种算法，取址耗时以毫秒显示…"
        modes.forEach { mode ->
            results.getValue(mode).text = "正在获取播放地址…"
            val scope = session.child(10_000)
            workers.execute {
                val started = SystemClock.elapsedRealtime()
                val result = runCatching { scope.run { probe(mode, episodeId) } }
                val elapsed = SystemClock.elapsedRealtime() - started
                main.post {
                    if (running !== session || !dialog.isShowing) return@post
                    val view = results.getValue(mode)
                    result.fold({ info ->
                        available++
                        view.text = "可用 · ${elapsed} ms\n${info.quality}"
                        view.setTextColor(if (phone && !PortraitStyle.isDark) Color.rgb(30, 130, 65) else Color.rgb(86, 196, 122))
                    }, { problem ->
                        view.text = "失败 · ${elapsed} ms\n${failureReason(problem)}"
                        view.setTextColor(if (phone && !PortraitStyle.isDark) Color.rgb(190, 45, 45) else Color.rgb(235, 95, 95))
                    })
                    remaining--
                    summary.text = if (remaining == 0) "检测完成：${available}/${modes.size} 个模式可用，点击模式即可选择。"
                        else "已完成 ${modes.size - remaining}/${modes.size}，其余模式仍在检测…"
                    if (remaining == 0) finishCheck(session)
                }
            }
        }
    }

    private fun finishCheck(session: RequestScope) {
        if (running !== session) return
        session.cancel()
        running = null
        checkButton.isEnabled = true
        checkButton.alpha = 1f
        checkButton.text = "重新检测"
    }

    private fun failureReason(problem: Throwable) =
        problem.message?.lineSequence()?.firstOrNull()?.take(96).orEmpty().ifBlank { "取址失败，请稍后重试" }

    /** 关闭弹框或切换模式时，停止检测及其网络请求，丢弃尚未回到主线程的结果。 */
    fun cancel() {
        running?.cancel()
        running = null
        workers.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
