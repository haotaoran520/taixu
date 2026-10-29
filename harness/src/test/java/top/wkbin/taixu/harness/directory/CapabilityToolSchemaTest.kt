package top.wkbin.taixu.harness.directory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.harness.validation.ToolSchemaValidator

/**
 * 守护"代码认参数、schema 不认"这类契约漂移。
 *
 * 真实事故：`auto_route`/`goal`/`expect_contains` 在实现里已被读取，
 * 但 schema 未声明，导致调用在进入闭环之前就被严格校验拒绝。
 */
class CapabilityToolSchemaTest {

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `route 的全部新参数都通过 schema 校验`() {
        val problems = ToolSchemaValidator.validate(
            CapabilityToolSchema.parameters,
            args(
                """{"action":"route","goal":"读取网页正文并提取标题","task_class":"page",
                   "operations":"read,search","domains":"web","input_types":"url",
                   "output_types":"text","read_only":true,"fresh":false,
                   "format":"injection","variant":"D_RANKING_WITH_RATIONALE","subtask_id":"s1"}""",
            ),
        )

        assertTrue("不应出现校验问题：$problems", problems.isEmpty())
    }

    @Test
    fun `call 的闭环参数与普通调用都通过校验`() {
        val withLoop = ToolSchemaValidator.validate(
            CapabilityToolSchema.parameters,
            args(
                """{"action":"call","server":"s","tool":"t","arguments":{"url":"https://a"},
                   "auto_route":true,"goal":"读正文","expect_contains":"Example Domain","subtask_id":"s2"}""",
            ),
        )
        val plain = ToolSchemaValidator.validate(
            CapabilityToolSchema.parameters,
            args("""{"action":"call","server":"s","tool":"t","arguments":{}}"""),
        )

        assertTrue("闭环参数不应被拒：$withLoop", withLoop.isEmpty())
        assertTrue("普通调用不应被拒：$plain", plain.isEmpty())
    }

    @Test
    fun `未声明的参数仍然会被拒绝`() {
        val problems = ToolSchemaValidator.validate(
            CapabilityToolSchema.parameters,
            args("""{"action":"call","server":"s","tool":"t","bogus_param":1}"""),
        )

        assertTrue("未知参数必须继续被拒绝，否则校验形同虚设", problems.isNotEmpty())
    }
}
