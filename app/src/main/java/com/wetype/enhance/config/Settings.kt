package com.wetype.enhance.config

/**
 * 声明式设置项：设置界面按这份清单自动生成，目标进程也用同一份清单取默认值。
 *
 * 两个让界面保持简洁的开关（框架能力）：
 * - [showIf]：只在满足条件时显示（例如"自定义颜色"时才显示 RGB 滑杆）；
 * - [Header.collapsible]：这一组默认收起，点标题才展开（不常用的选项塞进去）。
 */
sealed class Setting {

    abstract val key: String
    abstract val title: String
    abstract val desc: String?
    abstract val default: Any?

    /** 只在满足条件时显示；null = 总是显示（只影响设置界面，不影响读写） */
    abstract val showIf: ((Prefs) -> Boolean)?

    /** 开关 */
    data class Switch(
        override val key: String,
        override val title: String,
        override val desc: String? = null,
        override val default: Boolean = true,
        override val showIf: ((Prefs) -> Boolean)? = null,
    ) : Setting()

    /** 滑杆（整数） */
    data class Slider(
        override val key: String,
        override val title: String,
        override val desc: String? = null,
        override val default: Int = 0,
        val min: Int = 0,
        val max: Int = 100,
        val unit: String = "",
        override val showIf: ((Prefs) -> Boolean)? = null,
    ) : Setting()

    /** 分段单选（下标 0..options.size-1） */
    data class Segmented(
        override val key: String,
        override val title: String,
        override val desc: String? = null,
        override val default: Int = 0,
        val options: List<String> = emptyList(),
        override val showIf: ((Prefs) -> Boolean)? = null,
    ) : Setting()

    /** 文本输入 */
    data class Text(
        override val key: String,
        override val title: String,
        override val desc: String? = null,
        override val default: String = "",
        val hint: String = "",
        override val showIf: ((Prefs) -> Boolean)? = null,
    ) : Setting()

    /**
     * 分组标题（只影响设置界面排版，不是真正的配置项）。
     *
     * key 请以 `__` 开头（框架保留前缀），这样不会出现在配置快照/日志里。
     * [collapsible] = true 时默认收起，点标题展开。
     */
    data class Header(
        override val key: String,
        override val title: String,
        override val desc: String? = null,
        val collapsible: Boolean = false,
    ) : Setting() {
        override val default: Any? = null
        override val showIf: ((Prefs) -> Boolean)? = null
    }
}

/**
 * 输入法增强的配置清单。
 *
 * 常用项直接铺开；按钮开关、剪贴板与日志这些不常用的折起来，页面保持简短。
 */
object Settings {

    private val showWhenCustomColor: (Prefs) -> Boolean =
        { prefs -> prefs.getInt(Keys.BAR_THEME, BarTheme.AUTO) == BarTheme.CUSTOM }

    private val showWhenIconCustom: (Prefs) -> Boolean =
        { prefs -> prefs.getInt(Keys.ICON_COLOR, IconColor.AUTO) == IconColor.CUSTOM }

    private val showWhenButtonCustom: (Prefs) -> Boolean =
        { prefs -> prefs.getInt(Keys.BUTTON_COLOR_MODE, ButtonColor.AUTO) == ButtonColor.CUSTOM }

    val all: List<Setting> = listOf(
        // ---------------- 总开关 ----------------
        Setting.Header(key = "__h_master", title = "总开关"),
        Setting.Switch(
            key = Keys.ENABLED,
            title = "启用底栏",
            desc = "关闭后立即把底栏从输入法里移除",
            default = true,
        ),

        // ---------------- 常用 ----------------
        Setting.Header(key = "__h_look", title = "常用"),
        Setting.Segmented(
            key = Keys.BAR_THEME,
            title = "背景配色",
            desc = "「跟随键盘」直接用键盘背景色（不额外提亮/压暗，避免与键盘之间出现分界）",
            default = BarTheme.AUTO,
            options = listOf("跟随键盘", "深色", "浅色", "自定义"),
        ),
        Setting.Slider(
            key = Keys.BAR_BG_R,
            title = "自定义 · 红",
            desc = null,
            default = 32,
            min = 0,
            max = 255,
            showIf = showWhenCustomColor,
        ),
        Setting.Slider(
            key = Keys.BAR_BG_G,
            title = "自定义 · 绿",
            desc = null,
            default = 35,
            min = 0,
            max = 255,
            showIf = showWhenCustomColor,
        ),
        Setting.Slider(
            key = Keys.BAR_BG_B,
            title = "自定义 · 蓝",
            desc = null,
            default = 42,
            min = 0,
            max = 255,
            showIf = showWhenCustomColor,
        ),
        Setting.Slider(
            key = Keys.BAR_ALPHA,
            title = "背景不透明度",
            desc = "调低变半透明，0 = 只留按钮",
            default = 100,
            min = 0,
            max = 100,
            unit = "%",
        ),
        Setting.Segmented(
            key = Keys.ICON_COLOR,
            title = "图标 / 文字颜色",
            desc = "「自动」= 背景不透明时按背景亮度取反色，半透明时跟系统深浅色取反色；选「自定义」用下面的 RGB",
            default = IconColor.AUTO,
            options = listOf("自动（推荐）", "深色", "浅色", "自定义"),
        ),
        Setting.Slider(
            key = Keys.ICON_R,
            title = "图标 · 红",
            desc = null,
            default = 26,
            min = 0,
            max = 255,
            showIf = showWhenIconCustom,
        ),
        Setting.Slider(
            key = Keys.ICON_G,
            title = "图标 · 绿",
            desc = null,
            default = 27,
            min = 0,
            max = 255,
            showIf = showWhenIconCustom,
        ),
        Setting.Slider(
            key = Keys.ICON_B,
            title = "图标 · 蓝",
            desc = null,
            default = 31,
            min = 0,
            max = 255,
            showIf = showWhenIconCustom,
        ),
        Setting.Segmented(
            key = Keys.BUTTON_COLOR_MODE,
            title = "按钮底色",
            desc = "「跟随文字色」= 用图标颜色的淡色版本；「透明」= 不要底色只留图标",
            default = ButtonColor.AUTO,
            options = listOf("跟随文字色", "透明", "自定义"),
        ),
        Setting.Slider(
            key = Keys.BUTTON_R,
            title = "按钮 · 红",
            desc = null,
            default = 128,
            min = 0,
            max = 255,
            showIf = showWhenButtonCustom,
        ),
        Setting.Slider(
            key = Keys.BUTTON_G,
            title = "按钮 · 绿",
            desc = null,
            default = 128,
            min = 0,
            max = 255,
            showIf = showWhenButtonCustom,
        ),
        Setting.Slider(
            key = Keys.BUTTON_B,
            title = "按钮 · 蓝",
            desc = null,
            default = 128,
            min = 0,
            max = 255,
            showIf = showWhenButtonCustom,
        ),
        Setting.Slider(
            key = Keys.BUTTON_ALPHA,
            title = "按钮不透明度",
            desc = "按钮底色的浓淡，0 = 完全透明",
            default = 10,
            min = 0,
            max = 100,
            unit = "%",
        ),
        Setting.Header(
            key = "__h_more_look",
            title = "更多外观",
            desc = "高度 / 样式 / 字号 / 震动",
            collapsible = true,
        ),
        Setting.Slider(
            key = Keys.BAR_HEIGHT,
            title = "底栏高度",
            desc = null,
            default = 44,
            min = 28,
            max = 72,
            unit = "dp",
        ),
        Setting.Slider(
            key = Keys.BAR_SPACING,
            title = "按钮间距",
            desc = "按钮等宽均分整条底栏，这里调它们之间的间隙",
            default = 6,
            min = 0,
            max = 24,
            unit = "dp",
        ),
        Setting.Segmented(
            key = Keys.BAR_STYLE,
            title = "按钮样式",
            desc = null,
            default = BarStyle.ICON,
            options = listOf("图标", "图标+文字", "文字"),
        ),
        Setting.Slider(
            key = Keys.TEXT_SIZE,
            title = "文字大小",
            desc = null,
            default = 13,
            min = 9,
            max = 22,
            unit = "sp",
        ),
        Setting.Switch(
            key = Keys.HAPTIC,
            title = "点击震动",
            desc = null,
            default = true,
        ),

        // ---------------- 按钮开关（收起） ----------------
        Setting.Header(
            key = "__h_buttons",
            title = "显示哪些按钮",
            desc = "点开勾选",
            collapsible = true,
        ),
        Setting.Switch(key = Keys.ACTION_SELECT_ALL, title = "全选", desc = null, default = true),
        Setting.Switch(key = Keys.ACTION_COPY, title = "复制", desc = null, default = true),
        Setting.Switch(key = Keys.ACTION_CUT, title = "剪切", desc = null, default = true),
        Setting.Switch(key = Keys.ACTION_PASTE, title = "粘贴", desc = null, default = true),
        Setting.Switch(key = Keys.ACTION_CLEAR, title = "清空", desc = "危险操作，默认关闭", default = false),
        Setting.Switch(key = Keys.ACTION_CURSOR_LEFT, title = "光标左移", desc = null, default = true),
        Setting.Switch(key = Keys.ACTION_CURSOR_RIGHT, title = "光标右移", desc = null, default = true),
        Setting.Switch(key = Keys.ACTION_LINE_START, title = "行首", desc = null, default = false),
        Setting.Switch(key = Keys.ACTION_LINE_END, title = "行尾", desc = null, default = false),
        Setting.Switch(key = Keys.ACTION_UNDO, title = "撤销", desc = null, default = false),
        Setting.Switch(key = Keys.ACTION_REDO, title = "重做", desc = null, default = false),
        Setting.Switch(key = Keys.ACTION_HIDE_KEYBOARD, title = "收起键盘", desc = null, default = true),

        // ---------------- 高级（收起） ----------------
        Setting.Header(
            key = "__h_adv",
            title = "高级",
            desc = "日志",
            collapsible = true,
        ),
        Setting.Switch(
            key = Keys.VERBOSE_LOG,
            title = "详细日志",
            desc = "输出更多调试日志到 LSPosed 日志与诊断面板",
            default = true,
        ),
    )

    /** 配置 key 常量：目标进程读配置时用常量，避免手写字符串出错 */
    object Keys {
        const val ENABLED = "enabled"
        const val VERBOSE_LOG = "verbose_log"

        const val BAR_THEME = "bar_theme"
        const val BAR_BG_R = "bar_bg_r"
        const val BAR_BG_G = "bar_bg_g"
        const val BAR_BG_B = "bar_bg_b"
        const val BAR_ALPHA = "bar_alpha"
        // key 从 icon_color 改名为 icon_color_mode：老配置里可能存着"浅色"，换 key 让"自动"这个新默认值生效
        const val ICON_COLOR = "icon_color_mode"
        const val ICON_R = "icon_r"
        const val ICON_G = "icon_g"
        const val ICON_B = "icon_b"
        const val BUTTON_COLOR_MODE = "button_color_mode"
        const val BUTTON_R = "button_r"
        const val BUTTON_G = "button_g"
        const val BUTTON_B = "button_b"
        const val BUTTON_ALPHA = "button_alpha"
        const val BAR_STYLE = "bar_style"
        const val BAR_HEIGHT = "bar_height_dp"
        const val BAR_SPACING = "bar_spacing_dp"
        const val TEXT_SIZE = "text_size_sp"
        const val HAPTIC = "haptic_feedback"

        const val ACTION_SELECT_ALL = "action_select_all"
        const val ACTION_COPY = "action_copy"
        const val ACTION_CUT = "action_cut"
        const val ACTION_PASTE = "action_paste"
        const val ACTION_CLEAR = "action_clear"
        const val ACTION_CURSOR_LEFT = "action_cursor_left"
        const val ACTION_CURSOR_RIGHT = "action_cursor_right"
        const val ACTION_LINE_START = "action_line_start"
        const val ACTION_LINE_END = "action_line_end"
        const val ACTION_UNDO = "action_undo"
        const val ACTION_REDO = "action_redo"
        const val ACTION_HIDE_KEYBOARD = "action_hide_keyboard"
    }

    fun defaults(): Map<String, Any?> = all.associate { it.key to it.default }

    fun find(key: String): Setting? = all.firstOrNull { it.key == key }
}

/** 底栏配色 */
object BarTheme {
    /** 跟随键盘背景色（取不到就按系统深色模式） */
    const val AUTO = 0
    const val DARK = 1
    const val LIGHT = 2

    /** 用 [Settings.Keys.BAR_BG_R] / G / B 三个滑杆自定义 */
    const val CUSTOM = 3
}

/** 图标 / 文字颜色 */
object IconColor {
    /** 按背景亮度自动取对比色（背景半透明时跟系统深浅色取反色） */
    const val AUTO = 0
    const val DARK = 1
    const val LIGHT = 2

    /** 用 [Settings.Keys.ICON_R] / G / B 三个滑杆自定义 */
    const val CUSTOM = 3
}

/** 按钮底色 */
object ButtonColor {
    /** 跟随图标/文字颜色（淡色版本，默认） */
    const val AUTO = 0

    /** 完全透明，只留图标 */
    const val TRANSPARENT = 1

    /** 用 [Settings.Keys.BUTTON_R] / G / B 三个滑杆自定义 */
    const val CUSTOM = 2
}

/** 按钮样式 */
object BarStyle {
    /** 纯图标（最紧凑，默认） */
    const val ICON = 0

    /** 图标 + 文字 */
    const val ICON_TEXT = 1

    /** 纯文字 */
    const val TEXT = 2
}
