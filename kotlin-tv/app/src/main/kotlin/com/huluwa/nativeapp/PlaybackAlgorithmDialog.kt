// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    private val probe: (SigningSettings.Algorithm, String) -> StreamInfo,
    onChoice: (SigningSettings.Algorithm) -> Unit,
) {
    private val phone = PhoneDialog.shouldUse(activity)
    private val current = SigningSettings.selectedAlgorithm
    private var modes: List<SigningSettings.Algorithm> = SigningSettings.algorithms
    private var selected: SigningSettings.Algorithm? = if (autoCheck) null else current
    private var fastest: SigningSettings.Algorithm? = null
    private var checked = false
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(32) { action ->
        Thread(action, "playback-algorithm-check").apply { isDaemon = true }
    }
    private var running: RequestScope? = null
    private var checkPending = false
    private val latencies = mutableMapOf<SigningSettings.Algorithm, Long>()
    private val results = mutableMapOf<SigningSettings.Algorithm, TextView>()
    private val names = mutableMapOf<SigningSettings.Algorithm, TextView>()
    private val options = mutableMapOf<SigningSettings.Algorithm, LinearLayout>()
    private lateinit var list: LinearLayout
    private var focusAnchor: View? = null
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
        summary = label("检测后自动选中取址最快的可用算法，也可自行选择后确认。", 14f, mutedColor).apply {
            setPadding(0, 0, 0, dp(12))
        }
        list = column()
        val customButton = action("＋ 自定义算法") { showCustomAlgorithmDialog() }
        customButton.id = View.generateViewId()
        focusAnchor = customButton
        customButton.contentDescription = "添加自定义算法"
        val actions = column()
        // 自定义入口放在检测按钮下方，点击后展开名称与地址输入。
        checkButton = action("检测算法") { startCheck() }
        confirmButton = action("确认", primary = true) {
            val algorithm = selected
            if (algorithm != null && running == null && (!checked || algorithm in latencies)) {
                dialog.dismiss()
                onChoice(algorithm)
            }
        }
        val closeButton = action("取消") { dialog.dismiss() }
        val buttons = listOf(checkButton, confirmButton, closeButton)
        buttons.forEach { it.id = View.generateViewId() }
        // 先填充列表再创建窗口，styleWindow 才能按完整内容估算高度并正确压缩滚动区。
        rebuildRows(customButton)
        if (phone) {
            listOf(confirmButton, checkButton, customButton, closeButton).forEachIndexed { index, button ->
                actions.addView(button, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) })
            }
            val content = column().apply { addView(summary); addView(list) }
            dialog = PhoneDialog.sheet(activity, "算法模式", subtitle = message, content = content, footer = actions)
        } else {
            actions.orientation = LinearLayout.VERTICAL
            actions.gravity = Gravity.END
            actions.setPadding(0, dp(12), 0, 0)
            val checkActionRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(checkButton, LinearLayout.LayoutParams(-2, -2))
            }
            actions.addView(checkActionRow)
            actions.addView(customButton, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(8)
                gravity = Gravity.END
            })
            val otherActions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(10), 0, 0)
            }
            otherActions.addView(confirmButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
            otherActions.addView(closeButton, LinearLayout.LayoutParams(-2, -2))
            actions.addView(otherActions)
            val content = column().apply {
                addView(TvDialog.title(activity, "算法模式"))
                if (!message.isNullOrBlank()) addView(TvDialog.body(activity, message))
                addView(summary)
                addView(ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(list) })
                addView(actions)
            }
            dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
            TvDialog.styleWindow(activity, dialog, widthDp = 450,
                capPx = (activity.resources.displayMetrics.heightPixels * .85f).toInt()) {
                if (running != null) closeButton.requestFocus()
                else options[selected ?: current]?.requestFocus() ?: customButton.requestFocus()
            }
        }
        refreshSelection()
    }

    private fun rebuildRows(customButton: TextView) {
        list.removeAllViews()
        modes = SigningSettings.algorithms
        results.clear(); names.clear(); options.clear()
        val rows = modes.map { algorithm -> createRow(algorithm).also { row ->
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        } }
        if (!phone && rows.isNotEmpty()) {
            bindRowFocus(rows)
            checkButton.nextFocusUpId = customButton.id
            customButton.nextFocusDownId = checkButton.id
            checkButton.nextFocusDownId = customButton.id
            confirmButton.nextFocusUpId = customButton.id
        }
    }

    /** 自定义地址采用 openhongguo-signer 的 `/feature-hash` 协议，添加后立即参与当前列表检测。 */
    private fun showCustomAlgorithmDialog() {
        val content = column()
        val nameInput = field("算法名称", InputType.TYPE_CLASS_TEXT, 16)
        val addressInput = field(
            "算法地址，如 http://192.168.1.20:9876",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            240,
        )
        val hint = label("地址需兼容 openhongguo-signer 的 /feature-hash 接口。", 13f, mutedColor)
        nameInput.contentDescription = "自定义算法名称"
        addressInput.contentDescription = "自定义算法地址"
        if (phone) {
            content.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            content.addView(addressInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            content.addView(hint, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        } else {
            content.addView(TvDialog.title(activity, "添加自定义算法"))
            content.addView(nameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            content.addView(addressInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            content.addView(hint)
        }
        val error = label("", 13f, if (phone && !PortraitStyle.isDark) Color.rgb(190, 45, 45) else Color.rgb(235, 95, 95)).apply {
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        content.addView(error, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val actions = column()
        lateinit var customDialog: AlertDialog
        fun submit() {
            runCatching { SigningSettings.addCustomAlgorithm(nameInput.text.toString(), addressInput.text.toString()) }
                .onSuccess { added ->
                    customDialog.dismiss()
                    selected = added
                    checked = false
                    latencies.clear()
                    fastest = null
                    modes = SigningSettings.algorithms
                    ensureRows()
                    // 弹框关闭后再启动检测：地址输入框和软键盘的窗口仍在收尾，等下一帧更稳。
                    main.post {
                        if (!dialog.isShowing || isFinishing) return@post
                        if (running != null) checkPending = true else startCheck()
                    }
                    error.visibility = View.GONE
                }
                .onFailure { problem ->
                    error.text = problem.message ?: "添加失败，请检查名称和地址"
                    error.visibility = View.VISIBLE
                    if (addressInput.text.isBlank()) addressInput.requestFocus() else nameInput.requestFocus()
                }
        }
        val submitButton = action("确定", primary = true) { submit() }
        submitButton.id = View.generateViewId()
        val cancel = action("取消") { customDialog.dismiss() }
        cancel.id = View.generateViewId()
        if (phone) {
            listOf(submitButton, cancel).forEachIndexed { index, button ->
                actions.addView(button, LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(8) })
            }
            customDialog = PhoneDialog.sheet(activity, "添加自定义算法", content = content, footer = actions)
        } else {
            actions.orientation = LinearLayout.HORIZONTAL
            actions.gravity = Gravity.END
            buttons(actions, listOf(cancel, submitButton))
            content.addView(actions)
            customDialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
            TvDialog.styleWindow(activity, customDialog, widthDp = 480) {
                nameInput.requestFocus()
            }
        }
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) { addressInput.requestFocus(); true } else false
        }
        addressInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        customDialog.show()
        if (phone) {
            // PhoneDialog.sheet 用 ADJUST_NOTHING，靠 IME Insets 抬高面板；保持同一策略，
            // 避免键盘弹出时系统再次 resize 造成输入框跳动。
            customDialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            nameInput.requestFocus()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun field(hintValue: String, inputType: Int, maxLength: Int): EditText = EditText(activity).apply {
        hint = hintValue
        textSize = 16f
        isSingleLine = true
        setTextColor(textColor)
        setHintTextColor(mutedColor)
        inputType = inputType
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
        imeOptions = if (maxLength == 16) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        setPadding(dp(14), dp(12), dp(14), dp(12))
        minHeight = dp(46)
        background = if (phone) PortraitStyle.shape(activity, PortraitStyle.surface, 12)
            else TvStyle.shape(activity, TvStyle.surface, TvStyle.outline)
    }

    private fun buttons(parent: LinearLayout, values: List<TextView>) {
        values.forEachIndexed { index, button ->
            if (index > 0) parent.addView(android.view.View(activity), LinearLayout.LayoutParams(dp(10), 1))
            parent.addView(button, LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun action(value: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        if (phone) PhoneDialog.button(activity, value, primary = primary, onClick = onClick)
        else TvDialog.capsuleButton(activity, value, onClick)

    private fun paintOption(algorithm: SigningSettings.Algorithm) {
        val row = options[algorithm] ?: return
        if (phone) {
            row.background = PortraitStyle.selectable(activity,
                if (algorithm == selected) PortraitStyle.selectedSurface else PhoneDialog.groupBackground(activity), radius = 14)
        } else {
            row.background = TvStyle.shape(activity,
                if (row.hasFocus()) TvStyle.raised else if (algorithm == selected) TvStyle.selectedSurface else TvStyle.surface,
                if (row.hasFocus() || algorithm == selected) TvStyle.accent else Color.TRANSPARENT)
        }
    }

    private fun refreshSelection() {
        modes.forEach { algorithm ->
            val suffix = if (algorithm.id == current.id) " · 当前" else ""
            val endpoint = algorithm.endpoint?.let { "\n$it" }.orEmpty()
            names.getValue(algorithm).text = (if (algorithm == selected) "✓  " else "") + algorithm.label + suffix + endpoint
            options.getValue(algorithm).isSelected = algorithm == selected
            paintOption(algorithm)
        }
        val algorithm = selected
        confirmButton.isEnabled = running == null && algorithm != null && (!checked || algorithm in latencies)
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
        // 自定义项可能在弹框打开期间新增；每次检测都以最新列表为准。
        modes = SigningSettings.algorithms
        ensureRows()
        results.values.forEach { it.text = "等待检测…"; it.setTextColor(mutedColor) }
        refreshSelection()
        val checkedAlgorithms = modes
        val prepareScope = session.child(12_000)
        workers.execute {
            val episode = runCatching { prepareScope.run { resolveEpisode() } }
            main.post {
                if (running !== session || !dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@post
                episode.fold({ id -> checkModes(session, id, checkedAlgorithms) }, { problem ->
                    summary.text = "无法准备检测分集：${failureReason(problem)}"
                    results.values.forEach { it.text = "未检测" }
                    finishCheck(session)
                })
            }
        }
    }

    /** 新增自定义项后重画列表；正常检测刷新不重建，保留行上的结果与焦点。 */
    private fun ensureRows() {
        val known = options.keys.toSet()
        if (modes.all { it in known }) return
        results.clear(); names.clear(); options.clear()
        list.removeAllViews()
        val rows = modes.map { algorithm -> createRow(algorithm).also { row ->
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        } }
        if (!phone) bindRowFocus(rows)
    }

    /** 电视端为动态算法列表重建上下焦点；底部操作区由“自定义算法”入口承接。 */
    private fun bindRowFocus(rows: List<LinearLayout>) {
        if (rows.isEmpty()) return
        val custom = focusAnchor ?: return
        rows.forEachIndexed { index, row ->
            row.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: custom.id
            row.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: custom.id
        }
        custom.nextFocusUpId = rows.last().id
    }

    private fun createRow(algorithm: SigningSettings.Algorithm): LinearLayout {
        val row = column().apply {
            id = View.generateViewId()
            isFocusable = true; isClickable = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnFocusChangeListener { _, _ -> paintOption(algorithm) }
        }
        val name = label("", 16f, textColor).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        names[algorithm] = name
        row.addView(name)
        val result = label("尚未检测", 13f, mutedColor).apply {
            setPadding(0, dp(4), 0, 0)
            minLines = 2; maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
        }
        row.addView(result)
        results[algorithm] = result
        row.setOnClickListener {
            if (running == null) {
                selected = algorithm
                summary.text = if (!checked) "已选择${algorithm.label}，点击确认后使用此算法。"
                    else if (algorithm in latencies) "已选择${algorithm.label}（${latencies.getValue(algorithm)} ms），确认后使用此算法。"
                    else "所选算法未取得可用地址，请选择可用算法或重新检测。"
                refreshSelection()
            }
        }
        options[algorithm] = row
        return row
    }

    private fun checkModes(session: RequestScope, episodeId: String, checkedAlgorithms: List<SigningSettings.Algorithm>) {
        var remaining = checkedAlgorithms.size
        summary.text = "正在检测 ${checkedAlgorithms.size} 种算法，比较完整取址耗时…"
        checkedAlgorithms.forEach { algorithm ->
            results.getValue(algorithm).text = "正在获取播放地址…"
            val scope = session.child(10_000)
            workers.execute {
                // 单调时钟统计完整取址耗时，包含签名与内部重试，不包含分集准备。
                val started = SystemClock.elapsedRealtime()
                val result = runCatching { scope.run { probe(algorithm, episodeId) } }
                val elapsed = SystemClock.elapsedRealtime() - started
                main.post {
                    if (running !== session || !dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@post
                    val view = results[algorithm] ?: return@post
                    result.fold({ info ->
                        latencies[algorithm] = elapsed
                        view.text = "可用 · ${elapsed} ms\n${info.quality}"
                        view.setTextColor(if (phone && !PortraitStyle.isDark) Color.rgb(30, 130, 65) else Color.rgb(86, 196, 122))
                    }, { problem ->
                        view.text = "失败 · ${elapsed} ms\n${failureReason(problem)}"
                        view.setTextColor(if (phone && !PortraitStyle.isDark) Color.rgb(190, 45, 45) else Color.rgb(235, 95, 95))
                    })
                    remaining--
                    if (remaining == 0) {
                        fastest = checkedAlgorithms.filter { it in latencies }.minByOrNull { latencies.getValue(it) }
                        selected = fastest
                        val best = fastest
                        summary.text = if (best == null) "所有算法均未取得可用地址，请重新检测或稍后重试。"
                            else "已选取址最快的${best.label}（${latencies.getValue(best)} ms），确认后使用此算法。"
                        if (best != null) {
                            val bestView = results.getValue(best)
                            bestView.text = bestView.text.toString().replace(" ms\n", " ms · 最快\n")
                        }
                        finishCheck(session)
                        if (!phone && best != null) confirmButton.requestFocus()
                    } else summary.text = "已完成 ${checkedAlgorithms.size - remaining}/${checkedAlgorithms.size}，其余算法仍在检测…"
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
        if (checkPending) {
            checkPending = false
            startCheck()
        }
    }

    private fun failureReason(problem: Throwable) =
        problem.message?.lineSequence()?.firstOrNull()?.take(96).orEmpty().ifBlank { "取址失败，请稍后重试" }

    /** 关闭弹框或确认选择时，停止检测及其网络请求，丢弃未显示的结果。 */
    fun cancel() {
        running?.cancel()
        running = null
        checkPending = false
        workers.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
