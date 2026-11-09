// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 签名原语：SM3、GF(2^8) 运算、魔改 AES-V3、protobuf 编码。
 *
 * 算法移植自 openhongguo `internal/protocol/sign`（GPL-3.0），
 * 已用其官方测试向量逐项校验（见 LocalSignerTest）。
 * 全部为纯本地计算，不依赖任何签名服务。
 */

// ---------------- SM3（GM/T 0004-2012） ----------------

private val SM3_IV = intArrayOf(
    0x7380166f, 0x4914b2b9.toInt(), 0x172442d7, 0xda8a0600.toInt(),
    0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
)

private fun sm3P0(x: Int) = x xor x.rol(9) xor x.rol(17)
private fun sm3P1(x: Int) = x xor x.rol(15) xor x.rol(23)

internal fun sm3(message: ByteArray): ByteArray {
    val bitLen = message.size.toLong() * 8
    val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()

    val h = SM3_IV.copyOf()
    val w = IntArray(68)
    val wp = IntArray(64)
    for (off in 0 until padded.size step 64) {
        for (i in 0 until 16) w[i] = padded.i32(off + i * 4)
        for (j in 16 until 68) {
            val x = w[j - 16] xor w[j - 9] xor w[j - 3].rol(15)
            w[j] = sm3P1(x) xor w[j - 13].rol(7) xor w[j - 6]
        }
        for (j in 0 until 64) wp[j] = w[j] xor w[j + 4]
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (j in 0 until 64) {
            val tj = if (j < 16) 0x79cc4519 else 0x7a879d8a
            val ss1 = (a.rol(12) + e + tj.rol(j)).rol(7)
            val ss2 = ss1 xor a.rol(12)
            val ff = if (j < 16) a xor b xor c else (a and b) or (a and c) or (b and c)
            val gg = if (j < 16) e xor f xor g else (e and f) or (e.inv() and g)
            val tt1 = ff + d + ss2 + wp[j]
            val tt2 = gg + hh + ss1 + w[j]
            d = c
            c = b.rol(9)
            b = a
            a = tt1
            hh = g
            g = f.rol(19)
            f = e
            e = sm3P0(tt2)
        }
        h[0] = h[0] xor a; h[1] = h[1] xor b; h[2] = h[2] xor c; h[3] = h[3] xor d
        h[4] = h[4] xor e; h[5] = h[5] xor f; h[6] = h[6] xor g; h[7] = h[7] xor hh
    }
    return ByteArray(32).also { out -> for (i in 0 until 8) be32(h[i]).copyInto(out, i * 4) }
}

// ---------------- 位运算基础 ----------------

internal fun Int.rol(n: Int): Int {
    val s = n and 31
    return if (s == 0) this else (this shl s) or (this ushr (32 - s))
}

internal fun Int.ror(n: Int): Int {
    val s = n and 31
    return if (s == 0) this else (this ushr s) or (this shl (32 - s))
}

internal fun Long.rol64(n: Int): Long {
    val s = n and 63
    return if (s == 0) this else (this shl s) or (this ushr (64 - s))
}

internal fun Long.ror64(n: Int): Long {
    val s = n and 63
    return if (s == 0) this else (this ushr s) or (this shl (64 - s))
}

internal fun reverseBits(value: Byte): Byte {
    var src = value.u()
    var result = 0
    for (i in 0 until 8) {
        result = (result shl 1) or ((src shr i) and 1)
    }
    return result.toByte()
}

/** zigzag 编码，protobuf sint32/sint64 使用。 */
internal fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)

internal fun ByteArray.i32Le(at: Int) = ByteBufferLe.int(this, at)
internal fun ByteArray.i64Le(at: Int) = ByteBufferLe.long(this, at)

private object ByteBufferLe {
    fun int(data: ByteArray, at: Int): Int =
        (data[at].u()) or (data[at + 1].u() shl 8) or (data[at + 2].u() shl 16) or (data[at + 3].u() shl 24)

    fun long(data: ByteArray, at: Int): Long =
        (int(data, at).toLong() and 0xffffffffL) or (int(data, at + 4).toLong() shl 32)
}

// ---------------- IV 派生与 sumMD5（f13 分支选择用） ----------------

internal fun getIv(initial: Int, data: ByteArray): Int {
    var value = initial
    for (i in data.indices) {
        value = if (i and 1 == 0) {
            (value ushr 4) xor value xor (value shl 6) xor data[i].u()
        } else {
            ((value ushr 7) xor value xor (data[i].u() or (value shl 12))).inv()
        }
    }
    return value
}

internal fun sumMd5(data: ByteArray): Int {
    var check = 0x20220420
    for (i in 0 until 12) {
        val temp: Int
        if (i and 1 == 0) {
            temp = (check ushr 3) xor check
            check = data[i].u() xor (check shl 7)
        } else {
            temp = (check ushr 5) xor check
            check = (data[i].u() or (check shl 11)).inv()
        }
        check = check xor temp
    }
    return (check or 4) xor 0x01000000
}

// ---------------- protobuf 编码 ----------------

internal fun varint(value: Long): ByteArray {
    var n = value
    val out = java.io.ByteArrayOutputStream()
    while (n > 127) {
        out.write(((n and 127) or 128).toInt())
        n = n ushr 7
    }
    out.write(n.toInt())
    return out.toByteArray()
}

internal fun protoKey(tag: Int, wire: Int) = varint((tag.toLong() shl 3) or wire.toLong())

internal fun protoBytes(tag: Int, body: ByteArray): ByteArray {
    if (body.isEmpty()) return ByteArray(0)
    return protoKey(tag, 2) + varint(body.size.toLong()) + body
}

internal fun protoString(tag: Int, value: String) = protoBytes(tag, value.toByteArray(Charsets.UTF_8))

internal fun protoSInt(tag: Int, value: Long) = protoKey(tag, 0) + varint(zigzag(value))

internal fun protoFloat(tag: Int, value: Float): ByteArray {
    val out = protoKey(tag, 5)
    val bits = java.lang.Float.floatToIntBits(value)
    return out + le32(bits)
}

// ---------------- AES（标准，ttEncrypt / URL 解密用） ----------------

/** 标准 AES-128-CBC 解密；[data] 长度必须是 16 的倍数。 */
internal fun aes128CbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
    javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
        init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(iv),
        )
        doFinal(data)
    }

// ---------------- 魔改 AES-V3（MediaCrypto 用） ----------------

/** AES S 盒按算法生成，避免手抄常量出错；已用 FIPS-197 向量校验。 */
internal object AesSBox {
    private fun xt(a: Int) = if (a and 0x80 != 0) ((a shl 1) xor 0x1b) and 0xff else (a shl 1) and 0xff

    private fun gmul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var r = 0
        while (y != 0) {
            if (y and 1 != 0) r = r xor x
            x = xt(x)
            y = y ushr 1
        }
        return r
    }

    val bytes: ByteArray by lazy {
        val inv = IntArray(256)
        for (a in 1 until 256) {
            for (b in 1 until 256) {
                if (gmul(a, b) == 1) { inv[a] = b; break }
            }
        }
        ByteArray(256) { a ->
            var x = inv[a]
            var v = 0x63
            for (i in 0 until 5) {
                v = v xor (if (i == 0) x else ((x shl i) or (x ushr (8 - i))) and 0xff)
            }
            v.toByte()
        }
    }
}
