package top.wkbin.taixu.harness.routing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 结构化路由日志：阶段一的硬性交付物之一。
 *
 * 记录"候选为何入选/被过滤"，供离线评测与复盘；不含模型自由撰写的文本，
 * 全部字段都来自可复算的输入与分数拆解。
 *
 * 实现说明：JSON 一律用显式 `JsonArray` 构造，避免 `buildJsonObject`/`putJsonArray`
 * 嵌套 lambda 造成的隐式接收者二义（DSL_SCOPE_VIOLATION）。
 */
internal object RoutingLog {

    fun toJson(decision: RouteDecision): JsonObject = buildJsonObject {
        put("subtask_id", decision.subtask.id)
        put("task_class", decision.subtask.taskClass)
        put("goal", decision.subtask.goal)
        put("has_candidate", decision.hasCandidate)
        put("candidates", JsonArray(decision.candidates.map { candidateJson(it) }))
        put("exclusions", JsonArray(decision.exclusions.map { exclusionJson(it) }))
        put(
            "missing_capabilities",
            JsonArray(decision.missingCapabilities.map { JsonPrimitive(it) }),
        )
    }

    private fun candidateJson(candidate: RouteCandidate): JsonObject = buildJsonObject {
        put("tool_id", candidate.toolId)
        put("rank", candidate.rank)
        put("utility", round(candidate.utility))
        put("confidence", candidate.breakdown.confidence.name)
        put("breakdown", breakdownJson(candidate.breakdown))
        put("evidence_refs", JsonArray(candidate.rationale.evidenceRefs.map { JsonPrimitive(it) }))
        put("advantages", JsonArray(candidate.rationale.advantages.map { JsonPrimitive(it) }))
        put("limitations", JsonArray(candidate.rationale.limitations.map { JsonPrimitive(it) }))
    }

    private fun breakdownJson(breakdown: ScoreBreakdown): JsonObject = buildJsonObject {
        put("task_fit", round(breakdown.taskFit.value))
        put("task_fit_evidence", breakdown.taskFit.evidence.name)
        put("empirical_success", round(breakdown.empiricalSuccess.value))
        put("empirical_evidence", breakdown.empiricalSuccess.evidence.name)
        put("output_quality", round(breakdown.outputQuality.value))
        put("quality_evidence", breakdown.outputQuality.evidence.name)
        put("reliability", round(breakdown.reliability.value))
        put("reliability_evidence", breakdown.reliability.evidence.name)
        put("task_feedback", round(breakdown.taskFeedback.value))
    }

    private fun exclusionJson(exclusion: ToolExclusion): JsonObject = buildJsonObject {
        put("tool_id", exclusion.toolId)
        put("reason", exclusion.reason.name)
        put("detail", exclusion.detail)
    }

    /** 人类可读摘要：给模型看的候选清单（分数 + 理由 + 证据引用）。 */
    fun render(decision: RouteDecision): String = buildString {
        if (!decision.hasCandidate) {
            appendLine("未找到可行候选（路由已明确拒绝）")
            decision.missingCapabilities.forEach { appendLine("- 缺口：$it") }
            if (decision.exclusions.isNotEmpty()) {
                appendLine("被过滤的工具：")
                decision.exclusions.take(MAX_EXCLUSIONS).forEach {
                    appendLine("- ${it.toolId}：${it.reason}（${it.detail}）")
                }
            }
            return@buildString
        }
        decision.candidates.forEach { candidate ->
            appendLine(
                "候选 ${candidate.rank}：${candidate.toolId}  效用 ${round(candidate.utility)}  " +
                    "置信 ${candidate.breakdown.confidence}",
            )
            candidate.rationale.advantages.forEach { appendLine("  适合：$it") }
            candidate.rationale.limitations.forEach { appendLine("  限制：$it") }
            appendLine("  比较：${candidate.rationale.comparison}")
            if (candidate.rationale.evidenceRefs.isNotEmpty()) {
                appendLine("  证据：${candidate.rationale.evidenceRefs.joinToString(", ")}")
            }
        }
    }

    private fun round(value: Double): Double = kotlin.math.round(value * 1000) / 1000.0

    private const val MAX_EXCLUSIONS = 10
}
