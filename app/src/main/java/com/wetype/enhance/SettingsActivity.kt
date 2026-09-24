package com.wetype.enhance

/**
 * 模块设置界面。
 *
 * 框架的通用设置界面已经够用，这里只补两件事：
 * 1. 「常用诊断命令」按钮（点一下就执行，不用手输命令名）；
 * 2. 开启「实时预览」：在本 App 里弹出输入法（输入法窗口是同一个，底栏就在上面），
 *    一边滚设置一边看底栏实时变化。
 */
class SettingsActivity : com.wetype.enhance.ui.SettingsActivity() {

    override fun diagnosticCommands(): List<Pair<String, String>> = listOf(
        "输入法状态" to "ime_state",
        "底栏状态" to "bar_layout",
        "视图树" to "view_tree",
        "配置" to "prefs",
        "ping" to "ping",
    )

    override fun previewEnabled(): Boolean = true

    override fun previewHint(): String =
        "点下面的按钮弹出输入法 —— 底栏就在这个输入法窗口里，所以你能一边滚动上面的设置、" +
            "一边看底栏实时变化（改完立刻生效）。前提：输入法是当前默认输入法。"
}
