package top.wkbin.taixu.harness.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段二验收 1：理由只能引用可追溯事实；注入结构符合方案 6.4。 */
class RoutingInjectionTest {

    private val tc = "static_page_extraction"

    private fun tool(
        id: String,
        stat: TaskClassStat? = null,
        schema: String = """{"url":"string"}""",
        differentiators: Map<String, String> = mapOf("input" to "url"),
        family: String = "web.read",
    ) = ToolDescriptor(
        toolId = id,
        serverId = id.substringBefore('.'),
        name = id.substringAfter('.'),
        description = "read page content",
        familyId = family,
        capabilities = ToolCapabilities(domains = setOf("web"), operations = setOf("read")),
        quality = ToolQualityProfile(taskClasses = stat?.let { mapOf(tc to it) } ?: emptyMap()),
        familyDifferentiators = differentiators,
        inputSchemaSummary = schema,
    )

    private fun spec() = SubTaskSpec(
        id = "subtask_02",
        goal = "read page content",
        taskClass = tc,
        requiredOperations = setOf("read"),
        requiredDomains = setOf("web"),
        acceptanceCriteria = listOf("必须读取指定 URL 的页面正文"),
    )

    private fun decision() = ToolRoutingEngine(
        listOf(
            tool(
                "srv.fetch_page",
                TaskClassStat(tc, 0.94, 0.9, 83, "2026-10-20"),
                differentiators = mapOf("input" to "url", "rendering" to "static_only"),
            ),
            tool("srv.browser_page", differentiators = mapOf("input" to "url", "rendering" to "javascript")),
        ),
    ).route(spec())

    @Test
    fun `注入 JSON 符合 6_4 结构并携带输入 Schema`() {
        val json = RoutingInjection.build(decision(), InjectionVariant.D_RANKING_WITH_RATIONALE).toString()

        assertTrue(json.contains("\"subtask_id\":\"subtask_02\""))
        assertTrue(json.contains("\"selection_mode\":\"model_select_from_candidates\""))
        assertTrue(json.contains("\"acceptance_criteria\""))
        assertTrue(json.contains("\"rank\":1"))
        assertTrue(json.contains("\"utility_score\""))
        assertTrue(json.contains("\"confidence\""))
        assertTrue(json.contains("\"advantages\""))
        assertTrue(json.contains("\"limitations\""))
        assertTrue(json.contains("\"comparison\""))
        assertTrue(json.contains("\"evidence_refs\""))
        assertTrue(json.contains("\"input_schema\""))
    }

    @Test
    fun `C 变体只给排名与分数 不注入理由`() {
        val json = RoutingInjection.build(decision(), InjectionVariant.C_RANKING).toString()

        assertTrue(json.contains("\"utility_score\""))
        assertFalse(json.contains("\"advantages\""))
        assertFalse(json.contains("\"comparison\""))
        assertFalse(json.contains("\"evidence_refs\""))
    }

    @Test
    fun `B 变体不注入排名与分数`() {
        val json = RoutingInjection.build(decision(), InjectionVariant.B_RETRIEVAL).toString()

        assertFalse(json.contains("\"utility_score\""))
        assertFalse(json.contains("\"rank\""))
        assertTrue(json.contains("\"tool_id\""))
    }

    @Test
    fun `F 变体注入历史统计 无实测时样本量为 0 而不是编造`() {
        val json = RoutingInjection.build(decision(), InjectionVariant.F_WITH_HISTORY).toString()

        assertTrue(json.contains("\"history\""))
        // browser_page 没有实测样本 → history_samples 必须是 0
        assertTrue(json.contains("\"history_samples\":0"))
        assertTrue(json.contains("\"history_samples\":83"))
    }

    @Test
    fun `注入文本明确说明分数只是排序信号`() {
        val text = RoutingInjection.renderText(decision())

        assertTrue(text.contains("不是必须选第一名"))
        assertTrue(text.contains("不得补造性能事实"))
        assertTrue(text.contains("选择集合外工具将被拒绝"))
    }

    @Test
    fun `候选理由里的同族差异引用两侧已声明取值`() {
        val top = decision().candidates.first()
        val comparison = top.rationale.comparison

        assertTrue("比较应包含同族差异：" + comparison, comparison.contains("同族差异") || comparison.contains("覆盖优势"))
    }
}
