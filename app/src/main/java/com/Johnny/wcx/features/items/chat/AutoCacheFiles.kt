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
import com.Johnny.wcx.features.api.core.models.MessageInfo
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

@Feature(name = "自动缓存文件", categories = ["聊天"], description = "监听接收到的文件消息, 在对方上传完成后自动触发微信内部下载将其缓存到本地")
object AutoCacheFiles : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "AutoCacheFiles"

    /** 补偿扫描回看窗口: 微信启动/开瞬间错过的消息也能补上。 */
    private const val SCAN_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** 补偿扫描单轮条数上限, 避免开瞬间发起过多下载任务。 */
    private const val SCAN_LIMIT = 300

    /**
     * 补偿扫描周期: insert/update 事件可能双双错过 (监听器注册晚于消息落库、
     * 功能开启时消息早已落库、微信内部走非标准 SQL 路径等), 必须持续扫描兜底,
     * 只扫一次会在"开启瞬间之后到达/错过的消息"上留下永久空洞。
     */
    private const val SCAN_INTERVAL_MS = 180_000L

    /** 已成功缓存的 msgSvrId, 不再处理。 */
    private val doneSvrIds = ConcurrentHashMap.newKeySet<Long>()

    /** 尚未成功、值得后续 update 事件/补偿轮重试的 msgSvrId → msgId。 */
    private val pendingSvrIds = ConcurrentHashMap<Long, Long>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 同时进行的下载任务上限, 防止消息密集时把微信 CDN 队列挤爆。 */
    private val downloadSemaphore = Semaphore(3)

    private var useWhitelist by WePrefs.prefOption("autocache_files_use_whitelist", false)
    private var whitelist by WePrefs.prefOption("autocache_files_whitelist", emptySet())
    private var blacklist by WePrefs.prefOption("autocache_files_blacklist", emptySet())

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

    // 文件消息刚到达时可能是"对方上传中"占位; 对方传完后微信会就地更新该行的 content,
    // 因此插入与更新都要监听。
    override fun onInsert(table: String, values: ContentValues) =
        maybeCacheFile(table, values, fromUpdate = false)

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) = maybeCacheFile(table, values, fromUpdate = true, whereClause = whereClause, whereArgs = whereArgs)

    private fun maybeCacheFile(
        table: String,
        values: ContentValues,
        fromUpdate: Boolean,
        whereClause: String? = null,
        whereArgs: Array<String>? = null
    ) {
        if (table != "message") return

        val type = values.getAsInteger("type")
        // update 事件的 ContentValues 只含被更新的列, type 通常不在其中 (该列未变),
        // 不能因此直接丢弃 —— 缺省时按文件消息继续尝试, 由后续 appmsg 解析/查库确认。
        if (type != null && type != MessageType.FILE.code) return

        // 自己发出的文件本身就在本地, 无需缓存
        if (values.getAsInteger("isSend") == 1) return

        val freshContent = values.getAsString("content")

        // update 事件廉价预筛: message 表的更新极其频繁 (status/flag/已读等),
        // 既不带新 content 又不带 msgSvrId 时, 仅当该 msgId 已登记 pending
        // (insert 时见过的文件消息) 才值得深究, 避免每条更新都去查库。
        if (fromUpdate && freshContent == null && values.getAsLong("msgSvrId") == null) {
            val knownMsgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs)
            if (knownMsgId == null || knownMsgId !in pendingSvrIds.values) {
                WeLogger.d(TAG, "update event without content/msgSvrId skipped (msgId=$knownMsgId)")
                return
            }
        }

        val msgSvrId = values.getAsLong("msgSvrId") ?: run {
            // update 事件常缺 msgSvrId, 经 whereClause 提取 msgId 后反查
            if (!fromUpdate) {
                WeLogger.w(TAG, "insert event without msgSvrId, skip")
                return
            }
            val msgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs) ?: run {
                WeLogger.d(TAG, "update event: cannot extract msgId, skip")
                return
            }
            val svrId = WeMessageApi.getMsgSvrIdByMsgId(msgId) ?: run {
                WeLogger.d(TAG, "update event: no msgSvrId for msgId=$msgId, skip")
                return
            }
            // 已知的 pending 消息: 用事件里的 msgId 刷新映射, 补偿轮按最新 msgId 重试
            if (pendingSvrIds.containsKey(svrId)) pendingSvrIds[svrId] = msgId
            svrId
        }
        if (msgSvrId == 0L) {
            WeLogger.d(TAG, "msgSvrId=0 event skipped")
            return
        }
        if (msgSvrId in doneSvrIds) return

        val talker = values.getAsString("talker")
            ?: WeMessageApi.getTalkerByMsgSvrId(msgSvrId)
        if (!passesListFilter(talker)) {
            WeLogger.d(TAG, "file msg $msgSvrId: talker=$talker filtered out (or unresolved with active filter), skip")
            return
        }

        if (freshContent != null) {
            val file = try {
                MessageInfo.FileMessage(freshContent)
            } catch (e: Exception) {
                WeLogger.e(TAG, "file msg $msgSvrId: failed to parse appmsg content, skip", e)
                return
            }
            when {
                // 对方仍在上传, 此刻下载必然失败, 记为 pending 等对方传完后的更新事件
                file.isSenderUploading -> {
                    WeLogger.i(TAG, "file msg $msgSvrId: sender still uploading (appmsgType=${file.appMsgType}), deferring")
                    pendingSvrIds[msgSvrId] = lookupMsgId(msgSvrId)
                    return
                }
                // 未知/暂不可下载的 appmsg 类型: 同样记 pending, 由补偿轮持续重试
                // (对方上传完成/类型就绪后 content 会更新, 补偿轮按 msgId 重新查库即拿到新值)
                !file.isDownloadable -> {
                    WeLogger.i(TAG, "file msg $msgSvrId: not downloadable yet (appmsgType=${file.appMsgType}), deferring")
                    pendingSvrIds[msgSvrId] = lookupMsgId(msgSvrId)
                    return
                }
            }
        } else if (fromUpdate) {
            // update 未携带新 content: 只跟进 insert 时已标记 pending 的消息
            // (廉价预筛已放行到这里), 避免对无关列的更新也触发查库/下载。
            if (!pendingSvrIds.containsKey(msgSvrId)) {
                WeLogger.d(TAG, "file msg $msgSvrId: update without content and not pending, skip")
                return
            }
        } else {
            // insert 却没有 content, 无法解析 appmsg; 记 pending 让补偿轮按 msgId 路径重试
            WeLogger.w(TAG, "file msg $msgSvrId: insert without content, defer to compensation")
            pendingSvrIds[msgSvrId] = lookupMsgId(msgSvrId)
            return
        }

        if (fromUpdate) {
            // update 事件统一经 msgId 路径处理: content 以事件新值为准, 稳定列查库补齐
            val msgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs)
                ?: pendingSvrIds[msgSvrId]
                ?: lookupMsgId(msgSvrId)
            if (msgId <= 0L) {
                WeLogger.w(TAG, "file msg $msgSvrId: cannot resolve msgId for update event, skip")
                return
            }
            pendingSvrIds[msgSvrId] = msgId
            WeLogger.i(TAG, "file msg $msgSvrId ready via update (msgId=$msgId), auto caching")
            launchCache(msgSvrId, "update") { WeMessageApi.cacheFileByMsgId(msgId, freshContent) }
        } else {
            // insert 事件的 values 即完整行, 直接转换, 无需再查库
            if (!pendingSvrIds.containsKey(msgSvrId)) pendingSvrIds[msgSvrId] = lookupMsgId(msgSvrId)
            WeLogger.i(TAG, "file msg $msgSvrId ready on insert, auto caching")
            launchCache(msgSvrId, "insert") {
                try {
                    val instance = WeMessageApi.convertMsgInfoInstanceFromContentValues(values)
                    WeMessageApi.cacheFile(instance)
                } catch (e: Exception) {
                    WeLogger.e(TAG, "file msg $msgSvrId: failed to build msgInfo from values", e)
                    null
                }
            }
        }
    }

    /** 统一发起缓存; 成功记入 done, 失败保留 pending 等待后续事件/补偿轮重试。 */
    private fun launchCache(msgSvrId: Long, via: String, block: suspend () -> String?) {
        scope.launch {
            downloadSemaphore.withPermit {
                val path = try {
                    block()
                } catch (e: Exception) {
                    WeLogger.e(TAG, "cache task crashed for msgSvrId=$msgSvrId (via $via)", e)
                    null
                }
                if (path != null) {
                    doneSvrIds.add(msgSvrId)
                    pendingSvrIds.remove(msgSvrId)
                    WeLogger.i(TAG, "cached file msgSvrId=$msgSvrId via $via -> $path")
                } else {
                    WeLogger.e(TAG, "failed to auto-cache file msgSvrId=$msgSvrId via $via, will retry on later events")
                }
            }
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

    private fun lookupMsgId(msgSvrId: Long): Long {
        return try {
            WeDatabaseApi.rawQuery("SELECT msgId FROM message WHERE msgSvrId=?", arrayOf<Any>(msgSvrId))
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        } catch (e: Exception) {
            WeLogger.e(TAG, "lookupMsgId failed for msgSvrId=$msgSvrId", e)
            0L
        }
    }

    /**
     * 补偿机制: insert/update 事件可能双双错过 (监听器注册晚于消息落库、功能开启时
     * 消息早已落库、微信内部走非标准 SQL 路径更新、触发时 CDN 尚未就绪等)。
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
        val rows = try {
            WeDatabaseApi.rawQuery(
                "SELECT msgId, msgSvrId, talker FROM message WHERE type=? AND isSend=0 AND createTime>? ORDER BY createTime DESC LIMIT ?",
                arrayOf<Any>(MessageType.FILE.code, since, SCAN_LIMIT)
            ).use { cursor ->
                buildList<Triple<Long, Long, String>> {
                    while (cursor.moveToNext()) {
                        add(
                            Triple(
                                cursor.getLong(cursor.getColumnIndexOrThrow("msgId")),
                                cursor.getLong(cursor.getColumnIndexOrThrow("msgSvrId")),
                                cursor.getString(cursor.getColumnIndexOrThrow("talker")) ?: ""
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "compensation scan query failed", e)
            return false
        }
        WeLogger.i(TAG, "compensation scan: ${rows.size} recent file message(s) since $since")
        var launched = 0
        rows.forEach { (msgId, svrId, talker) ->
            if (svrId == 0L) return@forEach
            if (svrId in doneSvrIds) return@forEach
            if (!passesListFilter(talker)) {
                WeLogger.d(TAG, "compensation scan: msgSvrId=$svrId talker=$talker filtered out")
                return@forEach
            }
            pendingSvrIds[svrId] = msgId
            launched++
            launchCache(svrId, "scan") { WeMessageApi.cacheFileByMsgId(msgId) }
        }
        WeLogger.i(TAG, "compensation scan done: launched $launched/${rows.size}")
        return true
    }

    private fun retryPending() {
        val snapshot = pendingSvrIds.toMap()
        if (snapshot.isEmpty()) return
        WeLogger.i(TAG, "compensation retry: ${snapshot.size} pending file message(s)")
        snapshot.forEach { (svrId, msgId) ->
            if (svrId in doneSvrIds) {
                pendingSvrIds.remove(svrId)
                return@forEach
            }
            if (msgId <= 0L) {
                // 没有 msgId 无法定位行, 放弃 (等下次 insert/update 事件补齐)
                pendingSvrIds.remove(svrId)
                return@forEach
            }
            launchCache(svrId, "retry") { WeMessageApi.cacheFileByMsgId(msgId) }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text("自动缓存文件") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelistState = !useWhitelistState },
                            trailingContent = { Switch(checked = useWhitelistState, onCheckedChange = null) },
                            supportingContent = { Text(if (useWhitelistState) "仅对选中联系人缓存文件" else "对选中联系人跳过缓存文件") },
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
