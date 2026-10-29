package top.wkbin.taixu.harness.directory

import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.harness.JsonArgs
import top.wkbin.taixu.core.model.McpToolInfo
import top.wkbin.taixu.harness.routing.ErrorKind
import top.wkbin.taixu.harness.routing.EvidenceState
import top.wkbin.taixu.harness.routing.ExecutionPolicy
import top.wkbin.taixu.harness.routing.InjectionVariant
import top.wkbin.taixu.harness.routing.LoopConfig
import top.wkbin.taixu.harness.routing.LoopStatus
import top.wkbin.taixu.harness.routing.RoutingInjection
import top.wkbin.taixu.harness.routing.RoutingLog
import top.wkbin.taixu.harness.routing.SideEffectLevel
import top.wkbin.taixu.harness.routing.SubTaskSpec
import top.wkbin.taixu.harness.routing.SubtaskAcceptance
import top.wkbin.taixu.harness.routing.TaskExecutionLoop
import top.wkbin.taixu.harness.routing.ToolDescriptor
import top.wkbin.taixu.harness.routing.ToolDescriptorFactory
import top.wkbin.taixu.harness.routing.ToolExecutionResult
import top.wkbin.taixu.harness.routing.ToolOutput
import top.wkbin.taixu.harness.routing.ToolRoutingEngine
import top.wkbin.taixu.harness.routing.VerificationCheck
import top.wkbin.taixu.harness.routing.VerificationKind

/**
 * 能力路由与闭环执行的扩展实现。
 *
 * 从 [CapabilityToolRouter] 拆出，原因是原文件超出项目架构规则的 maxFileLines=400。
 * 这里只放"路由/闭环"关注点，分派与调用核心仍留在原文件，职责边界清晰。
 */
    /**
     * 只读路由：用**已缓存**的工具元数据跑确定性路由引擎，返回候选排名、分数拆解与证据化理由。
     *
     * 边界：只读缓存，不启动任何 MCP 进程，不写持久化状态，因此可与 list/inspect 同等放行。
     * 目的：让模型在大量能力域中先收敛候选（Top-K + 理由），而不是一次性拿到全部工具定义。
     */
    internal suspend fun CapabilityToolRouter.routeCapabilities(args: JsonObject): Pair<Boolean, String> {
        val goal = args.stringArg("goal")?.trim().orEmpty()
        if (goal.isBlank()) return false to "route 需要 goal 参数（描述要完成的子任务）"

        val descriptors = buildRegistry()
        if (descriptors.isEmpty()) {
            return false to "路由注册表为空：没有已缓存的 MCP 工具，也没有宿主能力。请先用 list/inspect 发现能力域后再 route。"
        }

        val spec = SubTaskSpec(
            id = args.stringArg("subtask_id")?.trim().orEmpty().ifBlank { "route" },
            goal = goal,
            taskClass = args.stringArg("task_class")?.trim().orEmpty(),
            requiredOperations = splitArg(args, "operations"),
            requiredDomains = splitArg(args, "domains"),
            requiredInputTypes = splitArg(args, "input_types"),
            expectedOutputTypes = splitArg(args, "output_types"),
            requiresFreshData = isTrue(args, "fresh"),
            readOnlyOnly = isTrue(args, "read_only"),
        )
        val decision = ToolRoutingEngine(descriptors).route(spec)

        // 阶段二注入：默认保持排名摘要；format=injection 给出 6.4 规范的候选块 + 选择规则，
        // format=json 只给结构化 JSON；variant 用于 A–F 消融实验。
        val variant = InjectionVariant.entries
            .firstOrNull { it.name.equals(args.stringArg("variant")?.trim(), ignoreCase = true) }
            ?: InjectionVariant.D_RANKING_WITH_RATIONALE
        return true to when (args.stringArg("format")?.trim()?.lowercase()) {
            "injection" -> RoutingInjection.renderText(decision, variant, descriptors)
            "json" -> RoutingInjection.build(decision, variant, descriptors).toString()
            else -> RoutingLog.render(decision)
        }
    }

    /** 路由注册表：已缓存 MCP 工具 + 宿主能力域。只用缓存，不拉起进程。 */
    private suspend fun CapabilityToolRouter.buildRegistry(): List<ToolDescriptor> {
        val descriptors = ArrayList<ToolDescriptor>()
        mcpManager?.enabledServerSummaries()?.forEach { summary ->
            val tools = mcpManager.cachedToolsOf(summary.id)
            if (tools.isEmpty()) return@forEach
            val visible = toolPolicy?.filterEnabled(summary.id, tools) ?: tools
            descriptors += ToolDescriptorFactory.fromMcpTools(
                serverId = summary.id,
                serverName = summary.name,
                tools = tools,
                enabledToolNames = visible.map { it.name }.toSet(),
            )
        }
        descriptors += hostDescriptors()
        return descriptors
    }

    /**
     * 闭环执行：路由 → 执行 → 验收 → 失败分类 → 重路由。
     *
     * 与单次调用相比多做三件事：结果为空/失败时自动换同族候选；临时故障有限重试；
     * 写操作副作用不明时停止并要求先查状态，而不是重复提交。
     */
    internal suspend fun CapabilityToolRouter.invokeWithCloseLoop(
        args: JsonObject,
        seedServerId: String,
        seedTool: String,
        callArgs: JsonObject,
        workspace: String,
        parentToolCallId: String?,
        operationId: String?,
        sessionId: String,
        metadata: MutableMap<String, String>,
    ): Pair<Boolean, String> {
        val registry = buildRegistry()
        if (registry.isEmpty()) {
            return invokeCapability(seedServerId, seedTool, callArgs, workspace, parentToolCallId, operationId, sessionId, metadata)
        }
        // callArgs 是**原服务专用**的参数契约：跨服务切换必然参数不匹配，
        // 因此自动闭环只在本服务内换同族工具；跨服务替代应由模型按 route 结果重新构造参数。
        val sameServer = registry.filter { it.serverId.equals(seedServerId, ignoreCase = true) }
        val descriptors = sameServer.ifEmpty { registry }
        val goal = args.stringArg("goal")?.trim().orEmpty()
            .ifBlank { "调用 $seedServerId.$seedTool 并返回可用结果" }
        val spec = SubTaskSpec(
            id = args.stringArg("subtask_id")?.trim().orEmpty().ifBlank { "call-$seedTool" },
            goal = goal,
            taskClass = args.stringArg("task_class")?.trim().orEmpty(),
            requiredOperations = splitArg(args, "operations"),
            requiredDomains = splitArg(args, "domains"),
        )
        val acceptance = SubtaskAcceptance(
            args.stringArg("expect_contains")
                ?.split(',', '\uFF0C')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { items ->
                    listOf(
                        VerificationCheck(VerificationKind.NON_EMPTY, description = "返回非空"),
                        VerificationCheck(VerificationKind.CONTAINS_ALL, params = items, description = "覆盖关键内容"),
                    )
                }
                ?: listOf(VerificationCheck(VerificationKind.NON_EMPTY, description = "返回非空")),
        )
        val loop = TaskExecutionLoop(ToolRoutingEngine(descriptors), LoopConfig(acceptance = acceptance))
        val outcome = loop.run(spec) { descriptor ->
            val (ok, text) = invokeCapability(
                descriptor.serverId, descriptor.name, callArgs,
                workspace, parentToolCallId, operationId, sessionId, metadata,
            )
            ToolExecutionResult(
                output = if (ok) ToolOutput(text = text) else null,
                error = if (ok) null else ErrorKind.UNKNOWN,
                errorMessage = if (ok) "" else text,
                renderedText = text,
            )
        }
        return when (outcome.status) {
            LoopStatus.SUCCEEDED -> true to outcome.finalText
            else -> false to buildString {
                appendLine(outcome.finalText)
                appendLine()
                append(outcome.renderTrace())
            }
        }
    }

    /** 宿主能力域的静态描述符：只读属性以可信风险策略目录为准，不用名称猜。 */
    private fun CapabilityToolRouter.hostDescriptors(): List<ToolDescriptor> {
        val infos = HostCapabilityDirectory.DEFERRED_ACTIONS
            .filterNot { HostCapabilityDirectory.isHidden(it.name) }
            .map { action ->
                McpToolInfo(
                    serverId = HostCapabilityDirectory.SERVER_ID,
                    serverName = "host",
                    name = action.name,
                    description = action.description,
                )
            }
        return ToolDescriptorFactory
            .fromMcpTools(HostCapabilityDirectory.SERVER_ID, "host", infos)
            .map { descriptor ->
                if (descriptor.name in HostCapabilityDirectory.READ_ONLY_ACTIONS) {
                    descriptor.copy(
                        capabilities = descriptor.capabilities.copy(readOnly = true),
                        policy = ExecutionPolicy(
                            idempotent = true,
                            sideEffectLevel = SideEffectLevel.NONE,
                            requiresConfirmation = false,
                        ),
                        capabilityEvidence = EvidenceState.DECLARED,
                    )
                } else {
                    descriptor
                }
            }
    }

    internal fun splitArg(args: JsonObject, key: String): Set<String> =
        args.stringArg(key).orEmpty()
            .split(',', ' ', ';', '\uFF0C')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    internal fun isTrue(args: JsonObject, key: String): Boolean =
        args.stringArg(key)?.trim()?.lowercase() in setOf("true", "1", "yes")

private fun JsonObject.stringArg(key: String): String? =
    JsonArgs.optionalString(this, key)?.trim()?.takeIf { it.isNotEmpty() }
