package com.wetype.enhance.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.wetype.enhance.R

/**
 * 设置界面用的小组件工具集（纯 android.* 手搓，不依赖 AppCompat / Compose）。
 *
 * 新模块可以直接复用这些函数拼自己的界面，或者用 [com.wetype.enhance.config.Settings] 声明式配置。
 */
internal object Ui {

    const val RIPPLE_LIGHT = 0x14FFFFFF

    fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 14).toFloat()
            setColor(ctx.getColor(R.color.kit_card))
            setStroke(dp(ctx, 1), ctx.getColor(R.color.kit_stroke))
        }
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 12) }
    }

    /** 卡片内部的深色面板背景（日志 / 结果区） */
    fun innerPanel(ctx: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(ctx, 10).toFloat()
        setColor(0xFF10131A.toInt())
        setStroke(dp(ctx, 1), 0x14FFFFFF)
    }

    fun sectionTitle(ctx: Context, title: String, desc: String? = null): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 2), 0, dp(ctx, 2), dp(ctx, 8))
        }
        row.addView(
            View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 2).toFloat()
                    setColor(ctx.getColor(R.color.kit_accent))
                }
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 14)).apply {
                    marginEnd = dp(ctx, 8)
                }
            }
        )
        row.addView(
            TextView(ctx).apply {
                text = title
                setTextColor(ctx.getColor(R.color.kit_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (!desc.isNullOrEmpty()) {
            row.addView(
                TextView(ctx).apply {
                    text = desc
                    setTextColor(ctx.getColor(R.color.kit_text_dim))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }
            )
        }
        return row
    }

    fun text(ctx: Context, value: String, sizeSp: Float = 14f): TextView =
        TextView(ctx).apply {
            text = value
            setTextColor(ctx.getColor(R.color.kit_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        }

    fun body(ctx: Context, value: String, mono: Boolean = false, sizeSp: Float = 11.5f): TextView =
        TextView(ctx).apply {
            text = value
            setTextColor(ctx.getColor(R.color.kit_text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (mono) typeface = Typeface.MONOSPACE
            setLineSpacing(dp(ctx, 2).toFloat(), 1f)
        }

    fun spacer(ctx: Context, heightDp: Int = 8): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, heightDp),
        )
    }

    fun switchRow(
        ctx: Context,
        title: String,
        desc: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE_LIGHT), null, null)
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(ctx, title))
        if (!desc.isNullOrEmpty()) {
            texts.addView(body(ctx, desc, sizeSp = 11.5f))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val switch = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(switch)
        row.setOnClickListener { switch.toggle() }
        return row
    }

    // 数值标签就是数字本身，不需要走 strings.xml 国际化
    @SuppressLint("SetTextI18n")
    fun seekRow(
        ctx: Context,
        title: String,
        value: Int,
        min: Int,
        max: Int,
        unit: String,
        onChange: (Int) -> Unit,
        onCommit: (Int) -> Unit,
    ): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 2))
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val valueText = TextView(ctx).apply {
            text = "$value$unit"
            setTextColor(ctx.getColor(R.color.kit_text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        header.addView(text(ctx, title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(valueText)
        wrap.addView(header)

        wrap.addView(
            SeekBar(ctx).apply {
                this.max = max - min
                progress = (value - min).coerceIn(0, this.max)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val current = min + progress
                        valueText.text = "$current$unit"
                        onChange(current)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        onCommit(min + (bar?.progress ?: 0))
                    }
                })
            }
        )
        return wrap
    }

    fun segmented(
        ctx: Context,
        options: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }
        options.forEachIndexed { index, label ->
            val active = index == selected
            row.addView(
                TextView(ctx).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(if (active) 0xFFFFFFFF.toInt() else ctx.getColor(R.color.kit_text_dim))
                    setPadding(dp(ctx, 8), dp(ctx, 9), dp(ctx, 8), dp(ctx, 9))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 10).toFloat()
                        setColor(if (active) ctx.getColor(R.color.kit_accent) else RIPPLE_LIGHT)
                    }
                    isClickable = true
                    setOnClickListener { onSelect(index) }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (index < options.size - 1) dp(ctx, 8) else 0
                },
            )
        }
        return row
    }

    /** 标题 + 说明 + 自定义内容（用于分段选择 / 文本输入这类需要标题的配置项） */
    fun labeledBlock(ctx: Context, title: String, desc: String?, content: View): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
        }
        wrap.addView(text(ctx, title))
        if (!desc.isNullOrEmpty()) wrap.addView(body(ctx, desc))
        wrap.addView(content)
        return wrap
    }

    fun input(ctx: Context, hint: String, value: String = ""): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setText(value)
            setTextColor(ctx.getColor(R.color.kit_text))
            setHintTextColor(ctx.getColor(R.color.kit_text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = innerPanel(ctx)
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

    fun button(
        ctx: Context,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit,
    ): View = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(dp(ctx, 10), dp(ctx, 11), dp(ctx, 10), dp(ctx, 11))
        background = RippleDrawable(
            ColorStateList.valueOf(0x40FFFFFF),
            GradientDrawable().apply {
                cornerRadius = dp(ctx, 10).toFloat()
                setColor(if (primary) ctx.getColor(R.color.kit_accent) else 0x22FFFFFF)
            },
            null,
        )
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun buttonRow(ctx: Context, vararg buttons: View): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 4), 0, 0)
        }
        buttons.forEachIndexed { index, view ->
            row.addView(
                view,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = if (index < buttons.size - 1) dp(ctx, 8) else 0
                },
            )
        }
        return row
    }
}
