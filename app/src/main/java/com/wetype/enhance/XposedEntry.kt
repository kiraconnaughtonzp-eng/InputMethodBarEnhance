package com.wetype.enhance

import com.wetype.enhance.log.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed / Xposed 入口（`assets/xposed_init` 指向本类）。
 *
 * 去重：LSPosed 在同一个进程里可能用不同的 ClassLoader 加载模块，
 * 那样 `object` 里的静态标志会各有一份、钩子会被装两遍（表现为日志成对出现）。
 * 这里用 `System.setProperty` 做**进程级**标志（System 由 boot classloader 加载，跨 ClassLoader 共享）。
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!ModuleScope.matches(lpparam)) return

        if (System.getProperty(HOOK_FLAG) != null) {
            XLog.i("本进程已注入过，跳过重复注入（pkg=${lpparam.packageName}）")
            return
        }

        try {
            System.setProperty(HOOK_FLAG, "1")
            XLog.i(
                "模块注入：pkg=${lpparam.packageName} process=${lpparam.processName} " +
                    "first=${lpparam.isFirstApplication}"
            )
            // ★ 必须走 ModuleHooks.install()：框架引导（HookKit.hookApplicationBootstrap）、
            //   配置监听、诊断命令、以及 ImeHook 都在它里面装配。
            //   直接调 ImeHook.install() 会导致：没心跳、改配置不生效、诊断命令不可用。
            ModuleHooks.install(lpparam)
        } catch (t: Throwable) {
            // 失败就把标志撤掉，允许后续重试
            System.clearProperty(HOOK_FLAG)
            XLog.e("模块初始化失败", t)
        }
    }

    companion object {
        private const val HOOK_FLAG = "wetype.enhance.hooked"
    }
}
