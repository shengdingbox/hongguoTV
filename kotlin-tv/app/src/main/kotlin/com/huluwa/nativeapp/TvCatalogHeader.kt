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

/** 电视顶部导航：与随心看客户端保持同一组入口，按屏幕宽度等比例分配。 */
internal class TvCatalogHeader(
    context: Context,
    selected: Int,
    private val selectTab: (Int) -> Unit,
) : LinearLayout(context) {
    companion object {
        /** 显示顺序和页面索引是两套编号；随心看索引 8，经典搜索页仍是索引 1。 */
        val DESTINATIONS = listOf(
            0 to "首页",
            8 to "随心看",
            1 to "找剧",
            1 to "搜索",
            2 to "排行榜",
            3 to "我的",
            6 to "设置",
        )

        /** 页面编号到顶部按钮下标的映射；没有独立按钮的旧页面归到相邻入口。 */
        fun normalize(index: Int) = when (index) {
            8 -> 1
            1 -> 2
            2 -> 4
            in 3..5 -> 5
            6 -> 6
            else -> 0
        }

        /** 顶部按钮下标到实际页面；首页和随心看共用 tab=0，靠 suixinMode 区分。 */
        fun tabForIndex(index: Int): Int = when (index) {
            1 -> 8
            in 3..5 -> 3
            6 -> 6
            else -> index
        }
    }

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
            text = "红果 TV"
            textSize = if (narrow) 17f else 22f
            visibility = if (tiny) View.GONE else View.VISIBLE
            includeFontPadding = false
            setTextColor(TvStyle.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(brand, LayoutParams(dp(if (tiny) 38 else if (narrow) 108 else 140), dp(44)))

        val selectedPage = tabForIndex(selected)
        DESTINATIONS.forEachIndexed { index, (pageIndex, title) ->
            val item = TextView(context).apply {
                id = View.generateViewId()
                text = title
                textSize = if (tiny) 11f else if (narrow) 12f else 14f
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    setAutoSizeTextTypeUniformWithConfiguration(9, if (narrow) 12 else 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                isSelected = pageIndex == selectedPage
                setPadding(dp(if (narrow) 3 else 8), dp(10), dp(if (narrow) 3 else 8), dp(10))
                fun paint(focused: Boolean) {
                    background = TvVisual.tab(context, focused, isSelected)
                    setTextColor(if (isSelected) TvStyle.accent else if (focused) TvStyle.text else TvStyle.muted)
                    setTypeface(null, if (focused || isSelected) Typeface.BOLD else Typeface.NORMAL)
                }
                setOnClickListener { selectTab(pageIndex) }
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
