// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 算法检测共用同一份播放签名输入，避免列表测速与正式 video_tab 请求的参数口径漂移。 */
internal object PlaybackProbe {
    fun signatureValues(id: String, profile: SignerProfile): Map<String, String> {
        fun identity(key: String, fallback: String) = profile.params[key]?.takeIf { it.isNotBlank() } ?: fallback
        // 没有 cdid 的旧档案也必须完成一次签名尝试，由签名器统一返回“设备尚未就绪”的失败原因。
        return linkedMapOf(
            "device_platform" to "android", "os" to "android", "ssmix" to "a",
            "cdid" to profile.params["cdid"].orEmpty(),
            "channel" to identity("channel", "vivo_8662_64"),
            "aid" to identity("aid", "8662"), "app_name" to identity("app_name", "novelread"),
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "manifest_version_code" to "74332", "update_version_code" to "74332",
            "resolution" to identity("resolution", "1260*2800"), "dpi" to identity("dpi", "480"),
            "device_type" to identity("device_type", "PGT-AN10"),
            "device_brand" to identity("device_brand", "HONOR"),
            "language" to identity("language", "zh"), "os_api" to identity("os_api", "31"),
            "os_version" to identity("os_version", "12"), "ac" to identity("ac", "wifi"),
            "host_abi" to "arm64-v8a", "dragon_device_type" to "phone",
            "pv_player" to "74332", "compliance_status" to "0",
            "need_personal_recommend" to "1", "player_so_load" to "1",
            "is_android_pad_screen" to "0",
            "iid" to profile.installId, "device_id" to profile.deviceId,
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
        )
    }
}
