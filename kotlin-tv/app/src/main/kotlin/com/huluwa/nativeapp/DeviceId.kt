// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import java.security.SecureRandom

/**
 * 本机匿名设备 ID。
 *
 * 首次启动生成 32 位随机码，写入独立 SharedPreferences；之后进程内只读内存缓存。
 * 只有卸载或清除应用数据才会生成新 ID，正常升级和重启保持不变。
 */
object DeviceId {
    private const val PREFS = "device_identity"
    private const val KEY_ID = "device_id"
    private const val LENGTH = 32
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    @Volatile
    private var cached: String? = null

    /** 返回当前设备 ID；缓存命中时不访问 SharedPreferences。 */
    @Synchronized
    fun get(context: Context): String {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ID, null)?.takeIf(::valid)
        val value = stored ?: generate().also {
            prefs.edit().putString(KEY_ID, it).commit()
        }
        cached = value
        return value
    }

    /** 供测试和诊断使用；UI 不应直接生成新的设备 ID。 */
    internal fun generate(): String {
        val random = SecureRandom()
        return buildString(LENGTH) {
            repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }

    private fun valid(value: String?) = value != null && value.length == LENGTH &&
        value.all { it in ALPHABET }
}
