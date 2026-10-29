package top.wkbin.taixu.harness.routing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阶段三验收：调用成功不等于任务完成；失败必须被正确归类。 */
class TaskVerificationTest {

    private val acceptance = SubtaskAcceptance(
        listOf(
            VerificationCheck(VerificationKind.NON_EMPTY, description = "正文非空"),
            VerificationCheck(VerificationKind.MIN_LENGTH, params = listOf("20"), description = "正文长度"),
            VerificationCheck(VerificationKind.HAS_SOURCE, description = "来源可核验"),
        ),
    )

    @Test
    fun `完整输出满足验收条件`() {
        val output = ToolOutput(
            text = "这是一个足够长的页面正文内容，用于验证最小长度检查。",
            sourceUrl = "https://example.com/a",
        )

        val outcome = ResultVerifier.verify(output, acceptance)

        assertTrue(outcome.failures.toString(), outcome.passed)
        assertTrue(outcome.observations.any { it.contains("来源") })
    }

    @Test
    fun `空返回与截断都不算完成`() {
        val empty = ResultVerifier.verify(ToolOutput(text = ""), acceptance)
        val truncated = ResultVerifier.verify(
            ToolOutput(text = "足够长的正文内容用于通过长度检查xxxxxxxxxx", truncated = true),
            acceptance,
        )

        assertFalse(empty.passed)
        assertTrue(empty.failures.any { it.contains("为空") })
        assertFalse(truncated.passed)
        assertTrue(truncated.failures.any { it.contains("截断") })
    }

    @Test
    fun `缺少可核验来源时判定不合格`() {
        val outcome = ResultVerifier.verify(
            ToolOutput(text = "这是足够长的正文内容，但没有给出任何来源链接信息。"),
            acceptance,
        )

        assertFalse(outcome.passed)
        assertTrue(outcome.failures.any { it.contains("来源") })
    }

    @Test
    fun `写入任务必须确认副作用`() {
        val writeAcceptance = SubtaskAcceptance(
            listOf(VerificationCheck(VerificationKind.SIDE_EFFECT_CONFIRMED, description = "写入生效")),
        )

        val unconfirmed = ResultVerifier.verify(ToolOutput(text = "OK", sideEffectConfirmed = null), writeAcceptance)
        val confirmed = ResultVerifier.verify(ToolOutput(text = "OK", sideEffectConfirmed = true), writeAcceptance)

        assertFalse(unconfirmed.passed)
        assertTrue(confirmed.passed)
    }

    @Test
    fun `内容不合格归为质量失败 仅覆盖不足归为任务未完成`() {
        // 内容层面：文本太短且无来源 → OUTPUT_QUALITY
        val shortOutput = ToolOutput(text = "篇幅不足")
        val qualityFailure = FailureClassifier.classify(
            ToolExecutionResult(output = shortOutput),
            ResultVerifier.verify(shortOutput, acceptance),
            writeOperation = false,
        )

        // 覆盖层面：内容完整、有来源，但未覆盖用户要求的对象 → TASK_INCOMPLETE
        val coverageAcceptance = SubtaskAcceptance(
            listOf(
                VerificationCheck(VerificationKind.NON_EMPTY, description = "正文非空"),
                VerificationCheck(VerificationKind.HAS_SOURCE, description = "来源可核验"),
                VerificationCheck(
                    VerificationKind.CONTAINS_ALL,
                    params = listOf("价格"),
                    description = "覆盖价格信息",
                    aspect = VerificationAspect.COVERAGE,
                ),
            ),
        )
        val richOutput = ToolOutput(
            text = "这是一段足够长的页面正文内容，包含标题与正文描述。",
            sourceUrl = "https://example.com/page",
        )
        val incomplete = FailureClassifier.classify(
            ToolExecutionResult(output = richOutput),
            ResultVerifier.verify(richOutput, coverageAcceptance),
            writeOperation = false,
        )

        assertNotNull(qualityFailure)
        assertTrue("短文本应判为输出质量不合格", qualityFailure!!.type == FailureType.OUTPUT_QUALITY)
        assertNotNull(incomplete)
        assertTrue("内容合格但覆盖不足应判为任务未完成", incomplete!!.type == FailureType.TASK_INCOMPLETE)
    }

    @Test
    fun `成功且验收通过时没有失败结论`() {
        val output = ToolOutput(text = "这是足够长的正文内容，并且附带来源链接。", sourceUrl = "https://e.com")

        val failure = FailureClassifier.classify(
            ToolExecutionResult(output = output),
            ResultVerifier.verify(output, acceptance),
            writeOperation = false,
        )

        assertNull(failure)
    }

    @Test
    fun `临时故障与能力缺陷必须分开`() {
        val transient = FailureClassifier.classify(
            ToolExecutionResult(error = ErrorKind.TIMEOUT), null, writeOperation = false,
        )
        val capability = FailureClassifier.classify(
            ToolExecutionResult(error = ErrorKind.UNSUPPORTED), null, writeOperation = false,
        )

        assertTrue(transient!!.type == FailureType.TRANSIENT_INFRASTRUCTURE)
        assertTrue(transient.retryable)
        assertTrue(capability!!.type == FailureType.CAPABILITY_MISMATCH)
        assertFalse(capability.retryable)
    }

    @Test
    fun `写操作超时归为副作用不确定且不可直接重试`() {
        val failure = FailureClassifier.classify(
            ToolExecutionResult(error = ErrorKind.TIMEOUT), null, writeOperation = true,
        )

        assertTrue(failure!!.type == FailureType.UNKNOWN_SIDE_EFFECT)
        assertTrue(failure.sideEffectUncertain)
        assertFalse(failure.retryable)
    }
}
