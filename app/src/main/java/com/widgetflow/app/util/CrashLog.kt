package com.widgetflow.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.widgetflow.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃日志：
 *  - 记录未捕获异常到 filesDir/logs/crash.txt（附带设备信息与最近业务日志）
 *  - 业务日志 write() 追加到 filesDir/logs/app.log（滚动保留最近 300 行）
 *  - 同时输出到 Logcat，便于 adb logcat 排查
 */
object CrashLog {

    private const val TAG = "WidgetFlow"
    private const val MAX_APP_LOG_LINES = 300

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            write(app, "== App 启动 ==", "SDK ${Build.VERSION.SDK_INT}, ${Build.MODEL}, ${Build.VERSION.RELEASE}")
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    writeCrash(app, thread, throwable)
                } catch (_: Throwable) {
                }
                prev?.uncaughtException(thread, throwable)
            }
        }
    }

    /** 记录一条业务日志（时间 + 标签 + 消息） */
    fun write(context: Context, tag: String, msg: String) {
        Log.d(TAG, "[$tag] $msg")
        try {
            val logFile = logFile(context)
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$stamp [$tag] $msg\n"
            val existing = if (logFile.exists()) logFile.readText() else ""
            val lines = (existing + line).lines().takeLast(MAX_APP_LOG_LINES)
            logFile.writeText(lines.joinToString("\n"))
        } catch (_: Throwable) {
        }
    }

    fun e(context: Context, tag: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        write(context, tag, "异常: ${t.javaClass.simpleName}: ${t.message}\n$sw")
    }

    private fun writeCrash(context: Context, thread: Thread, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val info = StringBuilder()
        info.append("===== 崩溃时间: ").append(stamp).append(" =====\n")
        info.append("线程: ").append(thread.name).append("\n")
        info.append("设备: ").append(Build.MANUFACTURER).append(" ")
            .append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" / SDK ").append(Build.VERSION.SDK_INT).append("\n")
        info.append("应用: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        info.append("异常: ").append(t.toString()).append("\n")
        info.append(sw).append("\n")
        // 附带最近业务日志
        info.append("----- 最近日志 -----\n")
        try {
            val logFile = logFile(context)
            if (logFile.exists()) info.append(logFile.readText().lines().takeLast(80).joinToString("\n"))
        } catch (_: Throwable) {
        }
        val crashFile = File(context.filesDir, "logs/crash.txt")
        crashFile.parentFile?.mkdirs()
        crashFile.appendText(info.toString() + "\n\n")
        Log.e(TAG, "崩溃已记录到 ${crashFile.absolutePath}\n${info}")
    }

    /** 读取合并后的日志文本（业务日志 + 崩溃记录），供界面展示/分享 */
    fun readLog(context: Context): String {
        val sb = StringBuilder()
        try {
            val logFile = logFile(context)
            if (logFile.exists()) sb.append(logFile.readText())
        } catch (_: Throwable) {
        }
        try {
            val crashFile = File(context.filesDir, "logs/crash.txt")
            if (crashFile.exists()) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(crashFile.readText())
            }
        } catch (_: Throwable) {
        }
        return sb.toString()
    }

    fun logDir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    private fun logFile(context: Context): File {
        val dir = logDir(context)
        return File(dir, "app.log")
    }
}
