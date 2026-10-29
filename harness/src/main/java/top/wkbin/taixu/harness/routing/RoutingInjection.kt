package top.wkbin.taixu.harness.routing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 注入变体（对应方案 9.3 的消融实验 A–F）。
 *
 * 保留这些变体不是为了"功能多"，而是为了让"理由是否真的有用"可被实验回答：
 * 同一批任务、同一模型下只切换注入形态，比较端到端完成率。
 */
internal enum class InjectionVariant {
    /** A：模型直接面对全部工具，无检索、无排序、无理由。 */
    A_ALL_TOOLS,

    /** B：候选检索 + 模型选择，无分数、无理由。 */
    B_RETRIEVAL,

    /** C：候选排序 + 分数，不注入理由。 */
    C_RANKING,

    /** D：候选排序 + 证据化理由 + 模型最终决策（阶段二目标形态）。 */
    D_RANKING_WITH_RATIONALE,

    /** E：D + 结果验证要求（阶段三）。 */
    E_WITH_VERIFICATION,

    /** F：E + 按任务类别的历史质量统计（阶段三/四）。 */
    F_WITH_HISTORY,
    ;

    val includesRanking: Boolean get() = this != A_ALL_TOOLS && this != B_RETRIEVAL
    val includesScore: Boolean get() = includesRanking
    val includesRationale: Boolean get() = this == D_RANKING_WITH_RATIONALE ||
        this == E_WITH_VERIFICATION || this == F_WITH_HISTORY
    val requiresVerification: Boolean get() = this == E_WITH_VERIFICATION || this == F_WITH_HISTORY
    val includesHistory: Boolean get() = this == F_WITH_HISTORY
}

/**
 * 候选注入渲染（方案 6.4）。
 *
 * 关键约束：
 * - `utility_score` 只是**排序信号**，注入文本里明确写"不是必须选第一名"；
 * - 理由与事实绑定，缺数据写"暂无充分数据"，不补造性能；
 * - 候选必须带输入 Schema 摘要，避免"选对工具却用错参数"。
 */
internal object RoutingInjection {

    private const val SELECTION_MODE = "model_select_from_candidates"

    fun build(
        decision: RouteDecision,
        variant: InjectionVariant = InjectionVariant.D_RANKING_WITH_RATIONALE,
        allTools: List<ToolDescriptor> = emptyList(),
        includeSchema: Boolean = true,
        schemaCharLimit: Int = 600,
    ): JsonObject = buildJsonObject {
        put("subtask_id", decision.subtask.id)
        put("selection_mode", SELECTION_MODE)
        put("variant", variant.name)
        put("acceptance_criteria", JsonArray(decision.subtask.acceptanceCriteria.map { JsonPrimitive(it) }))
        val pool: List<RouteCandidate> = when (variant) {
            InjectionVariant.A_ALL_TOOLS -> allTools
                .filter { it.isEligible }
                .mapIndexed { index, tool ->
                    RouteCandidate(
                        tool = tool,
                        rank = index + 1,
                        breakdown = UtilityScorer.score(tool, decision.subtask, ToolTaskFeedback()),
                        rationale = CandidateRationale(emptyList(), emptyList(), "", emptyList()),
                    )
                }
            else -> decision.candidates
        }
        put(
            "candidates",
            JsonArray(pool.map { candidateJson(it, decision.subtask.taskClass, variant, includeSchema, schemaCharLimit) }),
        )
        if (variant.requiresVerification) put("verification_required", true)
        if (!decision.hasCandidate && decision.missingCapabilities.isNotEmpty()) {
            put("missing_capabilities", JsonArray(decision.missingCapabilities.map { JsonPrimitive(it) }))
        }
    }

    private fun candidateJson(
        candidate: RouteCandidate,
        taskClass: String,
        variant: InjectionVariant,
        includeSchema: Boolean,
        schemaCharLimit: Int,
    ): JsonObject = buildJsonObject {
        put("tool_id", candidate.toolId)
        if (variant.includesRanking) put("rank", candidate.rank)
        if (variant.includesScore) {
            put("utility_score", round(candidate.utility))
            put("confidence", candidate.breakdown.confidence.name.lowercase())
        }
        if (variant.includesRationale) {
            put("advantages", JsonArray(candidate.rationale.advantages.map { JsonPrimitive(it) }))
            put("limitations", JsonArray(candidate.rationale.limitations.map { JsonPrimitive(it) }))
            put("comparison", candidate.rationale.comparison)
            put("evidence_refs", JsonArray(candidate.rationale.evidenceRefs.map { JsonPrimitive(it) }))
        }
        if (variant.includesHistory) {
            put("history", historyJson(candidate))
            // 只暴露本任务类别的实测样本量；没有就显式标 0，不虚构。
            val stat = candidate.tool.quality.statFor(taskClass)
            put("history_samples", stat?.takeIf { it.evidence == EvidenceState.MEASURED }?.sampleCount ?: 0)
        }
        if (includeSchema) {
            val schema = candidate.tool.inputSchemaSummary
            if (schema.isNotBlank()) {
                put("input_schema", schema.take(schemaCharLimit))
            }
        }
    }

    /** 历史质量统计：只暴露实测值；无实测时显式标 unknown。 */
    private fun historyJson(candidate: RouteCandidate): JsonObject = buildJsonObject {
        put("utility_score", round(candidate.utility))
        put("confidence", candidate.breakdown.confidence.name.lowercase())
        put("empirical_evidence", candidate.breakdown.empiricalSuccess.evidence.name)
        put("empirical_detail", candidate.breakdown.empiricalSuccess.detail)
        put("reliability_detail", candidate.breakdown.reliability.detail)
    }

    /** 给模型的可读注入块：JSON 之外补上选择规则（规则不进 JSON，避免与数据混淆）。 */
    fun renderText(
        decision: RouteDecision,
        variant: InjectionVariant = InjectionVariant.D_RANKING_WITH_RATIONALE,
        allTools: List<ToolDescriptor> = emptyList(),
    ): String = buildString {
        appendLine("【候选工具】subtask_id=${decision.subtask.id}  变体=${variant.name}")
        appendLine(build(decision, variant, allTools).toString())
        appendLine()
        appendLine("选择规则：")
        appendLine("- utility_score 只是排序信号，不是必须选第一名；")
        appendLine("- 若选择排名更低的候选，须基于其实际能力优势，并说明依据；")
        appendLine("- 未知信息视为未知，不得补造性能事实；")
        appendLine("- 只能从候选集合中选择；选择集合外工具将被拒绝并要求重新检索。")
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 1000) / 1000.0
}
