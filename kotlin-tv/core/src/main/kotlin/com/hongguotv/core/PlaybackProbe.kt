// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 一次播放签名测速的结果；只含签名自身耗时，不含播放内容接口。 */
data class PlaybackSignatureProbe(val elapsedMs: Long)

/** core 不依赖 Android；用 JVM 单调时钟统计签名耗时。 */
internal fun signatureElapsedMillis(started: Long): Long =
    (System.nanoTime() - started) / 1_000_000L

/** 算法检测共用同一份播放签名输入，避免列表测速与正式 video_tab 请求的参数口径漂移。 */
internal object PlaybackProbe {
    fun signatureValues(id: String, profile: SignerProfile): Map<String, String> {
        // 没有 cdid 的旧档案也必须完成一次签名尝试，由签名器统一返回“设备尚未就绪”的失败原因。
        return linkedMapOf(
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74532", "version_name" to "7.4.5.32",
            "manifest_version_code" to "74532", "update_version_code" to "74532",
            "os" to "android", "ssmix" to "a", "channel" to "update_64",
            "device_platform" to "android", "device_type" to "25053RT47C",
            "device_brand" to "Redmi", "os_version" to "16", "os_api" to "36",
            "ac" to "wifi", "language" to "zh", "host_abi" to "arm64-v8a",
            "iid" to profile.installId, "device_id" to profile.deviceId,
            "cdid" to profile.params["cdid"].orEmpty(),
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0",
            "use_os_player" to "0", "video_platform" to "1024", "source" to "0",
            "from_video_id" to "",
        )
    }
}
