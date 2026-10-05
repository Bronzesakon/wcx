package com.Johnny.wcx.features.items.payment

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.nul
import com.Johnny.wcx.utils.reflection.BString
import com.Johnny.wcx.utils.reflection.bool
import java.math.BigDecimal
import java.math.RoundingMode

@Feature(name = "修改显示余额", categories = ["红包与支付"], description = "伪装钱包余额文字\n零钱与零钱通分开设置：各自支持固定文本或实时增减（发红包后自动变化）")
object ModifyWalletBalanceDisplay : ClickableFeature(), IResolveDex {

    /** 零钱 */
    private const val KEY_BALANCE = "fake_wallet_balance"
    private const val KEY_REALTIME = "fake_wallet_balance_realtime"
    private const val KEY_REALTIME_OFFSET = "fake_wallet_balance_realtime_offset"

    /** 零钱通 */
    private const val KEY_LQT_BALANCE = "fake_wallet_lqt_balance"
    private const val KEY_LQT_REALTIME = "fake_wallet_lqt_realtime"
    private const val KEY_LQT_REALTIME_OFFSET = "fake_wallet_lqt_realtime_offset"

    /** 零钱余额显示 setter（微信 8.0.69-8.0.77 实测：WcPayMoneyLoadingView.setFirstMoney）。 */
    private val methodSetFirstMoney by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
            name = "setFirstMoney"
            paramTypes(BString)
        }
    }

    /** 零钱通余额显示 setter（微信 8.0.69-8.0.77 实测：WcPayMoneyLoadingView.setNewMoney）。 */
    private val methodSetNewMoney by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
            name = "setNewMoney"
            paramTypes(BString)
        }
    }

    /**
     * 8.0.77 实测：网络结算场景返回后（onGYNetEnd/doScene 回调）会用真实余额再次调用
     * WcPayMoneyLoadingView.e(String, boolean) 刷新显示，导致伪装金额"显示一秒后恢复"。
     * 该入口按零钱配置覆盖；匹配不到时静默跳过（allowFailure），不影响其它版本。
     */
    private val methodE by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
            name = "e"
            paramTypes(BString, bool)
        }
    }

    /**
     * 8.0.77 实测：动画统一入口 WcPayMoneyLoadingView.Ti(String) 一次调用同时设置
     * setFirstMoney + setNewMoney + g(String, boolean)。为防动画层直接用真实值刷新，
     * g 亦按零钱配置覆盖；匹配不到时静默跳过。
     */
    private val methodG by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
            name = "g"
            paramTypes(BString, bool)
        }
    }

    /** 兜底：WcPayMoneyLoadingView.setMoney(String)（8.0.77 亦有调用者），按零钱配置覆盖。 */
    private val methodSetMoney by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
            name = "setMoney"
            paramTypes(BString)
        }
    }

    /** 零钱固定伪装文本（实时模式关闭时生效）。 */
    private var balance by prefOption(KEY_BALANCE, nul<String>())

    /** 零钱实时增减开关：显示 = 真实零钱余额 ± 金额。 */
    private var realtimeEnabled by prefOption(KEY_REALTIME, false)

    /** 零钱实时增减金额（元），正加负减，支持小数；留空视为 0。 */
    private var realtimeOffsetYuan by prefOption(KEY_REALTIME_OFFSET, "")

    /** 零钱通固定伪装文本（实时模式关闭时生效）。 */
    private var lqtBalance by prefOption(KEY_LQT_BALANCE, nul<String>())

    /** 零钱通实时增减开关：显示 = 真实零钱通余额 ± 金额。 */
    private var lqtRealtimeEnabled by prefOption(KEY_LQT_REALTIME, false)

    /** 零钱通实时增减金额（元），正加负减，支持小数；留空视为 0。 */
    private var lqtRealtimeOffsetYuan by prefOption(KEY_LQT_REALTIME_OFFSET, "")

    override fun onEnable() {
        // 零钱余额：setFirstMoney
        methodSetFirstMoney.hookBefore {
            val raw = args[0] as? String ?: return@hookBefore
            args[0] = applyConfig(raw, balance, realtimeEnabled, realtimeOffsetYuan)
        }
        // 零钱通余额：setNewMoney
        methodSetNewMoney.hookBefore {
            val raw = args[0] as? String ?: return@hookBefore
            args[0] = applyConfig(raw, lqtBalance, lqtRealtimeEnabled, lqtRealtimeOffsetYuan)
        }
        // 8.0.77 反查：网络结算场景结束（onGYNetEnd/doScene）会用真实余额再次调用
        // e(String, boolean) 刷新显示，是"显示一秒后恢复"的来源。存在时按零钱配置覆盖；
        // 匹配不到（其它版本改名）时静默跳过，不影响既有 hook。
        if (!methodE.isPlaceholder && !methodG.isPlaceholder) {
            methodE.hookBefore {
                val raw = args[0] as? String ?: return@hookBefore
                args[0] = applyConfig(raw, balance, realtimeEnabled, realtimeOffsetYuan)
            }
            methodG.hookBefore {
                val raw = args[0] as? String ?: return@hookBefore
                args[0] = applyConfig(raw, balance, realtimeEnabled, realtimeOffsetYuan)
            }
        }
        // 兜底：setMoney(String) 调用者（8.0.77 亦存在），同样按零钱配置覆盖。
        if (!methodSetMoney.isPlaceholder) {
            methodSetMoney.hookBefore {
                val raw = args[0] as? String ?: return@hookBefore
                args[0] = applyConfig(raw, balance, realtimeEnabled, realtimeOffsetYuan)
            }
        }
    }

    /**
     * 实时模式：在微信真实余额数字上增减并保留原格式；否则用固定文本。
     *
     * 幂等防护（raw→输出 映射）：服务页等路径的余额文本会经过多个 hook 点，两条独立路径
     * 收到同一份原始文本会各自变换（叠加成双倍）——对同一原始文本**重放返回同一输出**；
     * 收到的文本是某次变换的输出（链式下发）时直接放行。碰撞（真实余额恰好等于输出）无害。
     */
    private fun applyConfig(raw: String, fixed: String?, realtime: Boolean, offsetText: String): String {
        if (realtime) {
            val offset = offsetText.trim().toDoubleOrNull() ?: return raw
            // key 含配置组合: 改 offset/开关后旧输出自动失效(修复"改配置要重启微信才生效")
            val key = "$offset|$raw"
            synchronized(rawToOutput) {
                rawToOutput[key]?.let { return it }
                if (rawToOutput.containsValue(raw)) return raw
            }
            val out = applyRealtimeOffset(raw, offset) ?: return raw
            synchronized(rawToOutput) {
                rawToOutput[key] = out
                while (rawToOutput.size > 64) {
                    val it = rawToOutput.entries.iterator()
                    if (it.hasNext()) { it.next(); it.remove() } else break
                }
            }
            return out
        }
        return fixed ?: raw
    }

    /** raw→输出 LRU（access-order，64 条上限），见 [applyConfig]。 */
    private val rawToOutput = LinkedHashMap<String, String>(64, 0.75f, true)

    /** 金额数字：兼容千分位逗号（"1,234.56"）与普通数字（"1234.56"）。 */
    private val AMOUNT_RE = Regex("(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)")

    /**
     * 在真实余额文本的数字部分上增减 offset（元），保留前缀/后缀格式。
     * - 千分位：原串带逗号时按原格式回填（修复旧版只匹配到逗号前数字导致 "0,234.56"/"---" 的问题）；
     * - 结果为负：微信金额组件不支持负数（显示 ---），钳制为 0.00。
     */
    private fun applyRealtimeOffset(raw: String, offsetYuan: Double): String? {
        val m = AMOUNT_RE.find(raw) ?: return null
        val grouped = m.groupValues[1].contains(',')
        val realYuan = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val prefix = raw.substring(0, m.range.first)
        val suffix = raw.substring(m.range.last + 1)
        val result = BigDecimal.valueOf(realYuan).add(BigDecimal.valueOf(offsetYuan))
        if (result.signum() < 0) return prefix + "0.00" + suffix
        val shown = result
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return prefix + (if (grouped) withThousandsSeparator(shown) else shown) + suffix
    }

    /** "1234567.89" → "1,234,567.89"（仅整数部分加千分位，小数部分原样）。 */
    private fun withThousandsSeparator(amount: String): String {
        val dot = amount.indexOf('.')
        val intPart = if (dot >= 0) amount.substring(0, dot) else amount
        val fracPart = if (dot >= 0) amount.substring(dot) else ""
        val sb = StringBuilder(intPart.length + intPart.length / 3)
        for (i in intPart.indices) {
            sb.append(intPart[i])
            val remaining = intPart.length - 1 - i
            if (remaining > 0 && remaining % 3 == 0) sb.append(',')
        }
        return sb.toString() + fracPart
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var balanceInput by remember { mutableStateOf(balance ?: "") }
            var rtState by remember { mutableStateOf(realtimeEnabled) }
            var rtOffsetInput by remember { mutableStateOf(realtimeOffsetYuan) }
            var lqtBalanceInput by remember { mutableStateOf(lqtBalance ?: "") }
            var lqtRtState by remember { mutableStateOf(lqtRealtimeEnabled) }
            var lqtRtOffsetInput by remember { mutableStateOf(lqtRealtimeOffsetYuan) }

            AlertDialogContent(
                title = { Text("修改显示余额") },
                text = {
                    DefaultColumn(scrollable = true) {
                        // ===== 零钱 =====
                        Text(
                            "零钱",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        ListItem(
                            modifier = Modifier.clickable { rtState = !rtState },
                            trailingContent = { Switch(checked = rtState, onCheckedChange = null) },
                            headlineContent = { Text("实时增减") },
                            supportingContent = { Text("显示 = 真实零钱 ± 金额，发红包后自动变化") }
                        )
                        if (rtState) {
                            OutlinedTextField(
                                value = rtOffsetInput,
                                onValueChange = { rtOffsetInput = it },
                                label = { Text("增减金额(元)，正加负减，可小数，如 10 或 -5.5") }
                            )
                        } else {
                            TextField(
                                value = balanceInput,
                                onValueChange = { balanceInput = it },
                                label = { Text("零钱固定余额 (留空不修改)") })
                        }
                        // ===== 零钱通 =====
                        Text(
                            "零钱通",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        ListItem(
                            modifier = Modifier.clickable { lqtRtState = !lqtRtState },
                            trailingContent = { Switch(checked = lqtRtState, onCheckedChange = null) },
                            headlineContent = { Text("实时增减") },
                            supportingContent = { Text("显示 = 真实零钱通 ± 金额，发红包后自动变化") }
                        )
                        if (lqtRtState) {
                            OutlinedTextField(
                                value = lqtRtOffsetInput,
                                onValueChange = { lqtRtOffsetInput = it },
                                label = { Text("增减金额(元)，正加负减，可小数，如 10 或 -5.5") }
                            )
                        } else {
                            TextField(
                                value = lqtBalanceInput,
                                onValueChange = { lqtBalanceInput = it },
                                label = { Text("零钱通固定余额 (留空不修改)") })
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        realtimeEnabled = rtState
                        realtimeOffsetYuan = rtOffsetInput
                        if (!rtState) {
                            balance = if (!balanceInput.isBlank()) balanceInput else null
                        }
                        lqtRealtimeEnabled = lqtRtState
                        lqtRealtimeOffsetYuan = lqtRtOffsetInput
                        if (!lqtRtState) {
                            lqtBalance = if (!lqtBalanceInput.isBlank()) lqtBalanceInput else null
                        }
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}