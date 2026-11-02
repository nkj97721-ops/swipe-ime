package ime.swipe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import swipe.trail.SwipeTrail

/** 应用内的轨迹预览 黑底加三行字母 在上面划动查看效果 */
class TrailPreviewView(ctx: Context, params: TrailParams) : View(ctx) {
    private val trail = SwipeTrail(resources.displayMetrics.density, params)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val rows = arrayOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val w = width.toFloat()
        val rh = height / 3f
        val kw = w / 10f
        p.textSize = 0.058f * w
        val offs = floatArrayOf(0f, 0.5f, 1.5f)
        for (r in 0..2) {
            for (i in rows[r].indices) {
                val cx = (offs[r] + i + 0.5f) * kw
                val cy = r * rh + rh / 2f
                c.drawText(rows[r][i].toString(), cx, cy - (p.ascent() + p.descent()) / 2f, p)
            }
        }
        if (trail.draw(c, SystemClock.uptimeMillis())) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                trail.down(e.x, e.y, e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) {
                    trail.move(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                }
                trail.move(e.x, e.y, e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> trail.up(e.eventTime)
        }
        invalidate()
        return true
    }
}
