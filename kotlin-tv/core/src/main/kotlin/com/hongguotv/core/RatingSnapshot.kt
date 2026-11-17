// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

/** 详情页评分的本机快照；评分变化不频繁，不设置有效期，作为离线兜底和首屏快速展示。 */
data class CachedRating(
    val seriesId: String,
    val rating: Double,
    val ratingCount: String,
    val savedAt: Long,
)

/** SharedPreferences 使用的小体积评分快照，读写失败或旧数据损坏时直接回落为空。 */
object RatingSnapshot {
    const val MAX_ENTRIES = 200
    private const val MAX_LENGTH = 2_048
    private val idPattern = Regex("[0-9]{1,30}")

    fun validId(id: String) = idPattern.matches(id) && id != "0"

    fun validRating(rating: Double) = rating.isFinite() && rating > 0.0 && rating <= 10.0

    fun encode(cached: CachedRating): String = JSONObject()
        .put("version", 1)
        .put("seriesId", cached.seriesId)
        .put("rating", cached.rating)
        .put("ratingCount", cached.ratingCount.take(20))
        .put("savedAt", cached.savedAt)
        .toString()

    fun decode(value: String): CachedRating? = runCatching {
        require(value.length <= MAX_LENGTH)
        val root = JSONObject(value)
        require(root.getInt("version") == 1)
        val seriesId = root.getString("seriesId")
        require(validId(seriesId))
        val rating = root.getDouble("rating")
        require(validRating(rating))
        val ratingCount = root.optString("ratingCount", "").trim().take(20)
        val savedAt = root.getLong("savedAt")
        require(savedAt > 0)
        CachedRating(seriesId, rating, ratingCount, savedAt)
    }.getOrNull()
}
