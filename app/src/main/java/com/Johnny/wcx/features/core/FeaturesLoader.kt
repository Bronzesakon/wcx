package com.Johnny.wcx.features.core

import com.tencent.mm.ui.LauncherUI
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.DexAdaptTask
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.api.ui.WeSettingsInjector
import com.Johnny.wcx.ui.content.DexResolver
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.VersionCompat
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

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
                showComposeDialog(boundActivity, directlyDismissable = false) {
                    DexResolver(
                        boundActivity,
                        brokenItems,
                        onDismiss
                    )
                }
            }
        }
    }
}
