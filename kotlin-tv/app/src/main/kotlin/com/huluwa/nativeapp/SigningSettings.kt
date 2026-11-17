// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import com.hongguotv.core.FridaSigner
import com.hongguotv.core.RemoteSigner
import com.hongguotv.core.SignerProfileStore

/** 算法选择独立于设备档案保存，默认本地算法；切换回来继续复用本机匿名身份。 */
object SigningSettings {
    private const val KEY = "nativeSigningMode"
    var nativeAlgorithms: Boolean
        get() = StartupSettings.string(KEY, "local") != "frida"
        set(value) = StartupSettings.set(KEY, if (value) "local" else "frida")

    val algorithmLabels = listOf("本地计算", "远程计算")
    val algorithmLabel: String
        get() = algorithmLabels[if (nativeAlgorithms) 0 else 1]

    fun createSigner(store: SignerProfileStore): RemoteSigner =
        if (nativeAlgorithms) RemoteSigner(store = store) else FridaSigner()
}
