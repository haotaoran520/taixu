package top.wkbin.taixu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.wkbin.taixu.feature.settings.R
import top.wkbin.taixu.ui.components.RuntimeCard
import top.wkbin.taixu.ui.components.RuntimeIcon
import top.wkbin.taixu.ui.components.RuntimeIconName
import top.wkbin.taixu.ui.components.previewOrNull
import top.wkbin.taixu.ui.components.previewStateOf

/** Creative destinations and background services share the adaptive settings shell. */
@Composable
fun FeatureHubScreen(
    onBack: () -> Unit,
    onOpenCustomIteration: () -> Unit,
    onStartRoundtable: () -> Unit,
    onOpenA2uiPoc: () -> Unit,
    viewModel: FeatureHubViewModel? = previewOrNull { koinViewModel() },
) {
    // 迭代增强四模块共用本页：内部状态切换子页，导航栈不动（TaiXuNavHost 有尺寸棘轮基线）。
    var showIterationModules by remember { mutableStateOf(false) }
    if (showIterationModules) {
        BackHandler { showIterationModules = false }
        IterationModulesScreen(onBack = { showIterationModules = false })
        return
    }

    val sentinelState by viewModel?.sentinelState?.collectAsStateWithLifecycle() ?: previewStateOf(top.wkbin.taixu.harness.workflow.SentinelState())
    val webChatStatus by viewModel?.webChatStatus?.collectAsStateWithLifecycle() ?: previewStateOf(top.wkbin.taixu.runtime.webchat.WebChatServerStatus())

    PersonalizationScaffold(stringResource(R.string.settings_feature_hub_title), onBack) {
        item("features-intro") {
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.primaryContainer, contentPadding = PaddingValues(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RuntimeIcon(RuntimeIconName.Sparkles, Modifier.size(40.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(R.string.settings_material_features_intro),
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item("creative-features") {
            PersonalizationSection(stringResource(R.string.settings_material_creation)) {
                FeatureLinkRow(RuntimeIconName.Code,
                    stringResource(R.string.settings_feature_custom_iteration_title),
                    stringResource(R.string.settings_feature_custom_iteration_subtitle),
                    first = true, last = false, onClick = onOpenCustomIteration)
                FeatureLinkRow(RuntimeIconName.Community,
                    stringResource(R.string.settings_feature_roundtable_title),
                    stringResource(R.string.settings_feature_roundtable_subtitle),
                    first = false, last = false, onClick = onStartRoundtable)
                FeatureLinkRow(RuntimeIconName.Palette,
                    stringResource(R.string.settings_feature_a2ui_title),
                    stringResource(R.string.settings_feature_a2ui_subtitle),
                    first = false, last = true, onClick = onOpenA2uiPoc)
            }
        }
        item("iteration-modules") {
            PersonalizationSection(stringResource(R.string.settings_iteration_modules_title)) {
                FeatureLinkRow(RuntimeIconName.VisibilityOff,
                    stringResource(R.string.settings_feature_obfuscation_title),
                    stringResource(R.string.settings_feature_obfuscation_subtitle),
                    first = true, last = false, onClick = { showIterationModules = true })
                FeatureLinkRow(RuntimeIconName.Shield,
                    stringResource(R.string.settings_feature_cascade_title),
                    stringResource(R.string.settings_feature_cascade_subtitle),
                    first = false, last = false, onClick = { showIterationModules = true })
                FeatureLinkRow(RuntimeIconName.Storage,
                    stringResource(R.string.settings_feature_pltguard_title),
                    stringResource(R.string.settings_feature_pltguard_subtitle),
                    first = false, last = false, onClick = { showIterationModules = true })
                FeatureLinkRow(RuntimeIconName.Brain,
                    stringResource(R.string.settings_feature_replay_title),
                    stringResource(R.string.settings_feature_replay_subtitle),
                    first = false, last = true, onClick = { showIterationModules = true })
            }
        }
        item("scheduled-service") {
            PersonalizationSection(stringResource(R.string.settings_material_automation)) {
                SentinelControlRow(state = sentinelState,
                    onEnable = { hour, minute -> viewModel?.enableSentinel(hour, minute) },
                    onDisable = { viewModel?.disableSentinel() })
            }
        }
        item("desktop-service") {
            PersonalizationSection(stringResource(R.string.settings_material_connection)) {
                WebChatControlRow(status = webChatStatus, onToggle = { viewModel?.toggleWebChat(it) })
            }
        }
    }
}
