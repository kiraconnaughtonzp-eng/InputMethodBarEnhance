package com.wetype.enhance.log

import com.wetype.enhance.BuildConfig
import de.robv.android.xposed.XposedBridge
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 目标进程内的日志工具。
 *
 * 三条铁律（详见《Xposed模块开发参考.md》）：
 * 1. 首行必须打 `module version = X`，排障先确认版本；
 * 2. 钩子回调里不做重活，日志要能去噪（once / throttle）；
 * 3. 最近 200 行进环形缓冲，由后台线程回写到模块 App 的诊断面板。
 *
 * 性能细节：
 * - 时间戳按"秒"缓存，同一秒内的多条日志不再各自 new Date + format；
 * - **详细日志（[v]）只进 logcat 和环形缓冲，不标记 dirty** —— 这样它不会触发
 *   "回写诊断"的 Binder 调用；等下一次真正有意义的日志（[i]/[w]/[e]）回写时，
 *   这些详细行会一起被带走（drain 取的是最近 N 行）。
 */
object XLog {

    /** ★ 新模块改成自己的短前缀，LSPosed 日志里用它过滤 */
    const val TAG = "[KIT]"

    private const val RING_MAX = 200

    @Volatile
    var verbose: Boolean = true

    private val lock = Any()
    private val ring = ArrayDeque<String>()
    private val onceKeys = HashSet<String>()
    private val lastThrottle = HashMap<String, Long>()
    private var dirty = false

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var cachedSecond = 0L
    private var cachedSecondText = ""

    /**
     * 这段代码既跑在目标进程（有 LSPosed 提供的 XposedBridge），也跑在模块 App 自己的进程（没有）。
     * 所以先探测一次：有就走 LSPosed 日志，没有就退回 logcat —— 否则 App 侧的报错会"静默丢失"。
     */
    private val xposedAvailable: Boolean by lazy {
        try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 每个进程启动时打一次版本行 */
    fun versionLine() {
        i("module version = ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
    }

    fun i(msg: String) = write("I", msg, markDirty = true)

    fun w(msg: String) = write("W", msg, markDirty = true)

    /** 仅当配置里的「详细日志」打开时输出；不影响诊断回写的节流 */
    fun v(msg: String) {
        if (verbose) write("V", msg, markDirty = false)
    }

    fun e(msg: String, t: Throwable? = null) {
        write("E", if (t == null) msg else "$msg | ${t.javaClass.name}: ${t.message}", markDirty = true)
        if (t != null) {
            try {
                XposedBridge.log(t)
            } catch (ignore: Throwable) {
                // 日志本身不允许影响宿主
            }
        }
    }

    /** 同一个 key 每个进程只打一次 */
    fun once(key: String, msg: String) {
        val first = synchronized(lock) { onceKeys.add(key) }
        if (first) i(msg)
    }

    /** 同一个 key 在 intervalMs 内最多打一次 */
    fun throttle(key: String, msg: String, intervalMs: Long = 1000L) {
        val now = System.currentTimeMillis()
        val pass = synchronized(lock) {
            val last = lastThrottle[key] ?: 0L
            if (now - last >= intervalMs) {
                lastThrottle[key] = now
                true
            } else {
                false
            }
        }
        if (pass) i(msg)
    }

    fun isDirty(): Boolean = synchronized(lock) { dirty }

    /** 取走环形缓冲内容（读完 dirty 复位） */
    fun drain(maxLines: Int = 40): String = synchronized(lock) {
        dirty = false
        if (ring.size <= maxLines) {
            ring.joinToString("\n")
        } else {
            ring.toList().takeLast(maxLines).joinToString("\n")
        }
    }

    private fun write(level: String, msg: String, markDirty: Boolean) {
        val line = "$TAG [$level] ${timeText()} $msg"
        try {
            if (xposedAvailable) {
                XposedBridge.log(line)
            } else {
                // 模块 App 自己的进程：写 logcat，便于 adb logcat -s <TAG> 排查
                android.util.Log.println(if (level == "E") android.util.Log.ERROR else android.util.Log.INFO, TAG, line)
            }
        } catch (ignore: Throwable) {
            // ignore
        }
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > RING_MAX) ring.removeFirst()
            if (markDirty) dirty = true
        }
    }

    /** 同一秒内复用同一段格式化结果，避免每条日志都 new Date + format */
    private fun timeText(): String {
        val now = System.currentTimeMillis()
        val second = now / 1000
        synchronized(lock) {
            if (second != cachedSecond) {
                cachedSecond = second
                cachedSecondText = timeFormat.format(Date(now))
            }
            return cachedSecondText
        }
    }
}
