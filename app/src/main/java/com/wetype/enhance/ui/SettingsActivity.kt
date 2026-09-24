package com.wetype.enhance.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.wetype.enhance.BuildConfig
import com.wetype.enhance.R
import com.wetype.enhance.config.CommandChannel
import com.wetype.enhance.config.LocalConfig
import com.wetype.enhance.config.Prefs
import com.wetype.enhance.config.Setting
import com.wetype.enhance.config.Settings
import com.wetype.enhance.log.XLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用设置界面（框架自带）—— 力求简短：
 *
 * 1. 顶部一张卡：模块名 + 版本 + 心跳（就两行）；
 * 2. 「设置」卡：由 [Settings.all] 自动生成，常用项铺开，其余靠
 *    [Setting.Header.collapsible] 折叠、[Setting.showIf] 条件显示；
 * 3. 「诊断」卡：日志 + 常用命令按钮；结果只在执行过后出现，手动输入命令默认折叠；
 * 4. 「使用说明」默认折叠。
 *
 * 需要更复杂的界面时重写 [buildCustomSections]；常用诊断命令重写 [diagnosticCommands]。
 */
open class SettingsActivity : Activity() {

    private companion object {
        /** 拖动滑杆时两次保存之间的最短间隔 */
        const val PERSIST_THROTTLE_MS = 200L
    }

    private var config: Prefs = Prefs.empty()
    private var configVersion = 0L

    private lateinit var content: LinearLayout
    private var heartbeatView: TextView? = null
    private var heartbeatStatusView: TextView? = null
    private var logView: TextView? = null
    private var commandNameInput: EditText? = null
    private var commandArgInput: EditText? = null
    private var commandResultView: TextView? = null

    private val expandedSections = HashSet<String>()
    private var manualCommandVisible = false
    private var helpVisible = false
    private var hasCommandResult = false
    private var diagnosticsVisible = false

    /** 上次保存配置的时间：拖动滑杆时用它节流 */
    private var lastPersistAt = 0L

    // ---- 实时预览（弹出目标 App 的键盘，边看边调）----
    private var previewInput: EditText? = null
    private var previewVisible = false
    private var previewActive = false

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadConfig()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Ui.dp(this@SettingsActivity, 16),
                Ui.dp(this@SettingsActivity, 12),
                Ui.dp(this@SettingsActivity, 16),
                Ui.dp(this@SettingsActivity, 24),
            )
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(scroll)

        render()
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnostics()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    /** ★ 自定义界面入口：返回的 View 会追加在「设置」卡之后 */
    protected open fun buildCustomSections(config: Prefs, save: () -> Unit): List<View> = emptyList()

    /** ★ 诊断区的常用命令按钮：`按钮文字 to 命令名` */
    protected open fun diagnosticCommands(): List<Pair<String, String>> = emptyList()

    /**
     * ★ 是否显示「实时预览」卡：适合"往目标 App 界面里注入 UI"的模块 ——
     * 在本 App 里点一下就能弹出目标 App 的键盘（输入法窗口是同一个），
     * 然后一边滚设置一边看注入效果实时变化。
     */
    protected open fun previewEnabled(): Boolean = false

    /** 实时预览的说明文字 */
    protected open fun previewHint(): String =
        "弹出输入法后，在下面的输入框里打字即可保持键盘弹出；改上面的参数，注入的界面会在 1~2 秒内实时更新。"

    // ------------------------------------------------------------------ 渲染

    private fun render() {
        try {
            renderInternal()
        } catch (t: Throwable) {
            // 兜底：设置界面本身出错也不该把 App 崩掉
            XLog.e("设置界面渲染失败", t)
            toast("界面渲染出错：${t.javaClass.simpleName}（详情见 LSPosed 日志）")
        }
    }

    private fun renderInternal() {
        content.removeAllViews()
        content.addView(statusCard())
        if (previewEnabled()) content.addView(previewCard())
        content.addView(settingsCard())
        buildCustomSections(config) { persist() }.forEach { content.addView(it) }
        content.addView(diagnosticsCard())
        content.addView(helpCard())
        refreshDiagnostics()
        // 重绘后把焦点还给预览输入框，键盘就不会掉下去
        if (previewActive && previewVisible) previewInput?.post { showPreviewKeyboard() }
    }

    /**
     * 实时预览卡：默认只显示一个按钮（不占版面），点了才出现输入框并弹出键盘。
     */
    private fun previewCard(): View {
        val card = Ui.card(this)
        card.addView(Ui.sectionTitle(this, "实时预览"))

        if (!previewVisible) {
            card.addView(Ui.body(this, previewHint(), sizeSp = 12f))
            card.addView(
                Ui.buttonRow(
                    this,
                    Ui.button(this, "弹出输入法预览", primary = true) { startPreview() },
                )
            )
            return card
        }

        card.addView(Ui.body(this, "在下面输入框里点一下就能保持键盘弹出；改上面的参数，注入的界面约 1 秒后更新。", sizeSp = 12f))
        val input = previewInput ?: Ui.input(this, hint = "点这里输入…").apply {
            isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus -> previewActive = hasFocus }
        }.also { previewInput = it }
        // 输入框是复用同一个实例的：重绘时它身上可能还挂着上一张卡片，必须先摘掉再 addView，
        // 否则会抛 "The specified child already has a parent"（踩过，会闪退）
        (input.parent as? ViewGroup)?.removeView(input)
        card.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(this@SettingsActivity, 8) },
        )
        card.addView(
            Ui.buttonRow(
                this,
                Ui.button(this, "重开键盘") { showPreviewKeyboard() },
                Ui.button(this, "收起键盘") { stopPreview() },
            )
        )
        return card
    }

    private fun startPreview() {
        previewVisible = true
        previewActive = true
        render()
    }

    private fun stopPreview() {
        previewVisible = false
        previewActive = false
        val input = previewInput
        if (input != null) {
            try {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
                input.clearFocus()
            } catch (t: Throwable) {
                XLog.e("收起预览键盘失败", t)
            }
        }
        render()
    }

    private fun showPreviewKeyboard() {
        val input = previewInput ?: return
        try {
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            previewActive = true
        } catch (t: Throwable) {
            XLog.e("弹出预览键盘失败", t)
            toast("弹出输入法失败：${t.javaClass.simpleName}")
        }
    }

    /** 只剩模块名 + 版本，保持干净 */
    private fun statusCard(): View {
        val card = Ui.card(this)
        val label = try {
            applicationInfo.loadLabel(packageManager).toString()
        } catch (t: Throwable) {
            "本模块"
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            Ui.text(this, label, 18f).apply { typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(Ui.body(this, "v${BuildConfig.VERSION_NAME}", sizeSp = 11.5f))
        card.addView(titleRow)
        return card
    }

    private fun settingsCard(): View {
        val card = Ui.card(this)
        var insideCollapsedSection: String? = null

        for (setting in Settings.all) {
            if (setting.showIf?.invoke(config) == false) continue

            if (setting is Setting.Header) {
                if (setting.collapsible) {
                    val expanded = expandedSections.contains(setting.key)
                    card.addView(collapsibleHeader(setting, expanded))
                    insideCollapsedSection = if (expanded) null else setting.key
                } else {
                    card.addView(Ui.sectionTitle(this, setting.title, setting.desc))
                    insideCollapsedSection = null
                }
                continue
            }

            if (insideCollapsedSection != null) continue
            card.addView(renderSetting(setting))
        }
        return card
    }

    private fun collapsibleHeader(header: Setting.Header, expanded: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Ui.dp(this@SettingsActivity, 2),
                Ui.dp(this@SettingsActivity, 12),
                Ui.dp(this@SettingsActivity, 2),
                Ui.dp(this@SettingsActivity, 8),
            )
            isClickable = true
            background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE_LIGHT), null, null)
        }
        row.addView(
            Ui.text(this, (if (expanded) "▾  " else "▸  ") + header.title, 15f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (!header.desc.isNullOrEmpty()) {
            row.addView(Ui.body(this, header.desc, sizeSp = 12f))
        }
        row.setOnClickListener {
            if (expanded) expandedSections.remove(header.key) else expandedSections.add(header.key)
            render()
        }
        return row
    }

    private fun renderSetting(setting: Setting): View = when (setting) {
        is Setting.Header -> Ui.sectionTitle(this, setting.title, setting.desc)

        is Setting.Switch -> Ui.switchRow(
            this,
            setting.title,
            setting.desc,
            config.getBoolean(setting.key, setting.default),
        ) { value ->
            config.set(setting.key, value)
            persist()
            render()
        }

        is Setting.Slider -> Ui.seekRow(
            this,
            setting.title,
            config.getInt(setting.key, setting.default),
            setting.min,
            setting.max,
            setting.unit,
            onChange = {
                config.set(setting.key, it)
                // 拖动过程中也实时生效（节流：约 5 次/秒），松手时再最终落盘
                persistThrottled()
            },
            onCommit = {
                config.set(setting.key, it)
                persist()
            },
        )

        is Setting.Segmented -> Ui.labeledBlock(
            this,
            setting.title,
            setting.desc,
            Ui.segmented(this, setting.options, config.getInt(setting.key, setting.default)) { index ->
                config.set(setting.key, index)
                persist()
                render()
            },
        )

        is Setting.Text -> {
            val input = Ui.input(this, setting.hint, config.getString(setting.key, setting.default))
            val commit = {
                config.set(setting.key, input.text?.toString().orEmpty())
                persist()
            }
            input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
            input.setOnEditorActionListener { _, _, _ ->
                commit()
                true
            }
            Ui.labeledBlock(this, setting.title, setting.desc, input)
        }
    }

    // ------------------------------------------------------------------ 诊断

    /** 诊断与调试：默认折叠成一行（右侧直接显示心跳状态），点开才是日志 / 命令 / JSON */
    private fun diagnosticsCard(): View {
        val card = Ui.card(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Ui.dp(this@SettingsActivity, 2),
                Ui.dp(this@SettingsActivity, 10),
                Ui.dp(this@SettingsActivity, 2),
                Ui.dp(this@SettingsActivity, 10),
            )
            isClickable = true
            background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE_LIGHT), null, null)
        }
        header.addView(
            Ui.text(this, (if (diagnosticsVisible) "▾  " else "▸  ") + "诊断与调试", 15f),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        heartbeatStatusView = Ui.body(this, "", sizeSp = 12f)
        header.addView(heartbeatStatusView)
        header.setOnClickListener {
            diagnosticsVisible = !diagnosticsVisible
            render()
        }
        card.addView(header)

        if (!diagnosticsVisible) return card

        heartbeatView = Ui.body(this, "读取中…", sizeSp = 11.5f)
        card.addView(heartbeatView)

        card.addView(Ui.spacer(this, 6))
        card.addView(Ui.body(this, "最近日志", sizeSp = 11.5f))
        logView = Ui.body(this, "（暂无日志）", mono = true, sizeSp = 10f).apply {
            background = Ui.innerPanel(this@SettingsActivity)
            setPadding(
                Ui.dp(this@SettingsActivity, 9),
                Ui.dp(this@SettingsActivity, 9),
                Ui.dp(this@SettingsActivity, 9),
                Ui.dp(this@SettingsActivity, 9),
            )
        }
        card.addView(
            logView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val presets = diagnosticCommands()
        if (presets.isNotEmpty()) {
            card.addView(Ui.spacer(this, 8))
            card.addView(commandButtons(presets))
        }

        // 结果只在执行过命令后出现
        commandResultView = Ui.body(this, "", mono = true, sizeSp = 10f).apply {
            background = Ui.innerPanel(this@SettingsActivity)
            setPadding(
                Ui.dp(this@SettingsActivity, 9),
                Ui.dp(this@SettingsActivity, 9),
                Ui.dp(this@SettingsActivity, 9),
                Ui.dp(this@SettingsActivity, 9),
            )
            visibility = if (hasCommandResult) View.VISIBLE else View.GONE
        }
        card.addView(
            commandResultView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = Ui.dp(this@SettingsActivity, 8) },
        )

        card.addView(Ui.spacer(this, 6))
        card.addView(
            clickableLabel(if (manualCommandVisible) "▾ 手动输入命令" else "▸ 手动输入命令") {
                manualCommandVisible = !manualCommandVisible
                render()
            }
        )
        if (manualCommandVisible) {
            commandNameInput = Ui.input(this, hint = "命令名，例如 ping", value = "ping")
            commandArgInput = Ui.input(this, hint = "参数（可留空）")
            card.addView(Ui.labeledBlock(this, "命令名", null, commandNameInput!!))
            card.addView(Ui.labeledBlock(this, "参数", null, commandArgInput!!))
            card.addView(
                Ui.buttonRow(
                    this,
                    Ui.button(this, "执行命令", primary = true) {
                        runCommand(
                            commandNameInput?.text?.toString()?.trim().orEmpty(),
                            commandArgInput?.text?.toString().orEmpty(),
                        )
                    },
                )
            )
        }

        card.addView(Ui.spacer(this, 6))
        card.addView(
            Ui.buttonRow(
                this,
                Ui.button(this, "复制诊断信息") { copyDiagnostics() },
                Ui.button(this, "查看配置 JSON") { showTextDialog("配置 JSON", config.toJson()) },
            )
        )
        return card
    }

    /** 常用命令：每行 3 个，点一下直接执行 */
    private fun commandButtons(presets: List<Pair<String, String>>): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@SettingsActivity, 4), 0, 0)
        }
        presets.chunked(3).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (label, command) ->
                row.addView(
                    Ui.button(this, label) { runCommand(command, "") },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = Ui.dp(this@SettingsActivity, 8)
                    },
                )
            }
            repeat(3 - chunk.size) {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(0, Ui.dp(this@SettingsActivity, 1), 1f).apply {
                        marginEnd = Ui.dp(this@SettingsActivity, 8)
                    },
                )
            }
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = Ui.dp(this@SettingsActivity, 8) },
            )
        }
        return column
    }

    private fun clickableLabel(text: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Ui.dp(this@SettingsActivity, 2),
                Ui.dp(this@SettingsActivity, 10),
                Ui.dp(this@SettingsActivity, 2),
                Ui.dp(this@SettingsActivity, 10),
            )
            isClickable = true
            background = RippleDrawable(ColorStateList.valueOf(Ui.RIPPLE_LIGHT), null, null)
        }
        row.addView(Ui.body(this, text, sizeSp = 13f))
        row.setOnClickListener { onClick() }
        return row
    }

    /** 使用说明：默认折叠，不占版面 */
    private fun helpCard(): View {
        val card = Ui.card(this)
        card.addView(
            clickableLabel(if (helpVisible) "▾  使用说明" else "▸  使用说明") {
                helpVisible = !helpVisible
                render()
            }
        )
        if (helpVisible) {
            card.addView(
                Ui.body(
                    this,
                    "1. LSPosed 中启用本模块，作用域勾选目标 App；\n" +
                        "2. 强制停止目标 App（或在 LSPosed 里重启作用域）后重新打开；\n" +
                        "3. 顶部出现心跳时间 = 模块注入成功。\n\n" +
                        "· 设置改动 1~2 秒内自动同步到目标进程；\n" +
                        "· 看模块日志：LSPosed 日志里过滤 ${XLog.TAG}。",
                )
            )
        }
        return card
    }

    // ------------------------------------------------------------------ 交互

    private fun loadConfig() {
        val loaded = LocalConfig.load(this)
        config = loaded.first
        configVersion = loaded.second
    }

    private fun persist() {
        lastPersistAt = System.currentTimeMillis()
        configVersion = LocalConfig.save(this, config)
    }

    /**
     * 拖动滑杆时的节流保存：让目标进程**边拖边变**，又不至于每个 tick 都写一次盘 + 通知一次。
     * 松手时 [persist] 会做最终保存。
     */
    private fun persistThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastPersistAt >= PERSIST_THROTTLE_MS) persist()
    }

    private fun runCommand(name: String, arg: String) {
        if (name.isEmpty()) {
            toast("请先填写命令名")
            return
        }
        hasCommandResult = true
        commandResultView?.visibility = View.VISIBLE
        commandResultView?.text = "执行中…（通常 2~4 秒）"
        CommandChannel.send(this, name, arg, timeoutMs = 8000L) { result ->
            commandResultView?.text = result
                ?: "没有收到结果：确认目标 App 正在运行、模块已在 LSPosed 中启用且作用域已勾选"
            refreshDiagnostics()
        }
    }

    private fun refreshDiagnostics() {
        val diag = LocalConfig.readDiag(this)
        val alive = diag.heartbeat > 0

        // 折叠标题右侧的一眼状态
        heartbeatStatusView?.text = if (alive) {
            "✓ ${timeFormat.format(Date(diag.heartbeat))}"
        } else {
            "✗ 无心跳 · 点开查看"
        }

        heartbeatView?.text = if (alive) {
            "✓ 心跳 ${timeFormat.format(Date(diag.heartbeat))} · pid ${diag.pid} · 配置 v$configVersion"
        } else {
            "✗ 没有心跳：检查 LSPosed 是否启用本模块、作用域是否勾选目标 App，然后强制停止目标 App 重开"
        }

        logView?.text = diag.note.lines().takeLast(10).joinToString("\n").ifBlank { "（暂无日志）" }
    }

    private fun copyDiagnostics() {
        val diag = LocalConfig.readDiag(this)
        val sb = StringBuilder()
        sb.appendLine("==== 模块诊断 ====")
        sb.appendLine("模块版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("配置版本：$configVersion")
        sb.appendLine("配置：${config.toJson()}")
        sb.appendLine(
            "心跳：" + (if (diag.heartbeat > 0) timeFormat.format(Date(diag.heartbeat)) else "无") +
                "  pid=" + diag.pid
        )
        if (diag.cmdResult.isNotEmpty()) {
            sb.appendLine("---- 最近命令结果 ----")
            sb.appendLine(diag.cmdResult)
        }
        sb.appendLine("---- 模块日志 ----")
        sb.appendLine(diag.note.ifBlank { "(空)" })
        copyToClipboard(sb.toString(), "模块诊断")
    }

    private fun showTextDialog(title: String, text: String) {
        val body = Ui.body(this, text, mono = true, sizeSp = 10f).apply {
            setTextColor(this@SettingsActivity.getColor(R.color.kit_text))
            setPadding(
                Ui.dp(this@SettingsActivity, 12),
                Ui.dp(this@SettingsActivity, 8),
                Ui.dp(this@SettingsActivity, 12),
                Ui.dp(this@SettingsActivity, 8),
            )
        }
        val scroll = ScrollView(this).apply {
            addView(
                body,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("复制") { _, _ -> copyToClipboard(text, title) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun copyToClipboard(text: String, label: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            toast("已复制到剪贴板")
        } catch (t: Throwable) {
            toast("复制失败：${t.javaClass.simpleName}")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
