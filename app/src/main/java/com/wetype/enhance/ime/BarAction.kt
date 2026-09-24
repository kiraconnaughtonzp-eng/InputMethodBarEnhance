package com.wetype.enhance.ime

import com.wetype.enhance.config.Prefs
import com.wetype.enhance.config.Settings

/**
 * 底栏按钮。
 *
 * - [settingKey]：对应设置界面里的开关，决定这个按钮显不显示；
 * - 展示顺序 = 这里的枚举声明顺序。
 */
enum class BarAction(
    val id: String,
    val label: String,
    val settingKey: String,
    val defaultEnabled: Boolean,
) {
    SELECT_ALL("select_all", "全选", Settings.Keys.ACTION_SELECT_ALL, true),
    COPY("copy", "复制", Settings.Keys.ACTION_COPY, true),
    CUT("cut", "剪切", Settings.Keys.ACTION_CUT, true),
    PASTE("paste", "粘贴", Settings.Keys.ACTION_PASTE, true),
    CLEAR("clear", "清空", Settings.Keys.ACTION_CLEAR, false),
    CURSOR_LEFT("cursor_left", "左移", Settings.Keys.ACTION_CURSOR_LEFT, true),
    CURSOR_RIGHT("cursor_right", "右移", Settings.Keys.ACTION_CURSOR_RIGHT, true),
    LINE_START("line_start", "行首", Settings.Keys.ACTION_LINE_START, false),
    LINE_END("line_end", "行尾", Settings.Keys.ACTION_LINE_END, false),
    UNDO("undo", "撤销", Settings.Keys.ACTION_UNDO, false),
    REDO("redo", "重做", Settings.Keys.ACTION_REDO, false),
    HIDE_KEYBOARD("hide_keyboard", "收起", Settings.Keys.ACTION_HIDE_KEYBOARD, true);

    companion object {
        /** 按配置筛出要显示的按钮（顺序按枚举声明） */
        fun enabledIn(prefs: Prefs): List<BarAction> =
            entries.filter { prefs.getBoolean(it.settingKey, it.defaultEnabled) }

        fun byId(id: String): BarAction? = entries.firstOrNull { it.id == id }
    }
}
