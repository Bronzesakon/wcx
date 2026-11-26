package com.Johnny.wcx.features.items.secret_friend

import android.content.Context
import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast

/**
 * 密友名单编辑入口。
 *
 * v310 起密友能力已并入 [HideContacts], 名单与总开关统一由其维护；本入口只负责编辑
 * 同一份名单，不再持有独立状态或第二套总开关。
 */
@Feature(
    name = "密友名单管理",
    categories = ["密友功能"],
    description = "编辑密友名单（与隐藏联系人共用名单）"
)
object SecretFriendManager : ClickableFeature() {

    /** 默认开启，保证从未操作过该入口的用户也能直接编辑名单。 */
    override val defaultEnabled = true

    /** 总开关由「密友功能」分类页统一控制，本行只展示名单编辑入口。 */
    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) = openSelector(context)

    /** 打开密友名单选择器；任意 Context 可用（供 #my# 面板等入口复用）。 */
    fun openSelector(context: Context) {
        val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
        showComposeDialog(context, fullScreen = true) {
            ContactsSelector(
                title = "选择密友",
                contacts = regularContacts,
                initialSelectedWxIds = HideContacts.hiddenContacts,
                onDismiss = onDismiss,
            ) {
                HideContacts.hiddenContacts = it
                showToast("密友名单已更新（${it.size} 人）")
                onDismiss()
            }
        }
    }
}
