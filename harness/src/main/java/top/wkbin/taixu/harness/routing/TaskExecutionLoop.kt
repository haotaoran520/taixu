package top.wkbin.taixu.harness.routing

/** 闭环状态：每一种终止原因都要能被上层区分处理，而不是笼统的"失败"。 */
internal enum class LoopStatus {
    /** 执行成功且通过验收。 */
    SUCCEEDED,

    /** 需要人工确认（循环、连续无进展、预算耗尽）。 */
    STOPPED_NEEDS_USER,

    /** 写操作副作用未知：必须先查状态，再由上层决定是否继续。 */
    NEEDS_STATE_CHECK,

    /** 现有工具不足以完成任务：需要补充工具或放宽要求。 */
    NEEDS_MORE_TOOLS,

    ABORTED,
}

internal data class LoopStep(
    val iteration: Int,
    val toolId: String,
    val failureType: FailureType?,
    val action: RerouteAction,
    val reason: String,
)

internal data class LoopOutcome(
    val status: LoopStatus,
    val iterations: Int,
    val usedToolIds: List<String>,
    val finalText: String,
    val verification: VerificationOutcome?,
    val steps: List<LoopStep>,
    val missingCapabilities: List<String> = emptyList(),
) {
    fun renderTrace(): String = buildString {
        appendLine("闭环结果：$status（迭代 $iterations 次，调用 ${usedToolIds.size} 次）")
        steps.forEach { step ->
            appendLine("  第${step.iteration}步 ${step.toolId} → ${step.action}：${step.reason}")
        }
        if (missingCapabilities.isNotEmpty()) {
            appendLine("缺口：${missingCapabilities.joinToString("；")}")
        }
    }
}

/** 执行器抽象：真实环境接 MCP，测试里注入假实现。 */
internal fun interface ToolExecutor {
    suspend fun execute(tool: ToolDescriptor): ToolExecutionResult
}

internal data class LoopConfig(
    val maxIterations: Int = 4,
    val acceptance: SubtaskAcceptance = SubtaskAcceptance(
        listOf(VerificationCheck(VerificationKind.NON_EMPTY, description = "返回非空")),
    ),
    val reroute: RerouteConfig = RerouteConfig(),
)

/**
 * 任务执行闭环（阶段三收尾）：路由 → 执行 → 验收 → 分类 → 重路由。
 *
 * 设计取舍：
 * - 只负责"下一步该做什么"，不直接调用任何平台 API，因此可完整单测；
 * - 已经因切换而被放弃的工具进入排除集，保证每轮都向"没试过的工具"推进，不会原地打转；
 * - 只要出现验收失败就记录到任务内反馈，让下一轮路由自然避开它。
 */
internal class TaskExecutionLoop(
    private val engine: ToolRoutingEngine,
    private val config: LoopConfig = LoopConfig(),
) {

    suspend fun run(
        spec: SubTaskSpec,
        executor: ToolExecutor,
    ): LoopOutcome {
        val steps = ArrayList<LoopStep>()
        val used = ArrayList<String>()
        val switchedAway = LinkedHashSet<String>()

        var routingState = TaskRoutingState()
        var attemptState = TaskAttemptState.EMPTY
        var decision = engine.route(spec, routingState)
        if (!decision.hasCandidate) {
            return LoopOutcome(
                status = LoopStatus.NEEDS_MORE_TOOLS,
                iterations = 0,
                usedToolIds = emptyList(),
                finalText = RoutingLog.render(decision),
                verification = null,
                steps = emptyList(),
                missingCapabilities = decision.missingCapabilities,
            )
        }

        var pending: ToolDescriptor? = null
        var pendingFingerprint = ""
        var iteration = 0

        while (iteration < config.maxIterations) {
            iteration++
            val candidate = pending
                ?.let { p -> decision.candidates.firstOrNull { it.toolId == p.toolId } }
                ?: decision.candidates.firstOrNull { it.toolId !in switchedAway }
                ?: return LoopOutcome(
                    LoopStatus.NEEDS_MORE_TOOLS, iteration, used,
                    "候选工具均已尝试且不能满足验收条件",
                    null, steps, decision.missingCapabilities,
                )

            val tool = candidate.tool
            val fingerprint = pendingFingerprint.ifBlank { fingerprintOf(spec, tool) }
            used += tool.toolId
            pending = null
            pendingFingerprint = ""

            val result = executor.execute(tool)
            val verification = result.output?.let { ResultVerifier.verify(it, config.acceptance) }
            val failure = FailureClassifier.classify(
                result = result,
                verification = verification,
                writeOperation = !tool.capabilities.readOnly,
            )

            if (failure == null) {
                steps += LoopStep(iteration, tool.toolId, null, RerouteAction.RETRY_SAME_TOOL, "验收通过")
                return LoopOutcome(
                    LoopStatus.SUCCEEDED, iteration, used, result.renderedText, verification, steps,
                )
            }

            val plan = ReroutePlanner.plan(failure, tool.toolId, fingerprint, attemptState, config.reroute)
            attemptState = plan.state
            steps += LoopStep(iteration, tool.toolId, failure.type, plan.action, plan.reason)

            when (plan.action) {
                RerouteAction.RETRY_SAME_TOOL, RerouteAction.RETRY_WITH_FIXED_ARGS -> {
                    pending = tool
                    // 参数修正后视为不同的调用组合，否则会被判成"同参重复"直接停下。
                    pendingFingerprint = if (plan.action == RerouteAction.RETRY_WITH_FIXED_ARGS) {
                        "$fingerprint#fixed"
                    } else {
                        fingerprint
                    }
                }

                RerouteAction.SWITCH_CANDIDATE -> {
                    switchedAway += tool.toolId
                    routingState = routingState.withFeedback(
                        tool.toolId,
                        feedbackFor(failure),
                    )
                    decision = engine.route(spec, routingState)
                    if (!decision.hasCandidate) {
                        return LoopOutcome(
                            LoopStatus.NEEDS_MORE_TOOLS, iteration, used,
                            RoutingLog.render(decision), verification, steps.forTrace(iteration),
                            decision.missingCapabilities,
                        )
                    }
                }

                RerouteAction.SEARCH_MORE -> return LoopOutcome(
                    LoopStatus.NEEDS_MORE_TOOLS, iteration, used, plan.reason, verification, steps,
                )

                RerouteAction.VERIFY_STATE_THEN_DECIDE -> return LoopOutcome(
                    LoopStatus.NEEDS_STATE_CHECK, iteration, used, plan.reason, verification, steps,
                )

                RerouteAction.STOP_AND_ASK_USER -> return LoopOutcome(
                    LoopStatus.STOPPED_NEEDS_USER, iteration, used, plan.reason, verification, steps,
                )

                RerouteAction.ABORT_WITH_REASON -> return LoopOutcome(
                    LoopStatus.ABORTED, iteration, used, plan.reason, verification, steps,
                )
            }
        }

        return LoopOutcome(
            LoopStatus.STOPPED_NEEDS_USER, iteration, used,
            "达到最大迭代次数 ${config.maxIterations}，停止以免空转", null, steps,
        )
    }

    /** 失败如何计入任务内反馈：临时故障不计入能力评价。 */
    private fun feedbackFor(failure: ClassifiedFailure): ToolTaskFeedback = when (failure.type) {
        FailureType.TRANSIENT_INFRASTRUCTURE -> ToolTaskFeedback(lastFailureType = failure.type)
        FailureType.CAPABILITY_MISMATCH -> ToolTaskFeedback(
            failureCount = 1, unsupported = true, lastFailureType = failure.type,
        )
        FailureType.OUTPUT_QUALITY -> ToolTaskFeedback(
            qualityFailureCount = 1, lastFailureType = failure.type,
        )
        else -> ToolTaskFeedback(failureCount = 1, lastFailureType = failure.type)
    }

    private fun fingerprintOf(spec: SubTaskSpec, tool: ToolDescriptor): String =
        "${spec.id}|${tool.toolId}|${spec.goal.length}"

    private fun List<LoopStep>.forTrace(iteration: Int): List<LoopStep> = this

    private fun TaskRoutingState.withFeedback(toolId: String, feedback: ToolTaskFeedback): TaskRoutingState {
        val existing = this.feedback[toolId] ?: ToolTaskFeedback()
        val merged = ToolTaskFeedback(
            successCount = existing.successCount + feedback.successCount,
            failureCount = existing.failureCount + feedback.failureCount,
            qualityFailureCount = existing.qualityFailureCount + feedback.qualityFailureCount,
            unsupported = existing.unsupported || feedback.unsupported,
            lastFailureType = feedback.lastFailureType ?: existing.lastFailureType,
        )
        return copy(feedback = this.feedback + (toolId to merged))
    }
}
