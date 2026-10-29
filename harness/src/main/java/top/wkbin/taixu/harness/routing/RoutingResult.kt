package top.wkbin.taixu.harness.routing

/** 硬约束过滤的排除原因：必须可解释，否则用户无法判断"为什么这个工具没被推荐"。 */
internal enum class ExclusionReason {
    MISSING_CAPABILITY,
    DISABLED,
    UNAVAILABLE,
    UNAUTHORIZED,
    READ_ONLY_VIOLATION,
    SAFETY_CONFIRMATION_REQUIRED,
    BLACKLISTED_IN_TASK,
}

/** 单条排除记录。 */
internal data class ToolExclusion(
    val toolId: String,
    val reason: ExclusionReason,
    val detail: String,
)

/** 单个评分维度的取值与证据状态。 */
internal data class ScoreComponent(
    val value: Double,
    val evidence: EvidenceState,
    val detail: String = "",
)

/**
 * 效用分数拆解：每个候选都能回答"这个总分是怎么来的"。
 *
 * 权重是**初始工程假设**，需由评测集校准；这里作为常量显式暴露，便于日志与实验对齐。
 */
internal data class ScoreBreakdown(
    val taskFit: ScoreComponent,
    val empiricalSuccess: ScoreComponent,
    val outputQuality: ScoreComponent,
    val reliability: ScoreComponent,
    val taskFeedback: ScoreComponent,
) {
    val utility: Double
        get() = WEIGHT_TASK_FIT * taskFit.value +
            WEIGHT_EMPIRICAL_SUCCESS * empiricalSuccess.value +
            WEIGHT_OUTPUT_QUALITY * outputQuality.value +
            WEIGHT_RELIABILITY * reliability.value +
            WEIGHT_TASK_FEEDBACK * taskFeedback.value

    /**
     * 置信度取最弱环节：实测数据缺失时整体只能是 UNKNOWN/LOW，
     * 不允许"分数很漂亮但其实是猜的"。
     */
    val confidence: ConfidenceLevel
        get() = when {
            empiricalSuccess.evidence != EvidenceState.MEASURED ||
                outputQuality.evidence != EvidenceState.MEASURED ||
                reliability.evidence != EvidenceState.MEASURED -> ConfidenceLevel.UNKNOWN
            else -> ConfidenceLevel.MEDIUM
        }

    companion object {
        const val WEIGHT_TASK_FIT = 0.35
        const val WEIGHT_EMPIRICAL_SUCCESS = 0.25
        const val WEIGHT_OUTPUT_QUALITY = 0.15
        const val WEIGHT_RELIABILITY = 0.15
        const val WEIGHT_TASK_FEEDBACK = 0.10
    }
}

/** 带证据化理由的候选。 */
internal data class RouteCandidate(
    val tool: ToolDescriptor,
    val rank: Int,
    val breakdown: ScoreBreakdown,
    val rationale: CandidateRationale,
) {
    val utility: Double get() = breakdown.utility
    val toolId: String get() = tool.toolId
}

/** 证据化理由：只允许引用已有能力字段与实测指标。 */
internal data class CandidateRationale(
    val advantages: List<String>,
    val limitations: List<String>,
    val comparison: String,
    val evidenceRefs: List<String>,
)

/** 路由结论。没有候选时必须明确拒绝，并说明缺什么能力。 */
internal data class RouteDecision(
    val subtask: SubTaskSpec,
    val candidates: List<RouteCandidate>,
    val exclusions: List<ToolExclusion>,
    val missingCapabilities: List<String>,
) {
    val hasCandidate: Boolean get() = candidates.isNotEmpty()

    val top: RouteCandidate? get() = candidates.firstOrNull()
}
