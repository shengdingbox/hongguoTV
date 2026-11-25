// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.hongguotv.core.LatestWriteQueue
import java.io.IOException
import java.util.concurrent.Executors

/** 启动界面只读内存；旧版大记录文件的读取与轻量设置迁移均在后台完成。 */
object StartupSettings {
    private const val PREFS = "native-ui-v1"
    private const val MIGRATED = "migrated-v1"
    private val keys = setOf("theme", "uiScale", "orientationMode", "phoneAppearance", "backdropStyle",
        "favoriteSort", "newReleaseWindow", "newReleaseDay", "nativeSigningMode", "mineContentTab", "offlineDownloadConcurrency",
        "nativeCustomAlgorithms", "nativeCustomAlgorithm")
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "hongguotv-startup-settings") }
    private val callbacks = mutableListOf<(Result<Unit>) -> Unit>()
    private var loading = false
    @Volatile private var values: Map<String, Any>? = null
    private lateinit var prefs: SharedPreferences
    private val writes = LatestWriteQueue<String, Any>("hongguotv-ui-settings", delayMillis = 0, write = { batch ->
        val edit = prefs.edit()
        batch.forEach { (key, value) ->
            when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
            }
        }
        edit.commit()
    })

    val isLoaded get() = values != null
    fun string(key: String, fallback: String = "") = values?.get(key) as? String ?: fallback
    fun int(key: String, fallback: Int = 0) = values?.get(key) as? Int ?: fallback

    fun set(key: String, value: Any) {
        check(isLoaded && key in keys && (value is String || value is Int))
        values = values.orEmpty() + (key to value)
        writes.submit(mapOf(key to value))
    }

    /** 由 Activity 主线程调用；界面可先展示首页，失败交由调用方重试，不丢失原有偏好。 */
    fun loadAsync(context: Context, done: (Result<Unit>) -> Unit) {
        if (isLoaded) { done(Result.success(Unit)); return }
        callbacks += done
        if (loading) return
        loading = true
        val app = context.applicationContext
        loader.execute {
            val result = runCatching {
                val target = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!target.getBoolean(MIGRATED, false)) {
                    val legacy = app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).all
                    val edit = target.edit()
                    keys.filterNot(target::contains).forEach { key ->
                        when (val value = legacy[key]) {
                            is String -> edit.putString(key, value)
                            is Int -> edit.putInt(key, value)
                        }
                    }
                    if (!edit.putBoolean(MIGRATED, true).commit()) throw IOException("保存启动设置失败")
                }
                prefs = target
                target.all.filterKeys { it in keys }.mapNotNull { (key, value) ->
                    value?.let { key to it }
                }.toMap()
            }
            main.post {
                loading = false
                result.onSuccess { values = it }
                val pending = callbacks.toList()
                callbacks.clear()
                pending.forEach { it(result.map { Unit }) }
            }
        }
    }
}
