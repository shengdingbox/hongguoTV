// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun Byte.u() = toInt() and 255
internal fun hex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.u()) }
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private val BASE64_VALUES = IntArray(128) { -1 }.apply {
    BASE64_ALPHABET.forEachIndexed { index, char -> this[char.code] = index }
}

/** 纯 Kotlin 标准 Base64 解码，避开仅在 Android 8+ 提供的 java.util.Base64。 */
internal fun decode64(s: String): ByteArray {
    val clean = s.trim().replace('-', '+').replace('_', '/')
    require(clean.length % 4 != 1) { "Base64 长度无效" }
    val padding = clean.indexOf('=')
    val end = if (padding >= 0) padding else clean.length
    val output = ByteArray((end * 3) / 4)
    var buffer = 0
    var bits = 0
    var written = 0
    for (i in 0 until end) {
        val value = BASE64_VALUES[clean[i].code]
        require(value >= 0) { "Base64 字符无效" }
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output[written++] = (buffer ushr bits).toByte()
            buffer = buffer and ((1 shl bits) - 1)
        }
    }
    return if (written == output.size) output else output.copyOf(written)
}

/** 纯 Kotlin 标准 Base64 编码，输出与 java.util.Base64 完全一致。 */
internal fun ByteArray.base64(): String {
    val out = StringBuilder((size + 2) / 3 * 4)
    var index = 0
    while (index < size) {
        val first = this[index++].toInt() and 0xff
        val second = if (index < size) this[index++].toInt() and 0xff else -1
        val third = if (index < size) this[index++].toInt() and 0xff else -1
        out.append(BASE64_ALPHABET[first ushr 2])
        out.append(BASE64_ALPHABET[((first and 0x03) shl 4) or (if (second >= 0) second ushr 4 else 0)])
        out.append(if (second >= 0) BASE64_ALPHABET[((second and 0x0f) shl 2) or (if (third >= 0) third ushr 6 else 0)] else '=')
        out.append(if (third >= 0) BASE64_ALPHABET[third and 0x3f] else '=')
    }
    return out.toString()
}
internal fun le32(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
internal fun be32(n: Int) = ByteBuffer.allocate(4).putInt(n).array()
internal fun le64(n: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array()
internal fun ByteArray.i32(at: Int, little: Boolean = false) = ByteBuffer.wrap(this, at, 4).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int
internal fun ByteArray.u32(at: Int) = i32(at).toLong() and 0xffffffffL
internal fun ByteArray.i64(at: Int, little: Boolean = false) = ByteBuffer.wrap(this, at, 8).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).long
internal fun digest(algorithm: String, value: ByteArray): ByteArray = MessageDigest.getInstance(algorithm).digest(value)
internal fun aes(mode: String, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/$mode/NoPadding").run {
    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data)
}
internal fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].u() xor b[it % b.size].u()).toByte() }
