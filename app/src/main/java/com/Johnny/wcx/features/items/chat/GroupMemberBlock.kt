package com.Johnny.wcx.features.items.chat

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 群内屏蔽指定成员。
 *
 * 只在本机生效：名单里的人在指定群里发的消息不会入库，因此聊天界面、通知、会话列表都看不到，
 * 对方和群主完全无感（不改服务器、不退群、不踢人）。
 *
 * ## 两种模式
 * - **完全隐藏**（m=0）：直接丢弃，连日志都不留；
 * - **静默**（m=1）：同样丢弃，但把「谁在哪个群发了什么类型的消息」记进本地面板，事后可查。
 *
 * ## 为什么挂在 insert 上
 * 消息一旦入库就会同时进入聊天列表、会话摘要、通知和多端同步，事后在各个渲染点逐个过滤既容易漏、
 * 又会因为列表复用出现「闪一下再消失」。在 `insertWithOnConflict` 的 before 阶段返回 false
 * （[allowInsert]）是唯一一个「从一开始就不存在」的位置。
 *
 * 自己发的消息永远放行；规则为空时零开销（不解析 MessageInfo）。
 */
@Feature(
    name = "群内屏蔽指定成员",
    categories = ["聊天"],
    description = "只屏蔽指定群里某些成员的消息，本机生效、对方无感；支持完全隐藏与静默（可在日志里回看）两种模式",
)
object GroupMemberBlock : ClickableFeature(), WeDatabaseListenerApi.IInsertFilter {

    private const val TAG = "GroupMemberBlock"

    private const val KEY_RULES = "gmb_rules"
    private const val KEY_LOG = "gmb_log"
    private const val MAX_LOG = 200

    private const val MODE_HIDE = 0
    private const val MODE_SILENT = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class GroupRule(
        val g: String,
        val u: List<String> = emptyList(),
        val m: Int = MODE_SILENT,
    )

    @Serializable
    private data class BlockLog(
        val t: Long,
        val g: String,
        val gn: String,
        val s: String,
        val sn: String,
        val ty: Int,
    )

    // ── 数据 ────────────────────────────────────────────────────────────────

    private fun loadRules(): MutableList<GroupRule> = runCatching {
        json.decodeFromString<List<GroupRule>>(WePrefs.getStringOrDef(KEY_RULES, "[]")).toMutableList()
    }.getOrElse {
        WeLogger.w(TAG, "解析屏蔽规则失败，按空规则处理", it)
        mutableListOf()
    }

    private fun saveRules(rules: List<GroupRule>) {
        runCatching { WePrefs.putString(KEY_RULES, json.encodeToString(rules)) }
            .onFailure { WeLogger.e(TAG, "保存屏蔽规则失败", it); showToast("保存失败：" + it.message) }
    }

    private fun loadLogs(): MutableList<BlockLog> = runCatching {
        json.decodeFromString<List<BlockLog>>(WePrefs.getStringOrDef(KEY_LOG, "[]")).toMutableList()
    }.getOrElse { mutableListOf() }

    private fun saveLogs(logs: List<BlockLog>) {
        runCatching { WePrefs.putString(KEY_LOG, json.encodeToString(logs.takeLast(MAX_LOG))) }
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    // ── 拦截 ────────────────────────────────────────────────────────────────

    override fun allowInsert(table: String, values: ContentValues): Boolean {
        if (table != "message") return true
        val rules = loadRules()
        if (rules.isEmpty()) return true

        val msg = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return true
        if (msg.isSelfSender) return true

        val talker = runCatching { msg.talker }.getOrNull().orEmpty()
        if (talker.isEmpty()) return true
        val rule = rules.firstOrNull { it.g == talker } ?: return true

        val sender = runCatching { msg.sender }.getOrNull().orEmpty()
        if (sender.isEmpty() || sender !in rule.u) return true

        if (rule.m == MODE_SILENT) {
            runCatching {
                val logs = loadLogs()
                logs.add(
                    BlockLog(
                        t = System.currentTimeMillis(),
                        g = talker,
                        gn = runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(""),
                        s = sender,
                        sn = runCatching { WeDatabaseApi.getGroupMemberDisplayName(talker, sender) }
                            .getOrDefault(sender),
                        ty = runCatching { msg.typeCode }.getOrDefault(0),
                    )
                )
                saveLogs(logs)
            }.onFailure { WeLogger.w(TAG, "记录拦截日志失败", it) }
        }

        WeLogger.i(TAG, "blocked group member message: group=$talker sender=$sender mode=${rule.m}")
        return false
    }

    // ── 设置界面 ────────────────────────────────────────────────────────────

    override fun onClick(context: ComponentActivity) {
        showMainDialog(context)
    }

    private fun showMainDialog(ctx: Context) {
        val rules = loadRules()
        val titles = rules.map { r ->
            "${groupLabel(r.g)} · ${r.u.size} 人 · ${modeLabel(r.m)}"
        }
        val actions = titles + listOf("＋ 添加/编辑群规则", "拦截日志（${loadLogs().size}）", "导出规则", "导入规则")

        AlertDialog.Builder(ctx)
            .setTitle("群内屏蔽指定成员")
            .setItems(actions.toTypedArray()) { _, which ->
                when {
                    which < titles.size -> showRuleActions(ctx, rules[which])
                    which == titles.size -> pickGroup(ctx)
                    which == titles.size + 1 -> showLogs(ctx)
                    which == titles.size + 2 -> exportRules(ctx)
                    else -> importRules(ctx)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showRuleActions(ctx: Context, rule: GroupRule) {
        val items = arrayOf("编辑成员（当前 ${rule.u.size} 人）", "切换模式（当前${modeLabel(rule.m)}）", "删除该群规则")
        AlertDialog.Builder(ctx)
            .setTitle(groupLabel(rule.g))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickMembers(ctx, rule)
                    1 -> {
                        val rules = loadRules()
                        val idx = rules.indexOfFirst { it.g == rule.g }
                        if (idx >= 0) {
                            rules[idx] = rule.copy(m = if (rule.m == MODE_HIDE) MODE_SILENT else MODE_HIDE)
                            saveRules(rules)
                            showToast("已切换为${modeLabel(rules[idx].m)}")
                        }
                        showMainDialog(ctx)
                    }
                    else -> {
                        val rules = loadRules().filterNot { it.g == rule.g }
                        saveRules(rules)
                        showToast("已删除该群规则")
                        showMainDialog(ctx)
                    }
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    /** 选群：列出所有群，点一个进入成员多选。 */
    private fun pickGroup(ctx: Context) {
        val groups = runCatching { WeDatabaseApi.getGroups() }.getOrDefault(emptyList())
        if (groups.isEmpty()) {
            showToast("未读取到群聊列表（微信数据库可能未就绪）")
            return
        }
        val names = groups.map { runCatching { it.displayName }.getOrDefault(it.wxId) }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("选择群聊")
            .setItems(names) { _, which ->
                val group = groups[which]
                val existing = loadRules().firstOrNull { it.g == group.wxId }
                pickMembers(ctx, existing ?: GroupRule(g = group.wxId))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选成员：多选，确认后写入规则。 */
    private fun pickMembers(ctx: Context, rule: GroupRule) {
        val members = runCatching { WeDatabaseApi.getGroupMembers(rule.g) }.getOrDefault(emptyList())
        if (members.isEmpty()) {
            showToast("该群成员列表为空（数据库未就绪或群已解散）")
            return
        }
        val labels = members.map {
            runCatching { it.displayName }.getOrDefault(it.wxId)
        }.toTypedArray()
        val checked = BooleanArray(members.size) { members[it].wxId in rule.u }

        AlertDialog.Builder(ctx)
            .setTitle("选择要屏蔽的成员")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val picked = members.filterIndexed { i, _ -> checked[i] }.map { it.wxId }
                val rules = loadRules()
                val idx = rules.indexOfFirst { it.g == rule.g }
                val updated = rule.copy(u = picked)
                if (idx >= 0) rules[idx] = updated else rules.add(updated)
                saveRules(rules)
                showToast(if (picked.isEmpty()) "已清空该群屏蔽名单" else "已屏蔽 ${picked.size} 人")
                showMainDialog(ctx)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLogs(ctx: Context) {
        val logs = loadLogs()
        if (logs.isEmpty()) {
            showToast("暂无拦截记录")
            return
        }
        val text = logs.reversed().joinToString("\n") { log ->
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(log.t))
            "$time  ${log.gn.ifBlank { log.g }}  ${log.sn}  [类型${log.ty}]"
        }
        AlertDialog.Builder(ctx)
            .setTitle("拦截日志（最近 ${logs.size} 条）")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ ->
                copyToClipboard(ctx, text)
            }
            .show()
    }

    private fun exportRules(ctx: Context) {
        val text = runCatching { json.encodeToString(loadRules()) }.getOrElse { "[]" }
        copyToClipboard(ctx, text)
        showToast("规则已复制到剪贴板")
    }

    private fun importRules(ctx: Context) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            showToast("剪贴板为空")
            return
        }
        val parsed = runCatching { json.decodeFromString<List<GroupRule>>(text) }.getOrElse { ex ->
            showToast("导入失败：格式不正确")
            WeLogger.w(TAG, "导入规则解析失败", ex)
            return
        }
        if (parsed.isEmpty()) {
            showToast("导入内容为空")
            return
        }
        val merged = loadRules().associateBy { it.g }.toMutableMap()
        parsed.forEach { merged[it.g] = it }
        saveRules(merged.values.toList())
        showToast("已导入 ${parsed.size} 条群规则")
        showMainDialog(ctx)
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun groupLabel(groupId: String): String =
        runCatching { WeDatabaseApi.getDisplayName(groupId) }.getOrDefault("").ifBlank { groupId }

    private fun modeLabel(mode: Int): String = if (mode == MODE_HIDE) "完全隐藏" else "静默（记日志）"

    private fun copyToClipboard(ctx: Context, text: String) {
        runCatching {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("WCX 群屏蔽规则", text))
        }.onFailure { WeLogger.w(TAG, "复制失败", it) }
    }
}
