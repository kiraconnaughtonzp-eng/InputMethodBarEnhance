package com.wetype.enhance.ime

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.wetype.enhance.ModuleRuntime
import com.wetype.enhance.config.Prefs
import com.wetype.enhance.config.Settings
import com.wetype.enhance.log.XLog
import de.robv.android.xposed.XposedHelpers

/**
 * 把底栏作为**键盘最底部的一行**挂进输入法窗口。
 *
 * 做法：把键盘容器的层级从 `[键盘]` 变成 `[键盘, 底栏]` —— 快捷键占最下面一行，
 * 键盘整体上移，**不覆盖任何按键**。
 *
 * 自愈机制（切夜间模式 / 重建窗口 / 从后台回来都不会丢）：
 * 1. **事件驱动**：底栏从窗口上被摘下来时（`onViewDetachedFromWindow`）立刻安排重挂（防抖 300ms）；
 * 2. **兜底看门狗**：10 秒一次极轻检查（在 [ImeHook] 里）；
 * 3. 生命周期钩子（onWindowShown / onConfigurationChanged）也会主动重挂。
 */
// bar 字段持有注入到输入法窗口里的 View：生命周期与输入法服务一致，属于有意为之
@SuppressLint("StaticFieldLeak")
object BarInjector {

    private const val WRAPPER_TAG = "wte_bar_wrapper"

    @Volatile
    private var bar: KeyboardBar? = null

    @Volatile
    private var lastAttachAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 底栏被摘下后自动重挂（事件驱动，不用等轮询） */
    private val reattachTask = Runnable {
        val service = ImeHook.currentService()
        val decor = service?.window?.window?.decorView
        if (service == null || decor == null || !decor.isShown) return@Runnable
        XLog.v("底栏被摘下（窗口/视图树重建），自动重挂")
        ensureAttached(service, ModuleRuntime.prefs())
    }

    /** 配置变化后刷新（主线程调用） */
    fun refresh(prefs: Prefs) {
        val service = ImeHook.currentService()
        if (service == null) {
            XLog.v("配置已更新，但输入法服务尚未创建，等 onWindowShown 再挂载")
            return
        }
        if (!prefs.getBoolean(Settings.Keys.ENABLED, true)) {
            detach()
            XLog.i("总开关已关闭，底栏已移除")
            return
        }
        ensureAttached(service, prefs)
    }

    /** 保证底栏存在且与配置一致：主线程、幂等 */
    fun ensureAttached(service: InputMethodService, prefs: Prefs) {
        // 兜底：只要真的要在键盘上渲染底栏，就确保跨进程配置监听已启动
        // （这样即使某条引导路径的时机不对，也不会"一直用默认配置"）
        ModuleRuntime.ensureStarted()

        if (!prefs.getBoolean(Settings.Keys.ENABLED, true)) {
            detach()
            return
        }

        if (isFullscreen(service)) {
            bar?.visibility = View.GONE
            XLog.v("处于全屏提取模式，底栏暂时隐藏")
            return
        }

        val existing = bar
        if (existing != null && isAliveInWindow(existing, service)) {
            existing.visibility = View.VISIBLE
            existing.bind(prefs, resolveKeyboardView(service), actionListener(service, prefs))
            return
        }
        if (existing != null) {
            // 只判断 parent != null 是不够的：窗口/主题被重建后，旧视图树虽然被摘掉但 parent 还在，
            // 于是以为底栏还活着、再也不重挂 —— 表现就是"切夜间模式 / 过一阵子底栏消失"。
            XLog.v("底栏已不在当前窗口里（窗口或主题被重建），重新挂载")
            detach()
        }

        if (!attachBottomRow(service, prefs)) {
            XLog.throttle("attach-fail", "底栏挂载失败：未找到键盘容器（窗口可能还没就绪）", 5000L)
        }
    }

    /** 轻量检查：底栏还挂在**当前**窗口里吗？（看门狗用，命中时什么都不做） */
    fun ensureAlive(service: InputMethodService, prefs: Prefs) {
        if (!prefs.getBoolean(Settings.Keys.ENABLED, true)) return
        val existing = bar
        if (existing != null && isAliveInWindow(existing, service)) return
        // 防抖：输入法可能正在重建视图，别在这个过程里反复插
        if (System.currentTimeMillis() - lastAttachAt < 1000L) return
        ensureAttached(service, prefs)
    }

    /**
     * 底栏是否仍然活在**当前**窗口的视图树里。
     *
     * `parent != null` 不够：视图树被整体替换后，旧节点依旧持有 parent 引用。
     * 所以还要确认它 attach 在窗口上、且根视图就是当前的 decorView。
     */
    private fun isAliveInWindow(barView: View, service: InputMethodService): Boolean {
        if (!barView.isAttachedToWindow) return false
        val decor = service.window?.window?.decorView ?: return false
        return barView.rootView === decor
    }

    /** 移除底栏并还原键盘层级 */
    fun detach() {
        mainHandler.removeCallbacks(reattachTask)

        val current = bar ?: return
        val parent = current.parent as? ViewGroup
        if (parent != null) {
            parent.removeView(current)
            if (parent.tag == WRAPPER_TAG) {
                val grand = parent.parent as? ViewGroup
                val index = grand?.indexOfChild(parent) ?: -1
                val lp = parent.layoutParams
                val frame = if (parent.childCount > 0) parent.getChildAt(0) else null
                parent.removeAllViews()
                if (grand != null && frame != null && index >= 0) {
                    if (lp != null) grand.addView(frame, index, lp) else grand.addView(frame, index)
                }
                grand?.removeView(parent)
            }
        }
        bar = null
        mainHandler.removeCallbacks(reattachTask)
    }

    fun isAttached(): Boolean = bar?.isAttachedToWindow == true

    fun describe(): String = if (isAttached()) "已挂载：键盘底部一行" else "未挂载"

    // ------------------------------------------------------------------ 挂载

    /**
     * 在键盘下面加一行底栏。两条路径，优先第一条：
     *
     * 1. **键盘的父容器是竖直 LinearLayout** → 直接把底栏作为兄弟行插在键盘后面，
     *    **完全不动键盘自己的 LayoutParams**（最安全）；
     * 2. 否则包一层 wrapper：高度必须用 `WRAP_CONTENT`。
     *
     * ⚠️ 绝不能把键盘的 LayoutParams 沿用到 wrapper 上：键盘那层通常是 `MATCH_PARENT`，
     * 而输入法窗口是 `WRAP_CONTENT` —— `MATCH_PARENT` 的孩子会把窗口撑到整屏高，
     * 结果键盘被顶到上面、下面留一大片空白（踩过这个坑）。
     */
    private fun attachBottomRow(service: InputMethodService, prefs: Prefs): Boolean {
        val frame = resolveInputFrame(service) ?: return false
        val parent = frame.parent as? ViewGroup ?: return false
        val index = parent.indexOfChild(frame)
        if (index < 0) return false

        val keyboard = resolveKeyboardView(service) ?: frame
        val newBar = KeyboardBar(service)
        newBar.bind(prefs, keyboard, actionListener(service, prefs))
        watchDetach(newBar)
        val barLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            newBar.barHeightPx(),
        )

        // ---- 路径 1：直接作为兄弟行（键盘的父容器是竖直 LinearLayout）----
        if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) {
            return try {
                parent.addView(newBar, index + 1, barLp)
                bar = newBar
                lastAttachAt = System.currentTimeMillis()
                XLog.i("底栏已挂载（键盘下方兄弟行）：parent=${parent.javaClass.simpleName} index=${index + 1}")
                logLayoutDiagnostics(service, frame, newBar)
                true
            } catch (t: Throwable) {
                XLog.e("底栏挂载失败（兄弟行）", t)
                false
            }
        }

        // ---- 路径 2：包一层 wrapper（高度用 WRAP_CONTENT，不沿用键盘的参数）----
        val frameLp = frame.layoutParams
        val wrapper = LinearLayout(service).apply {
            tag = WRAPPER_TAG
            orientation = LinearLayout.VERTICAL
        }

        parent.removeViewAt(index)
        wrapper.addView(
            frame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        wrapper.addView(newBar, barLp)
        parent.addView(wrapper, index, wrapperParams(parent, frameLp))

        bar = newBar
        lastAttachAt = System.currentTimeMillis()
        XLog.i("底栏已挂载（wrapper）：parent=${parent.javaClass.simpleName}")
        logLayoutDiagnostics(service, frame, newBar)
        return true
    }

    /** 底栏被摘下（窗口/视图树重建）就立刻安排重挂 */
    private fun watchDetach(barView: View) {
        barView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                mainHandler.removeCallbacks(reattachTask)
                mainHandler.postDelayed(reattachTask, 300L)
            }
        })
    }

    /** wrapper 的布局参数：宽度撑满、高度 WRAP_CONTENT；只保留必要的语义（weight / gravity） */
    private fun wrapperParams(parent: ViewGroup, frameLp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams {
        if (parent is LinearLayout) {
            val weight = (frameLp as? LinearLayout.LayoutParams)?.weight ?: 0f
            return if (weight > 0f) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    weight,
                )
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        val gravity = (frameLp as? FrameLayout.LayoutParams)?.gravity ?: Gravity.BOTTOM
        return FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.gravity = gravity }
    }

    /**
     * 布局自检：把各层高度和"下方剩余空间"打出来（留白偏大时降级成 warn，方便定位）。
     * 只在开了详细日志时做 —— 否则每次挂载都白跑一趟。
     */
    private fun logLayoutDiagnostics(service: InputMethodService, frame: View, barView: View) {
        if (!XLog.verbose) return
        mainHandler.postDelayed({
            try {
                val decor = service.window?.window?.decorView
                val parent = barView.parent as? ViewGroup
                val gapBelowBar = parent?.let { it.height - (barView.top + barView.height) } ?: -1
                val line = "底栏布局自检：键盘=${frame.width}x${frame.height}(top=${frame.top}) " +
                    "底栏=${barView.width}x${barView.height}(top=${barView.top}) " +
                    "父容器=${parent?.width}x${parent?.height} 窗口=${decor?.width}x${decor?.height} " +
                    "底栏下方剩余=${gapBelowBar}px"
                if (gapBelowBar > dp(service, 24)) {
                    XLog.w("$line ← 下方留白偏大，说明输入法窗口比内容高")
                } else {
                    XLog.v(line)
                }
            } catch (t: Throwable) {
                XLog.e("布局自检失败", t)
            }
        }, 400L)
    }

    private fun actionListener(
        service: InputMethodService,
        prefs: Prefs,
    ): (BarAction, View) -> Unit = { action, anchor ->
        ShortcutActions.perform(service, action, anchor, prefs)
    }

    // ------------------------------------------------------------------ 容器定位

    /** 定位"键盘输入区"容器：AOSP 里就是 InputMethodService.mInputFrame */
    private fun resolveInputFrame(service: InputMethodService): ViewGroup? {
        try {
            val frame = XposedHelpers.getObjectField(service, "mInputFrame")
            if (frame is ViewGroup) return frame
        } catch (t: Throwable) {
            XLog.v("读取 mInputFrame 失败：${t.javaClass.simpleName}")
        }

        try {
            val inputView = XposedHelpers.getObjectField(service, "mInputView") as? View
            val parent = inputView?.parent
            if (parent is ViewGroup) return parent
        } catch (t: Throwable) {
            XLog.v("读取 mInputView 失败：${t.javaClass.simpleName}")
        }

        val decor = service.window?.window?.decorView as? ViewGroup ?: return null
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return content.getChildAt(0) as? ViewGroup
    }

    /** 定位输入法的键盘根视图（用于"跟随键盘"取色） */
    private fun resolveKeyboardView(service: InputMethodService): View? {
        try {
            val inputView = XposedHelpers.getObjectField(service, "mInputView") as? View
            if (inputView != null) return inputView
        } catch (ignore: Throwable) {
            // 落到下面的兜底
        }
        try {
            val frame = XposedHelpers.getObjectField(service, "mInputFrame") as? ViewGroup
            if (frame != null && frame.childCount > 0) return frame.getChildAt(0)
        } catch (ignore: Throwable) {
            // 忽略
        }
        return null
    }

    private fun isFullscreen(service: InputMethodService): Boolean = try {
        service.isFullscreenMode
    } catch (t: Throwable) {
        false
    }

    private fun dp(service: InputMethodService, value: Int): Int =
        (value * service.resources.displayMetrics.density + 0.5f).toInt()
}
