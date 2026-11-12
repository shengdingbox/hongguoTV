// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.widget.TextView

/** A single accessible focus target with the label and current value aligned to opposite edges. */
internal class TvSettingsValueView(context: Context) : TextView(context) {
    private val ink = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()

    init { setSingleLine(true) }

    override fun onDraw(canvas: Canvas) {
        val parts = text.toString().split("  ·  ", limit = 2)
        val value = parts.getOrNull(1) ?: "›"
        val density = resources.displayMetrics.density
        val available = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f)
        val toggle = value == "开启" || value == "关闭"
        ink.typeface = typeface
        ink.textSize = textSize
        val rightText = if (toggle) "" else TextUtils.ellipsize(value, ink, available * .46f, TextUtils.TruncateAt.END).toString()
        val rightWidth = if (toggle) 42 * density else ink.measureText(rightText)
        val label = TextUtils.ellipsize(parts[0], ink, (available - rightWidth - 18 * density).coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        val baseline = height / 2f - (ink.fontMetrics.ascent + ink.fontMetrics.descent) / 2
        ink.color = TvStyle.text
        canvas.drawText(label, paddingLeft.toFloat(), baseline, ink)
        if (toggle) {
            val right = (width - paddingRight).toFloat()
            val halfHeight = 11 * density
            track.set(right - rightWidth, height / 2f - halfHeight, right, height / 2f + halfHeight)
            ink.color = if (value == "开启") TvStyle.accent else TvStyle.outline
            canvas.drawRoundRect(track, halfHeight, halfHeight, ink)
            ink.color = TvStyle.text
            val center = if (value == "开启") track.right - halfHeight else track.left + halfHeight
            canvas.drawCircle(center, track.centerY(), 8 * density, ink)
        } else {
            ink.color = if (hasFocus()) TvStyle.accent else TvStyle.muted
            canvas.drawText(rightText, width - paddingRight - rightWidth, baseline, ink)
        }
    }
}
