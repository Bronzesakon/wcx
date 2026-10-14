package com.Johnny.wcx.dexkit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.dexkit.resolution.resolveAllDex
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.ProcessPriority
import com.Johnny.wcx.utils.reflection.retainDexKit
import com.Johnny.wcx.utils.unreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DEX 适配任务 — **与 UI 完全解耦**的后台扫描器。
 *
 * 改造动机（v284）：旧实现把扫描协程直接挂在适配对话框的 Compose 作用域上，
 * 用户关掉对话框 / 切到后台 / Activity 被回收时，进度条不再刷新甚至任务被一并
 * 取消，表现为「只有微信主界面开着进度条的时候才走适配」。
 *
 * 现在扫描任务的持有者是本对象（进程级 `SupervisorJob`，与任何 Activity 无关）：
 *  - 对话框只是**观察者**，`state` 有任何更新它自己重组；关掉对话框不影响扫描；
 *  - [FeaturesLoader][com.Johnny.wcx.features.core.FeaturesLoader] 拿到待适配清单就
 *    直接起任务，**不再等 LauncherUI**（拿不到界面也照样适配）;
 *  - 每项扫完立刻落盘（[DexCacheManager.saveItemCache]），所以任务被微信进程重启
 *    打断后，下次启动只会把没做完的项重新算（`getOutdatedItems` 天然断点续跑）;
 *  - 扫描期间持一把 `PARTIAL_WAKE_LOCK`，避免息屏后 CPU 进入 suspend 让 native
 *    查询长时间不推进（这是「后台不跑」的另一半原因，与进程冻结无关）。
 *
 * v291 补上最后一块：上面说的「微信切后台 → 适配停住」其实**可以**绕过 ——
 * Android 12+ 的 cached-app freezer 只冻结 cached 进程，而进程是否 cached 由 adj
 * 决定。适配期间调用 [ProcessPriority]（反射 `IActivityManager.setProcessForeground`，
 * 即 `Service.startForeground()` 内部那条）把微信进程顶到前台优先级，freezer 就不会碰它，
 * 切后台也能一路跑完。反射不可用时退化回 v284 的行为：进程解冻后从当前项继续。
 */
object DexAdaptTask {

    private const val TAG = "DexAdaptTask"

    /**
     * 单项适配的超时上限。DexKit 的全量查找是 native 调用，遇到不匹配的微信版本
     * 可能长时间不返回（线程挂死、不抛异常）。没有超时整个适配会永远停在某一项。
     */
    private const val ITEM_TIMEOUT_MS = 120_000L

    /** 并发度：每项都会在 native 层做全量 dex 查找，过高会顶爆内存（微信闪退）。 */
    private const val SCAN_CONCURRENCY = 3

    /** WakeLock 最长持有时间（兜底，防止异常路径泄漏）。 */
    private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 单条适配结果。 */
    data class AdaptItemState(
        val name: String,
        val ok: Boolean,
        val message: String? = null,
        val elapsedMs: Long = 0,
    )

    /** 对 UI 暴露的完整状态快照。 */
    data class AdaptState(
        val running: Boolean = false,
        val finished: Boolean = false,
        val total: Int = 0,
        val completed: Int = 0,
        val current: String = "",
        val items: List<AdaptItemState> = emptyList(),
    ) {
        val failed: List<AdaptItemState> get() = items.filter { !it.ok }
    }

    private val _state = MutableStateFlow(AdaptState())
    val state: StateFlow<AdaptState> = _state.asStateFlow()

    /** 本次进程内已经跑过的项（成功或失败都算），避免重复启动时又扫一遍。 */
    private val doneItems: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 是否已有适配任务在跑（用于避免重复启动）。 */
    val isRunning: Boolean get() = _state.value.running

    /**
     * 启动一次后台适配。**幂等**：已有任务在跑、或这批项本次进程内都跑过时直接返回。
     * 本方法立即返回，扫描在进程级协程里继续。
     */
    fun start(items: List<IResolveDex>) {
        if (items.isEmpty()) return

        if (_state.value.running) {
            val remaining = (_state.value.total - _state.value.completed).coerceAtLeast(0)
            WeLogger.i(TAG, "适配任务已在运行, 忽略重复启动 (剩余 $remaining 项)")
            return
        }

        val pending = items.filter { item ->
            val displayName = (item as? BaseFeature)?.displayName
            displayName == null || displayName !in doneItems
        }
        if (pending.isEmpty()) {
            WeLogger.i(TAG, "这批 ${items.size} 项本次启动均已适配过, 跳过重复扫描")
            return
        }

        _state.value = AdaptState(running = true, total = pending.size)
        WeLogger.i(
            TAG,
            "启动后台 DEX 适配: ${pending.size} 项, 并发 $SCAN_CONCURRENCY, " +
                    "单项超时 ${ITEM_TIMEOUT_MS / 1000}s (与界面无关, 关掉进度框/切后台都会继续)"
        )

        scope.launch {
            runAdaptation(pending)
        }
    }

    // ---------------------------------------------------------------------------

    private suspend fun runAdaptation(items: List<IResolveDex>) {
        val startedAt = SystemClock.elapsedRealtime()
        val wakeLock = acquireWakeLock()

        // v291: WakeLock 挡不住 Android 12+ 的 cached-app freezer (微信一被切到后台,
        // 整个进程被冻住, 表现为"进度停在某一项, 切回微信才继续")。把进程顶到前台
        // 优先级才是系统认的豁免; 反射不可用时只是回到 wakelock 的老行为。
        val foregrounded = ProcessPriority.raise()
        try {
            // 长期持有同一份 DexKit：pin 住后不会被 120s 空闲回收打断长扫描
            val dexKit = retainDexKit()

            val channel = Channel<AdaptItemState>(Channel.UNLIMITED)
            val reporter = scope.launch {
                for (item in channel) reportItem(item)
            }

            val semaphore = Semaphore(SCAN_CONCURRENCY)
            coroutineScope {
                items.map { item ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit { scanItem(item, dexKit, channel) }
                    }
                }.forEach { it.await() }
            }

            channel.close()
            reporter.join()

            val snapshot = _state.value
            val failed = snapshot.failed.size
            val cost = SystemClock.elapsedRealtime() - startedAt
            _state.value = snapshot.copy(
                running = false,
                finished = true,
                current = if (failed == 0) "适配完成" else "适配完成, $failed 项失败",
            )
            WeLogger.i(
                TAG,
                "后台适配结束: 成功 ${snapshot.total - failed}/${snapshot.total}, " +
                        "失败 $failed, 总耗时 ${cost / 1000}s"
            )

            notifyFinished(snapshot.total - failed, failed)
        } catch (t: Throwable) {
            WeLogger.e(TAG, "后台适配异常终止", t as? Exception ?: Exception(t))
            _state.value = _state.value.copy(
                running = false,
                finished = true,
                current = "适配异常: ${t.message}",
            )
        } finally {
            if (foregrounded) ProcessPriority.release()
            releaseWakeLock(wakeLock)
        }
    }

    private suspend fun scanItem(
        item: IResolveDex,
        dexKit: org.luckypray.dexkit.DexKitBridge,
        channel: Channel<AdaptItemState>,
    ) {
        val displayName = if (item is BaseFeature) item.displayName else unreachable()
        val itemStarted = SystemClock.elapsedRealtime()
        val thread = Thread.currentThread().name
        WeLogger.i(TAG, "开始适配 [$displayName] (线程 $thread)")

        val result = try {
            val ok = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
                item.resolveAllDex(dexKit)
                DexCacheManager.saveItemCache(item)

                // 保存后复检：若校验仍不通过（部分委托没解析出结果），如实上报失败，
                // 而不是显示「成功」后下次启动又判为需要更新（死循环弹窗）
                if (!DexCacheManager.isItemCacheValid(item)) {
                    val unresolved = item.dexDelegates
                        .filter { it.getDescriptorString().isNullOrEmpty() }
                        .joinToString(", ") { it.key }
                    throw IllegalStateException("适配结果不完整, 未解析: ${unresolved.ifEmpty { "(未知)" }}")
                }
                true
            }
            if (ok != true) {
                throw IllegalStateException("适配超时 (${ITEM_TIMEOUT_MS / 1000}s), 已跳过该项")
            }
            AdaptItemState(displayName, ok = true, elapsedMs = SystemClock.elapsedRealtime() - itemStarted)
        } catch (t: Throwable) {
            val e = t as? Exception ?: Exception(t)
            WeLogger.e(TAG, "适配失败: $displayName", e)
            AdaptItemState(
                displayName,
                ok = false,
                message = e.message,
                elapsedMs = SystemClock.elapsedRealtime() - itemStarted,
            )
        }

        channel.send(result)
    }

    private fun reportItem(item: AdaptItemState) {
        doneItems += item.name
        val current = _state.value
        val items = current.items + item
        _state.value = current.copy(
            completed = items.size,
            current = if (item.ok) "已完成: ${item.name}" else "失败: ${item.name}",
            items = items,
        )
        WeLogger.i(
            TAG,
            "适配${if (item.ok) "成功" else "失败"} [${item.name}] " +
                    "耗时 ${item.elapsedMs}ms, 进度 ${items.size}/${current.total}" +
                    (item.message?.let { ", 原因: $it" } ?: "")
        )
    }

    /** 适配结束时给个回执：微信在前台时是一条 Toast，退到后台就只落日志。 */
    private fun notifyFinished(success: Int, failed: Int) {
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    val text = if (failed == 0) {
                        "DEX 适配完成 ($success 项), 重启微信后生效"
                    } else {
                        "DEX 适配完成, $failed 项失败 (不影响其他功能)"
                    }
                    com.Johnny.wcx.utils.android.showToast(HostInfo.application, text)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------

    private fun acquireWakeLock(): PowerManager.WakeLock? = runCatching {
        val context = HostInfo.application
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching null
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wcx:dex-adapt").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }.onFailure {
        WeLogger.w(TAG, "获取 WakeLock 失败 (继续适配): ${it.message}")
    }.getOrNull()

    private fun releaseWakeLock(lock: PowerManager.WakeLock?) {
        runCatching {
            if (lock != null && lock.isHeld) lock.release()
        }.onFailure {
            WeLogger.w(TAG, "释放 WakeLock 失败: ${it.message}")
        }
    }
}
