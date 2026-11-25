// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import com.hongguotv.core.PlaybackAlgorithm
import com.hongguotv.core.PlaybackAlgorithmPolicy
import com.hongguotv.core.RemoteSigner
import com.hongguotv.core.SignerProfileStore

/** 应用内唯一的算法策略；自动降级不写回设置，冷启动仍从本地开始。 */
object PlaybackAlgorithms {
    @Volatile private var instance: PlaybackAlgorithmPolicy? = null

    @Synchronized
    fun shared(context: Context, store: SignerProfileStore): PlaybackAlgorithmPolicy {
        instance?.let { return it }
        val localSigner = SigningSettings.createSigner(SigningSettings.Mode.LOCAL, store)
        return PlaybackAlgorithmPolicy(
            initial = SigningSettings.toPlaybackAlgorithm(SigningSettings.selectedAlgorithm),
            automatic = SigningSettings.automaticSwitching,
            localFactory = { localSigner },
            unidbgFactory = { SigningSettings.createSigner(SigningSettings.Mode.UNIDBG, store) },
            fridaFactory = { SigningSettings.createSigner(SigningSettings.Mode.FRIDA, store) },
            customFactory = { SigningSettings.createSigner(store) },
        ).also { instance = it }
    }

    /** 手动配置只影响后续取址；自动策略的临时选择不覆盖用户保存的模式。 */
    fun select(algorithm: PlaybackAlgorithm, factory: (() -> RemoteSigner)? = null) {
        instance?.select(algorithm, factory)
    }
}
