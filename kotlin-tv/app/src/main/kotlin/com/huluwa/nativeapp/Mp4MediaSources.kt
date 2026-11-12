// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory

/** 仅创建普通和分片 MP4 提取器，避免通用格式工厂保留无关解析代码。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object Mp4MediaSources {
    private val extractorsFactory = ExtractorsFactory {
        // 沿用 Media3 默认的字幕转码行为；每个媒体源使用独立的提取器实例。
        val subtitles = DefaultSubtitleParserFactory()
        arrayOf<Extractor>(FragmentedMp4Extractor(subtitles), Mp4Extractor(subtitles))
    }

    fun factory(dataSourceFactory: DataSource.Factory): ProgressiveMediaSource.Factory =
        ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)

    /** 实际播放和预加载均传入已绑定 VideoDataSource 的媒体源，此工厂仅供构造组件。 */
    fun explicitSourceFactory(): ProgressiveMediaSource.Factory = factory(PlaceholderDataSource.FACTORY)
}
