package com.wetype.enhance.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用键值配置（JSON 承载）。
 *
 * - 模块 App 进程与目标进程读写的是同一份 JSON，加配置项不用改表结构；
 * - 缺省值来自 [Settings.defaults]，因此"从没打开过模块 App"时模块也能用默认配置工作；
 * - 所有 getter 都不会抛异常（JSON 坏了就退回默认值）。
 *
 * 用法（目标进程里）：
 * ```
 * val prefs = ModuleRuntime.prefs()
 * if (prefs.getBoolean(Settings.Keys.ENABLED)) { ... }
 * val level = prefs.getInt(Settings.Keys.SAMPLE_LEVEL)
 * ```
 */
class Prefs private constructor(
    private val json: JSONObject,
    private val defaults: Map<String, Any?>,
) {

    /**
     * 配置代数：目标进程每应用一份新配置就 +1。
     *
     * 渲染层用它来判断"配置变没变"，就不必每次 bind 都把整份配置重新序列化一遍。
     */
    var generation: Int = 0

    fun has(key: String): Boolean = json.has(key)

    fun getBoolean(key: String, def: Boolean = default(key, false)): Boolean =
        if (json.has(key)) json.optBoolean(key, def) else def

    fun getInt(key: String, def: Int = default(key, 0)): Int =
        if (json.has(key)) json.optInt(key, def) else def

    fun getLong(key: String, def: Long = default(key, 0L)): Long =
        if (json.has(key)) json.optLong(key, def) else def

    fun getFloat(key: String, def: Float = default(key, 0f)): Float =
        if (json.has(key)) json.optDouble(key, def.toDouble()).toFloat() else def

    fun getString(key: String, def: String = default(key, "")): String =
        if (json.has(key)) json.optString(key, def) else def

    fun getStringList(key: String, def: List<String> = defaultList(key)): List<String> {
        if (!json.has(key)) return def
        val array = json.optJSONArray(key) ?: return def
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optString(i, "")
            if (item.isNotEmpty()) list.add(item)
        }
        return list
    }

    fun set(key: String, value: Any?): Prefs {
        if (value == null) json.remove(key) else json.put(key, value)
        return this
    }

    fun setStringList(key: String, value: List<String>): Prefs = set(key, JSONArray(value))

    fun remove(key: String): Prefs {
        json.remove(key)
        return this
    }

    fun toJson(): String = json.toString()

    fun keys(): List<String> = json.keys().asSequence().toList()

    /** 只包含用户可见的配置项（不含框架内部 key），用于日志 */
    fun snapshot(): String {
        val visible = keys().filterNot { it.startsWith(FRAMEWORK_KEY_PREFIX) }
        if (visible.isEmpty()) return "(空，使用默认值)"
        return visible.joinToString(", ") { "$it=${json.opt(it)}" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> default(key: String, fallback: T): T = (defaults[key] as? T) ?: fallback

    private fun defaultList(key: String): List<String> =
        (defaults[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    companion object {
        /** 框架占用的 key 前缀，用户配置项不要使用 */
        const val FRAMEWORK_KEY_PREFIX = "__"

        fun empty(defaults: Map<String, Any?> = Settings.defaults()): Prefs = Prefs(JSONObject(), defaults)

        fun fromJson(raw: String?, defaults: Map<String, Any?> = Settings.defaults()): Prefs {
            val json = try {
                if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
            } catch (t: Throwable) {
                JSONObject()
            }
            return Prefs(json, defaults)
        }
    }
}
