package com.Johnny.wcx.utils

import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主线程卡顿看门狗。
 *
 * 背景：模块在微信冷启动时做 dex 解析（创建 DexKitBridge、全量扫描）会占满 CPU/IO，
 * 主线程抢不到时间片 → 输入事件分发超过 5 秒 → 系统判定 ANR 并结束整个微信进程。
 * 用户感知就是「用着用着微信突然像被强行停止，然后模块重新加载」。
 *
 * 这里用 [Looper.setMessageLogging] 统计每条主线程消息的执行耗时，
 * 再用一个低优先级线程在主线程卡住超过 [BLOCK_WARN_MS] 时抓取主线程堆栈，
 * 直接定位「到底卡在谁身上」。
 *
 * 开销：每条消息 2 次字符串判断 + 一个 500ms 轮询的休眠线程，可忽略。
 */
object MainThreadWatchdog {

    private const val TAG = "MainThreadWatchdog"

    /** 超过该耗时判定为主线程卡顿。 */
    private const val BLOCK_WARN_MS = 2_000L

    /** 轮询间隔。 */
    private const val POLL_MS = 500L

    /** 两次记录之间的最小间隔，避免同一次卡顿刷屏。 */
    private const val REPORT_GAP_MS = 10_000L

    private const val PREFS = "wcx_main_thread_watchdog"
    private const val KEY_RECENT = "recent"
    private const val MAX_KEEP = 5

    @Volatile
    private var installed = false

    @Volatile
    private var inMessage = false

    @Volatile
    private var msgStart = 0L

    @Volatile
    private var msgDesc = ""

    @Volatile
    private var lastReportAt = 0L

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            val mainLooper = Looper.getMainLooper()
            val mainThread = mainLooper.thread

            mainLooper.setMessageLogging { line ->
                if (line.isNullOrEmpty()) return@setMessageLogging
                if (line.startsWith(">>>")) {
                    msgStart = SystemClock.uptimeMillis()
                    msgDesc = line.take(100)
                    inMessage = true
                } else if (line.startsWith("<<<")) {
                    inMessage = false
                }
            }

            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                while (true) {
                    runCatching { Thread.sleep(POLL_MS) }
                    if (!inMessage) continue
                    val cost = SystemClock.uptimeMillis() - msgStart
                    if (cost < BLOCK_WARN_MS) continue
                    val now = SystemClock.uptimeMillis()
                    if (now - lastReportAt < REPORT_GAP_MS) continue
                    lastReportAt = now
                    runCatching { report(mainThread, cost, msgDesc) }
                }
            }, "WCX-MainWatchdog").apply { isDaemon = true }.start()
        }.onFailure {
            WeLogger.w(TAG, "install failed: " + (it.message ?: it.javaClass.simpleName))
        }
    }

    private fun report(mainThread: Thread, cost: Long, desc: String) {
        val stack = mainThread.stackTrace
        val sb = StringBuilder()
        sb.append("主线程卡住 ").append(cost).append("ms，消息：").append(desc).append('\n')
        stack.take(25).forEach { sb.append("  at ").append(it).append('\n') }
        WeLogger.w(TAG, sb.toString())

        // 落到 SharedPreferences，供「模块自检」展示（进程被杀后仍能看到）。
        val ctx = HostInfo.application ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) +
                " 卡住 " + cost + "ms｜" + summarize(stack)
            val list = (prefs.getString(KEY_RECENT, "").orEmpty()
                .split('\n').filter { it.isNotBlank() } + line).takeLast(MAX_KEEP)
            prefs.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
        }
    }

    /** 从堆栈里挑最有信息量的几帧（跳过系统/反射框架层）。 */
    private fun summarize(stack: Array<StackTraceElement>): String {
        val picked = stack.asSequence()
            .filter { el ->
                val n = el.className
                !n.startsWith("android.os.") &&
                    !n.startsWith("com.android.internal.") &&
                    !n.startsWith("java.lang.reflect.") &&
                    !n.startsWith("android.app.") &&
                    !n.startsWith("com.android.") &&
                    !n.startsWith("kotlinx.coroutines.")
            }
            .take(3)
            .map { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
            .toList()
        return if (picked.isEmpty()) stack.firstOrNull()?.toString().orEmpty() else picked.joinToString(" ← ")
    }

    /** 供诊断页展示的最近卡顿记录（新的在前）。 */
    fun recent(context: Context): List<String> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }
            .reversed()
    }.getOrDefault(emptyList())
}
