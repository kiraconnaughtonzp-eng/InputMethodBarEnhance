package com.wetype.enhance.config

import android.annotation.SuppressLint
import android.content.Context
import com.wetype.enhance.log.XLog
import java.io.File

/** 模块 App 进程读到的诊断快照 */
data class DiagInfo(
    val heartbeat: Long = 0L,
    val pid: Int = 0,
    val note: String = "",
    val cmdId: Long = 0L,
    val cmdResult: String = "",
)

/**
 * 模块 App 进程内的配置读写。
 *
 * ContentProvider（供目标进程读取）与设置界面共用同一份 SharedPreferences，
 * 所以不会出现"两份配置不一致"的问题。
 */
object LocalConfig {

    @Volatile
    private var cachedJson: String? = null

    @Volatile
    private var cachedVersion: Long = -1L

    fun prefs(ctx: Context) =
        ctx.getSharedPreferences(ConfigContract.PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取配置 + 当前版本号（版本号严格单调递增） */
    fun load(ctx: Context): Pair<Prefs, Long> {
        val p = prefs(ctx)
        val cfg = Prefs.fromJson(p.getString(ConfigContract.KEY_JSON, null))
        return cfg to p.getLong(ConfigContract.KEY_VERSION, 0L)
    }

    /**
     * Provider 查询专用：配置 JSON + 版本号。
     *
     * 带内存缓存 —— 目标进程每隔一段时间就会查一次，没必要每次都把整份配置重新序列化。
     * [save] 会失效缓存。
     */
    @Synchronized
    fun loadJson(ctx: Context): Pair<Long, String> {
        val p = prefs(ctx)
        val version = p.getLong(ConfigContract.KEY_VERSION, 0L)
        val cached = cachedJson
        if (cached != null && cachedVersion == version) return version to cached
        val json = Prefs.fromJson(p.getString(ConfigContract.KEY_JSON, null)).toJson()
        cachedJson = json
        cachedVersion = version
        return version to json
    }

    /** 保存配置，返回新的版本号 */
    // 必须用 commit()：配置要"进程被杀也不丢"；写完再 notifyChange 推送给目标进程（立即生效）
    @SuppressLint("ApplySharedPref")
    fun save(ctx: Context, config: Prefs): Long {
        val p = prefs(ctx)
        val version = nextVersion(p.getLong(ConfigContract.KEY_VERSION, 0L))
        p.edit()
            .putString(ConfigContract.KEY_JSON, config.toJson())
            .putLong(ConfigContract.KEY_VERSION, version)
            .commit()
        cachedJson = null
        cachedVersion = -1L
        makePrefsBestEffortReadable(ctx)
        notifyConfigChanged(ctx)
        return version
    }

    /** 通知目标进程配置变了：它注册的 ContentObserver 会立刻醒来读，不用等轮询 */
    private fun notifyConfigChanged(ctx: Context) {
        try {
            ctx.contentResolver.notifyChange(ConfigContract.CONFIG_URI, null)
        } catch (t: Throwable) {
            // 不再静默吞掉：这里失败就意味着目标进程只能靠兜底轮询发现变更（表现为"改完要等几秒"）
            XLog.throttle(
                "notify-fail",
                "配置变更通知发送失败（目标进程会退回轮询）：${t.javaClass.simpleName}: ${t.message}",
                5000L,
            )
        }
    }

    fun reset(ctx: Context): Long = save(ctx, Prefs.empty())

    fun readDiag(ctx: Context): DiagInfo {
        val p = prefs(ctx)
        return DiagInfo(
            heartbeat = p.getLong(ConfigContract.KEY_HEARTBEAT, 0L),
            pid = p.getInt(ConfigContract.KEY_PID, 0),
            note = p.getString(ConfigContract.KEY_NOTE, "") ?: "",
            cmdId = p.getLong(ConfigContract.KEY_CMD_ID, 0L),
            cmdResult = p.getString(ConfigContract.KEY_CMD_RESULT, "") ?: "",
        )
    }

    /**
     * 目标进程回写诊断数据（心跳 / 日志 / 命令结果）。
     *
     * 这里用 apply()：诊断数据不需要"落盘即时性"，而且读写都在模块 App 进程内
     * （Provider 与设置界面同进程），apply() 会立刻更新内存，设置界面能看到最新值。
     * 之前用 commit() 每几秒同步写一次盘，是白白的 I/O。
     */
    @SuppressLint("ApplySharedPref")
    fun writeDiag(
        ctx: Context,
        heartbeat: Long? = null,
        pid: Int? = null,
        note: String? = null,
        cmdId: Long? = null,
        cmdResult: String? = null,
    ) {
        val editor = prefs(ctx).edit()
        if (heartbeat != null) editor.putLong(ConfigContract.KEY_HEARTBEAT, heartbeat)
        if (pid != null) editor.putInt(ConfigContract.KEY_PID, pid)
        if (note != null) editor.putString(ConfigContract.KEY_NOTE, note)
        if (cmdId != null) editor.putLong(ConfigContract.KEY_CMD_ID, cmdId)
        if (cmdResult != null) editor.putString(ConfigContract.KEY_CMD_RESULT, cmdResult)
        editor.apply()
    }

    @SuppressLint("ApplySharedPref")
    fun clearDiag(ctx: Context) {
        prefs(ctx).edit()
            .remove(ConfigContract.KEY_HEARTBEAT)
            .remove(ConfigContract.KEY_PID)
            .remove(ConfigContract.KEY_NOTE)
            .remove(ConfigContract.KEY_CMD_ID)
            .remove(ConfigContract.KEY_CMD_RESULT)
            .commit()
    }

    /** 版本号严格单调递增：同一毫秒内连续保存也不会打平 */
    private fun nextVersion(current: Long): Long {
        val now = System.currentTimeMillis()
        return if (now > current) now else current + 1L
    }

    /**
     * 尽力把配置文件设为可读，作为 XSharedPreferences 兜底通道的前提条件。
     * 失败不影响 Provider 主通道。
     */
    @SuppressLint("SetWorldReadable")
    private fun makePrefsBestEffortReadable(ctx: Context) {
        try {
            val file = File(ctx.applicationInfo.dataDir, "shared_prefs/${ConfigContract.PREFS_NAME}.xml")
            if (file.exists()) file.setReadable(true, false)
        } catch (ignore: Throwable) {
            // ignore
        }
    }
}
