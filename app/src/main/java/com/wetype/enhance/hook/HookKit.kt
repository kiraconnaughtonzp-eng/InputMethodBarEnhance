package com.wetype.enhance.hook

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.wetype.enhance.ModuleRuntime
import com.wetype.enhance.log.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 钩子回调：before / after 都收到同一个 param，可读参数、改参数、改返回值 */
typealias HookAction = (MethodHookParam) -> Unit

/**
 * 钩子工具集（框架自带）。
 *
 * 统一做了三件事：try/catch 兜底（绝不让宿主崩溃）、失败只记日志不影响其它钩子、成功/失败都打日志。
 */
object HookKit {

    private fun wrap(label: String, before: HookAction?, after: HookAction?): XC_MethodHook =
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (before == null) return
                try {
                    before(param)
                } catch (t: Throwable) {
                    XLog.e("钩子(before) 异常：$label", t)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (after == null) return
                try {
                    after(param)
                } catch (t: Throwable) {
                    XLog.e("钩子(after) 异常：$label", t)
                }
            }
        }

    private fun argsWith(parameterTypes: Array<out Any?>, callback: XC_MethodHook): Array<Any?> {
        val list = ArrayList<Any?>(parameterTypes.size + 1)
        list.addAll(parameterTypes)
        list.add(callback)
        return list.toTypedArray()
    }

    /**
     * 按类名钩精确方法。
     *
     * ```
     * HookKit.hook("com.example.target.MainActivity", lpparam.classLoader, "onCreate", Bundle::class.java,
     *     after = { param -> XLog.i("onCreate 被调用") })
     * ```
     */
    fun hook(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypes: Any?,
        before: HookAction? = null,
        after: HookAction? = null,
    ): XC_MethodHook.Unhook? {
        val label = "$className#$methodName"
        return try {
            val unhook = XposedHelpers.findAndHookMethod(
                className,
                classLoader,
                methodName,
                *argsWith(parameterTypes, wrap(label, before, after)),
            )
            XLog.i("已钩住 $label")
            unhook
        } catch (t: Throwable) {
            XLog.e("钩住 $label 失败（目标版本可能改名或改了签名）", t)
            null
        }
    }

    /** 按 Class 钩精确方法（钩 framework 类时更常用） */
    fun hookIn(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypes: Any?,
        before: HookAction? = null,
        after: HookAction? = null,
    ): XC_MethodHook.Unhook? {
        val label = "${clazz.name}#$methodName"
        return try {
            val unhook = XposedHelpers.findAndHookMethod(
                clazz,
                methodName,
                *argsWith(parameterTypes, wrap(label, before, after)),
            )
            XLog.i("已钩住 $label")
            unhook
        } catch (t: Throwable) {
            XLog.e("钩住 $label 失败", t)
            null
        }
    }

    /** 按类名钩同名全部重载 */
    fun hookAll(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        before: HookAction? = null,
        after: HookAction? = null,
    ): Set<XC_MethodHook.Unhook> {
        val clazz = try {
            XposedHelpers.findClassIfExists(className, classLoader)
        } catch (t: Throwable) {
            null
        }
        if (clazz == null) {
            XLog.e("找不到类：$className")
            return emptySet()
        }
        return hookAllIn(clazz, methodName, before, after)
    }

    /** 按 Class 钩同名全部重载 */
    fun hookAllIn(
        clazz: Class<*>,
        methodName: String,
        before: HookAction? = null,
        after: HookAction? = null,
    ): Set<XC_MethodHook.Unhook> {
        val label = "${clazz.name}#$methodName"
        return try {
            val hooks = XposedBridge.hookAllMethods(clazz, methodName, wrap(label, before, after))
            if (hooks.isEmpty()) {
                XLog.w("$label 没有匹配到方法")
            } else {
                XLog.i("已钩住 $label（${hooks.size} 个重载）")
            }
            hooks
        } catch (t: Throwable) {
            XLog.e("钩住 $label 失败", t)
            emptySet()
        }
    }

    /**
     * 钩住某个类及其祖先**自己声明**的方法（止于 [stopAt]，不含该层）。
     *
     * 场景：目标 App 覆写了生命周期等方法却没有调用 super —— 只钩 framework 基类会漏掉。
     * [stopAt] 传 null 表示一直往上找到顶。
     */
    fun hookHierarchy(
        runtimeClass: Class<*>,
        stopAt: Class<*>?,
        methodNames: Set<String>,
        before: HookAction? = null,
        after: HookAction? = null,
    ): Int {
        var hooked = 0
        var clazz: Class<*>? = runtimeClass
        while (clazz != null && clazz != stopAt) {
            for (method in clazz.declaredMethods) {
                if (!methodNames.contains(method.name)) continue
                try {
                    XposedBridge.hookMethod(method, wrap("${clazz.name}#${method.name}", before, after))
                    hooked++
                } catch (t: Throwable) {
                    XLog.e("钩住 ${clazz.name}#${method.name} 失败", t)
                }
            }
            clazz = clazz.superclass
        }
        XLog.i("层级钩子完成：${runtimeClass.name} 命中 $hooked 个方法")
        return hooked
    }

    /**
     * 监听某个类（含子类）的实例创建。
     *
     * 注意：构造函数回调里对象还没初始化完，只记引用，业务逻辑放到别处（首次用到时再做）。
     */
    fun onInstanceCreated(clazz: Class<*>, callback: (Any) -> Unit) {
        try {
            XposedBridge.hookAllConstructors(
                clazz,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val instance = param.thisObject ?: return
                        try {
                            callback(instance)
                        } catch (t: Throwable) {
                            XLog.e("实例回调异常：${clazz.name}", t)
                        }
                    }
                },
            )
            XLog.i("已监听实例创建：${clazz.name}")
        } catch (t: Throwable) {
            XLog.e("监听实例创建失败：${clazz.name}", t)
        }
    }

    /**
     * 通用引导：任何模块都要的"拿到宿主 Context"。
     *
     * 钩 `ContextWrapper.attachBaseContext`（最早的时机，过滤出 Application）+ `Application.onCreate`（兜底），
     * 交给 [ModuleRuntime] 启动跨进程配置监听与诊断回写。
     */
    fun hookApplicationBootstrap() {
        try {
            XposedHelpers.findAndHookMethod(
                ContextWrapper::class.java,
                "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        ModuleRuntime.onAppCreated(app)
                    }
                },
            )
            XLog.v("已钩住 ContextWrapper.attachBaseContext（获取宿主 Context）")
        } catch (t: Throwable) {
            XLog.e("钩住 attachBaseContext 失败", t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        ModuleRuntime.onAppCreated(app)
                    }
                },
            )
            XLog.v("已钩住 Application.onCreate（兜底）")
        } catch (t: Throwable) {
            XLog.e("钩住 Application.onCreate 失败", t)
        }
    }
}
