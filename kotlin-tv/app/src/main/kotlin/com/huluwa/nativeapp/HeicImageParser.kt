// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import kotlin.math.min

/**
 * 最小 HEIC 容器解析：只提取主图（pitm 指向的 hvc1 item）解码所需的三样东西——
 * hvcC 参数集（含 box 头，可直接作 MediaCodec 的 csd-0）、长度前缀码流、ispe 宽高。
 * Android 9 以前的系统没有 HEIC 解码器，当贝等老盒子靠它配合硬解兜底封面加载。
 */
object HeicImageParser {

    class HeicImage(val hvcC: ByteArray, val frame: ByteArray, val width: Int, val height: Int, val raw: ByteArray)

    private val brands = hashSetOf("heic", "mif1", "msf1", "hevc", "heix", "miaf")

    /** 只认 ftyp 品牌在 HEIC 家族内的文件，JPEG/PNG/WebP 一律返回 false。 */
    fun isHeic(data: ByteArray, length: Int = data.size): Boolean {
        if (length < 12) return false
        if (String(data, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        val major = String(data, 8, 4, Charsets.US_ASCII)
        if (major in brands) return true
        var p = 16
        while (p + 4 <= length) {
            if (String(data, p, 4, Charsets.US_ASCII) in brands) return true
            p += 4
        }
        return false
    }

    /** 解析失败抛 [IllegalArgumentException]，由调用方降级处理。 */
    fun parse(data: ByteArray): HeicImage {
        if (!isHeic(data)) throw IllegalArgumentException("不是 HEIC")
        // 顶层只找 meta；mdat 靠 iloc 的绝对偏移直接切片，无需遍历。
        val meta = topBoxes(data).firstOrNull { it.type == "meta" }
            ?: throw IllegalArgumentException("缺少 meta box")
        var primaryItem = -1
        var hvc1Item = -1
        val itemTypes = HashMap<Int, String>()
        var itemExtents: HashMap<Int, List<Pair<Long, Int>>>? = null
        var constructionMethodOne = false
        var idatData: Pair<Int, Int>? = null
        val properties = ArrayList<Triple<String, Int, Int>>() // type, 数据区偏移, 数据区长度
        var primaryProps: List<Int>? = null
        var r = Reader(data, meta.start)
        r.int(4) // FullBox version/flags
        subBoxes(data, r.pos, meta.end) { b ->
            when (b.type) {
                "pitm" -> {
                    val vr = Reader(data, b.start)
                    val version = vr.int(1)
                    vr.int(3)
                    primaryItem = if (version == 0) vr.u16().toInt() else vr.u32().toInt()
                }
                "iinf" -> {
                    val vr = Reader(data, b.start)
                    val version = vr.int(1)
                    vr.int(3)
                    if (version == 0) vr.u16() else vr.u32()
                    subBoxes(data, vr.pos, b.end) { entry ->
                        if (entry.type != "infe") return@subBoxes
                        val er = Reader(data, entry.start)
                        val v = er.int(1)
                        er.int(3)
                        val itemId = if (v < 3) er.u16().toInt() else er.u32().toInt()
                        er.u16() // item_protection_index
                        if (v >= 2) itemTypes[itemId] = String(data, er.pos, 4, Charsets.US_ASCII)
                        if (v >= 2 && itemTypes[itemId] == "hvc1" && hvc1Item < 0) hvc1Item = itemId
                    }
                }
                "iloc" -> {
                    val vr = Reader(data, b.start)
                    val version = vr.int(1)
                    vr.int(3)
                    val sizes = vr.int(2)
                    val offsetSize = sizes ushr 12
                    val lengthSize = (sizes ushr 8) and 0xf
                    val baseSize = (sizes ushr 4) and 0xf
                    val indexSize = if (version >= 1) sizes and 0xf else 0
                    val count = if (version < 2) vr.u16().toInt() else vr.u32().toInt()
                    val map = HashMap<Int, List<Pair<Long, Int>>>(count)
                    for (i in 0 until count) {
                        val itemId = if (version < 2) vr.u16().toInt() else vr.u32().toInt()
                        if (version >= 1 && vr.u16().toInt() and 0xf == 1) constructionMethodOne = true
                        vr.u16() // data_reference_index
                        val base = vr.uint(baseSize)
                        val extentCount = vr.u16().toInt()
                        val extents = ArrayList<Pair<Long, Int>>(extentCount)
                        for (e in 0 until extentCount) {
                            if (indexSize > 0) vr.uint(indexSize)
                            val extentOffset = vr.uint(offsetSize)
                            val extentLength = vr.uint(lengthSize).toInt()
                            extents.add(base + extentOffset to extentLength)
                        }
                        map[itemId] = extents
                    }
                    itemExtents = map
                }
                "idat" -> idatData = b.start to (b.end - b.start)
                "iprp" -> {
                    subBoxes(data, b.start, b.end) { prop ->
                        when (prop.type) {
                            "ipco" -> subBoxes(data, prop.start, prop.end) { p ->
                                properties.add(Triple(String(data, p.start - 4, 4, Charsets.US_ASCII), p.start, p.end - p.start))
                            }
                            "ipma" -> {
                                val pr = Reader(data, prop.start)
                                val version = pr.int(1)
                                pr.int(3)
                                val entryCount = pr.u32().toInt()
                                for (i in 0 until entryCount) {
                                    val itemId = if (version < 1) pr.u16().toInt() else pr.u32().toInt()
                                    val associationCount = pr.int(1)
                                    val ids = ArrayList<Int>(associationCount)
                                    for (a in 0 until associationCount) {
                                        ids.add(if (version < 1) pr.int(1) and 0x7f else pr.u16() and 0x7fff)
                                    }
                                    if (itemId == primaryItem) primaryProps = ids
                                }
                            }
                        }
                    }
                }
            }
        }
        val item = if (hvc1Item >= 0) hvc1Item else primaryItem
        if (item <= 0 || primaryItem <= 0) throw IllegalArgumentException("缺少主图 item")
        // 主图本身不是单帧 hvc1（例如 grid 拼图）时放弃兜底，交给 Glide 原链路，
        // 避免把一张 tile 当成完整封面显示。
        val primaryType = itemTypes[primaryItem]
        if (primaryType != null && primaryType != "hvc1") throw IllegalArgumentException("主图是 $primaryType，非单帧 hvc1")
        if (primaryProps == null || properties.isEmpty()) throw IllegalArgumentException("缺少属性关联")

        var width = 0
        var height = 0
        var hvcC: ByteArray? = null
        for (index in primaryProps!!) {
            val (type, start, length) = properties.getOrNull(index - 1) ?: continue
            when (type) {
                "ispe" -> {
                    val ir = Reader(data, start)
                    ir.int(4)
                    width = ir.u32().toInt()
                    height = ir.u32().toInt()
                }
                // csd-0 需要 hvcC 完整 box（含 size/type 头），从 box 头开始切片。
                "hvcC" -> hvcC = data.copyOfRange(start - 8, start + length)
            }
        }
        val csd = hvcC ?: throw IllegalArgumentException("缺少 hvcC")
        if (width <= 0 || height <= 0) throw IllegalArgumentException("缺少 ispe 尺寸")
        // HEIC 静态图普遍虚标 level（常见 6.2/186），Amlogic 等老固件 HEVC 组件
        // 只认到 5.1，超限直接 start failed。单帧小图的负载远低于 4.1，钳到 120 安全。
        if (csd.size > 20) csd[20] = min(csd[20].toInt() and 0xff, 120).toByte()

        val extents = itemExtents?.get(item) ?: throw IllegalArgumentException("缺少主图码流位置")
        val dataBase = if (constructionMethodOne) {
            val idat = idatData ?: throw IllegalArgumentException("idat 缺失")
            idat.first
        } else 0
        val frame = ByteArray(extents.sumOf { it.second })
        var cursor = 0
        for ((offset, length) in extents) {
            if (length == 0) continue
            val from = dataBase + offset.toInt()
            if (from < 0 || from + length > data.size) throw IllegalArgumentException("码流越界")
            System.arraycopy(data, from, frame, cursor, length)
            cursor += length
        }
        return HeicImage(csd, frame, width, height, data)
    }

    private class Box(val type: String, val start: Int, val end: Int)

    private class Reader(private val data: ByteArray, var pos: Int) {
        fun int(bytes: Int): Int {
            var v = 0
            repeat(bytes) { v = (v shl 8) or (data[pos++].toInt() and 0xff) }
            return v
        }
        fun u16() = int(2)
        fun u32() = int(4)
        fun uint(bytes: Int): Long = if (bytes == 0) 0L else int(bytes).toLong() and 0xffffffffL
    }

    private inline fun subBoxes(data: ByteArray, from: Int, to: Int, block: (Box) -> Unit) {
        var p = from
        while (p + 8 <= to) {
            var size = Reader(data, p).u32().toInt()
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            var header = 8
            if (size == 1) {
                if (p + 16 > to) break
                var big = 0L
                repeat(8) { big = (big shl 8) or (data[p + 8 + it].toLong() and 0xff) }
                size = big.toInt()
                header = 16
            } else if (size == 0) size = to - p
            if (size < header || p + size > to) break
            if (type.all { it.code in 32..126 }) block(Box(type, p + header, p + size))
            p += size
        }
    }

    private fun topBoxes(data: ByteArray): List<Box> {
        val out = ArrayList<Box>(4)
        subBoxes(data, 0, data.size) { out.add(it) }
        return out
    }
}
