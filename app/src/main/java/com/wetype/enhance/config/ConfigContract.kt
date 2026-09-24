package com.wetype.enhance.config

import android.net.Uri
import com.wetype.enhance.BuildConfig

/**
 * 跨进程配置 / 诊断通道的契约。
 *
 * - authority 由 `applicationId` 派生（`<moduleId>.config`），与清单里的 `${configAuthority}` 一致，
 *   所以改包名时不用再回来改这里；
 * - 配置表只有一行：`version + json`（整个配置就是一段 JSON，加配置项不用改表结构）。
 */
object ConfigContract {

    /** 模块包名：XSharedPreferences 兜底通道要读它的 shared_prefs（= BuildConfig.APPLICATION_ID） */
    val MODULE_PACKAGE: String = BuildConfig.APPLICATION_ID

    val AUTHORITY: String = MODULE_PACKAGE + ".config"

    /** 配置表（单行：version + json） */
    val CONFIG_URI: Uri = Uri.parse("content://$AUTHORITY/config")

    /** 诊断表（单行：心跳 / pid / 模块日志 / 命令结果） */
    val DIAG_URI: Uri = Uri.parse("content://$AUTHORITY/diag")

    const val PATH_CONFIG = "config"
    const val PATH_DIAG = "diag"

    // ---- config 列 ----
    const val COL_VERSION = "version"
    const val COL_JSON = "json"

    // ---- diag 列 ----
    const val COL_HEARTBEAT = "heartbeat"
    const val COL_PID = "pid"
    const val COL_NOTE = "note"
    const val COL_CMD_ID = "cmd_id"
    const val COL_CMD_RESULT = "cmd_result"

    /** SharedPreferences 文件名（shared_prefs/kit_config.xml） */
    const val PREFS_NAME = "kit_config"

    /** SharedPreferences key（与 Provider 列名区分开） */
    const val KEY_JSON = "json"
    const val KEY_VERSION = "version"
    const val KEY_HEARTBEAT = "heartbeat"
    const val KEY_PID = "pid"
    const val KEY_NOTE = "note"
    const val KEY_CMD_ID = "cmd_id"
    const val KEY_CMD_RESULT = "cmd_result"

    /**
     * 框架在配置 JSON 里占用的 key（统一以 `__` 开头）。
     * 你自己在 [Settings] 里声明的 key 不要用这个前缀。
     */
    const val CFG_CMD_REQUEST_ID = "__cmd_request_id"
    const val CFG_CMD_NAME = "__cmd_name"
    const val CFG_CMD_ARG = "__cmd_arg"
}
