package top.wkbin.taixu.harness.routing

/**
 * 效用打分器：把"能力契合 + 实测成功 + 结果质量 + 运行可靠性 + 任务内反馈"折算成可拆解的分数。
 *
 * 三条硬规则（来自方案约束）：
 * 1. 成本、Token、是否已连接、是否已被调用 **不进入** 打分；
 * 2. 数据不足时给中性值与 UNKNOWN 证据，而不是给 0 或凭空高分；
 * 3. 小样本按 Beta 先验收缩，"1/1 全胜"不得压过 "95/100"。
 */
internal object UtilityScorer {

    /** 先验均值：无数据时假设"可能行也可能不行"。 */
    const val PRIOR_MEAN = 0.5

    /** 先验强度：约等于 4 次虚拟观测，样本少时强力拉回中性。 */
    const val PRIOR_STRENGTH = 4.0

    /** 中性值：用于"该项无证据"，使总分不被单点缺失放大。 */
    const val NEUTRAL = 0.5

    fun score(
        tool: ToolDescriptor,
        spec: SubTaskSpec,
        feedback: ToolTaskFeedback,
    ): ScoreBreakdown {
        val caps = tool.capabilities
        return ScoreBreakdown(
            taskFit = taskFit(spec, caps),
            empiricalSuccess = empiricalSuccess(tool, spec),
            outputQuality = outputQuality(tool, spec),
            reliability = reliability(tool),
            taskFeedback = taskFeedback(feedback),
        )
    }

    /** 能力契合度：各硬条件分组的覆盖率均值。无硬条件时给中性值并标注无证据。 */
    private fun taskFit(spec: SubTaskSpec, caps: ToolCapabilities): ScoreComponent {
        val groups = mutableListOf<Pair<String, Double>>()
        if (spec.requiredOperations.isNotEmpty()) {
            groups += "操作" to overlap(spec.requiredOperations, caps.operations)
        }
        if (spec.requiredDomains.isNotEmpty()) {
            groups += "领域" to overlap(spec.requiredDomains, caps.domains)
        }
        if (spec.requiredInputTypes.isNotEmpty()) {
            groups += "输入类型" to overlap(spec.requiredInputTypes, caps.inputTypes)
        }
        if (spec.expectedOutputTypes.isNotEmpty()) {
            groups += "输出类型" to overlap(spec.expectedOutputTypes, caps.outputTypes)
        }
        if (spec.requiresFreshData) {
            groups += "实时数据" to if (caps.freshness == FRESHNESS_LIVE) 1.0 else 0.0
        }
        if (groups.isEmpty()) {
            return ScoreComponent(NEUTRAL, EvidenceState.UNKNOWN, "子任务未声明硬条件，无法量化契合度")
        }
        val value = groups.map { it.second }.average().coerceIn(0.0, 1.0)
        val weak = groups.filter { it.second < 1.0 }.map { "${it.first}覆盖 ${(it.second * 100).toInt()}%" }
        val detail = if (weak.isEmpty()) "全部硬条件满足" else weak.joinToString("；")
        // 能力标签是本地推断/工具自述，不是实测：证据状态最高 DECLARED。
        return ScoreComponent(value, EvidenceState.DECLARED, detail)
    }

    private fun overlap(required: Set<String>, actual: Set<String>): Double {
        if (required.isEmpty()) return 1.0
        return required.count { it in actual }.toDouble() / required.size
    }

    private fun empiricalSuccess(tool: ToolDescriptor, spec: SubTaskSpec): ScoreComponent {
        val stat = tool.quality.statFor(spec.taskClass)
            ?: return ScoreComponent(
                NEUTRAL,
                EvidenceState.UNKNOWN,
                if (spec.taskClass.isBlank()) "子任务未标注任务类别，无法取用同类统计" else "该任务类别暂无实测样本",
            )
        if (stat.sampleCount <= 0) {
            return ScoreComponent(NEUTRAL, EvidenceState.UNKNOWN, "样本数为 0")
        }
        val smoothed = smooth(stat.successRate, stat.sampleCount)
        return ScoreComponent(
            smoothed,
            stat.evidence,
            "同类样本 ${stat.sampleCount} 次，原始成功率 ${fmt(stat.successRate)}，先验平滑后 ${fmt(smoothed)}",
        )
    }

    private fun outputQuality(tool: ToolDescriptor, spec: SubTaskSpec): ScoreComponent {
        val stat = tool.quality.statFor(spec.taskClass)
            ?: return ScoreComponent(NEUTRAL, EvidenceState.UNKNOWN, "该任务类别暂无质量实测数据")
        if (stat.sampleCount <= 0) {
            return ScoreComponent(NEUTRAL, EvidenceState.UNKNOWN, "样本数为 0")
        }
        val smoothed = smooth(stat.resultQuality, stat.sampleCount)
        return ScoreComponent(
            smoothed,
            stat.evidence,
            "样本 ${stat.sampleCount} 次，原始质量分 ${fmt(stat.resultQuality)}",
        )
    }

    private fun reliability(tool: ToolDescriptor): ScoreComponent {
        val rel = tool.quality.reliability
        if (!rel.hasEvidence) {
            return ScoreComponent(NEUTRAL, EvidenceState.UNKNOWN, "无调用可靠性数据")
        }
        val bad = rel.failureCount + rel.timeoutCount + rel.schemaViolationCount
        val rate = (bad.toDouble() / rel.invocationCount).coerceIn(0.0, 1.0)
        val smoothedRate = smooth(rate, rel.invocationCount)
        val score = (1.0 - smoothedRate).coerceIn(0.0, 1.0)
        return ScoreComponent(score, rel.evidence, "调用 ${rel.invocationCount} 次，异常 ${bad} 次")
    }

    private fun taskFeedback(feedback: ToolTaskFeedback): ScoreComponent {
        if (feedback.isEmpty) {
            return ScoreComponent(NEUTRAL, EvidenceState.UNKNOWN, "本任务内尚无调用记录")
        }
        var value = NEUTRAL
        value += 0.25 * feedback.successCount
        value -= 0.35 * feedback.failureCount
        value -= 0.35 * feedback.qualityFailureCount
        val clamped = value.coerceIn(0.0, 1.0)
        val detail = buildString {
            append("本任务成功 ${feedback.successCount}")
            append("、失败 ${feedback.failureCount}")
            if (feedback.qualityFailureCount > 0) append("、质量不合格 ${feedback.qualityFailureCount}")
            feedback.lastFailureType?.let { append("，最近失败类型 $it") }
        }
        return ScoreComponent(clamped, EvidenceState.MEASURED, detail)
    }

    /** Beta 先验平滑：少量样本向 [PRIOR_MEAN] 收缩。 */
    fun smooth(observedRate: Double, sampleCount: Int): Double {
        if (sampleCount <= 0) return PRIOR_MEAN
        val n = sampleCount.toDouble()
        return ((observedRate * n) + PRIOR_MEAN * PRIOR_STRENGTH) / (n + PRIOR_STRENGTH)
    }

    private fun fmt(value: Double): String = String.format("%.2f", value)

    const val FRESHNESS_LIVE = "live_fetch"
}
