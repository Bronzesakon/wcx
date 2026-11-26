package com.Johnny.wcx.features.items.moments

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.utils.toClass

/**
 * 朋友圈相关的「入口 / 分组」隐藏。
 *
 * 两个页面在 8.0.79 都是 **Preference 列表**（发现页 `FindMoreFriendsUI` 继承
 * `AbstractTabChildPreference`，分组页 `SnsTagDetailUI` 继承 `MMPreference`），
 * 但它们的 Preference 适配器是微信内部实现（混淆名每版都变），直接 hook 适配器不稳。
 *
 * 这里换一条更稳的路：在 `onResume` 之后遍历 ListView 的**已渲染子项**，按显示文字命中后
 * 把整行 `GONE`。这两个列表都很短（十来项、不滚动），一次遍历就够，而且：
 * - 不改任何数据或适配器，关掉开关重进页面即恢复原样；
 * - 匹配不到就什么都不做，最坏情况是「没隐藏」，不会把页面搞坏。
 */

private const val TAG = "MomentsHider"

// ── 发现页「朋友圈」入口 ─────────────────────────────────────────────────────

@Feature(
    name = "发现页朋友圈入口隐藏",
    categories = ["朋友圈"],
    description = "「发现」页里的「朋友圈」一行不再显示（不影响朋友圈本身的数据与其它入口）",
)
object HideDiscoverMomentsEntry : SwitchFeature() {

    override fun onEnable() {
        hookListFilter(this, "com.tencent.mm.ui.FindMoreFriendsUI") { label ->
            label == "朋友圈"
        }
    }
}

// ── 朋友圈「谁可以看」分组 ───────────────────────────────────────────────────

@Feature(
    name = "朋友圈分组图标隐藏",
    categories = ["朋友圈"],
    description = "朋友圈「谁可以看 / 部分可见」里的分组（标签）项不再显示",
)
object HideMomentsGroupTag : SwitchFeature() {

    private val PAGES = listOf(
        "com.tencent.mm.plugin.sns.ui.SnsTagDetailUI",
        "com.tencent.mm.plugin.sns.ui.SnsTagPartlyUI",
    )

    override fun onEnable() {
        PAGES.forEach { page ->
            hookListFilter(this, page) { label -> label.contains("分组") }
        }
    }
}

// ── 公共实现 ────────────────────────────────────────────────────────────────

/**
 * 在 [className] 的 `onResume` 之后按显示文字隐藏列表行。
 *
 * 开关状态在每次回调里实时读（[SwitchFeature.isEnabled]），所以关掉开关后重新进入页面立即恢复，
 * 不需要重启，也不需要卸载 Hook。
 */
private fun hookListFilter(
    feature: SwitchFeature,
    className: String,
    predicate: (label: String) -> Boolean,
) {
    val clazz = runCatching { className.toClass() }.getOrElse { ex ->
        WeLogger.w(TAG, "$className 未找到，跳过", ex)
        return
    }
    val onResume = runCatching { clazz.getDeclaredMethod("onResume") }.getOrElse { ex ->
        WeLogger.w(TAG, "$className.onResume 不存在，跳过", ex)
        return
    }
    runCatching {
        XposedBridge.hookMethod(onResume, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!feature.isEnabled) return
                val activity = param.thisObject as? Activity ?: return
                runCatching { hideListRows(activity, predicate) }
                    .onFailure { WeLogger.w(TAG, "隐藏列表行失败（$className）", it) }
            }
        })
    }.onFailure { WeLogger.w(TAG, "hook $className.onResume 失败", it) }
}

/** 遍历 ListView 已渲染的子项，命中 [predicate] 的整行设为 GONE。 */
private fun hideListRows(activity: Activity, predicate: (String) -> Boolean) {
    val list = activity.findViewById<ViewGroup>(android.R.id.list) ?: return
    for (index in 0 until list.childCount) {
        val row = list.getChildAt(index) ?: continue
        val label = firstText(row)?.trim() ?: continue
        if (label.isNotEmpty() && predicate(label) && row.visibility != View.GONE) {
            row.visibility = View.GONE
            WeLogger.d(TAG, "hidden row: $label")
        }
    }
}

private fun firstText(view: View): String? {
    if (view is TextView) {
        val text = view.text?.toString()
        if (!text.isNullOrBlank()) return text
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            firstText(view.getChildAt(index))?.let { return it }
        }
    }
    return null
}
