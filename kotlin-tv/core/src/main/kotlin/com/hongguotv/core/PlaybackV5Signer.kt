// SPDX-License-Identifier: GPL-3.0-only
// GPL-3.0-only derived; see the README 来源与声明 section.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 播放专用 v04.06.04 签名；进程启动时间和 nonce 在所有取址请求间保持稳定。 */
internal class PlaybackV5Signer private constructor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = Math::random,
    private val processNonce: String = ByteArray(18).also(java.security.SecureRandom()::nextBytes)
        .base64()
        .replace('+', '-')
        .replace('/', '_')
        .trimEnd('='),
) {
    private val processStarted = clock()
    private fun rol(value: Int, count: Int) = Integer.rotateLeft(value, count)
    private fun ror(value: Int, count: Int) = Integer.rotateRight(value, count)
    private fun rand() = (random() * 4294967296.0).toLong().toInt()
    private fun unsigned(v: Int) = v.toLong() and 0xffffffffL
    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream().apply { while (n > 127) { write(((n and 127) or 128).toInt()); n = n ushr 7 }; write(n.toInt()) }.toByteArray()
    }
    private data class Field(val tag: Int, val value: Any, val type: String = "string")
    private fun f(tag: Int, value: Any, type: String = "string") = Field(tag, value, type)
    private fun proto(vararg fields: Field): ByteArray = ByteArrayOutputStream().apply {
        for ((tag, value, type) in fields) {
            when (type) {
                "sint" -> { val n = (value as Number).toLong(); write(varint(tag.toLong() shl 3)); write(varint(if (n < 0) -n * 2 - 1 else n * 2)) }
                "float" -> { write(varint((tag.toLong() shl 3) or 5)); write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat((value as Number).toFloat()).array()) }
                else -> { val bytes = if (value is ByteArray) value else value.toString().toByteArray(); if (bytes.isNotEmpty()) { write(varint((tag.toLong() shl 3) or 2)); write(varint(bytes.size.toLong())); write(bytes) } }
            }
        }
    }.toByteArray()
    private fun getIv(initial: Int, data: ByteArray): Int {
        var value = initial
        for (i in data.indices) value = if (i and 1 == 0) (value ushr 4) xor value xor (value shl 6) xor data[i].u() else ((value ushr 7) xor value xor (data[i].u() or (value shl 12))).inv()
        return value
    }
    private fun iv(query: ByteArray, body: ByteArray, ts: Int) = getIv(getIv(getIv(0x20230928, query), body), le32(ts))
    private fun branch(query: String, body: ByteArray?, ts: Int): Int {
        val value = iv(sm3(query.toByteArray(Charsets.UTF_8)), body?.let { digest("MD5", it) } ?: ByteArray(16), ts)
        return if (value and 15 == 12) 0 else 1
    }
    private fun sumMd5(data: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val temp = (check ushr (if (i and 1 == 0) 3 else 5)) xor check
            check = if (i and 1 == 0) data[i].u() xor (check shl 7) else (data[i].u() or (check shl 11)).inv()
            check = check xor temp
        }
        return (check or 4) xor 0x1000000
    }
    private fun hashF13V5(query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        val ivV = iv(query, body, ts)
        require(ivV and 15 == 12) { "Unsupported hash branch" }
        val ivV1 = 0x9d4a97c2.toInt()
        val count = ivV1 + ts
        val tt = PlaybackV5Constants.hashTable.map { ror(it, (count + 1) and 255) }
        val shift = (count + 2) and 7
        val pad = intArrayOf(0xfa,0x45,0x61,0xd7).map { ((it or (it shl 8)) shr shift).toByte() }.toByteArray()
        val data = query + body + le32(ts) + pad + hex("00000000000001a0")
        val di = (data.indices step 4).map { data.i32(it) }.toMutableList()
        for (i in 0 until 112) {
            val x = di[i + 1]
            val y = di[i + 14]
            di.add(di[i] + di[i + 9] + (rol(x,14) xor rol(x,25) xor (x ushr 3)) + (rol(y,13) xor rol(y,15) xor (y ushr 10)))
        }
        val initial = intArrayOf(0x7aba4fc8,0x67166507,0x6403fa00,0x340f512f,984304912,3005047866L.toInt(),2874125293L.toInt(),2152413264L.toInt()).map { ror(it, count) }
        val chosen = intArrayOf(96,1,3,4,0,6,5,2,0,7,7)
        val d = initial.toMutableList()
        for (i in 0 until chosen[0]) {
            val base = ivV1 + i
            val n1 = ((d[chosen[3]] xor d[chosen[4]]) and d[chosen[1]]) xor d[chosen[3]]
            val n2 = rol(d[chosen[1]],26) xor rol(d[chosen[1]],21) xor rol(d[chosen[1]],7)
            val n4 = di[base and 127] + n1 + n2 + tt[base and 63] + d[chosen[5]]
            val n5 = rol(d[chosen[2]],30) xor rol(d[chosen[2]],19) xor rol(d[chosen[2]],10)
            val n6 = (d[chosen[2]] and d[chosen[6]]) or ((d[chosen[2]] or d[chosen[6]]) and d[chosen[7]])
            val old = d[chosen[9]]
            val last = d[7]
            for (k in 7 downTo 1) d[k] = d[k - 1]
            d[0] = last
            d[chosen[10]] = n5 + n6 + n4
            d[chosen[8]] = old + n4
        }
        val ret = (0..7).fold(byteArrayOf()) { a, i -> a + be32(d[i] + initial[i]) }
        val folded = ByteArray(16) { (ret[it].u() xor ret[it + 16].u()).toByte() }
        return folded + le32(sumMd5(folded) or 0x20)
    }

    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = IntArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28; val d0 = key[at].u(); val d1 = key[at + 1].u(); val v = data[i].u()
            var d2 = (((v shl 4) or (v ushr 4)) and 255) + d0
            d2 = d2.inv() xor d1; d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
            d2 = (d2 + d1) and 255; d2 = (d2 xor d0) and 255; encoded[data.size-i-1] = d2.inv() and 255
        }
        val last = encoded.last() xor encoded[encoded.size-2]; val first = encoded[0]
        encoded[0] = (last.inv() + first) and 255
        encoded[1] = ((encoded[0] xor encoded.last() xor 254) + encoded[1]) and 255
        encoded[2] = (encoded[2] + ((last-first) xor (((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255) xor 2)) and 255
        for (i in 0 until encoded.size-4) {
            val temp = (((encoded[i+2] shl 3) or (encoded[i+2] ushr 5)) and 255) xor encoded[i+1] xor (i+3)
            encoded[i+3] = (temp.inv() + encoded[i+3]) and 255
        }
        encoded[encoded.size-1] = encoded.last() xor encoded[encoded.size-2]
        encoded[0] = ((encoded[0] xor encoded[1]) + encoded.drop(1).sum()) and 255
        return encoded.map { it.toByte() }.toByteArray()
    }
    private fun medusaV5(url: String, body: ByteArray?, ts: Int, who: Map<String, String>): String {
        val query = sm3(url.substringAfter('?').toByteArray(Charsets.UTF_8))
        val f13 = hashF13V5(query, body?.let { digest("MD5", it) } ?: ByteArray(16), ts)
        val flags = ByteArray(8)
        fun v(key: String, fallback: String) = who[key] ?: fallback
        val version = v("version_name", "7.4.3.32")
        val deviceInfo = proto(
            f(1,1,"sint"), f(2,2,"sint"), f(3,"8662"), f(4, v("device_id", "0")),
            f(5, processNonce), f(6,"!noperm!"), f(7,"!notset!"), f(12,"Asia/Shanghai,8"), f(13,"zh_CN"),
            f(22, v("os_version", "16")), f(25, processStarted,"sint"),
            f(30, v("device_type", "25053RT47C")), f(31,"HONOR"),
            f(32, v("device_type", "25053RT47C")), f(34,"HONOR"),
        )
        val env = proto(f(6,"v04.06.04.03-bugfix"), f(8, v("channel", "update_64")),
            f(12, deviceInfo), f(14, version))
        val message = proto(
            f(1, PlaybackV5Constants.magic), f(2,5,"sint"), f(3, unsigned(rand()),"sint"),
            f(4,"8662"), f(5, v("device_id", "0")), f(6, PlaybackV5Constants.license),
            f(7, version), f(8,"v04.06.04-ml-android"), f(9, 0x04060400,"sint"),
            f(10, flags), f(12, ts,"sint"), f(13, f13), f(14, query.copyOf(6)),
            f(15, proto(f(1,1,"sint"),f(2,1,"sint"),f(3,1,"sint"),f(5,1,"sint"))),
            f(17, ts,"sint"), f(20,"none"), f(21,377,"sint"), f(23, env),
            f(24, """{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}"""),
        )
        val nonce = rand()
        val key = PlaybackV5Constants.signKey
        val hash = sm3(key + le32(nonce) + key)
        val high = (nonce ushr 16) and 255
        val seed = le32((((high shl 11) or (nonce ushr 24)) xor (high shr 5) xor high).inv())
        val transformed = (flags + xmxor(message, hash)).reversedArray()
        for (i in transformed.indices) transformed[i] = (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = 0x18000001 or ((query[0].u() and 63) shl 14) or ((f13[0].u() and 63) shl 8)
        val packed = byteArrayOf(0xca.toByte()) + le32(rand()) + le32(check) + transformed +
            byteArrayOf((nonce ushr 16).toByte(), (nonce ushr 24).toByte())
        val encrypted = PlaybackV5Aes(digest("MD5", key.copyOfRange(0,16)), ts)
            .encrypt(packed, digest("MD5", key.copyOfRange(16,32)))
        val prefix = le32(5) + PlaybackV5Constants.magic
        for (i in prefix.indices) prefix[i] = (prefix[i].u() xor le32(ts)[i and 3].u()).toByte()
        return (prefix + byteArrayOf(nonce.toByte(),(nonce ushr 8).toByte(),0,1) + encrypted).base64()
    }
    private fun helios(ts: Int): String {
        val random = rand()
        val ascii = digest("MD5",le32(random)+"8662".toByteArray()).hex().toByteArray()
        val words = (0..3).map { ascii.i64(it*8,true) }.toMutableList(); val table = mutableListOf(words[0])
        var b0 = words.removeAt(0); var b8 = words.removeAt(0)
        for (i in 0 until 34) {
            var x8 = (java.lang.Long.rotateRight(b8,8)+b0) xor i.toLong()
            words.add(x8); x8 = x8 xor java.lang.Long.rotateRight(b0,61); table.add(x8); b0=x8; b8=words.removeAt(0)
        }
        // v5 协议的内嵌 license 使用 PlaybackV5Constants.license（官方 v04.06.04 的值）。
        val text = ("$ts-" + PlaybackV5Constants.license + "-8662").toByteArray()
        val padding = 16 - text.size % 16
        val data = text+ByteArray(padding) { padding.toByte() }
        var output = le32(random)
        for (at in data.indices step 16) {
            var a = data.i64(at,true); var b = data.i64(at+8,true)
            for (i in 0 until 34) { b = table[i] xor (a + java.lang.Long.rotateRight(b,8)); a = b xor java.lang.Long.rotateRight(a,61) }
            output += le64(a)+le64(b)
        }
        return output.base64()
    }
    fun sign(base: String, values: Map<String,String>, body: ByteArray?, now: Long = clock()): SignedRequest {
        val ts = (now / 1000).toInt()
        // 旧版播放协议要求查询串带 ts（秒），且必须与 x-khronos 完全一致。
        // 先复制调用方参数再补字段，既不改变调用方的 Map，也能让签名与实际 URL 使用同一份值。
        val signedValues = linkedMapOf<String, String>().apply {
            putAll(values)
            put("ts", ts.toString())
        }
        for (offset in 0..1023) {
            val ticket = now + offset
            val query = encodeQuery(signedValues + ("_rticket" to ticket.toString()))
            if (branch(query, body, ts) != 0) continue
            val url = "$base?$query"
            val headers = linkedMapOf(
                "x-khronos" to ts.toString(),
                "x-helios" to helios(ts),
                "x-medusa" to medusaV5(url, body, ts, signedValues),
            )
            if (body != null) {
                headers["Content-Type"] = "application/json; charset=utf-8"
                headers["x-ss-stub"] = digest("MD5", body).hex().uppercase()
            }
            return SignedRequest(url, headers, body)
        }
        throw SignatureException("无法生成 V5 播放签名")
    }
    companion object {
        val shared: PlaybackV5Signer by lazy { PlaybackV5Signer() }
    }
}
