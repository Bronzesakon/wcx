package com.Johnny.wcx.features.items.home_screen_menu

import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.features.api.ui.WeHomeScreenPopupMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.CheckCircleIcon
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.android.showToast

/**
 * 首页右上角菜单 → 朋友圈消息。
 *
 * 朋友圈的点赞/评论提醒平时要点进「发现 → 朋友圈 → 右上角消息」才能看到，这里给一个直达入口：
 * 直接拉起 `SnsMsgUI`（微信自己的朋友圈消息列表页），打开后微信会自行把这些提醒标为已读，
 * 所以顺带起到「清除朋友圈未读」的作用，不需要我们自己去改微信的 KV。
 */
@Feature(
    name = "朋友圈消息入口",
    categories = ["首页右上角菜单"],
    description = "在首页右上角菜单加一项，直接进入朋友圈消息列表（打开即清红点）",
)
object OpenMomentsMessages : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    private const val SNS_MSG_UI = "com.tencent.mm.plugin.sns.ui.SnsMsgUI"

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(777013, "朋友圈消息", CheckCircleIcon) {
                openMomentsMessages()
            }
        )
    }

    private fun openMomentsMessages() {
        runCatching {
            val intent = Intent().apply {
                setClassName(HostInfo.packageName, SNS_MSG_UI)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            HostInfo.application.startActivity(intent)
        }.onFailure { ex ->
            showToast("打开朋友圈消息失败：" + (ex.message ?: ex.javaClass.simpleName))
        }
    }
}
