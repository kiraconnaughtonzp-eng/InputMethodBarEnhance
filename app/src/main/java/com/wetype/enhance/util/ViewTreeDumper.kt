package com.wetype.enhance.util

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视图树 dump（框架自带的通用诊断工具）。
 *
 * 用途：目标 App 的类名/布局往往是混淆的，靠 dump 才能确认某个界面里到底有哪些节点、
 * 哪个才是你要改的那个控件。
 *
 * ```
 * // 配合你自己的钩子：拿到 Activity / Dialog 的 decorView 后 dump
 * CommandRegistry.register("view_tree") { ViewTreeDumper.dumpTopWindow() }
 * ```
 */
object ViewTreeDumper {

    private const val MAX_NODES = 400

    /** dump 指定根视图 */
    fun dump(root: View, title: String = ""): String {
        val sb = StringBuilder()
        sb.append("视图树 dump ").append(if (title.isEmpty()) "" else "· $title ").append("@ ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("root=").append(root.javaClass.name).append('\n')
        sb.append("（缩进=层级，[x,y w x h] 为屏幕坐标与尺寸，# 为资源 id）\n")
        sb.append("--------------------------------------------------\n")

        var count = 0

        fun walk(view: View, depth: Int) {
            if (count >= MAX_NODES) return
            count++

            val location = IntArray(2)
            view.getLocationOnScreen(location)

            sb.append("  ".repeat(depth.coerceAtMost(20)))
            sb.append(view.javaClass.name)

            val id = view.id
            if (id != View.NO_ID) {
                sb.append(" #")
                sb.append(
                    try {
                        view.resources.getResourceEntryName(id)
                    } catch (t: Throwable) {
                        "0x" + Integer.toHexString(id)
                    }
                )
            }

            sb.append(" [").append(location[0]).append(',').append(location[1]).append(' ')
                .append(view.width).append('x').append(view.height).append(']')

            if (view.visibility != View.VISIBLE) {
                sb.append(" vis=").append(
                    when (view.visibility) {
                        View.INVISIBLE -> "INVISIBLE"
                        View.GONE -> "GONE"
                        else -> view.visibility.toString()
                    }
                )
            }
            if (view is TextView) {
                val text = view.text?.toString()?.replace('\n', ' ')?.trim()
                if (!text.isNullOrEmpty()) {
                    sb.append(" text=\"").append(text.take(24)).append('"')
                }
            }
            if (view is ViewGroup) {
                sb.append(" children=").append(view.childCount)
            }
            sb.append('\n')

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i), depth + 1)
                }
            }
        }

        walk(root, 0)
        if (count >= MAX_NODES) {
            sb.append("...(已截断，最多 ").append(MAX_NODES).append(" 个节点)\n")
        }
        return sb.toString()
    }

    /**
     * 尝试 dump 当前进程最上层窗口的视图树。
     *
     * 走 `WindowManagerGlobal.getRootViews()` 反射：部分 ROM / Android 版本会限制隐藏 API，
     * 失败时返回原因文本（此时请在你自己的钩子里拿到 View 后调用 [dump]）。
     */
    fun dumpTopWindow(): String {
        val root = try {
            topRootView()
        } catch (t: Throwable) {
            return "取顶层窗口失败（隐藏 API 可能被限制）：${t.javaClass.simpleName}: ${t.message}\n" +
                "请在模块自己的钩子里拿到 View 后调用 ViewTreeDumper.dump(view)"
        } ?: return "没有拿到窗口根视图：目标 App 可能还没有任何界面，先打开它的界面再试"
        return dump(root, "topWindow")
    }

    // WindowManagerGlobal 是隐藏 API：这里是有意为之的调试用途，失败会被 try/catch 兜住
    @SuppressLint("PrivateApi")
    private fun topRootView(): View? {
        val clazz = Class.forName("android.view.WindowManagerGlobal")
        val instance = clazz.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = clazz.getMethod("getRootViews").invoke(instance) as? List<View> ?: return null
        return views.lastOrNull()
    }
}
