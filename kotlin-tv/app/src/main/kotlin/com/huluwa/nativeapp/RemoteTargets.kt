package com.huluwa.nativeapp

import com.hongguotv.core.EpisodeGroupDetail
import com.hongguotv.core.PlaybackSource
import com.hongguotv.core.RemoteCommand
import com.hongguotv.core.RemoteTarget

/** 用回调连接播放器私有状态与手机遥控，提供命令执行及剧集列表。 */
class LambdaTarget(
    override val source: PlaybackSource,
    private val handler: (RemoteCommand) -> Boolean,
    private val episodesProvider: () -> List<EpisodeGroupDetail>
) : RemoteTarget {
    override fun apply(cmd: RemoteCommand): Boolean = handler(cmd)
    override fun episodes(): List<EpisodeGroupDetail> = episodesProvider()
}
