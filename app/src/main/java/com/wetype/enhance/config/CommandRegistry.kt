package com.wetype.enhance.config

import android.os.Handler
import android.os.Looper
import com.wetype.enhance.log.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 目标进程内的诊断命令注册表（框架能力）。
 *
 * 模块 App 的「诊断 → 执行命令」会通过配置通道下发命令名 + 参数，
 * 目标进程执行后把文本结果回写到诊断表显示出来 —— 相当于一条"不用连电脑的 adb shell"。
 *
 * 注册（在 [com.wetype.enhance.ModuleHooks.install] 里）：
 * ```
 * CommandRegistry.register("ping") { "pong" }
 * CommandRegistry.register("state") { ModuleRuntime.prefs().snapshot() }
 * ```
 * 注意：handler 运行在后台轮询线程上；需要操作 UI / 主线程对象时用 [runOnMainSync]。
 */
object CommandRegistry {

    private val handlers = ConcurrentHashMap<String, (String) -> String>()

    fun register(name: String, handler: (String) -> String) {
        handlers[name] = handler
        XLog.v("已注册诊断命令：$name")
    }

    fun names(): List<String> = handlers.keys.sorted()

    /** 执行命令；任何异常都会变成可读的文本结果，不影响宿主 */
    fun execute(name: String, arg: String): String {
        val handler = handlers[name]
            ?: return "没有这个命令：$name\n已注册：${names().joinToString(", ").ifEmpty { "(无)" }}"
        return try {
            val start = System.currentTimeMillis()
            val result = handler(arg)
            val cost = System.currentTimeMillis() - start
            XLog.i("命令 $name 执行完成，耗时 ${cost}ms")
            result
        } catch (t: Throwable) {
            XLog.e("命令 $name 执行失败", t)
            "命令执行异常：${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 需要主线程执行的命令体（会阻塞等待，最多 timeoutMs） */
    fun runOnMainSync(timeoutMs: Long = 2000L, block: () -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference("(等待主线程超时)")
        Handler(Looper.getMainLooper()).post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                result.set("主线程执行异常：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else "(等待主线程超时 ${timeoutMs}ms)"
    }
}
