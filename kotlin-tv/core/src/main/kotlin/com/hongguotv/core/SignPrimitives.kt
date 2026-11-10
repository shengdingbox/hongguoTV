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

/**
 * 红果客户端的定制 S 盒：4 份 256 字节表首尾拼接，共 1024 字节。
 *
 * 注意：这不是标准 AES 的 FIPS-197 S 盒，也不是按算法生成的标准盒——
 * 每一份都是上游硬编码的置换表（取自 openhongguo `const.go` 的 `sbox()`），
 * `AesV3` 按 `khronos and 3` 选取其中一份。用标准盒会让签名结果与真机不一致。
 */
internal object AesSBox {
    val bytes: ByteArray by lazy {
        hex(
            "fa7d086b9c59b34b045f39d0384a91990067a6209ff54d827326eedf18668333800319fbd9feaeaaa9b052c60bf379254e78b436ac5d1a279e88dbbd3c63ec49" +
                "15c1301fdcb856d46ccdca0943c835a3ef1ef496d2fc0e727b9484d1ea455a62023fd31281342bdd7ee628f2a54613013b21f66137292a0ded8cafbf9d5cbb24" +
                "760f75e45389e1988db19a65704f544c58ab6e6f8b23c407110cbacfa0a48ed8053d14b2da74c3d7e7bed67fde48163e8590a155b7774222c986502e17f96431" +
                "2c9bf16d1c4468e3e9a89397cb3257ebe5716aadc0ccc7c5fd601da22d47a7e251695e7ace0a41b6958ff7b987e03a06108ab5f85bd5f0bc92ff7c2fc2e81b40" +
                "ec1bdabdba98910cb22b83413467fb0ad876b546055961237590872ae350154cacb179ebaee595470468f0863d518b0fca8ee4b94ef21282bc0ed5f7ef2825cf" +
                "5b5de96a5502e133be93e7f5ad9d3e3924a8e2fa1757d07a0d0830d6b8a38dfd079ac41e6e226497d21db0bf45663f6cdddb2780a711dca6c552f8c0b6c85c00" +
                "73607ba01913aac935484bd3a4cd9f99f3104440547e29f4061fa2aba12f3cf6af856236217f5edf201ab3b4e6ff72848f6526945a77ea4378c74acc2c146bc6" +
                "e87453fcd41cce317003188c96383289f13a5fd7f9a969b7633758c23bc371cb9e92018a0b4d889bbb4f6d6fe0fea549de561609ed9cc12dee817dd97cd12e42" +
                "5b4dc1a65dea44fd454e1ba13fd189e17d2faadbabad59cbb1ce9a28c9e0f670394ad7ff30f5ddbc573b118db2ee00b6e61a5a7cf9dec4cd2e80bbb94ca59f84" +
                "08c66f426cf027e78b3a9c51fb6721754131a7ca20432ab7bfd97af2b5f88c2c23834f8f60a004133714e301c563665c7481df58bd68903dd2b334f419933229" +
                "d649ae0d4bd8079eac1e2d0b40b872ba761071a8e4561d48fee5c24791da87269d1f886bc098be25099733a385165e7fdc6e54e9f7a9c8e8c377d0822bec0262" +
                "8a920e3eb00f05f3f19678388636183c24cf0ab453cc6165a4c794d5157e6def792235126a8e5206557b46645095e20cedd31703a29b99eb1cfcafd47369fa5f" +
                "f72c1ebfc8e1f39f76807148aa94ad64fb89c660c332b34dd2e044dd5fa8b1c7682334c96d127fb7eb15bea9d17893a00c92a4d747e38ac270ab26419a79a7d8" +
                "14858fc06f56d08c11b92e3ce29dcf0ede035d463ecd38430f335ad91a656c223bfc30a688ea37a2b48d8e519cd640eef9f884f4ae97e9ca0a456757042f835c" +
                "d5c5c482b6a391981f4aac96816ecb1b0908af1895497d54edfa16313adab866f5a5f1fe10010674cc63df7c2825f6ceb24f8be5bc8769bb8621070036e70b50" +
                "599b1ce862581961f2bd275eba1de699423d0d2ab5dc5b29f02d4c537b6a734e3f75ff4ba1351755723920d3b0fdef02ec777ee42bdb90c1059e7ad4526b2413",
        )
    }
}
