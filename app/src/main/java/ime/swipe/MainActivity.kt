package ime.swipe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val store = Store(this)
        val params = TrailParams.load(store)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(pad, (28 * d).toInt(), pad, pad)

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val b1 = Button(this)
        b1.text = "启用输入法"
        b1.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        val b2 = Button(this)
        b2.text = "切换输入法"
        b2.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        btns.addView(b1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(b2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)

        val et = EditText(this)
        et.setTextColor(Color.WHITE)
        et.setHintTextColor(Color.GRAY)
        et.hint = "在这里试输入法"
        root.addView(et)

        val tip = TextView(this)
        tip.text = "轨迹预览 在黑色区域划动 滑块自动保存 输入法每次划动时读取"
        tip.setTextColor(Color.rgb(150, 150, 150))
        tip.textSize = 13f
        tip.setPadding(0, (8 * d).toInt(), 0, (6 * d).toInt())
        root.addView(tip)

        val preview = TrailPreviewView(this, params)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (171 * d).toInt()))

        val sv = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        sv.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun fmt(i: Int, v: Float): String {
            return if (TrailParams.STEP[i] >= 1f) v.roundToInt().toString() else ((v * 10f).roundToInt() / 10f).toString()
        }

        val labels = ArrayList<TextView>()
        val bars = ArrayList<SeekBar>()
        for (i in params.v.indices) {
            val mn = TrailParams.MIN[i]
            val st = TrailParams.STEP[i]
            val tv = TextView(this)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            tv.setPadding(0, (10 * d).toInt(), 0, 0)
            tv.text = TrailParams.NAMES[i] + "  " + fmt(i, params.v[i])
            val sb = SeekBar(this)
            sb.max = ((TrailParams.MAX[i] - mn) / st).roundToInt()
            sb.progress = ((params.v[i] - mn) / st).roundToInt()
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = mn + p * st
                    params.v[i] = v
                    tv.text = TrailParams.NAMES[i] + "  " + fmt(i, v)
                    if (fromUser) params.save(store)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}

                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            labels.add(tv)
            bars.add(sb)
            box.addView(tv)
            box.addView(sb)
        }

        val reset = Button(this)
        reset.text = "恢复默认"
        reset.setOnClickListener {
            for (i in params.v.indices) {
                params.v[i] = TrailParams.DEF[i]
                bars[i].progress = ((TrailParams.DEF[i] - TrailParams.MIN[i]) / TrailParams.STEP[i]).roundToInt()
                labels[i].text = TrailParams.NAMES[i] + "  " + fmt(i, TrailParams.DEF[i])
            }
            params.save(store)
        }
        box.addView(reset)

        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
