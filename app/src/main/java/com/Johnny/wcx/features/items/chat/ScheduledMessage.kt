package com.Johnny.wcx.features.items.chat

import android.app.AlarmManager
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException

@Feature(
    name = "定时发送消息",
    categories = ["聊天"],
    description = "在指定时间发送消息到群聊或私聊，支持每天重复或单次发送，支持多种消息类型"
)
object ScheduledMessage : ClickableFeature() {

    private const val TAG = "ScheduledMessage"
    private const val ALARM_ACTION = "com.Johnny.wcx.SCHEDULED_MESSAGE"
    private const val EXTRA_SCHEDULE_ID = "schedule_id"

    /**
     * 漏发补发窗口: 微信进程被杀/闹钟被系统吞掉时, 下次拉起微信后只要还没超过这个时长,
     * 就补发一次当天漏掉的任务。原实现只有 15 分钟, 用户几小时后才打开微信就会整天漏发。
     */
    private const val CATCHUP_WINDOW_MS = 12 * 60 * 60 * 1000L
    /** 普通重排允许的迟到窗口; 冷启动补发仍使用 12 小时宽限。 */
    private const val RETRY_WINDOW_MS = 5 * 60 * 1000L
    /** 精确窗前提前唤醒专用线程, 避免 delay 唤醒本身成为迟到源。 */
    private const val PREWAKE_LEAD_MS = 250L
    private const val PREWAKE_WAKE_LOCK_TIMEOUT_MS = 2_000L
    /** 守护轮询间隔: 进程内存活时兜底扫描到点任务, 同时补回丢失的进程内定时器 */
    private const val MONITOR_INTERVAL_MS = 30_000L
    /** 单段发送失败后的重试等待 */
    private const val SEGMENT_RETRY_DELAY_MS = 2_000L
    /** 单次任务迟到重试的间隔 */
    private const val ONETIME_RETRY_DELAY_MS = 30_000L
    /** 多接收方时, 切换到下一个接收方之间的间隔 (避免连续发送被判为风控) */
    private const val TARGET_GAP_MS = 800L
    /** 单个任务当天最多尝试次数, 超出则等明天, 防止失败时无限重试 */
    private const val MAX_FIRE_ATTEMPTS = 3

    /**
     * IMAGE 段 filePath 为空时, content 存 "svr:<msgSvrId>" 作为延迟下载标记:
     * 创建任务时图片 CDN 下载未落地不再让整个任务创建失败, 发送时自动重试下载。
     */
    const val DEFERRED_IMAGE_PREFIX = "svr:"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class MessageSegment(
        val type: MessageType,
        val content: String = "",
        val filePath: String = "",
        val duration: Int = 0,
        // 复读式引用: 创建时媒体未落地的消息只记录来源 (与转发/复读同一通道),
        // 发送时按引用从聊天记录现场解析媒体再发出
        val srcTalker: String = "",
        val srcMsgId: Long = 0,
        val srcSvrId: Long = 0
    ) : java.io.Serializable

    @Serializable
    data class ScheduleConfig(
        val id: String,
        val talker: String,
        val talkerName: String,
        /**
         * 除 [talker] 之外额外勾选的发送对象 (定时发送支持多选)。
         * 旧任务没有该字段, 反序列化时为空列表, 行为与原来一致。
         */
        val extraTalkers: List<String> = emptyList(),
        /** 与 [extraTalkers] 一一对应的显示名 */
        val extraTalkerNames: List<String> = emptyList(),
        // 任务主题（消息菜单创建时必填；旧数据无该字段，反序列化时取默认空串）
        val subject: String = "",
        val messageType: MessageType = MessageType.TEXT,
        val content: String = "",
        val filePath: String = "",
        val duration: Int = 0,
        val hour: Int = 9,
        val minute: Int = 0,
        val repeatDaily: Boolean = true,
        var enabled: Boolean = true,
        val oneTimeOnly: Boolean = false,
        var nextSendTime: Long = 0,
        val segments: List<MessageSegment> = emptyList(),
        /**
         * 最近一次成功发送的日期 (yyyy-MM-dd)。用于"漏发补发"判定:
         * 进程被杀 / 闹钟被系统吞掉后重新拉起微信时, 只有当天还没发过才补发,
         * 避免每次冷启动都重复补发同一条消息。旧数据无此字段, 反序列化为空串。
         */
        var lastFiredDate: String = "",
        /** 当天已尝试发送的次数, 成功或跨天时归零; 用于限制失败重试次数 */
        var fireAttempts: Int = 0,
        /** fireAttempts 所属日期; 旧数据为空时按新的一天处理 */
        var fireAttemptDate: String = "",
        /**
         * 当天已经成功收到消息的接收方。
         * 多接收方任务里部分失败需要重试时, 跳过这些已成功的接收方, 避免重复发送。
         */
        var sentTargets: List<String> = emptyList(),
        /** 每个接收方已成功提交的消息段索引; 重试只补未提交或明确失败的段 */
        var sentSegments: Map<String, List<Int>> = emptyMap(),
        /** 持久化闹钟 requestCode; 进程重启后复用, 避免旧闹钟变孤儿 */
        var alarmRequestCode: Int = 0
    ) : java.io.Serializable {
        /** 任务的全部发送对象 (第一个是 talker, 其余是额外勾选的) */
        fun targetTalkers(): List<String> {
            val all = (listOf(talker) + extraTalkers).filter { it.isNotBlank() }
            return all.distinct()
        }

        /** 全部发送对象的显示名 */
        fun targetNames(): List<String> {
            val names = (listOf(talkerName) + extraTalkerNames).filter { it.isNotBlank() }
            return names.ifEmpty { targetTalkers() }
        }
    }

    enum class MessageType(val description: String) {
        TEXT("文本"),
        IMAGE("图片"),
        VOICE("语音"),
        VIDEO("视频"),
        FILE("文件"),
        LINK("链接"),
        // 微信收藏项: content 存收藏 localId, 发送时交给微信原生收藏发送通道,
        // 因此图片/语音/视频/文件/链接/位置/音乐/小程序/聊天记录都能原样发出。
        FAVORITE("收藏")
    }

    // 存储改用 kotlinx JSON (ignoreUnknownKeys=true): 原先的 Java 序列化对类结构零容忍,
    // ScheduleConfig 每加一个字段 serialVersionUID 就变, 导致旧任务整批读不出
    // (InvalidClassException: stream classdesc serialVersionUID 与 local 不一致)
    private var schedulesJson by prefOption("scheduled_messages_json", "")

    // 旧版 Java 序列化存储, 仅用于一次性迁移最近版本写入的任务
    private var legacySchedules by prefOption("scheduled_messages", emptyList<ScheduleConfig>())

    private var schedules: List<ScheduleConfig>
        get() {
            val version = storageVersion
            cachedSchedules?.takeIf { cachedSchedulesVersion == version }?.let { return it }
            val raw = schedulesJson
            val decoded = if (raw.isNotBlank()) {
                runCatching { json.decodeFromString<List<ScheduleConfig>>(raw) }
                    .onFailure { WeLogger.e(TAG, "failed to parse schedules json", it) }
                    .getOrDefault(emptyList())
            } else {
                migrateLegacySchedules()
            }
            cachedSchedules = decoded
            cachedSchedulesVersion = version
            return decoded
        }
        set(value) {
            schedulesJson = runCatching { json.encodeToString(value) }
                .onFailure { WeLogger.e(TAG, "failed to encode schedules json", it) }
                .getOrDefault("[]")
            storageVersion++
            cachedSchedules = value
            cachedSchedulesVersion = storageVersion
        }

    private fun migrateLegacySchedules(): List<ScheduleConfig> {
        val legacy = runCatching { legacySchedules }
            .onFailure { WeLogger.e(TAG, "failed to read legacy schedules", it) }
            .getOrDefault(emptyList())
        if (legacy.isEmpty()) return emptyList()
        WeLogger.i(TAG, "migrated ${legacy.size} legacy schedules to json storage")
        schedules = legacy
        return legacy
    }
    /**
     * 任务存储的读写锁。
     * 多个任务可能同时触发, 而 updateSchedule 是"读出整表 -> 替换一项 -> 整表写回",
     * 无锁并发会互相覆盖: 后写的一份把先写的 nextSendTime/lastFiredDate 抹掉,
     * 表现为任务状态回退、下次触发时间算错, 是定时发送偶发失灵的诱因之一。
     */
    private val storageLock = Any()

    /** 写入存储时递增; 触发热路径据此避免无条件第二次 JSON 解码。 */
    @Volatile
    private var storageVersion = 0L
    /** 当前版本对应的内存快照; 热路径不再解码 JSON。 */
    @Volatile
    private var cachedSchedules: List<ScheduleConfig>? = null
    @Volatile
    private var cachedSchedulesVersion = -1L
    @Volatile
    private var currentDayKey: String = LocalDate.now().toString()

    /**
     * 闹钟 requestCode 分配表。
     * 原实现直接用 schedule.id.hashCode() 作 requestCode: 不同 id 一旦哈希相撞,
     * PendingIntent(FLAG_UPDATE_CURRENT) 会互相覆盖, 被覆盖的那个任务再也收不到闹钟。
     * 这里改为按任务 id 分配稳定且互不相同的小整数并持久化, 哈希不再参与。
     */
    private val alarmCodes = ConcurrentHashMap<String, Int>()
    private var alarmCodeSeq by prefOption("scheduled_alarm_code_seq", 1000)

    private val activeAlarms = ConcurrentHashMap<String, PendingIntent>()
    private val timerStates = ConcurrentHashMap<String, TimerState>()

    /** 媒体/多接收人等长任务仍在 IO; 单文本走专用调度线程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSendJobs = ConcurrentHashMap<String, Job>()
    // 在途互斥: 进程内定时器与 AlarmManager 闹钟会在同一时刻先后触发,
    // 时间守卫只能挡"提前触发"; 若第一个触发者正在发送(媒体解析/下载可能耗时数十秒),
    // 第二个触发者重读任务时 nextSendTime 尚未推进, 守卫照样放行 → 重复发送。
    private val triggering = ConcurrentHashMap.newKeySet<String>()
    private lateinit var alarmReceiver: BroadcastReceiver
    private lateinit var recoveryReceiver: BroadcastReceiver

    private var schedulerThread: HandlerThread? = null
    private var schedulerHandler: Handler? = null
    private var monitorRunnable: Runnable? = null
    /** 仅调度线程读写: 多个任务同时临近时只提权一次。 */
    private var schedulerPriorityDepth = 0
    private var schedulerOriginalPriority = Process.THREAD_PRIORITY_DEFAULT
    private val queueTimingStats = ScheduledMessageTimingStats(256)

    private class TimerState(val token: Any) {
        var prepared = false
        var wakeLock: PowerManager.WakeLock? = null
    }

    private val recoveryCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = recoverSchedules("resume")
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun recoverSchedules(source: String) {
        if (!isActive) return
        refreshDayKey()
        val handler = schedulerHandler ?: return
        handler.post { if (isActive) sweepSchedules(source) }
    }

    override fun onEnable() {
        startSchedulerThread()
        refreshDayKey()
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                val receivedAtMs = System.currentTimeMillis()
                val receivedAtNs = SystemClock.elapsedRealtimeNanos()
                val pending = goAsync()
                val handler = schedulerHandler
                if (handler == null) {
                    runCatching { pending.finish() }
                    return
                }
                handler.post {
                    try {
                        handleScheduleTrigger(scheduleId, "alarm", receivedAtMs, receivedAtNs)
                    } finally {
                        runCatching { pending.finish() }
                    }
                }
            }
        }
        recoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                recoverSchedules(intent?.action ?: "recovery")
            }
        }

        runCatching {
            // 自有闹钟只允许本应用触发, 防止外部伪造 action 造成重复发送。
            ContextCompat.registerReceiver(
                HostInfo.application,
                alarmReceiver,
                IntentFilter(ALARM_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            WeLogger.e(TAG, "failed to register alarm receiver", it)
        }

        runCatching {
            // 系统广播来自 system UID; NOT_EXPORTED 可能收不到, 单独用 EXPORTED 注册。
            val recoveryFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            ContextCompat.registerReceiver(
                HostInfo.application,
                recoveryReceiver,
                recoveryFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure {
            WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} failed to register schedule recovery receiver", it)
        }

        runCatching { HostInfo.application.registerActivityLifecycleCallbacks(recoveryCallbacks) }
            .onFailure { WeLogger.e(TAG, "${WeLogger.FORK_LOG_PREFIX} failed to register schedule recovery", it) }

        // 先预热再挂任务: 到点任务可能立刻补发, 不能把首次 dex 解析留在热路径。
        runCatching { WeMessageApi.warmUpTextSend() }
            .onFailure { WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} warm up text send handles failed", it) }

        runCatching {
            val enabled = synchronized(storageLock) { schedules }.filter { it.enabled }
            cleanupOrphanAlarms(enabled)
            enabled.forEach { scheduleAlarm(it, allowCatchUp = true) }
        }.onFailure {
            WeLogger.e(TAG, "failed to schedule alarms", it)
        }
        scheduleMonitor()
    }

    /**
     * v310 只持久化 requestCode 序列, 不持久化 id→code; 升级/进程重启后旧 PendingIntent
     * 可能变成孤儿并再次触发。这里在启用时按序列范围清理一次, 只保留已持久化的当前 code。
     */
    private fun cleanupOrphanAlarms(schedules: List<ScheduleConfig>) {
        if (alarmCodeSeq <= 1000 || schedules.isEmpty()) return
        val context = HostInfo.application
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val maxCode = alarmCodeSeq.coerceAtMost(1000 + 2048)
        val keepCodes = schedules.mapNotNull { it.alarmRequestCode.takeIf { code -> code > 0 } }.toSet()
        val intent = Intent(ALARM_ACTION).setPackage(context.packageName)
        for (code in 1001..maxCode) {
            if (code in keepCodes) continue
            val pi = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            runCatching { alarmManager.cancel(pi) }
            pi.cancel()
        }
    }

    private fun startSchedulerThread() {
        stopSchedulerThread()
        val thread = HandlerThread("wekit-scheduled-message").also { it.start() }
        schedulerThread = thread
        schedulerHandler = Handler(thread.looper)
    }

    private fun stopSchedulerThread() {
        monitorRunnable?.let { schedulerHandler?.removeCallbacks(it) }
        monitorRunnable = null
        timerStates.values.forEach { schedulerHandler?.removeCallbacksAndMessages(it.token) }
        timerStates.clear()
        schedulerHandler?.removeCallbacksAndMessages(null)
        schedulerHandler = null
        schedulerPriorityDepth = 0
        schedulerThread?.quitSafely()
        schedulerThread = null
    }

    /** 最近任务感知的守护轮询; 普通任务 30 秒, 临近任务自动提高检查频率。 */
    private fun scheduleMonitor() {
        if (!isActive) return
        val handler = schedulerHandler ?: return
        monitorRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                monitorRunnable = null
                if (!isActive) return
                refreshDayKey()
                sweepSchedules("monitor")
                scheduleMonitor()
            }
        }
        monitorRunnable = runnable
        val now = System.currentTimeMillis()
        val next = runCatching { synchronized(storageLock) { schedules } }
            .getOrDefault(emptyList())
            .filter { it.enabled && it.nextSendTime > now }
            .minOfOrNull { it.nextSendTime }
        val delayMs = when {
            next == null -> MONITOR_INTERVAL_MS
            next <= now -> 0L
            else -> minOf(MONITOR_INTERVAL_MS, maxOf(1_000L, next - now - 1_000L))
        }
        handler.postDelayed(runnable, delayMs)
    }

    private fun sweepSchedules(source: String) {
        if (!isActive) return
        val now = System.currentTimeMillis()
        val list = runCatching { synchronized(storageLock) { schedules } }
            .onFailure { WeLogger.e(TAG, "failed to read schedules in monitor", it) }
            .getOrDefault(emptyList())
        list.filter { it.enabled }.forEach { schedule ->
            runCatching {
                when {
                    schedule.nextSendTime in 1..now -> handleScheduleTrigger(schedule.id, source)
                    schedule.nextSendTime > now && !timerStates.containsKey(schedule.id) ->
                        scheduleInProcess(schedule)
                    schedule.nextSendTime <= 0 -> scheduleAlarm(schedule, allowCatchUp = true)
                }
            }.onFailure {
                WeLogger.e(TAG, "monitor: failed to handle ${schedule.id}", it)
            }
        }
    }

    override fun onDisable() {
        activeSendJobs.values.forEach { it.cancel() }
        activeSendJobs.clear()
        activeAlarms.values.forEach { it.cancel() }
        activeAlarms.clear()
        timerStates.keys.forEach { releaseTimerResources(it, restorePriority = false) }
        runCatching { HostInfo.application.unregisterActivityLifecycleCallbacks(recoveryCallbacks) }
            .onFailure { WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} failed to unregister schedule recovery", it) }
        runCatching {
            HostInfo.application.unregisterReceiver(alarmReceiver)
        }.onFailure {
            WeLogger.e(TAG, "failed to unregister alarm receiver", it)
        }
        runCatching {
            HostInfo.application.unregisterReceiver(recoveryReceiver)
        }.onFailure {
            WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} failed to unregister recovery receiver", it)
        }
        stopSchedulerThread()
    }

    private fun requestCodeFor(schedule: ScheduleConfig): Int {
        alarmCodes[schedule.id]?.let { return it }
        if (schedule.alarmRequestCode > 0) {
            alarmCodes[schedule.id] = schedule.alarmRequestCode
            return schedule.alarmRequestCode
        }
        return synchronized(storageLock) {
            alarmCodes[schedule.id] ?: run {
                val code = alarmCodeSeq + 1
                alarmCodeSeq = code
                schedule.alarmRequestCode = code
                alarmCodes[schedule.id] = code
                updateSchedule(schedule)
                code
            }
        }
    }

    private fun scheduleAlarm(schedule: ScheduleConfig, allowCatchUp: Boolean = false) {
        if (!isActive) return
        val context = HostInfo.application
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val requestCode = requestCodeFor(schedule)
        val intent = Intent(ALARM_ACTION).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = calculateNextTriggerTime(schedule, allowCatchUp)
        if (triggerTime <= 0) {
            if (schedule.enabled) {
                schedule.enabled = false
                synchronized(storageLock) { updateSchedule(schedule) }
                cancelAlarm(schedule)
                WeLogger.i(TAG, "${WeLogger.FORK_LOG_PREFIX} schedule ${schedule.id} retired: no further trigger time")
            }
            return
        }
        schedule.nextSendTime = triggerTime
        synchronized(storageLock) { updateSchedule(schedule) }

        scheduleInProcess(schedule)
        runCatching {
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
            activeAlarms[schedule.id] = pendingIntent
            WeLogger.i(
                TAG,
                "${WeLogger.FORK_LOG_PREFIX} armed ${schedule.id}: next=$triggerTime, exact=$exactAllowed, " +
                        "wall=${System.currentTimeMillis()}, elapsed=${SystemClock.elapsedRealtime()}, " +
                        "uptime=${SystemClock.uptimeMillis()}"
            )
        }.onFailure {
            WeLogger.e(TAG, "${WeLogger.FORK_LOG_PREFIX} failed to set alarm for schedule ${schedule.id}", it)
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                activeAlarms[schedule.id] = pendingIntent
                WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} armed ${schedule.id}: next=$triggerTime, fallback=inexact")
            }.onFailure { e2 ->
                WeLogger.e(TAG, "fallback alarm also failed for ${schedule.id}", e2)
            }
        }
    }

    /**
     * 专用 HandlerThread 上的墙钟定时器: T-250ms 唤醒并短时提权, T-100ms 进入有界自旋。
     * 深睡/改时间后每次等待都重读墙钟; 与 AlarmManager 互为冗余。
     */
    private fun scheduleInProcess(schedule: ScheduleConfig) {
        val handler = schedulerHandler ?: return
        cancelTimer(schedule.id)
        val next = schedule.nextSendTime
        if (next <= System.currentTimeMillis()) {
            handler.post { handleScheduleTrigger(schedule.id, "timer") }
            return
        }
        val state = TimerState(Any())
        timerStates[schedule.id] = state
        val deadline = ScheduledMessageDeadline(next)
        val timer = object : Runnable {
            override fun run() {
                if (!isActive || timerStates[schedule.id] !== state) return
                val waitMs = deadline.nextWaitMillis(
                    System.currentTimeMillis(),
                    System.nanoTime()
                )
                when {
                    waitMs == 0L -> {
                        timerStates.remove(schedule.id, state)
                        releaseTimerResources(schedule.id, restorePriority = true, expected = state)
                        handleScheduleTrigger(schedule.id, "timer")
                        scheduleMonitor()
                    }
                    waitMs > 0L -> handler.postAtTime(
                        this,
                        state.token,
                        SystemClock.uptimeMillis() + waitMs
                    )
                    else -> {
                        // 最后 100ms 在同一个 Runnable 内有界自旋, 避免 Handler 队列往返。
                        while (isActive && timerStates[schedule.id] === state &&
                            deadline.nextWaitMillis(System.currentTimeMillis(), System.nanoTime()) == -1L
                        ) {
                            // busy wait is intentionally bounded by ScheduledMessageDeadline
                        }
                        if (isActive && timerStates[schedule.id] === state) {
                            handler.postAtTime(this, state.token, SystemClock.uptimeMillis())
                        }
                    }
                }
            }
        }
        val wake = object : Runnable {
            override fun run() {
                if (!isActive || timerStates[schedule.id] !== state) return
                prepareTimerThread(state)
                handler.post(timer)
            }
        }
        val wakeDelayMs = maxOf(0L, next - System.currentTimeMillis() - PREWAKE_LEAD_MS)
        handler.postAtTime(wake, state.token, SystemClock.uptimeMillis() + wakeDelayMs)
    }

    private fun prepareTimerThread(state: TimerState) {
        if (state.prepared) return
        state.prepared = true
        runCatching {
            if (schedulerPriorityDepth == 0) {
                schedulerOriginalPriority = Process.getThreadPriority(Process.myTid())
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            }
            schedulerPriorityDepth++
        }.onFailure { WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} scheduler thread priority unavailable", it) }

        val context = HostInfo.application
        if (context.checkSelfPermission(android.Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching
            state.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wcx:scheduled-message").apply {
                setReferenceCounted(false)
                acquire(PREWAKE_WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure {
            WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} scheduled-message WakeLock unavailable", it)
        }
    }

    private fun releaseTimerResources(
        scheduleId: String,
        restorePriority: Boolean,
        expected: TimerState? = null,
    ) {
        val state = timerStates.remove(scheduleId) ?: expected ?: return
        if (expected != null && state !== expected) return
        runCatching { state.wakeLock?.takeIf { it.isHeld }?.release() }
        state.wakeLock = null
        if (state.prepared && restorePriority) {
            val restore = Runnable {
                if (schedulerPriorityDepth > 0) schedulerPriorityDepth--
                if (schedulerPriorityDepth == 0) {
                    runCatching { Process.setThreadPriority(schedulerOriginalPriority) }
                }
            }
            val handler = schedulerHandler
            if (schedulerThread === Thread.currentThread() || handler == null) {
                restore.run()
            } else {
                handler.post(restore)
            }
        }
        schedulerHandler?.removeCallbacksAndMessages(state.token)
    }

    private fun cancelTimer(scheduleId: String) {
        releaseTimerResources(scheduleId, restorePriority = true)
    }

    private fun refreshDayKey(): String {
        val today = LocalDate.now().toString()
        if (currentDayKey != today) currentDayKey = today
        return currentDayKey
    }

    private fun todayKey(): String = currentDayKey

    private fun tomorrowTarget(schedule: ScheduleConfig): Long {
        val targetTime = LocalTime.of(schedule.hour, schedule.minute)
        return java.time.LocalDateTime.now().plusDays(1).with(targetTime)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun todayTargetOf(schedule: ScheduleConfig): Long {
        val targetTime = LocalTime.of(schedule.hour, schedule.minute)
        return java.time.LocalDateTime.now().with(targetTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun calculateNextTriggerTime(schedule: ScheduleConfig, allowCatchUp: Boolean): Long {
        val now = System.currentTimeMillis()
        val today = refreshDayKey()
        val todayTarget = todayTargetOf(schedule)

        if (todayTarget > now) return todayTarget

        // nextSendTime==0 表示从未排期; 不要凭空补发几小时前的旧任务。
        val wasScheduled = schedule.nextSendTime > 0 && schedule.nextSendTime <= now
        if (!wasScheduled) {
            return if (schedule.repeatDaily) tomorrowTarget(schedule) else -1L
        }
        if (ScheduledMessagePlan.alreadyFiredToday(schedule.lastFiredDate, today)) {
            return if (schedule.repeatDaily) tomorrowTarget(schedule) else -1L
        }
        if (!ScheduledMessagePlan.canFire(
                schedule.fireAttemptDate,
                schedule.fireAttempts,
                today,
                MAX_FIRE_ATTEMPTS
            )
        ) {
            WeLogger.w(TAG, "schedule ${schedule.id} exhausted attempts today, giving up")
            return if (schedule.repeatDaily) tomorrowTarget(schedule) else -1L
        }
        val windowMs = if (allowCatchUp) CATCHUP_WINDOW_MS else RETRY_WINDOW_MS
        if (!ScheduledMessagePlan.isWithinCatchupWindow(now, schedule.nextSendTime, windowMs)) {
            return if (schedule.repeatDaily) tomorrowTarget(schedule) else -1L
        }
        return ScheduledMessagePlan.retryAt(
            now,
            schedule.repeatDaily,
            2_000L,
            ONETIME_RETRY_DELAY_MS
        )
    }

    private fun handleScheduleTrigger(
        scheduleId: String,
        source: String,
        receivedAtMs: Long = 0,
        receivedAtNs: Long = 0,
    ) {
        if (!isActive) return
        val entryNs = SystemClock.elapsedRealtimeNanos()
        val entryUptimeMs = SystemClock.uptimeMillis()
        val nowMs = System.currentTimeMillis()
        val version = storageVersion
        val schedule = synchronized(storageLock) { schedules.find { it.id == scheduleId } } ?: return
        if (!schedule.enabled) return

        // 只允许到点后执行; 过早广播直接丢弃, 不进入热路径。
        if (schedule.nextSendTime > 0 && nowMs < schedule.nextSendTime - 2_000L) {
            WeLogger.i(
                TAG,
                "${WeLogger.FORK_LOG_PREFIX} trigger for ${schedule.id} too early, skipping " +
                        "(next=${schedule.nextSendTime}, now=$nowMs, source=$source)"
            )
            return
        }
        if (!triggering.add(scheduleId)) {
            WeLogger.i(TAG, "${WeLogger.FORK_LOG_PREFIX} trigger for ${schedule.id} already in flight, skipping duplicate")
            return
        }
        try {
            // 版本未变时复用首份快照, 避免第二次 JSON 解码; 并发写入才重读。
            val fresh = synchronized(storageLock) {
                if (storageVersion == version) schedule else schedules.find { it.id == scheduleId }
            } ?: return
            if (!fresh.enabled || !isActive) return
            if (fresh.nextSendTime != schedule.nextSendTime) return

            val today = refreshDayKey()
            if (ScheduledMessagePlan.alreadyFiredToday(fresh.lastFiredDate, today)) {
                advanceAfterTrigger(fresh, sentOk = true)
                return
            }
            if (!ScheduledMessagePlan.canFire(
                    fresh.fireAttemptDate,
                    fresh.fireAttempts,
                    today,
                    MAX_FIRE_ATTEMPTS
                )
            ) {
                WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} schedule ${fresh.id} exhausted attempts today")
                advanceAfterTrigger(fresh, sentOk = false)
                return
            }

            val oldCompletedOnPastDay = fresh.fireAttemptDate.isEmpty() &&
                    fresh.lastFiredDate.isNotEmpty() && fresh.lastFiredDate != today
            val newDay = (fresh.fireAttemptDate.isNotEmpty() && fresh.fireAttemptDate != today) ||
                    oldCompletedOnPastDay
            if (newDay) {
                fresh.sentTargets = emptyList()
                fresh.sentSegments = emptyMap()
            }
            fresh.fireAttempts = ScheduledMessagePlan.effectiveFireAttempts(
                fresh.fireAttemptDate,
                fresh.fireAttempts,
                today
            ) + 1
            fresh.fireAttemptDate = today

            val targets = fresh.targetTalkers()
            val fastText = when {
                fresh.segments.size == 1 &&
                        (fresh.segments[0].type == MessageType.TEXT || fresh.segments[0].type == MessageType.LINK) &&
                        targets.size == 1 -> fresh.segments[0].content
                fresh.segments.isEmpty() &&
                        (fresh.messageType == MessageType.TEXT || fresh.messageType == MessageType.LINK) &&
                        targets.size == 1 -> fresh.content
                else -> null
            }
            if (fastText != null) {
                val target = targets.first()
                val probe = sendTextSafelyProbe(target, fastText)
                queueTimingStats.add(probe.measuredNs)
                if (probe.accepted) markLegacySegmentSent(fresh, target, 0)
                finishTrigger(
                    schedule = fresh,
                    sentOk = probe.accepted,
                    source = source,
                    targetNextSendTime = schedule.nextSendTime,
                    entryNs = entryNs,
                    entryUptimeMs = entryUptimeMs,
                    receivedAtMs = receivedAtMs,
                    receivedAtNs = receivedAtNs,
                    getterNs = probe.getterNs,
                    constructNs = probe.constructNs,
                    queueNs = probe.queueNs,
                    queueMeasured = true,
                    queueEndWallMs = probe.queueEndWallMs,
                )
            } else {
                val job = scope.launch {
                    try {
                        val sentOk = if (fresh.segments.isNotEmpty()) {
                            sendSegments(fresh)
                        } else {
                            sendLegacySingle(fresh)
                        }
                        finishTrigger(
                            schedule = fresh,
                            sentOk = sentOk,
                            source = source,
                            targetNextSendTime = schedule.nextSendTime,
                            entryNs = entryNs,
                            entryUptimeMs = entryUptimeMs,
                            receivedAtMs = receivedAtMs,
                            receivedAtNs = receivedAtNs,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "${WeLogger.FORK_LOG_PREFIX} complex schedule ${fresh.id} failed", e)
                    } finally {
                        triggering.remove(scheduleId)
                        activeSendJobs.remove(scheduleId)
                    }
                }
                activeSendJobs[scheduleId] = job
            }
        } catch (e: Throwable) {
            triggering.remove(scheduleId)
            WeLogger.e(TAG, "${WeLogger.FORK_LOG_PREFIX} schedule trigger ${schedule.id} failed", e)
        }
    }

    private fun finishTrigger(
        schedule: ScheduleConfig,
        sentOk: Boolean,
        source: String,
        targetNextSendTime: Long,
        entryNs: Long,
        entryUptimeMs: Long,
        receivedAtMs: Long,
        receivedAtNs: Long,
        getterNs: Long = 0,
        constructNs: Long = 0,
        queueNs: Long = 0,
        queueMeasured: Boolean = false,
        queueEndWallMs: Long = 0,
    ) {
        val persistStartNs = SystemClock.elapsedRealtimeNanos()
        if (sentOk) {
            schedule.lastFiredDate = todayKey()
            schedule.fireAttempts = 0
        }
        advanceAfterTrigger(schedule, sentOk)
        val persistUs = (SystemClock.elapsedRealtimeNanos() - persistStartNs) / 1_000L
        val endWallMs = System.currentTimeMillis()
        val dispatchUs = if (receivedAtNs > 0) (entryNs - receivedAtNs) / 1_000L else -1L
        val entryToQueueText = if (queueMeasured) {
            "${(getterNs + constructNs + queueNs) / 1_000L}us"
        } else {
            "n/a"
        }
        val queueCallText = if (queueMeasured) "${queueNs / 1_000L}us" else "n/a"
        val getterText = if (queueMeasured) "${getterNs / 1_000L}us" else "n/a"
        val constructText = if (queueMeasured) "${constructNs / 1_000L}us" else "n/a"
        val queueDriftText = if (queueEndWallMs > 0) "${queueEndWallMs - targetNextSendTime}ms" else "n/a"
        WeLogger.i(
            TAG,
            "${WeLogger.FORK_LOG_PREFIX} trigger ${schedule.id} probe: source=$source, " +
                    "queueDrift=$queueDriftText, finishDrift=${endWallMs - targetNextSendTime}ms, " +
                    "dispatch=${dispatchUs}us, " +
                    "entryToQueue=$entryToQueueText, queueCall=$queueCallText, getter=$getterText, " +
                    "construct=$constructText, persist=${persistUs}us, " +
                    "sent=$sentOk, alarmReceivedAt=$receivedAtMs, wall=$endWallMs, " +
                    "elapsed=${entryNs / 1_000_000L}, uptime=$entryUptimeMs, " +
                    "p50=${queueTimingStats.percentile(50.0) / 1_000L}us, " +
                    "p95=${queueTimingStats.percentile(95.0) / 1_000L}us, " +
                    "p99=${queueTimingStats.percentile(99.0) / 1_000L}us, " +
                    "max=${queueTimingStats.max() / 1_000L}us"
        )
    }

    /**
     * 逐段发送: 每段独立成败互不影响。
     * 原实现把整段循环包在一个 runCatching 里, 第一段(常见于图片/视频)一旦抛异常,
     * 后面的文本段就全都发不出去了 —— 用户看到的就是"整条定时消息没发出来"。
     * 媒体段常常只是文件还没落地/CDN 未就绪, 因此对失败段退避重试一次。
     *
     * 定时任务可以勾选多个接收方: 逐个接收方依次发送, 每个接收方内部按消息段顺序发,
     * 保证"每人先收到第1段、再收到第2段"。当天已成功的接收方会被跳过, 避免重试时重复发送。
     */
    private suspend fun sendSegments(schedule: ScheduleConfig): Boolean {
        var allOk = true
        val targets = schedule.targetTalkers()
        if (targets.isEmpty()) {
            WeLogger.e(TAG, "schedule ${schedule.id} has no target talker")
            return false
        }
        for ((targetIndex, target) in targets.withIndex()) {
            if (!isActive) return false
            if (target in schedule.sentTargets ||
                ScheduledMessageProgress.isTargetComplete(schedule.sentSegments, target, schedule.segments.size)
            ) {
                markTargetComplete(schedule, target, schedule.segments.size)
                continue
            }
            val ok = sendSegmentsTo(schedule, target)
            if (ok) {
                markTargetComplete(schedule, target, schedule.segments.size)
            } else {
                WeLogger.e(TAG, "${WeLogger.FORK_LOG_PREFIX} schedule ${schedule.id}: target $target failed")
                allOk = false
            }
            if (targetIndex < targets.lastIndex) delay(TARGET_GAP_MS)
        }
        return allOk
    }

    /** 把尚未成功提交的消息段发给单个接收方; 已成功段直接跳过。 */
    private suspend fun sendSegmentsTo(schedule: ScheduleConfig, target: String): Boolean {
        var allOk = true
        schedule.segments.forEachIndexed { index, segment ->
            if (!isActive) return false
            if (ScheduledMessageProgress.isSegmentSent(schedule.sentSegments, target, index)) {
                return@forEachIndexed
            }
            val ok = runCatching { sendSegment(target, segment) }
                .onFailure { WeLogger.e(TAG, "segment $index (${segment.type}) threw", it) }
                .getOrDefault(false)
            if (!ok) {
                WeLogger.w(TAG, "${WeLogger.FORK_LOG_PREFIX} segment $index (${segment.type}) failed, retrying once")
                delay(SEGMENT_RETRY_DELAY_MS)
                val retried = runCatching { sendSegment(target, segment) }
                    .onFailure { WeLogger.e(TAG, "segment $index (${segment.type}) retry threw", it) }
                    .getOrDefault(false)
                if (!retried) {
                    WeLogger.e(TAG, "${WeLogger.FORK_LOG_PREFIX} segment $index (${segment.type}) gave up for ${schedule.id}")
                    allOk = false
                    return@forEachIndexed
                }
            }
            markSegmentSent(schedule, target, index)
            if (index < schedule.segments.lastIndex) delay(500)
        }
        return allOk
    }

    private fun markSegmentSent(schedule: ScheduleConfig, target: String, segmentIndex: Int) {
        val merged = synchronized(storageLock) {
            val current = schedules.find { it.id == schedule.id } ?: return
            if (!current.enabled) return
            current.sentSegments = ScheduledMessageProgress.markSegmentSent(
                current.sentSegments,
                target,
                segmentIndex
            )
            updateSchedule(current)
            current.sentSegments
        }
        schedule.sentSegments = merged
    }

    private fun markTargetComplete(schedule: ScheduleConfig, target: String, segmentCount: Int) {
        synchronized(storageLock) {
            val current = schedules.find { it.id == schedule.id } ?: return
            if (!current.enabled) return
            if (!ScheduledMessageProgress.isTargetComplete(current.sentSegments, target, segmentCount)) return
            if (target !in current.sentTargets) {
                current.sentTargets = (current.sentTargets + target).distinct()
                updateSchedule(current)
            }
            schedule.sentTargets = current.sentTargets
        }
    }

    private fun markLegacySegmentSent(schedule: ScheduleConfig, target: String, segmentIndex: Int) {
        markSegmentSent(schedule, target, segmentIndex)
        markTargetComplete(schedule, target, 1)
    }

    /** 触发结束后的收尾: 落库 -> 单次任务关闭 -> 重排下一次(含失败重试) */
    private fun advanceAfterTrigger(schedule: ScheduleConfig, sentOk: Boolean) {
        runCatching {
            val current = synchronized(storageLock) {
                val stored = schedules.find { it.id == schedule.id } ?: return@synchronized null
                if (!stored.enabled) return@synchronized null
                stored.lastFiredDate = schedule.lastFiredDate
                stored.fireAttempts = schedule.fireAttempts
                stored.fireAttemptDate = schedule.fireAttemptDate
                stored.sentTargets = schedule.sentTargets
                stored.sentSegments = schedule.sentSegments
                if (schedule.oneTimeOnly && sentOk) stored.enabled = false
                updateSchedule(stored)
                stored
            } ?: run {
                cancelAlarm(schedule)
                return
            }
            if (!isActive || !current.enabled) {
                cancelAlarm(current)
                return
            }
            if (current.oneTimeOnly && sentOk) {
                cancelAlarm(current)
                return
            }
            scheduleAlarm(current)
        }.onFailure {
            WeLogger.e(TAG, "failed to advance schedule ${schedule.id}", it)
        }
    }

    /** 返回 true 表示这一段确实发出去了; false 交给上层决定是否重试 */
    private fun sendSegment(talker: String, segment: MessageSegment): Boolean {
        return when (segment.type) {
            MessageType.TEXT -> sendTextSafely(talker, segment.content)
            MessageType.LINK -> sendTextSafely(talker, segment.content)
            MessageType.IMAGE -> {
                when {
                    segment.filePath.isNotBlank() ->
                        runCatching { WeMessageApi.sendImage(talker, segment.filePath) }
                            .onFailure { WeLogger.e(TAG, "send image failed (path=${segment.filePath})", it) }
                            .getOrDefault(false)
                    segment.content.startsWith(DEFERRED_IMAGE_PREFIX) -> {
                        // 旧版本创建的延迟段: svrId 存在 content 里
                        val svrId = segment.content.removePrefix(DEFERRED_IMAGE_PREFIX).toLongOrNull() ?: 0
                        sendImageByReference(talker, segment.srcTalker.ifEmpty { talker }, svrId, segment.srcMsgId)
                    }
                    segment.srcSvrId > 0 || segment.srcMsgId > 0 ->
                        // 复读式引用段: 与转发功能同一通道, 发送时从聊天记录现场解析
                        sendImageByReference(talker, segment.srcTalker.ifEmpty { talker }, segment.srcSvrId, segment.srcMsgId)
                    else -> {
                        WeLogger.e(TAG, "image segment has neither path nor source reference")
                        false
                    }
                }
            }
            MessageType.VOICE -> {
                if (segment.filePath.isNotBlank()) {
                    runCatching { WeMessageApi.sendVoice(talker, segment.filePath, segment.duration) }
                        .onFailure { WeLogger.e(TAG, "send voice failed (path=${segment.filePath})", it) }
                        .getOrDefault(false)
                } else if (segment.srcSvrId > 0 || segment.srcMsgId > 0) {
                    sendVoiceByReference(talker, segment.srcTalker.ifEmpty { talker }, segment)
                } else {
                    WeLogger.e(TAG, "voice segment has neither path nor source reference")
                    false
                }
            }
            MessageType.VIDEO -> {
                if (segment.filePath.isNotBlank()) {
                    runCatching { WeMessageApi.sendVideo(talker, segment.filePath) }
                        .onFailure { WeLogger.e(TAG, "send video failed (path=${segment.filePath})", it) }
                        .getOrDefault(false)
                } else if (segment.srcSvrId > 0 || segment.srcMsgId > 0) {
                    sendVideoByReference(talker, segment.srcTalker.ifEmpty { talker }, segment)
                } else {
                    WeLogger.e(TAG, "video segment has neither path nor source reference")
                    false
                }
            }
            MessageType.FILE -> {
                when {
                    segment.filePath.isNotBlank() -> {
                        val fileName = segment.filePath.substringAfterLast('/')
                        runCatching { WeMessageApi.sendFile(talker, segment.filePath, fileName) }
                            .onFailure { WeLogger.e(TAG, "send file failed (path=${segment.filePath})", it) }
                            .getOrDefault(false)
                    }
                    segment.srcSvrId > 0 || segment.srcMsgId > 0 ->
                        // 复读式引用段: 发送时现场触发文件下载 (与用户手动下载文件同机制)
                        sendFileByReference(talker, segment.srcTalker.ifEmpty { talker }, segment)
                    else -> {
                        WeLogger.e(TAG, "file segment has neither path nor source reference")
                        false
                    }
                }
            }
            MessageType.FAVORITE -> sendFavoriteSegment(talker, segment)
        }
    }

    /**
     * 收藏段: 交给微信原生收藏发送通道 (与 Hchat 定时任务的 TYPE_FAVORITE 同一条路径)。
     * content 存收藏 localId。图片/语音/视频/文件等各种类型均由微信自己处理,
     * 我们无需解析 favProto 或定位媒体文件。
     */
    private fun sendFavoriteSegment(talker: String, segment: MessageSegment): Boolean {
        val localId = segment.content.trim().toLongOrNull()
        if (localId == null || localId <= 0L) {
            WeLogger.e(TAG, "favorite segment has invalid localId: ${segment.content}")
            return false
        }
        return runCatching {
            h.Hchat.hooks.api.core.WeChatApis.media()?.favorites()?.send(talker, localId) == true
        }.onFailure {
            WeLogger.e(TAG, "send favorite failed (localId=$localId)", it)
        }.getOrDefault(false)
    }

    private fun sendTextSafelyProbe(talker: String, content: String): WeMessageApi.TextSendProbe {
        if (content.isBlank()) {
            return WeMessageApi.TextSendProbe(true, 0L, 0L, 0L, 0L)
        }
        return runCatching { WeMessageApi.sendTextProbe(talker, content) }
            .onFailure { WeLogger.e(TAG, "send text failed (talker=$talker)", it) }
            .getOrElse {
                WeMessageApi.TextSendProbe(false, 0L, 0L, 0L, 0L)
            }
    }

    private fun sendTextSafely(talker: String, content: String): Boolean =
        sendTextSafelyProbe(talker, content).accepted

    /**
     * 复读式发送: 按引用重建权威消息对象后, 走与 ForwardMessages(转发) 完全相同的通道
     * (图片 md5 / 语音本地 silk / 视频本地 mp4), 媒体在发送时刻从聊天记录解析。
     * 本地缓存已被清理时退回 CDN 下载。
     */
    private fun resolveSourceInstance(srcTalker: String, svrId: Long, msgId: Long): Any? {
        return runCatching {
            when {
                svrId > 0 -> WeMessageApi.getMsgInfoInstanceByMsgSvrId(svrId, srcTalker.takeIf { it.isNotEmpty() })
                msgId > 0 -> WeMessageApi.getMsgInfoInstanceByMsgId(msgId)
                else -> null
            }
        }.onFailure {
            WeLogger.w(TAG, "resolve source instance failed (svrId=$svrId, msgId=$msgId)", it)
        }.getOrNull()
    }

    private fun sendImageByReference(talker: String, srcTalker: String, svrId: Long, msgId: Long): Boolean {
        val instance = resolveSourceInstance(srcTalker, svrId, msgId)
        if (instance != null) {
            runCatching {
                val md5 = WeServiceApi.getImageMd5FromMsgInfo(MessageInfo(instance))
                if (md5.isNotBlank()) {
                    WeMessageApi.sendImageByMd5(talker, md5, null)
                    return true
                }
                WeLogger.w(TAG, "repeat-path image md5 blank (svrId=$svrId)")
            }.onFailure {
                WeLogger.w(TAG, "repeat-path image via md5 failed, fallback to cdn (svrId=$svrId)", it)
            }
        }
        // 本地缓存已清理等场景: 退回 CDN 下载
        val path = svrId.takeIf { it > 0 }?.let { WeMessageApi.downloadImage(it) }
        if (path != null) {
            return runCatching { WeMessageApi.sendImage(talker, path) }
                .onFailure { WeLogger.e(TAG, "send image failed (path=$path)", it) }
                .getOrDefault(false)
        }
        WeLogger.e(TAG, "deferred image failed at send time (svrId=$svrId, msgId=$msgId)")
        return false
    }

    private fun sendVoiceByReference(talker: String, srcTalker: String, segment: MessageSegment): Boolean {
        val instance = resolveSourceInstance(srcTalker, segment.srcSvrId, segment.srcMsgId)
        if (instance == null) {
            WeLogger.e(TAG, "voice source not found at send time (svrId=${segment.srcSvrId}, msgId=${segment.srcMsgId})")
            return false
        }
        return runCatching {
            val msgInfo = MessageInfo(instance)
            val encPath = msgInfo.imagePath ?: error("voice encPath missing")
            val voicePath = WeMessageApi.getVoiceFullPath(encPath)
            // 微信语音仅在落盘后可重发; 文件不存在时 getDurationMs(JNI) 行为不可控, 先行拦截
            if (!java.io.File(voicePath).exists()) error("voice file not on disk at send time: $voicePath")
            val durationMs = segment.duration.takeIf { it > 0 }
                ?: AudioUtils.getDurationMs(voicePath).toInt()
            if (durationMs <= 0) error("voice duration unavailable at send time: $voicePath")
            WeMessageApi.sendVoice(talker, voicePath, durationMs)
        }.onFailure {
            WeLogger.e(TAG, "repeat-path voice failed (svrId=${segment.srcSvrId})", it)
        }.getOrDefault(false)
    }

    private fun sendVideoByReference(talker: String, srcTalker: String, segment: MessageSegment): Boolean {
        val instance = resolveSourceInstance(srcTalker, segment.srcSvrId, segment.srcMsgId)
        if (instance == null) {
            WeLogger.e(TAG, "video source not found at send time (svrId=${segment.srcSvrId}, msgId=${segment.srcMsgId})")
            return false
        }
        return runCatching {
            val msgInfo = MessageInfo(instance)
            val mp4Path = WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo)
            if (mp4Path.isBlank()) error("video mp4 path blank")
            if (!java.io.File(mp4Path).exists()) error("video mp4 not on disk at send time: $mp4Path")
            WeMessageApi.sendVideo(talker, mp4Path)
        }.onFailure {
            WeLogger.e(TAG, "repeat-path video failed (svrId=${segment.srcSvrId})", it)
        }.getOrDefault(false)
    }

    private fun sendFileByReference(talker: String, srcTalker: String, segment: MessageSegment): Boolean {
        val instance = resolveSourceInstance(srcTalker, segment.srcSvrId, segment.srcMsgId)
        if (instance == null) {
            WeLogger.e(TAG, "file source not found at send time (svrId=${segment.srcSvrId}, msgId=${segment.srcMsgId})")
            return false
        }
        return runCatching {
            val path = WeMessageApi.downloadFile(instance) ?: error("file download failed at send time")
            WeMessageApi.sendFile(talker, path, path.substringAfterLast('/'))
        }.onFailure {
            WeLogger.e(TAG, "repeat-path file failed (svrId=${segment.srcSvrId})", it)
        }.getOrDefault(false)
    }

    /** 老版本创建的单条任务(无 segments) */
    private suspend fun sendLegacySingle(schedule: ScheduleConfig): Boolean {
        val targets = schedule.targetTalkers()
        if (targets.isEmpty()) {
            WeLogger.e(TAG, "legacy schedule ${schedule.id} has no target talker")
            return false
        }
        var allOk = true
        for ((targetIndex, target) in targets.withIndex()) {
            if (!isActive) return false
            if (target in schedule.sentTargets ||
                ScheduledMessageProgress.isTargetComplete(schedule.sentSegments, target, 1)
            ) {
                markLegacySegmentSent(schedule, target, 0)
                continue
            }
            val ok = sendLegacySingleTo(schedule, target)
            if (ok) {
                markLegacySegmentSent(schedule, target, 0)
            } else {
                WeLogger.e(TAG, "${WeLogger.FORK_LOG_PREFIX} legacy schedule ${schedule.id}: target $target failed")
                allOk = false
            }
            if (targetIndex < targets.lastIndex) delay(TARGET_GAP_MS)
        }
        return allOk
    }

    /** 老版本单条任务对单个接收方的发送 */
    private fun sendLegacySingleTo(schedule: ScheduleConfig, talker: String): Boolean {
        return when (schedule.messageType) {
            MessageType.TEXT -> sendTextSafely(talker, schedule.content)
            MessageType.LINK -> sendTextSafely(talker, schedule.content)
            MessageType.IMAGE -> {
                if (schedule.filePath.isBlank()) {
                    WeLogger.e(TAG, "legacy image schedule has no file path")
                    return false
                }
                runCatching { WeMessageApi.sendImage(talker, schedule.filePath) }
                    .onFailure { WeLogger.e(TAG, "legacy image send failed", it) }
                    .getOrDefault(false)
            }
            MessageType.VOICE -> {
                if (schedule.filePath.isBlank()) {
                    WeLogger.e(TAG, "legacy voice schedule has no file path")
                    return false
                }
                runCatching { WeMessageApi.sendVoice(talker, schedule.filePath, schedule.duration) }
                    .onFailure { WeLogger.e(TAG, "legacy voice send failed", it) }
                    .getOrDefault(false)
            }
            MessageType.VIDEO -> {
                if (schedule.filePath.isBlank()) {
                    WeLogger.e(TAG, "legacy video schedule has no file path")
                    return false
                }
                runCatching { WeMessageApi.sendVideo(talker, schedule.filePath) }
                    .onFailure { WeLogger.e(TAG, "legacy video send failed", it) }
                    .getOrDefault(false)
            }
            MessageType.FILE -> {
                if (schedule.filePath.isBlank()) {
                    WeLogger.e(TAG, "legacy file schedule has no file path")
                    return false
                }
                val fileName = schedule.filePath.substringAfterLast('/')
                runCatching { WeMessageApi.sendFile(talker, schedule.filePath, fileName) }
                    .onFailure { WeLogger.e(TAG, "legacy file send failed", it) }
                    .getOrDefault(false)
            }
            MessageType.FAVORITE -> sendFavoriteSegment(
                talker,
                MessageSegment(type = MessageType.FAVORITE, content = schedule.content)
            )
        }
    }

    private fun cancelAlarm(schedule: ScheduleConfig) {
        cancelTimer(schedule.id)
        val pi = activeAlarms.remove(schedule.id) ?: run {
            if (schedule.alarmRequestCode <= 0) return
            val intent = Intent(ALARM_ACTION).apply {
                setPackage(HostInfo.application.packageName)
                putExtra(EXTRA_SCHEDULE_ID, schedule.id)
            }
            PendingIntent.getBroadcast(
                HostInfo.application,
                schedule.alarmRequestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        } ?: return
        pi.cancel()
        runCatching {
            val am = HostInfo.application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        }.onFailure { WeLogger.w(TAG, "cancel alarm failed for ${schedule.id}", it) }
    }

    fun addSchedule(schedule: ScheduleConfig) {
        synchronized(storageLock) {
            schedules = schedules + schedule
        }
        if (schedule.enabled) {
            scheduleAlarm(schedule)
        }
    }

    fun getSchedulesFor(talker: String): List<ScheduleConfig> {
        // 多接收方任务: 只要包含该会话就算命中, 否则"取消定时发送"会漏掉群发任务
        return synchronized(storageLock) { schedules.filter { talker in it.targetTalkers() } }
    }

    private fun updateSchedule(schedule: ScheduleConfig) {
        schedules = schedules.map { if (it.id == schedule.id) schedule else it }
    }

    /** 任务列表标题: 单个接收方显示名字, 多个显示「A、B 等N个对象」 */
    private fun scheduleTitle(schedule: ScheduleConfig): String {
        val names = schedule.targetNames()
        return when {
            names.isEmpty() -> "未选择发送对象"
            names.size == 1 -> names.first()
            else -> "${names.take(2).joinToString("、")} 等${names.size}个对象"
        }
    }

    fun deleteSchedule(schedule: ScheduleConfig) {
        cancelAlarm(schedule)
        synchronized(storageLock) {
            schedules = schedules.filter { it.id != schedule.id }
        }
    }





    @OptIn(ExperimentalMaterial3Api::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var showAddDialog by remember { mutableStateOf(false) }
            var showEditDialog by remember { mutableStateOf(false) }
            var editingSchedule by remember { mutableStateOf<ScheduleConfig?>(null) }
            // 本地可观察快照: schedules 每次访问都会重新解析 JSON, 不是可观察状态,
            // 开关/增删改后必须刷新本快照才能驱动列表 UI 即时重组
            var taskItems by remember { mutableStateOf(synchronized(storageLock) { schedules }) }
            fun refreshTasks() { taskItems = synchronized(storageLock) { schedules } }

            if (showAddDialog) {
                ScheduleEditorDialog(
                    onDismiss = { showAddDialog = false },
                    onSave = { schedule ->
                        addSchedule(schedule)
                        showToast("定时任务已添加")
                        showAddDialog = false
                        refreshTasks()
                    }
                )
            } else if (showEditDialog && editingSchedule != null) {
                ScheduleEditorDialog(
                    onDismiss = { showEditDialog = false },
                    onSave = { schedule ->
                        updateSchedule(schedule)
                        // 编辑后重新计算并设置闹钟（时间可能已变更）；关闭时务必撤掉旧闹钟
                        if (schedule.enabled) scheduleAlarm(schedule) else cancelAlarm(schedule)
                        showToast("定时任务已更新")
                        showEditDialog = false
                        refreshTasks()
                    },
                    existing = editingSchedule!!
                )
            } else {
                AlertDialogContent(
                    title = { Text("定时发送消息") },
                    text = {
                        DefaultColumn(scrollable = true) {
                            if (taskItems.isEmpty()) {
                                Text(
                                    "还没有定时任务，点击下方按钮添加",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            } else {
                                taskItems.forEach { schedule ->
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            editingSchedule = schedule
                                            showEditDialog = true
                                        },
                                        headlineContent = { Text(scheduleTitle(schedule)) },
                                        supportingContent = {
                                            val summary = if (schedule.segments.isNotEmpty()) {
                                                segmentsSummary(schedule.segments)
                                            } else {
                                                schedule.messageType.description
                                            }
                                            // 有主题时前置显示，便于对应消息菜单创建的任务
                                            val subjectPrefix =
                                                if (schedule.subject.isBlank()) "" else "「${schedule.subject}」"
                                            Text(
                                                "$subjectPrefix${schedule.hour.toString().padStart(2, '0')}:${schedule.minute.toString().padStart(2, '0')} " +
                                                        "${if (schedule.repeatDaily) "每天" else "单次"} $summary"
                                            )
                                        },
                                        trailingContent = {
                                            Switch(
                                                checked = schedule.enabled,
                                                onCheckedChange = { enabled ->
                                                    // 先写本地副本并持久化, 再刷新快照驱动开关即时翻转
                                                    val updated = schedule.copy(enabled = enabled)
                                                    if (enabled) {
                                                        scheduleAlarm(updated)
                                                    } else {
                                                        cancelAlarm(updated)
                                                    }
                                                    updateSchedule(updated)
                                                    taskItems = taskItems.map {
                                                        if (it.id == updated.id) updated else it
                                                    }
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
                    confirmButton = {
                        Button(onClick = { showAddDialog = true }) { Text("添加任务") }
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ScheduleEditorDialog(
        onDismiss: () -> Unit,
        onSave: (ScheduleConfig) -> Unit,
        existing: ScheduleConfig? = null
    ) {
        val isEditing = existing != null
        val initialSegments = remember {
            existing?.let {
                if (it.segments.isNotEmpty()) {
                    it.segments
                } else if (it.content.isNotBlank() || it.filePath.isNotBlank()) {
                    listOf(
                        MessageSegment(
                            type = it.messageType,
                            content = it.content,
                            filePath = it.filePath,
                            duration = it.duration
                        )
                    )
                } else {
                    emptyList()
                }
            } ?: emptyList()
        }

        // 与长按菜单端「定时发送」共用同一个编辑器组件, 两个入口功能完全一致:
        // 发送对象(可多选) / 消息段列表 / 添加消息段 / 从收藏添加 / 发送时间 / 每天重复 / 启用。
        val initialTargets = remember {
            existing?.let {
                val extras = it.extraTalkers.mapIndexed { index, wxId ->
                    ScheduleTarget(wxId, it.extraTalkerNames.getOrNull(index).orEmpty())
                }
                listOf(ScheduleTarget(it.talker, it.talkerName)) + extras
            } ?: emptyList()
        }

        ScheduleTaskEditor(
            title = if (isEditing) "编辑定时任务" else "添加定时任务",
            initialTargets = initialTargets,
            initialSegments = initialSegments,
            initialHour = existing?.hour ?: 9,
            initialMinute = existing?.minute ?: 0,
            initialRepeatDaily = existing?.repeatDaily ?: true,
            initialEnabled = existing?.enabled ?: true,
            subjectRequired = false,
            onDismiss = onDismiss,
            onConfirm = { draft ->
                if (draft.targets.isEmpty() || draft.talker.isBlank()) {
                    showToast("请选择发送对象")
                    return@ScheduleTaskEditor
                }
                if (draft.segments.isEmpty()) {
                    showToast("请至少添加一个消息段")
                    return@ScheduleTaskEditor
                }
                draft.segments.forEachIndexed { index, segment ->
                    val valid = when (segment.type) {
                        MessageType.TEXT, MessageType.LINK, MessageType.FAVORITE -> segment.content.isNotBlank()
                        MessageType.IMAGE, MessageType.VOICE,
                        MessageType.VIDEO, MessageType.FILE -> segment.filePath.isNotBlank()
                    }
                    if (!valid) {
                        showToast("第${index + 1}段消息内容无效")
                        return@ScheduleTaskEditor
                    }
                }

                onSave(
                    ScheduleConfig(
                        id = existing?.id ?: "${System.currentTimeMillis()}",
                        talker = draft.talker,
                        talkerName = draft.talkerName,
                        extraTalkers = draft.extraTalkers,
                        extraTalkerNames = draft.extraTalkerNames,
                        subject = draft.subject,
                        messageType = existing?.messageType ?: MessageType.TEXT,
                        content = existing?.content ?: "",
                        filePath = existing?.filePath ?: "",
                        duration = existing?.duration ?: 0,
                        hour = draft.hour,
                        minute = draft.minute,
                        repeatDaily = draft.repeatDaily,
                        // 关闭"每天重复"即为一次性任务：发送一次后自动停用
                        oneTimeOnly = !draft.repeatDaily,
                        enabled = draft.enabled,
                        segments = draft.segments
                    )
                )
            }
        )
    }

    @Composable
    private fun Spacer(modifier: Modifier) {
        androidx.compose.foundation.layout.Spacer(modifier)
    }
}
