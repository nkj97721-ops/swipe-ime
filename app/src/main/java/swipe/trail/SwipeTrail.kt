package swipe.trail

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import ime.swipe.TrailParams
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 滑行轨迹渲染 白色 参数见 TrailParams
 * 触摸: down / move / up   绘制: onDraw 末尾调用 draw 返回 true 时继续刷新
 */
class SwipeTrail(private val density: Float, var params: TrailParams = TrailParams()) {
    private class Pt(val x: Float, val y: Float, val t: Long)

    private val pts = ArrayList<Pt>()
    private var upAt = 0L
    private var sx = 0f
    private var sy = 0f
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
        // SRC 让新一段直接覆盖旧段 避免半透明叠加出现珠点
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }

    fun down(x: Float, y: Float, t: Long) {
        pts.clear()
        upAt = 0L
        sx = x
        sy = y
        pts.add(Pt(x, y, t))
    }

    fun move(x: Float, y: Float, t: Long) {
        val k = params.smooth
        sx = sx * k + x * (1f - k)
        sy = sy * k + y * (1f - k)
        pts.add(Pt(sx, sy, t))
    }

    fun up(t: Long) {
        upAt = t
    }

    /** 返回 true 表示动画未结束 调用方需继续刷新 */
    fun draw(c: Canvas, now: Long): Boolean {
        if (pts.isEmpty()) return false
        val p = params
        val life = max(1f, p.life)
        val extra = if (upAt > 0) (now - upAt) * (p.lift - 1f) else 0f
        fun age(q: Pt): Float = (now - q.t) + extra
        while (pts.isNotEmpty() && age(pts[0]) > life) pts.removeAt(0)
        if (pts.size < 2) return pts.isNotEmpty() && upAt == 0L

        val headPx = p.head * density
        val glowPx = p.glow * density
        val passes = if (glowPx > 0f) 2 else 1
        val layer = c.saveLayer(null, null)
        for (pass in 0 until passes) {
            val isGlow = passes == 2 && pass == 0
            for (i in 1 until pts.size) {
                val q = pts[i]
                val ax: Float
                val ay: Float
                if (i > 1) { ax = (pts[i - 1].x + q.x) / 2; ay = (pts[i - 1].y + q.y) / 2 } else { ax = pts[0].x; ay = pts[0].y }
                val bx: Float
                val by: Float
                if (i < pts.size - 1) { bx = (q.x + pts[i + 1].x) / 2; by = (q.y + pts[i + 1].y) / 2 } else { bx = q.x; by = q.y }
                val k = max(0f, 1f - age(q) / life)
                var wd = headPx * (p.tail + (1f - p.tail) * k.pow(p.taper))
                var a = p.opacity * k.pow(p.fade)
                if (isGlow) {
                    wd += 2f * glowPx
                    a *= 0.35f
                }
                paint.strokeWidth = wd
                paint.alpha = (255f * a).roundToInt().coerceIn(0, 255)
                path.rewind()
                path.moveTo(ax, ay)
                path.quadTo(q.x, q.y, bx, by)
                c.drawPath(path, paint)
            }
        }
        c.restoreToCount(layer)
        return true
    }
}
