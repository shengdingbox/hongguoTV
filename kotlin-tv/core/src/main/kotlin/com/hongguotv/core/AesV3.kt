// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 红果客户端使用的魔改 AES（AES-V3），用于 X-Medusa 的封装。
 *
 * 移植自 openhongguo `internal/protocol/sign/aes.go`（GPL-3.0）。
 * 与标准 AES 的差异：
 * - S 盒按 `khronos & 3` 选取 256 字节窗口（S 盒共 4 份拼接）；
 * - SubBytes 后额外按 con2 置换行，ShiftRows 使用固定的 16 项顺序表；
 * - 只有一轮 MixColumns；
 * - 每次 AddRoundKey 都按 con2 重排列。
 */
internal class AesV3(key: ByteArray, khronos: Int) {
    private val box: ByteArray
    private val con2: IntArray
    private val order: IntArray
    private val keys: Array<ByteArray>

    init {
        val word = khronos and 3
        box = AesSBox.bytes.copyOfRange(word * 256, word * 256 + 256)
        con2 = AES_CON2[word]
        order = AES_ORDER[word]
        keys = expand(key, word)
    }

    private fun expand(key: ByteArray, word: Int): Array<ByteArray> {
        val init = AES_INIT_WORD[word]
        val box = AesSBox.bytes.copyOfRange(word * 256, word * 256 + 256)
        val initial = le32(init) + le32(init) + le32(init) + le32(init)
        val expanded = ByteArray(12 * 4)
        xor(initial, key).copyInto(expanded, 0, 0, 16)
        var rounds = 8
        for (i in 4 until 12) {
            val at = 4 * (i - 1)
            var a = expanded[at]
            var b = expanded[at + 1]
            var c = expanded[at + 2]
            var d = expanded[at + 3]
            if (i and 3 == 0) {
                val t = ((init ushr (rounds and 24)) xor box[b.u()].u()) and 255
                b = box[c.u()]
                c = box[d.u()]
                d = box[a.u()]
                a = t.toByte()
            }
            rounds += 2
            expanded[at + 4] = (a.u() xor expanded[at - 12].u()).toByte()
            expanded[at + 5] = (b.u() xor expanded[at - 11].u()).toByte()
            expanded[at + 6] = (c.u() xor expanded[at - 10].u()).toByte()
            expanded[at + 7] = (d.u() xor expanded[at - 9].u()).toByte()
        }
        return Array(12) { expanded.copyOfRange(it * 4, it * 4 + 4) }
    }

    private fun addCon(state: Array<IntArray>, keys: Array<ByteArray>, con2: IntArray) {
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                state[i][j] = state[i][j] xor keys[i][con2[j]].u()
            }
        }
    }

    private fun sub(state: Array<IntArray>) {
        for (i in 0 until 4) {
            for (j in 0 until 4) state[i][j] = box[state[i][j]].u()
        }
        val old = Array(4) { state[it].copyOf() }
        for (i in 0 until 4) state[i] = old[con2[i]]
    }

    private fun shift(state: Array<IntArray>) {
        val flat = IntArray(16) { state[it / 4][it % 4] }
        for (i in 0 until 16) state[i / 4][i % 4] = flat[order[i]]
    }

    private fun shiftCon(state: Array<IntArray>) {
        val old = Array(4) { state[it].copyOf() }
        for (i in 0 until 4) {
            for (j in 0 until 4) state[i][j] = old[i][con2[j]]
        }
    }

    private fun mixColumns(state: Array<IntArray>) {
        fun xt(x: Int) = if (x and 128 != 0) ((x shl 1) xor 0x1b) and 255 else (x shl 1) and 255
        for (i in 0 until 4) {
            val t = state[0][i] xor state[1][i] xor state[2][i] xor state[3][i]
            val u = state[0][i]
            state[0][i] = state[0][i] xor (t xor xt(state[0][i] xor state[1][i]))
            state[1][i] = state[1][i] xor (t xor xt(state[1][i] xor state[2][i]))
            state[2][i] = state[2][i] xor (t xor xt(state[2][i] xor state[3][i]))
            state[3][i] = state[3][i] xor (t xor xt(state[3][i] xor u))
        }
    }

    private fun block(value: ByteArray): ByteArray {
        val state = Array(4) { r -> IntArray(4) { c -> value[r * 4 + c].u() } }
        addCon(state, keys.copyOfRange(0, 4), con2)
        for (i in 1 until 3) {
            sub(state)
            shift(state)
            if (i == 1) {
                shiftCon(state)
                mixColumns(state)
            }
            addCon(state, keys.copyOfRange(i * 4, i * 4 + 4), con2)
        }
        for (i in 0 until 4) {
            for (j in 0 until 4) state[i][j] = state[i][j] xor keys[4 + i][j].u()
        }
        return ByteArray(16) { state[it / 4][it % 4].toByte() }
    }

    fun encrypt(data: ByteArray, iv: ByteArray): ByteArray {
        val source = data.copyOf()
        val plaintext = ByteArray(32)
        for (i in 0 until 31) {
            val at = i * 8
            var n = (source[at].u() ushr 4) and 2
            n = n or (source[at + 1].u() and 64)
            n = n or ((source[at + 2].u() ushr 2) and 1)
            n = n or ((source[at + 3].u() shl 3) and 128)
            n = n or ((source[at + 4].u() ushr 1) and 4)
            n = n or ((source[at + 5].u() shl 3) and 16)
            n = n or ((source[at + 6].u() shl 5) and 32)
            n = n or ((source[at + 7].u() ushr 4) and 8)
            plaintext[i] = n.toByte()
        }
        plaintext[31] = 1
        val blocks = ByteArray(32)
        var previous = iv
        for (off in 0 until 32 step 16) {
            val value = block(xor(plaintext.copyOfRange(off, off + 16), previous))
            value.copyInto(blocks, off)
            previous = value
        }
        val out = source.copyOf()
        for (i in 0 until 31) {
            val at = i * 8
            val k = blocks[i].u()
            out[at] = ((out[at].u() and 0xdf) or ((k shl 4) and 32)).toByte()
            out[at + 1] = ((out[at + 1].u() and 0xbf) or (k and 64)).toByte()
            out[at + 2] = ((out[at + 2].u() and 0xfb) or ((k shl 2) and 4)).toByte()
            out[at + 3] = ((out[at + 3].u() and 0xef) or ((k ushr 3) and 16)).toByte()
            out[at + 4] = ((out[at + 4].u() and 0xf7) or ((k + k) and 8)).toByte()
            out[at + 5] = ((out[at + 5].u() and 0xfd) or ((k ushr 3) and 2)).toByte()
            out[at + 6] = ((out[at + 6].u() and 0xfe) or ((k ushr 5) and 1)).toByte()
            out[at + 7] = ((out[at + 7].u() and 0x7f) or ((k shl 4) and 128)).toByte()
        }
        return byteArrayOf(blocks[31]) + out
    }

    private companion object {
        val AES_CON2 = arrayOf(
            intArrayOf(1, 0, 2, 3), intArrayOf(2, 0, 3, 1),
            intArrayOf(0, 1, 3, 2), intArrayOf(1, 0, 2, 3),
        )
        val AES_ORDER = arrayOf(
            intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3),
            intArrayOf(0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11),
            intArrayOf(0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15),
            intArrayOf(0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3),
        )
        val AES_INIT_WORD = intArrayOf(0xca025ddc.toInt(), 0x823dc546.toInt(), 0xc9420583.toInt(), 0xc298225f.toInt())
    }
}
