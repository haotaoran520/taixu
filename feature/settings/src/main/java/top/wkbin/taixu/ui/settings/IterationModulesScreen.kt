package top.wkbin.taixu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.core.datastore.IterationTuningStore
import top.wkbin.taixu.core.network.ZeroWidth
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.previewOrNull
import top.wkbin.taixu.ui.components.previewStateOf

/**
 * 乾坤「特色功能 → 迭代增强」：四个自迭代能力的统一入口。
 *
 * 每个模块一行开关 + 一行可操作项，开关写入独立 DataStore 并即时同步到运行时，
 * 因此这里看到的状态就是 Agent Loop 下一轮真正使用的状态。
 */
@Composable
fun IterationModulesScreen(
    onBack: () -> Unit,
    viewModel: IterationModulesViewModel? = previewOrNull { koinViewModel() },
) {
    val state by viewModel?.state?.collectAsStateWithLifecycle() ?: previewStateOf(IterationModulesState())
    var showLexiconDialog by remember { mutableStateOf(false) }

    PersonalizationScaffold("迭代增强", onBack) {
        item("iteration-intro") {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentPadding = PaddingValues(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RuntimeIcon(RuntimeIconName.Sparkles, Modifier.size(32.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        "以下四个能力默认与上游行为一致，可随时关闭。改动只影响本机后续请求，不需要重启应用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        // ---- 模块 1：出站零宽混淆 ----
        item("module-obfuscation") {
            PersonalizationSection("出站混淆（零宽）") {
                PreferenceSurface(first = true, last = true) {
                    ToggleRow(
                        icon = RuntimeIconName.VisibilityOff,
                        title = "出站零宽混淆",
                        subtitle = if (state.obfuscationReady) {
                            "命中词表时在字母间插入 U+200B/U+200C，入站 SSE 原样保留"
                        } else {
                            "运行时词库尚未加载，开关暂不生效"
                        },
                        checked = state.obfuscationEnabled,
                        change = { viewModel?.setObfuscationEnabled(it) },
                    )
                    SettingsRow(
                        icon = RuntimeIconName.Visibility,
                        title = "预览效果",
                        subtitle = "· = 插入的零宽字符；strip 后与原文完全一致",
                        onClick = { viewModel?.previewObfuscation(IterationModulesViewModel.SAMPLE_TEXT) },
                    )
                    SettingsRow(
                        icon = RuntimeIconName.Edit,
                        title = "导入词库（热更新）",
                        subtitle = if (state.lexiconTerms > 0) {
                            "当前草稿 ${state.lexiconTerms} 条词条；写入后下一轮出站立即生效"
                        } else {
                            "粘贴词表写入 filesDir，覆盖 assets 内置词库，无需重启"
                        },
                        value = state.lexiconNotice,
                        onClick = { showLexiconDialog = true },
                    )
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            visibleZeroWidth(state.obfuscationSample),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "词库：assets/" + IterationModulesViewModel.lexiconAssetPath + "（支持热更新）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // ---- 模块 2：SSE 拒答级联 ----
        item("module-cascade") {
            PersonalizationSection("拒答级联") {
                PreferenceSurface(first = true, last = true) {
                    ToggleRow(
                        icon = RuntimeIconName.Shield,
                        title = "SSE 拒答自动重试",
                        subtitle = "识别流首段多语言拒答特征后改写提示词重发；拒答轮不落库、不提交 tool_calls",
                        checked = state.refusalCascadeEnabled,
                        change = { viewModel?.setRefusalCascadeEnabled(it) },
                    )
                    SettingsRow(
                        icon = RuntimeIconName.Tune,
                        title = "重试上限",
                        subtitle = "S0 原样 → S4 天花板；点击切换。审批检查点不受重试影响",
                        value = "S${state.refusalMaxLevel}",
                        onClick = {
                            val next = (state.refusalMaxLevel + 1) % (IterationTuningStore.MAX_REFUSAL_MAX_LEVEL + 1)
                            viewModel?.setRefusalMaxLevel(next)
                        },
                    )
                }
            }
        }

        // ---- 模块 3：PLT 守卫 ----
        item("module-pltguard") {
            PersonalizationSection("文件访问守卫（PLT hook）") {
                PreferenceSurface(first = true, last = true) {
                    ToggleRow(
                        icon = RuntimeIconName.Storage,
                        title = "libguard_hook 重定向",
                        subtitle = if (state.pltGuardPackaged) {
                            "ByteHook PLT hook open/openat/fopen，命中规则时改读 assets 安全副本"
                        } else {
                            "当前构建未打包 libguard_hook.so，开启后也不会生效"
                        },
                        checked = state.pltGuardEnabled,
                        change = { viewModel?.setPltGuardEnabled(it) },
                        enabled = state.pltGuardPackaged,
                    )
                    SettingsRow(
                        icon = RuntimeIconName.Info,
                        title = "作用范围",
                        subtitle = "仅自身进程的 libc 调用，不注入、不 hook 其他应用；实验特性默认关闭",
                    )
                }
            }
        }

        // ---- 模块 4：经验回放 ----
        item("module-replay") {
            PersonalizationSection("经验回放（成功策略）") {
                PreferenceSurface(first = true, last = true) {
                    ToggleRow(
                        icon = RuntimeIconName.Brain,
                        title = "拒答成功后写入经验库",
                        subtitle = "命中关键词重叠的 top-10 记录作为 few-shot 拼在 system prompt 尾部",
                        checked = state.strategyReplayEnabled,
                        change = { viewModel?.setStrategyReplayEnabled(it) },
                    )
                    SettingsRow(
                        icon = RuntimeIconName.History,
                        title = "已收录策略",
                        subtitle = if (state.recentStrategies.isEmpty()) {
                            "暂无记录；拒答后成功的一轮会自动写入"
                        } else {
                            state.recentStrategies.joinToString("；") { recent ->
                                "S${recent.level} " + recent.originalPrompt.replace('\n', ' ').take(24)
                            }
                        },
                        value = "${state.strategyCount} 条",
                        onClick = { viewModel?.refreshStrategies() },
                    )
                    SettingsRow(
                        icon = RuntimeIconName.Trash,
                        title = "清空经验库",
                        subtitle = "只清理本机 successful_strategy 表，不影响对话历史",
                        onClick = { viewModel?.clearStrategies() },
                    )
                }
            }
        }

        item("iteration-footnote") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "实验特性，按需开启；关闭后行为与上游版本完全一致。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showLexiconDialog) {
        AlertDialog(
            onDismissRequest = { showLexiconDialog = false },
            title = { Text("导入词库（热更新）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "每行一个词条，# 开头为注释。写入 filesDir 后覆盖 assets 内置词库，只作用于出站请求。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.lexiconDraft,
                        onValueChange = { viewModel?.onLexiconDraftChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 10,
                        label = { Text("词表") },
                        supportingText = { Text("${state.lexiconTerms} 条有效词条") },
                    )
                    state.lexiconNotice?.let { notice ->
                        Text(
                            notice,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel?.applyLexiconDraft()
                        showLexiconDialog = false
                    },
                ) { Text("写入并生效") }
            },
            dismissButton = {
                TextButton(onClick = { showLexiconDialog = false }) { Text("取消") }
            },
        )
    }
}

private fun visibleZeroWidth(text: String): String =
    if (text.isEmpty()) "" else text.map { if (ZeroWidth.isZeroWidth(it)) '·' else it }.joinToString("")
