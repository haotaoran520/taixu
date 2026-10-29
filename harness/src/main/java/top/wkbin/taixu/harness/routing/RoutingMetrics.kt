package top.wkbin.taixu.harness.routing

/** 评测样本：路由器排名 + 可接受工具集合（允许一题多解，方案 9.1）。 */
internal data class EvalSample(
    val taskId: String,
    val ranking: List<String>,
    val acceptable: Set<String>,
)

/** 汇总指标：只衡量路由器，不含模型决策与执行结果。 */
internal data class MetricsSummary(
    val sampleCount: Int,
    val recallAt1: Double,
    val recallAt3: Double,
    val recallAt5: Double,
    val mrr: Double,
    val ndcgAt5: Double,
)

/** 证据审计结果：理由里的引用能否被注册表/统计解析。 */
internal data class EvidenceAudit(
    val totalRefs: Int,
    val unresolvedRefs: List<String>,
) {
    val consistency: Double
        get() = if (totalRefs == 0) 1.0 else (totalRefs - unresolvedRefs.size).toDouble() / totalRefs
}

/**
 * 路由器评测指标（方案 9.2）。
 *
 * 刻意只覆盖"路由阶段"可离线计算的指标：Recall@K、MRR、NDCG@K、证据一致率。
 * 端到端完成率、参数正确率、重路由挽救率需要真实执行与验收器，属于阶段三。
 */
internal object RoutingMetrics {

    fun recallAtK(sample: EvalSample, k: Int): Double {
        if (sample.acceptable.isEmpty()) return 1.0
        val hit = sample.ranking.take(k).any { it in sample.acceptable }
        return if (hit) 1.0 else 0.0
    }

    /** MRR：第一个可接受工具的名次倒数。 */
    fun reciprocalRank(sample: EvalSample): Double {
        val index = sample.ranking.indexOfFirst { it in sample.acceptable }
        return if (index < 0) 0.0 else 1.0 / (index + 1)
    }

    /** NDCG@K：二值相关性（可接受=1），按 log2 折扣。 */
    fun ndcgAtK(sample: EvalSample, k: Int): Double {
        if (sample.acceptable.isEmpty()) return 1.0
        val dcg = sample.ranking.take(k).foldIndexed(0.0) { index, acc, toolId ->
            if (toolId in sample.acceptable) acc + 1.0 / log2(index + 2.0) else acc
        }
        val idealHits = minOf(k, sample.acceptable.size)
        val idcg = (0 until idealHits).sumOf { 1.0 / log2(it + 2.0) }
        return if (idcg == 0.0) 0.0 else dcg / idcg
    }

    fun summarize(samples: List<EvalSample>): MetricsSummary {
        if (samples.isEmpty()) {
            return MetricsSummary(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        return MetricsSummary(
            sampleCount = samples.size,
            recallAt1 = samples.map { recallAtK(it, 1) }.average(),
            recallAt3 = samples.map { recallAtK(it, 3) }.average(),
            recallAt5 = samples.map { recallAtK(it, 5) }.average(),
            mrr = samples.map { reciprocalRank(it) }.average(),
            ndcgAt5 = samples.map { ndcgAtK(it, 5) }.average(),
        )
    }

    /**
     * 证据一致率：每条 evidence_ref 都必须能被注册表或统计解析。
     *
     * 解析不了就说明理由里出现了"无法追溯的事实"，这正是阶段二要监控的可解释性风险。
     */
    fun auditEvidence(
        decision: RouteDecision,
        registry: List<ToolDescriptor>,
        taskClass: String,
    ): EvidenceAudit {
        val byId = registry.associateBy { it.toolId }
        var total = 0
        val unresolved = ArrayList<String>()

        decision.candidates.forEach { candidate ->
            candidate.rationale.evidenceRefs.forEach { ref ->
                total += 1
                if (!resolves(ref, candidate.tool, byId, taskClass)) {
                    unresolved += "${candidate.toolId} -> $ref"
                }
            }
        }
        return EvidenceAudit(total, unresolved)
    }

    private fun resolves(
        ref: String,
        tool: ToolDescriptor,
        byId: Map<String, ToolDescriptor>,
        taskClass: String,
    ): Boolean {
        val parts = ref.split(':')
        return when (parts.firstOrNull()) {
            "capability" -> parts.size >= 2 && (byId[parts[1]]?.toolId == parts[1])
            "history" -> parts.size >= 2 && byId.containsKey(parts[1])
            "evidence" -> parts.size >= 3 && parts[1] == "missing" && byId.containsKey(parts[2])
            "eval" -> {
                // eval:<taskClass>:<date>，必须是该候选在同类任务上的实测样本。
                val stat = tool.quality.statFor(taskClass)
                stat != null && stat.sampleCount > 0 && stat.evidence == EvidenceState.MEASURED
            }
            else -> false
        }
    }

    private fun log2(value: Double): Double = kotlin.math.ln(value) / kotlin.math.ln(2.0)
}
