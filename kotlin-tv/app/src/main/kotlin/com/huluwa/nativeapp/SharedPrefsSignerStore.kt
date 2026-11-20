// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import android.content.Context
import android.util.Log
import com.hongguotv.core.SignerProfile
import com.hongguotv.core.SignerProfileStore
import org.json.JSONObject

/**
 * 把本机注册的匿名设备档案落到 SharedPreferences。
 *
 * 平台按「注册过的 device_id + iid」放行，注册一次要一次往返；
 * 持久化后冷启动直接复用，避免每次开机都要重新注册（也省掉首次播放的等待）。
 *
 * 档案保存匿名安装标识、设备型号与匿名游客票据，不含账号凭据。
 */
class SharedPrefsSignerStore(context: Context) : SignerProfileStore {
    private val prefs = context.getSharedPreferences(DEFAULT_PREFS, Context.MODE_PRIVATE)

    override fun load(): SignerProfile? {
        val raw = prefs.getString(KEY_PROFILE, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val params = JSONObject(json.getString("params"))
            val values = linkedMapOf<String, String>()
            for (key in params.keys()) values[key] = params.optString(key, "")
            val cookieJson = json.optJSONObject("cookies")
            val cookies = linkedMapOf<String, String>()
            cookieJson?.let { stored -> stored.keys().forEach { key -> cookies[key] = stored.optString(key, "") } }
            val profile = SignerProfile(values, json.optString("userAgent"), cookies)
            profile.takeIf { it.available }
        }.onFailure { Log.w("HongguoTV", "本机设备档案损坏，将重新注册: ${it.message}") }.getOrNull()
    }

    override fun save(profile: SignerProfile) {
        val json = JSONObject()
            .put("params", JSONObject(profile.params as Map<*, *>))
            .put("userAgent", profile.userAgent)
            .put("cookies", JSONObject(profile.cookies as Map<*, *>))
            .put("savedAt", System.currentTimeMillis())
        prefs.edit().putString(KEY_PROFILE, json.toString()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_PROFILE).apply()
    }

    private companion object {
        const val KEY_PROFILE = "profile"
        const val DEFAULT_PREFS = "native-signer-v1"
    }
}
