package com.Johnny.wcx.features.items.contacts.hidecontacts

import android.app.Activity
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.tencent.mm.ui.chatting.ChattingUI
import com.Johnny.wcx.features.api.ui.WeStartActivityApi
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt

private const val TAG = "HideContactsBlock"

/**
 * 名单内联系人的「禁止进入聊天」与「禁止查看资料」。
 *
 * 两个能力共用 [HideContacts] 的名单与临时显示态：
 * - [HideContacts.isHiddenNow] 为 true 时才拦截，`#show` / 三击标题临时显示期间整段放行；
 * - 开关分别是 `hide_block_chat` / `hide_block_profile`，默认关闭，用户按需打开。
 *
 * 拦截分两道：
 * 1. [WeStartActivityApi] 前置兜底——目标 Intent 带 `Chat_User` / `Contact_User` 且命中名单时
 *    直接取消启动，页面根本不会创建（最干净，无空白页闪烁）；
 * 2. `onCreate` 之后 finish——兜住 Intent 参数名变化或经内部跳转绕过的场景。
 *    必须在 after 里 finish，绝不能在 before 里跳过 onCreate，否则 `super.onCreate()` 未被调用，
 *    framework 会抛 SuperNotCalledException 直接崩溃。
 */
internal fun HideContacts.installBlockHooks() {
    installBlockStartActivityGuard()
    installBlockChatOnCreateGuard()
    installBlockProfileOnCreateGuard()
}

private fun HideContacts.installBlockStartActivityGuard() {
    WeStartActivityApi.addListener { param, intent ->
        // Chat_User / Contact_User 是微信聊天页与资料页的目标 wxid 参数名，两个页面各自只认一个。
        val chatTarget = intent.getStringExtra("Chat_User")
        val profileTarget = intent.getStringExtra("Contact_User")

        val blocked = when {
            surfaceOn(SURFACE_BLOCK_CHAT) && chatTarget != null -> isHiddenNow(chatTarget)
            surfaceOn(SURFACE_BLOCK_PROFILE) && profileTarget != null -> isHiddenNow(profileTarget)
            else -> false
        }
        if (!blocked) return@addListener

        WeLogger.d(TAG, "cancelling startActivity toward hidden contact")
        // void 方法在 before 阶段给 result 赋非 null 哨兵即跳过原方法，启动被取消。
        param.result = 0
    }
}

private fun HideContacts.installBlockChatOnCreateGuard() {
    // ChattingUI 在个别微信版本会改名/挪包，解析失败只退化为「仅 startActivity 兜底」，
    // 不能让整个 installBlockHooks 中断。
    val chatOnCreate = runCatching {
        ChattingUI::class.reflekt().firstMethodOrNull { name = "onCreate"; parameterCount = 1 }
    }.getOrElse { ex ->
        WeLogger.w(TAG, "解析 ChattingUI.onCreate 失败，禁止进入聊天退化为仅跳转拦截", ex)
        null
    }
    if (chatOnCreate == null) {
        WeLogger.w(TAG, "ChattingUI.onCreate 未解析，禁止进入聊天退化为仅跳转拦截")
        return
    }

    chatOnCreate.hookAfter {
        if (!surfaceOn(SURFACE_BLOCK_CHAT)) return@hookAfter
        val activity = thisObject as? Activity ?: return@hookAfter
        val wxId = activity.intent?.getStringExtra("Chat_User") ?: return@hookAfter
        if (!isHiddenNow(wxId)) return@hookAfter
        if (activity.isFinishing) return@hookAfter
        WeLogger.d(TAG, "blocked entering chat with hidden contact $wxId")
        activity.finish()
    }
}

private fun HideContacts.installBlockProfileOnCreateGuard() {
    val profileOnCreate = runCatching {
        ContactInfoUI::class.reflekt().firstMethodOrNull { name = "onCreate"; parameterCount = 1 }
    }.getOrElse { ex ->
        WeLogger.w(TAG, "解析 ContactInfoUI.onCreate 失败，禁止查看资料退化为仅跳转拦截", ex)
        null
    }
    if (profileOnCreate == null) {
        WeLogger.w(TAG, "ContactInfoUI.onCreate 未解析，禁止查看资料退化为仅跳转拦截")
        return
    }

    profileOnCreate.hookAfter {
        if (!surfaceOn(SURFACE_BLOCK_PROFILE)) return@hookAfter
        val activity = thisObject as? Activity ?: return@hookAfter
        val wxId = activity.intent?.getStringExtra("Contact_User") ?: return@hookAfter
        if (!isHiddenNow(wxId)) return@hookAfter
        if (activity.isFinishing) return@hookAfter
        WeLogger.d(TAG, "blocked viewing profile of hidden contact $wxId")
        activity.finish()
    }
}
