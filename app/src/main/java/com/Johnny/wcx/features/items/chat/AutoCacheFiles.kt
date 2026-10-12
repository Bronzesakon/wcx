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
        startCompensation()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        doneSvrIds.clear()
        pendingSvrIds.clear()
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

        val msgSvrId = values.getAsLong("msgSvrId") ?: run {
            // update 事件常缺 msgSvrId, 经 whereClause 提取 msgId 后反查
            if (!fromUpdate) return
            val msgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs) ?: return
            val svrId = WeMessageApi.getMsgSvrIdByMsgId(msgId) ?: return
            // 已知的 pending 消息: 用事件里的 msgId 刷新映射, 补偿轮按最新 msgId 重试
            if (pendingSvrIds.containsKey(svrId)) pendingSvrIds[svrId] = msgId
            svrId
        }
        if (msgSvrId == 0L || msgSvrId in doneSvrIds) return

        val talker = values.getAsString("talker")
            ?: WeMessageApi.getTalkerByMsgSvrId(msgSvrId)
            ?: return
        if (!passesListFilter(talker)) return

        val freshContent = values.getAsString("content")
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
                // 未知的 appmsg 类型, 保守跳过
                !file.isDownloadable -> {
                    WeLogger.d(TAG, "file msg $msgSvrId: not downloadable yet (appmsgType=${file.appMsgType}), skip")
                    return
                }
            }
        } else if (fromUpdate) {
            // update 未携带新 content: 只跟进 insert 时已标记 pending 的消息,
            // 避免对 message 表无关列的更新也触发查库/下载。
            if (!pendingSvrIds.containsKey(msgSvrId)) return
        } else {
            // insert 却没有 content, 无法解析 appmsg
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
            launchCache(msgSvrId) { WeMessageApi.cacheFileByMsgId(msgId, freshContent) }
        } else {
            // insert 事件的 values 即完整行, 直接转换, 无需再查库
            if (!pendingSvrIds.containsKey(msgSvrId)) pendingSvrIds[msgSvrId] = lookupMsgId(msgSvrId)
            WeLogger.i(TAG, "file msg $msgSvrId ready on insert, auto caching")
            launchCache(msgSvrId) {
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
    private fun launchCache(msgSvrId: Long, block: suspend () -> String?) {
        scope.launch {
            downloadSemaphore.withPermit {
                val path = try {
                    block()
                } catch (e: Exception) {
                    WeLogger.e(TAG, "cache task crashed for msgSvrId=$msgSvrId", e)
                    null
                }
                if (path != null) {
                    doneSvrIds.add(msgSvrId)
                    pendingSvrIds.remove(msgSvrId)
                    WeLogger.i(TAG, "cached file to $path")
                } else {
                    WeLogger.e(TAG, "failed to auto-cache file msgSvrId=$msgSvrId, will retry on later events")
                }
            }
        }
    }

    private fun passesListFilter(talker: String): Boolean {
        return if (useWhitelist) {
            talker in whitelist
        } else {
            talker !in blacklist
        }
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
     * 补偿机制: insert/update 事件可能双双错过 (监听器注册晚于消息落库、微信内部走非标准
     * SQL 路径更新、触发时 CDN 尚未就绪等)。开启后先延迟扫描近期消息, 之后定期重试 pending。
     */
    private fun startCompensation() {
        scope.launch {
            delay(30_000)
            scanRecentMessages()
            while (isActive) {
                delay(180_000)
                retryPending()
            }
        }
    }

    private suspend fun scanRecentMessages() {
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
            WeLogger.e(TAG, "scanRecentMessages query failed", e)
            return
        }
        WeLogger.i(TAG, "compensation scan: ${rows.size} recent file message(s) since $since")
        rows.forEach { (msgId, svrId, talker) ->
            if (svrId == 0L || svrId in doneSvrIds) return@forEach
            if (!passesListFilter(talker)) return@forEach
            pendingSvrIds[svrId] = msgId
            launchCache(svrId) { WeMessageApi.cacheFileByMsgId(msgId) }
        }
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
            launchCache(svrId) { WeMessageApi.cacheFileByMsgId(msgId) }
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
