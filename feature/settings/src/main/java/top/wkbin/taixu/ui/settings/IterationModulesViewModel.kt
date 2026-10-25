package top.wkbin.taixu.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.common.logging.AppLogger
import top.wkbin.taixu.core.database.SuccessfulStrategyRepository
import top.wkbin.taixu.core.datastore.IterationTuningStore
import top.wkbin.taixu.core.network.ObfuscationRuntime
import top.wkbin.taixu.core.network.TextObfuscator
import top.wkbin.taixu.harness.CascadePolicy
import top.wkbin.taixu.harness.RefusalCascade

/** 经验回放库里的一条成功策略（只用于设置页展示）。 */
data class StrategyPreview(
    val level: Int,
    val originalPrompt: String,
    val rewrittenPrompt: String,
    val createdAt: Long,
)

/** 四个迭代增强模块的完整界面状态。 */
data class IterationModulesState(
    val obfuscationEnabled: Boolean = IterationTuningStore.DEFAULT_OBFUSCATION,
    val refusalCascadeEnabled: Boolean = IterationTuningStore.DEFAULT_REFUSAL_CASCADE,
    val refusalMaxLevel: Int = IterationTuningStore.DEFAULT_REFUSAL_MAX_LEVEL,
    val pltGuardEnabled: Boolean = IterationTuningStore.DEFAULT_PLT_GUARD,
    val strategyReplayEnabled: Boolean = IterationTuningStore.DEFAULT_STRATEGY_REPLAY,
    val strategyCount: Int = 0,
    val recentStrategies: List<StrategyPreview> = emptyList(),
    val obfuscationSample: String = "",
    val obfuscationReady: Boolean = false,
    val pltGuardPackaged: Boolean = false,
    /** 导入词库弹窗里的草稿；[lexiconTerms] 是去掉注释与空行后的有效词条数。 */
    val lexiconDraft: String = "",
    val lexiconTerms: Int = 0,
    val lexiconNotice: String? = null,
)

/**
 * 乾坤「特色功能 → 迭代增强」的状态载体。
 *
 * 开关全部落在独立 DataStore（[IterationTuningStore]），写入即同步到运行时：
 * M1 → [ObfuscationRuntime]，M2 → [RefusalCascade.update]，M4 直接读既有 Room 实例。
 */
class IterationModulesViewModel(
    private val context: Context,
    private val tuning: IterationTuningStore,
    private val strategyRepository: SuccessfulStrategyRepository,
    private val refusalCascade: RefusalCascade,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(IterationModulesState())
    val state: StateFlow<IterationModulesState> = _state.asStateFlow()

    private data class Toggles(
        val obfuscation: Boolean,
        val cascade: Boolean,
        val maxLevel: Int,
        val pltGuard: Boolean,
        val replay: Boolean,
    )

    init {
        _state.update {
            it.copy(
                obfuscationReady = ObfuscationRuntime.isReady(),
                pltGuardPackaged = isPltGuardPackaged(),
                obfuscationSample = ObfuscationRuntime.preview(SAMPLE_TEXT),
            )
        }
        viewModelScope.launch {
            combine(
                tuning.obfuscationEnabled,
                tuning.refusalCascadeEnabled,
                tuning.refusalMaxLevel,
                tuning.pltGuardEnabled,
                tuning.strategyReplayEnabled,
            ) { obfuscation, cascade, maxLevel, pltGuard, replay ->
                Toggles(obfuscation, cascade, maxLevel, pltGuard, replay)
            }.collect { toggles ->
                ObfuscationRuntime.setEnabled(toggles.obfuscation)
                refusalCascade.update(CascadePolicy(enabled = toggles.cascade, maxLevel = toggles.maxLevel))
                _state.update {
                    it.copy(
                        obfuscationEnabled = toggles.obfuscation,
                        refusalCascadeEnabled = toggles.cascade,
                        refusalMaxLevel = toggles.maxLevel,
                        pltGuardEnabled = toggles.pltGuard,
                        strategyReplayEnabled = toggles.replay,
                    )
                }
            }
        }
        refreshStrategies()
    }

    fun setObfuscationEnabled(enabled: Boolean) = write("obfuscation") { tuning.setObfuscationEnabled(enabled) }

    fun setRefusalCascadeEnabled(enabled: Boolean) = write("cascade") { tuning.setRefusalCascadeEnabled(enabled) }

    fun setRefusalMaxLevel(level: Int) = write("maxLevel") { tuning.setRefusalMaxLevel(level) }

    fun setPltGuardEnabled(enabled: Boolean) = write("pltGuard") { tuning.setPltGuardEnabled(enabled) }

    fun setStrategyReplayEnabled(enabled: Boolean) = write("replay") { tuning.setStrategyReplayEnabled(enabled) }

    fun refreshStrategies() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val total = strategyRepository.count()
                val rows = strategyRepository.recentRows(RECENT_LIMIT).map { row ->
                    StrategyPreview(
                        level = row.strategyLevel,
                        originalPrompt = row.originalPrompt,
                        rewrittenPrompt = row.rewrittenPrompt,
                        createdAt = row.createdAt,
                    )
                }
                _state.update { it.copy(strategyCount = total, recentStrategies = rows) }
            }.onFailure { logger.w("IterationModulesViewModel: refresh strategies failed: ${it.message}", it) }
        }
    }

    fun clearStrategies() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { strategyRepository.clear() }
                .onFailure { logger.w("IterationModulesViewModel: clear strategies failed: ${it.message}", it) }
            refreshStrategies()
        }
    }

    /** 词库热更新：把用户输入的词表写进 hot file 并立即对下一次出站请求生效。 */
    fun updateLexicon(lexiconText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ObfuscationRuntime.updateLexicon(lexiconText)
            _state.update {
                it.copy(
                    obfuscationReady = ObfuscationRuntime.isReady(),
                    obfuscationSample = if (ok) ObfuscationRuntime.preview(SAMPLE_TEXT) else it.obfuscationSample,
                    lexiconTerms = countTerms(lexiconText),
                    lexiconNotice = if (ok) {
                        "已生效：${countTerms(lexiconText)} 条词条"
                    } else {
                        "写入被拒绝：运行时词库尚未加载"
                    },
                )
            }
            if (!ok) logger.w("IterationModulesViewModel: lexicon hot update rejected (runtime not attached)")
        }
    }

    fun onLexiconDraftChange(text: String) {
        _state.update { it.copy(lexiconDraft = text, lexiconTerms = countTerms(text), lexiconNotice = null) }
    }

    /** 把弹窗草稿写进 hot file；下一次出站请求即生效，无需重启。 */
    fun applyLexiconDraft() {
        val draft = _state.value.lexiconDraft
        if (countTerms(draft) == 0) {
            _state.update { it.copy(lexiconNotice = "词表为空：至少需要一行非注释词条") }
            return
        }
        updateLexicon(draft)
    }

    fun dismissLexiconNotice() {
        _state.update { it.copy(lexiconNotice = null) }
    }

    fun previewObfuscation(text: String) {
        _state.update { it.copy(obfuscationSample = ObfuscationRuntime.preview(text.ifBlank { SAMPLE_TEXT })) }
    }

    /** libguard_hook.so 是否随包安装（M3 只在打包后才可能生效）。 */
    fun isPltGuardPackaged(): Boolean = runCatching {
        val dir = context.applicationInfo?.nativeLibraryDir ?: return@runCatching false
        File(dir, PLT_GUARD_LIB).isFile
    }.getOrDefault(false)

    private fun write(tag: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
                .onFailure { logger.w("IterationModulesViewModel: persist $tag failed: ${it.message}", it) }
        }
    }

    /** 与 `.trie` 装载规则一致：忽略空行与 `#` 注释行。 */
    private fun countTerms(text: String): Int = text.lineSequence()
        .map { it.trim() }
        .count { it.isNotEmpty() && !it.startsWith("#") }

    companion object {
        private const val RECENT_LIMIT = 5
        private const val PLT_GUARD_LIB = "libguard_hook.so"
        const val SAMPLE_TEXT: String = "示例：请帮我处理这个敏感词清单"
        val lexiconAssetPath: String = TextObfuscator.ASSET_PATH
    }
}
