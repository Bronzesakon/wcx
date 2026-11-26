package com.Johnny.wcx.utils

import android.content.res.Resources
import androidx.annotation.PluralsRes

/**
 * 安全版 `getQuantityString`。
 *
 * 模块里的 plurals 只提供了 `quantity="other"`（中文的复数规则只有 other，够用），但在
 * en / ru / ar 等语言的规则下，数量 1 需要 `one`、2~4 需要 `few` 等条目；一旦缺失，
 * 系统会直接抛 `Resources$NotFoundException`。这类调用都发生在主线程的点击回调里，
 * 异常没人接就会把微信整个进程带崩。
 *
 * 两级兜底：先按请求的数量取；失败则依次尝试其它常见数量，命中任意一个已存在的条目；
 * 仍然失败就返回空串并记日志，绝不抛异常。
 */
private val PLURAL_PROBE_QUANTITIES = intArrayOf(2, 5, 1, 11, 0, 100)

fun Resources.safeGetQuantityString(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String {
    runCatching { return getQuantityString(id, quantity, *formatArgs) }
    for (probe in PLURAL_PROBE_QUANTITIES) {
        runCatching { return getQuantityString(id, probe, *formatArgs) }
    }
    val name = runCatching { getResourceEntryName(id) }.getOrNull() ?: id.toString()
    WeLogger.w(TAG, "plurals $name has no entry for quantity=$quantity, fallback failed")
    return ""
}

private const val TAG = "SafeQuantityString"
