package com.Johnny.wcx.features.items.contacts.hidecontacts

import android.app.Activity
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass

private const val TAG = "HideContactsSearchCmd"

/** 微信主页搜索 UI（类名稳定，指令拦截点）。 */
private const val FTS_MAIN_SEARCH_UI = "com.tencent.mm.plugin.fts.ui.FTSBaseMainUI"

/**
 * 主页搜索框临时显示指令（默认 `#wm#`）。
 *
 * 拦截点：`FTSBaseMainUI` 上「单 String 入参 + void 返回」的逐字搜索回调。
 * 该方法每版都可能改名，同类又常有多个 `void(String)`，所以全量按形状挂载、按指令文字
 * 精确比对：不匹配立刻返回，误挂无副作用。
 *
 * 命中后按 [HideContacts.searchCommandMinutes] 临时显示（期间名单内联系人全部可见），
 * 到期自动恢复隐藏，并退出搜索页。
 */
internal fun HideContacts.installSearchCommandHook() {
    val clazz = runCatching { FTS_MAIN_SEARCH_UI.toClass() }.getOrElse { ex ->
        WeLogger.w(TAG, "FTSBaseMainUI 未找到，搜索框指令不可用", ex)
        null
    } ?: return

    val methods = runCatching {
        clazz.reflekt().methods {
            parameters(String::class)
            returnType(Void.TYPE)
        }
    }.getOrElse { ex ->
        WeLogger.w(TAG, "枚举 FTSBaseMainUI 的 void(String) 失败，搜索框指令不可用", ex)
        return
    }
    if (methods.isEmpty()) {
        WeLogger.w(TAG, "FTSBaseMainUI 没有 void(String) 方法，搜索框指令不可用")
        return
    }

    methods.forEach { method ->
        method.hookBefore {
            val query = (args.getOrNull(0) as? String)?.trim() ?: return@hookBefore
            val command = searchCommandText.trim()
            if (command.isEmpty() || !query.equals(command, ignoreCase = true)) return@hookBefore

            val activity = thisObject as? Activity ?: return@hookBefore
            WeLogger.d(TAG, "search command matched, temporarily show for ${searchCommandMinutes}m")
            temporarilyShowFor(activity, searchCommandMinutes)
            // 指令已生效，退出搜索页回到会话列表，避免把指令当成真实搜索词。
            runCatching { activity.finish() }
        }
    }
}
