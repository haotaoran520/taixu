package top.wkbin.taixu.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.iterationTuningDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "iteration_tuning")

/**
 * 迭代增强四个能力的用户可调开关。
 *
 * 独立 DataStore（不复用 [SettingsDataStore]）以避免触达架构检查的尺寸棘轮基线。
 * 默认值与上游行为保持一致：拒答级联与经验回放默认开启，零宽混淆与 PLT 守卫是实验特性默认关闭。
 */
class IterationTuningStore(private val context: Context) {

    private val obfuscationKey = booleanPreferencesKey("obfuscation_enabled")
    private val refusalCascadeKey = booleanPreferencesKey("refusal_cascade_enabled")
    private val refusalMaxLevelKey = intPreferencesKey("refusal_max_level")
    private val pltGuardKey = booleanPreferencesKey("plt_guard_enabled")
    private val strategyReplayKey = booleanPreferencesKey("strategy_replay_enabled")

    // ---- M1 出站零宽混淆 ----
    val obfuscationEnabled: Flow<Boolean> =
        context.iterationTuningDataStore.data.map { it[obfuscationKey] ?: DEFAULT_OBFUSCATION }

    suspend fun setObfuscationEnabled(value: Boolean) {
        context.iterationTuningDataStore.edit { it[obfuscationKey] = value }
    }

    // ---- M2 拒答级联 ----
    val refusalCascadeEnabled: Flow<Boolean> =
        context.iterationTuningDataStore.data.map { it[refusalCascadeKey] ?: DEFAULT_REFUSAL_CASCADE }

    suspend fun setRefusalCascadeEnabled(value: Boolean) {
        context.iterationTuningDataStore.edit { it[refusalCascadeKey] = value }
    }

    val refusalMaxLevel: Flow<Int> =
        context.iterationTuningDataStore.data.map { it[refusalMaxLevelKey] ?: DEFAULT_REFUSAL_MAX_LEVEL }

    suspend fun setRefusalMaxLevel(value: Int) {
        context.iterationTuningDataStore.edit {
            it[refusalMaxLevelKey] = value.coerceIn(MIN_REFUSAL_MAX_LEVEL, MAX_REFUSAL_MAX_LEVEL)
        }
    }

    // ---- M3 PLT 守卫（libguard_hook.so） ----
    val pltGuardEnabled: Flow<Boolean> =
        context.iterationTuningDataStore.data.map { it[pltGuardKey] ?: DEFAULT_PLT_GUARD }

    suspend fun setPltGuardEnabled(value: Boolean) {
        context.iterationTuningDataStore.edit { it[pltGuardKey] = value }
    }

    // ---- M4 经验回放 ----
    val strategyReplayEnabled: Flow<Boolean> =
        context.iterationTuningDataStore.data.map { it[strategyReplayKey] ?: DEFAULT_STRATEGY_REPLAY }

    suspend fun setStrategyReplayEnabled(value: Boolean) {
        context.iterationTuningDataStore.edit { it[strategyReplayKey] = value }
    }

    companion object {
        const val DEFAULT_OBFUSCATION: Boolean = false
        const val DEFAULT_REFUSAL_CASCADE: Boolean = true
        const val DEFAULT_REFUSAL_MAX_LEVEL: Int = 3
        const val MIN_REFUSAL_MAX_LEVEL: Int = 0
        const val MAX_REFUSAL_MAX_LEVEL: Int = 4
        const val DEFAULT_PLT_GUARD: Boolean = false
        const val DEFAULT_STRATEGY_REPLAY: Boolean = true
    }
}
