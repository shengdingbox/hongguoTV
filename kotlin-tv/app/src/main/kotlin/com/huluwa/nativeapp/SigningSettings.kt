// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import com.hongguotv.core.FridaSigner
import com.hongguotv.core.RemoteSigner
import com.hongguotv.core.SignerProfileStore
import com.hongguotv.core.UnidbgSigner

/**
 * 算法选择独立于设备档案保存，默认本地算法；切换回来继续复用本机匿名身份。
 *
 * 三种模式：
 * - [Mode.LOCAL]：本机算法，身份与签名都在设备上完成；
 * - [Mode.UNIDBG]：远程计算，签名交给 openhongguo-signer（unidbg 跑真实 so）；
 * - [Mode.FRIDA]：远程计算，签名交给内置的 Frida 服务。
 *
 * 存的是模式名而不是下标，新加模式不会让旧用户的选择漂移。
 */
object SigningSettings {
    private const val KEY = "nativeSigningMode"

    enum class Mode {
        LOCAL, UNIDBG, FRIDA;

        val label: String
            get() = when (this) {
                LOCAL -> "本地计算"
                UNIDBG -> "远程计算-unidbg"
                FRIDA -> "远程计算-frida"
            }
    }

    /** 弹框与设置页展示的顺序。 */
    val modes = listOf(Mode.LOCAL, Mode.UNIDBG, Mode.FRIDA)

    /** 当前模式；无法识别的历史取值回退到本地算法。 */
    var mode: Mode
        get() = when (StartupSettings.string(KEY, "local")) {
            "unidbg" -> Mode.UNIDBG
            // 旧版本只有「本地/远程」两档，存的是 frida，读出来就是远程计算-frida。
            "frida" -> Mode.FRIDA
            else -> Mode.LOCAL
        }
        set(value) = StartupSettings.set(
            KEY,
            when (value) {
                Mode.LOCAL -> "local"
                Mode.UNIDBG -> "unidbg"
                Mode.FRIDA -> "frida"
            },
        )

    val algorithmLabels = modes.map { it.label }
    val algorithmLabel: String
        get() = mode.label

    fun createSigner(store: SignerProfileStore): RemoteSigner = when (mode) {
        Mode.LOCAL -> RemoteSigner(store = store)
        // 与本地算法共用同一份匿名设备档案。
        Mode.UNIDBG -> UnidbgSigner(store = store)
        Mode.FRIDA -> FridaSigner()
    }
}
