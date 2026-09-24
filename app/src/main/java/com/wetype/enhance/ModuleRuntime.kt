package com.wetype.enhance

import android.app.Application
import android.content.Context
import com.wetype.enhance.config.Prefs
import com.wetype.enhance.config.Settings
import com.wetype.enhance.config.TargetConfigStore
import com.wetype.enhance.log.XLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 目标进程内的模块运行时（框架自带）。
 *
 * 职责：持有宿主 Context、暴露当前配置、分发"宿主就绪 / 配置变化"事件。
 *
 * 省电设计：`onAppCreated` 只缓存 Context，**不启动任何轮询**；
 * 真正需要跨进程配置时，由模块在"确认本进程确实有目标组件"后调用 [ensureStarted]
 * （例如抓到输入法服务时）—— 这样宿主的多余进程里不会常驻轮询线程。
 */
object ModuleRuntime {

    /** 宿主 App 的 Context（就绪前为 null） */
    @Volatile
    var appContext: Context? = null
        private set

    @Volatile
    private var appReady = false

    @Volatile
    private var started = false

    private val readyListeners = CopyOnWriteArrayList<(Context) -> Unit>()
    private val configListeners = CopyOnWriteArrayList<(Prefs) -> Unit>()

    /** 由 HookKit 调用：宿主 Application 创建完成（只缓存 Context） */
    fun onAppCreated(app: Application) {
        if (appReady) return
        val context: Context = try {
            app.applicationContext ?: app
        } catch (t: Throwable) {
            app
        }

        synchronized(this) {
            if (appReady) return
            appReady = true
            appContext = context
        }

        readyListeners.forEach { listener ->
            try {
                listener(context)
            } catch (t: Throwable) {
                XLog.e("ready 监听器异常", t)
            }
        }
        readyListeners.clear()

        XLog.v("宿主 Context 就绪：${context.packageName}（按需启动配置监听）")
    }

    /**
     * 启动跨进程配置监听 + 诊断回写（幂等）。
     *
     * ★ 由模块在"确认这个进程里有目标组件"时调用，避免在宿主所有进程里都常驻轮询。
     * 未启动时 [prefs] 返回默认配置，一切功能仍可用，只是设置不生效。
     */
    fun ensureStarted() {
        if (started) return
        val context = appContext ?: return
        synchronized(this) {
            if (started) return
            started = true
        }

        TargetConfigStore.setListener { config ->
            XLog.verbose = config.getBoolean(Settings.Keys.VERBOSE_LOG, true)
            configListeners.forEach { listener ->
                try {
                    listener(config)
                } catch (t: Throwable) {
                    XLog.e("配置监听器异常", t)
                }
            }
        }
        TargetConfigStore.start(context)
        XLog.i("配置监听已启动：${context.packageName}")
    }

    /** 宿主 Context 就绪时回调；若已就绪会立即回调 */
    fun onReady(listener: (Context) -> Unit) {
        val ctx = appContext
        if (appReady && ctx != null) {
            try {
                listener(ctx)
            } catch (t: Throwable) {
                XLog.e("ready 监听器异常", t)
            }
            return
        }
        readyListeners.add(listener)
    }

    /**
     * 配置变化回调。注册时会先用当前配置回调一次（可能在 [ensureStarted] 之前，
     * 那时拿到的是默认值；配置真正读到后会再回调）。
     */
    fun onConfigChanged(listener: (Prefs) -> Unit) {
        configListeners.add(listener)
        try {
            listener(prefs())
        } catch (t: Throwable) {
            XLog.e("配置监听器异常", t)
        }
    }

    /**
     * 当前配置（未就绪时返回默认值配置，绝不会是 null）。
     *
     * 顺手保证配置监听已启动：**有人读配置 = 这个进程真的需要配置**，
     * 这样即使某条启动路径漏了（例如服务引导时机不对），也不会出现"一直用默认配置"。
     */
    fun prefs(): Prefs {
        if (!started) ensureStarted()
        return TargetConfigStore.prefs()
    }

    /** 总开关（配置里的 enabled），供业务代码快速判断 */
    fun enabled(): Boolean = prefs().getBoolean(Settings.Keys.ENABLED, true)
}
