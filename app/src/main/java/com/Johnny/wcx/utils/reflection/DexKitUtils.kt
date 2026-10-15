package com.Johnny.wcx.utils.reflection

import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

inline val MethodData.asMethod get() = getMethodInstance(ClassLoaders.HOST)

inline val ClassData.asClass get() = getInstance(ClassLoaders.HOST)

inline val MethodData.asConstructor get() = getConstructorInstance(ClassLoaders.HOST)

private object DexKitHolder {

    /**
     * 空闲多久回收 DexKit。
     *
     * 原值 30s 太短：一次 DexKit 全量查找动辄超过 30 秒，这段时间里不会再有人调用
     * [get]，定时器就会把**正在被其它线程使用**的 bridge `close()` 掉，
     * native 层 use-after-free 直接崩微信（表现为"适配到一半微信闪退"）。
     * 放宽到 120s，并且只要有 [retain] 过的长期持有者就永不回收。
     */
    private val IDLE_TIMEOUT = 120.seconds

    // SupervisorJob so a cancelled timer job never takes down the whole scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var bridge: DexKitBridge? = null
    private var idleJob: Job? = null

    /** 被长期持有（脚本子系统）时不做空闲回收 */
    private val pinned = AtomicBoolean(false)

    fun get(): DexKitBridge = runBlocking { obtain() }

    /**
     * 长期持有 DexKit：置位后 bridge 不再因空闲被回收（幂等）。
     * 供脚本子系统等需要常驻使用 DexKit 的模块调用。
     */
    fun retain(): DexKitBridge = runBlocking {
        pinned.set(true)
        obtain()
    }

    private suspend fun obtain(): DexKitBridge = mutex.withLock {
        val existing = bridge
        val instance = existing ?: DexKitBridge.create(HostInfo.appInfo.sourceDir).also {
            WeLogger.d("DexKitHolder", "created DexKit bridge")
            bridge = it
        }

        // 有新的使用需求，重置空闲回收
        idleJob?.cancel()
        idleJob = null
        if (!pinned.get()) {
            idleJob = scope.launch {
                delay(IDLE_TIMEOUT)
                mutex.withLock {
                    if (!pinned.get()) {
                        WeLogger.d("DexKitHolder", "idle timeout reached, closing DexKit")
                        bridge?.close()
                        bridge = null
                        idleJob = null
                    }
                }
            }
        }
        instance
    }
}

val DexKit: DexKitBridge get() = DexKitHolder.get()

/**
 * 取得一个长期存活的 DexKit 实例。
 *
 * 脚本子系统原先自行 `DexKitBridge.create(...)` 创建了第二个实例并永久持有，与主适配
 * 用的这一个同时存在。微信 8.x 的 dex 很大，两份索引会把内存顶到 OOM（微信闪退），
 * 也让 native 层承受双份并发查找。现在统一复用同一个实例，并以 pin 的方式保证它
 * 不会被空闲回收（脚本运行时随时需要它）。
 */
fun retainDexKit(): DexKitBridge = DexKitHolder.retain()
