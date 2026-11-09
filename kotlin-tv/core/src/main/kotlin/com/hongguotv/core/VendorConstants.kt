// Derived from drpys 22261ad; GPL-3.0. See kotlin-tv/NOTICE.md.
package com.hongguotv.core

/**
 * 客户端标识与设备档案兜底值。
 *
 * 内置签名算法已移除：访问红果官方接口所需的设备身份由局域网签名服务下发
 * （见 [RemoteSigner]），这里的 [device] 只用于两处：
 * 1. 账号登录时的匿名设备注册（平台不要求签名）；
 * 2. 签名服务暂时不可用时，让界面仍能给出可读的错误提示。
 */
internal object VendorConstants {
    const val VIDEO_UA =
        "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"
    const val APP_UA = "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"

    val device: Map<String, String>
        get() =
            linkedMapOf(
                "ac" to "wifi",
                "channel" to "update_64",
                "aid" to "8662",
                "app_name" to "novelread",
                "version_code" to "71332",
                "version_name" to "7.1.3.32",
                "device_platform" to "android",
                "os" to "android",
                "ssmix" to "a",
                "device_type" to "25053RT47C",
                "device_brand" to "Redmi",
                "language" to "zh",
                "os_api" to "36",
                "os_version" to "16",
                "manifest_version_code" to "71332",
                "resolution" to "1280*2772",
                "dpi" to "520",
                "update_version_code" to "71332",
                "host_abi" to "arm64-v8a",
                "dragon_device_type" to "phone",
                "pv_player" to "71332",
                "compliance_status" to "0",
                "need_personal_recommend" to "1",
                "player_so_load" to "1",
                "is_android_pad_screen" to "0",
            )
}
