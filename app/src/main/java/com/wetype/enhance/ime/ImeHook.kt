package com.wetype.enhance.ime

import android.app.Service
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import com.wetype.enhance.ModuleRuntime
import com.wetype.enhance.config.Settings
import com.wetype.enhance.config.TargetConfigStore
import com.wetype.enhance.hook.HookKit
import com.wetype.enhance.log.XLog
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 输入法（com.tencent.wetype）输入法服务钩子。
 *
 * 只钩 framework 的稳定方法（`InputMethodService` / `Service`），不依赖输入法的混淆类名，
 * 所以输入法升级后一般不会失效；同时把输入法服务**自身声明**的生命周期方法也钩一遍
 * （万一它覆写后没调用 super）。
 *
 * 钩子回调都极轻：只记引用 / 投递主线程，绝不做深反射、Binder、重查询。
 * 跨进程配置监听**只在本进程真的抓到输入法服务时**才启动（省电）。
 */
object ImeHook {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hierarchyHooked = HashSet<Class<*>>()

    @Volatile
    private var serviceRef: WeakReference<InputMethodService>? = null

    @Volatile
    private var bootstrapDone = false

    @Volatile
    private var installed = false

    private val lifecycleMethods = setOf(
        "onCreateInputView",
        "onStartInputView",
        "onFinishInputView",
        "onWindowShown",
        "onWindowHidden",
        // 夜间/日间切换等配置变化：输入法通常会重建输入视图，需要重新挂底栏
        "onConfigurationChanged",
    )

    /** 看门狗：事件驱动为主（底栏被摘下会立刻重挂），这里只做很稀的兜底检查 */
    private const val WATCHDOG_INTERVAL_MS = 30_000L

    fun install(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        if (installed) return
        installed = true
        hookServiceCreation()
        hookFrameworkLifecycle()
        XLog.i("钩子安装完成，等待输入法创建输入法服务")
    }

    fun currentService(): InputMethodService? = serviceRef?.get()

    /** 诊断命令用：当前状态快照 */
    fun describeState(): String {
        val service = currentService()
        val prefs = ModuleRuntime.prefs()
        return buildString {
            appendLine("输入法服务：${service?.javaClass?.name ?: "(尚未创建，先弹出一次键盘)"}")
            appendLine("全屏提取模式：${service?.let { runCatching { it.isFullscreenMode }.getOrDefault(false) } ?: "-"}")
            appendLine("底栏：${BarInjector.describe()}")
            appendLine("配置通道：${TargetConfigStore.describe()}")
            appendLine("当前按钮：${BarAction.enabledIn(prefs).joinToString(" ") { it.label }}")
            append("配置：${prefs.snapshot()}")
        }
    }

    // ------------------------------------------------------------------ 钩子安装

    private fun hookServiceCreation() {
        // ① 构造函数：任何 InputMethodService 子类实例化都会调用父类构造函数，最可靠的捕获点
        HookKit.onInstanceCreated(InputMethodService::class.java) { instance ->
            val service = instance as? InputMethodService
            if (service != null) capture(service)
        }

        // ② 兜底：Service.onCreate（输入法服务几乎必然会调 super.onCreate）
        HookKit.hookAllIn(Service::class.java, "onCreate", after = { param ->
            val service = param.thisObject as? InputMethodService
            if (service != null) capture(service)
        })
    }

    private fun hookFrameworkLifecycle() {
        for (name in lifecycleMethods) {
            HookKit.hookAllIn(InputMethodService::class.java, name, after = { param ->
                val service = param.thisObject as? InputMethodService
                val method = param.method as? Method
                if (service != null && method != null) {
                    capture(service)
                    onLifecycle(service, method.name)
                }
            })
        }
    }

    /** 钩住输入法服务自身（及其祖先）声明的生命周期方法 */
    private fun hookRuntimeLifecycle(serviceClass: Class<*>) {
        if (!hierarchyHooked.add(serviceClass)) return
        HookKit.hookHierarchy(
            serviceClass,
            InputMethodService::class.java,
            lifecycleMethods,
            after = { param ->
                val service = param.thisObject as? InputMethodService
                val method = param.method as? Method
                if (service != null && method != null) onLifecycle(service, method.name)
            },
        )
    }

    // ------------------------------------------------------------------ 生命周期

    private fun capture(service: InputMethodService) {
        if (serviceRef?.get() !== service) {
            serviceRef = WeakReference(service)
            XLog.i("捕获输入法服务实例：${service.javaClass.name}")
        }
        if (!bootstrapDone) {
            // 构造函数里拿不到 base context，投递到主线程稍后再引导
            mainHandler.post { bootstrap(service) }
        }
    }

    private fun bootstrap(service: InputMethodService) {
        if (bootstrapDone) return
        val context = try {
            service.applicationContext
        } catch (t: Throwable) {
            null
        } ?: return

        synchronized(this) {
            if (bootstrapDone) return
            bootstrapDone = true
        }

        hookRuntimeLifecycle(service.javaClass)

        // 到这里才真正需要跨进程配置：只有这个进程（键盘所在进程）会常驻轮询
        ModuleRuntime.ensureStarted()

        XLog.i("输入法服务就绪：${context.packageName}（${service.javaClass.simpleName}）")

        // 服务创建时窗口可能还没出来，稍后再试一次；正常挂载由 onWindowShown 驱动
        mainHandler.postDelayed({ attach(service) }, 200L)
        startWatchdog()
    }

    private fun onLifecycle(service: InputMethodService, name: String) {
        when (name) {
            "onWindowShown" -> {
                XLog.v("onWindowShown")
                TargetConfigStore.setWindowVisible(true)
                attach(service)
                // 部分版本会在 onWindowShown 之后才 setInputView，稍后补一次（幂等）
                mainHandler.postDelayed({ attach(service) }, 300L)
            }

            "onStartInputView" -> {
                XLog.v("onStartInputView")
                mainHandler.postDelayed({ attach(service) }, 120L)
            }

            "onConfigurationChanged" -> {
                // 夜间/日间、语言、字体大小等变化：输入法很可能重建了输入视图，稍后重挂（幂等）
                XLog.v("onConfigurationChanged：稍后重建底栏")
                mainHandler.postDelayed({ attach(service) }, 120L)
                mainHandler.postDelayed({ attach(service) }, 500L)
                mainHandler.postDelayed({ attach(service) }, 1200L)
            }

            "onWindowHidden", "onFinishInputView" -> TargetConfigStore.setWindowVisible(false)
        }
    }

    private fun attach(service: InputMethodService) {
        try {
            BarInjector.ensureAttached(service, ModuleRuntime.prefs())
        } catch (t: Throwable) {
            XLog.e("挂载底栏失败", t)
        }
    }

    /**
     * 兜底看门狗（10 秒一次，只在活着的进程里做一次极轻检查）。
     * 正常情况下底栏被摘下来时 [BarInjector] 会通过 attach 状态监听立刻重挂，不依赖它。
     */
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val service = currentService()
                if (service != null && service.window?.window?.decorView?.isShown == true) {
                    BarInjector.ensureAlive(service, ModuleRuntime.prefs())
                }
            } catch (t: Throwable) {
                XLog.e("底栏看门狗异常", t)
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
        XLog.v("底栏看门狗已启动（每 ${WATCHDOG_INTERVAL_MS}ms 兜底检查一次）")
    }
}
