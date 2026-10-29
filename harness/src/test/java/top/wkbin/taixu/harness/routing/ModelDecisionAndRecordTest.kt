package top.wkbin.taixu.harness.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段二验收 3：排名和模型选择必须分开记录；越界选择必须拒绝执行。 */
class ModelDecisionAndRecordTest {

    private fun tool(id: String, stat: TaskClassStat? = null) = ToolDescriptor(
        toolId = id,
        serverId = id.substringBefore('.'),
        name = id.substringAfter('.'),
        description = "read page content",
        familyId = "web.read",
        capabilities = ToolCapabilities(domains = setOf("web"), operations = setOf("read")),
        quality = ToolQualityProfile(
            taskClasses = stat?.let { mapOf("page" to it) } ?: emptyMap(),
        ),
    )

    private fun decision() = ToolRoutingEngine(
        listOf(
            tool("srv.a_fetch", TaskClassStat("page", 0.95, 0.9, 50)),
            tool("srv.b_fetch"),
            tool("srv.c_fetch"),
        ),
    ).route(
        SubTaskSpec(
            id = "subtask_03",
            goal = "read page content",
            taskClass = "page",
            requiredOperations = setOf("read"),
            requiredDomains = setOf("web"),
        ),
    )

    @Test
    fun `解析结构化决策`() {
        val json = Json.parseToJsonElement(
            """{"decision":"call_tool","selected_tool_id":"srv.a_fetch","selection_basis":"matches_static_page","confidence":"medium","expected_observation":"页面标题与正文"}""",
        ) as JsonObject

        val parsed = ModelDecisionParser.parse(json)

        assertEquals(DecisionAction.CALL_TOOL, parsed.action)
        assertEquals(listOf("srv.a_fetch"), parsed.selectedToolIds)
        assertEquals("matches_static_page", parsed.selectionBasis)
        assertEquals("medium", parsed.confidence)
    }

    @Test
    fun `选择候选集合外的工具时拒绝执行并要求重新路由`() {
        val d = decision()
        val model = ModelDecision(
            action = DecisionAction.CALL_TOOL,
            selectedToolIds = listOf("srv.not_in_candidates"),
            selectionBasis = "我觉得这个更好",
            confidence = "low",
            expectedObservation = "",
        )

        val validation = ModelDecisionParser.validate(model, d.candidates)

        assertFalse(validation.accepted)
        assertEquals(listOf("srv.not_in_candidates"), validation.outOfCandidateTools)
        assertTrue(validation.message.contains("拒绝执行"))
    }

    @Test
    fun `继续检索或不调用不触发执行拒绝`() {
        val d = decision()
        val searchMore = ModelDecision(DecisionAction.SEARCH_MORE, emptyList(), "候选不足", "low", "")

        val validation = ModelDecisionParser.validate(searchMore, d.candidates)

        assertTrue(validation.accepted)
        assertTrue(validation.message.contains("继续检索"))
    }

    @Test
    fun `排名与模型选择分开记录 且能算出是否跟了第一名`() {
        val d = decision()
        val record = DecisionRecord(
            subtaskId = d.subtask.id,
            rankingToolIds = d.candidates.map { it.toolId },
            utilityByToolId = d.candidates.associate { it.toolId to it.utility },
            modelDecision = ModelDecision(
                DecisionAction.CALL_TOOL, listOf("srv.b_fetch"), "同族更便宜", "medium", "正文",
            ),
            validation = SelectionValidation(true, emptyList(), "ok"),
            outcome = OutcomeAssessment(taskCompleted = true, qualitySatisfied = false, note = "正文不完整"),
        )

        val json = DecisionLog.toJson(record).toString()

        assertTrue(json.contains("\"record_kind\":\"model_selection\""))
        assertTrue(json.contains("\"ranking\""))
        assertTrue(json.contains("\"selection_basis\""))
        assertEquals(2, record.selectedRank)
        assertTrue(record.choseLowRank)
        assertFalse(record.followedTop1)
        assertFalse(record.requiresReroute)
    }

    @Test
    fun `排名日志不含模型选择字段 两份日志互不混写`() {
        val d = decision()
        val rankingJson = RoutingLog.toJson(d).toString()

        assertTrue(rankingJson.contains("\"candidates\""))
        assertFalse("排名日志不应包含模型决策", rankingJson.contains("selection_basis"))
        assertFalse(rankingJson.contains("followed_top1"))
    }

    @Test
    fun `未选择任何工具时名次为空`() {
        val record = DecisionRecord(
            subtaskId = "s",
            rankingToolIds = listOf("a"),
            utilityByToolId = mapOf("a" to 0.5),
            modelDecision = ModelDecision(DecisionAction.NO_CALL, emptyList(), "", "", ""),
            validation = SelectionValidation(true, emptyList(), "ok"),
        )

        assertNull(record.selectedRank)
        assertFalse(record.choseLowRank)
    }
}
