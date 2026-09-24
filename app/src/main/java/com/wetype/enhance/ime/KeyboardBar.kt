package com.wetype.enhance.ime

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.wetype.enhance.config.BarStyle
import com.wetype.enhance.config.BarTheme
import com.wetype.enhance.config.ButtonColor
import com.wetype.enhance.config.IconColor
import com.wetype.enhance.config.Prefs
import com.wetype.enhance.config.Settings
import com.wetype.enhance.log.XLog

/**
 * 键盘底部那一行快捷功能栏。
 *
 * 布局：按钮**等宽均分整条底栏**（从中间向两边铺开），按钮之间的间距可调（默认 6dp）。
 * 外观全部可调：背景配色 / 自定义 RGB / 背景不透明度 / 图标与文字颜色 / 按钮样式 / 高度 / 字号。
 */
@SuppressLint("ViewConstructor")
class KeyboardBar(private val imeService: InputMethodService) : FrameLayout(imeService) {

    private val row = LinearLayout(imeService)

    private val buttons = ArrayList<ButtonViews>()

    private var prefs: Prefs = Prefs.empty()
    private var keyboard: View? = null
    private var onAction: ((BarAction, View) -> Unit)? = null

    private var lastButtonsKey: String? = null
    private var lastThemeKey: String? = null

    /** 上次应用过的配置代数：代数是"配置变没变"的廉价判据（不用重新序列化整份配置） */
    private var lastConfigGeneration = -1

    private class ButtonViews(
        val action: BarAction,
        val container: LinearLayout,
        val icon: BarIconView?,
        val label: TextView?,
    )

    init {
        tag = TAG
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * 用最新配置刷新底栏。
     *
     * @param keyboard 输入法的键盘视图（"跟随键盘"取色用），可为 null
     */
    fun bind(prefs: Prefs, keyboard: View?, action: (BarAction, View) -> Unit) {
        this.prefs = prefs
        this.keyboard = keyboard
        onAction = action

        if (prefs.generation != lastConfigGeneration) {
            lastConfigGeneration = prefs.generation
            // 配置变了就无条件重建按钮 + 重画外观（不做任何"看起来没变"的优化，省得改了不生效）
            lastButtonsKey = null
            lastThemeKey = null
        }

        val actions = BarAction.enabledIn(prefs)
        val buttonsKey = actions.joinToString(",") { it.id } + "@" + barStyle() + "@" + textSizeSp()
        if (buttonsKey != lastButtonsKey) {
            lastButtonsKey = buttonsKey
            rebuildButtons(actions)
        }

        val background = resolveBackgroundColor()
        val foreground = resolveForegroundColor(background)
        val fill = resolveButtonFill(foreground)
        val themeKey = "${Integer.toHexString(background)}/${Integer.toHexString(foreground)}/" +
            "${Integer.toHexString(fill)}@${barHeightPx()}@${spacingPx()}"
        if (themeKey != lastThemeKey) {
            lastThemeKey = themeKey
            applyTheme(background, foreground, fill)
        }
    }

    fun barHeightPx(): Int = dp(barHeightDp())

    // ------------------------------------------------------------------ 构建

    private fun rebuildButtons(actions: List<BarAction>) {
        row.removeAllViews()
        buttons.clear()
        for (action in actions) {
            row.addView(createButton(action))
        }
        lastThemeKey = null
        val background = resolveBackgroundColor()
        val foreground = resolveForegroundColor(background)
        applyTheme(background, foreground, resolveButtonFill(foreground))
        XLog.v("底栏按钮已重建：${actions.size} 个（${actions.joinToString(" ") { it.label }}）")
    }

    /**
     * 每个按钮等宽（weight=1）→ 自动均分整条底栏、从中间向两边铺开；
     * 左右各留 间距/2 的边距 → 按钮间距 = [Settings.Keys.BAR_SPACING]。
     */
    private fun createButton(action: BarAction): View {
        val style = barStyle()
        val withIcon = style == BarStyle.ICON || style == BarStyle.ICON_TEXT
        val withLabel = style == BarStyle.TEXT || style == BarStyle.ICON_TEXT

        val container = LinearLayout(imeService).apply {
            // 图标+文字用竖排，窄按钮里更耐看
            orientation = if (withLabel && withIcon) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }

        val icon = if (withIcon) {
            BarIconView(imeService).apply {
                setIcon(action)
                layoutParams = LinearLayout.LayoutParams(iconSizePx(), iconSizePx())
            }
        } else {
            null
        }

        val label = if (withLabel) {
            TextView(imeService).apply {
                text = action.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }
        } else {
            null
        }

        icon?.let { container.addView(it) }
        label?.let { container.addView(it) }
        container.setOnClickListener { v -> onAction?.invoke(action, v) }

        buttons.add(ButtonViews(action, container, icon, label))
        return container
    }

    // ------------------------------------------------------------------ 外观

    private fun applyTheme(background: Int, foreground: Int, buttonFill: Int) {
        // 不加发丝分隔线、不刻意提亮/压暗背景：底栏与键盘同色，不留"分界"
        this.background = GradientDrawable().apply { setColor(background) }

        // 水波纹：透明底色时用文字色的淡色，否则用按钮底色的深一点的版本
        val pillRipple = if (Color.alpha(buttonFill) == 0) {
            withAlpha(foreground, 0x33)
        } else {
            (buttonFill and 0x00FFFFFF) or (0x33 shl 24)
        }

        for (button in buttons) {
            button.icon?.setIconColor(foreground)
            button.label?.apply {
                setTextColor(foreground)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp().toFloat())
                if (button.icon != null) setPadding(0, dp(1), 0, 0)
            }
            // 底色透明时不画内容层，只留水波纹（少一层 overdraw）
            button.container.background = if (Color.alpha(buttonFill) == 0) {
                RippleDrawable(ColorStateList.valueOf(pillRipple), null, null)
            } else {
                RippleDrawable(
                    ColorStateList.valueOf(pillRipple),
                    pillDrawable(buttonFill),
                    pillDrawable(Color.WHITE),
                )
            }
            button.container.layoutParams = LinearLayout.LayoutParams(
                0,
                pillHeightPx(),
                1f,
            ).apply {
                marginStart = spacingPx() / 2
                marginEnd = spacingPx() / 2
            }
            (button.icon?.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                val size = iconSizePx()
                if (lp.width != size) {
                    lp.width = size
                    lp.height = size
                    button.icon.layoutParams = lp
                }
            }
        }

        // ★ 底栏自身的高度也必须跟着改：
        //   这个高度是挂载时写在 LayoutParams 上的，如果这里不更新，改「底栏高度」时
        //   只有里面的按钮在变、整条栏的高度要等底栏被重建（下次弹键盘）才生效 —— 表现为"生效很慢"。
        layoutParams?.let { lp ->
            val target = barHeightPx()
            if (lp.height != target) {
                lp.height = target
                layoutParams = lp
                XLog.v("底栏高度已更新：${target}px")
            }
        }
    }

    private fun pillDrawable(fill: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = pillHeightPx() / 2f
        setColor(fill)
    }

    // ------------------------------------------------------------------ 取色

    private fun resolveBackgroundColor(): Int {
        val base = when (barTheme()) {
            BarTheme.DARK -> 0xFF20232A.toInt()
            BarTheme.LIGHT -> 0xFFF2F3F6.toInt()
            BarTheme.CUSTOM -> Color.rgb(
                prefs.getInt(Settings.Keys.BAR_BG_R, 32).coerceIn(0, 255),
                prefs.getInt(Settings.Keys.BAR_BG_G, 35).coerceIn(0, 255),
                prefs.getInt(Settings.Keys.BAR_BG_B, 42).coerceIn(0, 255),
            )

            else -> autoBackgroundColor()
        }
        return withAlpha(base, alphaByte())
    }

    /** 跟随键盘：**原样**取键盘背景色（不提亮不压暗，避免出现"分界"）；取不到就按系统深色模式 */
    private fun autoBackgroundColor(): Int {
        val keyboardColor = (keyboard?.background as? ColorDrawable)?.color
        if (keyboardColor != null && Color.alpha(keyboardColor) > 200) {
            return keyboardColor
        }
        return if (systemNight()) 0xFF20232A.toInt() else 0xFFF2F3F6.toInt()
    }

    /**
     * 图标 / 文字颜色：
     * - 自动：背景够不透明时按**背景亮度取反色**；背景半透明/透明时**跟随系统深浅色取反色**；
     * - 深色 / 浅色：预设；
     * - 自定义：用 ICON_R / G / B 三个滑杆。
     */
    private fun resolveForegroundColor(background: Int): Int =
        when (prefs.getInt(Settings.Keys.ICON_COLOR, IconColor.AUTO)) {
            IconColor.DARK -> DARK_FOREGROUND
            IconColor.LIGHT -> LIGHT_FOREGROUND
            IconColor.CUSTOM -> Color.rgb(
                prefs.getInt(Settings.Keys.ICON_R, 26).coerceIn(0, 255),
                prefs.getInt(Settings.Keys.ICON_G, 27).coerceIn(0, 255),
                prefs.getInt(Settings.Keys.ICON_B, 31).coerceIn(0, 255),
            )

            else -> {
                val opaque = withAlpha(background, 0xFF)
                if (Color.alpha(background) >= 200) {
                    if (luminance(opaque) > 0.5) DARK_FOREGROUND else LIGHT_FOREGROUND
                } else {
                    // 半透明背景：跟系统深浅色取反
                    if (systemNight()) LIGHT_FOREGROUND else DARK_FOREGROUND
                }
            }
        }

    /**
     * 按钮底色：
     * - 跟随文字色（默认）：文字色的淡色版本；
     * - 透明：只要图标不要底色；
     * - 自定义：BUTTON_R / G / B + 按钮不透明度。
     */
    private fun resolveButtonFill(foreground: Int): Int =
        when (prefs.getInt(Settings.Keys.BUTTON_COLOR_MODE, ButtonColor.AUTO)) {
            ButtonColor.TRANSPARENT -> Color.TRANSPARENT

            ButtonColor.CUSTOM -> withAlpha(
                Color.rgb(
                    prefs.getInt(Settings.Keys.BUTTON_R, 128).coerceIn(0, 255),
                    prefs.getInt(Settings.Keys.BUTTON_G, 128).coerceIn(0, 255),
                    prefs.getInt(Settings.Keys.BUTTON_B, 128).coerceIn(0, 255),
                ),
                buttonAlphaByte(),
            )

            else -> withAlpha(foreground, buttonAlphaByte())
        }

    private fun buttonAlphaByte(): Int =
        (prefs.getInt(Settings.Keys.BUTTON_ALPHA, 10).coerceIn(0, 100) * 255 / 100)

    private fun systemNight(): Boolean =
        (imeService.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun alphaByte(): Int =
        (prefs.getInt(Settings.Keys.BAR_ALPHA, 100).coerceIn(0, 100) * 255 / 100)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun luminance(color: Int): Double =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0

    // ------------------------------------------------------------------ 配置读取

    private fun barHeightDp(): Int = prefs.getInt(Settings.Keys.BAR_HEIGHT, 44)

    private fun textSizeSp(): Int = prefs.getInt(Settings.Keys.TEXT_SIZE, 13)

    private fun barTheme(): Int = prefs.getInt(Settings.Keys.BAR_THEME, BarTheme.AUTO)

    private fun barStyle(): Int = prefs.getInt(Settings.Keys.BAR_STYLE, BarStyle.ICON)

    private fun spacingPx(): Int = dp(prefs.getInt(Settings.Keys.BAR_SPACING, 6).coerceIn(0, 24))

    /** 胶囊高度：比整条栏矮一点，四周留白 */
    private fun pillHeightPx(): Int = (barHeightPx() - dp(8)).coerceAtLeast(dp(24))

    private fun iconSizePx(): Int = (barHeightPx() * 0.46f).toInt().coerceIn(dp(16), dp(24))

    /** 图标+文字时文字要更小一点，否则窄按钮里放不下 */
    private fun labelSizeSp(): Int = (textSizeSp() - 4).coerceAtLeast(8)

    private fun dp(value: Int): Int =
        (value * imeService.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val TAG = "wte_keyboard_bar"

        private const val DARK_FOREGROUND = 0xFF1A1B1F.toInt()
        private const val LIGHT_FOREGROUND = 0xFFE9EAEC.toInt()
    }
}
