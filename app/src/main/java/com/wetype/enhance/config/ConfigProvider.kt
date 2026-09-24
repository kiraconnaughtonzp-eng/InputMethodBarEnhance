package com.wetype.enhance.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 跨进程配置 / 诊断通道（框架自带，新模块不用改）。
 *
 * - `content://<moduleId>.config/config` 单行：version + json
 * - `content://<moduleId>.config/diag`   单行：heartbeat + pid + note + cmd_id + cmd_result
 *
 * 目标进程只读 config、只写 diag；配置本身由模块设置界面（同进程）直接写 SharedPreferences。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        return when (uri.lastPathSegment) {
            ConfigContract.PATH_CONFIG -> {
                val (version, json) = LocalConfig.loadJson(ctx)
                MatrixCursor(arrayOf(ConfigContract.COL_VERSION, ConfigContract.COL_JSON)).apply {
                    addRow(arrayOf<Any>(version, json))
                }
            }

            ConfigContract.PATH_DIAG -> {
                val diag = LocalConfig.readDiag(ctx)
                MatrixCursor(
                    arrayOf(
                        ConfigContract.COL_HEARTBEAT,
                        ConfigContract.COL_PID,
                        ConfigContract.COL_NOTE,
                        ConfigContract.COL_CMD_ID,
                        ConfigContract.COL_CMD_RESULT,
                    )
                ).apply {
                    addRow(
                        arrayOf<Any>(
                            diag.heartbeat,
                            diag.pid,
                            diag.note,
                            diag.cmdId,
                            diag.cmdResult,
                        )
                    )
                }
            }

            else -> null
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val ctx = context ?: return 0
        if (values == null) return 0
        return when (uri.lastPathSegment) {
            // 目标进程照理只写 diag；这里保留 config 的写入能力，方便特殊场景下远程改配置
            ConfigContract.PATH_CONFIG -> {
                val json = values.getAsString(ConfigContract.COL_JSON) ?: return 0
                LocalConfig.save(ctx, Prefs.fromJson(json))
                1
            }

            ConfigContract.PATH_DIAG -> {
                LocalConfig.writeDiag(
                    ctx,
                    heartbeat = values.getAsLong(ConfigContract.COL_HEARTBEAT),
                    pid = values.getAsInteger(ConfigContract.COL_PID),
                    note = values.getAsString(ConfigContract.COL_NOTE),
                    cmdId = values.getAsLong(ConfigContract.COL_CMD_ID),
                    cmdResult = values.getAsString(ConfigContract.COL_CMD_RESULT),
                )
                1
            }

            else -> 0
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null
}
