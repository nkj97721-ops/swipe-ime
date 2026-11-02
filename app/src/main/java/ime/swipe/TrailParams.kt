package ime.swipe

/** 滑行轨迹参数 颜色固定白色 */
class TrailParams(val v: FloatArray = DEF.copyOf()) {
    val head: Float get() = v[0]          // 头部线宽 dp
    val opacity: Float get() = v[1] / 100f // 不透明度
    val tail: Float get() = v[2] / 100f   // 尾部线宽比例
    val life: Float get() = v[3]          // 存活时长 ms
    val fade: Float get() = v[4]          // 渐隐曲线
    val taper: Float get() = v[5]         // 变细曲线
    val glow: Float get() = v[6]          // 发光 dp
    val smooth: Float get() = v[7] / 100f // 跟手平滑
    val lift: Float get() = v[8]          // 抬手后渐隐倍速

    fun save(s: Store) {
        for (i in v.indices) s.putFloat("tp" + i, v[i])
    }

    companion object {
        val NAMES = arrayOf("头部线宽 dp", "不透明度 %", "尾部线宽 %", "存活时长 ms", "渐隐曲线", "变细曲线", "发光 dp", "跟手平滑 %", "抬手后渐隐倍速")
        val MIN = floatArrayOf(1f, 0f, 0f, 100f, 0.3f, 0.3f, 0f, 0f, 1f)
        val MAX = floatArrayOf(30f, 100f, 100f, 2000f, 8f, 8f, 20f, 98f, 10f)
        val STEP = floatArrayOf(0.5f, 1f, 1f, 10f, 0.1f, 0.1f, 1f, 1f, 0.1f)
        val DEF = floatArrayOf(16f, 30f, 0f, 150f, 0.3f, 2f, 0f, 0f, 8f)

        fun load(s: Store): TrailParams {
            val p = TrailParams()
            for (i in p.v.indices) p.v[i] = s.getFloat("tp" + i, DEF[i])
            return p
        }
    }
}
