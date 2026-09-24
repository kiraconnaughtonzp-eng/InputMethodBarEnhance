package com.wetype.enhance.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/**
 * 绘制 [BarIcons] 里定义的矢量图标。
 *
 * 路径按 24x24 视口构建，绘制时缩放到实际尺寸，所以线宽/圆角在不同高度下都保持等比例。
 */
@SuppressLint("ViewConstructor")
class BarIconView(context: Context) : View(context) {

    private val path = Path()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BarIcons.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 预先建好，别在 onDraw 里分配（滚动时会反复重绘） */
    private val dashEffect = DashPathEffect(floatArrayOf(3.2f, 3.2f), 0f)

    fun setIcon(action: BarAction) {
        path.reset()
        path.addPath(BarIcons.pathOf(action))
        paint.pathEffect = if (BarIcons.isDashed(action)) dashEffect else null
        invalidate()
    }

    fun setIconColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (path.isEmpty) return
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val scale = size / BarIcons.VIEWPORT

        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
