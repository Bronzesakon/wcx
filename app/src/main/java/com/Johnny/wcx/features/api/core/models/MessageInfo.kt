@file:Suppress("NOTHING_TO_INLINE", "unused")

package com.Johnny.wcx.features.api.core.models

import android.content.ContentValues
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.serialization.NativeXmlParser
import com.Johnny.wcx.utils.serialization.XmlObject
import com.Johnny.wcx.utils.serialization.asInt
import com.Johnny.wcx.utils.serialization.asLong
import com.Johnny.wcx.utils.serialization.asString
import com.Johnny.wcx.utils.serialization.get
import com.Johnny.wcx.utils.serialization.getByPath
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import com.Johnny.wcx.utils.strings.stripWxId
import java.nio.ByteBuffer

class MessageInfo(val instance: Any) {

    val typeCode by lazy { getFieldByName<Int>(instance, "field_type") }
    val type by lazy { MessageType.fromCode(typeCode) }

    val id by lazy { getFieldByName<Long>(instance, "field_msgId") }
    val serverId by lazy { getFieldByName<Long>(instance, "field_msgSvrId") }
    val isSend by lazy { getFieldByName<Int>(instance, "field_isSend") }
    val createTime by lazy { getFieldByName<Long>(instance, "field_createTime") }
    val talker by lazy { getFieldByName<String>(instance, "field_talker") }
    val content by lazy { getFieldByName<String>(instance, "field_content") }

    val actualContent: String
        get() {
            var text = content
            if (isInGroupChat) {
                text = text.stripWxId()
            }
            return text
        }

    val quoteMsgActualContent: String?
        get() {
            val quoteMsg = toQuoteMessage() ?: return null

            var text = quoteMsg.title
            if (isInGroupChat) {
                text = text.stripWxId()
            }

            return text
        }

    val humanReadableRepr: String
        get() {
            val type = type ?: return "[${MessageType.UNKNOWN.displayName}]"

            return when {
                type.code == MessageType.QUOTE.code -> quoteMsgActualContent ?: actualContent
                type.isText -> actualContent
                type.isSystem -> actualContent
                else -> "[${type.displayName}]"
            }
        }

    val imagePath by lazy { getFieldByName<String?>(instance, "field_imgPath") }
    val lvBuffer by lazy { getFieldByName<ByteArray>(instance, "field_lvbuffer") }
    val talkerId by lazy { getFieldByName<Int>(instance, "field_talkerId") }
    val seq by lazy { getFieldByName<Long>(instance, "field_msgSeq") }

    val msgSource: String by lazy {
        val buffer = lvBuffer
        if (buffer.isEmpty()) return@lazy ""
        if (buffer[0] != '{'.code.toByte() || buffer.last() != '}'.code.toByte()) return@lazy ""

        val bb = ByteBuffer.wrap(buffer)
        bb.position(1) // skip '{'

        // skip string field (2-byte length prefix + data)
        if (bb.remaining() >= 2) {
            val n1 = bb.short.toInt()
            if (n1 > 3072) error("Buffer String Length Error")
            if (n1 != 0 && bb.remaining() >= n1) bb.position(bb.position() + n1)
        }

        // skip 4-byte int field
        if (bb.remaining() >= 4) bb.position(bb.position() + 4)

        if (bb.remaining() < 2) return@lazy ""

        val n2 = bb.short.toInt()
        if (n2 > 3072) error("Buffer String Length Error")
        if (n2 == 0 || bb.remaining() < n2) return@lazy ""

        val bytes = ByteArray(n2)
        bb.get(bytes)
        String(bytes, Charsets.UTF_8)
    }

    val mentionedUsers: List<String> by lazy {
        if (msgSource.isEmpty()) return@lazy emptyList()
        val xml = try {
            NativeXmlParser.toXmlObject(msgSource)
        } catch (_: Exception) {
            return@lazy emptyList()
        }
        val atUserListStr = xml.getByPath("msgsource.atuserlist")?.asString ?: return@lazy emptyList()
        atUserListStr.split(",").filter { it.isNotEmpty() }
    }

    val isAtMe get() = mentionedUsers.contains(WeApi.selfWxId)

    val isAnnounceAll get() = mentionedUsers.contains("announcement@all")

    val isNotifyAll: Boolean
        get() {
            if (mentionedUsers.contains("notify@all") || mentionedUsers.contains("announcement@all")) {
                val contentText = actualContent
                return contentText.contains("@所有人") || contentText.contains("@ all people")
            }
            return false
        }

    val isInGroupChat get() = talker.isGroupChatWxId
    val isOfficialAccount get() = talker.startsWith("gh_")
    val sender by lazy {
        @Suppress("DEPRECATION")
        if (typeCode == MessageType.SYSTEM.code) {
            return@lazy "system"
        }

        if (typeCode == MessageType.PAT.code) {
            val patMsg = PatMessage(content)
            return@lazy patMsg.fromUser
        }

        if (isSelfSender) {
            return@lazy WeApi.selfWxId
        }

        if (!isInGroupChat) {
            return@lazy talker
        }

        return@lazy content.split(':')[0]
    }

    val isSelfSender get() = isSend != 0

    inline fun toPatMessage(): PatMessage? {
        if (typeCode != MessageType.PAT.code)
            return null

        return PatMessage(content)
    }

    fun toQuoteMessage(): QuoteMessage? {
        if (typeCode != MessageType.QUOTE.code)
            return null

        return QuoteMessage(content)
    }

    fun toTransferMessage(): TransferMessage? {
        if (type != MessageType.TRANSFER)
            return null

        return TransferMessage(content)
    }

    fun toFileMessage(): FileMessage? {
        if (type != MessageType.FILE)
            return null

        return FileMessage(content)
    }

    fun toImageMessage(): ImageMessage? {
        if (type != MessageType.IMAGE)
            return null

        return ImageMessage(content)
    }

    class FileMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val size by lazy { xml.getByPath("msg.appmsg.appattach.totallen")!!.asLong }
        val ext by lazy { xml.getByPath("msg.appmsg.appattach.fileext")!!.asString }
        val md5 by lazy { xml.getByPath("msg.appmsg.md5")!!.asString }
        val url by lazy { xml.getByPath("msg.appmsg.appattach.cdnattachurl")!!.asString }
        val key by lazy { xml.getByPath("msg.appmsg.appattach.aeskey")!!.asString }

        /**
         * appmsg 内层 `<type>`:
         * - 6 / 130: 文件已就绪, 可以下载
         * - 74 / 131: 对方仍在上传中, 此时触发下载必然失败
         * - null / 其它: **不是文件消息** (链接 5 / 转发 19 / 引用 57 ...)
         *
         * 取值失败一律当 null —— 这个字段只用来回答"是不是文件消息", 不该因为
         * `<type>` 内容异常就抛异常打断调用方。
         */
        val appMsgType: Int? by lazy { runCatching { xml.getByPath("msg.appmsg.type")?.asInt }.getOrNull() }

        /** 对方是否仍在上传 (气泡显示"对方上传中")。 */
        val isSenderUploading get() = appMsgType == 74 || appMsgType == 131

        /** 文件是否已就绪、可下载。 */
        val isDownloadable get() = appMsgType == 6 || appMsgType == 130

        /**
         * 是不是一条**文件**消息 (已就绪或仍在传)。
         *
         * 必须与 [appMsgType] 配套使用: 事件驱动路径上 `type` 列经常缺席 (update 只带被改的列),
         * 链接 / 引用 / 转发等其它 appmsg 会被误当成文件候选。若对它们一律记 pending, 补偿轮
         * 会永远重试, 而微信侧的文件下载链路解析这些内容后会对 null 解引用抛 NPE
         * (设备日志: `Attempt to read from field 'int pt0.r.i' on a null object reference`)。
         */
        val isFileAppMsg get() = appMsgType == 6 || appMsgType == 130 || isSenderUploading

        /**
         * content 里是否存在 `<msg><appmsg>` 这一层。
         *
         * 用于把"**确凿**不是文件消息"(视频 `<videomsg>` / 语音 / 系统消息, 压根没有
         * `<appmsg>`) 与"拿不准"(有 `<appmsg>` 但 `<type>` 读不出来) 区分开: 前者可以
         * 直接结案不再重试, 后者留给调用方的重试上限兜底。
         */
        val looksLikeAppMsg get() = runCatching { xml.getByPath("msg.appmsg") != null }.getOrDefault(false)
    }

    class ImageMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val md5 by lazy { xml.getByPath("msg.img.md5")!!.asString }
        val bigImgUrl by lazy { xml.getByPath("msg.img.cdnbigimgurl")!!.asString }
        val midImgUrl by lazy { xml.getByPath("msg.img.cdnmidimgurl")!!.asString }
        val thumbUrl by lazy { xml.getByPath("msg.img.cdnthumburl")!!.asString }
        val aesKey by lazy { xml.getByPath("msg.img.aeskey")!!.asString }
    }

    class PatMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val createTime by lazy { recordObj["createTime"]!!.asLong }
        val fromUser by lazy { recordObj["fromUser"]!!.asString }
        val pattedUser by lazy { recordObj["pattedUser"]!!.asString }
        val readStatus by lazy { recordObj["readStatus"]!!.asInt }
        val recordNum by lazy { xml.getByPath("msg.appmsg.patMsg.records.recordNum")!!.asInt }
        val showModifyTip by lazy { recordObj["showModifyTip"]!!.asInt }
        val svrId by lazy { recordObj["svrId"]!!.asLong }
        val talker by lazy { xml.getByPath("msg.appmsg.patMsg.chatUser")!!.asString }
        val template by lazy { recordObj["template"]!!.asString }
        val recordObj by lazy {
            xml.getByPath("msg.appmsg.patMsg.records.record")!! as XmlObject
        }
    }

    class QuoteMessage(xmlStr: String) {
        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val chatusr by lazy { xml.getByPath("msg.appmsg.refermsg.chatusr")!!.asString }
        val displayname by lazy { xml.getByPath("msg.appmsg.refermsg.displayname")!!.asString }
        val msgsource by lazy { xml.getByPath("msg.appmsg.refermsg.msgsource")!!.asString }
        val svrid by lazy { xml.getByPath("msg.appmsg.refermsg.svrid")!!.asString.toLong() }
        val fromusr by lazy { xml.getByPath("msg.appmsg.refermsg.fromusr")!!.asString }
        val type by lazy { xml.getByPath("msg.appmsg.refermsg.type")!!.asString.toInt() }
        val content by lazy { xml.getByPath("msg.appmsg.refermsg.content")!!.asString }
    }

    class TransferMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val des by lazy { xml.getByPath("msg.appmsg.des")!!.asString }

        // 'transcationid' is WeChat's typo
        val transactionId by lazy { xml.getByPath("msg.appmsg.wcpayinfo.transcationid")!!.asString }
        val transferId by lazy { xml.getByPath("msg.appmsg.wcpayinfo.transferid")!!.asString }
        val beginTransferTime by lazy { xml.getByPath("msg.appmsg.wcpayinfo.begintransfertime")!!.asString.toLong() }
        val payerUsername by lazy { xml.getByPath("msg.appmsg.wcpayinfo.payer_username")!!.asString }
        val receiverUsername by lazy { xml.getByPath("msg.appmsg.wcpayinfo.receiver_username")!!.asString }
        val invalidTime by lazy { xml.getByPath("msg.appmsg.wcpayinfo.invalidtime")!!.asString.toInt() }
        val feedesc by lazy { xml.getByPath("msg.appmsg.wcpayinfo.feedesc")!!.asString }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <T> getFieldByName(instance: Any, name: String): T {
            return instance.reflekt().getField(name, true) as T
        }

        /**
         * Safely extracts tag content directly from raw XML strings.
         * Bypasses JSON type coercion overhead and prevents 32-digit string truncation.
         */
        private fun extractXmlTag(xml: String, tag: String): String? {
            val startTag = "<$tag>"
            val endTag = "</$tag>"
            if (!xml.contains(startTag) || !xml.contains(endTag)) return null

            val content = xml.substringAfter(startTag).substringBefore(endTag)
            return if (content.startsWith("<![CDATA[")) {
                content.substringAfter("<![CDATA[").substringBefore("]]>")
            } else {
                content
            }.trim()
        }

        private fun String.cleanupXml(): String {
            return "<msg>" + substringAfter("<msg>")
                .substringBeforeLast("</msg>")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\t", "")
                .replace("<?xml version=\"1.0\"?>", "") + "</msg>"
        }

        fun fromContentValues(contentValues: ContentValues): MessageInfo {
            return MessageInfo(WeMessageApi.convertMsgInfoInstanceFromContentValues(contentValues))
        }
    }
}
