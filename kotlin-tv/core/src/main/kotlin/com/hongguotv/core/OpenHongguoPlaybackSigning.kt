// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.Random
import java.util.concurrent.atomic.AtomicLong

/**
 * openhongguo PR #25 的本地播放签名，移植自 internal/protocol/sign。
 * 来源：https://github.com/beilunyang/openhongguo/pull/25
 *
 * V5 Medusa 使用设备布局、field21=369 和 04/05 标志；Gorgon、Helios 与其配套。
 * Argus/Ladon 绑定最终查询串和原始请求体，发送时必须同时保留 Medusa。
 * 既有 74532 专用接口仍由 PlaybackV5Signer 处理，不混用两种请求口径。
 */
internal object OpenHongguoPlaybackSigning {
    private const val SDK_CODE = 0x040a0300
    private const val UNSET = "!notset!"
    private val flags = byteArrayOf(4, 5, 0, 0, 0, 0, 0, 0)
    private val launched = System.currentTimeMillis()
    private val processSeed = System.nanoTime() and Long.MAX_VALUE
    private val pid = 3000L + processSeed % 25000L
    private val ppid = 500L + (processSeed / 25000L) % 700L
    private val counter = AtomicLong(20L + processSeed % 100L)
    private val deviceKeys = listOf(
        "iid", "device_id", "ac", "channel", "aid", "app_name", "cdid", "version_code",
        "version_name", "device_platform", "os", "ssmix", "device_type", "device_brand",
        "language", "os_api", "os_version", "manifest_version_code", "resolution", "dpi",
        "update_version_code", "host_abi", "dragon_device_type", "pv_player", "compliance_status",
        "need_personal_recommend", "player_so_load", "is_android_pad_screen", "openudid",
    )

    /** 与 SDK PlaybackBody 一致；按 Go JSON 的键顺序生成后，原字节同时用于签名和发送。 */
    fun body(id: String): ByteArray {
        require(id.matches(Regex("[0-9]{1,30}")))
        return """{"biz_param":{"need_all_video_definition":true,"video_id_type":0,"video_platform":3},"video_id":"$id"}"""
            .toByteArray(Charsets.UTF_8)
    }

    fun sign(
        url: String,
        values: Map<String, String>,
        body: ByteArray?,
        profile: SignerProfile,
        nowMillis: Long,
        random: Random,
    ): SignedRequest {
        // 对齐 SDK DeviceQueryPairs；查询顺序固定，设备字段继续复用本机注册档案。
        val ordered = linkedMapOf<String, String>()
        for (key in deviceKeys) values[key]?.let { ordered[key] = it }
        for ((key, value) in values) {
            if (key !in ordered && key !in setOf("ts", "_rticket", "user_is_login")) ordered[key] = value
        }
        val bodyMd5 = body?.let { digest("MD5", it) } ?: ByteArray(16)
        val entropy = drawEntropy(nowMillis, random)
        // SDK 支持分支 5 和 12；这里固定选择同一协议的分支 12，避免沿用旧版 F13。
        for (offset in 0L until 256L) {
            val ticket = nowMillis + offset
            val ts = (ticket / 1000L).toInt()
            val query = encodeSdkQuery(ordered + linkedMapOf("ts" to ts.toString(), "_rticket" to ticket.toString()))
            val querySm3 = sm3(query.toByteArray(Charsets.UTF_8))
            if (getIv(getIv(getIv(0x20230928, querySm3), bodyMd5), le32(ts)) and 15 != 12) continue
            val f13 = field13(querySm3, bodyMd5, ts)
            val headers = linkedMapOf(
                "Accept" to "application/json; charset=utf-8,application/x-protobuf",
                "x-xs-from-web" to "0",
                "x-ss-req-ticket" to ticket.toString(),
                "x-tt-request-tag" to "t=0;n=0",
                "sdk-version" to "2",
                "passport-sdk-version" to "50561",
                "x-vc-bdturing-sdk-version" to "3.7.2.cn",
                "x-khronos" to ts.toString(),
                "x-gorgon" to xGorgon(query, body, body != null, ts, entropy.gorgonRandom, SDK_CODE, flags),
                "x-helios" to assembleHelios(ts, entropy.heliosRandom, PlaybackV5Constants.license),
                "x-medusa" to medusa(querySm3, f13, ts, values, entropy),
                "x-tt-dt" to "",
            )
            headers.putAll(PlaybackSigning.headers(
                query, body, ts, profile.deviceId, profile.installId,
                versionName = values["version_name"].orEmpty(), random = random,
            ))
            if (body != null) {
                headers["Content-Type"] = "application/json; charset=UTF-8"
                headers["x-ss-stub"] = bodyMd5.hex().uppercase(java.util.Locale.ROOT)
            }
            return SignedRequest("${url.substringBefore('?')}?$query", headers, body)
        }
        throw SignatureException("无法生成 openhongguo 播放签名，请重试")
    }

    /** V5 分支 12 的摘要，尾部只处理前 8 字节，与旧版的 12 字节算法不同。 */
    internal fun field13(querySm3: ByteArray, bodyMd5: ByteArray, ts: Int): ByteArray {
        val seed = 0x9d4a97c2.toInt()
        val count = seed + ts
        val table = IntArray(64) { PlaybackV5Constants.hashTable[it].ror(count + 1) }
        val padding = byteArrayOf(0xfa.toByte(), 0x45, 0x61, 0xd7.toByte()).map {
            val byte = it.u()
            ((byte or (byte shl 8)) ushr ((count + 2) and 7)).toByte()
        }.toByteArray()
        val input = querySm3 + bodyMd5 + le32(ts) + padding + hex("00000000000001a0")
        val words = IntArray(128)
        for (i in 0 until 16) words[i] = input.i32(i * 4)
        for (i in 0 until 112) {
            val x = words[i + 1]
            val y = words[i + 14]
            words[i + 16] = words[i] + words[i + 9] +
                (x.rol(14) xor x.rol(25) xor (x ushr 3)) +
                (y.rol(13) xor y.rol(15) xor (y ushr 10))
        }
        val initial = intArrayOf(
            0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f,
            984304912, 3005047866L.toInt(), 2874125293L.toInt(), 2152413264L.toInt(),
        ).map { it.ror(count) }
        val state = initial.toIntArray()
        for (i in 0 until 96) {
            val base = seed + i
            val choose = ((state[4] xor state[0]) and state[1]) xor state[4]
            val rotate = state[1].rol(26) xor state[1].rol(21) xor state[1].rol(7)
            val next = words[base and 127] + choose + rotate + table[base and 63] + state[6]
            val majority = (state[3] and state[5]) or ((state[3] or state[5]) and state[2])
            val rotated = state[3].rol(30) xor state[3].rol(19) xor state[3].rol(10)
            val old = state[7]
            System.arraycopy(state, 0, state, 1, 7)
            state[7] = rotated + majority + next
            state[0] = old + next
        }
        val output = ByteArray(32)
        for (i in 0 until 8) be32(state[i] + initial[i]).copyInto(output, i * 4)
        val head = ByteArray(16) { (output[it].u() xor output[it + 16].u()).toByte() }
        var hash = 0x20220420
        for (i in 0 until 8) {
            hash = if (i and 1 == 0) {
                (hash shl 7) xor head[i].u() xor (hash ushr 3) xor hash
            } else {
                (((hash shl 11) or head[i].u()) xor (hash ushr 5) xor hash).inv()
            }
        }
        val tail = le32(hash)
        tail[0] = (tail[0].u() and 0xdb).toByte()
        tail[3] = (tail[3].u() xor 1).toByte()
        return head + tail
    }

    private fun medusa(querySm3: ByteArray, f13: ByteArray, ts: Int, who: Map<String, String>, entropy: SignEntropy): String {
        val messageRandom = entropy.messageRandom and Int.MAX_VALUE
        val keyRandom = entropy.random and Int.MAX_VALUE
        val packedRandom = entropy.packedRandom and Int.MAX_VALUE
        val deviceId = who["device_id"].orEmpty()
        val version = who["version_name"].orEmpty()
        val counters = protoSInt(1, counter.incrementAndGet()) + protoSInt(2, 5) +
            protoSInt(3, 694367) + protoSInt(5, 694367)
        val env = protoSInt(1, (ts.toLong() - launched / 1000L).coerceAtLeast(1L)) +
            protoString(6, "v04.10.03.04-bugfix") + protoSInt(7, pid) +
            protoString(8, "xiaomi_8662_64") +
            protoBytes(12, deviceBlock(who, ts.toLong() * 1000L + messageRandom % 1000)) +
            protoKey(13, 2) + byteArrayOf(0) + protoString(14, version.ifEmpty { UNSET }) +
            protoSInt(15, ppid) + protoSInt(18, ts.toLong() + 766353820L) +
            protoSInt(19, ts.toLong() + 16L) + protoSInt(20, ts.toLong() - 1072050540L)
        val message = protoBytes(1, PlaybackV5Constants.magic) + protoSInt(2, 5) +
            protoSInt(3, messageRandom.toLong()) + protoString(4, SIGN_APP_ID) +
            protoString(5, deviceId) + protoString(6, PlaybackV5Constants.license) +
            protoString(7, version) + protoString(8, "v04.10.03-ml-android") +
            protoSInt(9, SDK_CODE.toLong()) + protoBytes(10, flags) + protoSInt(12, ts.toLong()) +
            protoBytes(13, f13) + protoBytes(14, querySm3.copyOf(6)) + protoBytes(15, counters) +
            protoSInt(17, ts.toLong()) + protoString(20, "none") + protoSInt(21, 369) +
            protoBytes(23, env) + protoString(24,
                """{"cmr":16777216,"cmr2":16777216,"un_h":2398682137,"vpn":0,"sts":27,"kd":0,"fkd":1963693275,"pd":1442983616,"lp":"2|520830913092|521433182354|2078052786776|1081701896","fl":"0|0|2523408538|2586339478|997724116|0|0|0|0|606600138","dyn":"","do":0,"tk":true}""",
            )
        val (hash, seed) = keyHash(PlaybackV5Constants.signKey, keyRandom)
        val transformed = (flags + xmxor(message, hash)).reversedArray()
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = 0x18000001 or ((querySm3[0].u() and 63) shl 14) or ((f13[0].u() and 63) shl 8)
        val packed = byteArrayOf(0xca.toByte()) + le32(packedRandom) + le32(check) + transformed +
            byteArrayOf((keyRandom ushr 16).toByte(), (keyRandom ushr 24).toByte())
        val key = PlaybackV5Constants.signKey
        val encrypted = PlaybackV5Aes(digest("MD5", key.copyOfRange(0, 16)), ts)
            .encrypt(packed, digest("MD5", key.copyOfRange(16, 32)))
        val prefix = le32(5) + PlaybackV5Constants.magic
        for (i in prefix.indices) prefix[i] = (prefix[i].u() xor le32(ts)[i and 3].u()).toByte()
        return (prefix + byteArrayOf(keyRandom.toByte(), (keyRandom ushr 8).toByte(), 0, 1) + encrypted).base64()
    }

    /** 同一匿名设备稳定派生安装时间、存储和内存，布局与 SDK medusa_device.go 保持一致。 */
    private fun deviceBlock(who: Map<String, String>, nowMillis: Long): ByteArray {
        val hash = digest("SHA-256", ("medusa-device\u0000" + who["device_id"].orEmpty()).toByteArray(Charsets.UTF_8))
        fun pick(index: Int, modulus: Long) = (hash.i32Le(index * 4).toLong() and 0xffffffffL) % modulus
        val storage = floatArrayOf(106.83f, 108.57f, 229.36f, 233.91f, 487.62f)[pick(0, 5).toInt()] + pick(1, 400).toFloat() / 1000f
        val memory = floatArrayOf(5.37f, 7.42f, 11.18f, 15.07f)[pick(2, 4).toInt()] + pick(3, 90).toFloat() / 1000f
        val freeStorage = storage * (0.25f + pick(5, 500).toFloat() / 1000f)
        val freeMemory = 0.35f + pick(6, 900).toFloat() / 1000f
        val day = 86_400_000L
        val installed = 1795440816000L - day - pick(4, 20L * day)
        fun text(tag: Int, key: String) = protoString(tag, who[key].orEmpty().ifEmpty { UNSET })
        return protoSInt(1, 1) + protoString(3, SIGN_APP_ID) + protoString(4, who["device_id"].orEmpty()) +
            protoString(5, UNSET) + protoString(6, "!noperm!") + protoSInt(7, -888888) +
            protoSInt(8, -888888) + protoSInt(9, 2) + protoSInt(10, -888888) +
            protoString(11, UNSET) + protoString(12, "Asia/Shanghai,8") + protoString(13, "zh_CN") +
            protoSInt(14, 8) + protoFloat(16, storage) + protoFloat(17, freeStorage) +
            protoFloat(18, memory) + protoFloat(19, freeMemory) + protoFloat(20, storage * 2f) +
            protoFloat(21, freeStorage * 2f) + text(22, "os_version") + protoSInt(23, 60) +
            protoSInt(24, 10) + protoSInt(25, installed) + protoSInt(26, launched + 540L) +
            protoSInt(27, installed) + protoSInt(28, nowMillis) + protoSInt(29, 1) +
            text(30, "device_type") + text(31, "device_brand") + protoString(32, UNSET) +
            protoString(33, UNSET) + protoString(34, UNSET) + text(35, "device_brand") +
            protoString(36, UNSET) + protoSInt(38, 3) + protoSInt(40, launched)
    }

    /** SDK 按 encodeURIComponent 编码，保留 Java URLEncoder 额外转义的符号。 */
    private fun encodeSdkQuery(values: Map<String, String>): String {
        fun component(value: String) = encodePart(value).replace("%21", "!")
            .replace("%27", "'").replace("%28", "(").replace("%29", ")")
        return values.entries.joinToString("&") { (key, value) -> "${component(key)}=${component(value)}" }
    }
}
