package com.Johnny.wcx.utils.reflection

import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import kotlin.time.Duration.Companion.seconds

inline val MethodData.asMethod get() = getMethodInstance(ClassLoaders.HOST)

inline val ClassData.asClass get() = getInstance(ClassLoaders.HOST)

inline val MethodData.asConstructor get() = getConstructorInstance(ClassLoaders.HOST)

private object DexKitHolder {

    private const val TAG = "DexKitHolder"

    /**
     * 空闲多久回收 DexKit。
     *
     * 长扫描通过 [acquire] 持有租约，或由 [retain] 永久 pin 住，因此计时器不会在查询
     * 进行中关闭 bridge。保留 120 秒空闲窗口，避免一次性查询结束后立刻重建 native 索引。
     */
    private val IDLE_TIMEOUT = 120.seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var bridge: DexKitBridge? = null
    private var activeLeaseCount = 0
    private var pinned = false
    private var idleCloseJob: Job? = null
    private var idleCloseGeneration = 0L

    private fun bridgeLocked(): DexKitBridge {
        val current = bridge
        return if (current?.isValid == true) {
            current
        } else {
            DexKitBridge.create(HostInfo.appInfo.sourceDir).also {
                WeLogger.d(TAG, "created DexKit bridge")
                bridge = it
            }
        }
    }

    /** Direct bridge access without lease accounting; cancels any pending idle close. */
    fun peek(): DexKitBridge = synchronized(lock) {
        cancelIdleCloseLocked()
        bridgeLocked()
    }

    fun acquire(): DexKitBridge = synchronized(lock) {
        cancelIdleCloseLocked()
        val acquired = bridgeLocked()
        activeLeaseCount++
        acquired
    }

    /** Pin the shared bridge for process-long users such as the background adapter. */
    fun retain(): DexKitBridge = synchronized(lock) {
        pinned = true
        cancelIdleCloseLocked()
        bridgeLocked()
    }

    fun release() = synchronized(lock) {
        check(activeLeaseCount > 0) { "DexKit lease released without a matching acquire" }

        activeLeaseCount--
        if (activeLeaseCount == 0 && !pinned) scheduleIdleCloseLocked()
    }

    private fun cancelIdleCloseLocked() {
        idleCloseGeneration++
        idleCloseJob?.cancel()
        idleCloseJob = null
    }

    private fun scheduleIdleCloseLocked() {
        check(idleCloseJob == null)
        check(!pinned && activeLeaseCount == 0)
        val generation = ++idleCloseGeneration

        idleCloseJob = scope.launch {
            delay(IDLE_TIMEOUT)
            synchronized(lock) {
                if (generation != idleCloseGeneration || activeLeaseCount != 0 || pinned) {
                    return@synchronized
                }

                val idleBridge = bridge
                bridge = null
                idleCloseJob = null

                if (idleBridge != null) {
                    WeLogger.d(TAG, "idle timeout reached, closing DexKit")
                    idleBridge.close()
                }
            }
        }
    }
}

/**
 * Keeps the shared bridge valid for the entire [block]. DexKit result objects must be consumed
 * inside the block because some of them retain the bridge for follow-up queries.
 */
fun <T> withDexKit(block: (DexKitBridge) -> T): T {
    val dexKit = DexKitHolder.acquire()
    return try {
        block(dexKit)
    } finally {
        DexKitHolder.release()
    }
}

/** Suspending counterpart of [withDexKit]. The lease remains active across suspension points. */
suspend fun <T> withDexKitSuspending(block: suspend (DexKitBridge) -> T): T {
    val dexKit = DexKitHolder.acquire()
    return try {
        block(dexKit)
    } finally {
        DexKitHolder.release()
    }
}

/**
 * Direct bridge access without lease accounting. Used by one-shot scan paths that pass the
 * bridge around; callers that need bounded lifetime should use [withDexKit] instead.
 */
val DexKit: DexKitBridge get() = DexKitHolder.peek()

/**
 * 取得一个长期存活的 DexKit 实例。
 *
 * 后台适配与脚本子系统复用同一份 native 索引；pin 住后不会因空闲回收被关闭。
 */
fun retainDexKit(): DexKitBridge = DexKitHolder.retain()
