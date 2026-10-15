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
import java.util.concurrent.atomic.AtomicLong

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

    /** 单条消息最多尝试次数 (含补偿轮重试); 超过即结案, 避免永久刷屏。 */
    private const val MAX_ATTEMPTS = 6

    /** pending 条目的最长存活时间; 超过即结案 (对方上传中也可能永远不会传完)。 */
    private const val PENDING_TTL_MS = 30 * 60 * 1000L

    /** 已成功缓存的 msgSvrId, 不再处理。 */
    private val doneSvrIds = ConcurrentHashMap.newKeySet<Long>()

    /**
     * 判定为**无法缓存**而结案的 msgSvrId (不是文件消息 / 反复失败), 不再处理。
     *
     * 没有这个集合, 失败条目会被补偿扫描与 pending 重试无限拾起: 设备日志实测
     * 88 分钟内同 5 条消息被重试 17 轮, 每轮都在微信内部刷一条 NPE 堆栈
     * (`Attempt to read from field 'int pt0.r.i' on a null object reference`)。
     */
    private val settledSvrIds = ConcurrentHashMap.newKeySet<Long>()

    /** 尚未成功、值得后续 update 事件/补偿轮重试的消息: msgSvrId → 追踪记录。 */
    private val pendingSvrIds = ConcurrentHashMap<Long, Pending>()

    /** pending 条目: 记录最新 msgId 与尝试次数, 以便给出"放弃"的定论而不是无限重试。 */
    private class Pending(var msgId: Long) {
        val firstSeenAt = System.currentTimeMillis()

        @Volatile
        var attempts = 0
    }

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 同时进行的下载任务上限, 防止消息密集时把微信 CDN 队列挤爆。 */
    private val downloadSemaphore = Semaphore(3)

    /**
     * 诊断计数器: 被廉价预筛跳过的 update 事件数。
     * D 级日志在 release 构建会被 WeLogger 直接丢弃 (见 WeLogger.enqueue),
     * 高频分流改用计数, 每轮补偿扫描汇总输出, 保证"事件来过但被跳过"可见。
     */
    private val prefilteredUpdateEvents = AtomicLong(0)

    private var useWhitelist by WePrefs.prefOption("autocache_files_use_whitelist", false)
    private var whitelist by WePrefs.prefOption("autocache_files_whitelist", emptySet())
    private var blacklist by WePrefs.prefOption("autocache_files_blacklist", emptySet())

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
        WeLogger.i(
            TAG,
            "enabled: listening message insert/update; compensation scan every ${SCAN_INTERVAL_MS / 1000}s " +
                "(window ${SCAN_WINDOW_MS / 3600000}h, limit $SCAN_LIMIT per round); " +
                "list filter: ${describeListFilter()}"
        )
        startCompensation()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        doneSvrIds.clear()
        settledSvrIds.clear()
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
            if (knownMsgId == null || !isPendingMsgId(knownMsgId)) {
                prefilteredUpdateEvents.incrementAndGet()
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
                WeLogger.i(TAG, "update event: cannot extract msgId from whereClause, skip")
                return
            }
            val svrId = WeMessageApi.getMsgSvrIdByMsgId(msgId) ?: run {
                WeLogger.i(TAG, "update event: no msgSvrId for msgId=$msgId, skip")
                return
            }
            // 已知的 pending 消息: 用事件里的 msgId 刷新映射, 补偿轮按最新 msgId 重试
            pendingSvrIds[svrId]?.msgId = msgId
            svrId
        }
        if (msgSvrId == 0L) {
            WeLogger.w(TAG, "msgSvrId=0 event skipped (table=$table, fromUpdate=$fromUpdate)")
            return
        }
        if (isFinished(msgSvrId)) return

        val talker = values.getAsString("talker")
            ?: WeMessageApi.getTalkerByMsgSvrId(msgSvrId)
        if (!passesListFilter(talker)) {
            WeLogger.i(TAG, "file msg $msgSvrId: talker=$talker blocked by ${describeListFilter()}, skip")
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
                    trackPending(msgSvrId, lookupMsgId(msgSvrId))
                    return
                }
                // 不是文件消息: update 事件不带 type 列时会被放进来。
                // 确凿的非文件 (链接 5 / 转发 19 / 引用 57, 或压根没有 <appmsg> 的视频/语音/
                // 系统消息) **必须结案而不是记 pending** —— 微信的文件下载链路解析这些内容会
                // 返回 null 并就地解引用 (NPE), 记 pending 只会让补偿轮每 3 分钟重试一次、
                // 永远刷屏 (设备日志实测 88 分钟 17 轮 x 5 条)。
                !file.isFileAppMsg -> {
                    if (file.appMsgType == null && file.looksLikeAppMsg) {
                        // 有 <appmsg> 但 <type> 读不出来: 拿不准是不是文件, 交给重试上限兜底
                        WeLogger.i(TAG, "file msg $msgSvrId: appmsg 的 <type> 不可用, deferring")
                        trackPending(msgSvrId, lookupMsgId(msgSvrId))
                    } else {
                        WeLogger.i(
                            TAG,
                            "file msg $msgSvrId 不是文件消息 (appmsgType=${file.appMsgType}), 结案不再重试"
                        )
                        settle(msgSvrId, "content 不是文件类 appmsg (appmsgType=${file.appMsgType})")
                    }
                    return
                }
                // 是文件消息但尚未就绪: 记 pending, 由补偿轮持续重试
                // (对方上传完成/类型就绪后 content 会更新, 补偿轮按 msgId 重新查库即拿到新值)
                !file.isDownloadable -> {
                    WeLogger.i(TAG, "file msg $msgSvrId: not downloadable yet (appmsgType=${file.appMsgType}), deferring")
                    trackPending(msgSvrId, lookupMsgId(msgSvrId))
                    return
                }
            }
        } else if (fromUpdate) {
            // update 未携带新 content: 只跟进 insert 时已标记 pending 的消息
            // (廉价预筛已放行到这里), 避免对无关列的更新也触发查库/下载。
            if (!pendingSvrIds.containsKey(msgSvrId)) {
                WeLogger.i(TAG, "file msg $msgSvrId: update without content and not pending, skip")
                return
            }
        } else {
            // insert 却没有 content, 无法解析 appmsg; 记 pending 让补偿轮按 msgId 路径重试
            WeLogger.w(TAG, "file msg $msgSvrId: insert without content, defer to compensation")
            trackPending(msgSvrId, lookupMsgId(msgSvrId))
            return
        }

        if (fromUpdate) {
            // update 事件统一经 msgId 路径处理: content 以事件新值为准, 稳定列查库补齐
            val msgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs)
                ?: pendingSvrIds[msgSvrId]?.msgId
                ?: lookupMsgId(msgSvrId)
            if (msgId <= 0L) {
                WeLogger.w(TAG, "file msg $msgSvrId: cannot resolve msgId for update event, skip")
                return
            }
            trackPending(msgSvrId, msgId)
            WeLogger.i(TAG, "file msg $msgSvrId ready via update (msgId=$msgId), auto caching")
            launchCache(msgSvrId, "update") { WeMessageApi.cacheFileByMsgId(msgId, freshContent) }
        } else {
            // insert 事件的 values 即完整行, 直接转换, 无需再查库
            trackPending(msgSvrId, lookupMsgId(msgSvrId))
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

    /**
     * 统一发起缓存; 成功记入 done, 失败保留 pending 等后续事件/补偿轮重试 (次数与存活
     * 时间都有上限, 见 [giveUpIfExhausted], 不会无限重试)。
     */
    private fun launchCache(msgSvrId: Long, via: String, block: suspend () -> String?) {
        pendingSvrIds[msgSvrId]?.let { it.attempts += 1 }
        scope.launch {
            downloadSemaphore.withPermit {
                val path = try {
                    block()
                } catch (e: Exception) {
                    WeLogger.e(TAG, "cache task crashed for msgSvrId=$msgSvrId (via $via)", e)
                    null
                }
                if (path != null) {
                    markDone(msgSvrId)
                    WeLogger.i(TAG, "cached file msgSvrId=$msgSvrId via $via -> $path")
                } else {
                    val attempts = pendingSvrIds[msgSvrId]?.attempts ?: 0
                    WeLogger.e(
                        TAG,
                        "failed to auto-cache file msgSvrId=$msgSvrId via $via " +
                            "(第 $attempts 次), will retry on later events"
                    )
                    giveUpIfExhausted(msgSvrId)
                }
            }
        }
    }

    /** 名单过滤当前配置的文字描述, 用于日志判断消息是否被名单挡下。 */
    private fun describeListFilter(): String = when {
        useWhitelist && whitelist.isNotEmpty() -> "whitelist(${whitelist.size} contacts)"
        !useWhitelist && blacklist.isNotEmpty() -> "blacklist(${blacklist.size} contacts)"
        useWhitelist -> "whitelist(empty)"
        else -> "blacklist(empty)"
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

    /** 登记/刷新一条待重试消息; 已有记录时只更新 msgId (保留尝试次数与首次时间)。 */
    private fun trackPending(msgSvrId: Long, msgId: Long) {
        val existing = pendingSvrIds[msgSvrId]
        if (existing != null) {
            if (msgId > 0L) existing.msgId = msgId
        } else {
            pendingSvrIds[msgSvrId] = Pending(msgId)
        }
    }

    /** 该 msgId 是否属于某个待重试消息 (update 事件的廉价预筛用)。 */
    private fun isPendingMsgId(msgId: Long) = pendingSvrIds.values.any { it.msgId == msgId }

    /** 终态: 成功. */
    private fun markDone(msgSvrId: Long) {
        doneSvrIds.add(msgSvrId)
        pendingSvrIds.remove(msgSvrId)
    }

    /** 终态: 结案 (不再尝试), 并打出一条"为什么放弃"的定论。 */
    private fun settle(msgSvrId: Long, reason: String) {
        pendingSvrIds.remove(msgSvrId)
        if (settledSvrIds.add(msgSvrId)) WeLogger.w(TAG, "结案 msgSvrId=$msgSvrId: $reason")
    }

    /** 已到终态 (成功或结案), 任何路径都不应再处理。 */
    private fun isFinished(msgSvrId: Long) = msgSvrId in doneSvrIds || msgSvrId in settledSvrIds

    /**
     * @return true 表示已放弃 (尝试次数或存活时间超限), 调用方不应再重试。
     *
     * 补偿轮会一直重试 pending, 若某个失败原因无法自愈 (内容不是文件消息 / 对方永远
     * 不传完), 就会变成每轮刷一条错误的死循环 —— 这里给重试画上句号。
     */
    private fun giveUpIfExhausted(msgSvrId: Long): Boolean {
        val p = pendingSvrIds[msgSvrId] ?: return false
        val ageMs = System.currentTimeMillis() - p.firstSeenAt
        if (p.attempts >= MAX_ATTEMPTS || ageMs > PENDING_TTL_MS) {
            settle(
                msgSvrId,
                "尝试 ${p.attempts} 次 / 存活 ${ageMs / 1000}s 仍未成功 " +
                    "(不是可下载的文件消息, 或对方始终未传完)"
            )
            return true
        }
        return false
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
            if (svrId == 0L) {
                WeLogger.i(TAG, "compensation scan: skip msgId=$msgId (msgSvrId=0, 尚未同步的消息行)")
                return@forEach
            }
            if (isFinished(svrId)) {
                WeLogger.i(TAG, "compensation scan: skip msgSvrId=$svrId (已缓存或已结案)")
                return@forEach
            }
            if (!passesListFilter(talker)) {
                WeLogger.i(TAG, "compensation scan: skip msgSvrId=$svrId talker=$talker (blocked by ${describeListFilter()})")
                return@forEach
            }
            trackPending(svrId, msgId)
            launched++
            launchCache(svrId, "scan") { WeMessageApi.cacheFileByMsgId(msgId) }
        }
        WeLogger.i(
            TAG,
            "compensation scan done: launched $launched/${rows.size}, " +
                "pre-filtered update events since last scan: ${prefilteredUpdateEvents.getAndSet(0)}"
        )
        return true
    }

    private fun retryPending() {
        val snapshot = pendingSvrIds.toMap()
        if (snapshot.isEmpty()) return
        WeLogger.i(TAG, "compensation retry: ${snapshot.size} pending file message(s)")
        snapshot.forEach { (svrId, entry) ->
            if (isFinished(svrId)) {
                pendingSvrIds.remove(svrId)
                return@forEach
            }
            // 每轮重试前先判"是否该放弃": 重试次数与存活时间都有上限, 否则一个无法自愈的
            // 失败原因 (内容不是文件消息 / 对方永远不传完) 会变成永久刷屏。
            if (giveUpIfExhausted(svrId)) return@forEach
            if (entry.msgId <= 0L) {
                // 没有 msgId 无法定位行, 放弃 (等下次 insert/update 事件补齐)
                pendingSvrIds.remove(svrId)
                return@forEach
            }
            launchCache(svrId, "retry") { WeMessageApi.cacheFileByMsgId(entry.msgId) }
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
                        ListItem(
                            modifier = Modifier.clickable {
                                // 配错名单 (如误把常用联系人加进黑名单) 时可一键恢复,
                                // 不必进选择器逐个取消; 名单变更需重启微信生效。
                                val had = if (useWhitelistState) {
                                    val nonEmpty = whitelist.isNotEmpty()
                                    whitelist = emptySet()
                                    nonEmpty
                                } else {
                                    val nonEmpty = blacklist.isNotEmpty()
                                    blacklist = emptySet()
                                    nonEmpty
                                }
                                showToast(if (had) "已清空名单, 重启微信以使更改生效" else "名单本就是空的")
                            },
                            supportingContent = { Text("名单内的联系人不会被自动缓存, 清空后恢复全部缓存") },
                            headlineContent = { Text(if (useWhitelistState) "清空白名单" else "清空黑名单") }
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
