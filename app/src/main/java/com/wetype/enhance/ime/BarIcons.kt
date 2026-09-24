package com.wetype.enhance.ime

import android.graphics.Path
import android.graphics.RectF

/**
 * 快捷栏图标：24x24 视口内的纯代码矢量路径（不依赖模块资源，宿主进程里也能用）。
 *
 * - 线宽固定 [STROKE]（视口单位），绘制时把画布缩放到实际尺寸即可；
 * - 想调造型：改这里 → 运行 `tools/icon-preview.ps1` 生成预览图先看效果，确认后再回填。
 */
object BarIcons {

    const val VIEWPORT = 24f
    const val STROKE = 2f

    /** 该图标是否需要虚线描边（PathEffect 在绘制时设置） */
    fun isDashed(action: BarAction): Boolean = action == BarAction.SELECT_ALL

    fun pathOf(action: BarAction): Path {
        val p = Path()
        when (action) {
            BarAction.SELECT_ALL -> {
                roundRect(p, 3.5f, 3.5f, 17f, 17f, 3f)
                poly(p, 8.6f, 12.2f, 11.0f, 14.6f, 15.8f, 9.2f)
            }

            BarAction.COPY -> {
                roundRect(p, 9f, 9f, 11.5f, 11.5f, 2.5f)
                p.moveTo(15.3f, 5f)
                p.lineTo(7f, 5f)
                // 从 12 点钟方向逆时针画到 9 点钟，正好接上当前点，不会多出一条斜线
                p.arcTo(RectF(4.5f, 5f, 9.5f, 10f), 270f, -90f, false)
                p.lineTo(4.5f, 15.3f)
            }

            BarAction.CUT -> {
                circle(p, 6.8f, 18.6f, 2.4f)
                circle(p, 17.2f, 18.6f, 2.4f)
                line(p, 8.4f, 16.9f, 17.6f, 4.6f)
                line(p, 15.6f, 16.9f, 6.4f, 4.6f)
            }

            BarAction.PASTE -> {
                roundRect(p, 5f, 5f, 14f, 15.5f, 2f)
                roundRect(p, 9f, 3f, 6f, 4f, 1.5f)
                line(p, 12f, 9.8f, 12f, 15.2f)
                poly(p, 9.7f, 12.9f, 12f, 15.2f, 14.3f, 12.9f)
            }

            BarAction.CLEAR -> {
                line(p, 4.5f, 6.8f, 19.5f, 6.8f)
                line(p, 9.5f, 4f, 14.5f, 4f)
                roundRect(p, 7f, 7f, 10f, 13.5f, 2.5f)
                line(p, 10.8f, 10.6f, 10.8f, 16.8f)
                line(p, 13.2f, 10.6f, 13.2f, 16.8f)
            }

            BarAction.CURSOR_LEFT -> poly(p, 14.5f, 5.5f, 8f, 12f, 14.5f, 18.5f)

            BarAction.CURSOR_RIGHT -> poly(p, 9.5f, 5.5f, 16f, 12f, 9.5f, 18.5f)

            BarAction.LINE_START -> {
                line(p, 4.8f, 5.5f, 4.8f, 18.5f)
                line(p, 17.2f, 12f, 7.6f, 12f)
                poly(p, 10.6f, 8.8f, 7.6f, 12f, 10.6f, 15.2f)
            }

            BarAction.LINE_END -> {
                line(p, 19.2f, 5.5f, 19.2f, 18.5f)
                line(p, 6.8f, 12f, 16.4f, 12f)
                poly(p, 13.4f, 8.8f, 16.4f, 12f, 13.4f, 15.2f)
            }

            BarAction.UNDO -> {
                arc(p, 12f, 12.6f, 6f, 180f, 180f)
                poly(p, 9.2f, 9.4f, 6f, 12.6f, 9.2f, 15.8f)
            }

            BarAction.REDO -> {
                arc(p, 12f, 12.6f, 6f, 180f, 180f)
                poly(p, 14.8f, 9.4f, 18f, 12.6f, 14.8f, 15.8f)
            }

            BarAction.HIDE_KEYBOARD -> {
                poly(p, 6f, 9.5f, 12f, 15.5f, 18f, 9.5f)
                line(p, 6f, 19f, 18f, 19f)
            }
        }
        return p
    }

    // ------------------------------------------------------------------ 基础图形

    private fun roundRect(p: Path, x: Float, y: Float, w: Float, h: Float, r: Float) {
        p.addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW)
    }

    private fun circle(p: Path, cx: Float, cy: Float, r: Float) {
        p.addCircle(cx, cy, r, Path.Direction.CW)
    }

    private fun line(p: Path, x1: Float, y1: Float, x2: Float, y2: Float) {
        p.moveTo(x1, y1)
        p.lineTo(x2, y2)
    }

    private fun poly(p: Path, vararg pts: Float) {
        if (pts.size < 4) return
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            p.lineTo(pts[i], pts[i + 1])
            i += 2
        }
    }

    private fun arc(p: Path, cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
        p.addArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep)
    }
}
