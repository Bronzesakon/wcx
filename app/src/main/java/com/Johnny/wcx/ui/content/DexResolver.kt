package com.Johnny.wcx.ui.content

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.dexkit.DexAdaptTask
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.restartHost
import java.io.PrintWriter
import java.io.StringWriter

/**
 * DEX 适配进度对话框 —— **纯观察者**。
 *
 * v284 起扫描由 [DexAdaptTask] 在进程级协程里跑，本对话框只订阅它的 `state`：
 *  - 打开即自动开始适配（不再需要点「开始适配」）；
 *  - 随时可以关闭，**适配会在后台继续**（旧实现一关窗口/一切后台，用户就以为停了）；
 *  - 关掉后再打开、或微信下次启动，仍能看到最新进度与结果。
 */
@Composable
fun DexResolver(
    context: Context,
    outdatedItems: List<IResolveDex>,
    dismiss: () -> Unit
) {
    val state by DexAdaptTask.state.collectAsState()

    // 打开即启动；start 是幂等的，且任务本身与这个 composition 无关，
    // 因此本 effect 被取消（关窗）也不会停掉扫描。
    LaunchedEffect(Unit) {
        DexAdaptTask.start(outdatedItems)
    }

    // v293: 登记「进度窗口可见」。适配结束时 [DexAdaptTask] 据此决定要不要补一个
    // 完成弹窗 —— 窗口还开着就由它自己显示完成态，用户关掉了才另弹。
    DisposableEffect(Unit) {
        DexAdaptTask.progressWindowVisible.set(true)
        onDispose { DexAdaptTask.progressWindowVisible.set(false) }
    }

    // v293: 窗口自己就是完成提示（自带「重启微信」按钮），认领掉，
    // 免得 FeaturesLoader 的监听协程再弹一个重复的。
    LaunchedEffect(state.finished) {
        if (state.finished) DexAdaptTask.claimCompletionPrompt()
    }

    val total = if (state.total > 0) state.total else outdatedItems.size
    val running = state.running
    val failed = state.failed

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DEX 缓存更新",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                if (running || !state.finished) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "$total",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider()

            val tipText = when {
                running ->
                    "正在后台适配 ${state.completed}/$total。点空白处或返回键可关闭本窗口, " +
                            "适配会在后台继续, 完成后重启微信生效"

                state.finished && failed.isEmpty() ->
                    "适配完成! 所有功能已成功更新 DEX 缓存"

                state.finished && failed.isNotEmpty() ->
                    "适配完成, 但有 ${failed.size} 个功能失败 (不影响其他功能使用)"

                else ->
                    "检测到 $total 个功能需要更新 DEX 缓存, 正在启动扫描…"
            }
            Text(text = tipText, style = MaterialTheme.typography.bodyMedium)

            AnimatedVisibility(visible = running) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = state.current, style = MaterialTheme.typography.bodyMedium)
                    LinearWavyProgressIndicator(
                        progress = {
                            if (total == 0) 0f else state.completed.toFloat() / total
                        },
                        modifier = Modifier.fillMaxWidth(),
                        amplitude = { progress ->
                            if (progress == 0f || progress == 1f) 0f else 1f
                        }
                    )
                    Text(
                        text = "总进度: ${state.completed}/$total",
                        style = MaterialTheme.typography.labelSmall
                    )
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) // indeterminate sub-bar
                }
            }

            AnimatedVisibility(visible = failed.isNotEmpty()) {
                ErrorDetailsSection(
                    failedResults = failed,
                    onCopy = {
                        copyToClipboard(context, buildErrorReport(failed))
                        showToast(context, "已复制")
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                when {
                    // v292: 去掉「后台运行」按钮 —— 适配本来就在后台跑, 这个按钮只会让人以为
                    // 点了才会切到后台。要关窗口直接点空白处 / 返回键 (见 showComposeDialog
                    // 的 directlyDismissable), 进度不受影响。
                    running -> Unit

                    state.finished -> {
                        TextButton(onClick = dismiss) { Text("关闭") }
                        Button(onClick = {
                            dismiss()
                            restartHost()
                        }) { Text("重启微信") }
                    }

                    else -> TextButton(onClick = dismiss) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * v293: 适配**完成弹窗** —— 用户关掉进度窗口后，适配跑完时由
 * [com.Johnny.wcx.features.core.FeaturesLoader] 补弹的这个。
 *
 * v292 把进度窗口改成「可直接关闭」后出现一个断档：适配完成时只剩一条 Toast
 * （“DEX 适配完成，重启微信后生效”），没有「重启微信」按钮，用户看完文字并不知道
 * 去哪重启。这个弹窗就是补上那个动作入口：进度窗口还开着时不会弹（窗口自己带按钮），
 * 只有被用户关掉了才由 [DexAdaptTask.claimCompletionPrompt] 认领后弹出。
 */
@Composable
fun DexAdaptCompletion(
    context: Context,
    total: Int,
    success: Int,
    failed: List<DexAdaptTask.AdaptItemState>,
    dismiss: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "DEX 适配完成",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            HorizontalDivider()

            Text(
                text = if (failed.isEmpty()) {
                    "已成功更新 $success/$total 个功能的 DEX 缓存, 重启微信后生效。"
                } else {
                    "已更新 $success/$total 个功能, 但有 ${failed.size} 个失败 " +
                        "(不影响其他功能使用), 重启微信后生效。"
                },
                style = MaterialTheme.typography.bodyMedium
            )

            if (failed.isNotEmpty()) {
                ErrorDetailsSection(
                    failedResults = failed,
                    onCopy = {
                        copyToClipboard(context, buildErrorReport(failed))
                        showToast(context, "已复制")
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = dismiss) { Text("稍后再说") }
                Button(onClick = {
                    dismiss()
                    restartHost()
                }) { Text("重启微信") }
            }
        }
    }
}

@Composable
private fun ErrorDetailsSection(
    failedResults: List<DexAdaptTask.AdaptItemState>,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val errorText = buildString {
                failedResults.forEachIndexed { i, r ->
                    append("${i + 1}. ${r.name}\n")
                    append("   错误: ${r.message}\n\n")
                }
            }
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
            )
            TextButton(onClick = onCopy) { Text("复制错误信息") }
        }
    }
}

private fun buildErrorReport(failedResults: List<DexAdaptTask.AdaptItemState>) = buildString {
    append("=== WCX Dex 扫描错误报告 ===\n\n")
    failedResults.forEachIndexed { i, r ->
        append("${i + 1}. ${r.name}\n")
        append("   错误信息: ${r.message}\n")
        append("   耗时: ${r.elapsedMs}ms\n")
        append("   堆栈跟踪:\n")
        val sw = StringWriter()
        Exception(r.message).printStackTrace(PrintWriter(sw))
        append(sw.toString())
        append("\n\n")
    }
}
