package ime.swipe

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试用诊断信息。崩溃记录写入本地文件，其他状态只保存在内存里；
 * 主界面和输入法在同一个进程，所以主界面能直接读到输入法最近的状态。
 */
object Diag {
    private const val FILE = "diag"
    private var installed = false

    @Volatile var lexStatus = "还没开始加载"
    @Volatile var editor = "还没有输入框"
    @Volatile var typed = "还没有输入拼音"
    @Volatile var glide = "还没有滑行"

    private fun now(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    @Synchronized
    fun init(ctx: Context, countStart: Boolean) {
        val app = ctx.applicationContext
        if (countStart) {
            try {
                val sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                sp.edit().putInt("starts", sp.getInt("starts", 0) + 1).putString("last_start", now()).apply()
            } catch (_: Throwable) {
            }
        }
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                    .putString("last_crash", now() + "  线程 " + thread.name + "\n" + writer.toString().take(2500))
                    .commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(ctx: Context): String {
        val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return "输入法服务启动次数: " + sp.getInt("starts", 0) + "，最近一次 " + (sp.getString("last_start", null) ?: "无") + "\n" +
            "词库: " + lexStatus + "\n" +
            "最近的输入框: " + editor + "\n" +
            "最近的拼音输入: " + typed + "\n" +
            "最近的滑行: " + glide + "\n" +
            "最近一次崩溃: " + (sp.getString("last_crash", null) ?: "无")
    }
}
