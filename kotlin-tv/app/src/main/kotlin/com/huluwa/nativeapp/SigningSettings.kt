// SPDX-License-Identifier: GPL-3.0-only
package com.huluwa.nativeapp

import com.hongguotv.core.FridaSigner
import com.hongguotv.core.PlaybackAlgorithm
import com.hongguotv.core.RemoteSigner
import com.hongguotv.core.SignerProfileStore
import com.hongguotv.core.UnidbgSigner
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 算法选择独立于设备档案保存，默认自动切换；切回本地继续复用本机匿名身份。
 *
 * 内置模式：
 * - [Mode.LOCAL]：本机算法，身份与签名都在设备上完成；
 * - [Mode.UNIDBG]：远程计算，签名交给 openhongguo-signer（unidbg 跑真实 so）；
 * - [Mode.FRIDA]：远程计算，签名交给内置的 Frida 服务；
 * - [Mode.CUSTOM]：用户添加的 openhongguo-signer 兼容服务。
 *
 * 内置模式存名称；自定义项存列表与当前项 ID，名称或地址变化不会让旧用户的选择漂移。
 */
object SigningSettings {
    private const val KEY = "nativeSigningMode"
    private const val STRATEGY_KEY = "nativePlaybackAlgorithmStrategy"
    private const val CUSTOM_LIST_KEY = "nativeCustomAlgorithms"
    private const val CUSTOM_SELECTED_KEY = "nativeCustomAlgorithm"
    private const val MAX_NAME_LENGTH = 16
    private const val MAX_CUSTOM_COUNT = 20

    enum class Mode {
        LOCAL, UNIDBG, FRIDA, CUSTOM;

        val label: String
            get() = when (this) {
                LOCAL -> "本地计算"
                UNIDBG -> "远程计算-unidbg"
                FRIDA -> "远程计算-frida"
                CUSTOM -> "自定义算法"
            }
    }

    /** 一个可展示、可检测、可正式使用的算法。自定义项使用稳定 ID 区分同名或同地址配置。 */
    data class Algorithm(
        val id: String,
        val label: String,
        val mode: Mode,
        val endpoint: String? = null,
    ) {
        val custom get() = mode == Mode.CUSTOM
    }

    private data class CustomAlgorithm(
        val id: String,
        val label: String,
        val endpoint: String,
    )

    private val builtInAlgorithms = listOf(
        Algorithm("local", Mode.LOCAL.label, Mode.LOCAL),
        Algorithm("unidbg", Mode.UNIDBG.label, Mode.UNIDBG),
        Algorithm("frida", Mode.FRIDA.label, Mode.FRIDA),
    )

    /** 三个内置项在前，自定义项按添加顺序追加。 */
    val algorithms: List<Algorithm>
        get() = builtInAlgorithms + customAlgorithms.map {
            Algorithm("custom:${it.id}", it.label, Mode.CUSTOM, it.endpoint)
        }

    private val customAlgorithms: List<CustomAlgorithm>
        get() {
            val raw = StartupSettings.string(CUSTOM_LIST_KEY)
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                val result = mutableListOf<CustomAlgorithm>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val label = item.optString("name").trim()
                    val endpoint = item.optString("endpoint").trim()
                    if (id.isNotBlank() && label.isNotBlank() && endpoint.isNotBlank()) {
                        result += CustomAlgorithm(id, label, endpoint)
                    }
                }
                result
            }.getOrDefault(emptyList())
        }

    private var selectedCustomId: String
        get() = StartupSettings.string(CUSTOM_SELECTED_KEY)
        set(value) = StartupSettings.set(CUSTOM_SELECTED_KEY, value)

    /** 手动算法单独保存，自动降级不会覆盖此项。 */
    var mode: Mode
        get() = when (StartupSettings.string(KEY, "local")) {
            "unidbg" -> Mode.UNIDBG
            "frida" -> Mode.FRIDA
            "custom" -> if (selectedCustomId in customAlgorithms.map { it.id }) Mode.CUSTOM else Mode.LOCAL
            else -> Mode.LOCAL
        }
        set(value) = StartupSettings.set(KEY, when (value) {
            Mode.LOCAL -> "local"
            Mode.UNIDBG -> "unidbg"
            Mode.FRIDA -> "frida"
            Mode.CUSTOM -> "custom"
        })

    /** 独立开关：默认开启；已有自定义服务的用户保留手动模式。 */
    var automaticSwitching: Boolean
        get() = StartupSettings.string(STRATEGY_KEY, if (mode == Mode.CUSTOM) "manual" else "auto") == "auto"
        set(value) = StartupSettings.set(STRATEGY_KEY, if (value) "auto" else "manual")

    /** 当前选中的配置；自定义项丢失时安全回退到本地算法。 */
    val selectedAlgorithm: Algorithm
        get() {
            if (mode != Mode.CUSTOM) return builtInAlgorithms.first { it.mode == mode }
            val id = selectedCustomId
            return algorithms.firstOrNull { it.custom && it.id == "custom:$id" } ?: builtInAlgorithms.first()
        }

    val algorithmLabels: List<String>
        get() = algorithms.map { it.label }
    val algorithmLabel: String
        get() = selectedAlgorithm.label

    /** 先写入稳定选择，再让完整数据供正式播放、设置页和检测共用。 */
    fun select(algorithm: Algorithm) {
        val known = algorithms.firstOrNull { it.id == algorithm.id } ?: return
        if (known.custom) selectedCustomId = known.id.removePrefix("custom:")
        mode = known.mode
    }

    /**
     * 新增自定义算法。地址按 openhongguo-signer 根地址保存；即使误填完整的 `/feature-hash`，
     * 也会去掉该路径，避免签名器重复拼接。
     */
    fun addCustomAlgorithm(name: String, address: String): Algorithm {
        val cleanName = name.trim()
        if (cleanName.isBlank()) throw IllegalArgumentException("请输入算法名称")
        if (cleanName.length > MAX_NAME_LENGTH) throw IllegalArgumentException("算法名称不能超过 $MAX_NAME_LENGTH 个字")
        val current = customAlgorithms
        val existingNames = builtInAlgorithms.map { it.label } + current.map { it.label }
        if (existingNames.any { it.equals(cleanName, ignoreCase = true) }) {
            throw IllegalArgumentException("已有同名算法，请换一个名称")
        }
        if (current.size >= MAX_CUSTOM_COUNT) throw IllegalArgumentException("最多添加 $MAX_CUSTOM_COUNT 个自定义算法")
        val endpoint = normalizeEndpoint(address)
        val item = CustomAlgorithm(UUID.randomUUID().toString(), cleanName, endpoint)
        val array = JSONArray()
        (current + item).forEach { saved ->
            array.put(JSONObject().put("id", saved.id).put("name", saved.label).put("endpoint", saved.endpoint))
        }
        StartupSettings.set(CUSTOM_LIST_KEY, array.toString())
        return Algorithm("custom:${item.id}", item.label, Mode.CUSTOM, item.endpoint)
    }

    private fun normalizeEndpoint(address: String): String {
        var value = address.trim().trimEnd('/')
        if (value.isBlank()) throw IllegalArgumentException("请输入自定义算法地址")
        if (value.endsWith("/feature-hash", ignoreCase = true)) {
            value = value.dropLast("/feature-hash".length).trimEnd('/')
        }
        val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("算法地址格式不正确，请填写 http:// 或 https:// 开头的地址")
        if (url.scheme !in setOf("http", "https")) throw IllegalArgumentException("算法地址只支持 http:// 或 https://")
        if (url.query != null || url.fragment != null) throw IllegalArgumentException("算法地址不能包含查询参数或片段")
        if (url.username.isNotBlank() || url.password.isNotBlank()) throw IllegalArgumentException("算法地址不能包含用户名或密码")
        // 只保留协议、主机、端口与可选子路径；签名器会在末尾追加 /feature-hash。
        return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
    }

    fun createSigner(store: SignerProfileStore): RemoteSigner = createSigner(selectedAlgorithm, store)

    fun createSigner(target: Mode, store: SignerProfileStore): RemoteSigner =
        createSigner(if (target == Mode.CUSTOM) selectedAlgorithm else builtInAlgorithms.first { it.mode == target }, store)

    /** 按用户指定算法创建签名器，播放取址、设置页和检测共用同一套构造规则。 */
    fun createSigner(target: Algorithm, store: SignerProfileStore): RemoteSigner = when (target.mode) {
        Mode.LOCAL -> RemoteSigner(store = store)
        // 与本地算法共用同一份匿名设备档案。
        Mode.UNIDBG -> UnidbgSigner(store = store)
        Mode.FRIDA -> FridaSigner()
        Mode.CUSTOM -> UnidbgSigner(
            endpoint = target.endpoint ?: throw IllegalArgumentException("自定义算法地址为空"),
            store = store,
        )
    }

    /** 设置页的算法与 core 的播放算法一一映射；自定义项统一走远程自定义分支。 */
    fun toPlaybackAlgorithm(target: Algorithm): PlaybackAlgorithm = when (target.mode) {
        Mode.LOCAL -> PlaybackAlgorithm.LOCAL
        Mode.UNIDBG -> PlaybackAlgorithm.UNIDBG
        Mode.FRIDA -> PlaybackAlgorithm.FRIDA
        Mode.CUSTOM -> PlaybackAlgorithm.CUSTOM
    }

    /** 设置页保存用户选择的模式名，下次启动继续沿用；自定义 ID 由 [select] 单独保存。 */
    fun modeOf(algorithm: PlaybackAlgorithm): Mode = when (algorithm) {
        PlaybackAlgorithm.LOCAL -> Mode.LOCAL
        PlaybackAlgorithm.UNIDBG -> Mode.UNIDBG
        PlaybackAlgorithm.FRIDA -> Mode.FRIDA
        PlaybackAlgorithm.CUSTOM -> Mode.CUSTOM
    }
}
