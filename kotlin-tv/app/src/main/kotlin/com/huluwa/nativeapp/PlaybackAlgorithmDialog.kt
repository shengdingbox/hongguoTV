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

/** 检测后预选取址最快的可用算法，只有用户确认后才切换正式模式。 */
internal class PlaybackAlgorithmDialog(
    private val activity: Activity,
    message: String?,
    private val autoCheck: Boolean,
    private val resolveEpisode: () -> String,
    private val probe: (SigningSettings.Mode, String) -> StreamInfo,
    onChoice: (SigningSettings.Mode) -> Unit,
) {
    private val phone = PhoneDialog.shouldUse(activity)
    private val modes = SigningSettings.modes
    private val current = SigningSettings.mode
    private var selected: SigningSettings.Mode? = if (autoCheck) null else current
    private var fastest: SigningSettings.Mode? = null
    private var checked = false
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(modes.size) { action ->
        Thread(action, "playback-algorithm-check").apply { isDaemon = true }
    }
    private var running: RequestScope? = null
    private val latencies = mutableMapOf<SigningSettings.Mode, Long>()
    private val results = mutableMapOf<SigningSettings.Mode, TextView>()
    private val names = mutableMapOf<SigningSettings.Mode, TextView>()
    private val options = mutableMapOf<SigningSettings.Mode, LinearLayout>()
    private val summary: TextView
    private val checkButton: TextView
    private val confirmButton: TextView
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
        summary = label("检测后自动选中取址最快的可用模式，也可自行选择后确认。", 14f, mutedColor).apply {
            setPadding(0, 0, 0, dp(12))
        }
        val list = column()
        val rows = modes.map { mode ->
            column().apply {
                id = View.generateViewId()
                isFocusable = true; isClickable = true
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnFocusChangeListener { _, _ -> paintOption(mode) }
                val name = label("", 16f, textColor)
                names[mode] = name
                addView(name)
                val result = label("尚未检测", 13f, mutedColor).apply {
                    setPadding(0, dp(4), 0, 0)
                    minLines = 2; maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.15f)
                }
                addView(result)
                results[mode] = result
                setOnClickListener {
                    if (running == null) {
                        selected = mode
                        summary.text = if (!checked) "已选择${mode.label}，点击确认后使用此模式。"
                            else if (mode in latencies) "已选择${mode.label}（${latencies.getValue(mode)} ms），确认后使用此模式。"
                            else "所选模式未取得可用地址，请选择可用模式或重新检测。"
                        refreshSelection()
                    }
                }
            }.also { row ->
                options[mode] = row
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        checkButton = action("检测算法") { startCheck() }
        confirmButton = action("确认", primary = true) {
            val mode = selected
            if (mode != null && running == null && (!checked || mode in latencies)) {
                dialog.dismiss()
                onChoice(mode)
            }
        }
        val closeButton = action("取消") { dialog.dismiss() }
        val buttons = listOf(checkButton, confirmButton, closeButton)
        buttons.forEach { it.id = View.generateViewId() }
        val actions = column()
        if (phone) {
            listOf(confirmButton, checkButton, closeButton).forEachIndexed { index, button ->
                actions.addView(button, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) })
            }
            val content = column().apply { addView(summary); addView(list) }
            dialog = PhoneDialog.sheet(activity, "算法模式", subtitle = message, content = content, footer = actions)
        } else {
            actions.orientation = LinearLayout.HORIZONTAL
            actions.gravity = Gravity.END
            actions.setPadding(0, dp(12), 0, 0)
            buttons.forEachIndexed { index, button ->
                actions.addView(button, LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) leftMargin = dp(12) })
            }
            val content = column().apply {
                addView(TvDialog.title(activity, "算法模式"))
                if (!message.isNullOrBlank()) addView(TvDialog.body(activity, message))
                addView(summary)
                addView(ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(list) })
                addView(actions)
            }
            dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
            TvDialog.styleWindow(activity, dialog, widthDp = 420,
                capPx = (activity.resources.displayMetrics.heightPixels * .85f).toInt()) {
                if (running != null) closeButton.requestFocus()
                else options[selected ?: current]?.requestFocus()
            }
            rows.forEachIndexed { index, row ->
                row.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: row.id
                row.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: checkButton.id
            }
            buttons.forEachIndexed { index, button ->
                button.nextFocusUpId = rows.last().id
                button.nextFocusDownId = button.id
                button.nextFocusLeftId = buttons.getOrNull(index - 1)?.id ?: button.id
                button.nextFocusRightId = buttons.getOrNull(index + 1)?.id ?: button.id
            }
        }
        refreshSelection()
    }

    private fun action(value: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        if (phone) PhoneDialog.button(activity, value, primary = primary, onClick = onClick)
        else TvDialog.capsuleButton(activity, value, onClick)

    private fun paintOption(mode: SigningSettings.Mode) {
        val row = options[mode] ?: return
        if (phone) {
            row.background = PortraitStyle.selectable(activity,
                if (mode == selected) PortraitStyle.selectedSurface else PhoneDialog.groupBackground(activity), radius = 14)
        } else {
            row.background = TvStyle.shape(activity,
                if (row.hasFocus()) TvStyle.raised else if (mode == selected) TvStyle.selectedSurface else TvStyle.surface,
                if (row.hasFocus() || mode == selected) TvStyle.accent else Color.TRANSPARENT)
        }
    }

    private fun refreshSelection() {
        modes.forEach { mode ->
            names.getValue(mode).text = (if (mode == selected) "✓  " else "") + mode.label + if (mode == current) " · 当前" else ""
            options.getValue(mode).isSelected = mode == selected
            paintOption(mode)
        }
        val mode = selected
        confirmButton.isEnabled = running == null && mode != null && (!checked || mode in latencies)
        confirmButton.alpha = if (confirmButton.isEnabled) 1f else .5f
    }

    /** 在窗口显示后启动自动检测，保留手机与电视已有的窗口及焦点初始化。 */
    fun start() {
        if (autoCheck) startCheck()
    }

    /** 先确定共同分集，再让每种算法在独立线程中完成一次取址。 */
    private fun startCheck() {
        if (running != null || !dialog.isShowing) return
        val session = RequestScope()
        running = session
        checked = true
        selected = null; fastest = null; latencies.clear()
        checkButton.isEnabled = false
        checkButton.alpha = .5f
        checkButton.text = "检测中…"
        summary.text = "正在准备检测分集…"
        results.values.forEach { it.text = "等待检测…"; it.setTextColor(mutedColor) }
        refreshSelection()
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
        summary.text = "正在检测 ${modes.size} 种算法，比较完整取址耗时…"
        modes.forEach { mode ->
            results.getValue(mode).text = "正在获取播放地址…"
            val scope = session.child(10_000)
            workers.execute {
                // 单调时钟统计完整取址耗时，包含签名与内部重试，不包含排队和分集准备。
                val started = SystemClock.elapsedRealtime()
                val result = runCatching { scope.run { probe(mode, episodeId) } }
                val elapsed = SystemClock.elapsedRealtime() - started
                main.post {
                    if (running !== session || !dialog.isShowing) return@post
                    val view = results.getValue(mode)
                    result.fold({ info ->
                        latencies[mode] = elapsed
                        view.text = "可用 · ${elapsed} ms\n${info.quality}"
                        view.setTextColor(if (phone && !PortraitStyle.isDark) Color.rgb(30, 130, 65) else Color.rgb(86, 196, 122))
                    }, { problem ->
                        view.text = "失败 · ${elapsed} ms\n${failureReason(problem)}"
                        view.setTextColor(if (phone && !PortraitStyle.isDark) Color.rgb(190, 45, 45) else Color.rgb(235, 95, 95))
                    })
                    remaining--
                    if (remaining == 0) {
                        fastest = modes.filter { it in latencies }.minByOrNull { latencies.getValue(it) }
                        selected = fastest
                        val best = fastest
                        summary.text = if (best == null) "所有模式均未取得可用地址，请重新检测或稍后重试。"
                            else "已选取址最快的${best.label}（${latencies.getValue(best)} ms），确认后使用此模式。"
                        if (best != null) {
                            val bestView = results.getValue(best)
                            bestView.text = bestView.text.toString().replace(" ms\n", " ms · 最快\n")
                        }
                        finishCheck(session)
                        if (!phone && best != null) confirmButton.requestFocus()
                    } else summary.text = "已完成 ${modes.size - remaining}/${modes.size}，其余模式仍在检测…"
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
        refreshSelection()
    }

    private fun failureReason(problem: Throwable) =
        problem.message?.lineSequence()?.firstOrNull()?.take(96).orEmpty().ifBlank { "取址失败，请稍后重试" }

    /** 关闭弹框或确认选择时，停止检测及其网络请求，丢弃未显示的结果。 */
    fun cancel() {
        running?.cancel()
        running = null
        workers.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
