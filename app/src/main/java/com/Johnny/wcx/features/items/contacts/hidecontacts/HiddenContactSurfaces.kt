package com.Johnny.wcx.features.items.contacts.hidecontacts

import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.contacts.HideContacts

/**
 * 隐藏联系人的各个「隐藏面」。
 *
 * 每个面是一个独立开关（[SwitchFeature]），开关状态就是面是否生效的唯一依据；真正的 Hook
 * 由 [HideContacts] 统一安装一次，各 Hook 内部用 [HideContacts.surfaceOn] 按面判断——
 * 这样多个开关共用一份 Hook，不会出现「同一个 Hook 被装两遍」的问题。
 *
 * 默认全部开启：升级上来的用户名单还在，行为与之前一致；想单独关掉某个面再关即可。
 */

// ── 主页会话 ────────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_CONVERSATIONS,
    categories = ["密友功能"],
    description = "名单内联系人的会话不出现在主页聊天列表；对方发来新消息也保持隐藏（聊天记录不删）",
)
object HideConversationSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 通讯录 ──────────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_CONTACTS,
    categories = ["密友功能"],
    description = "名单内联系人不出现在通讯录、新的朋友、标签成员与联系人计数里",
)
object HideContactListSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 群成员 / @ 选择器 ───────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_GROUP_MEMBERS,
    categories = ["密友功能"],
    description = "群成员列表与 @ 选择器里不再出现名单内联系人",
)
object HideGroupMemberSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 收藏 ────────────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_FAVORITES,
    categories = ["密友功能"],
    description = "收藏列表里由名单内联系人发送的内容不再显示",
)
object HideFavoriteSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 视频号互动 ──────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_FINDER_LIKE,
    categories = ["密友功能"],
    description = "视频号的点赞/评论列表里不再出现名单内联系人",
)
object HideFinderLikeSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 搜索 ────────────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_SEARCH,
    categories = ["密友功能"],
    description = "全局搜索、群成员搜索、共同群聊建议里不再命中名单内联系人",
)
object HideSearchSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 朋友圈 ──────────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_MOMENTS,
    categories = ["密友功能"],
    description = "名单内联系人的朋友圈内容、红点与在朋友圈的可见性一起隐藏",
)
object HideMomentsSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

@Feature(
    name = HideContacts.SURFACE_MOMENTS_INLINE,
    categories = ["密友功能"],
    description = "别人朋友圈里由名单内联系人产生的点赞/评论条目不再显示",
)
object HideMomentsInlineSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 音视频通话 ──────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_VOIP,
    categories = ["密友功能"],
    description = "名单内联系人的语音/视频来电不弹出，通话记录里也不显示",
)
object HideVoipSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 禁止进入聊天 / 禁止查看资料 ─────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_BLOCK_CHAT,
    categories = ["密友功能"],
    description = "点开名单内联系人的聊天页会立即退出；临时显示期间放行",
)
object BlockHiddenChatSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

@Feature(
    name = HideContacts.SURFACE_BLOCK_PROFILE,
    categories = ["密友功能"],
    description = "点开名单内联系人的资料页会立即退出；临时显示期间放行",
)
object BlockHiddenProfileSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 自动免打扰 ──────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_MUTE,
    categories = ["密友功能"],
    description = "新加入名单的联系人自动设为消息免打扰（已隐藏的不重复设置）",
)
object MuteHiddenSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}


// ── 新的朋友 / 未读角标 ─────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_NEW_FRIENDS,
    categories = ["密友功能"],
    description = "通讯录「新的朋友」里名单内联系人的好友申请、头像条与红点都不显示",
)
object HideNewFriendsSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

@Feature(
    name = HideContacts.SURFACE_UNREAD,
    categories = ["密友功能"],
    description = "名单内联系人的未读消息不计入主页角标与底部 Tab 数字",
)
object HideUnreadSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = true
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

// ── 自动拒接 ────────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_AUTO_REJECT_VOIP,
    categories = ["密友功能"],
    description = "名单内联系人打来时立即向对方拒接（关闭则仅隐藏来电，对方会响到超时）",
)
object AutoRejectVoipSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}


// ── 密友消息震动 ────────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_VIBRATE,
    categories = ["密友功能"],
    description = "名单内联系人来消息时（通知被隐藏）补一次短震动提醒",
)
object VibrateHiddenSurface : SwitchFeature() {
    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}


// ── 微信团队汇总提醒 ────────────────────────────────────────────────────────

@Feature(
    name = HideContacts.SURFACE_TEAM_SUMMARY,
    categories = ["密友功能"],
    description = "名单内联系人的通知被隐藏后，改发一条「微信团队」聚合提醒（显示未读条数）",
)
object TeamSummarySurface : SwitchFeature() {
    override val defaultEnabled: Boolean = false
    override fun onEnable() = HideContacts.onSurfaceChanged()
    override fun onDisable() = HideContacts.onSurfaceChanged()
}

/**
 * 各面开关变化后的统一收尾：刷新一次会话列表让结果立刻可见。
 *
 * Hook 是按面动态判断的，所以不需要装卸 Hook —— 开关一变，下一次查询就生效。
 */
internal fun HideContacts.onSurfaceChanged() {
    runCatching { WeConversationApi.reloadConversations() }
}
