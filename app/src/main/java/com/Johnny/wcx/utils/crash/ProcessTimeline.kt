package com.Johnny.wcx.utils.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import com.Johnny.wcx.utils.WeLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 微信主进程的启动时间线。
 *
 * 用途：判断「后台被杀」到底是偶发还是周期性。
 * 每次模块加载时追加一条记录（时间 / pid / 进程名 / 内存），保留最近 [MAX_RECORDS] 条。
 *
 * 典型判读：
 * - 一天内启动次数远大于用户手动打开的次数的 ⇒ 进程在后台被反复杀掉（或被系统反复拉起）；
 * - 启动记录密集（间隔十几分钟）⇒ 存在崩溃循环或被反复回收；
 * - 内存（PSS）持续偏高 ⇒ 更容易被系统按内存不足回收。
 */
object ProcessTimeline {

    private const val TAG = "ProcessTimeline"

    private const val PREFS = "wcx_proc_timeline"
    private const val KEY_LINES = "lines"
    private const val KEY_DAY = "day"
    private const val KEY_DAY_COUNT = "day_count"

    private const val MAX_RECORDS = 60

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 模块加载时调用：记录本次启动。 */
    fun recordStartup(context: Context) {
        runCatching {
            // 只统计主进程：小程序（:appbrand0）等子进程会被系统频繁起停，
            // 全部计入会让「今日启动次数」严重虚高、完全失去参考价值。
            val processName = context.applicationInfo?.processName ?: context.packageName
            if (processName != context.packageName) return

            val now = System.currentTimeMillis()
            val pid = Process.myPid()
            val pssMb = readPssMb(context)
            val line = fmt.format(Date(now)) + "｜pid=" + pid +
                "｜" + processName +
                "｜" + if (pssMb > 0) pssMb.toString() + "MB" else "?"

            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY_LINES, "").orEmpty()
            val lines = (old.split('\n').filter { it.isNotBlank() } + line)
                .takeLast(MAX_RECORDS)

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val lastDay = prefs.getString(KEY_DAY, "").orEmpty()
            val lastCount = prefs.getInt(KEY_DAY_COUNT, 0)
            val count = if (lastDay == today) lastCount + 1 else 1

            prefs.edit()
                .putString(KEY_LINES, lines.joinToString("\n"))
                .putString(KEY_DAY, today)
                .putInt(KEY_DAY_COUNT, count)
                .apply()
        }.onFailure { WeLogger.w(TAG, "record startup failed: " + (it.message ?: it.javaClass.simpleName)) }
    }

    /** 今日（按自然日）记录到的进程启动次数。 */
    fun todayStartCount(context: Context): Int = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (prefs.getString(KEY_DAY, "") != today) 0 else prefs.getInt(KEY_DAY_COUNT, 0)
    }.getOrDefault(0)

    /** 最近 n 条启动记录（新的在前）。 */
    fun recent(context: Context, n: Int = 5): List<String> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LINES, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }
            .takeLast(n)
            .reversed()
    }.getOrDefault(emptyList())

    /** 当前进程内存（PSS，MB）；失败返回 0。 */
    fun currentPssMb(context: Context): Int = readPssMb(context)

    private fun readPssMb(context: Context): Int {
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return 0
            val info: Debug.MemoryInfo = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull() ?: return 0
            info.totalPss / 1024
        }.getOrDefault(0)
    }
}
