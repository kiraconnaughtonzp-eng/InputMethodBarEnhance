package com.wetype.enhance

import android.os.Build
import com.wetype.enhance.config.CommandRegistry
import com.wetype.enhance.hook.HookKit
import com.wetype.enhance.ime.BarInjector
import com.wetype.enhance.ime.ImeHook
import com.wetype.enhance.log.XLog
import com.wetype.enhance.util.ViewTreeDumper
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 输入法增强 —— 业务装配入口。
 *
 * 框架负责：作用域过滤、去重、宿主导入、跨进程配置（推送式热生效）、日志回写、
 * 诊断命令通道、设置界面（含实时预览）。
 * 这里只负责：挂输入法钩子 + 注册诊断命令。
 */
object ModuleHooks {

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 版本行：排障第一眼要看的东西，务必保留在 install 的最前面
        XLog.versionLine()

        // ① 通用引导：拿到宿主 Context（此步不启动任何轮询）
        HookKit.hookApplicationBootstrap()

        // ② 配置热生效：设置改完会立刻回调（推送，不用等轮询）。
        //    注意：跨进程配置监听由 ImeHook 在"真的抓到输入法服务"时才启动
        //    （ModuleRuntime.ensureStarted()），这样输入法的其它进程不会有常驻开销。
        ModuleRuntime.onConfigChanged { prefs ->
            XLog.i("配置更新：${prefs.snapshot()}")
            BarInjector.refresh(prefs)
        }

        // ③ 诊断命令：模块 App「诊断与调试」里点一下就执行，结果显示在 App 上
        CommandRegistry.register("ping") { _ ->
            "pong | pid=${android.os.Process.myPid()} | ${Build.MANUFACTURER} ${Build.MODEL} | " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
        CommandRegistry.register("ime_state") { _ -> ImeHook.describeState() }
        CommandRegistry.register("prefs") { _ -> ModuleRuntime.prefs().snapshot() }
        CommandRegistry.register("view_tree") { _ -> ViewTreeDumper.dumpTopWindow() }
        CommandRegistry.register("bar_layout") { _ ->
            val service = ImeHook.currentService()
            if (service == null) {
                "输入法服务尚未创建：先在输入法里弹出一次键盘"
            } else {
                CommandRegistry.runOnMainSync { BarInjector.describe() }
            }
        }

        // ④ 挂上输入法钩子（构造函数捕获 + framework 生命周期 + 输入法自身生命周期）
        ImeHook.install(lpparam.classLoader)
    }
}
