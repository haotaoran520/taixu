package top.wkbin.taixu.harness.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段一验收点：
 * 1. 能解释候选为何入选或被过滤；
 * 2. 找不到可行候选时明确拒绝路由；
 * 3. 候选不会仅因"已连接/已缓存"获得额外优先级。
 */
class ToolRoutingEngineTest {

    private val taskClass = "page_extraction"

    private fun tool(
        id: String,
        operations: Set<String> = setOf("read"),
        domains: Set<String> = setOf("web"),
        readOnly: Boolean = true,
        availability: AvailabilityState = AvailabilityState.AVAILABLE,
        availabilityReason: String = "",
        stat: TaskClassStat? = null,
        reliability: ReliabilityStat = ReliabilityStat(),
        constraints: List<String> = emptyList(),
        family: String = "web.read",
    ) = ToolDescriptor(
        toolId = id,
        serverId = id.substringBefore('.'),
        name = id.substringAfter('.'),
        description = "fetch page content",
        familyId = family,
        capabilities = ToolCapabilities(domains = domains, operations = operations, readOnly = readOnly),
        constraints = constraints,
        quality = ToolQualityProfile(
            taskClasses = stat?.let { mapOf(taskClass to it) } ?: emptyMap(),
            reliability = reliability,
        ),
        availability = availability,
        availabilityReason = availabilityReason,
    )

    private fun spec(
        operations: Set<String> = setOf("read"),
        domains: Set<String> = setOf("web"),
        readOnlyOnly: Boolean = false,
        goal: String = "fetch page content",
    ) = SubTaskSpec(
        id = "subtask_01",
        goal = goal,
        taskClass = taskClass,
        requiredOperations = operations,
        requiredDomains = domains,
        readOnlyOnly = readOnlyOnly,
    )

    @Test
    fun `被禁用的工具进入排除清单并说明原因`() {
        val engine = ToolRoutingEngine(
            listOf(
                tool("srv.fetch_ok"),
                tool("srv.fetch_disabled", availability = AvailabilityState.DISABLED, availabilityReason = "用户关闭"),
            ),
        )

        val decision = engine.route(spec())

        assertEquals(listOf("srv.fetch_ok"), decision.candidates.map { it.toolId })
        val exclusion = decision.exclusions.single { it.toolId == "srv.fetch_disabled" }
        assertEquals(ExclusionReason.DISABLED, exclusion.reason)
        assertEquals("用户关闭", exclusion.detail)
    }

    @Test
    fun `没有可行候选时明确拒绝并给出能力缺口`() {
        val engine = ToolRoutingEngine(listOf(tool("srv.fetch_only")))

        val decision = engine.route(spec(operations = setOf("delete"), domains = setOf("web")))

        assertFalse(decision.hasCandidate)
        assertTrue(decision.candidates.isEmpty())
        assertTrue(decision.exclusions.any { it.reason == ExclusionReason.MISSING_CAPABILITY })
        assertTrue(
            "缺口诊断必须说明缺什么能力",
            decision.missingCapabilities.any { it.contains("delete") },
        )
    }

    @Test
    fun `仅有不可用工具时缺口诊断指向不可用工具`() {
        val engine = ToolRoutingEngine(
            listOf(tool("srv.fetch_disabled", availability = AvailabilityState.DISABLED)),
        )

        val decision = engine.route(spec())

        assertFalse(decision.hasCandidate)
        assertTrue(
            decision.missingCapabilities.any { it.contains("srv.fetch_disabled") },
        )
    }

    @Test
    fun `连接状态与列表顺序都不影响效用分数`() {
        val a = tool("srv.a_fetch")
        val b = tool("srv.b_fetch")
        val c = tool("srv.c_fetch")

        val forward = ToolRoutingEngine(listOf(a, b, c)).route(spec())
        val reversed = ToolRoutingEngine(listOf(c, b, a)).route(spec())

        val forwardScores = forward.candidates.associate { it.toolId to it.utility }
        val reversedScores = reversed.candidates.associate { it.toolId to it.utility }
        assertEquals(forwardScores, reversedScores)
        // 三个工具能力完全相同 → 分数持平，排名只由 toolId 决定（确定性），不因"谁先连接"改变。
        assertEquals(1.0, forwardScores.values.distinct().size.toDouble(), 0.0)
    }

    @Test
    fun `少量样本不得压过大量样本`() {
        val lucky = tool(
            "srv.lucky_fetch",
            stat = TaskClassStat(taskClass, successRate = 1.0, resultQuality = 1.0, sampleCount = 1),
            reliability = ReliabilityStat(invocationCount = 1, evidence = EvidenceState.MEASURED),
        )
        val proven = tool(
            "srv.proven_fetch",
            stat = TaskClassStat(taskClass, successRate = 0.95, resultQuality = 0.9, sampleCount = 100),
            reliability = ReliabilityStat(invocationCount = 100, failureCount = 0, evidence = EvidenceState.MEASURED),
        )

        val decision = ToolRoutingEngine(listOf(lucky, proven)).route(spec())

        assertEquals("srv.proven_fetch", decision.candidates.first().toolId)
        val luckyScore = decision.candidates.first { it.toolId == "srv.lucky_fetch" }.utility
        val provenScore = decision.candidates.first { it.toolId == "srv.proven_fetch" }.utility
        assertTrue("平滑后 1/1=$luckyScore 必须低于 95/100=$provenScore", luckyScore < provenScore)
    }

    @Test
    fun `首选明显领先时收缩候选数量 分数胶着时放宽`() {
        val strong = tool(
            "srv.strong_fetch",
            stat = TaskClassStat(taskClass, successRate = 0.95, resultQuality = 0.9, sampleCount = 100),
            reliability = ReliabilityStat(invocationCount = 100, failureCount = 0, evidence = EvidenceState.MEASURED),
        )
        val unknowns = (1..4).map { tool("srv.weak${it}_fetch") }

        val clearWinner = ToolRoutingEngine(listOf(strong) + unknowns).route(spec())
        assertEquals(3, clearWinner.candidates.size)

        val allEqual = (1..5).map { tool("srv.same${it}_fetch") }
        val tied = ToolRoutingEngine(allEqual).route(spec())
        assertEquals(5, tied.candidates.size)
    }

    @Test
    fun `无实测数据的候选必须显式标注未知而不是编造成功率`() {
        val engine = ToolRoutingEngine(listOf(tool("srv.bare_fetch")))

        val candidate = engine.route(spec()).candidates.single()

        assertEquals(EvidenceState.UNKNOWN, candidate.breakdown.empiricalSuccess.evidence)
        assertEquals(ConfidenceLevel.UNKNOWN, candidate.breakdown.confidence)
        assertTrue(
            candidate.rationale.limitations.any { it.contains("暂无实测样本") },
        )
        assertTrue(
            candidate.rationale.evidenceRefs.any { it.startsWith("evidence:missing:") },
        )
        assertFalse(
            "没有实测数据时不得声称成功率",
            candidate.rationale.advantages.any { it.contains("成功率") },
        )
    }

    @Test
    fun `只读任务排除会改变状态的工具`() {
        val engine = ToolRoutingEngine(
            listOf(
                tool("srv.read_fetch"),
                tool("srv.write_fetch", operations = setOf("read", "write"), readOnly = false),
            ),
        )

        val decision = engine.route(spec(readOnlyOnly = true))

        assertTrue(decision.exclusions.any {
            it.toolId == "srv.write_fetch" && it.reason == ExclusionReason.READ_ONLY_VIOLATION
        })
        assertTrue(decision.candidates.none { it.toolId == "srv.write_fetch" })
    }

    @Test
    fun `任务内失败反馈会降低排名`() {
        val a = tool("srv.a_fetch")
        val b = tool("srv.b_fetch")
        val state = TaskRoutingState(
            feedback = mapOf("srv.a_fetch" to ToolTaskFeedback(failureCount = 2)),
        )

        val decision = ToolRoutingEngine(listOf(a, b)).route(spec(), state)

        assertEquals("srv.b_fetch", decision.candidates.first().toolId)
    }

    @Test
    fun `任务内被证实不支持的工具有效排除`() {
        val a = tool("srv.a_fetch")
        val b = tool("srv.b_fetch")
        val state = TaskRoutingState(
            feedback = mapOf("srv.a_fetch" to ToolTaskFeedback(unsupported = true)),
        )

        val decision = ToolRoutingEngine(listOf(a, b)).route(spec(), state)

        assertTrue(decision.exclusions.any {
            it.toolId == "srv.a_fetch" && it.reason == ExclusionReason.BLACKLISTED_IN_TASK
        })
    }

    @Test
    fun `路由日志包含分数拆解与排除原因`() {
        val engine = ToolRoutingEngine(
            listOf(
                tool("srv.fetch_ok"),
                tool("srv.fetch_disabled", availability = AvailabilityState.DISABLED),
            ),
        )

        val json = RoutingLog.toJson(engine.route(spec()))
        val text = json.toString()

        assertTrue(text.contains("task_fit"))
        assertTrue(text.contains("empirical_evidence"))
        assertTrue(text.contains("DISABLED"))
        assertTrue(RoutingLog.render(engine.route(spec())).contains("候选 1"))
    }
}
