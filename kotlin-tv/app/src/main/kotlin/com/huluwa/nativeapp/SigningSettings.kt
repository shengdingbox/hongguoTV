// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import com.hongguotv.core.FridaSigner
import com.hongguotv.core.RemoteSigner
import com.hongguotv.core.SignerProfileStore

/** 开关独立于设备档案保存，切换回来继续复用原来的本机匿名身份。 */
object SigningSettings {
    private const val KEY = "nativeSigningMode"
    var nativeAlgorithms: Boolean
        get() = StartupSettings.string(KEY, "frida") == "local"
        set(value) = StartupSettings.set(KEY, if (value) "local" else "frida")

    fun createSigner(store: SignerProfileStore): RemoteSigner =
        if (nativeAlgorithms) RemoteSigner(store = store) else FridaSigner()
}
