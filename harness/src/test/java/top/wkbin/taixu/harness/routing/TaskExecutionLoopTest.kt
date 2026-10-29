package top.wkbin.taixu.harness.routing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段三收尾：执行闭环。验证"验收→分类→重路由"真的改变了执行行为。 */
class TaskExecutionLoopTest {

    private fun tool(id: String, readOnly: Boolean = true, family: String = "web.read") = ToolDescriptor(
        toolId = id,
        serverId = id.substringBefore('.'),
        name = id.substringAfter('.'),
        description = "read page content",
        familyId = family,
        capabilities = ToolCapabilities(
            domains = setOf("web"),
            operations = if (readOnly) setOf("read") else setOf("write"),
            readOnly = readOnly,
        ),
    )

    private fun spec() = SubTaskSpec(
        id = "loop", goal = "read page content", taskClass = "page",
        requiredOperations = setOf("read"), requiredDomains = setOf("web"),
    )

    private fun engine(vararg tools: ToolDescriptor) = ToolRoutingEngine(tools.toList())

    @Test
    fun `首次执行通过验收即成功`() = runBlocking {
        var calls = 0
        val loop = TaskExecutionLoop(engine(tool("srv.a")))

        val outcome = loop.run(spec()) {
            calls++
            ToolExecutionResult(output = ToolOutput(text = "页面正文"), renderedText = "页面正文")
        }

        assertEquals(LoopStatus.SUCCEEDED, outcome.status)
        assertEquals(1, calls)
        assertEquals("页面正文", outcome.finalText)
    }

    @Test
    fun `临时故障有限重试后成功 不切换工具`() = runBlocking {
        var calls = 0
        val loop = TaskExecutionLoop(engine(tool("srv.a"), tool("srv.b")))

        val outcome = loop.run(spec()) {
            calls++
            if (calls == 1) {
                ToolExecutionResult(error = ErrorKind.TIMEOUT, errorMessage = "timeout")
            } else {
                ToolExecutionResult(output = ToolOutput(text = "重试后的正文"), renderedText = "重试后的正文")
            }
        }

        assertEquals(LoopStatus.SUCCEEDED, outcome.status)
        assertEquals(2, calls)
        assertTrue("应重试同一工具", outcome.usedToolIds.all { it == "srv.a" })
    }

    @Test
    fun `输出为空即质量不合格 自动切换到替代候选`() = runBlocking {
        val executed = ArrayList<String>()
        val loop = TaskExecutionLoop(engine(tool("srv.a"), tool("srv.b")))

        val outcome = loop.run(spec()) { descriptor ->
            executed += descriptor.toolId
            if (descriptor.toolId == "srv.a") {
                ToolExecutionResult(output = ToolOutput(text = ""), renderedText = "")
            } else {
                ToolExecutionResult(output = ToolOutput(text = "替代工具返回的正文"), renderedText = "替代工具返回的正文")
            }
        }

        assertEquals(LoopStatus.SUCCEEDED, outcome.status)
        assertEquals(listOf("srv.a", "srv.b"), executed)
        assertEquals(FailureType.OUTPUT_QUALITY, outcome.steps.first().failureType)
        assertEquals(RerouteAction.SWITCH_CANDIDATE, outcome.steps.first().action)
    }

    @Test
    fun `写操作超时只执行一次 停下来要求先查状态`() = runBlocking {
        var calls = 0
        val loop = TaskExecutionLoop(engine(tool("srv.write", readOnly = false, family = "web.write")))

        val outcome = loop.run(
            SubTaskSpec(
                id = "loop", goal = "write record", taskClass = "page",
                requiredOperations = setOf("write"), requiredDomains = setOf("web"),
            ),
        ) {
            calls++
            ToolExecutionResult(error = ErrorKind.TIMEOUT, errorMessage = "timeout")
        }

        assertEquals(LoopStatus.NEEDS_STATE_CHECK, outcome.status)
        assertEquals("写操作不得被重放", 1, calls)
        assertTrue(outcome.finalText.contains("禁止盲目重放") || outcome.steps.any { it.action == RerouteAction.VERIFY_STATE_THEN_DECIDE })
    }

    @Test
    fun `所有候选都不合格时报告需要更多工具`() = runBlocking {
        val loop = TaskExecutionLoop(engine(tool("srv.a"), tool("srv.b")))

        val outcome = loop.run(spec()) {
            ToolExecutionResult(output = ToolOutput(text = ""), renderedText = "")
        }

        assertEquals(LoopStatus.NEEDS_MORE_TOOLS, outcome.status)
        assertTrue(outcome.usedToolIds.size <= 2)
    }

    @Test
    fun `没有候选时直接报告缺口且不执行任何工具`() = runBlocking {
        var calls = 0
        val loop = TaskExecutionLoop(engine(tool("srv.a", readOnly = false, family = "web.write")))

        val outcome = loop.run(spec()) {
            calls++
            ToolExecutionResult(output = ToolOutput(text = "x"), renderedText = "x")
        }

        assertEquals(LoopStatus.NEEDS_MORE_TOOLS, outcome.status)
        assertEquals(0, calls)
        assertTrue(outcome.missingCapabilities.isNotEmpty())
    }

    @Test
    fun `达到最大迭代次数时停止而不是无限重试`() = runBlocking {
        var calls = 0
        val loop = TaskExecutionLoop(
            engine(tool("srv.a"), tool("srv.b"), tool("srv.c")),
            LoopConfig(maxIterations = 1),
        )

        val outcome = loop.run(spec()) {
            calls++
            ToolExecutionResult(output = ToolOutput(text = ""), renderedText = "")
        }

        assertEquals(LoopStatus.STOPPED_NEEDS_USER, outcome.status)
        assertEquals(1, calls)
        assertTrue(outcome.finalText.contains("最大迭代次数"))
    }
}
