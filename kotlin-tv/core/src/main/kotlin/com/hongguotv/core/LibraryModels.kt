// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

data class LibraryProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val completed: Boolean,val updatedAt: Long,
    val watchedEpisodeIds: Set<String> = emptySet()) {
    companion object {
        const val MAX_WATCHED_EPISODES = 10_000
    }

    /** Keep actual episode IDs: skipped episodes and other seasons are never inferred as watched. */
    fun completedEpisodes(): Set<String> = if (completed || (duration > 0 && position >= duration - duration / 10))
        watchedEpisodeIds + episodeId else watchedEpisodeIds

    fun withWatchedEpisodes(previous: Set<String> = emptySet()) = copy(
        watchedEpisodeIds = (previous + completedEpisodes()).toList().takeLast(MAX_WATCHED_EPISODES).toSet())
}
data class LibrarySettings(val quality: Int=1080,val speed: Float=1f,val autoNext: Boolean=true,val type: ContentType=ContentType.COMIC,val frame: VideoFrameMode=VideoFrameMode.FIT,val autoSeason: Boolean=true,val danmaku: Boolean=true,
    val danmakuOpacity: Int=DanmakuPrefs.OPACITY_DEFAULT,val danmakuFont: Int=DanmakuPrefs.FONT_DEFAULT,val danmakuSpeed: Int=DanmakuPrefs.SPEED_DEFAULT,val danmakuRows: Int=DanmakuPrefs.ROWS_DEFAULT,
    val showClock: Boolean=true,val episodeCache: Boolean=true)
data class LibraryData(val favorites: List<Series>,val history: List<LibraryProgress>,val watched: Set<String>,val searches: List<RecentSearch>,val settings: LibrarySettings,val later: List<Series> = emptyList(),val hidden: Set<String> = emptySet(),val favoriteGroups: Map<String,String> = emptyMap(),val reservations: Set<String> = emptySet())

