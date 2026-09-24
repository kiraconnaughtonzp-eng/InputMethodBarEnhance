package com.wetype.enhance

import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 作用域：模块注入到哪些 App / 哪些进程。
 *
 * ★ 本模块**不绑定某一个输入法**：钩子只用了 framework 的 `InputMethodService` / `Service`，
 *   动作只用了标准 `InputConnection`，所以**任何输入法都能用**（国产三家、Gboard、AOSP 输入法都行）。
 *   要做的事只有两件：
 *     1. 把包名加进 [TARGET_PACKAGES]（并同步 `res/values/arrays.xml` 的 `xposedscope`）；
 *     2. 在 LSPosed 里**勾选该输入法**（LSPosed 只往你勾选的 App 里注入，作用域数组只是"推荐勾选"）。
 */
object ModuleScope {

    /**
     * 支持的输入法包名（常用清单，可自行增删）。
     *
     * 不在清单里的输入法也能用：加上包名即可 —— 只要它是标准的 `InputMethodService` 实现。
     */
    val TARGET_PACKAGES: Set<String> = setOf(
        "com.tencent.wetype",                    // 微信输入法（主要测试目标）
        "com.sohu.inputmethod.sogou",            // 搜狗输入法
        "com.sohu.inputmethod.sogou.xiaomi",     // 搜狗输入法（小米定制）
        "com.baidu.input",                       // 百度输入法
        "com.baidu.input_mi",                    // 百度输入法（小米定制）
        "com.iflytek.inputmethod",               // 讯飞输入法
        "com.google.android.inputmethod.latin",  // Gboard
        "com.android.inputmethod.latin",         // AOSP 输入法
    )

    /**
     * 只在哪些进程里生效。
     *
     * - `null` = 目标 App 的**所有**进程都注入（输入法常把键盘放在独立进程，如微信输入法的 `:hld`，
     *   所以默认必须是 null）；
     * - 需要区分进程时填集合，匹配时既接受完整进程名，也接受冒号后的短名。
     */
    val PROCESS_WHITELIST: Set<String>? = null

    fun matches(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        if (lpparam.packageName !in TARGET_PACKAGES) return false
        val whitelist = PROCESS_WHITELIST ?: return true
        val process = lpparam.processName ?: return false
        return whitelist.contains(process) || whitelist.contains(process.substringAfterLast(':'))
    }
}
