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
        startCompensation()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        doneSvrIds.clear()
        pendingSvrIds.clear()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (type != MessageType.IMAGE.code) return

        // 自己发出的图片本身就在本地, 无需缓存
        if (values.getAsInteger("isSend") == 1) return

        val talker = values.getAsString("talker") ?: return
        if (!passesListFilter(talker)) return

        val msgSvrId = values.getAsLong("msgSvrId") ?: return
        if (msgSvrId == 0L || msgSvrId in doneSvrIds) return

        // insert 时刻 ImgInfo2 行可能尚未写入, 记入 pending 并带重试地触发缓存
        WeLogger.i(TAG, "detected image message; msgSvrId=$msgSvrId, auto caching")
        pendingSvrIds.add(msgSvrId)
        cacheWithRetry(msgSvrId)
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
            val msgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs) ?: return
            WeMessageApi.getMsgSvrIdByMsgId(msgId) ?: return
        }
        if (msgSvrId == 0L || msgSvrId in doneSvrIds) return

        // type 缺失且非已知 pending: 与自动缓存无关的 message 更新, 跳过
        if (type == null && msgSvrId !in pendingSvrIds) return

        val talker = values.getAsString("talker")
            ?: WeMessageApi.getTalkerByMsgSvrId(msgSvrId)
            ?: return
        if (!passesListFilter(talker)) return

        WeLogger.i(TAG, "image msg $msgSvrId update event, retry caching")
        cacheWithRetry(msgSvrId)
    }

    /**
     * 带重试的缓存: insert 时刻 ImgInfo2 行可能尚未写入 (微信先落 message 行, 图片服务后写),
     * 且 CDN 未必就绪, 单次 [WeMessageApi.cacheImage] 失败很常见。
     * 立即重试一次 + 两次延迟重试覆盖"行未写/未就绪", 仍失败则留在 pending 等 update 事件/补偿轮。
     */
    private fun cacheWithRetry(msgSvrId: Long) {
        scope.launch {
            downloadSemaphore.withPermit {
                for (attempt in 1..MAX_ATTEMPTS) {
                    val path = try {
                        WeMessageApi.cacheImage(msgSvrId)
                    } catch (e: Exception) {
                        WeLogger.e(TAG, "cacheImage crashed for msgSvrId=$msgSvrId (attempt $attempt)", e)
                        null
                    }
                    if (path != null) {
                        doneSvrIds.add(msgSvrId)
                        pendingSvrIds.remove(msgSvrId)
                        WeLogger.i(TAG, "cached image to $path")
                        return@withPermit
                    }
                    if (attempt < MAX_ATTEMPTS) {
                        WeLogger.w(TAG, "cacheImage attempt $attempt failed for msgSvrId=$msgSvrId, retrying")
                        delay(if (attempt == 1) 3_000 else 8_000)
                    }
                }
                WeLogger.e(TAG, "failed to auto-cache image msgSvrId=$msgSvrId after $MAX_ATTEMPTS attempts, will retry on later events")
            }
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
            WeLogger.e(TAG, "scanRecentMessages query failed", e)
            return
        }
        WeLogger.i(TAG, "compensation scan: ${svrIds.size} recent image message(s) since $since")
        svrIds.forEach { svrId ->
            if (svrId == 0L || svrId in doneSvrIds) return@forEach
            pendingSvrIds.add(svrId)
            cacheWithRetry(svrId)
        }
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
            cacheWithRetry(svrId)
        }
    }

    private fun passesListFilter(talker: String): Boolean {
        return if (useWhitelist) {
            talker in whitelist
        } else {
            talker !in blacklist
        }
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
