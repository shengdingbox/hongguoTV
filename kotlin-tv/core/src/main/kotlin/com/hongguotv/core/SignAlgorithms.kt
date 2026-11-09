// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 红果客户端请求签名（X-Gorgon / X-Ladon / X-Argus / X-Medusa / X-Helios）。
 *
 * 移植自 openhongguo `internal/protocol/sign`（GPL-3.0），
 * 已用其官方测试向量逐项校验（见 LocalSignerTest）。
 *
 * 与参考实现的差异：本文件只保留实际在用的路径——
 * 参考实现会为请求挑选 hash 分支，本实现沿用旧 Signer 的做法，
 * 通过微调 `_rticket` 把分支固定到可直接计算的 [hashF13] 分支。
 */

private const val GORGON_SDK_VERSION = 67503104
internal const val SIGN_APP_ID = "8662"
internal const val SIGN_CHANNEL_ID = "1588093228"

private val MEDUSA_SIGN_KEY =
    hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5")
private val MEDUSA_AES_KEY = hex("f1593376766ea98d34f31b057a9d5be4")
private val MEDUSA_AES_IV = hex("1fe109a4125283f418de9e051a969e12")

// ---------------- X-Ladon / X-Khronos 派生值 ----------------

/**
 * hash 分支预计算：返回 [hashF13] 所需的数据，或 null 表示该分支暂不支持。
 *
 * 分支由 (querySM3, bodyMD5, ts) 决定；调用方通过调整 `_rticket` 循环到可用分支。
 */
internal fun hashBranch(querySm3: ByteArray, bodyMd5: ByteArray, ts: ByteArray): Int =
    getIv(getIv(getIv(0x20230928, querySm3), bodyMd5), ts).let { iv ->
        val low = iv and 15
        low - ((low * 171) shr 9) * 3
    }

internal fun hashF13(querySm3: ByteArray, bodyMd5: ByteArray, ts: ByteArray, khronos: Int): ByteArray {
    val iv = getIv(getIv(getIv(0x20230928, querySm3), bodyMd5), ts)
    val low = iv and 15
    val ivV0 = (low * 171) shr 9
    val branch = low - ivV0 * 3
    return when (branch) {
        2 -> branch2F13(iv, querySm3, bodyMd5, ts, khronos)
        0 -> branch0F13(querySm3, bodyMd5, ts, khronos, ivV0)
        // 分支 1 是简单的拼接+sumMD5，参考实现里同样不依赖额外表。
        else -> {
            val out = querySm3.copyOfRange(0, 16) + bodyMd5
            out + le32(sumMd5(querySm3 + bodyMd5))
        }
    }
}

// ---------------- 分支 0 ----------------

private val BRANCH0_IV = intArrayOf(
    0xc4a78580.toInt(), 0xb3c0fd39.toInt(), 0xc58c5686.toInt(),
    0xc9aa3ba7.toInt(), 0xf5a7adf2.toInt(), 0x963c2ed1.toInt(),
)

private val BRANCH0_TT = intArrayOf(
    0xebb64faf.toInt(), 0x07aadcc2, 0xcf3187bf.toInt(), 0xe01138ff.toInt(),
    0x6d0bfcff, 0x5a30a3be, 0xb41ad638.toInt(), 0x34180eb8,
    0xf233eb6f.toInt(), 0xb1a584cc.toInt(), 0xccc30dc7.toInt(), 0x47d1db51,
    0xd55653de.toInt(), 0x70a84fa1, 0x57473c12, 0xf76f0288.toInt(),
    0x2c077f0a, 0xda0dcad0.toInt(), 0xfbb86f6c.toInt(), 0xfdc4cf00.toInt(),
    0x688a020d, 0xe676c6a6.toInt(), 0x8cd6338b.toInt(), 0x1a3c8d0e,
    0xcce8b06b.toInt(), 0x6ad0ed0b, 0xa0522717.toInt(), 0xdc71ac83.toInt(),
    0x2285db71, 0xd5b4dda6.toInt(), 0x736f8650, 0x6560306c,
    0x617ce2a6, 0xe423417e.toInt(), 0x0a40e143, 0x544e4032,
    0x88dffb2a.toInt(), 0x716c1ae0, 0x4c467a88, 0x05b23bb3,
    0xe1d0b866.toInt(), 0xbaa3dcb8.toInt(), 0xae3374d3.toInt(), 0xc3381a50.toInt(),
    0x1702f75b, 0xfe6da368.toInt(), 0xf0b4cf48.toInt(), 0x4e0ffbb8,
    0x72aad10d, 0x26c53a3d, 0xf2bce0f6.toInt(), 0xb4557581.toInt(),
    0x4a257fdd, 0x8c3182a2.toInt(), 0xab0b3b86.toInt(), 0x3d5dfb14,
    0x4f103634, 0xd37b52d7.toInt(), 0x444eff16, 0xeb0a33d1.toInt(),
    0x6ca86f6e, 0x00284ba7, 0x08387cfa, 0x5fb37586,
)

// 参考实现里有 984304912 等十进制字面量，这里统一写成十六进制避免负数歧义。
private val BRANCH0_INIT = intArrayOf(
    0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f,
    984304912, 3005047866.toInt(), 2874125293.toInt(), 2152413264,
)

private val BRANCH0_ROUNDS = arrayOf(
    intArrayOf(101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3),
    intArrayOf(96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4),
    intArrayOf(96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5),
    intArrayOf(99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6),
    intArrayOf(96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4),
    intArrayOf(100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5),
)

private fun expandWords(data: ByteArray): IntArray {
    val out = IntArray(16 + 112)
    var n = 0
    var off = 0
    while (off + 4 <= data.size) {
        out[n++] = data.i32(off)
        off += 4
    }
    var di0 = out[0]
    for (i in 0 until 112) {
        val di1 = out[i + 1]
        val di14 = out[i + 14]
        val r1 = di1.rol(14) xor di1.rol(25) xor (di1 ushr 3)
        val r2 = di14.rol(13) xor di14.rol(15) xor (di14 ushr 10)
        out[i + 16] = di0 + out[i + 9] + r1 + r2
        di0 = di1
    }
    return out
}

private fun mixRounds(state: IntArray, rounds: IntArray, di: IntArray, tt: IntArray, ivV1: Int): IntArray {
    val s = state.copyOf()
    for (i in 0 until rounds[0]) {
        val base = ivV1 + i
        val w = di[base and 127]
        val n1 = ((s[rounds[3]] xor s[rounds[4]]) and s[rounds[1]]) xor s[rounds[3]]
        val n2 = s[rounds[1]].rol(26) xor s[rounds[1]].rol(21) xor s[rounds[1]].rol(7)
        val n4 = w + n1 + n2 + tt[base and 63] + s[rounds[5]]
        val n5 = s[rounds[2]].rol(30) xor s[rounds[2]].rol(19) xor s[rounds[2]].rol(10)
        val n6 = (s[rounds[2]] and s[rounds[6]]) or ((s[rounds[2]] or s[rounds[6]]) and s[rounds[7]])
        val n7 = n5 + n6
        val old = s[rounds[9]]
        val last = s[s.size - 1]
        System.arraycopy(s, 0, s, 1, s.size - 1)
        s[0] = last
        s[rounds[10]] = n7 + n4
        s[rounds[8]] = old + n4
    }
    return s
}

private fun branch0F13(querySm3: ByteArray, bodyMd5: ByteArray, ts: ByteArray, khronos: Int, ivV0: Int): ByteArray {
    val ivV1 = BRANCH0_IV[minOf(ivV0, BRANCH0_IV.size - 1)]
    val countV1 = (ivV1 + khronos + 1) and 255
    val countV2 = ivV1 + khronos
    val tt = IntArray(BRANCH0_TT.size) { BRANCH0_TT[it].ror(countV1) }
    val n0 = (countV2 + 2) and 7
    val seed = intArrayOf(0xfa, 0x45, 0x61, 0xd7)
    val pad = ByteArray(seed.size) { (((seed[it] or (seed[it] shl 8)) ushr n0) and 255).toByte() }
    val data = querySm3 + bodyMd5 + ts + pad + hex("00000000000001a0")
    val di = expandWords(data)
    val init = IntArray(BRANCH0_INIT.size) { BRANCH0_INIT[it].ror(countV2 and 31) }
    val d = mixRounds(init, BRANCH0_ROUNDS[ivV0], di, tt, ivV1)
    val ret = ByteArray(32)
    for (i in 0 until 8) be32(d[i] + init[i]).copyInto(ret, i * 4)
    for (i in 0 until 16) ret[i] = (ret[i].u() xor ret[16 + i].u()).toByte()
    val folded = ret.copyOfRange(0, 16)
    return folded + le32(sumMd5(folded))
}

// ---------------- 分支 2 ----------------

private val BRANCH2_SV = intArrayOf(
    0xa7aefe20.toInt(), 0x7149f1d6, 0x47e4ca07, 0xe9b58f67.toInt(), 0x93b924de.toInt(), 0xc614d0f5.toInt(), 0x38afe0ef, 0xb2bbad73.toInt(),
    0xe24444c3.toInt(), 0x9d3aec9b.toInt(), 0xdf7b37e4.toInt(), 0xd8b16d40.toInt(), 0xf8ac31b8.toInt(), 0x76b9a90b, 0x31d833ee, 0x953fce64.toInt(),
    0x353595a4, 0x4609c13b, 0x36925008, 0x8c6d0925.toInt(), 0x5df5c177, 0x1cfbf52b, 0x8a4fa7f0.toInt(), 0x114ca35e,
    0x8193f984.toInt(), 0x7a7a8733, 0x316ab4d5, 0x3c20cfc9, 0xa6d84453.toInt(), 0x3a18500c, 0x798ec47a, 0x97a76b28.toInt(),
    0x66c4ff96, 0x51716443, 0x0dd2fc3b, 0xb5696da7.toInt(), 0xbbeb3ac5.toInt(), 0x5c53d204, 0xd32608ce.toInt(), 0x7279b9ec,
    0xf4188ecf.toInt(), 0xf7d793db.toInt(), 0x332cc491, 0xab76ae15.toInt(), 0x9bebe727.toInt(), 0x18a01384, 0x5be9f8a7, 0x5f90a754,
    0x39b663c0, 0x36673c83, 0x7c92f514, 0x9d7d94d7.toInt(), 0xe2e8d9aa.toInt(), 0x5f7e9ea9, 0x7abd4551, 0x569e05da,
    0x40a25632, 0x3df5a9a5, 0xbab37d80.toInt(), 0x454286dc, 0x3f5d4e78, 0x03d7b75d, 0xb1fe4af7.toInt(), 0xa5ab26a3.toInt(),
)

private val BRANCH2_ORDERS = hex(
    "0f0704000908030a060b050d0e010c020f05080c0009020103070e060b0a0d04060500070c000a04080f010b0d09020e060b020504030801" +
        "01070a000d0c090e0d070e0f0b0208030c0509010004060a0d0902060f0b0a040807000c0503010e0c090f07060f030e020d0405010b0a00" +
        "0c050a090e0802040407030f01060b000b0f0408020a0700090d06010e03050c0b060a0508020c03070f0e090d0001040906080f05080004" +
        "0a0b030d01020c0e090d0c0604070a03030f00080105020e01000d0f090a0b0e0402080703060c0501080a0c0f0905060b000304020e070d04" +
        "080f000c0f0e0d0a01060207090503040205080d0b0a0606000e0f070c09030a08040f000b01060d0c0709030e05020a070b050f00020e0108" +
        "030d0c0609040d070f08050f06040b0a0e0c090002030d0c020704010b0e0e08060f09050003",
)

private fun md5V3Step(kind: Int, a: Int, b: Int, c: Int, d: Int, m: Int, shift: Int, constant: Int): Int {
    val f = when (kind) {
        0 -> (b and c) or (b.inv() and d)
        1 -> (b and d) or (c and d.inv())
        2 -> b xor c xor d
        else -> c xor (b or d.inv())
    }
    return b + ((a + f + m + constant).rol(shift))
}

private fun md5SumV3(message: ByteArray, countV2: Int, orders: ByteArray, countV1: Int): ByteArray {
    val sv = IntArray(BRANCH2_SV.size) { BRANCH2_SV[it].ror(countV1) }
    val start = intArrayOf(
        0x79e0f2fb.ror(countV2), 0xc8b52570.toInt().ror(countV2),
        0xebc2f8cd.toInt().ror(countV2), 0x7c104d93.ror(countV2),
    )
    val endCount = (countV2 + 6) and 255
    val end = intArrayOf(
        0x19be4866.ror(endCount), 0xe85986b4.toInt().ror(endCount),
        0xe19b326e.toInt().ror(endCount), 0x71d1d7d4.ror(endCount),
    )
    val m = IntArray(16) { message.i32Le(it * 4) }
    var a = start[0]; var b = start[1]; var c = start[2]; var d = start[3]

    fun run(kind: Int, shifts: IntArray, offset: Int) {
        for (i in 0 until 16) {
            val phase = i and 3
            val w = m[orders[offset + i].u()]
            val k = sv[offset + i]
            when (phase) {
                0 -> a = md5V3Step(kind, a, b, c, d, w, shifts[i], k)
                1 -> d = md5V3Step(kind, d, a, b, c, w, shifts[i], k)
                2 -> c = md5V3Step(kind, c, d, a, b, w, shifts[i], k)
                else -> b = md5V3Step(kind, b, c, d, a, w, shifts[i], k)
            }
        }
    }
    run(0, intArrayOf(7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22), 0)
    run(1, intArrayOf(5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20), 16)
    run(2, intArrayOf(4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23), 32)
    run(3, intArrayOf(6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21), 48)
    val ret = le32((start[0] + a) xor end[0]) +
        le32((start[1] + b) xor end[1]) +
        le32((start[2] + c) xor end[2]) +
        le32((start[3] + d) xor end[3])
    return ret + le32(sumMd5(ret))
}

private fun branch2F13(iv: Int, querySm3: ByteArray, bodyMd5: ByteArray, ts: ByteArray, khronos: Int): ByteArray {
    val v = (iv and 13) * 86
    val ivV0 = ((v ushr 15) and 255) + ((v ushr 8) and 255)
    val n1s = intArrayOf(0x8980f29b.toInt(), 0xeb549c7f.toInt(), 0xb08726db.toInt(), 0xd40cb5e6.toInt(), 0xe8f559e4.toInt())
    val n1 = n1s[minOf(ivV0, n1s.size - 1)]
    val countV1 = (n1 + khronos + 1) and 255
    val countV2 = n1 + khronos
    val shift = (countV2 + 5) and 7
    val seed = intArrayOf(0x84, 0x96, 0x77, 0x9d, 0xd4, 0x15, 0x0b, 0xf8)
    val pad = ByteArray(seed.size) { (((seed[it] or (seed[it] shl 8)) ushr shift) and 255).toByte() }
    val input = querySm3 + bodyMd5 + ts + pad + hex("a0010000")
    val begin = ivV0 shl 6
    return md5SumV3(input, countV2, BRANCH2_ORDERS.copyOfRange(begin, begin + 64), countV1)
}

// ---------------- X-Gorgon ----------------

/** 非标准 RC4：KSA/PRGA 都只做单向赋值 `s[i] = s[j]`，不是交换。 */
private fun rc4Gorgon(data: ByteArray, key: ByteArray): ByteArray {
    val s = IntArray(256) { it }
    var j = 0
    for (i in 0 until 256) {
        j = (j + s[i] + key[i % key.size].u()) and 255
        s[i] = s[j]
    }
    val out = ByteArray(data.size)
    var i = 0
    j = 0
    for (n in data.indices) {
        i = (i + 1) and 255
        val x = s[i]
        j = (j + x) and 255
        val y = s[j]
        s[i] = y
        out[n] = (data[n].u() xor s[(y + y) and 255]).toByte()
    }
    return out
}

internal fun xGorgon(query: String, body: ByteArray?, hasBody: Boolean, khronos: Int, random: Int): String {
    val queryMd5 = digest("MD5", query.toByteArray(Charsets.UTF_8))
    val bodyMd5 = if (hasBody) digest("MD5", body!!) else ByteArray(16)
    val input = queryMd5.copyOfRange(0, 4) + bodyMd5.copyOfRange(0, 4) +
        ByteArray(4) + le32(GORGON_SDK_VERSION) + be32(khronos)
    val key = byteArrayOf(0x4a, 0x40, 0x16, ((random shr 8) and 255).toByte(), 0x47, 0x6c, 0x01, (random and 255).toByte())
    val out = rc4Gorgon(input, key)
    val n = out.size
    for (i in 0 until n) {
        val value = out[i].u()
        val swapped = (value ushr 4) or (value shl 4)
        val next = out[if (i + 1 < n) i + 1 else 0].u()
        out[i] = (reverseBits((next xor swapped).toByte()).u() xor 20).inv().toByte()
    }
    return (hex("8404") + byteArrayOf(random.toByte(), (random shr 8).toByte(), 0x40, 0x01) + out).hex()
}

// ---------------- X-Helios ----------------

private fun diffuse(words: LongArray): LongArray {
    val table = ArrayList<Long>(35)
    table.add(words[0])
    var b0 = words[0]
    var b8 = words[1]
    val queue = ArrayDeque<Long>()
    queue.addLast(words[2])
    queue.addLast(words[3])
    for (i in 0L until 34L) {
        var x8 = (b8.ror64(8) + b0) xor i
        queue.addLast(x8)
        x8 = x8 xor b0.ror64(61)
        table.add(x8)
        b0 = x8
        b8 = queue.removeFirst()
    }
    return table.toLongArray()
}

private fun heliosPad(khronos: Int): ByteArray {
    val text = "$khronos-$SIGN_CHANNEL_ID-$SIGN_APP_ID".toByteArray(Charsets.UTF_8)
    val fill = 16 - (text.size % 16)
    val size = ((text.size + 1 + 15) / 16) * 16
    val out = ByteArray(size) { fill.toByte() }
    text.copyInto(out)
    return out
}

internal fun assembleHelios(khronos: Int, random: Int): String {
    val seed = le32(random) + SIGN_APP_ID.toByteArray(Charsets.UTF_8)
    val ascii = digest("MD5", seed).hex().toByteArray(Charsets.US_ASCII)
    val words = LongArray(4) { ascii.i64Le(it * 8) }
    val table = diffuse(words)
    val padded = heliosPad(khronos)
    val output = java.io.ByteArrayOutputStream()
    output.write(le32(random))
    for (off in padded.indices step 16) {
        var a = padded.i64Le(off)
        var b = padded.i64Le(off + 8)
        for (t in table.take(34)) {
            b = t xor (a + b.ror64(8))
            a = b xor a.ror64(61)
        }
        output.write(le64(a))
        output.write(le64(b))
    }
    return output.toByteArray().base64()
}

// ---------------- X-Medusa ----------------

private fun deviceProto(deviceId: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun w(bytes: ByteArray) = out.write(bytes)
    w(protoSInt(1, 1)); w(protoSInt(2, 2))
    w(protoString(3, SIGN_APP_ID)); w(protoString(4, deviceId))
    w(protoString(5, "Ai6svO3PyrwDOUSmO6ZcResxu")); w(protoString(6, "!noperm!"))
    w(protoSInt(7, -888888)); w(protoSInt(8, -888888))
    w(protoSInt(9, 3)); w(protoSInt(10, -888888))
    w(protoString(11, "!notset!")); w(protoString(12, "Asia/Shanghai,8"))
    w(protoString(13, "zh_CN")); w(protoSInt(14, 4))
    w(protoFloat(16, 255.24993896484375f)); w(protoFloat(17, 35.58599090576172f))
    w(protoFloat(18, 3.467449188232422f)); w(protoFloat(19, 3.467449188232422f))
    w(protoFloat(20, 255.1754913330078f)); w(protoFloat(21, 42.17544174194336f))
    w(protoString(22, "16")); w(protoSInt(23, 41)); w(protoSInt(24, 36))
    w(protoSInt(25, 1728388016635)); w(protoSInt(26, 1728388016635))
    w(protoSInt(27, 1728388016635)); w(protoSInt(28, 1728388016637))
    w(protoSInt(29, -1))
    w(protoString(30, "25053RT47C")); w(protoString(31, "Redmi"))
    w(protoString(32, "25053RT47C")); w(protoString(33, "25053RT47C"))
    w(protoString(34, "Xiaomi")); w(protoString(35, "Redmi")); w(protoString(36, "Redmi"))
    w(protoSInt(38, 31))
    return out.toByteArray()
}

internal fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
    val n = data.size
    val encoded = IntArray(n)
    for (i in 0 until n) {
        val at = (i * 4) and 28
        val d0 = key[at].u()
        val d1 = key[at + 1].u()
        val v = data[i].u()
        var d2 = ((((v shl 4) or (v ushr 4)) and 255) + d0) and 255
        d2 = d2.inv() xor d1
        d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
        d2 = (d2 + d1) and 255
        d2 = d2 xor d0
        encoded[n - i - 1] = d2.inv() and 255
    }
    val last = encoded[n - 1] xor encoded[n - 2]
    val first = encoded[0]
    encoded[0] = (last.inv() + first) and 255
    encoded[1] = ((encoded[0] xor encoded[n - 1] xor 254) + encoded[1]) and 255
    val shifted = ((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255
    encoded[2] = (encoded[2] + ((last - first) xor (shifted xor 2))) and 255
    for (i in 0 until n - 4) {
        val sh = ((encoded[i + 2] shl 3) or (encoded[i + 2] ushr 5)) and 255
        val temp = sh xor encoded[i + 1] xor (i + 3)
        encoded[i + 3] = (temp.inv() + encoded[i + 3]) and 255
    }
    encoded[n - 1] = encoded[n - 1] xor encoded[n - 2]
    var sum = 0
    for (i in 1 until n) sum += encoded[i]
    encoded[0] = ((encoded[0] xor encoded[1]) + (sum and 255)) and 255
    return ByteArray(n) { encoded[it].toByte() }
}

internal fun keyHash(signKey: ByteArray, random: Int): Pair<ByteArray, ByteArray> {
    val hash = sm3(signKey + le32(random) + signKey)
    val d1 = (random shr 16) and 255
    var d2 = ((d1 shl 11) or (random ushr 24)) and 0x7fffffff
    d2 = d2 xor (d1 shr 5)
    d2 = d2 xor d1
    d2 = d2.inv()
    return hash to le32(d2)
}

/** 签名扰动参数；正常请求随机取值，测试时固定以复现向量。 */
internal data class SignEntropy(
    val envLaunch: Long,
    val envPid: Long,
    val nowSecs: Long,
    val messageRandom: Int,
    val random: Int,
    val packedRandom: Int,
    val gorgonRandom: Int,
    val heliosRandom: Int,
)

internal fun drawEntropy(nowMillis: Long, random: java.util.Random = java.util.Random()): SignEntropy {
    val buf = ByteArray(16).also { random.nextBytes(it) }
    val n = buf.i64Le(0)
    return SignEntropy(
        envLaunch = 100 + (n % 21),
        envPid = 10001 + ((buf[8].u() or (buf[9].u() shl 8)) % 2000),
        nowSecs = nowMillis / 1000,
        messageRandom = buf.i32Le(0),
        random = buf.i32Le(4),
        packedRandom = buf.i32Le(8),
        gorgonRandom = buf[12].u() or (buf[13].u() shl 8),
        heliosRandom = buf.i32Le(12),
    )
}

internal fun assembleMedusa(
    rawUrl: String,
    body: ByteArray?,
    hasBody: Boolean,
    khronos: Int,
    deviceId: String,
    versionName: String,
    entropy: SignEntropy,
): String {
    val bodyMd5 = if (hasBody) digest("MD5", body!!) else ByteArray(16)
    val queryRaw = rawUrl.substringAfter('?', "")
    val querySm3 = sm3(queryRaw.toByteArray(Charsets.UTF_8))
    val ts = le32(khronos)
    val queryBodyTs = hashF13(querySm3, bodyMd5, ts, khronos)
    val check = ((querySm3[0].u() and 63) shl 14) or 0x18000001 or ((queryBodyTs[0].u() and 63) shl 8)

    var nested = protoSInt(1, 111) + protoSInt(2, 10) + protoSInt(3, 694367) + protoSInt(5, 586952199)
    val nested13 = protoSInt(1, entropy.nowSecs) + protoSInt(2, -2) + protoSInt(4, 200)
    var env = protoSInt(1, entropy.envLaunch) + protoSInt(2, 146331399) + protoSInt(3, 146331396)
    env += protoSInt(5, 7) + protoString(6, "v04.06.04.03-bugfix") + protoSInt(7, entropy.envPid)
    env += protoBytes(12, deviceProto(deviceId)) + protoBytes(13, nested13) + protoString(14, versionName)

    val queryHash = sm3(queryRaw.toByteArray(Charsets.UTF_8) + bodyMd5 + "none".toByteArray(Charsets.UTF_8))

    val fixed1 = hex("f7e85ffad7d7dc3bd62ac87057cf6118")
    val fixed2 = hex("4001000000000000")
    var message = protoBytes(1, fixed1) + protoSInt(2, 3) + protoSInt(3, entropy.messageRandom.toLong())
    message += protoString(4, SIGN_APP_ID) + protoString(5, deviceId) + protoString(6, SIGN_CHANNEL_ID)
    message += protoString(7, versionName) + protoString(8, "v04.06.04-ml-android")
    message += protoSInt(9, 67503104) + protoBytes(10, fixed2) + protoSInt(12, khronos.toLong())
    message += protoBytes(13, queryBodyTs) + protoBytes(14, querySm3.copyOfRange(0, 6)) + protoBytes(15, nested)
    message += protoString(16, "AXYQOS6n2m60x1fVZHIrH3iol") + protoSInt(17, khronos.toLong())
    message += protoBytes(19, queryHash) + protoString(20, "none") + protoSInt(21, 312)
    message += protoBytes(23, env)
    message += protoString(
        24,
        """{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}""",
    )

    val (hash, seed) = keyHash(MEDUSA_SIGN_KEY, entropy.random)
    val transformed = (fixed2 + xmxor(message, hash)).reversedArray()
    for (i in transformed.indices) {
        transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
    }
    val packed = byteArrayOf(0x35) + le32(entropy.packedRandom) + le32(check) + transformed +
        byteArrayOf((entropy.random shr 16).toByte(), (entropy.random shr 24).toByte())

    val encrypted = AesV3(MEDUSA_AES_KEY, khronos).encrypt(packed, MEDUSA_AES_IV)
    val version = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118")
    var prefix = ByteArray(0)
    for (i in 0 until 5) prefix += le32(version.i32(i * 4, true) xor khronos)
    val out = prefix + byteArrayOf((entropy.random and 255).toByte(), ((entropy.random shr 8) and 255).toByte(), 0, 1) + encrypted
    return out.base64()
}

// ---------------- 测试辅助 ----------------

/** 测试用：按 medusa 实际使用的密钥派生 hash 与 seed。 */
internal fun medusaKeyHashForTest(random: Int) = keyHash(MEDUSA_SIGN_KEY, random)

/** 测试用：直接调用 xmxor。 */
internal fun xmxorForTest(data: ByteArray, key: ByteArray) = xmxor(data, key)
