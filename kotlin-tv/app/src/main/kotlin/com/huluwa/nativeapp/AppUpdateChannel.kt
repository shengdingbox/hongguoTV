// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.os.Build
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateChecker
import com.hongguotv.core.AppUpdateInfo

/** 冷启动和手动检查共用渠道；兼容版装在新系统上也继续获取兼容版更新。 */
internal object AppUpdateChannel {
    val variant: String get() = AppUpdate.variantFor(Build.VERSION.SDK_INT, BuildConfig.ANDROID5_COMPAT)

    fun check(deviceId: String? = null): AppUpdateInfo? = AppUpdateChecker().check(
        BuildConfig.VERSION_CODE, deviceId, BuildConfig.VERSION_NAME,
        sdkInt = Build.VERSION.SDK_INT, variant = variant,
    )

    fun accepts(update: AppUpdateInfo): Boolean = update.variant == variant &&
        (update.minSdk == 0 || update.minSdk <= Build.VERSION.SDK_INT)
}
