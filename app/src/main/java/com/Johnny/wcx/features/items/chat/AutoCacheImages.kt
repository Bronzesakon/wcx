package com.Johnny.wcx.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

@Feature(name = "自动缓存图片", categories = ["聊天"], description = "监听接收到的图片消息, 自动触发微信从 CDN 下载, 将原图缓存到本地")
object AutoCacheImages : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "AutoCacheImages"

    /** 单条消息最多尝试次数 (立即 + 两次延迟重试)。 */
    private const val MAX_ATTEMPTS = 3

    /** 补偿扫描回看窗口: 微信启动/开瞬间错过的消息也能补上。 */
    private const val SCAN_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** 补偿扫描单轮条数上限, 避免开瞬间发起过多下载任务。 */
    private const val SCAN_LIMIT = 300

    /**
     * 补偿扫描周期: insert/update 事件可能双双错过 (监听器注册晚于消息落库、
     * 功能开启时消息早已落库、ImgInfo2 行写入过晚等), 必须持续扫描兜底,
     * 只扫一次会在"开启瞬间之后到达/错过的消息"上留下永久空洞。
     */
    private const val SCAN_INTERVAL_MS = 180_000L

    /** 已成功缓存的 msgSvrId, 不再处理。 */
    private val doneSvrIds = ConcurrentHashMap.newKeySet<Long>()

    /** 尚未成功、值得后续 update 事件/补偿轮重试的 msgSvrId。 */
    private val pendingSvrIds = ConcurrentHashMap.newKeySet<Long>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 同时进行的下载任务上限, 防止消息密集时把微信 CDN 队列挤爆。 */
    private val downloadSemaphore = Semaphore(3)

    private var useWhitelist by WePrefs.prefOption("autocache_images_use_whitelist", false)
    private var whitelist by WePrefs.prefOption("autocache_images_whitelist", emptySet())
    private var blacklist by WePrefs.prefOption("autocache_images_blacklist", emptySet())

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
        WeLogger.i(
            TAG,
            "enabled: listening message insert/update; compensation scan every ${SCAN_INTERVAL_MS / 1000}s " +
                "(window ${SCAN_WINDOW_MS / 3600000}h, limit $SCAN_LIMIT per round)"
        )
        startCompensation()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        doneSvrIds.clear()
        pendingSvrIds.clear()
        WeLogger.i(TAG, "disabled: listener removed, compensation stopped")
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type")
        if (type == null) {
            WeLogger.d(TAG, "insert event without type column, skip")
            return
        }
        if (type != MessageType.IMAGE.code) return

        // 自己发出的图片本身就在本地, 无需缓存
        if (values.getAsInteger("isSend") == 1) return

        val talker = values.getAsString("talker")
        if (!passesListFilter(talker)) {
            WeLogger.d(TAG, "image insert: talker=$talker filtered out (or unresolved with active filter), skip")
            return
        }

        val msgSvrId = values.getAsLong("msgSvrId") ?: run {
            WeLogger.w(TAG, "image insert without msgSvrId, skip")
            return
        }
        if (msgSvrId == 0L) {
            WeLogger.d(TAG, "image insert with msgSvrId=0, skip")
            return
        }
        if (msgSvrId in doneSvrIds) return

        // insert 时刻 ImgInfo2 行可能尚未写入, 记入 pending 并带重试地触发缓存
        WeLogger.i(TAG, "detected image message; msgSvrId=$msgSvrId, auto caching")
        pendingSvrIds.add(msgSvrId)
        cacheWithRetry(msgSvrId, "insert")
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "message") return

        val type = values.getAsInteger("type")
        // update 事件的 ContentValues 只含被更新的列, type 通常不在其中 (该列未变),
        // 缺省时只跟进 insert 时已见过的图片消息, 避免对无关列的更新做无用功。
        if (type != null && type != MessageType.IMAGE.code) return

        val msgSvrId = values.getAsLong("msgSvrId") ?: run {
            val msgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs) ?: run {
                WeLogger.d(TAG, "update event: cannot extract msgId, skip")
                return
            }
            WeMessageApi.getMsgSvrIdByMsgId(msgId) ?: run {
                WeLogger.d(TAG, "update event: no msgSvrId for msgId=$msgId, skip")
                return
            }
        }
        if (msgSvrId == 0L) {
            WeLogger.d(TAG, "update event with msgSvrId=0, skip")
            return
        }
        if (msgSvrId in doneSvrIds) return

        // type 缺失且非已知 pending: 与自动缓存无关的 message 更新, 跳过
        if (type == null && msgSvrId !in pendingSvrIds) {
            WeLogger.d(TAG, "update event for unrelated msgSvrId=$msgSvrId, skip")
            return
        }

        val talker = values.getAsString("talker")
            ?: WeMessageApi.getTalkerByMsgSvrId(msgSvrId)
        if (!passesListFilter(talker)) {
            WeLogger.d(TAG, "image msg $msgSvrId: talker=$talker filtered out (or unresolved with active filter), skip")
            return
        }

        WeLogger.i(TAG, "image msg $msgSvrId update event, retry caching")
        cacheWithRetry(msgSvrId, "update")
    }

    /**
     * 带重试的缓存: insert 时刻 ImgInfo2 行可能尚未写入 (微信先落 message 行, 图片服务后写),
     * 且 CDN 未必就绪, 单次 [WeMessageApi.cacheImage] 失败很常见。
     * 立即重试一次 + 两次延迟重试覆盖"行未写/未就绪", 仍失败则留在 pending 等 update 事件/补偿轮。
     */
    private fun cacheWithRetry(msgSvrId: Long, via: String) {
        scope.launch {
            downloadSemaphore.withPermit {
                for (attempt in 1..MAX_ATTEMPTS) {
                    val path = try {
                        WeMessageApi.cacheImage(msgSvrId)
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "cacheImage crashed for msgSvrId=$msgSvrId (attempt $attempt, via $via)", e)
                        null
                    }
                    if (path != null) {
                        doneSvrIds.add(msgSvrId)
                        pendingSvrIds.remove(msgSvrId)
                        WeLogger.i(TAG, "cached image msgSvrId=$msgSvrId via $via -> $path")
                        return@withPermit
                    }
                    if (attempt < MAX_ATTEMPTS) {
                        WeLogger.w(TAG, "cacheImage attempt $attempt failed for msgSvrId=$msgSvrId (via $via), retrying")
                        delay(if (attempt == 1) 3_000 else 8_000)
                    }
                }
                WeLogger.e(TAG, "failed to auto-cache image msgSvrId=$msgSvrId via $via after $MAX_ATTEMPTS attempts, will retry on later events")
            }
        }
    }

    /**
     * 补偿机制: insert/update 事件可能双双错过 (监听器注册晚于消息落库、功能开启时
     * 消息早已落库、ImgInfo2 行写入过晚、触发时 CDN 尚未就绪等)。
     * 开启后先延迟扫描一轮, 之后每 [SCAN_INTERVAL_MS] 持续扫描 + 重试 pending,
     * 保证任何时刻错过的消息都能在下一轮被补上。
     */
    private fun startCompensation() {
        scope.launch {
            delay(30_000)
            var dbFailStreak = 0
            while (isActive) {
                val scanned = scanRecentMessages()
                retryPending()
                // db 未就绪 (重置 dex 缓存后的重适配窗口内 getStorage hook 不触发) 时
                // 逐步加快重试, 最短 ~15s 一轮; 就绪后回到正常周期
                dbFailStreak = if (scanned) 0 else dbFailStreak + 1
                val interval = if (dbFailStreak > 0) {
                    minOf(15_000L * dbFailStreak, SCAN_INTERVAL_MS)
                } else {
                    SCAN_INTERVAL_MS
                }
                delay(interval)
            }
        }
    }

    /** @return false 表示本轮因 db 未就绪/查询失败没有扫描, 调用方应加快重试。 */
    private suspend fun scanRecentMessages(): Boolean {
        if (!WeDatabaseApi.isReady) {
            // hook 安装时微信可能早已调用过 getStorage(), hookAfter 永不触发;
            // 按需主动初始化, 仍失败则本轮跳过 (重适配窗口), 稍后重试。
            WeDatabaseApi.ensureDatabase()
            if (!WeDatabaseApi.isReady) {
                WeLogger.w(TAG, "compensation scan skipped: database not ready yet, will retry sooner")
                return false
            }
        }
        val since = System.currentTimeMillis() - SCAN_WINDOW_MS
        val svrIds = try {
            WeDatabaseApi.rawQuery(
                "SELECT msgSvrId FROM message WHERE type=? AND isSend=0 AND createTime>? ORDER BY createTime DESC LIMIT ?",
                arrayOf<Any>(MessageType.IMAGE.code, since, SCAN_LIMIT)
            ).use { cursor ->
                buildList<Long> {
                    while (cursor.moveToNext()) {
                        add(cursor.getLong(cursor.getColumnIndexOrThrow("msgSvrId")))
                    }
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "compensation scan query failed", e)
            return false
        }
        WeLogger.i(TAG, "compensation scan: ${svrIds.size} recent image message(s) since $since")
        var launched = 0
        svrIds.forEach { svrId ->
            if (svrId == 0L) return@forEach
            if (svrId in doneSvrIds) return@forEach
            pendingSvrIds.add(svrId)
            launched++
            cacheWithRetry(svrId, "scan")
        }
        WeLogger.i(TAG, "compensation scan done: launched $launched/${svrIds.size}")
        return true
    }

    private fun retryPending() {
        val snapshot = pendingSvrIds.toList()
        if (snapshot.isEmpty()) return
        WeLogger.i(TAG, "compensation retry: ${snapshot.size} pending image message(s)")
        snapshot.forEach { svrId ->
            if (svrId in doneSvrIds) {
                pendingSvrIds.remove(svrId)
                return@forEach
            }
            cacheWithRetry(svrId, "retry")
        }
    }

    /**
     * 名单过滤; [talker] 为 null (事件未携带且反查失败, 如 db 未就绪) 时:
     * 未配置名单直接放行 —— talker 本就只用于名单过滤, 不该因拿不到它就把消息丢掉;
     * 已配置名单则保守跳过, 由补偿轮在 db 就绪后重新扫到。
     */
    private fun passesListFilter(talker: String?): Boolean {
        val filterActive = if (useWhitelist) whitelist.isNotEmpty() else blacklist.isNotEmpty()
        if (!filterActive) return true
        if (talker == null) return false
        return if (useWhitelist) talker in whitelist else talker !in blacklist
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text("自动缓存图片") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelistState = !useWhitelistState },
                            trailingContent = { Switch(checked = useWhitelistState, onCheckedChange = null) },
                            supportingContent = { Text(if (useWhitelistState) "仅对选中联系人缓存图片" else "对选中联系人跳过缓存图片") },
                            headlineContent = { Text(if (useWhitelistState) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单") }
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val currentList = if (useWhitelistState) whitelist else blacklist

                                showComposeDialog(context, fullScreen = true) {
                                    ContactsSelector(
                                        title = if (useWhitelistState) "选择白名单" else "选择黑名单",
                                        contacts = contacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) { selectedIds ->
                                        if (useWhitelistState) {
                                            whitelist = selectedIds
                                        } else {
                                            blacklist = selectedIds
                                        }
                                        showToast("已保存 ${selectedIds.size} 个联系人, 重启微信以使更改生效")
                                        onDismiss()
                                    }
                                }
                            },
                            supportingContent = { Text("点击选择联系人") },
                            headlineContent = { Text(if (useWhitelistState) "配置白名单" else "配置黑名单") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        useWhitelist = useWhitelistState
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
