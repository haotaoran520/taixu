package top.wkbin.taixu.harness.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段三三条验收重点：
 * 1. 首选明显跑偏时能切换；
 * 2. 临时故障不被当成能力缺陷；
 * 3. 重复副作用风险下不盲目重放。
 */
class ReroutePlannerTest {

    private fun failure(type: FailureType, retryable: Boolean = false) =
        ClassifiedFailure(type = type, reason = "test", retryable = retryable)

    @Test
    fun `首选结果不合格时切换到替代候选`() {
        val plan = ReroutePlanner.plan(
            failure(FailureType.OUTPUT_QUALITY),
            toolId = "srv.bad_fetch",
            argsFingerprint = "url=a",
            state = TaskAttemptState.EMPTY,
        )

        assertEquals(RerouteAction.SWITCH_CANDIDATE, plan.action)
        assertEquals(listOf("srv.bad_fetch"), plan.downweight)
        assertTrue(plan.markUnsupported.isEmpty())
    }

    @Test
    fun `调用成功但任务未完成时重新检索而不是死磕同一个工具`() {
        val plan = ReroutePlanner.plan(
            failure(FailureType.TASK_INCOMPLETE),
            toolId = "srv.a",
            argsFingerprint = "q=1",
            state = TaskAttemptState.EMPTY,
        )

        assertEquals(RerouteAction.SEARCH_MORE, plan.action)
    }

    @Test
    fun `临时故障有限重试且不标记为能力缺陷`() {
        val first = ReroutePlanner.plan(
            failure(FailureType.TRANSIENT_INFRASTRUCTURE, retryable = true),
            "srv.a", "url=x", TaskAttemptState.EMPTY,
        )
        assertEquals(RerouteAction.RETRY_SAME_TOOL, first.action)
        assertEquals("srv.a", first.retryToolId)
        assertTrue("临时故障不得进入 unsupported", first.markUnsupported.isEmpty())

        // 达到重试上限后换人，但仍不写成能力缺陷
        val exhausted = ReroutePlanner.plan(
            failure(FailureType.TRANSIENT_INFRASTRUCTURE, retryable = true),
            "srv.a", "url=x",
            TaskAttemptState(transientRetries = mapOf("srv.a" to 2)),
        )
        assertEquals(RerouteAction.SWITCH_CANDIDATE, exhausted.action)
        assertTrue(exhausted.markUnsupported.isEmpty())
        assertTrue(exhausted.reason.contains("不作为能力缺陷"))
    }

    @Test
    fun `因果真实的同参重复调用会被识别为循环`() {
        val state = TaskAttemptState(
            attempts = listOf(AttemptRecord("srv.a", "url=x", FailureType.OUTPUT_QUALITY, false)),
        )

        val plan = ReroutePlanner.plan(
            failure(FailureType.OUTPUT_QUALITY), "srv.a", "url=x", state,
        )

        assertEquals(RerouteAction.STOP_AND_ASK_USER, plan.action)
        assertTrue(plan.reason.contains("相同参数"))
    }

    @Test
    fun `写操作副作用未知时先查状态而不是重放`() {
        val plan = ReroutePlanner.plan(
            failure(FailureType.UNKNOWN_SIDE_EFFECT),
            toolId = "srv.write_file",
            argsFingerprint = "path=/a",
            state = TaskAttemptState.EMPTY,
        )

        assertEquals(RerouteAction.VERIFY_STATE_THEN_DECIDE, plan.action)
        assertTrue(plan.reason.contains("禁止盲目重放"))
        assertEquals("srv.write_file", plan.retryToolId)
    }

    @Test
    fun `参数错误只允许按 Schema 修正一次`() {
        val first = ReroutePlanner.plan(
            failure(FailureType.INVALID_ARGUMENTS, retryable = true), "srv.a", "args1", TaskAttemptState.EMPTY,
        )
        assertEquals(RerouteAction.RETRY_WITH_FIXED_ARGS, first.action)

        val second = ReroutePlanner.plan(
            failure(FailureType.INVALID_ARGUMENTS, retryable = true), "srv.a", "args2",
            TaskAttemptState(schemaFixes = mapOf("srv.a" to 1)),
        )
        assertEquals(RerouteAction.SWITCH_CANDIDATE, second.action)
    }

    @Test
    fun `连续无进展会收敛到人工介入`() {
        val plan = ReroutePlanner.plan(
            failure(FailureType.OUTPUT_QUALITY),
            "srv.b", "url=b",
            TaskAttemptState(noProgressStreak = 2),
        )

        assertEquals(RerouteAction.STOP_AND_ASK_USER, plan.action)
        assertTrue(plan.reason.contains("无实质进展"))
    }

    @Test
    fun `调用总预算耗尽时终止`() {
        val many = (1..13).map { AttemptRecord("srv.t$it", "args$it", FailureType.OUTPUT_QUALITY, false) }

        val plan = ReroutePlanner.plan(
            failure(FailureType.OUTPUT_QUALITY), "srv.new", "args-new",
            TaskAttemptState(attempts = many),
        )

        assertEquals(RerouteAction.ABORT_WITH_REASON, plan.action)
        assertTrue(plan.reason.contains("上限"))
    }
}
