// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Detail

/**
 * 手机竖屏播放页：视频固定在顶部 16:9 区域，下方是常驻控制面板。
 * 播放内核完全复用 MainActivity 的 EpisodePlaybackSession / playbackCache，
 * 这里只负责竖屏专属的界面：
 *  - 进度条拖动定位（复用 PlaybackSeekBar，天然支持触摸）
 *  - 上一集/播放暂停/下一集、选集/倍速/清晰度/弹幕按钮
 * 进度文字由 MainActivity 的 tick 通过 [statusText]/[progressText]/[playButton]/[seekBar] 刷新。
 */
class PhonePlayer(
    private val activity: MainActivity,
) {
    private fun dp(v: Int) = PhoneUi.dp(activity, v)
    private fun text(v: String, size: Float, color: Int = TvStyle.text) = PhoneUi.text(activity, v, size, color)
    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)

    /** 面板控件引用，由 MainActivity 每秒 tick 刷新文字。 */
    var titleText: TextView? = null
    var statusText: TextView? = null
    var progressText: TextView? = null
    var playButton: TextView? = null
    var seekBar: PlaybackSeekBar? = null

    /**
     * 渲染播放页外壳。视频区（PlayerView + 弹幕层）由 MainActivity.playEpisode
     * 挂到 root 顶部，这里只建下方常驻控制面板。
     */
    fun render(root: android.widget.FrameLayout, data: Detail) {
        root.setBackgroundColor(Color.BLACK)
        // 控制面板
        val panel = PhoneUi.column(activity).apply { setBackgroundColor(TvStyle.background) }
        // 标题行
        val titleRow = PhoneUi.row(activity).apply { setPadding(dp(14), dp(10), dp(14), dp(2)) }
        titleText = text("${data.series.title}  ·  第 ${activity.phoneEpisodeIndex+1} 集", 16f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        titleRow.addView(titleText, lp(0, -2).apply { weight = 1f })
        val back = text("✕ 退出", 13f, TvStyle.muted).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = TvStyle.shape(activity, TvStyle.raised)
            setOnClickListener { activity.returnToDetailPhone() }
        }
        titleRow.addView(back)
        panel.addView(titleRow, lp(-1, -2))

        statusText = text("", 13f, TvStyle.gold).apply { setPadding(dp(14), dp(6), dp(14), 0); maxLines = 1 }
        panel.addView(statusText, lp(-1, -2))

        // 进度条
        val bar = PlaybackSeekBar(activity).apply { max = 1000 }
        bar.onScrubPreview = preview@{ ratio ->
            val duration = activity.playerDuration
            if (duration <= 0) return@preview
            progressText?.text = "定位到 ${formatTime((duration*ratio).toLong())} / ${formatTime(duration)}"
        }
        bar.onScrub = scrub@{ ratio, final ->
            val duration = activity.playerDuration
            if (duration <= 0 || !activity.phonePlaybackReady) return@scrub
            if (final) activity.seekToPhone((duration*ratio).toLong())
        }
        seekBar = bar
        panel.addView(bar, lp(-1, dp(26)).apply { leftMargin = dp(14); rightMargin = dp(14) })
        progressText = text("", 12f, TvStyle.muted).apply { setPadding(dp(14), dp(2), dp(14), dp(6)) }
        panel.addView(progressText, lp(-1, -2))

        // 传输控制：上一集 / 播放暂停 / 下一集
        val transport = PhoneUi.row(activity).apply { setPadding(dp(14), dp(2), dp(14), dp(6)) }
        val prev = text("上一集", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            background = TvStyle.shape(activity, TvStyle.raised)
            setOnClickListener { activity.skipEpisodePhone(-1) }
        }
        transport.addView(prev, lp(0, -2).apply { weight = 1f; rightMargin = dp(10) })
        val play = text("暂停", 15f, TvStyle.background).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            background = TvStyle.shape(activity, TvStyle.accent)
            setOnClickListener { activity.togglePlaybackPhone() }
        }
        playButton = play
        transport.addView(play, lp(0, -2).apply { weight = 1f; rightMargin = dp(10) })
        val next = text("下一集", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            background = TvStyle.shape(activity, TvStyle.raised)
            setOnClickListener { activity.skipEpisodePhone(1) }
        }
        transport.addView(next, lp(0, -2).apply { weight = 1f })
        panel.addView(transport, lp(-1, -2))

        // 第二行：选集 / 倍速 / 清晰度 / 弹幕
        val extras = PhoneUi.row(activity).apply { setPadding(dp(14), 0, dp(14), dp(10)) }
        listOf(
            "选集" to { activity.showPhoneEpisodePicker() },
            "倍速" to { activity.showPhoneSpeedPicker() },
            "清晰度" to { activity.showPhoneQualityPicker() },
            "弹幕" to { activity.togglePhoneDanmaku() }).forEach { (label, action) ->
            val chip = text(label, 13f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = TvStyle.shape(activity, TvStyle.raised)
                setOnClickListener { action() }
            }
            extras.addView(chip, lp(0, -2).apply { weight = 1f; rightMargin = dp(8) })
        }
        panel.addView(extras, lp(-1, -2))

        // 面板贴着视频区下沿放（而不是钉在屏幕底部）：竖屏屏高差异大，
        // 钉底会让视频与控件之间留出大片黑边，控件也离视频太远。
        val videoHeight = (activity.resources.displayMetrics.widthPixels * 9f / 16f).toInt()
        root.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = videoHeight })
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) / 1000)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
