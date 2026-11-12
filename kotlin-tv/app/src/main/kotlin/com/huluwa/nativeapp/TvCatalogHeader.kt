// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Six independent TV destinations; proportional tabs also fit dense and scaled TV displays. */
internal class TvCatalogHeader(context: Context, selected: Int, selectTab: (Int) -> Unit) : LinearLayout(context) {
    val navigation = mutableListOf<TextView>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        gravity = Gravity.CENTER_VERTICAL
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val narrow = widthDp < 800
        val tiny = widthDp < 600
        val brand = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(ImageView(context).apply { setImageResource(R.drawable.app_icon) },
            LayoutParams(dp(if (narrow) 24 else 28), dp(if (narrow) 24 else 28)).apply { rightMargin = dp(8) })
        brand.addView(TextView(context).apply {
            text = "红果 TV"; textSize = if (narrow) 17f else 22f
            visibility = if (tiny) View.GONE else View.VISIBLE
            includeFontPadding = false; setTextColor(TvStyle.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(brand, LayoutParams(dp(if (tiny) 38 else if (narrow) 108 else 140), dp(44)))
        listOf("首页", "找剧", "排行榜", "收藏", "观看历史", "设置").forEachIndexed { index, title ->
            val item = TextView(context).apply {
                id = View.generateViewId(); text = title; textSize = if (tiny) 12f else if (narrow) 13f else 15f
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    setAutoSizeTextTypeUniformWithConfiguration(10, if (narrow) 13 else 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }
                gravity = Gravity.CENTER; includeFontPadding = false; maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                isSelected = selected == index
                setPadding(dp(if (narrow) 4 else 9), dp(10), dp(if (narrow) 4 else 9), dp(10))
                fun paint(focused: Boolean) {
                    background = TvVisual.tab(context, focused, isSelected)
                    setTextColor(if (isSelected) TvStyle.accent else if (focused) TvStyle.text else TvStyle.muted)
                    setTypeface(null, if (focused || isSelected) Typeface.BOLD else Typeface.NORMAL)
                }
                setOnClickListener { selectTab(index) }
                setOnFocusChangeListener { _, focused -> paint(focused) }
                paint(false)
            }
            navigation += item
            addView(item, LayoutParams(0, dp(44), 1f))
        }
        navigation.forEachIndexed { index, item ->
            item.nextFocusLeftId = navigation.getOrNull(index - 1)?.id ?: item.id
            item.nextFocusRightId = navigation.getOrNull(index + 1)?.id ?: item.id
            item.nextFocusUpId = item.id
        }
    }
}
