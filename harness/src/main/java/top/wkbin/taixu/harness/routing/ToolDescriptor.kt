package top.wkbin.taixu.harness.routing

/**
 * 工具能力画像的证据状态。
 *
 * 每一项优势都必须能回答"凭什么这样说"：来自实测统计、工具自述、本地推断，还是根本没有数据。
 * 方案明确要求：没有实测数据时不得把默认值解释成实际性能。
 */
internal enum class EvidenceState {
    /** 来自真实执行/评测统计。 */
    MEASURED,

    /** 来自工具自身的名称/描述/注解等自述信息。 */
    DECLARED,

    /** 由路由器按名称、Schema 等信号推断（可解释但非权威）。 */
    INFERRED,

    /** 没有任何证据。 */
    UNKNOWN,
}

/** 副作用等级：决定执行前是否需要确认，以及失败后能否安全重放。 */
internal enum class SideEffectLevel {
    NONE,
    REVERSIBLE,
    IRREVERSIBLE,
}

/** 准入状态：可用性是**过滤条件**，不是排名偏好。 */
internal enum class AvailabilityState {
    AVAILABLE,
    /** 用户显式禁用（设置里的工具开关）。 */
    DISABLED,
    /** 依赖缺失/进程不可达等运行期不可用。 */
    UNAVAILABLE,
    /** 权限或安全策略不允许。 */
    UNAUTHORIZED,
}

/** 统计置信度：样本不足时必须显式暴露"未知"，而不是给个好看的分数。 */
internal enum class ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

/**
 * 能力标签集合：回答"这个工具能不能做这类事"。
 *
 * 只描述客观能力，不表达主观偏好——因此不放"是否已连接""是否常用"这类会污染排名的字段。
 */
internal data class ToolCapabilities(
    val domains: Set<String> = emptySet(),
    val operations: Set<String> = emptySet(),
    val inputTypes: Set<String> = emptySet(),
    val outputTypes: Set<String> = emptySet(),
    val readOnly: Boolean = true,
    /** 数据新鲜度语义：live_fetch / cached / static 等；空串表示未声明。 */
    val freshness: String = "",
)

/**
 * 某一任务类别上的实测表现。
 *
 * [sampleCount] 与 [evidence] 是一等公民：少量样本不允许被读成"高成功率"，
 * 统计层按 Beta 先验收缩（见 [UtilityScorer]）。
 */
internal data class TaskClassStat(
    val taskClass: String,
    val successRate: Double,
    val resultQuality: Double,
    val sampleCount: Int,
    val lastEvaluatedAt: String? = null,
    val evidence: EvidenceState = EvidenceState.MEASURED,
) {
    val confidence: ConfidenceLevel
        get() = when {
            sampleCount >= MIN_HIGH_SAMPLES -> ConfidenceLevel.HIGH
            sampleCount >= MIN_MEDIUM_SAMPLES -> ConfidenceLevel.MEDIUM
            sampleCount > 0 -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.UNKNOWN
        }

    companion object {
        const val MIN_HIGH_SAMPLES = 30
        const val MIN_MEDIUM_SAMPLES = 10
    }
}

/** 运行可靠性画像：失败/超时/输出异常，与"任务成功率"分开记录。 */
internal data class ReliabilityStat(
    val invocationCount: Int = 0,
    val failureCount: Int = 0,
    val timeoutCount: Int = 0,
    val schemaViolationCount: Int = 0,
    val evidence: EvidenceState = EvidenceState.UNKNOWN,
) {
    val hasEvidence: Boolean get() = evidence == EvidenceState.MEASURED && invocationCount > 0
}

/** 可靠性查询结果：区分"本任务类别实测"与"全局统计回退"，后者不得被当成同类证据。 */
internal data class ReliabilityLookup(
    val stat: ReliabilityStat,
    val scopedToTaskClass: Boolean,
)

/**
 * 质量画像：按任务类别拆分，绝不使用"全任务混合成功率"代替。
 *
 * 阶段二要求四个维度都带任务类别口径：成功率、质量、可靠性、样本量。
 * 当某类别缺可靠性数据时允许回退到全局统计，但必须通过 [ReliabilityLookup.scopedToTaskClass]
 * 显式告知调用方"这不是同类证据"。
 */
internal data class ToolQualityProfile(
    val taskClasses: Map<String, TaskClassStat> = emptyMap(),
    /** 全局可靠性：跨任务聚合，仅作回退。 */
    val reliability: ReliabilityStat = ReliabilityStat(),
    /** 按任务类别拆分的可靠性。 */
    val reliabilityByTaskClass: Map<String, ReliabilityStat> = emptyMap(),
) {
    fun statFor(taskClass: String?): TaskClassStat? {
        val key = taskClass?.takeIf { it.isNotBlank() } ?: return null
        return taskClasses[key]
    }

    fun reliabilityFor(taskClass: String?): ReliabilityLookup? {
        val key = taskClass?.takeIf { it.isNotBlank() }
        val scoped = key?.let { reliabilityByTaskClass[it] }
        if (scoped != null && scoped.hasEvidence) return ReliabilityLookup(scoped, true)
        if (reliability.hasEvidence) return ReliabilityLookup(reliability, false)
        return null
    }
}

/** 执行策略：安全约束的执行期表达。 */
internal data class ExecutionPolicy(
    val idempotent: Boolean = false,
    val sideEffectLevel: SideEffectLevel = SideEffectLevel.NONE,
    val requiresConfirmation: Boolean = false,
)

/**
 * 工具描述符：MCP 原生定义（name/description/inputSchema）是接口真相，
 * 这里叠加的是客户端维护的扩展层（能力、约束、质量、策略），不改写真实 Schema。
 */
internal data class ToolDescriptor(
    val toolId: String,
    val serverId: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    /** 同族标识：功能重叠的工具归为一族，用于"同类比较"与替代关系。 */
    val familyId: String = "",
    val capabilities: ToolCapabilities = ToolCapabilities(),
    /** 已知限制：用来区分"相关"与"真能做"。 */
    val constraints: List<String> = emptyList(),
    val quality: ToolQualityProfile = ToolQualityProfile(),
    val policy: ExecutionPolicy = ExecutionPolicy(),
    val availability: AvailabilityState = AvailabilityState.AVAILABLE,
    val availabilityReason: String = "",
    val capabilityEvidence: EvidenceState = EvidenceState.INFERRED,
    /**
     * 同族横向差异字段：维度名 → 该工具在该维度上的**客观取值**。
     *
     * 只允许放可从工具声明或实测得到的事实（如 input=url、rendering=static_only、output=text），
     * 用于生成"比同族更适合哪里"的差异说明；不允许放"更稳定""更快"这类无证据的主观判断。
     */
    val familyDifferentiators: Map<String, String> = emptyMap(),
    /** 输入 Schema 摘要：注入候选时随工具一并给模型，避免"选对工具却用错参数"。 */
    val inputSchemaSummary: String = "",
) {
    val isEligible: Boolean get() = availability == AvailabilityState.AVAILABLE

    /**
     * 检索文本：只用客观描述与能力标签构造，**不包含**连接状态、调用次数等偏好信号，
     * 避免排序被"用得多的工具"隐性带偏。
     */
    val searchableText: String
        get() = buildString {
            append(name).append(' ')
            append(description).append(' ')
            append(capabilities.domains.joinToString(" ")).append(' ')
            append(capabilities.operations.joinToString(" ")).append(' ')
            append(capabilities.inputTypes.joinToString(" ")).append(' ')
            append(capabilities.outputTypes.joinToString(" ")).append(' ')
            append(familyId).append(' ')
            append(constraints.joinToString(" "))
        }.trim()
}

/**
 * 子任务规格：把自然语言请求转成可路由的结构化条件。
 *
 * 由上层 Task Analyzer 填充；路由器只消费这些字段，不自行臆测用户意图。
 */
internal data class SubTaskSpec(
    val id: String,
    val goal: String,
    /** 任务类别（用于取对应统计），例如 static_page_extraction。 */
    val taskClass: String = "",
    val requiredOperations: Set<String> = emptySet(),
    val requiredDomains: Set<String> = emptySet(),
    val requiredInputTypes: Set<String> = emptySet(),
    val expectedOutputTypes: Set<String> = emptySet(),
    val requiresFreshData: Boolean = false,
    val readOnlyOnly: Boolean = false,
    val acceptanceCriteria: List<String> = emptyList(),
) {
    val hasHardRequirements: Boolean
        get() = requiredOperations.isNotEmpty() || requiredDomains.isNotEmpty() ||
            requiredInputTypes.isNotEmpty() || expectedOutputTypes.isNotEmpty() || requiresFreshData
}

/** 任务内反馈：只影响当前任务，跨会话长期统计另行维护。 */
internal data class ToolTaskFeedback(
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val qualityFailureCount: Int = 0,
    /** 当前任务中已被证实不支持该场景：直接排除，不再浪费一次调用。 */
    val unsupported: Boolean = false,
    val lastFailureType: FailureType? = null,
) {
    val isEmpty: Boolean
        get() = successCount == 0 && failureCount == 0 && qualityFailureCount == 0 && !unsupported
}

/** 失败分类：不同故障类型必须走不同策略，不能统统"换下一个候选"。 */
internal enum class FailureType {
    /** 能力不匹配：重排同族候选。 */
    CAPABILITY_MISMATCH,

    /** 参数错误：允许按 Schema 修正一次。 */
    INVALID_ARGUMENTS,

    /** 临时基础设施故障：有限重试，别当成能力缺陷。 */
    TRANSIENT_INFRASTRUCTURE,

    /** 调用成功但结果不合格：降权并换互补工具。 */
    OUTPUT_QUALITY,

    /** 工具正常返回但任务未完成：诊断缺失能力。 */
    TASK_INCOMPLETE,

    /** 写操作结果不确定：先查状态，禁止盲目重放。 */
    UNKNOWN_SIDE_EFFECT,
}

/** 任务级路由状态：承载当前任务的临时反馈与调用预算。 */
internal data class TaskRoutingState(
    val feedback: Map<String, ToolTaskFeedback> = emptyMap(),
    val callBudget: Int = DEFAULT_CALL_BUDGET,
    val callsUsed: Int = 0,
) {
    fun feedbackFor(toolId: String): ToolTaskFeedback = feedback[toolId] ?: ToolTaskFeedback()

    fun isBlacklisted(toolId: String): Boolean = feedbackFor(toolId).unsupported

    val budgetExhausted: Boolean get() = callsUsed >= callBudget

    companion object {
        const val DEFAULT_CALL_BUDGET = 12
    }
}
