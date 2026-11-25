// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Random
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 播放取址专用签名。
 *
 * 红果从 2026-11-10 起在播放模型接口校验真实的 X-Argus / X-Ladon；通用签名里的
 * 四字节时间戳占位会得到 HTTP 200 空响应。这里实现 Argus 551 信封和 34 轮 Ladon，
 * 只用于播放模型请求，其他接口继续使用 [RemoteSigner] 的通用六头签名。
 *
 * 移植自某 Apache-2.0 授权开源实现的 PR：
 * - X-Argus：protobuf bean → Simon 64/128 → 掩码翻转 → 标准 AES-128-CBC
 * - X-Ladon：34 轮 ARX，密钥由 nonce + aid 的 MD5 派生
 * - 播放 POST 不携带 X-Medusa；video_tab GET 继续使用原有通用签名
 */
internal object PlaybackSigning {
    private const val LICENSE_ID = "1611921764"
    private const val DEFAULT_AID = "1967"
    private val randomSource = SecureRandom()

    fun isPlayerPath(path: String): Boolean =
        path.startsWith("/novel/player/video_model/") || path.startsWith("/novel/player/multi_video_model/")

    private val ARGUS_SIGN_KEY = hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304")
    private val ARGUS_AES_KEY = hex("d31e3718288a1027baab59f146a09a9c")
    private val ARGUS_AES_IV = hex("ea180a0336ed352fcd24e4d50018ae54")
    private val ARGUS_MASK = byteArrayOf(0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte(), 0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte())
    private val ARGUS_TAIL = hex("eafb2cfe8568519154")

    /** 对完整播放 URL 生成专用签名头；[rawQuery] 是线上实际发送的查询串。 */
    fun headers(
        rawQuery: String,
        body: ByteArray?,
        khronos: Int,
        deviceId: String,
        installId: String,
        aid: String = queryValue(rawQuery, "aid").ifBlank { DEFAULT_AID },
        versionName: String = queryValue(rawQuery, "version_name"),
        random: Random = randomSource,
    ): Map<String, String> {
        val bodyMd5 = if (body == null || body.isEmpty()) ByteArray(16) else MessageDigest.getInstance("MD5").digest(body)
        val nonce = random.nextInt()
        return linkedMapOf(
            "x-argus" to argus(rawQuery, bodyMd5, khronos, nonce, aid, deviceId, installId, versionName),
            "x-ladon" to ladon(khronos, random.nextInt(), aid),
        )
    }

    internal fun argusForTest(
        rawQuery: String,
        body: ByteArray?,
        khronos: Int,
        nonce: Int,
        aid: String,
        deviceId: String,
        installId: String,
    ): String {
        val bodyMd5 = if (body == null || body.isEmpty()) ByteArray(16) else MessageDigest.getInstance("MD5").digest(body)
        return argus(rawQuery, bodyMd5, khronos, nonce, aid, deviceId, installId, queryValue(rawQuery, "version_name"))
    }

    internal fun ladonForTest(khronos: Int, nonce: Int, aid: String): String =
        ladon(khronos, nonce, aid)

    private fun argus(
        rawQuery: String,
        bodyMd5: ByteArray,
        khronos: Int,
        nonce: Int,
        aid: String,
        deviceId: String,
        installId: String,
        versionName: String,
    ): String {
        val queryBytes = if (rawQuery.isEmpty()) ByteArray(16) else rawQuery.toByteArray(Charsets.UTF_8)
        val bodyHash = sm3(bodyMd5)
        val queryHash = sm3(queryBytes)
        val counters = protoVarint(1, 1) + protoVarint(2, 1) + protoVarint(3, 1) + protoVarint(7, 0xc792ecccL)
        val device = protoBytes(1, "NX551J".toByteArray()) + protoVarint(2, 8196) + protoVarint(4, 0x80e0d800L)
        val bean = protoVarint(1, 1077940818) +
            protoVarint(2, 2) +
            protoVarint(3, nonce.toLong() and 0xffffffffL) +
            protoBytes(4, aid.toByteArray()) +
            protoBytes(5, deviceId.toByteArray()) +
            protoBytes(6, LICENSE_ID.toByteArray()) +
            protoBytes(7, versionName.toByteArray()) +
            protoBytes(8, "v04.04.05-ov-android".toByteArray()) +
            protoVarint(9, 134744640) +
            protoBytes(10, ByteArray(8)) +
            protoVarint(11, 0) +
            protoVarint(12, ((khronos.toLong() and 0xffffffffL) shl 1) and 0xffffffffL) +
            protoBytes(13, bodyHash.copyOfRange(0, 6)) +
            protoBytes(14, queryHash.copyOfRange(0, 6)) +
            protoBytes(15, counters) +
            protoBytes(16, installId.toByteArray()) +
            protoBytes(20, "none".toByteArray()) +
            protoVarint(21, 738) +
            protoBytes(23, device) +
            protoVarint(25, 2)

        val plain = simonEncrypt(pkcs7(bean))
        var frame = ByteArray(ARGUS_MASK.size + plain.size + ARGUS_TAIL.size)
        ARGUS_MASK.copyInto(frame, 0)
        plain.copyInto(frame, ARGUS_MASK.size)
        ARGUS_TAIL.copyInto(frame, ARGUS_MASK.size + plain.size)
        for (i in ARGUS_MASK.size until frame.size) frame[i] = (frame[i].u() xor ARGUS_MASK[i % ARGUS_MASK.size].u()).toByte()
        frame.reverse()
        val padded = pkcs7(frame + byteArrayOf(0x61, 0x6f))
        val cipher = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(ARGUS_AES_KEY, "AES"), IvParameterSpec(ARGUS_AES_IV))
            doFinal(padded)
        }
        return (byteArrayOf(0xf2.toByte(), 0x81.toByte()) + cipher).base64()
    }

    private fun ladon(khronos: Int, nonce: Int, aid: String): String {
        val seed = le32(nonce)
        val digest = MessageDigest.getInstance("MD5").digest(seed + aid.toByteArray())
        val key = digest.hex().toByteArray(Charsets.US_ASCII)
        val keys = LongArray(34)
        var a = le64(key, 0)
        var b = le64(key, 8)
        var c = le64(key, 16)
        var d = le64(key, 24)
        for (i in keys.indices) {
            keys[i] = a
            val next = (java.lang.Long.rotateRight(b, 8) + a) xor i.toLong()
            b = c; c = d; d = next
            a = next xor java.lang.Long.rotateLeft(a, 3)
        }
        val plain = pkcs7("${khronos.toLong() and 0xffffffffL}-$LICENSE_ID-$aid".toByteArray())
        var at = 0
        while (at < plain.size) {
            var left = le64(plain, at)
            var right = le64(plain, at + 8)
            for (keyRound in keys) {
                right = keyRound xor (left + java.lang.Long.rotateRight(right, 8))
                left = right xor java.lang.Long.rotateLeft(left, 3)
            }
            writeLe64(plain, at, left)
            writeLe64(plain, at + 8, right)
            at += 16
        }
        return (seed + plain).base64()
    }

    private fun simonEncrypt(plain: ByteArray): ByteArray {
        val keys = simonKeys()
        var at = 0
        while (at < plain.size) {
            var a = le64(plain, at)
            var b = le64(plain, at + 8)
            for (key in keys) {
                val next = a xor (java.lang.Long.rotateLeft(b, 1) and java.lang.Long.rotateLeft(b, 8)) xor
                    java.lang.Long.rotateLeft(b, 2) xor key
                a = b
                b = next
            }
            writeLe64(plain, at, a)
            writeLe64(plain, at + 8, b)
            at += 16
        }
        return plain
    }

    private fun simonKeys(): LongArray {
        val out = LongArray(72)
        for (i in 0 until 4) out[i] = le64(ARGUS_SIGN_KEY, i * 8)
        val z = 0x3dc94c3a046d678bL
        for (i in 4 until out.size) {
            var x = java.lang.Long.rotateRight(out[i - 1], 3) xor out[i - 3]
            x = x xor java.lang.Long.rotateRight(x, 1)
            out[i] = out[i - 4].inv() xor x xor ((z ushr ((i - 4) % 62)) and 1L) xor 3L
        }
        return out
    }

    private fun pkcs7(data: ByteArray): ByteArray {
        val pad = 16 - data.size % 16
        return data + ByteArray(pad) { pad.toByte() }
    }

    private fun protoVarint(tag: Int, value: Long): ByteArray = protoKey(tag, 0) + varint(value)
    private fun protoBytes(tag: Int, body: ByteArray): ByteArray = protoKey(tag, 2) + varint(body.size.toLong()) + body
    private fun protoKey(tag: Int, wire: Int): ByteArray = varint((tag.toLong() shl 3) or wire.toLong())
    private fun varint(value: Long): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        while (n > 127) {
            out.write(((n and 127) or 128).toInt())
            n = n ushr 7
        }
        out.write(n.toInt())
        return out.toByteArray()
    }

    private fun queryValue(query: String, name: String): String =
        query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=', "")
            if (URLDecoder.decode(key, "UTF-8") == name) URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") else null
        }.orEmpty()

    private fun le64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((data[offset + i].u().toLong()) shl (i * 8))
        return value
    }

    private fun writeLe64(data: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) data[offset + i] = (value ushr (i * 8)).toByte()
    }
}
