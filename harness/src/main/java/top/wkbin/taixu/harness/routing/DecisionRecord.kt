package top.wkbin.taixu.harness.routing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 任务结果判定：调用成功不等于任务完成，必须分开记录。 */
internal data class OutcomeAssessment(
    val taskCompleted: Boolean,
    val qualitySatisfied: Boolean,
    val note: String = "",
)

/**
 * 决策记录：把**路由器排名**与**模型选择**分开保存（阶段二验收重点 3）。
 *
 * 两者混在一起时，"排名好但模型选错"和"排名差导致选错"无法区分，
 * 也就无法回答方案 9.4 的"到底该改检索、改排序，还是改理由格式"。
 */
internal data class DecisionRecord(
    val subtaskId: String,
    /** 路由器排名（独立于模型选择）。 */
    val rankingToolIds: List<String>,
    val utilityByToolId: Map<String, Double>,
    val modelDecision: ModelDecision,
    val validation: SelectionValidation,
    val outcome: OutcomeAssessment? = null,
) {
    val top1ToolId: String? get() = rankingToolIds.firstOrNull()

    /** 模型是否选择了第一名。 */
    val followedTop1: Boolean
        get() = modelDecision.selectedToolIds.firstOrNull() == top1ToolId

    /** 被选中的第一项在候选中的名次（1 起）；未选择时为 null。 */
    val selectedRank: Int?
        get() {
            val first = modelDecision.selectedToolIds.firstOrNull() ?: return null
            val index = rankingToolIds.indexOf(first)
            return if (index >= 0) index + 1 else null
        }

    /** 是否放弃高排名而选了低排名候选（需要依据解释）。 */
    val choseLowRank: Boolean
        get() = selectedRank?.let { it > 1 } ?: false

    /** 模型选择越界（不在候选集合）时，必须标记为"需重新路由"。 */
    val requiresReroute: Boolean get() = !validation.accepted
}

/** 决策日志：与 [RoutingLog]（排名日志）分开，两者通过 subtask_id 关联。 */
internal object DecisionLog {

    fun toJson(record: DecisionRecord): JsonObject = buildJsonObject {
        put("subtask_id", record.subtaskId)
        put("record_kind", "model_selection")
        put("ranking", JsonArray(record.rankingToolIds.map { JsonPrimitive(it) }))
        put("utility_by_tool", buildJsonObject {
            record.utilityByToolId.forEach { (toolId, score) -> put(toolId, round(score)) }
        })
        put("decision", buildJsonObject {
            put("action", record.modelDecision.action.name.lowercase())
            put("selected_tool_ids", JsonArray(record.modelDecision.selectedToolIds.map { JsonPrimitive(it) }))
            put("selection_basis", record.modelDecision.selectionBasis)
            put("confidence", record.modelDecision.confidence)
            put("expected_observation", record.modelDecision.expectedObservation)
        })
        put("validation", buildJsonObject {
            put("accepted", record.validation.accepted)
            put("out_of_candidate_tools", JsonArray(record.validation.outOfCandidateTools.map { JsonPrimitive(it) }))
            put("message", record.validation.message)
        })
        put("followed_top1", record.followedTop1)
        put("selected_rank", record.selectedRank ?: 0)
        put("chose_low_rank", record.choseLowRank)
        put("requires_reroute", record.requiresReroute)
        record.outcome?.let { outcome ->
            put("outcome", buildJsonObject {
                put("task_completed", outcome.taskCompleted)
                put("quality_satisfied", outcome.qualitySatisfied)
                put("note", outcome.note)
            })
        }
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 1000) / 1000.0
}
