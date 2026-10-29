package top.wkbin.taixu.harness.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段二验收 1&2：同族差异只引用已声明字段；数据不足明确标未知；路由指标可算。 */
class FamilyAndMetricsTest {

    private val tc = "page"

    private fun tool(
        id: String,
        differentiators: Map<String, String> = emptyMap(),
        family: String = "web.read",
        globalRel: ReliabilityStat = ReliabilityStat(),
        classRel: Map<String, ReliabilityStat> = emptyMap(),
        stat: TaskClassStat? = null,
    ) = ToolDescriptor(
        toolId = id,
        serverId = id.substringBefore('.'),
        name = id.substringAfter('.'),
        description = "read page content",
        familyId = family,
        capabilities = ToolCapabilities(domains = setOf("web"), operations = setOf("read")),
        quality = ToolQualityProfile(
            taskClasses = stat?.let { mapOf(tc to it) } ?: emptyMap(),
            reliability = globalRel,
            reliabilityByTaskClass = classRel,
        ),
        familyDifferentiators = differentiators,
    )

    private fun spec() = SubTaskSpec(
        id = "s", goal = "read page content", taskClass = tc,
        requiredOperations = setOf("read"), requiredDomains = setOf("web"),
    )

    @Test
    fun `同族差异只引用已声明维度`() {
        val a = tool("srv.a", mapOf("rendering" to "static_only", "output" to "text"))
        val b = tool("srv.b", mapOf("rendering" to "javascript", "output" to "text"))

        val deltas = FamilyDifferentials.deltas(a, listOf(a, b))

        assertEquals(1, deltas.size)
        assertEquals("rendering", deltas.first().dimension)
        assertTrue(deltas.first().render().contains("rendering=static_only"))
    }

    @Test
    fun `未声明差异字段时不生成任何差异`() {
        val a = tool("srv.a")
        val b = tool("srv.b")

        assertTrue(FamilyDifferentials.deltas(a, listOf(a, b)).isEmpty())
    }

    @Test
    fun `按任务类别的可靠性优先于全局回退`() {
        val perClass = tool(
            "srv.per_class",
            globalRel = ReliabilityStat(100, 0, 0, 0, EvidenceState.MEASURED),
            classRel = mapOf(tc to ReliabilityStat(10, 8, 1, 0, EvidenceState.MEASURED)),
        )
        val globalOnly = tool(
            "srv.global_only",
            globalRel = ReliabilityStat(100, 0, 0, 0, EvidenceState.MEASURED),
        )

        val perClassScore = UtilityScorer.score(perClass, spec(), ToolTaskFeedback()).reliability
        val globalScore = UtilityScorer.score(globalOnly, spec(), ToolTaskFeedback()).reliability

        assertTrue("同类可靠性差应显著低于全局回退", perClassScore.value < globalScore.value)
        assertTrue(perClassScore.detail.contains("本任务类别"))
        assertTrue(globalScore.detail.contains("全局回退"))
    }

    @Test
    fun `Recall@K MRR NDCG 计算正确`() {
        val sample = EvalSample(taskId = "t1", ranking = listOf("a", "b", "c"), acceptable = setOf("b"))

        assertEquals(0.0, RoutingMetrics.recallAtK(sample, 1), 1e-9)
        assertEquals(1.0, RoutingMetrics.recallAtK(sample, 3), 1e-9)
        assertEquals(0.5, RoutingMetrics.reciprocalRank(sample), 1e-9)
        assertTrue(RoutingMetrics.ndcgAtK(sample, 5) in 0.5..1.0)
    }

    @Test
    fun `多解可接受集合与汇总指标`() {
        val samples = listOf(
            EvalSample("t1", listOf("a", "b"), setOf("b")),
            EvalSample("t2", listOf("c", "d"), setOf("c")),
        )

        val summary = RoutingMetrics.summarize(samples)

        assertEquals(2, summary.sampleCount)
        assertEquals(0.5, summary.recallAt1, 1e-9)
        assertEquals(1.0, summary.recallAt3, 1e-9)
        assertEquals(0.75, summary.mrr, 1e-9)
    }

    @Test
    fun `证据一致率能识别不可追溯引用`() {
        val bare = tool("srv.bare")
        val decision = ToolRoutingEngine(listOf(bare)).route(spec())

        val healthy = RoutingMetrics.auditEvidence(decision, listOf(bare), tc)
        assertEquals(1.0, healthy.consistency, 1e-9)

        val brokenCandidate = decision.candidates.first().copy(
            rationale = decision.candidates.first().rationale.copy(
                evidenceRefs = listOf("eval:page:2026-01-01"),
            ),
        )
        val broken = decision.copy(candidates = listOf(brokenCandidate))
        val audit = RoutingMetrics.auditEvidence(broken, listOf(bare), tc)

        assertFalse(audit.unresolvedRefs.isEmpty())
        assertEquals(0.0, audit.consistency, 1e-9)
    }
}
