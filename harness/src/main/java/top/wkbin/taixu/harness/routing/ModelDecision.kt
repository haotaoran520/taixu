package top.wkbin.taixu.harness.routing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 模型决策动作（方案 6.5）。 */
internal enum class DecisionAction {
    CALL_TOOL,
    CALL_MULTIPLE,
    SEARCH_MORE,
    NO_CALL,
    UNKNOWN,
    ;

    val executesTool: Boolean get() = this == CALL_TOOL || this == CALL_MULTIPLE
}

/** 模型最终决策：只记录"选了什么、依据是什么"，不要求输出思维链。 */
internal data class ModelDecision(
    val action: DecisionAction,
    val selectedToolIds: List<String>,
    val selectionBasis: String,
    val confidence: String,
    val expectedObservation: String,
)

/** 决策校验结果：越界选择必须拒绝执行，而不是"尽力而为"地照着调。 */
internal data class SelectionValidation(
    val accepted: Boolean,
    val outOfCandidateTools: List<String>,
    val message: String,
)

internal object ModelDecisionParser {

    fun parse(json: JsonObject): ModelDecision {
        val action = when (json.str("decision")?.lowercase()) {
            "call_tool" -> DecisionAction.CALL_TOOL
            "call_multiple" -> DecisionAction.CALL_MULTIPLE
            "search_more" -> DecisionAction.SEARCH_MORE
            "no_call" -> DecisionAction.NO_CALL
            else -> DecisionAction.UNKNOWN
        }
        val selected = buildList {
            json.str("selected_tool_id")?.takeIf { it.isNotBlank() }?.let { add(it) }
            json["selected_tool_ids"]?.let { element ->
                runCatching { element.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
                    ?.filter { it.isNotBlank() }?.let { addAll(it) }
            }
        }.distinct()
        return ModelDecision(
            action = action,
            selectedToolIds = selected,
            selectionBasis = json.str("selection_basis").orEmpty(),
            confidence = json.str("confidence").orEmpty(),
            expectedObservation = json.str("expected_observation").orEmpty(),
        )
    }

    /**
     * 校验：只能从候选集合中选择。
     *
     * 方案 6.5 明确要求——若模型选择不在候选集合中的工具，客户端应拒绝直接执行，
     * 并要求路由器重新检索或说明候选集为何不充分。
     */
    fun validate(decision: ModelDecision, candidates: List<RouteCandidate>): SelectionValidation {
        if (!decision.action.executesTool) {
            return SelectionValidation(
                accepted = true,
                outOfCandidateTools = emptyList(),
                message = when (decision.action) {
                    DecisionAction.SEARCH_MORE -> "模型要求继续检索，未执行工具"
                    DecisionAction.NO_CALL -> "模型选择不调用工具"
                    else -> "决策动作无法识别，按不执行处理"
                },
            )
        }
        val allowed = candidates.map { it.toolId }.toSet()
        val outside = decision.selectedToolIds.filterNot { it in allowed }
        if (decision.selectedToolIds.isEmpty()) {
            return SelectionValidation(false, emptyList(), "声称调用工具但未给出 selected_tool_id，拒绝执行并要求重新决策")
        }
        if (outside.isNotEmpty()) {
            return SelectionValidation(
                accepted = false,
                outOfCandidateTools = outside,
                message = "选择不在候选集合内：${outside.joinToString(", ")}；已拒绝执行，请重新检索或说明候选集为何不充分",
            )
        }
        return SelectionValidation(true, emptyList(), "选择均在候选集合内，允许执行")
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
