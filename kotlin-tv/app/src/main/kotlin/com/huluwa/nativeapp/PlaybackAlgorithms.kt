// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.PlaybackAlgorithm
import com.hongguotv.core.PlaybackAlgorithmChange
import com.hongguotv.core.PlaybackAlgorithmPolicy
import com.hongguotv.core.RemoteSigner
import com.hongguotv.core.SignerProfileStore

/**
 * 应用内唯一的播放算法策略。
 *
 * 播放页、首页预览与离线下载共用同一个实例，统一累计当前算法的连续取址失败；
 * 算法仅在用户选择后切换，并写回 SigningSettings，下一次冷启动继续沿用。
 */
object PlaybackAlgorithms {
    @Volatile private var instance: PlaybackAlgorithmPolicy? = null
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun shared(context: Context, store: SignerProfileStore): PlaybackAlgorithmPolicy {
        instance?.let { return it }
        val localSigner = SigningSettings.createSigner(SigningSettings.Mode.LOCAL, store)
        return PlaybackAlgorithmPolicy(
            initial = SigningSettings.toPlaybackAlgorithm(SigningSettings.selectedAlgorithm),
            localFactory = { localSigner },
            unidbgFactory = { SigningSettings.createSigner(SigningSettings.Mode.UNIDBG, store) },
            fridaFactory = { SigningSettings.createSigner(SigningSettings.Mode.FRIDA, store) },
            customFactory = { SigningSettings.createSigner(store) },
            onChange = { change -> main.post { persist(change) } },
        ).also { instance = it }
    }

    /** 用户选择在主线程持久化；自定义项的具体 ID 已由 [SigningSettings.select] 写入。 */
    private fun persist(change: PlaybackAlgorithmChange) {
        if (change.to == PlaybackAlgorithm.CUSTOM) {
            SigningSettings.mode = SigningSettings.Mode.CUSTOM
        } else {
            SigningSettings.mode = SigningSettings.modeOf(change.to)
        }
    }

    /** 设置页手动切换时同步单例；自定义项必须同时带入具体签名器。 */
    fun select(algorithm: PlaybackAlgorithm, factory: (() -> RemoteSigner)? = null) {
        instance?.select(algorithm, factory)
    }
}
