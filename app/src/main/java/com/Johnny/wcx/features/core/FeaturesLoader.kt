package com.Johnny.wcx.features.core

import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.DexAdaptTask
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.ui.content.DexAdaptCompletion
import com.Johnny.wcx.ui.content.DexResolver
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.VersionCompat
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    /**
     * v293: 完成弹窗等待 LauncherUI 的上限。适配期间进程被顶到前台优先级
     * (见 [DexAdaptTask]), 但用户真的把微信切走了也只能等; 超时就退回 Toast。
     */
    private const val COMPLETION_DIALOG_WAIT_MS = 60_000L

    /** v293: 完成弹窗的监听协程 (同一时刻只保留一个)。 */
    private var completionWatcherJob: Job? = null

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        val allDexItems = allFeatures.filterIsInstance<IResolveDex>()

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems.filter { DexCacheManager.isItemCacheValid(it) }
        // 已定论：上次启动已经适配过、当前代码版本下结果不会再变（缓存仍不完整，
        // 说明该功能在当前微信版本上定位不到符号）。保持禁用，但不重复弹适配框。
        val settledItems = allDexItems - outdatedItems.toSet() - validItems.toSet()

        if (outdatedItems.isNotEmpty() || settledItems.isNotEmpty())
            WeLogger.i(
                TAG,
                "found ${validItems.size} valid items, ${outdatedItems.size} outdated items, " +
                        "${settledItems.size} settled (skipped) items"
            )

        // Load what we can from cache. Items with *some* missing keys are still partially loaded —
        // their valid delegates work immediately; only the item itself is queued for re-resolution.
        val cacheFailedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (outdatedItems + cacheFailedItems).distinct()

        if (allBrokenItems.isNotEmpty())
            handleBrokenItems(allBrokenItems)

        val skipItems = (allBrokenItems + settledItems).toSet()

        val elapsed = measureTime {
            allFeatures.forEach { feature ->
                val isBroken = feature is IResolveDex && skipItems.contains(feature)

                if (isBroken && feature !is WeSettingsInjector) {
                    WeLogger.w(TAG, "skipping ${feature.name} — incomplete cache, awaiting re-resolution")
                    return@forEach
                }

                feature.startup()
            }
        }
        WeLogger.i(TAG, "enabling all hook items took $elapsed")

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            showToast("WCX 加载成功!")
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐委托从缓存恢复状态。
     *
     * - 某个委托的 key 缺失 → 其他委托不受影响，仍正常加载。
     * - 有任意 key 缺失的 item 加入返回列表，等待 DexKit 重新扫描。
     * - 缓存文件整体读取失败 → 删除损坏文件，整个 item 加入返回列表。
     */
    private fun loadDescriptorsFromCache(items: List<IResolveDex>): List<IResolveDex> {
        val failedItems = mutableListOf<IResolveDex>()

        for (item in items) {
            val path = (item as BaseFeature).displayName
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
                    WeLogger.w(TAG, "cache missing for $path")
                    failedItems += item
                    continue
                }

                // loadFromCache 逐委托加载；返回未命中的 key 集合
                val missingKeys = item.loadFromCache(cache)
                if (missingKeys.isNotEmpty()) {
                    val total = item.dexDelegates.size
                    val loaded = total - missingKeys.size
                    WeLogger.w(TAG, "$path: loaded $loaded/$total delegates from cache, missing: $missingKeys")
                    failedItems += item
                    // 已命中的委托此时已经可用；hook 仍然跳过（见 loadFeatures），
                    // 等 DexKit 把缺失的部分补齐、cache 更新后下次启动即完整。
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache(path) }
                failedItems += item
            }
        }

        return failedItems
    }

    private fun handleBrokenItems(brokenItems: List<IResolveDex>) {
        if (Preferences.noDexResolve) return
        if (!TargetProcesses.isInMain) return

        // 版本兼容性检测：大量Hook失效时输出警告日志并弹窗提示用户
        try {
            val (compatible, message) = VersionCompat.checkCompatibility(
                brokenItems.size,
                FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<IResolveDex>().size
            )
            if (!compatible) {
                WeLogger.w(TAG, "版本兼容性警告: $message")
                // 弹窗提示用户当前微信版本部分功能暂未适配
                showToast("当前微信版本部分功能暂未适配，等待模块更新")
            } else {
                WeLogger.i(TAG, "版本兼容性: $message")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "版本兼容性检测异常", e)
        }

        // 1) 先把适配任务起在后台 —— 不依赖任何 Activity / 对话框。
        //    v284 起适配是「进程级后台任务 + 可选进度窗口」的结构：
        //    拿不到 LauncherUI、用户关掉进度框、微信切到后台，扫描都照常进行。
        DexAdaptTask.start(brokenItems)
        WeLogger.i(
            TAG,
            "launched background dex-adapt task for ${brokenItems.size} items " +
                    "(independent of any UI; closing the dialog / backgrounding WeChat keeps it running)"
        )

        // 2) 再尝试挂一个进度对话框 —— 它只是观察者，失败也不影响适配
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var activity = LauncherUI.getInstance()
            var waited = 0L
            while (activity == null && waited < 30_000L) {
                // v293: 适配已经跑完、完成弹窗也认领了的话, 就别再弹进度窗口
                // (它只会显示一个完成态, 和完成弹窗叠在一起)。
                if (DexAdaptTask.isCompletionPromptClaimed) {
                    WeLogger.i(TAG, "dex 适配已完成且完成弹窗已认领, 不再弹进度窗口")
                    return@launch
                }
                delay(1_000.milliseconds)
                waited += 1_000
                activity = LauncherUI.getInstance()
            }

            if (activity == null) {
                WeLogger.w(TAG, "no LauncherUI for dex progress dialog; adaptation already running in background, not cancelled")
                return@launch
            }

            val boundActivity = activity
            withContext(Dispatchers.Main) {
                if (DexAdaptTask.isCompletionPromptClaimed) return@withContext
                // v292: 允许直接关闭 —— 适配是后台任务, 关窗口不会中断它 (旧实现把两者绑死了)。
                showComposeDialog(boundActivity, directlyDismissable = true) {
                    DexResolver(
                        boundActivity,
                        brokenItems,
                        onDismiss
                    )
                }
            }
        }

        // 3) v293: 适配完成时, 若用户已关掉进度窗口, 补一个带「重启微信」按钮的完成弹窗。
        //    v292 把进度窗口改成可直接关闭后出现一个断档: 完成时只剩一条 Toast,
        //    用户看完文字找不到重启入口。这里把「完成提示」从 Toast 升级成弹窗:
        //     - 进度窗口还开着 → 它自己显示完成态 (自带按钮), 不弹 (claim 由窗口做);
        //     - 窗口被关掉 → 这里认领并弹 [DexAdaptCompletion];
        //     - 微信在后台拿不到 LauncherUI → 等最多 [COMPLETION_DIALOG_WAIT_MS],
        //       超时就退回 Toast, 不至于一声不响。
        completionWatcherJob?.cancel()
        completionWatcherJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            DexAdaptTask.state.collect { s ->
                if (!s.finished) return@collect
                if (DexAdaptTask.progressWindowVisible.get()) return@collect
                if (!DexAdaptTask.claimCompletionPrompt()) return@collect

                val success = s.items.count { it.ok }
                val failed = s.items.filter { !it.ok }
                WeLogger.i(TAG, "dex 适配完成 (成功 $success/${s.total}), 准备完成弹窗")

                var activity = LauncherUI.getInstance()
                var waited = 0L
                while (activity == null && waited < COMPLETION_DIALOG_WAIT_MS) {
                    delay(1_000.milliseconds)
                    waited += 1_000
                    activity = LauncherUI.getInstance()
                }

                if (activity == null) {
                    // 微信还在后台: 没法弹窗, 至少给条 Toast (切回微信也不会再补,
                    // 但下次启动缓存已更新, 不会再弹适配框)。
                    WeLogger.w(TAG, "拿不到 LauncherUI, 完成提示退化为 Toast")
                    withContext(Dispatchers.Main) {
                        showToast(
                            if (failed.isEmpty()) {
                                "DEX 适配完成 ($success 项), 重启微信后生效"
                            } else {
                                "DEX 适配完成, ${failed.size} 项失败 (不影响其他功能)"
                            }
                        )
                    }
                    return@collect
                }

                val boundActivity = activity
                withContext(Dispatchers.Main) {
                    showComposeDialog(boundActivity, directlyDismissable = true) {
                        DexAdaptCompletion(
                            boundActivity,
                            s.total,
                            success,
                            failed,
                            onDismiss
                        )
                    }
                }
            }
        }
    }
}
