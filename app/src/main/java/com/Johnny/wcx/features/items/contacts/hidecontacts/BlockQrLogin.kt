package com.Johnny.wcx.features.items.contacts.hidecontacts

import android.app.Activity
import android.os.Bundle
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.utils.toClass

/**
 * 拦截扫码登录。
 *
 * 电脑/平板扫码登录时，手机端会弹出一个「登录确认」页。这个开关会让该页在创建后立即关闭，
 * 相当于手机端根本不给你点「确认登录」的机会——适合手机不在手上又怕被登的场景。
 *
 * 拦截点只看类名（登录确认页在两个版本里换过包），找不到就静默降级：
 * 页面照常弹出，只是没有拦截。不会影响任何其它功能。
 *
 * 默认关闭：开了之后自己扫码登录也会被拦（要先关掉再登录），所以交给用户自己决定。
 */
@Feature(
    name = "拦截扫码登录",
    categories = ["密友功能"],
    description = "别人扫码登录你的微信时，手机端的「登录确认」页会立即关闭并提示（自己也登不了，需先关闭本开关）",
)
object BlockQrLogin : SwitchFeature() {

    private const val TAG = "BlockQrLogin"

    /** 登录确认页的历史类名（换包时新增到这里即可）。 */
    private val LOGIN_UI_CLASSES = listOf(
        "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI",
        "com.tencent.mm.plugin.webwx.ui.LoginAuthUI",
    )

    override fun onEnable() {
        var hooked = 0
        LOGIN_UI_CLASSES.forEach { className ->
            val clazz = runCatching { className.toClass() }.getOrElse { null } ?: return@forEach
            val onCreate = runCatching { clazz.getDeclaredMethod("onCreate", Bundle::class.java) }
                .getOrElse { ex ->
                    WeLogger.w(TAG, "解析 $className.onCreate 失败", ex)
                    null
                } ?: return@forEach

            runCatching {
                XposedBridge.hookMethod(onCreate, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 必须在 after 里 finish：before 阶段跳过 onCreate 会触发
                        // SuperNotCalledException，整个进程崩掉。
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.isFinishing) return
                        WeLogger.i(TAG, "blocked qr login page: ${activity.javaClass.name}")
                        runCatching { activity.finish() }
                        runCatching { showToast("已拦截一次扫码登录") }
                    }
                })
                hooked++
            }.onFailure { ex ->
                WeLogger.w(TAG, "hook $className 失败", ex)
            }
        }
        if (hooked == 0) {
            WeLogger.w(TAG, "登录确认页类名未命中，扫码登录拦截不可用（不影响其它功能）")
        }
    }
}
