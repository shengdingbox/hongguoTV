// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.PlaybackAlgorithmChange
import com.hongguotv.core.PlaybackAlgorithm
import com.hongguotv.core.PlaybackAlgorithmPolicy
import com.hongguotv.core.InMemorySignerProfileStore
import com.hongguotv.core.SignerProfileStore

/**
 * 应用内唯一的播放算法策略。
 *
 * 播放页、首页预览与离线下载共用同一个实例，所以“连续两次取址失败”不会因页面不同被拆开；
 * 自动切换写回 SigningSettings，下一次冷启动继续沿用。
 */
object PlaybackAlgorithms {
    @Volatile private var instance: PlaybackAlgorithmPolicy? = null
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun shared(context: Context, store: SignerProfileStore): PlaybackAlgorithmPolicy {
        instance?.let { return it }
        val app = context.applicationContext
        val localSigner = SigningSettings.createSigner(SigningSettings.Mode.LOCAL, store)
        return PlaybackAlgorithmPolicy(
            initial = SigningSettings.toPlaybackAlgorithm(SigningSettings.mode),
            localFactory = { localSigner },
            localProbeFactory = {
                // 每次探测使用全新内存档案副本；失败不会残留坏身份，也不会改写正式档案。
                val probeStore = InMemorySignerProfileStore()
                val current = runCatching { localSigner.profile() }.getOrNull()
                if (current?.available == true) probeStore.save(current)
                SigningSettings.createSigner(SigningSettings.Mode.LOCAL, probeStore)
            },
            unidbgFactory = { SigningSettings.createSigner(SigningSettings.Mode.UNIDBG, store) },
            fridaFactory = { SigningSettings.createSigner(SigningSettings.Mode.FRIDA, store) },
            onChange = { change -> main.post { persist(change) } },
        ).also { instance = it }
    }

    /** 自动切换在主线程持久化，避免后台线程访问 SharedPreferences。 */
    private fun persist(change: PlaybackAlgorithmChange) {
        SigningSettings.mode = SigningSettings.modeOf(change.to)
    }

    /** 设置页手动切换时同步单例；如果尚未创建则不提前注册设备。 */
    fun select(algorithm: PlaybackAlgorithm) {
        instance?.select(algorithm)
    }
}
