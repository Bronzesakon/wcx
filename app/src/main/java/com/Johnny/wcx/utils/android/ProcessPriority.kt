package com.Johnny.wcx.utils.android

import android.os.IBinder
import android.os.Process
import com.Johnny.wcx.utils.WeLogger

/**
 * 把当前进程临时提升到「前台」优先级 —— 用于让长时间后台任务不被系统冻结。
 *
 * ### 为什么需要
 *
 * Android 12+ 的 cached-app freezer 会把**整个进程**冻住（cgroup freezer，
 * 协程 / 线程全部停摆），而微信一旦被切到后台就是 cached 进程。
 * `PARTIAL_WAKE_LOCK` 挡不住它：wakelock 只保证 CPU 不睡，进程照样被 STOP。
 * 用户看到的现象就是「DEX 适配窗口一失去焦点，进度就停住；切回微信才继续」。
 *
 * 系统只认两种豁免：前台服务（需要宿主 manifest 里有对应 Service，在 hook 场景下
 * 做不到 —— 我们的类不在微信的 ClassLoader 里），以及 AMS 的
 * `setProcessForeground`（`Service.startForeground()` 内部就是调它）。
 * 这里走后一条：反射 `IActivityManager.setProcessForeground(token, pid, true)`，
 * 把微信进程的 adj 顶到前台，freezer 就不会碰它。
 *
 * ### 风险与兜底
 *
 * - 全链路 `runCatching`，反射不可用只是打一条 W 日志并**保持原有行为**（不恶化）。
 * - 调用方**必须**在任务结束时 [release]，否则进程会一直被当前台（耗电）。
 * - 我们是寄生在微信进程里的，提升的是微信自己的进程优先级，只在适配期间生效。
 */
object ProcessPriority {

    private const val TAG = "ProcessPriority"

    @Volatile
    private var raised = false

    /** 反射是否已确认不可用（避免每次都抛异常）。 */
    @Volatile
    private var unavailable = false

    /** 不可用的原因，只打一次日志。 */
    @Volatile
    private var unavailableReason: String? = null

    /** 当前是否处于提升状态。 */
    val isRaised: Boolean get() = raised

    /** 把当前进程标记为前台。@return 是否真的生效（false = 反射不可用，行为不变）。 */
    @Synchronized
    fun raise(): Boolean {
        if (unavailable) {
            unavailableReason?.let { WeLogger.w(TAG, "进程提优先不可用: $it (切后台仍可能被冻结)") }
            return false
        }
        val ok = setProcessForeground(true)
        if (ok) {
            raised = true
            WeLogger.i(
                TAG,
                "已把进程提到前台优先级 (pid=${Process.myPid()}), 切后台不会被冻结; " +
                    "任务结束会还原"
            )
        } else {
            unavailable = true
        }
        return ok
    }

    /** 还原为普通优先级。重复调用无害。 */
    @Synchronized
    fun release() {
        if (!raised) return
        raised = false
        if (setProcessForeground(false)) {
            WeLogger.i(TAG, "已还原进程优先级 (pid=${Process.myPid()})")
        }
    }

    private fun setProcessForeground(foreground: Boolean): Boolean {
        return try {
            // IActivityManager: 隐藏 API, LSPosed 环境下宿主进程已做 hidden-api 豁免
            val am = Class.forName("android.app.ActivityManager")
                .getDeclaredMethod("getService")
                .apply { isAccessible = true }
                .invoke(null) ?: return fail("拿不到 ActivityManager.getService()")

            val threadCls = Class.forName("android.app.ActivityThread")
            val thread = threadCls.getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null) ?: return fail("拿不到 ActivityThread")

            val token = threadCls.getDeclaredMethod("getApplicationThread")
                .apply { isAccessible = true }
                .invoke(thread) as? IBinder ?: return fail("拿不到 ApplicationThread token")

            // 不同版本签名略有出入, 逐个候选试
            val method = am.javaClass.methods.firstOrNull {
                it.name == "setProcessForeground" && it.parameterTypes.size >= 3
            } ?: return fail("IActivityManager 上没有 setProcessForeground")

            method.isAccessible = true
            val args = when (method.parameterTypes.size) {
                3 -> arrayOf(token, Process.myPid(), foreground)
                else -> arrayOf(token, Process.myPid(), foreground, 0)
            }
            method.invoke(am, *args)
            true
        } catch (t: Throwable) {
            fail("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun fail(reason: String): Boolean {
        if (unavailableReason == null) {
            unavailableReason = reason
            WeLogger.w(
                TAG,
                "无法提升进程优先级 ($reason)。这是 Android 12+ 的系统限制, " +
                    "适配在微信切到后台后仍可能被冻结, 回到前台会接着跑"
            )
        }
        return false
    }
}
