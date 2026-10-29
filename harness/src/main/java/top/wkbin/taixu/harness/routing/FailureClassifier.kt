package top.wkbin.taixu.harness.routing

/** 执行层的可观测错误类型：只描述现象，不预设归因。 */
internal enum class ErrorKind {
    TIMEOUT,
    CONNECTION_RESET,
    UNAVAILABLE,
    INVALID_ARGUMENTS,
    SCHEMA_VIOLATION,
    PERMISSION_DENIED,
    UNSUPPORTED,
    UNKNOWN,
}

/** 一次执行的可观测结果。 */
internal data class ToolExecutionResult(
    val output: ToolOutput? = null,
    val error: ErrorKind? = null,
    val errorMessage: String = "",
    /** 原始渲染文本：闭环成功后要原样回传给调用方。 */
    val renderedText: String = "",
)

/** 分类结论：类型 + 是否可重试 + 副作用是否不确定。 */
internal data class ClassifiedFailure(
    val type: FailureType,
    val reason: String,
    val retryable: Boolean,
    val sideEffectUncertain: Boolean = false,
)

/**
 * 失败分类器（方案 7.2）。
 *
 * 核心目的：**把"临时网络问题"和"能力不足"分开**。
 * 前者应有限重试，后者应立刻降权换人——混淆二者会让系统要么浪费重试，
 * 要么把可用工具误判为不可用。
 */
internal object FailureClassifier {

    fun classify(
        result: ToolExecutionResult,
        verification: VerificationOutcome?,
        writeOperation: Boolean,
    ): ClassifiedFailure? {
        result.error?.let { error ->
            return when (error) {
                // 写操作超时/连接中断：无法确定副作用是否已发生 → 绝不能盲目重放。
                ErrorKind.TIMEOUT, ErrorKind.CONNECTION_RESET -> if (writeOperation) {
                    ClassifiedFailure(
                        type = FailureType.UNKNOWN_SIDE_EFFECT,
                        reason = "写操作在 ${error.name} 下结果未知，需先验证副作用",
                        retryable = false,
                        sideEffectUncertain = true,
                    )
                } else {
                    ClassifiedFailure(
                        type = FailureType.TRANSIENT_INFRASTRUCTURE,
                        reason = "临时连接故障（${error.name}）",
                        retryable = true,
                    )
                }
                ErrorKind.UNAVAILABLE -> ClassifiedFailure(
                    FailureType.TRANSIENT_INFRASTRUCTURE, "服务暂时不可达", retryable = true,
                )
                ErrorKind.INVALID_ARGUMENTS, ErrorKind.SCHEMA_VIOLATION -> ClassifiedFailure(
                    FailureType.INVALID_ARGUMENTS, "参数或 Schema 不合法（${error.name}）", retryable = true,
                )
                ErrorKind.PERMISSION_DENIED, ErrorKind.UNSUPPORTED -> ClassifiedFailure(
                    FailureType.CAPABILITY_MISMATCH, "当前环境不支持该能力（${error.name}）", retryable = false,
                )
                ErrorKind.UNKNOWN -> if (writeOperation) {
                    ClassifiedFailure(
                        FailureType.UNKNOWN_SIDE_EFFECT,
                        "写操作返回未知错误，副作用未确认，需先验证状态",
                        retryable = false,
                        sideEffectUncertain = true,
                    )
                } else {
                    ClassifiedFailure(
                        FailureType.TRANSIENT_INFRASTRUCTURE, "未知错误，按临时故障处理一次", retryable = true,
                    )
                }
            }
        }

        // 调用成功但验收失败：区分"输出本身不合格"与"输出合格但任务没完成"。
        if (verification != null && !verification.passed) {
            // 内容层面失败（空/截断/缺来源/格式错）→ 输出质量；仅覆盖层面失败 → 任务未完成。
            val qualityIssue = if (verification.failedAspects.isNotEmpty()) {
                VerificationAspect.CONTENT in verification.failedAspects
            } else {
                result.output?.let { (it.text.isBlank() && it.fields.isEmpty()) || it.truncated } ?: true
            }
            return if (qualityIssue) {
                ClassifiedFailure(
                    FailureType.OUTPUT_QUALITY,
                    "调用成功但输出不合格：${verification.failures.joinToString("；")}",
                    retryable = false,
                )
            } else {
                ClassifiedFailure(
                    FailureType.TASK_INCOMPLETE,
                    "调用成功但未满足验收条件：${verification.failures.joinToString("；")}",
                    retryable = false,
                )
            }
        }
        return null
    }
}
