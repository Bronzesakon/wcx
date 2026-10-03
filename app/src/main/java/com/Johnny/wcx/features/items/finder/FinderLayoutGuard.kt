package com.Johnny.wcx.features.items.finder

import android.text.StaticLayout
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

/**
 * 视频号崩溃防御：翻评论时 `com.tencent.mm.plugin.finder.view.m7` 计算出的排版宽度可能为负
 * （实测 -42），传入 StaticLayout 后 `Layout.<init>` 抛 `IllegalArgumentException: Layout: -42 < 0`
 * 直接崩掉微信（8.0.76/77 原生问题，栈内无模块代码）。
 *
 * 在 [StaticLayout.Builder.build] 入口把负宽度钳制为 0：该条评论排版为空行，但不崩。
 * 反射字段缓存为 lazy，hookBefore 本身轻量。
 */
@Feature(
    name = "视频号崩溃防御",
    categories = ["视频号"],
    description = "修复视频号翻评论时 StaticLayout 收到负宽度导致的崩溃（Layout: -42 < 0）"
)
object FinderLayoutGuard : SwitchFeature() {

    private const val TAG = "FinderLayoutGuard"

    private val widthField by lazy {
        runCatching {
            StaticLayout.Builder::class.java.getDeclaredField("mWidth").apply { isAccessible = true }
        }.getOrNull()
    }

    override fun onEnable() {
        val field = widthField ?: run {
            WeLogger.w(TAG, "StaticLayout.Builder.mWidth 未找到, 防御不可用")
            return
        }
        StaticLayout.Builder::class.java.getDeclaredMethod("build").hookBefore {
            val builder = thisObject as? StaticLayout.Builder ?: return@hookBefore
            val width = runCatching { field.getInt(builder) }.getOrNull() ?: return@hookBefore
            if (width >= 0) return@hookBefore
            runCatching { field.setInt(builder, 0) }
            WeLogger.d(TAG, "clamped negative StaticLayout width $width to 0")
        }
    }
}
