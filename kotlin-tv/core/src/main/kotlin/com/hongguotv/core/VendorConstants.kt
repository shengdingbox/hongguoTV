// SPDX-License-Identifier: GPL-3.0-only
// GPL-3.0 derived; see README.md 的来源与声明 section.
package com.hongguotv.core

/**
 * 客户端标识与设备档案取值。
 *
 * 签名完全在本机完成（见 [RemoteSigner]），这里的 [device] 同时用于：
 * 1. 匿名设备注册（[DeviceRegister]）——平台按注册身份放行；
 * 2. 请求参数里的机型 / 系统版本等指纹，必须与注册时的声明一致。
 *
 * 这些值改动会同时改变注册报文与签名指纹，不要单独改其中一处。
 */
internal object VendorConstants {
    const val VIDEO_UA =
        "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 2026-03-03 QuicVersion:21ac1950 2025-11-18)"
    const val APP_UA = VIDEO_UA

    val device: Map<String, String>
        get() =
            linkedMapOf(
                "ac" to "wifi",
                "channel" to "xiaomi_8662_64",
                "aid" to "8662",
                "app_name" to "novelread",
                "version_code" to "73932",
                "version_name" to "7.3.9.32",
                "device_platform" to "android",
                "os" to "android",
                "ssmix" to "a",
                "device_type" to "23127PN0CC",
                "device_brand" to "Xiaomi",
                "language" to "zh",
                "os_api" to "34",
                "os_version" to "14",
                "manifest_version_code" to "73932",
                "resolution" to "1200*2670",
                "dpi" to "460",
                "update_version_code" to "73932",
                "host_abi" to "arm64-v8a",
                "dragon_device_type" to "phone",
                "pv_player" to "73932",
                "compliance_status" to "0",
                "need_personal_recommend" to "1",
                "player_so_load" to "1",
                "is_android_pad_screen" to "0",
            )
}
