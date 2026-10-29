package top.wkbin.taixu.harness.routing

/** 重路由动作（方案 7.2 推荐动作的执行化表达）。 */
internal enum class RerouteAction {
    /** 临时故障的有限重试：**不**降低该工具的能力评价。 */
    RETRY_SAME_TOOL,

    /** 参数错误：允许按 Schema 修正一次。 */
    RETRY_WITH_FIXED_ARGS,

    /** 能力不匹配或输出不合格：交给同族/互补候选。 */
    SWITCH_CANDIDATE,

    /** 调用成功但任务未完成：诊断缺口并重新检索。 */
    SEARCH_MORE,

    /** 写操作副作用未知：先查状态，再决定是否重试（禁止盲目重放）。 */
    VERIFY_STATE_THEN_DECIDE,

    /** 循环、预算耗尽或需要澄清：停下来问人，而不是继续烧调用。 */
    STOP_AND_ASK_USER,

    /** 明确不可恢复：记录原因终止。 */
    ABORT_WITH_REASON,
}

internal data class AttemptRecord(
    val toolId: String,
    val argsFingerprint: String,
    val failureType: FailureType?,
    val retryable: Boolean,
)

/** 任务内尝试账本：用于检测重复调用与无进展循环（方案 7.4）。 */
internal data class TaskAttemptState(
    val attempts: List<AttemptRecord> = emptyList(),
    val transientRetries: Map<String, Int> = emptyMap(),
    val schemaFixes: Map<String, Int> = emptyMap(),
    val noProgressStreak: Int = 0,
) {
    fun repeatsOf(toolId: String, fingerprint: String): Int =
        attempts.count { it.toolId == toolId && it.argsFingerprint == fingerprint }

    fun transientRetriesOf(toolId: String): Int = transientRetries[toolId] ?: 0

    fun schemaFixesOf(toolId: String): Int = schemaFixes[toolId] ?: 0

    companion object {
        val EMPTY = TaskAttemptState()
    }
}

internal data class RerouteConfig(
    val maxTransientRetries: Int = 2,
    val maxSchemaFixes: Int = 1,
    val maxNoProgress: Int = 3,
    val maxTotalAttempts: Int = 12,
)

internal data class ReroutePlan(
    val action: RerouteAction,
    val reason: String,
    val retryToolId: String? = null,
    /** 应在当前任务内标记为"不支持该场景"的工具。 */
    val markUnsupported: List<String> = emptyList(),
    /** 应在当前任务内降权的工具（输出质量不合格）。 */
    val downweight: List<String> = emptyList(),
    val state: TaskAttemptState,
)

/**
 * 重路由决策器。
 *
 * 三条硬规则：
 * 1. **临时故障不等于能力差**：超时/断连优先有限重试，且不写入 unsupported；
 * 2. **写操作副作用未知不重放**：先查状态，避免重复创建/删除；
 * 3. **检测循环**：同工具同参数的重复调用、连续无进展都会收敛到"停下来问人"。
 */
internal object ReroutePlanner {

    fun plan(
        failure: ClassifiedFailure,
        toolId: String,
        argsFingerprint: String,
        state: TaskAttemptState,
        config: RerouteConfig = RerouteConfig(),
    ): ReroutePlan {
        val recorded = state.copy(
            attempts = state.attempts + AttemptRecord(toolId, argsFingerprint, failure.type, failure.retryable),
        )

        // 1) 总预算
        if (recorded.attempts.size > config.maxTotalAttempts) {
            return ReroutePlan(
                RerouteAction.ABORT_WITH_REASON,
                "工具调用次数已达上限 ${config.maxTotalAttempts}，停止以免空转",
                state = recorded,
            )
        }

        // 2) 同一工具 + 同一组参数重复调用：除非有"重试确实会改变结果"的证据（临时故障），否则视为循环。
        val repeats = state.repeatsOf(toolId, argsFingerprint)
        if (repeats >= 1 && failure.type != FailureType.TRANSIENT_INFRASTRUCTURE) {
            return ReroutePlan(
                RerouteAction.STOP_AND_ASK_USER,
                "检测到 $toolId 用相同参数重复调用 $repeats 次且未推进，需人工确认或改用其他路径",
                state = recorded.copy(noProgressStreak = state.noProgressStreak + 1),
            )
        }

        val plan = when (failure.type) {
            FailureType.UNKNOWN_SIDE_EFFECT -> ReroutePlan(
                RerouteAction.VERIFY_STATE_THEN_DECIDE,
                "写操作副作用未确认，先查询状态再决定是否重试（禁止盲目重放）",
                retryToolId = toolId,
                state = recorded,
            )

            FailureType.TRANSIENT_INFRASTRUCTURE -> {
                val used = state.transientRetriesOf(toolId)
                if (used < config.maxTransientRetries) {
                    ReroutePlan(
                        RerouteAction.RETRY_SAME_TOOL,
                        "临时故障，第 ${used + 1}/${config.maxTransientRetries} 次重试（不降低该工具能力评价）",
                        retryToolId = toolId,
                        state = recorded.copy(
                            transientRetries = recorded.transientRetries + (toolId to used + 1),
                        ),
                    )
                } else {
                    ReroutePlan(
                        RerouteAction.SWITCH_CANDIDATE,
                        "临时故障重试 $used 次仍失败，切换替代工具；不作为能力缺陷记录",
                        state = recorded,
                    )
                }
            }

            FailureType.INVALID_ARGUMENTS -> {
                val used = state.schemaFixesOf(toolId)
                if (used < config.maxSchemaFixes) {
                    ReroutePlan(
                        RerouteAction.RETRY_WITH_FIXED_ARGS,
                        "参数不合法，按 Schema 修正后重试第 ${used + 1} 次",
                        retryToolId = toolId,
                        state = recorded.copy(
                            schemaFixes = recorded.schemaFixes + (toolId to used + 1),
                        ),
                    )
                } else {
                    ReroutePlan(
                        RerouteAction.SWITCH_CANDIDATE,
                        "参数修正已达上限仍失败，改用其他候选或请求澄清",
                        state = recorded,
                    )
                }
            }

            FailureType.CAPABILITY_MISMATCH -> ReroutePlan(
                RerouteAction.SWITCH_CANDIDATE,
                "该工具不具备所需能力，标记本任务不适用并重排同族候选",
                markUnsupported = listOf(toolId),
                state = recorded,
            )

            FailureType.OUTPUT_QUALITY -> ReroutePlan(
                RerouteAction.SWITCH_CANDIDATE,
                "输出质量不合格，降低其在本任务中的优先级并选择能补足缺口的工具",
                downweight = listOf(toolId),
                state = recorded.copy(noProgressStreak = state.noProgressStreak + 1),
            )

            FailureType.TASK_INCOMPLETE -> ReroutePlan(
                RerouteAction.SEARCH_MORE,
                "调用成功但未满足验收条件，诊断缺失能力并重新检索",
                state = recorded.copy(noProgressStreak = state.noProgressStreak + 1),
            )
        }

        // 3) 连续无进展：收敛到人工介入
        if (plan.action != RerouteAction.STOP_AND_ASK_USER &&
            plan.state.noProgressStreak >= config.maxNoProgress
        ) {
            return plan.copy(
                action = RerouteAction.STOP_AND_ASK_USER,
                reason = "连续 ${plan.state.noProgressStreak} 次无实质进展，停止并说明阻碍",
            )
        }
        return plan
    }
}
