package top.wkbin.taixu.harness.routing

/**
 * 证据化理由生成器。
 *
 * 顺序固定为"数据 → 计算排序 → 由排序依据生成解释"，**绝不**反过来用理由文本反推分数。
 * 每条理由必须能指回一个字段或一次统计；没有数据就写"暂无"，不允许编造性能结论。
 */
internal class RoutingRationaleBuilder {

    fun build(
        tool: ToolDescriptor,
        spec: SubTaskSpec,
        breakdown: ScoreBreakdown,
        ranked: List<Pair<ToolDescriptor, ScoreBreakdown>>,
        position: Int,
        peers: List<ToolDescriptor> = emptyList(),
    ): CandidateRationale {
        val advantages = ArrayList<String>()
        val limitations = ArrayList<String>()
        val evidenceRefs = ArrayList<String>()

        // 1. 能力契合：来自 capability 字段，属自述/推断证据。
        val matched = matchedCapabilities(tool, spec)
        if (matched.isNotEmpty()) {
            advantages += "覆盖本任务要求：${matched.joinToString("、")}"
            evidenceRefs += "capability:${tool.toolId}"
        }
        if (breakdown.taskFit.evidence == EvidenceState.DECLARED) {
            advantages += "硬条件满足度 ${pct(breakdown.taskFit.value)}（${breakdown.taskFit.detail}）"
        }

        // 2. 实测表现：只有 MEASURED 才允许进优点，且必须带样本量。
        val stat = tool.quality.statFor(spec.taskClass)
        if (stat != null && stat.evidence == EvidenceState.MEASURED && stat.sampleCount > 0) {
            advantages += "同类任务实测 ${stat.sampleCount} 次，成功率 ${pct(stat.successRate)}" +
                "（先验平滑后 ${pct(breakdown.empiricalSuccess.value)}）"
            evidenceRefs += "eval:${stat.taskClass}:${stat.lastEvaluatedAt ?: "undated"}"
        }

        // 3. 限制：工具自述约束 + 未满足的硬条件。
        limitations += tool.constraints
        val unmet = unmatchedRequirements(tool, spec)
        if (unmet.isNotEmpty()) {
            limitations += "不满足本任务条件：${unmet.joinToString("；")}"
        }

        // 4. 数据不足时必须显式说明，而不是沉默。
        if (stat == null || stat.sampleCount == 0) {
            limitations += "该任务类别暂无实测样本，排名主要基于能力匹配"
            evidenceRefs += "evidence:missing:${tool.toolId}"
        }
        if (breakdown.confidence == ConfidenceLevel.UNKNOWN) {
            limitations += "置信度未知（缺少实测统计）"
        }

        return CandidateRationale(
            advantages = advantages.ifEmpty {
                listOf("未找到明确的能力优势证据（工具描述与能力标签均未命中任务要求）")
            },
            limitations = limitations.ifEmpty { listOf("未记录已知限制") },
            comparison = compare(tool, spec, breakdown, ranked, position, peers),
            evidenceRefs = evidenceRefs.distinct(),
        )
    }

    /** 与同族或次优候选做差异比较；没有可比对象时明确说"无可比候选"。 */
    private fun compare(
        tool: ToolDescriptor,
        spec: SubTaskSpec,
        breakdown: ScoreBreakdown,
        ranked: List<Pair<ToolDescriptor, ScoreBreakdown>>,
        position: Int,
        peers: List<ToolDescriptor>,
    ): String {
        val competitor = ranked.drop(position + 1).firstOrNull { it.first.familyId == tool.familyId && tool.familyId.isNotBlank() }
            ?: ranked.getOrNull(position + 1)
            ?: return "本任务只有单一候选，无可比对象"

        val other = competitor.first
        val otherScore = competitor.second
        val gap = breakdown.utility - otherScore.utility
        val relation = when {
            gap > 0 -> "高于"
            gap < 0 -> "低于"
            else -> "持平于"
        }
        val reason = when {
            breakdown.taskFit.value > otherScore.taskFit.value -> "硬条件覆盖更好"
            breakdown.empiricalSuccess.value > otherScore.empiricalSuccess.value -> "同类任务实测成功率更高"
            breakdown.outputQuality.value > otherScore.outputQuality.value -> "历史结果质量更高"
            breakdown.reliability.value > otherScore.reliability.value -> "运行更稳定"
            else -> "能力标签覆盖面相当"
        }
        // 同族横向差异：只引用两侧都显式声明的字段，值原样引用。
        val deltas = FamilyDifferentials.deltas(tool, peers)
        val gapNotes = FamilyDifferentials.peerGaps(tool, peers)
        val deltaText = buildString {
            if (deltas.isNotEmpty()) {
                append("；同族差异：")
                append(deltas.joinToString("；") { it.render() })
            }
            if (gapNotes.isNotEmpty()) {
                append("；覆盖优势：")
                append(gapNotes.joinToString("；"))
            }
        }
        return "相对 ${other.toolId} $relation ${pct(kotlin.math.abs(gap))}：$reason" +
            if (other.constraints.isNotEmpty()) "；对方限制：${other.constraints.first()}" else "" +
            deltaText
    }

    private fun matchedCapabilities(tool: ToolDescriptor, spec: SubTaskSpec): List<String> {
        val caps = tool.capabilities
        return buildList {
            spec.requiredOperations.filter { it in caps.operations }.forEach { add("操作 $it") }
            spec.requiredDomains.filter { it in caps.domains }.forEach { add("领域 $it") }
            spec.requiredInputTypes.filter { it in caps.inputTypes }.forEach { add("输入 $it") }
            spec.expectedOutputTypes.filter { it in caps.outputTypes }.forEach { add("输出 $it") }
        }
    }

    private fun unmatchedRequirements(tool: ToolDescriptor, spec: SubTaskSpec): List<String> = buildList {
        val caps = tool.capabilities
        spec.requiredOperations.filterNot { it in caps.operations }.forEach { add("缺少操作 $it") }
        spec.requiredDomains.filterNot { it in caps.domains }.forEach { add("缺少领域 $it") }
        spec.requiredInputTypes.filterNot { it in caps.inputTypes }.forEach { add("不支持输入 $it") }
        spec.expectedOutputTypes.filterNot { it in caps.outputTypes }.forEach { add("不产出 $it") }
        if (spec.requiresFreshData && caps.freshness != UtilityScorer.FRESHNESS_LIVE) {
            add("不保证实时数据")
        }
    }

    private fun pct(value: Double): String = "${(value * 100).toInt()}%"
}
