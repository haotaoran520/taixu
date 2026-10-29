package top.wkbin.taixu.harness.routing

/**
 * 确定性路由引擎（方案"阶段一：最小可用路由器"）。
 *
 * 流水线固定为五段，每段职责单一且可观测：
 * 1. **硬约束过滤**：可用性、权限、只读要求、任务内黑名单——准入问题不参与打分；
 * 2. **多路召回**：关键词 / BM25 / 能力标签取并集，召回强调覆盖率；
 * 3. **效用打分**：能力契合、实测成功、结果质量、运行可靠性、任务内反馈，输出分数拆解；
 * 4. **Top-K 暴露**：按分差决定给几个候选，而不是永远只给 Top-1；
 * 5. **理由与诊断**：证据化理由；无候选时明确拒绝并说明缺什么能力。
 *
 * 显式不做的事：不因"已连接/已缓存/调用次数多"加分；不把成本纳入主效用。
 */
internal class ToolRoutingEngine(
    private val descriptors: List<ToolDescriptor>,
    private val config: RoutingConfig = RoutingConfig(),
    private val retriever: ToolRetriever = ToolRetriever(config),
    private val rationaleBuilder: RoutingRationaleBuilder = RoutingRationaleBuilder(),
) {
    fun route(spec: SubTaskSpec, state: TaskRoutingState = TaskRoutingState()): RouteDecision {
        val exclusions = ArrayList<ToolExclusion>()
        val eligible = descriptors.filter { tool ->
            val reason = exclusionOf(tool, spec, state)
            if (reason != null) {
                exclusions += reason
                false
            } else {
                true
            }
        }

        val recalled = retriever.recall(eligible, spec)
        if (recalled.isEmpty()) {
            return RouteDecision(
                subtask = spec,
                candidates = emptyList(),
                exclusions = exclusions.sortedBy { it.toolId },
                missingCapabilities = diagnoseMissingCapabilities(spec),
            )
        }

        val scored: List<Pair<ToolDescriptor, ScoreBreakdown>> = recalled
            .map { it to UtilityScorer.score(it, spec, state.feedbackFor(it.toolId)) }
            .sortedWith(
                compareByDescending<Pair<ToolDescriptor, ScoreBreakdown>> { it.second.utility }
                    .thenBy { it.first.toolId },
            )

        val count = candidateCount(scored)
        val candidates = scored.take(count).mapIndexed { index, (tool, breakdown) ->
            RouteCandidate(
                tool = tool,
                rank = index + 1,
                breakdown = breakdown,
                rationale = rationaleBuilder.build(tool, spec, breakdown, scored, index, peers = descriptors),
            )
        }

        return RouteDecision(
            subtask = spec,
            candidates = candidates,
            exclusions = exclusions.sortedBy { it.toolId },
            missingCapabilities = if (candidates.isEmpty()) diagnoseMissingCapabilities(spec) else emptyList(),
        )
    }

    /** 硬约束：返回 null 表示准入通过。 */
    private fun exclusionOf(
        tool: ToolDescriptor,
        spec: SubTaskSpec,
        state: TaskRoutingState,
    ): ToolExclusion? {
        when (tool.availability) {
            AvailabilityState.AVAILABLE -> Unit
            AvailabilityState.DISABLED -> return ToolExclusion(
                tool.toolId, ExclusionReason.DISABLED,
                tool.availabilityReason.ifBlank { "用户在设置中禁用" },
            )
            AvailabilityState.UNAVAILABLE -> return ToolExclusion(
                tool.toolId, ExclusionReason.UNAVAILABLE,
                tool.availabilityReason.ifBlank { "依赖缺失或服务不可达" },
            )
            AvailabilityState.UNAUTHORIZED -> return ToolExclusion(
                tool.toolId, ExclusionReason.UNAUTHORIZED,
                tool.availabilityReason.ifBlank { "权限或安全策略不允许" },
            )
        }
        if (spec.readOnlyOnly && !tool.capabilities.readOnly) {
            return ToolExclusion(tool.toolId, ExclusionReason.READ_ONLY_VIOLATION, "本任务要求只读，该工具会改变状态")
        }
        if (spec.readOnlyOnly && tool.policy.requiresConfirmation) {
            return ToolExclusion(
                tool.toolId, ExclusionReason.SAFETY_CONFIRMATION_REQUIRED,
                "只读规划模式下不引入需要用户确认的写操作",
            )
        }
        if (state.isBlacklisted(tool.toolId)) {
            return ToolExclusion(tool.toolId, ExclusionReason.BLACKLISTED_IN_TASK, "本任务中已被证实不支持该场景")
        }
        // 完全不具备所要求操作能力的工具不进入候选：这是"能不能做"，不是"做得好不好"。
        if (spec.requiredOperations.isNotEmpty() &&
            tool.capabilities.operations.none { it in spec.requiredOperations }
        ) {
            return ToolExclusion(
                tool.toolId, ExclusionReason.MISSING_CAPABILITY,
                "不具备所需操作：${spec.requiredOperations.joinToString("/")}",
            )
        }
        return null
    }

    /** 按分差决定候选数量：胶着时多给，明显领先时少给。 */
    private fun candidateCount(ranked: List<Pair<ToolDescriptor, ScoreBreakdown>>): Int {
        if (ranked.size <= config.minTopK) return ranked.size
        val gap = ranked[0].second.utility - ranked[1].second.utility
        val desired = when {
            gap >= config.clearLeaderGap -> config.minTopK
            gap < config.closeScoreGap -> config.maxTopK
            else -> config.topK
        }
        return desired.coerceIn(1, ranked.size)
    }

    /**
     * 缺口诊断：区分"没有工具提供该能力"与"有工具但都被过滤掉"。
     * 前者说明需要别的手段，后者说明应该让用户去启用或修依赖。
     */
    private fun diagnoseMissingCapabilities(spec: SubTaskSpec): List<String> {
        val findings = LinkedHashSet<String>()
        fun check(label: String, required: Set<String>, provide: (ToolDescriptor) -> Set<String>) {
            required.forEach { item ->
                val providers = descriptors.filter { item in provide(it) }
                val usable = providers.filter { it.isEligible }
                when {
                    providers.isEmpty() -> findings += "无任何工具提供$label「$item」"
                    usable.isEmpty() -> findings += "$label「$item」仅有不可用工具：${providers.joinToString("/") { it.toolId }}"
                }
            }
        }
        check("操作", spec.requiredOperations) { it.capabilities.operations }
        check("领域", spec.requiredDomains) { it.capabilities.domains }
        check("输入类型", spec.requiredInputTypes) { it.capabilities.inputTypes }
        check("输出类型", spec.expectedOutputTypes) { it.capabilities.outputTypes }
        if (spec.requiresFreshData && descriptors.none { it.isEligible && it.capabilities.freshness == UtilityScorer.FRESHNESS_LIVE }) {
            findings += "无可用工具保证实时数据（freshness=live_fetch）"
        }
        if (findings.isEmpty() && spec.goal.isNotBlank()) {
            findings += "没有任何可用工具与任务描述产生召回（关键词/BM25/能力标签均未命中），可能需要先启用的能力域或补充工具"
        }
        return findings.toList()
    }
}
