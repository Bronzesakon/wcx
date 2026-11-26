package com.Johnny.wcx.features.items.chat

import android.view.View
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import java.lang.reflect.Field
import java.util.WeakHashMap

@Feature(name = "合并消息显示", categories = ["聊天"], description = "将同一发送者的连续多条消息合并为一组消息显示 (Telegram 风格)")
object MergeMessagesIntoGroups : SwitchFeature(), WeChatMessageViewApi.ICreateViewListener {

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    // ── field cache ──────────────────────────────────────────────────────────

    /** 按 tag 类缓存；字段缺失时为 null，头像另有子树兜底。 */
    private class TagFields(
        val avatar: Field?,
        val displayName: Field?,
        val time: Field?,
    )

    private val fieldsByClass = WeakHashMap<Class<*>, TagFields>()

    private fun fieldsFor(tag: Any): TagFields = fieldsByClass.getOrPut(tag.javaClass) {
        val avatar = runCatching {
            tag.reflekt().firstField { name = "avatarIV"; superclass() }
                .self.apply { isAccessible = true }
        }.getOrNull()
        val displayName = runCatching {
            tag.reflekt().firstField { name = "userTV"; superclass() }
                .self.apply { isAccessible = true }
        }.getOrNull()
        val time = runCatching {
            tag.reflekt().firstField { name = "timeTV"; superclass() }
                .self.apply { isAccessible = true }
        }.getOrNull()
        TagFields(avatar, displayName, time)
    }

    // ── ICreateViewListener ──────────────────────────────────────────────────

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        val tag = view.tag ?: return

        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.type?.isSystem ?: false || msgInfo.type == MessageType.PAT) return
        // 对方消息在群聊里带头像、单聊里带头像；自己发的消息在群聊与单聊同样会
        // 每条挂一个头像。四个方向都要参与合并：同组只有第一条保留头像。
        val isSend = msgInfo.isSend != 0
        val isGroupChat = msgInfo.isInGroupChat
        val currentSender = msgInfo.sender
        val position = param.args[2] as Int

        val adapter = param.thisObject.reflekt()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get() ?: return

        val fields = fieldsFor(tag)
        val avatar = (fields.avatar?.get(tag) as? View) ?: findAvatarInSubtree(view, isSend)
        if (avatar == null) {
            // 布局解析不到头像，本条不参与合并，保持原样。
            return
        }

        val currentHasVisibleTime =
            (fields.time?.get(tag) as? View)?.visibility == View.VISIBLE

        val isFirstInGroup = run {
            val prevSender = senderAt(adapter, position - 1)
            prevSender != currentSender || currentHasVisibleTime
        }

        // Avatar: 挂在组的**第一条**上（Telegram 风格），同组其余消息留白。
        // 用 INVISIBLE 而不是 GONE，保证气泡列不会被挤位。
        val avatarContainer = avatar.parent as? View ?: avatar
        avatarContainer.visibility =
            if (isFirstInGroup) View.VISIBLE else View.INVISIBLE

        // Display Name: only hidden for later messages in group chats
        if (isGroupChat) {
            (fields.displayName?.get(tag) as? View)?.visibility =
                if (isFirstInGroup) View.VISIBLE else View.GONE
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * 字段名取不到头像时的兜底：在整行子树里按类名找头像控件，
     * 用「在行的左/右半区」区分自己（右）与对方（左）。
     */
    private fun findAvatarInSubtree(root: View, onRight: Boolean): View? {
        val queue = java.util.ArrayDeque<Pair<View, Int>>()
        queue.add(root to 0)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 96) {
            val (v, depth) = queue.removeFirst()
            if (depth > 8) continue
            if (v is ImageView && v.width > 0 && v.drawable != null &&
                v.javaClass.name.contains("Avatar", ignoreCase = true)
            ) {
                val centerX = offsetX(v, root) + v.width / 2f
                if ((centerX > root.width / 2f) == onRight) return v
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) queue.add(v.getChildAt(i) to depth + 1)
            }
        }
        return null
    }

    private fun offsetX(v: View, ancestor: View): Float {
        var x = 0f
        var cur: View? = v
        while (cur != null && cur !== ancestor) {
            x += cur.left + cur.translationX
            cur = cur.parent as? View
        }
        return x
    }

    private fun senderAt(adapter: Any, position: Int): String? = runCatching {
        val raw = adapter.reflekt()
            .firstMethod { name = "getItem" }
            .invoke(position) ?: return null
        MessageInfo(raw).sender
    }.getOrNull()
}
