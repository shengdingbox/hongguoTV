// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
import android.widget.Switch
import com.hongguotv.core.PlaybackSignatureProbe
import com.hongguotv.core.RequestScope
import com.hongguotv.core.StreamInfo
import java.util.concurrent.Executors

/** 用真实播放请求判断可用性，再只比较签名耗时；确认后才切换正式模式。 */
internal class PlaybackAlgorithmDialog(
    private val activity: Activity,
    message: String?,
    private val autoCheck: Boolean,
    private val probe: (SigningSettings.Algorithm, String) -> StreamInfo,
    private val signProbe: (SigningSettings.Algorithm, String) -> PlaybackSignatureProbe,
    private val onAutomaticChange: (Boolean) -> Unit,
    onChoice: (SigningSettings.Algorithm) -> Unit,
) {
    private companion object {
        // 固定使用已有 1080P 取址记录的普通短剧分集，每种算法、每次检测使用同一样本。
        const val SAMPLE_EPISODE_ID = "7675424396831378456"
    }

    private val phone = PhoneDialog.shouldUse(activity)
    private val current = SigningSettings.selectedAlgorithm
    private var automatic = SigningSettings.automaticSwitching
    private val automaticToggle: Switch
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
    private val qualities = mutableMapOf<SigningSettings.Algorithm, String>()
    private val availableAlgorithms = mutableSetOf<SigningSettings.Algorithm>()
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
        automaticToggle = Switch(activity).apply {
            id = View.generateViewId()
            text = "自动切换"
            textSize = 16f
            setTextColor(textColor)
            isChecked = automatic
            isFocusable = true
            setPadding(dp(4), dp(10), dp(4), dp(12))
        }
        summary = label(strategyHint(), 14f, mutedColor).apply {
            setPadding(0, 0, 0, dp(12))
        }
        list = column()
        val customButton = action("自定义算法") { showCustomAlgorithmDialog() }
        customButton.id = View.generateViewId()
        focusAnchor = customButton
        customButton.contentDescription = "添加自定义算法"
        val actions = column()
        // 自定义入口放在检测按钮下方，点击后展开名称与地址输入。
        checkButton = action("检测算法") { startCheck() }
        confirmButton = action("确认", primary = true) {
            val algorithm = selected
            if (running == null && automatic) {
                dialog.dismiss()
                onChoice(current)
            } else if (algorithm != null && running == null &&
                (!checked || (algorithm in latencies && algorithm in availableAlgorithms))
            ) {
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
            val content = column().apply { addView(automaticToggle); addView(summary); addView(list) }
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
                addView(automaticToggle)
                addView(summary)
                addView(ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(list) })
                addView(actions)
            }
            dialog = AlertDialog.Builder(activity).setView(TvDialog.card(activity, content)).create()
            TvDialog.styleWindow(activity, dialog, widthDp = 450,
                capPx = (activity.resources.displayMetrics.heightPixels * .85f).toInt()) {
                if (running != null) closeButton.requestFocus()
                else if (automatic) automaticToggle.requestFocus()
                else options[selected ?: current]?.requestFocus() ?: customButton.requestFocus()
            }
        }
        automaticToggle.setOnCheckedChangeListener { _, enabled ->
            onAutomaticChange(enabled)
            automatic = SigningSettings.automaticSwitching
            if (automaticToggle.isChecked != automatic) automaticToggle.isChecked = automatic
            if (!automatic && selected == null) selected = current
            summary.text = strategyHint()
            refreshSelection()
        }
        refreshSelection()
    }

    private fun strategyHint() = if (automatic) {
        "本地连续取址失败 3 次后使用 unidbg，仍失败再用 Frida；记住可用远程算法，每 5 分钟探测本地，成功立即恢复。关闭后仅手动切换。"
    } else "仅使用手动选择的算法。检测结果来自固定样本，不代表当前集一定可用。"

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
                        if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@post
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

    private fun field(hintValue: String, keyboardType: Int, maxLength: Int): EditText = EditText(activity).apply {
        hint = hintValue
        textSize = 16f
        isSingleLine = true
        setTextColor(textColor)
        setHintTextColor(mutedColor)
        inputType = keyboardType
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
            names.getValue(algorithm).text = (if (algorithm == selected) "✓  " else "") + algorithm.label + suffix
            options.getValue(algorithm).isSelected = algorithm == selected
            options.getValue(algorithm).isEnabled = !automatic
            options.getValue(algorithm).alpha = if (automatic) .6f else 1f
            paintOption(algorithm)
        }
        if (!phone) automaticToggle.nextFocusDownId = if (automatic) checkButton.id else options.values.firstOrNull()?.id ?: checkButton.id
        val algorithm = selected
        confirmButton.isEnabled = running == null && (automatic || algorithm != null &&
            (!checked || (algorithm in latencies && algorithm in availableAlgorithms)))
        confirmButton.alpha = if (confirmButton.isEnabled) 1f else .5f
    }

    /** 在窗口显示后启动自动检测，保留手机与电视已有的窗口及焦点初始化。 */
    fun start() {
        if (autoCheck) startCheck()
    }

    /** 所有算法固定检测同一普通短剧分集，各自使用独立线程取址。 */
    private fun startCheck() {
        if (running != null || !dialog.isShowing) return
        val session = RequestScope()
        running = session
        checked = true
        selected = null; fastest = null; latencies.clear(); qualities.clear(); availableAlgorithms.clear()
        checkButton.isEnabled = false
        checkButton.alpha = .5f
        checkButton.text = "检测中…"
        // 自定义项可能在弹框打开期间新增；每次检测都以最新列表为准。
        modes = SigningSettings.algorithms
        ensureRows()
        results.values.forEach { it.text = "等待检测…"; it.setTextColor(mutedColor) }
        refreshSelection()
        checkModes(session, SAMPLE_EPISODE_ID, modes)
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
            row.nextFocusUpId = rows.getOrNull(index - 1)?.id ?: automaticToggle.id
            row.nextFocusDownId = rows.getOrNull(index + 1)?.id ?: custom.id
        }
        custom.nextFocusUpId = rows.last().id
        automaticToggle.nextFocusDownId = if (automatic) checkButton.id else rows.first().id
        automaticToggle.nextFocusUpId = custom.id
    }

    private fun createRow(algorithm: SigningSettings.Algorithm): LinearLayout {
        val row = column().apply {
            id = View.generateViewId()
            isFocusable = true; isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnFocusChangeListener { _, _ -> paintOption(algorithm) }
        }
        val name = label("", 16f, textColor).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        names[algorithm] = name
        row.addView(name)
        val result = label("尚未检测", 13f, mutedColor).apply {
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(result)
        results[algorithm] = result
        row.setOnClickListener {
            if (running == null && !automatic) {
                selected = algorithm
                summary.text = if (!checked) "已选择${algorithm.label}，点击确认后使用此算法。"
                    else "已选择${algorithm.label}（签名 ${latencies[algorithm]?.let { "$it ms" } ?: "—"}），确认后使用此算法。"
                refreshSelection()
            }
        }
        options[algorithm] = row
        return row
    }

    private fun checkModes(session: RequestScope, episodeId: String, checkedAlgorithms: List<SigningSettings.Algorithm>) {
        var remaining = checkedAlgorithms.size
        summary.text = "正在使用固定短剧分集检测 ${checkedAlgorithms.size} 种算法…"
        checkedAlgorithms.forEach { algorithm ->
            results.getValue(algorithm).text = "签名 — · 检测中 · 样本最高 —"
            val scope = session.child(20_000)
            workers.execute {
                val availability = runCatching { scope.run { probe(algorithm, episodeId) } }
                // 可用性失败不能吞掉签名结果；本地算法尤其需要单独展示本机计算耗时。
                val signature = runCatching { scope.run { signProbe(algorithm, episodeId) } }
                main.post {
                    if (running !== session || !dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@post
                    val view = results[algorithm] ?: return@post
                    val latency = signature.getOrNull()?.elapsedMs
                    if (latency != null) {
                        latencies[algorithm] = latency
                    } else {
                        latencies.remove(algorithm)
                    }
                    val usable = availability.isSuccess
                    val quality = availability.getOrNull()?.quality?.takeIf { it.isNotBlank() }
                    if (quality != null) qualities[algorithm] = quality else qualities.remove(algorithm)
                    if (usable) availableAlgorithms += algorithm else availableAlgorithms -= algorithm
                    view.text = detectionLine(latency, usable, quality)
                    view.setTextColor(detectionColor(usable, signature.isSuccess))
                    remaining--
                    if (remaining == 0) {
                        fastest = checkedAlgorithms
                            .filter { it in latencies && it in availableAlgorithms }
                            .minByOrNull { latencies.getValue(it) }
                        selected = fastest
                        val best = fastest
                        summary.text = when {
                            automatic -> strategyHint()
                            best != null -> "已选播放签名最快的${best.label}（${latencies.getValue(best)} ms），确认后使用此算法。"
                            else -> "没有同时可播放且完成签名测速的算法，请重新检测。"
                        }
                        finishCheck(session)
                        if (!phone && best != null) confirmButton.requestFocus()
                    } else summary.text = "已完成 ${checkedAlgorithms.size - remaining}/${checkedAlgorithms.size}，其余算法仍在检测…"
                }
            }
        }
    }

    private fun detectionLine(latency: Long?, usable: Boolean, quality: String?): String = buildString {
        append(if (latency == null) "签名 —" else "签名 ${latency} ms")
        append(" · ")
        append(if (usable) "可用" else "不可用")
        append(" · 样本最高 ")
        append(quality ?: "—")
    }

    private fun detectionColor(usable: Boolean, signatureOk: Boolean): Int = when {
        !usable || !signatureOk -> if (phone && !PortraitStyle.isDark) Color.rgb(190, 45, 45) else Color.rgb(235, 95, 95)
        else -> if (phone && !PortraitStyle.isDark) Color.rgb(30, 130, 65) else Color.rgb(86, 196, 122)
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

    /** 关闭弹框或确认选择时，停止检测及其网络请求，丢弃未显示的结果。 */
    fun cancel() {
        running?.cancel()
        running = null
        checkPending = false
        workers.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
