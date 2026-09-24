package com.wetype.enhance.config

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.wetype.enhance.log.XLog
import de.robv.android.xposed.XSharedPreferences
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 目标进程内的配置仓库（框架自带，新模块不用改）。
 *
 * 设计要点：
 * - **推送为主**：模块 App 保存配置时 notifyChange，这里的 ContentObserver 立刻唤醒读取 →
 *   改设置**立即生效**，不依赖轮询频率；
 * - **兜底轮询会自适应**：如果发现"配置变更是靠定时轮询才发现"的（说明推送没生效），
 *   自动把可见时的兜底间隔从 10 秒加快到 3 秒；推送恢复后自动变回 10 秒；
 * - 键盘收起时 120 秒一轮（几乎不耗电）；失败退避 15 秒；
 * - 日志会写明每次生效是**推送**还是**定时**触发的，排障一眼就能看出通道是否正常；
 * - 双通道：ContentProvider 优先，XSharedPreferences 兜底，默认值最后兜底；
 * - 诊断回写只推最近 40 行、间隔 20 秒、用 apply()，避免无意义 I/O。
 */
object TargetConfigStore {

    /** 键盘显示时的兜底轮询间隔（推送正常时它只是心跳/日志的保鲜器） */
    private const val POLL_INTERVAL_ACTIVE_MS = 10_000L

    /** 检测到"推送没生效"时的兜底间隔：回到最早的 1.5 秒，保证改设置跟手（只在推送坏的设备上才会用到） */
    private const val POLL_INTERVAL_PUSH_BROKEN_MS = 1_500L

    /** 键盘收起时的兜底轮询间隔（几乎不耗电） */
    private const val POLL_INTERVAL_IDLE_MS = 120_000L

    /** 连续失败后的退避间隔 */
    private const val POLL_INTERVAL_FAIL_MS = 15_000L

    /** 诊断回写间隔（只有出现 [i]/[w]/[e] 级日志才会真的写） */
    private const val DIAG_PUSH_INTERVAL_MS = 20_000L

    /** 诊断日志最多回写多少行（设置界面只显示 10 行） */
    private const val DIAG_MAX_LINES = 40

    @Volatile
    private var current: Prefs = Prefs.empty()

    @Volatile
    private var lastJson: String? = null

    @Volatile
    private var lastCommandId = 0L

    /** 配置代数：每应用一份新配置 +1（渲染层用它做缓存失效） */
    private var generationCounter = 0

    @Volatile
    private var firstRead = true

    @Volatile
    private var started = false

    @Volatile
    private var windowVisible = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var listener: ((Prefs) -> Unit)? = null

    /** 本次唤醒是否由推送（ContentObserver）触发 */
    @Volatile
    private var wokenByPush = false

    /** 推送是否可信：靠定时轮询才发现变更 ⇒ 置 false，兜底轮询自动加快 */
    @Volatile
    private var pushTrusted = true

    private var xPrefs: XSharedPreferences? = null

    /** 被 notifyChange 唤醒用：轮询线程 poll 它，收到就立刻读一次 */
    private val wakeQueue = LinkedBlockingQueue<Boolean>()

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** 当前配置快照；通道不可用/还没启动时就是"默认配置" */
    fun prefs(): Prefs = current

    /** 配置变化回调（主线程） */
    fun setListener(l: ((Prefs) -> Unit)?) {
        listener = l
    }

    /** 键盘显示/收起：决定兜底轮询的快慢 */
    fun setWindowVisible(visible: Boolean) {
        if (windowVisible == visible) return
        windowVisible = visible
        XLog.v("键盘${if (visible) "显示" else "收起"}：兜底轮询切到 ${currentInterval()}ms")
        wakeQueue.offer(true)
    }

    /** 诊断用：这个进程里配置通道的状态 */
    fun describe(): String {
        val last = lastJson
        return buildString {
            append("监听=").append(if (started) "已启动" else "未启动")
            append(" 推送=").append(if (pushTrusted) "正常" else "未生效(已加快轮询)")
            append(" 键盘=").append(if (windowVisible) "显示" else "收起")
            append(" 已读到配置=").append(if (last == null) "否（当前是默认值）" else "是(${last.length} 字符)")
        }
    }

    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        appContext = context.applicationContext ?: context
        registerObserver()
        Thread({ loop() }, "kit-config-watcher").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
        XLog.v("配置监听已启动（推送为主 + 自适应兜底轮询）")
    }

    /** 配置变了就立刻醒来读，不用等轮询 */
    private fun registerObserver() {
        val ctx = appContext ?: return
        try {
            ctx.contentResolver.registerContentObserver(
                ConfigContract.CONFIG_URI,
                false,
                object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        XLog.v("收到配置变更推送")
                        wokenByPush = true
                        wakeQueue.offer(true)
                    }
                },
            )
            XLog.once("observer-ok", "已注册配置变更监听：改动会立即生效（推送）")
        } catch (t: Throwable) {
            pushTrusted = false
            XLog.e("注册配置变更监听失败 → 退回兜底轮询（已自动加快到 ${POLL_INTERVAL_PUSH_BROKEN_MS}ms）", t)
        }
    }

    private fun currentInterval(): Long = when {
        !pushTrusted && windowVisible -> POLL_INTERVAL_PUSH_BROKEN_MS
        windowVisible -> POLL_INTERVAL_ACTIVE_MS
        else -> POLL_INTERVAL_IDLE_MS
    }

    // ------------------------------------------------------------------ 轮询

    private fun loop() {
        var failures = 0
        var lastDiagPush = 0L
        pushHeartbeat()

        while (true) {
            val byPush = wokenByPush
            wokenByPush = false

            try {
                readOnce(if (byPush) "推送" else "定时")
                failures = 0
            } catch (t: Throwable) {
                failures++
                XLog.throttle(
                    "config-unavailable",
                    "配置通道不可用($failures)：${t.javaClass.simpleName}: ${t.message}",
                    6000L,
                )
            }

            // 回写诊断：顺带刷一次心跳（同一趟 Binder 调用，不额外花钱）
            val now = System.currentTimeMillis()
            if (now - lastDiagPush >= DIAG_PUSH_INTERVAL_MS && XLog.isDirty()) {
                lastDiagPush = now
                pushDiag(
                    ContentValues().apply {
                        put(ConfigContract.COL_NOTE, XLog.drain(DIAG_MAX_LINES))
                        put(ConfigContract.COL_HEARTBEAT, now)
                        put(ConfigContract.COL_PID, Process.myPid())
                    }
                )
            }

            val interval = if (failures >= 2) POLL_INTERVAL_FAIL_MS else currentInterval()
            try {
                // 有推送就直接醒；没有就睡到兜底间隔
                wakeQueue.poll(interval, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                return
            }
        }
    }

    private fun readOnce(trigger: String) {
        val ctx = appContext ?: return
        val json: String
        val source: String

        val fromProvider = tryReadProvider(ctx)
        if (fromProvider != null) {
            json = fromProvider.second
            source = "provider v=${fromProvider.first}"
        } else {
            val fromPrefs = tryReadXPrefs() ?: error("Provider 与 XSharedPreferences 均不可用")
            json = fromPrefs
            source = "xsharedprefs"
        }

        if (json == lastJson) return
        val isFirst = lastJson == null
        lastJson = json

        val config = Prefs.fromJson(json)
        config.generation = ++generationCounter
        current = config
        XLog.verbose = config.getBoolean(Settings.Keys.VERBOSE_LOG, true)
        XLog.i("配置生效($source, 触发=$trigger)：${config.snapshot()}")

        // 自适应：真正的"变更"如果是靠定时轮询发现的，说明推送没生效 → 把兜底轮询加快
        if (!isFirst) {
            if (trigger != "推送" && pushTrusted) {
                pushTrusted = false
                XLog.w(
                    "这次配置变更是【定时轮询】发现的 → 推送未生效，已自动把兜底轮询加快到 " +
                        "${POLL_INTERVAL_PUSH_BROKEN_MS}ms（改设置最多等 3 秒）"
                )
                wakeQueue.offer(true)
            } else if (trigger == "推送" && !pushTrusted) {
                pushTrusted = true
                XLog.i("推送已恢复 → 兜底轮询回到 ${POLL_INTERVAL_ACTIVE_MS}ms")
            }
        }

        if (firstRead) {
            // 进程刚起来，不补执行上次遗留的命令
            firstRead = false
            lastCommandId = config.getLong(ConfigContract.CFG_CMD_REQUEST_ID, 0L)
        } else {
            maybeRunCommand(config)
        }

        mainHandler.post {
            try {
                listener?.invoke(config)
            } catch (t: Throwable) {
                XLog.e("配置监听回调异常", t)
            }
        }
    }

    private fun tryReadProvider(ctx: Context): Pair<Long, String>? {
        val cursor = ctx.contentResolver.query(ConfigContract.CONFIG_URI, null, null, null, null)
            ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            val json = c.getString(c.getColumnIndexOrThrow(ConfigContract.COL_JSON)) ?: return null
            val version = c.getLong(c.getColumnIndexOrThrow(ConfigContract.COL_VERSION))
            return version to json
        }
    }

    private fun tryReadXPrefs(): String? {
        return try {
            var xp = xPrefs
            if (xp == null) {
                xp = XSharedPreferences(ConfigContract.MODULE_PACKAGE, ConfigContract.PREFS_NAME)
                xPrefs = xp
            }
            if (xp.hasFileChanged()) xp.reload()
            xp.getString(ConfigContract.KEY_JSON, null)
        } catch (t: Throwable) {
            XLog.throttle(
                "xprefs-fail",
                "XSharedPreferences 读取失败：${t.javaClass.simpleName}: ${t.message}",
                10000L,
            )
            null
        }
    }

    // ------------------------------------------------------------------ 命令通道

    private fun maybeRunCommand(config: Prefs) {
        val requestId = config.getLong(ConfigContract.CFG_CMD_REQUEST_ID, 0L)
        if (requestId <= 0L || requestId == lastCommandId) return
        lastCommandId = requestId

        val name = config.getString(ConfigContract.CFG_CMD_NAME, "")
        val arg = config.getString(ConfigContract.CFG_CMD_ARG, "")
        XLog.i("收到诊断命令：$name (arg=${arg.take(40)})")
        val result = CommandRegistry.execute(name, arg)
        pushDiag(
            ContentValues().apply {
                put(ConfigContract.COL_CMD_ID, requestId)
                put(ConfigContract.COL_CMD_RESULT, result)
            }
        )
    }

    // ------------------------------------------------------------------ 诊断回写

    private fun pushHeartbeat() {
        pushDiag(
            ContentValues().apply {
                put(ConfigContract.COL_HEARTBEAT, System.currentTimeMillis())
                put(ConfigContract.COL_PID, Process.myPid())
            }
        )
    }

    private fun pushDiag(values: ContentValues) {
        val ctx = appContext ?: return
        try {
            ctx.contentResolver.update(ConfigContract.DIAG_URI, values, null, null)
        } catch (t: Throwable) {
            XLog.throttle("diag-fail", "诊断数据回写失败：${t.javaClass.simpleName}", 10000L)
        }
    }
}
