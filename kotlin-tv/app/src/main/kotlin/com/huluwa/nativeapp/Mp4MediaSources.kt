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

/** 在线、缓存与离线播放均为 MP4，不通过默认工厂引入其他容器的提取器。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object Mp4MediaSources {
    private val extractors = ExtractorsFactory {
        // 每次建源都创建独立提取器，保留 Media3 默认的分片 MP4 探测顺序和字幕处理。
        val subtitles = DefaultSubtitleParserFactory()
        val flags = Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES or
            Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265
        // 让提取器标记 GOP 内可丢弃的样本，B 帧片源起播和追帧时无需等待全部依赖样本。
        arrayOf<Extractor>(FragmentedMp4Extractor(subtitles), Mp4Extractor(subtitles, flags))
    }

    // 播放器和预加载器始终接收已经绑定 VideoDataSource 的 MediaSource；
    // 其占位工厂不直接打开 hongguotv:// 地址，也不创建默认网络或容器工厂。
    fun factory(dataSourceFactory: DataSource.Factory = PlaceholderDataSource.FACTORY): ProgressiveMediaSource.Factory =
        ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
}
