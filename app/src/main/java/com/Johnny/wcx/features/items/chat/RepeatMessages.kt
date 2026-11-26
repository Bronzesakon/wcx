package com.Johnny.wcx.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Exposure_plus_1
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.ExposurePlus1Icon
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.serialization.XmlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "消息复读", categories = ["聊天"], description = "向消息长按菜单添加菜单项, 可复读一些常见消息")
object RepeatMessages : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = RepeatMessages::class.java.simpleName

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    @Suppress("DEPRECATION")
    private val SUPPORTED_MSG_TYPES = setOf(
        MessageType.TEXT,
        MessageType.QUOTE,
        MessageType.APP,
        MessageType.IMAGE,
        MessageType.VOICE,
        MessageType.MICRO_VIDEO,
        MessageType.STICKER,
        MessageType.SO_GOU_EMOJI
    )

    fun isSupported(msgInfo: MessageInfo): Boolean = msgInfo.type in SUPPORTED_MSG_TYPES

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777008, "复读", ExposurePlus1Icon, MaterialSymbols.Outlined.Exposure_plus_1,
                isSupported = ::isSupported,
                onClick = { view, _, msgInfo ->
                    val context = view.context

                    CoroutineScope(Dispatchers.IO).launch {
                        val reason = repeatMessageWithReason(msgInfo)
                        showToastSuspend(context, reason ?: "已复读")
                    }
                }
            )
        )
    }

    @Suppress("DEPRECATION")
    fun repeatMessage(msgInfo: MessageInfo): Boolean = repeatMessageWithReason(msgInfo) == null

    /**
     * 复读一条消息；成功返回 null，失败返回可直接展示给用户的原因。
     *
     * 原来所有失败路径都只返回 false，界面上永远是同一句「复读失败!」，
     * 分不清是「没下载」「路径解析不了」还是「微信拒绝发送」。这里把三类原因分开报。
     */
    @Suppress("DEPRECATION")
    fun repeatMessageWithReason(msgInfo: MessageInfo): String? {
        // 微信刚启动时模块还在后台扫 dex，发送组件没定位好，这时复读必然失败一次。
        // 先等一小会儿（最多 8 秒），比直接甩一句「复读失败」有用。
        if (!WeMessageApi.awaitSendReady()) {
            return "模块正在解析微信（发送组件未就绪），请稍后重试"
        }
        return runCatching {
            when (msgInfo.type) {
                MessageType.TEXT ->
                    if (WeMessageApi.sendText(msgInfo.talker, msgInfo.actualContent)) null
                    else "文本发送被微信拒绝"

                MessageType.IMAGE -> repeatImage(msgInfo)

                MessageType.VOICE -> {
                    val encPath = msgInfo.imagePath
                    if (encPath.isNullOrBlank()) {
                        "语音未下载（消息里没有文件引用）"
                    } else {
                        val voicePath = WeMessageApi.getVoiceFullPath(encPath)
                        when {
                            voicePath.isBlank() -> "语音路径解析失败：$encPath"
                            !java.io.File(voicePath).let { it.isFile && it.length() > 0 } ->
                                "语音文件不存在（可能未下载）：$voicePath"
                            else -> {
                                val durationMs = AudioUtils.getDurationMs(voicePath).toInt()
                                if (WeMessageApi.sendVoice(msgInfo.talker, voicePath, durationMs)) null
                                else "语音发送失败（时长 ${durationMs}ms）"
                            }
                        }
                    }
                }

                MessageType.VIDEO, MessageType.MICRO_VIDEO -> repeatVideo(msgInfo)

                MessageType.STICKER, MessageType.SO_GOU_EMOJI -> {
                    val md5 = emojiMd5(msgInfo)
                    when {
                        md5.isNullOrBlank() -> "表情 md5 解析失败"
                        WeMessageApi.sendEmojiByMd5(msgInfo.talker, md5) -> null
                        else -> "表情发送失败（md5=$md5）"
                    }
                }

                MessageType.APP ->
                    if (WeMessageApi.sendXmlAppMsg(msgInfo.talker, msgInfo.actualContent)) null
                    else "卡片消息发送失败"

                MessageType.QUOTE -> {
                    val quote = msgInfo.quoteMsgActualContent
                    when {
                        quote.isNullOrBlank() -> "引用消息内容为空"
                        WeMessageApi.sendText(msgInfo.talker, quote) -> null
                        else -> "引用消息发送失败"
                    }
                }

                else -> "不支持的消息类型（type=${msgInfo.typeCode}）"
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to repeat message: type=${msgInfo.typeCode}", it)
            "异常：" + (it.message ?: it.javaClass.simpleName)
        }
    }

    private fun repeatImage(msgInfo: MessageInfo): String? {
        val md5 = runCatching { WeServiceApi.getImageMd5FromMsgInfo(msgInfo) }.getOrNull()
        if (md5.isNullOrBlank()) return "图片 md5 解析失败"
        WeMessageApi.sendImageByMd5(msgInfo.talker, md5, null)
        return null
    }

    private fun repeatVideo(msgInfo: MessageInfo): String? {
        val mp4Path = runCatching { WeServiceApi.getVideoMp4PathFromMsgInfo(msgInfo) }
            .getOrElse {
                WeLogger.e(TAG, "restoreMp4Path failed", it)
                return "视频路径解析失败：" + (it.message ?: it.javaClass.simpleName)
            }
        if (mp4Path.isBlank()) return "视频未下载（微信没有给出本地 mp4 路径）"
        if (!java.io.File(mp4Path).let { it.isFile && it.length() > 0 }) {
            return "视频文件不存在（可能未下载完）：$mp4Path"
        }
        return if (WeMessageApi.sendVideo(msgInfo.talker, mp4Path)) null else "视频发送失败"
    }

    private fun emojiMd5(msgInfo: MessageInfo): String? {
        return msgInfo.imagePath?.takeIf { it.isNotBlank() }
            ?: XmlUtils.extractXmlAttr(msgInfo.content, "md5")?.takeIf { it.isNotBlank() }
            ?: XmlUtils.extractXmlTag(msgInfo.content, "md5")?.takeIf { it.isNotBlank() }
    }
}
