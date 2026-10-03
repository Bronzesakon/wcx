package com.Johnny.wcx.features.items.chat

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Send
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.SendIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * 复刻 FkWeChat 的「长按智能回复」：
 * 消息长按菜单新增「智能回复」入口，AI 按回复风格生成多条备选回复，
 * 点击任意备选即直接发送（OpenAI 兼容接口，默认 DeepSeek）。
 */
@Feature(
    name = "长按智能回复",
    categories = ["聊天"],
    description = "消息长按菜单添加「智能回复」: AI 生成多条备选回复, 点击直接发送 (需在设置中配置 OpenAI 兼容接口与 API Key)"
)
object SmartLongPressReply : ClickableFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "SmartLongPressReply"
    private const val MENU_ITEM_ID = 777026

    // ── 配置项 ──────────────────────────────────────────────────────────────
    private var apiUrl by prefOption("smart_reply_api_url", "https://api.deepseek.com/chat/completions")
    private var apiKey by prefOption("smart_reply_api_key", "")
    private var model by prefOption("smart_reply_model", "deepseek-chat")
    private var count by prefOption("smart_reply_count", 3)
    private var styleIndex by prefOption("smart_reply_style", 0)
    private var contextEnabled by prefOption("smart_reply_context", false)
    private var contextCount by prefOption("smart_reply_context_count", 6)
    private var customStyleName by prefOption("smart_reply_custom_name", "")
    private var customStylePrompt by prefOption("smart_reply_custom_prompt", "")

    private val styleNames = listOf("自然", "礼貌", "活泼", "高冷", "霸道", "委婉拒绝", "自定义")
    private val stylePrompts = listOf(
        "自然得体的回复",
        "礼貌客气, 多使用敬语",
        "轻松活泼, 带点俏皮",
        "简短高冷, 惜字如金",
        "霸道/冷酷",
        "礼貌地拒绝对方的要求, 不让对方感到难堪, 语气委婉",
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun onClick(context: ComponentActivity) {
        showConfigDialog(context)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                MENU_ITEM_ID,
                "智能回复",
                SendIcon,
                MaterialSymbols.Outlined.Send,
                isSupported = { msgInfo -> msgInfo.type?.isText ?: false },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
            ) { view, _, msgInfo ->
                startSmartReply(view.context, msgInfo)
            }
        )
    }

    // ── 长按入口：生成 → 展示备选 → 点击发送 ───────────────────────────────

    private fun startSmartReply(context: Context, msgInfo: MessageInfo) {
        showComposeDialog(context) {
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }
            var replies by remember { mutableStateOf<List<String>>(emptyList()) }

            LaunchedEffect(msgInfo) {
                val result = withContext(Dispatchers.IO) { generateReplies(msgInfo.actualContent, msgInfo.talker) }
                loading = false
                if (result.error != null) {
                    error = result.error
                } else {
                    replies = result.replies
                }
            }

            AlertDialogContent(
                title = { Text("智能回复") },
                text = {
                    when {
                        loading -> {
                            DefaultColumn(scrollable = true) {
                                CircularProgressIndicator()
                                Text("正在生成备选回复…")
                            }
                        }
                        error != null -> {
                            DefaultColumn(scrollable = true) {
                                Text("生成失败: $error")
                                Text("请在「长按智能回复」设置页配置 API 地址 / API Key / 模型")
                            }
                        }
                        replies.isNotEmpty() -> {
                            LazyColumn {
                                itemsIndexed(replies) { _, reply ->
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            onDismiss()
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val sent = runCatching {
                                                    WeMessageApi.sendText(msgInfo.talker, reply)
                                                }.getOrDefault(false)
                                                showToastSuspend(context, if (sent) "已发送" else "发送失败!")
                                            }
                                        },
                                        headlineContent = { Text(reply) },
                                    )
                                }
                            }
                        }
                        else -> {
                            DefaultColumn(scrollable = true) {
                                Text("没有生成到备选回复, 请稍后重试")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            )
        }
    }

    // ── AI 调用（OpenAI 兼容 chat/completions）────────────────────────────

    private data class GenerateResult(val replies: List<String>, val error: String?)

    private fun generateReplies(text: String, talker: String): GenerateResult {
        if (text.isBlank()) return GenerateResult(emptyList(), "消息内容为空")
        if (apiKey.isBlank()) return GenerateResult(emptyList(), "未配置 API Key")

        val requestUrl = normalizeApiUrl(apiUrl)
        // 风格=6 为自定义：使用用户填写的自定义系统提示词（未填则回退默认）
        val style = if (styleIndex == 6) {
            customStylePrompt.ifBlank { "自然得体的回复" }
        } else {
            stylePrompts[styleIndex.coerceIn(0, stylePrompts.lastIndex)]
        }
        val systemPrompt = "你是微信聊天助手。根据用户发给你的消息, 生成 ${count.coerceIn(1, 6)} 条不同角度的备选回复。" +
            "每条回复单独一行, 直接输出回复文本本身, 不要编号、不要引号、不要解释、不要空白行。" +
            "本次回复风格: $style"

        // 联系上下文：开启时携带「被长按这条消息之前」（更早）的最近 N 条文本消息，按时间正序喂给模型。
        // 找到长按消息在会话记录中的位置，只取它前面的记录——否则会把对方之后发的消息也当上下文，
        // 模型会生成针对「后面消息」的回复而不是针对长按的这条。消息内容本身已单独作为 user 输入传入。
        val contextCntFixed = contextCount.coerceIn(1, 20)
        val contextMessages: List<Pair<String, String>> = if (contextEnabled && talker.isNotBlank()) {
            runCatching {
                val history = WeDatabaseApi.getMessages(talker, 1, 48)
                    .filter { it.type?.isText == true && it.content.isNotBlank() }
                val currentIndex = history.indexOfFirst { it.content.trim() == text.trim() }
                history
                    .let { list ->
                        if (currentIndex >= 0) list.drop(currentIndex + 1)
                        else list.filterNot { m -> m.content.trim() == text.trim() }
                    }
                    .take(contextCntFixed)
                    .reversed()
                    .map { m ->
                        val role = if (m.isSend != 0) "assistant" else "user"
                        role to m.content.replace('\n', ' ').trim().take(100)
                    }
            }.getOrElse { e ->
                WeLogger.w(TAG, "failed to load chat context: ${e.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                contextMessages.forEach { (role, content) ->
                    add(buildJsonObject {
                        put("role", role)
                        put("content", content)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("temperature", 1.0)
        }

        return try {
            val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 30000
                // 免费模型(如智谱 glm-4-flash)响应较慢, 加大读超时防止 timeout
                readTimeout = 120000
            }
            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                    .getOrNull().orEmpty()
            }
            connection.disconnect()

            if (code !in 200..299) {
                val server = extractServerError(body)
                val detail = if (server != null) "；服务端: $server" else ""
                WeLogger.e(TAG, "smart reply API returned $code on $requestUrl: $body")
                return GenerateResult(emptyList(), "HTTP $code$detail\n调用地址: $requestUrl")
            }
            val parsed = parseReplies(body)
            if (parsed.isEmpty()) {
                GenerateResult(emptyList(), "AI 未返回可用回复, 请重试或调大备选条数")
            } else {
                GenerateResult(parsed, null)
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "smart reply generation failed", e)
            val hint = if (e is java.net.SocketTimeoutException) {
                "（请求超时: 免费模型响应较慢, 已加大等待时间, 请稍后重试或减少备选条数）"
            } else {
                ""
            }
            GenerateResult(emptyList(), "请求异常: ${e.message}$hint")
        }
    }

    private fun parseReplies(body: String): List<String> {
        return try {
            val root = Json.parseToJsonElement(body).jsonObject
            val choices = root["choices"]?.jsonArray ?: return emptyList()
            val contents = choices.mapNotNull { choice ->
                choice.jsonObject["message"]?.jsonObject
                    ?.get("content")?.jsonPrimitive?.contentOrNull
            }
            contents.flatMap { it.split('\n') }
                .map { it.trim().trim('"').trim() }
                .filter { it.length >= 2 }
                .distinct()
                .take(count.coerceIn(1, 6))
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to parse smart reply response", e)
            emptyList()
        }
    }

    private fun extractServerError(body: String): String? = runCatching {
        val err = Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        val msg = err?.get("message")?.jsonPrimitive?.contentOrNull
        if (msg.isNullOrBlank()) null else msg
    }.getOrNull()

    private fun normalizeApiUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.endsWith("/chat/completions", ignoreCase = true)) return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "$trimmed/v1/chat/completions"
        } else {
            "https://$trimmed/v1/chat/completions"
        }
    }

    // ── 读取可用模型（OpenAI 兼容 /models）────────────────────────────────

    private fun fetchModels(url: String): List<String> {
        val modelsUrl = buildModelsUrl(url)
        val connection = URL(modelsUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                val server = extractServerError(body)
                throw Exception(
                    "HTTP $code" + if (server != null) "；服务端: $server" else ""
                )
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseModelIds(body)
        } catch (e: Exception) {
            WeLogger.e(TAG, "fetch models failed: $modelsUrl", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun parseModelIds(body: String): List<String> {
        return runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            data.mapNotNull { item ->
                item.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun buildModelsUrl(apiUrl: String): String {
        val trimmed = normalizeApiUrl(apiUrl).trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions", ignoreCase = true) ->
                trimmed.dropLast("/chat/completions".length) + "/models"
            trimmed.endsWith("/models", ignoreCase = true) -> trimmed
            else -> "$trimmed/models"
        }
    }

    // ── 设置对话框 ──────────────────────────────────────────────────────────

    private fun showConfigDialog(context: Context) {
        showComposeDialog(context) {
            var url by remember { mutableStateOf(apiUrl) }
            var key by remember { mutableStateOf(apiKey) }
            var mdl by remember { mutableStateOf(model) }
            var cnt by remember { mutableStateOf(count.toString()) }
            var style by remember { mutableStateOf(styleIndex) }
            var styleMenu by remember { mutableStateOf(false) }
            var customName by remember { mutableStateOf(customStyleName) }
            var customPrompt by remember { mutableStateOf(customStylePrompt) }
            var contextOn by remember { mutableStateOf(contextEnabled) }
            var contextCnt by remember { mutableStateOf(contextCount.toString()) }
            var modelsLoading by remember { mutableStateOf(false) }
            var modelsError by remember { mutableStateOf<String?>(null) }
            var models by remember { mutableStateOf<List<String>>(emptyList()) }

            AlertDialogContent(
                title = { Text("长按智能回复 设置") },
                text = {
                    DefaultColumn(scrollable = true) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("API 地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = mdl,
                            onValueChange = { mdl = it },
                            label = { Text("模型") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                if (key.isBlank()) {
                                    modelsError = "请先填写 API Key 再读取模型"
                                    return@Button
                                }
                                modelsLoading = true
                                modelsError = null
                                CoroutineScope(Dispatchers.IO).launch {
                                    val result = runCatching { fetchModels(url) }
                                    modelsLoading = false
                                    result.onSuccess { models = it }
                                        .onFailure { e ->
                                            modelsError = e.message ?: "读取失败"
                                            WeLogger.e(TAG, "fetch models failed", e)
                                        }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (modelsLoading) "读取中…" else "读取可用模型")
                        }
                        modelsError?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                        models.forEach { model ->
                            ListItem(
                                modifier = Modifier.clickable { mdl = model },
                                headlineContent = { Text("${if (model == mdl) "✓ " else ""}$model") },
                            )
                        }
                        OutlinedTextField(
                            value = cnt,
                            onValueChange = { cnt = it.filter { c -> c.isDigit() } },
                            label = { Text("备选条数 (1-6)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "联系上下文: ${if (contextOn) "开" else "关"}（携带该消息之前最近 ${contextCnt.toIntOrNull()?.coerceIn(1, 20) ?: 6} 条, 点击切换）",
                            fontSize = androidx.compose.material3.MaterialTheme.typography.bodySmall.fontSize,
                            modifier = Modifier.clickable { contextOn = !contextOn },
                        )
                        if (contextOn) {
                            OutlinedTextField(
                                value = contextCnt,
                                onValueChange = { contextCnt = it.filter { c -> c.isDigit() }.take(2) },
                                label = { Text("上下文条数 (1-20)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            "回复风格: ${styleDisplayName(style, customName)}",
                            modifier = Modifier.clickable { styleMenu = true },
                        )
                        DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                            styleNames.forEachIndexed { index, name ->
                                DropdownMenuItem(
                                    text = { Text(if (index == 6 && name == "自定义") customName.ifBlank { "自定义" } else name) },
                                    onClick = {
                                        style = index
                                        styleMenu = false
                                    },
                                )
                            }
                        }
                        if (style == 6) {
                            OutlinedTextField(
                                value = customName,
                                onValueChange = { customName = it },
                                label = { Text("自定义风格名 (显示用)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = customPrompt,
                                onValueChange = { customPrompt = it },
                                label = { Text("自定义系统提示词 (本次回复风格)") },
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            "提示: 地址可填完整 /chat/completions 地址 (默认 DeepSeek); " +
                                "兼容 OpenAI 接口的任意服务 (OpenAI/智谱/月之暗面/硅基流动等) 均可。\n" +
                                "风格选「自定义」可填写自己的风格名与系统提示词。"
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        apiUrl = url
                        apiKey = key
                        model = mdl
                        count = cnt.toIntOrNull()?.coerceIn(1, 6) ?: 3
                        styleIndex = style.coerceIn(0, styleNames.lastIndex)
                        customStyleName = customName.trim()
                        customStylePrompt = customPrompt.trim()
                        contextEnabled = contextOn
                        contextCount = contextCnt.toIntOrNull()?.coerceIn(1, 20) ?: 6
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            )
        }
    }

    private fun styleDisplayName(style: Int, customName: String): String = when {
        style == 6 -> customName.ifBlank { "自定义" }
        style in styleNames.indices -> styleNames[style]
        else -> "自定义"
    }
}