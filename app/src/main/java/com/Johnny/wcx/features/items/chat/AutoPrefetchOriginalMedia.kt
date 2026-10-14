package com.Johnny.wcx.features.items.chat

import android.content.ContentValues
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * **不打开**图片 / 视频, 也把收到的原图与原画质视频拉下来。
 *
 * 与「自动查看原图/原视频」的分工:
 *  - 「自动查看原图/原视频」是**你点开时才**动作 —— 在图片/视频页出现的一瞬间把
 *    「查看原图 / 查看原视频」按钮压掉并替你点掉, 视觉上就是"打开即原画质";
 *  - 本功能是**后台提前拉** —— 消息一落库就触发 CDN 下载, 等你真点开时原画质已到本地。
 *
 * ## 图片
 *
 * 复用已在「自动缓存图片」里验证过的通道: [WeMessageApi.cacheImage] →
 * `ImageService.downloadImage(...)`, 该通道**优先下载原图** (`ImgInfo2.hdImgId > 0` 时
 * 取原图那一行, 见 `WeMessageApi.ensureImageCachedFile`)。
 *
 * ## 视频 (原画质)
 *
 * 微信的「查看原视频」是 **UI 层自己造的 CDN 任务**, 没有模型层入口, 因此这里按
 * 8.0.76 安装包反查结果**复刻微信自己那条链路** (classes3/4/12.dex)。精简后的原始逻辑
 * (ImageGalleryUI 里 "save origin video" 那个方法) 是:
 *
 * ```java
 * if (!gallery.o1.N(msg)) skip;                 // 静态: 是不是可下载原视频的消息
 * v2 = gallery.n1.t(msg)                        // 静态: 现场解析视频消息 -> v21.v2 (并写缓存)
 * j3 = v21.j3.h.d(v2.h())                       // VideoParseInfo.parse(消息内容 XML) -> 原视频记录
 * if (j3 == null || j3.d.isEmpty()) skip;        // 没有原视频信息就不做
 * c  = new rl4.c(System.currentTimeMillis());
 * g  = new gallery.g(msg, v2, c, j3);           // 下载回调 (实现 il4.q)
 * f  = new gallery.f(msg, v2.e(), true);        // 下载任务 (实现 rl4.a), v2.e() 是 CDN 地址
 * l  = new il4.l(g);                            // CDN 会话
 * l.a(f);
 * l.m = <gallery.n1 实例>;                       // 进度回调 (il4.u), 仅 UI 场景需要
 * l.p();                                        // 执行
 * ```
 *
 * 复刻时**保留微信自己的前置条件** (`j3 != null && j3.d` 非空 = 这条消息确实带原视频),
 * 避免对普通视频乱发下载任务; `l.m` 在后台没有 `n1` 实例, 用一个动态代理占位,
 * 只为避免进度回调时空指针。
 *
 * ### v287: v285/v286 为什么在设备上"不点开视频就不下载"
 *
 * 反汇编 8.0.76 (classes12.dex) 后确认两处硬伤, 都是**静默失败**——
 * 失败时日志只打"该视频没有原视频信息", 看起来像"这条视频本来就没有原片":
 *
 *  1. **注册表找错类**: 原来读 `v21.i3.h` 这个静态字段, 但 `v21.i3` 就是
 *     `MicroMsg.VideoParseInfo` 本身 (只有 a/b/c/d/e 五个解析方法, **没有任何字段**)。
 *     真正的单例是 **`v21.j3.h`(static final, 类型 v21.i3)**, 两个解析缓存
 *     `v21/j3;->i` / `->j` 也在 `v21.j3` 上。所以旧代码 `getDeclaredField("h")`
 *     直接抛 NoSuchFieldException → 整个配方解析失败 → 记一次"链路不可用"后
 *     **当成已处理**, 再也不重试。
 *  2. **视频信息走了只读缓存**: 原来用 `gallery.n1.s(msg)`, 它内部只是
 *     `v21.d3.h(msg.y0())` 查 gallery 的缓存 —— 用户没打开过视频页时缓存是空的,
 *     返回 null。微信自己能用是因为页面加载时先调了 **`gallery.n1.t(msg)`**
 *     (日志 tag `MicroMsg.Imagegallery.handler.video` 的 "insert video info:"):
 *     它现场 `h35/d.fromXml(消息内容)` 解析出 `v21.v2` (url/长度/md5), 写
 *     `com.tencent.mm.storage.f9.Ta(msgId, msg, true)` 落库, 再塞进缓存。
 *     **后台提前拉原视频必须走 `t`**, 这才是"自动查看原视频"的等价动作。
 *
 * 另外 `v21.i3.d(key)` 的 key 是**消息内容 XML** (`v2.h()` = `e9.j()`,
 * 内部 `aa.d(xml, "msg", null)` 解析, 取 `.msg.videomsg.$.cdnrawvideourl` 等),
 * 不是 fileid; 记录里 `d` = cdnrawvideourl、`f` = cdnrawvideoaeskey、
 * `b` = isplaceholder、`g` = rawlength。
 *
 * 这些类名是**混淆名** (il4 / v21 / rl4 这类短包名), 微信改版后会整体失效 —— 所以
 * 每一环都有独立日志说明卡在哪里; 并且装了**探针**: 微信自己走一次原视频下载
 * (打开视频页时「自动查看原图/原视频」会替你点掉按钮) 就会把真实参数打进日志,
 * 下次据此校准。
 *
 * ## 触发与补偿
 *
 * 消息落库的 insert / update 事件驱动, 外加每 5 分钟一轮、回看 6 小时的补偿扫描
 * (与「自动缓存图片」同款设计: 监听器注册晚于消息落库、开启功能时消息早已落库、
 * 图片 / 视频信息行写入过晚都会漏事件, 必须靠扫描兜底)。
 *
 * ### v288: 默认跟随「自动查看原图/原视频」的开关
 *
 * 设备日志 (2026-10-14) 里本功能**一行日志都没有** —— 因为它的开关一直没人拨过
 * ([SwitchFeature.startup] 读 `WePrefs.getBoolOrDef(name, defaultEnabled)`,
 * 而 `defaultEnabled` 原本是 false), 于是 `onEnable()` 从未执行, 用户看到的现象就是
 * "收到视频不下载, 点开才下载"。用户真正拨过的开关只有「自动查看原图/原视频」。
 *
 * 两个功能是同一诉求的两半: 那个只能在**点开**图片/视频页时动作, 本功能才做得到
 * "不点开也拉"。所以 [defaultEnabled] 改为**跟随**前者的开关值 —— 已经明确开过
 * "自动查看原图/原视频" 的用户, 升级后本功能自动生效; 在本功能里拨过开关的用户,
 * 以本功能的 key 为准 (不会覆盖用户的显式选择)。
 */
@Feature(
    name = "后台自动加载原图/原视频",
    categories = ["聊天"],
    description = "不打开图片 / 视频也自动把收到的原图与原画质视频拉下来, 点开时已是原画质"
)
object AutoPrefetchOriginalMedia : SwitchFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "AutoPrefetchOriginalMedia"

    /** 「自动查看原图/原视频」的开关 key (见 [defaultEnabled])。 */
    private const val VIEW_FEATURE_KEY = "自动查看原图/原视频"

    /** 上面那个功能改名前的 key (v281 事故留下的兼容 key)。 */
    private const val VIEW_FEATURE_LEGACY_KEY = "自动查看原图"

    /**
     * 本功能从未被拨动过时的默认值 = **跟随「自动查看原图/原视频」当前的开关值**。
     *
     * 用户既然显式开过 "自动查看原图/原视频", 说明他要的就是"原画质自动到本地";
     * 那个功能只能在**点开**时动作, 本功能才能"不点开也拉", 所以直接继承。
     * 一旦用户在本功能的设置项里拨过开关, `WePrefs` 里就有本功能的 key 了,
     * `getBoolOrDef` 不会再走到这里 —— 用户的显式选择永远优先。
     */
    override val defaultEnabled: Boolean
        get() = runCatching {
            WePrefs.getBoolOrDef(
                VIEW_FEATURE_KEY,
                WePrefs.getBoolOrDef(VIEW_FEATURE_LEGACY_KEY, false)
            )
        }.getOrDefault(false)

    /**
     * 启动日志 (v288 新增)。
     *
     * v285/v286 的日志里本功能**一行都没有**, 没法区分"开关没开"和"开了但静默失败" ——
     * 这次把开关值 + 三个 key 的存在性都打出来, 下次一眼就能定论。
     */
    override val shouldEnableOnStartup: Boolean
        get() {
            val selfKey = runCatching { WePrefs.containsKey(name) }.getOrDefault(false)
            val viewKey = runCatching { WePrefs.containsKey(VIEW_FEATURE_KEY) }.getOrDefault(false)
            val viewLegacyKey =
                runCatching { WePrefs.containsKey(VIEW_FEATURE_LEGACY_KEY) }.getOrDefault(false)
            WeLogger.i(
                TAG,
                "startup: 开关=$_isEnabled (本功能 key '$name' 存在=$selfKey; " +
                    "未拨动过时跟随「自动查看原图/原视频」: " +
                    "新key 存在=$viewKey, 旧key 存在=$viewLegacyKey)"
            )
            return _isEnabled
        }

    /** 单条消息最多尝试次数 (立即 + 两次延迟重试)。 */
    private const val MAX_ATTEMPTS = 3

    /** 补偿扫描回看窗口。 */
    private const val SCAN_WINDOW_MS = 6 * 60 * 60 * 1000L

    /** 补偿扫描单轮条数上限, 避免开启瞬间发起过多下载任务。 */
    private const val SCAN_LIMIT = 200

    /** 补偿扫描周期。 */
    private const val SCAN_INTERVAL_MS = 300_000L

    /** 同时进行的下载任务上限 (原视频动辄几十 MB)。 */
    private const val MAX_CONCURRENT = 2

    /** 已完成 (已拉到本地) 的消息, 键为 "type:msgSvrId"。 */
    private val doneKeys = ConcurrentHashMap.newKeySet<String>()

    /** 正在处理中的 msgSvrId, 防止 insert/update/扫描 三路重复发起。 */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    /** 已发现但尚未完成的 msgSvrId (update 事件据此判断是否值得跟进)。 */
    private val pendingSvrIds = ConcurrentHashMap.newKeySet<Long>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val semaphore = Semaphore(MAX_CONCURRENT)

    private fun isTarget(type: Int?): Boolean =
        type == MessageType.IMAGE.code || type == MessageType.VIDEO.code

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
        installDownloadProbes()
        WeLogger.i(
            TAG,
            "enabled: 收到图片/视频即后台拉原图与原画质视频 " +
                "(补偿扫描每 ${SCAN_INTERVAL_MS / 1000}s, 回看 ${SCAN_WINDOW_MS / 3600000}h, 并发 $MAX_CONCURRENT)"
        )
        startCompensation()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        doneKeys.clear()
        inFlight.clear()
        pendingSvrIds.clear()
        WeLogger.i(TAG, "disabled: 监听已移除, 补偿扫描已停止")
    }

    // ---------------------------------------------------------------------------------------------
    // 事件入口
    // ---------------------------------------------------------------------------------------------

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        // 自己发出的媒体本来就在本地
        if (values.getAsInteger("isSend") == 1) return
        // insert 事件里的列不全 (type 可能缺失), 统一按 msgSvrId 回查 message 行
        dispatch(values.getAsLong("msgSvrId") ?: return, "insert")
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "message") return

        val msgSvrId = values.getAsLong("msgSvrId") ?: run {
            val msgId = WeDatabaseListenerApi.extractMsgIdFromUpdate(values, whereClause, whereArgs)
                ?: return
            WeMessageApi.getMsgSvrIdByMsgId(msgId) ?: return
        }
        if (msgSvrId <= 0L) return

        // update 事件绝大多数与媒体无关 (已读、状态位等)。只有两种值得跟进:
        //  ① 事件自带 type 且是图片/视频  ② 这条消息是之前 insert 时见过的 (等它的下载信息落库)
        val explicitType = values.getAsInteger("type")
        if (!isTarget(explicitType) && msgSvrId !in pendingSvrIds) return

        dispatch(msgSvrId, "update")
    }

    /**
     * 按 msgSvrId 回查 message 行并串行处理。
     *
     * 注意**不能在 hook 回调里直接查库** —— insert 事件跑在微信自己的写库线程上,
     * 在那里再发一次查询容易出问题, 统一丢到 IO 协程里做。
     */
    private fun dispatch(msgSvrId: Long, via: String) {
        if (msgSvrId <= 0L) return
        if (!inFlight.add(msgSvrId)) return
        scope.launch {
            try {
                val row = queryRow(msgSvrId) ?: return@launch
                if (!isTarget(row.type)) return@launch
                val key = "${row.type}:${row.msgSvrId}"
                if (key in doneKeys) return@launch
                pendingSvrIds.add(row.msgSvrId)
                WeLogger.i(
                    TAG,
                    "检测到媒体消息 ($via): msgSvrId=$msgSvrId type=${row.type} talker=${row.talker}, 后台下载"
                )
                semaphore.withPermit { runTask(row, via, key) }
            } finally {
                inFlight.remove(msgSvrId)
            }
        }
    }

    private class Row(val type: Int, val msgId: Long, val msgSvrId: Long, val talker: String?)

    private fun queryRow(msgSvrId: Long): Row? {
        if (!ensureDatabase()) return null
        return try {
            WeDatabaseApi.rawQuery(
                "SELECT type, msgId, talker FROM message WHERE msgSvrId=? LIMIT 1",
                arrayOf<Any>(msgSvrId)
            ).use { cursor ->
                if (!cursor.moveToNext()) {
                    WeLogger.w(TAG, "message 表里找不到 msgSvrId=$msgSvrId")
                    null
                } else {
                    Row(
                        type = cursor.getInt(cursor.getColumnIndexOrThrow("type")),
                        msgId = cursor.getLong(cursor.getColumnIndexOrThrow("msgId")),
                        msgSvrId = msgSvrId,
                        talker = cursor.getString(cursor.getColumnIndexOrThrow("talker")),
                    )
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "查询 message 行失败 (msgSvrId=$msgSvrId)", e)
            null
        }
    }

    private suspend fun runTask(row: Row, via: String, key: String) {
        for (attempt in 1..MAX_ATTEMPTS) {
            val ok = try {
                if (row.type == MessageType.IMAGE.code) prefetchImage(row.msgSvrId) else prefetchOriginVideo(row)
            } catch (t: Throwable) {
                WeLogger.e(TAG, "后台下载异常 ($key, 第 $attempt 次, via $via)", t)
                false
            }
            if (ok) {
                doneKeys.add(key)
                pendingSvrIds.remove(row.msgSvrId)
                return
            }
            if (attempt < MAX_ATTEMPTS) {
                // 图片服务 / 视频信息行落库可能晚于消息本身, 且 CDN 未必就绪
                delay(if (attempt == 1) 5_000 else 10_000)
            }
        }
        WeLogger.w(TAG, "后台下载未完成 $key (via $via), 留给补偿扫描")
    }

    /** 原图: 复用「自动缓存图片」的通道 (内部优先取 hdImgId 原图行)。@return 是否已落地。 */
    private fun prefetchImage(msgSvrId: Long): Boolean {
        val path = WeMessageApi.cacheImage(msgSvrId)
        return if (path != null) {
            WeLogger.i(TAG, "原图已落地: msgSvrId=$msgSvrId -> $path")
            true
        } else {
            false
        }
    }

    /** 原画质视频: 取完整 MsgInfo, 交给复刻的微信下载链路。 */
    private fun prefetchOriginVideo(row: Row): Boolean {
        val msgId = if (row.msgId > 0) row.msgId else queryRow(row.msgSvrId)?.msgId ?: 0L
        if (msgId <= 0L) {
            WeLogger.w(TAG, "视频消息没有有效 msgId, 跳过: msgSvrId=${row.msgSvrId}")
            return false
        }
        val msgInfo = WeMessageApi.getMsgInfoInstanceByMsgId(msgId) ?: run {
            WeLogger.w(TAG, "取不到 MsgInfo, 跳过: msgId=$msgId")
            return false
        }
        return OriginVideoDownload.request(msgInfo, row.msgSvrId)
    }

    // ---------------------------------------------------------------------------------------------
    // 补偿扫描
    // ---------------------------------------------------------------------------------------------

    private fun startCompensation() {
        scope.launch {
            delay(30_000)
            while (isActive) {
                scanRecentMessages()
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private fun ensureDatabase(): Boolean {
        if (WeDatabaseApi.isReady) return true
        runCatching { WeDatabaseApi.ensureDatabase() }
        if (WeDatabaseApi.isReady) return true
        WeLogger.w(TAG, "数据库未就绪 (重适配窗口), 本轮跳过, 稍后重试")
        return false
    }

    private fun scanRecentMessages() {
        if (!ensureDatabase()) return
        val since = System.currentTimeMillis() - SCAN_WINDOW_MS
        val rows = try {
            WeDatabaseApi.rawQuery(
                "SELECT type, msgId, msgSvrId, talker FROM message " +
                    "WHERE type IN (?, ?) AND isSend=0 AND createTime>? ORDER BY createTime DESC LIMIT ?",
                arrayOf<Any>(MessageType.IMAGE.code, MessageType.VIDEO.code, since, SCAN_LIMIT)
            ).use { cursor ->
                buildList<Row> {
                    while (cursor.moveToNext()) {
                        add(
                            Row(
                                type = cursor.getInt(cursor.getColumnIndexOrThrow("type")),
                                msgId = cursor.getLong(cursor.getColumnIndexOrThrow("msgId")),
                                msgSvrId = cursor.getLong(cursor.getColumnIndexOrThrow("msgSvrId")),
                                talker = cursor.getString(cursor.getColumnIndexOrThrow("talker")),
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "补偿扫描查询失败", e)
            return
        }

        var launched = 0
        rows.forEach { row ->
            if (row.msgSvrId <= 0L) return@forEach
            val key = "${row.type}:${row.msgSvrId}"
            if (key in doneKeys || row.msgSvrId in inFlight) return@forEach
            pendingSvrIds.add(row.msgSvrId)
            launched++
            dispatch(row.msgSvrId, "scan")
        }
        WeLogger.i(TAG, "补偿扫描: 近 ${SCAN_WINDOW_MS / 3600000}h 内 ${rows.size} 条媒体消息, 本轮发起 $launched 条")
    }

    // ---------------------------------------------------------------------------------------------
    // 探针: 让微信自己跑一次原视频下载, 把真实参数打进日志 (用来校准上面的复刻配方)
    // ---------------------------------------------------------------------------------------------

    private var probesInstalled = false

    private fun installDownloadProbes() {
        if (probesInstalled) return
        probesInstalled = true

        // 打开带原视频的视频页时, 「自动查看原图/原视频」会自动点掉「查看原视频」按钮,
        // 于是这两个构造器必然被调到 —— 日志里的真实参数就是复刻配方对不对的判据。
        hookFirstConstructor(OriginVideoDownload.TASK_DOWNLOAD, "下载任务 gallery.f") { args ->
            "msgId=${msgIdOf(args.getOrNull(0))} url=${args.getOrNull(1)} 原视频标记=${args.getOrNull(2)}"
        }
        hookFirstConstructor(OriginVideoDownload.TASK_CALLBACK, "下载回调 gallery.g") { args ->
            "msgId=${msgIdOf(args.getOrNull(0))} 视频信息=${args.getOrNull(1)?.javaClass?.simpleName} " +
                "记录=${args.getOrNull(3)?.javaClass?.simpleName}"
        }
    }

    private fun hookFirstConstructor(
        className: String,
        label: String,
        describe: (Array<Any?>) -> String,
    ) {
        runCatching {
            val clazz = className.toClassOrNull() ?: run {
                WeLogger.w(TAG, "探针跳过: $className 不存在")
                return@runCatching
            }
            val ctor = clazz.reflekt().firstConstructorOrNull { } ?: run {
                WeLogger.w(TAG, "探针跳过: $label 没有构造器")
                return@runCatching
            }
            ctor.hookAfter {
                runCatching { WeLogger.i(TAG, "探针[$label] 微信侧调用: ${describe(args)}") }
            }
            WeLogger.i(TAG, "探针已装: $label")
        }.onFailure { WeLogger.w(TAG, "探针安装失败: $label", it) }
    }

    private fun msgIdOf(msgInfo: Any?): Long = runCatching {
        msgInfo?.javaClass?.getMethod("getMsgId")?.invoke(msgInfo) as? Long ?: 0L
    }.getOrDefault(0L)

    // ---------------------------------------------------------------------------------------------
    // 复刻微信的「原视频」下载链路 (8.0.76 混淆名, 失效时靠日志定位到具体一环)
    // ---------------------------------------------------------------------------------------------

    private object OriginVideoDownload {

        private const val TAG = "AutoPrefetchOriginalMedia"

        /** 视频消息解析 + 原视频下载记录查询 (静态方法所在地)。 */
        private const val GALLERY_VIDEO_HELPER = "com.tencent.mm.ui.chatting.gallery.n1"

        /** 视频消息判定 (静态 N(MsgInfo): Z)。 */
        private const val GALLERY_VIDEO_CHECK = "com.tencent.mm.ui.chatting.gallery.o1"

        const val TASK_DOWNLOAD = "com.tencent.mm.ui.chatting.gallery.f"
        const val TASK_CALLBACK = "com.tencent.mm.ui.chatting.gallery.g"

        /** CDN 会话: 构造参数是下载回调接口, 有 a(任务) / p() 执行 / m 进度回调。 */
        private const val CDN_SESSION = "il4.l"

        /**
         * 原视频信息解析器 (真身 `MicroMsg.VideoParseInfo`): 实例方法 `d(String)`
         * 按**消息内容 XML** 解析出原视频 CDN 记录 (cdnrawvideourl / aeskey / rawlength)。
         */
        private const val VIDEO_PARSER = "v21.i3"

        /**
         * 解析器单例的宿主: 静态字段 `h`(类型 v21.i3) 就是解析器实例,
         * `i` / `j` 是它内部的两个解析缓存。**别把 h 当成 v21.i3 自己的字段** ——
         * v21.i3 一个字段都没有 (v287 踩过)。
         */
        private const val VIDEO_RECORD_HOLDER = "v21.j3"

        /** 原视频下载记录: d = cdnrawvideourl, f = aeskey, b = isplaceholder, g = rawlength。 */
        private const val VIDEO_RECORD = "v21.j3"

        /** 下载计时对象, 构造参数是 long。 */
        private const val CDN_TIME = "rl4.c"

        /** 只在同一条消息上打一次"已发起"日志。 */
        private val logged = ConcurrentHashMap.newKeySet<Long>()

        @Volatile
        private var recipe: Recipe? = null

        @Volatile
        private var resolveFailureLogged = false

        private class Recipe(
            val checkVideo: Method,
            /** `n1.t(MsgInfo)`: 现场解析视频消息并写缓存 (微信自己先走它)。 */
            val buildVideoInfo: Method,
            /** `n1.s(MsgInfo)`: 只读 gallery 缓存, 作为兜底。 */
            val cachedVideoInfo: Method,
            val lookupRecord: Method,
            val registry: Any,
            val recordUrl: Field,
            val videoInfoUrl: Method,
            val videoInfoKey: Method,
            val newTime: Constructor<*>,
            val newCallback: Constructor<*>,
            val newDownloadTask: Constructor<*>,
            val newSession: Constructor<*>,
            val sessionAdd: Method,
            val sessionRun: Method,
            val sessionProgress: Field?,
            val progressType: Class<*>?,
        )

        fun resolve(className: String): Class<*>? = className.toClassOrNull()

        /** @return true 表示这条消息已经不需要再重试 (已发起 / 本来就没有原视频)。 */
        fun request(msgInfo: Any, msgSvrId: Long): Boolean {
            val r = resolveRecipe()
            if (r == null) {
                if (!resolveFailureLogged) {
                    resolveFailureLogged = true
                    WeLogger.w(TAG, "原视频下载链路不可用, 后台只拉原图 (微信改版后需按日志校准混淆名)")
                }
                return true
            }
            return runCatching { start(r, msgInfo, msgSvrId) }.getOrElse { t ->
                WeLogger.e(TAG, "原视频下载复刻执行异常 (msgSvrId=$msgSvrId)", t)
                false
            }
        }

        private fun start(r: Recipe, msgInfo: Any, msgSvrId: Long): Boolean {
            // 1) 不是视频消息: 与微信同款静态判定
            val isVideo = runCatching { r.checkVideo.invoke(null, msgInfo) as? Boolean }.getOrNull()
            if (isVideo != true) {
                WeLogger.i(TAG, "跳过 msgSvrId=$msgSvrId: 不是可下载原视频的消息 (isVideo=$isVideo)")
                return true
            }

            // 2) 现场解析视频消息 -> v21.v2
            //    必须走 n1.t (insert video info): n1.s 只读 gallery 的缓存,
            //    用户没打开过视频页时缓存是空的 → v285/v286 就是这样静默失败的。
            val videoInfo = runCatching { r.buildVideoInfo.invoke(null, msgInfo) }.getOrNull()
                ?: runCatching { r.cachedVideoInfo.invoke(null, msgInfo) }.getOrNull()
            if (videoInfo == null) {
                // 解析失败 != "没有原视频" (可能 msg 还没就绪), 交还给重试 / 补偿扫描
                WeLogger.w(TAG, "视频信息解析失败 (msgSvrId=$msgSvrId), 稍后重试")
                return false
            }
            val key = runCatching { r.videoInfoKey.invoke(videoInfo) as? String }.getOrNull()
            val url = runCatching { r.videoInfoUrl.invoke(videoInfo) as? String }.getOrNull()
            if (key.isNullOrEmpty()) {
                WeLogger.w(TAG, "视频信息缺 key (msgSvrId=$msgSvrId), 稍后重试")
                return false
            }
            if (url.isNullOrEmpty()) {
                // 视频地址 (msg.imgPath) 还没就绪 —— 收视频瞬间常常如此, 交给重试 / 补偿扫描
                WeLogger.w(TAG, "视频地址尚未就绪 (msgSvrId=$msgSvrId), 稍后重试")
                return false
            }

            // 3) 原视频下载记录 —— 微信自己就是靠它判断"这条消息确实带原视频"
            val record = runCatching { r.lookupRecord.invoke(r.registry, key) }.getOrNull()
            val recordUrl = record?.let { runCatching { r.recordUrl.get(it) as? String }.getOrNull() }
            if (record == null || recordUrl.isNullOrEmpty()) {
                WeLogger.i(TAG, "该视频没有原视频信息 (msgSvrId=$msgSvrId key=${key.take(48)}), 无需下载")
                return true
            }

            // 4) 复刻微信那条链路
            val now = r.newTime.newInstance(System.currentTimeMillis())
            val callback = r.newCallback.newInstance(msgInfo, videoInfo, now, record)
            val task = r.newDownloadTask.newInstance(msgInfo, url, true)
            val session = r.newSession.newInstance(callback)
            r.sessionAdd.invoke(session, task)
            installProgressPlaceholder(r, session)
            val accepted = runCatching { r.sessionRun.invoke(session) as? Boolean }.getOrNull()

            if (logged.add(msgSvrId)) {
                WeLogger.i(
                    TAG,
                    "原视频下载已发起: msgSvrId=$msgSvrId url=$url accepted=$accepted"
                )
                if (logged.size > 512) logged.clear()
            } else {
                WeLogger.i(TAG, "原视频下载再次发起: msgSvrId=$msgSvrId accepted=$accepted")
            }
            return accepted == true
        }

        /**
         * 进度回调 (`il4.l.m`, 类型是 `il4.u`) 只在 UI 场景用得到, 后台没有 gallery.n1 实例,
         * 用一个空实现的动态代理占位, 避免进度回调时空指针。
         */
        private fun installProgressPlaceholder(r: Recipe, session: Any) {
            val field = r.sessionProgress ?: return
            val type = r.progressType ?: return
            runCatching {
                field.set(
                    session,
                    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                        when (method.name) {
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.getOrNull(0)
                            "toString" -> "WcxOriginVideoProgress"
                            else -> defaultValue(method.returnType)
                        }
                    }
                )
            }.onFailure { WeLogger.w(TAG, "进度回调占位失败 (可忽略): ${it.message}") }
        }

        /** 动态代理方法的默认返回值。 */
        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }

        /** 解析一次并缓存; 失败不重试, 免得每条消息都刷一遍日志。 */
        private fun resolveRecipe(): Recipe? {
            recipe?.let { return it }
            synchronized(this) {
                recipe?.let { return it }
                val built = runCatching { buildRecipe() }
                    .onFailure { WeLogger.e(TAG, "原视频下载链路解析失败 (微信改版后混淆名已变)", it) }
                    .getOrNull()
                recipe = built
                if (built != null) {
                    WeLogger.i(TAG, "原视频下载链路已解析: ${built.sessionRun.declaringClass.name}.p()")
                }
                return built
            }
        }

        private fun buildRecipe(): Recipe {
            val helper = need(GALLERY_VIDEO_HELPER)
            val checkCls = need(GALLERY_VIDEO_CHECK)
            val sessionCls = need(CDN_SESSION)
            val parserCls = need(VIDEO_PARSER)
            val holderCls = need(VIDEO_RECORD_HOLDER)
            val recordCls = need(VIDEO_RECORD)
            val timeCls = need(CDN_TIME)

            val checkVideo = staticMethod(checkCls, "N", 1)
            // n1.t = 现场解析 (insert video info); n1.s = 只读缓存, 兜底用
            val buildVideoInfo = staticMethodOrNull(helper, "t", 1)
                ?: error("$GALLERY_VIDEO_HELPER.t(MsgInfo) 不存在")
            val cachedVideoInfo = staticMethodOrNull(helper, "s", 1)
                ?: error("$GALLERY_VIDEO_HELPER.s(MsgInfo) 不存在")
            val videoInfoCls = buildVideoInfo.returnType

            // 解析器单例挂在 v21.j3 上 (静态字段 h), 不在 v21.i3 自己身上
            val registry = holderCls.getDeclaredField("h").apply { isAccessible = true }.get(null)
                ?: error("$VIDEO_RECORD_HOLDER.h 为 null")
            val lookupRecord = declaredMethod(parserCls, "d", String::class.java)
                ?: error("$VIDEO_PARSER.d(String) 不存在")
            val recordUrl = declaredField(recordCls, "d", String::class.java)
                ?: error("$VIDEO_RECORD.d (String) 不存在")

            // v2.e() 是 CDN 地址 (下载任务第 2 个参数), v2.h() 是下载记录的 key (消息内容 XML)
            val videoInfoUrl = declaredMethod(videoInfoCls, "e")
                ?: error("${videoInfoCls.name}.e() 不存在")
            val videoInfoKey = declaredMethod(videoInfoCls, "h")
                ?: error("${videoInfoCls.name}.h() 不存在")

            val msgInfoCls = buildVideoInfo.parameterTypes[0]
            val callbackCls = need(TASK_CALLBACK)
            val callbackInterface = callbackCls.interfaces.firstOrNull()
                ?: error("$TASK_CALLBACK 没有实现下载回调接口")

            val newTime = declaredConstructor(timeCls, java.lang.Long.TYPE)
                ?: error("$CDN_TIME(long) 不存在")
            val newCallback = declaredConstructor(callbackCls, msgInfoCls, videoInfoCls, timeCls, recordCls)
                ?: error("$TASK_CALLBACK(${msgInfoCls.simpleName}, v2, ${timeCls.simpleName}, ${recordCls.simpleName}) 不存在")
            val newDownloadTask = declaredConstructor(
                need(TASK_DOWNLOAD), msgInfoCls, String::class.java, java.lang.Boolean.TYPE
            ) ?: error("$TASK_DOWNLOAD(MsgInfo, String, boolean) 不存在")
            val newSession = declaredConstructor(sessionCls, callbackInterface)
                ?: error("$CDN_SESSION(${callbackInterface.simpleName}) 不存在")
            val sessionAdd = declaredMethod(sessionCls, "a", callbackOrTaskInterface = true)
                ?: error("$CDN_SESSION.a(下载任务) 不存在")
            val sessionRun = declaredMethod(sessionCls, "p")
                ?: error("$CDN_SESSION.p() 不存在")

            // 进度回调字段: 接口类型 + gallery.n1 实现了它 (原始逻辑里 l.m 被赋成 n1),
            // 且不能是构造参数里那个下载回调接口 (那个我们已经传了真对象)
            val sessionProgress = sessionCls.declaredFields
                .firstOrNull {
                    it.type.isInterface &&
                        it.type != callbackInterface &&
                        it.type.isAssignableFrom(helper)
                }
                ?.apply { isAccessible = true }

            return Recipe(
                checkVideo = checkVideo,
                buildVideoInfo = buildVideoInfo,
                cachedVideoInfo = cachedVideoInfo,
                lookupRecord = lookupRecord,
                registry = registry,
                recordUrl = recordUrl,
                videoInfoUrl = videoInfoUrl,
                videoInfoKey = videoInfoKey,
                newTime = newTime,
                newCallback = newCallback,
                newDownloadTask = newDownloadTask,
                newSession = newSession,
                sessionAdd = sessionAdd,
                sessionRun = sessionRun,
                sessionProgress = sessionProgress,
                progressType = sessionProgress?.type,
            )
        }

        private fun need(className: String): Class<*> = resolve(className) ?: error("类不存在: $className")

        private fun staticMethod(clazz: Class<*>, name: String, paramCount: Int): Method =
            staticMethodOrNull(clazz, name, paramCount)
                ?: error("${clazz.name} 里找不到静态方法 $name/$paramCount")

        private fun staticMethodOrNull(clazz: Class<*>, name: String, paramCount: Int): Method? =
            clazz.declaredMethods
                .firstOrNull {
                    it.name == name && it.parameterCount == paramCount && Modifier.isStatic(it.modifiers)
                }
                ?.apply { isAccessible = true }

        private fun declaredMethod(
            clazz: Class<*>,
            name: String,
            param: Class<*>? = null,
            callbackOrTaskInterface: Boolean = false,
        ): Method? = clazz.declaredMethods
            .firstOrNull { m ->
                m.name == name && when {
                    param != null -> m.parameterCount == 1 && m.parameterTypes[0] == param
                    callbackOrTaskInterface -> m.parameterCount == 1 && m.parameterTypes[0].isInterface
                    else -> m.parameterCount == 0
                }
            }
            ?.apply { isAccessible = true }

        private fun declaredField(clazz: Class<*>, name: String, type: Class<*>): Field? =
            clazz.declaredFields
                .firstOrNull { it.name == name && it.type == type }
                ?.apply { isAccessible = true }

        private fun declaredConstructor(clazz: Class<*>, vararg params: Class<*>): Constructor<*>? =
            runCatching {
                clazz.getDeclaredConstructor(*params).apply { isAccessible = true }
            }.getOrNull()
    }
}
