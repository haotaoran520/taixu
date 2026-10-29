package top.wkbin.taixu.harness.directory

import kotlinx.serialization.json.JsonObject
import top.wkbin.taixu.core.model.McpToolAnnotations
import top.wkbin.taixu.core.model.McpToolInfo
import top.wkbin.taixu.harness.routing.ErrorKind
import top.wkbin.taixu.harness.routing.EvidenceState
import top.wkbin.taixu.harness.routing.InjectionVariant
import top.wkbin.taixu.harness.routing.ExecutionPolicy
import top.wkbin.taixu.harness.routing.LoopStatus
import top.wkbin.taixu.harness.routing.RoutingInjection
import top.wkbin.taixu.harness.routing.RoutingLog
import top.wkbin.taixu.harness.routing.SideEffectLevel
import top.wkbin.taixu.harness.routing.SubTaskSpec
import top.wkbin.taixu.harness.routing.SubtaskAcceptance
import top.wkbin.taixu.harness.routing.LoopConfig
import top.wkbin.taixu.harness.routing.VerificationCheck
import top.wkbin.taixu.harness.routing.VerificationKind
import top.wkbin.taixu.harness.routing.TaskExecutionLoop
import top.wkbin.taixu.harness.routing.ToolDescriptor
import top.wkbin.taixu.harness.routing.ToolDescriptorFactory
import top.wkbin.taixu.harness.routing.ToolExecutionResult
import top.wkbin.taixu.harness.routing.ToolOutput
import top.wkbin.taixu.harness.routing.ToolRoutingEngine
import top.wkbin.taixu.harness.mcp.McpManager
import top.wkbin.taixu.harness.mcp.McpToolCallPolicy
import top.wkbin.taixu.harness.JsonArgs

/**
 * use_capability 统一代理的分发器（自 ToolExecutor 零增长迁出，对齐 Reasonix 的代理入口）：
 * - list：列出已启用的 MCP 服务与内置宿主能力域，**不启动任何服务器进程**；
 * - inspect：server="host" 返回目录清单（零进程、零缓存依赖）；其余服务查看工具清单与参数
 *   （缓存为空时按需发现一次——模型必须拿到完整清单才能构造 call）；
 * - call：server="host" 走 [HostCapabilityDirectory] 展平后进入与直接 host 调用完全相同的
 *   执行路径（审批已在 ToolExecutor 入口按同一矩阵完成）；其余按 (server, tool) 交给
 *   MCP 管理器执行（未连接的服务在此按需启动并发现）；
 * - decline：模型显式放弃某能力，确认即回。
 *
 * 宿主域调用不依赖 MCP 管理器：目录是纯静态事实源，inspect/call 全部本地完成。
 */
class CapabilityToolRouter(
    private val mcpManager: McpManager?,
    /** 嵌套记录脱敏（调用方注入 SecretRedactor；目录层保持纯净）。 */
    private val argRedactor: (String) -> String = { it },
    private val plugins: top.wkbin.taixu.harness.plugins.HarnessPluginRegistry? = null,
    /** 按工具策略过滤模型可见面并在执行前拒绝已禁用工具。 */
    private val toolPolicy: McpToolCallPolicy? = null,
    /** 宿主执行通道：与直接 host 调用共用同一 executeHost（含输出上限与截图 metadata 附带）。 */
    private val hostExecutor: suspend (JsonObject, String?, String, MutableMap<String, String>) -> Pair<Boolean, String>,
) {
    suspend fun execute(
        args: JsonObject,
        workspace: String,
        parentToolCallId: String?,
        operationId: String?,
        sessionId: String,
        metadata: MutableMap<String, String>,
        scriptCall: (suspend (String, String, JsonObject) -> Pair<Boolean, String>)? = null,
    ): Pair<Boolean, String> {
        val action = args.stringArg("action")?.lowercase().orEmpty()
        return when (action) {
            "list" -> listCapabilities()
            "inspect" -> inspect(args)
            "call" -> call(args, workspace, parentToolCallId, operationId, sessionId, metadata)
            "decline" -> {
                val serverId = args.stringArg("server").orEmpty().trim()
                val tool = args.stringArg("tool").orEmpty().trim()
                true to "已记录：不再尝试 ${if (serverId.isNotBlank()) "$serverId." else ""}$tool。请改用其他方式完成任务或向用户说明障碍。"
            }
            "script" -> script(args, scriptCall)
            "route" -> routeCapabilities(args)
            else -> false to "action 必须是 list / inspect / call / decline / script / route 之一"
        }
    }

    /**
     * codemode 脚本动作：模型写 JS，脚本内 capability.call(server, tool, args) 批量/循环/条件
     * 调用能力域。每条内层调用经调用方注入的受控入口走完整校验、审批、
     * 嵌套留痕与脱敏——脚本是编排层，不是绕过审批的通道。
     */
    private suspend fun script(
        args: JsonObject,
        scriptCall: (suspend (String, String, JsonObject) -> Pair<Boolean, String>)?,
    ): Pair<Boolean, String> {
        val dispatch = scriptCall ?: return false to "脚本受控执行入口未初始化，本次调用未执行。"
        val code = args.stringArg("code").orEmpty()
        if (code.isBlank()) return false to "script 需要 code 参数（JS 脚本；全局对象 capability.call(server, tool, args) 返回 {ok, output}）"
        val timeoutMs = args.stringArg("timeout_seconds")?.trim()?.toLongOrNull()
            ?.coerceIn(1, CapabilityScriptRunner.MAX_TIMEOUT_SECONDS)?.times(1000)
            ?: CapabilityScriptRunner.DEFAULT_TIMEOUT_MS
        val runner = CapabilityScriptRunner(argRedactor, dispatch)
        return runner.execute(code, timeoutMs)
    }

    /**
     * 只读路由：用**已缓存**的工具元数据跑确定性路由引擎，返回候选排名、分数拆解与证据化理由。
     *
     * 边界：只读缓存，不启动任何 MCP 进程，不写持久化状态，因此可与 list/inspect 同等放行。
     * 目的：让模型在大量能力域中先收敛候选（Top-K + 理由），而不是一次性拿到全部工具定义。
     */
    private suspend fun routeCapabilities(args: JsonObject): Pair<Boolean, String> {
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
    private suspend fun buildRegistry(): List<ToolDescriptor> {
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
    private suspend fun invokeWithCloseLoop(
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
    private fun hostDescriptors(): List<ToolDescriptor> {
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

    private fun splitArg(args: JsonObject, key: String): Set<String> =
        args.stringArg(key).orEmpty()
            .split(',', ' ', ';', '\uFF0C')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun isTrue(args: JsonObject, key: String): Boolean =
        args.stringArg(key)?.trim()?.lowercase() in setOf("true", "1", "yes")

    private suspend fun listCapabilities(): Pair<Boolean, String> {
        val summaries = mcpManager?.enabledServerSummaries().orEmpty()
        val hostLine = HostCapabilityDirectory.listSummaryLine()
        val pluginLines = plugins?.summaries().orEmpty()
        if (summaries.isEmpty()) {
            return true to buildString {
                appendLine("可用的能力域：")
                appendLine(hostLine)
                if (pluginLines.isNotEmpty()) appendLine(pluginLines)
                append("未启用任何 MCP 服务。可在「设置 → MCP 插件与协议生态」启用内置能力或添加自定义服务。")
            }
        }
        return true to buildString {
            appendLine("可用的能力域（MCP 服务未连接的在首次 call 时自动启动并发现工具）：")
            appendLine(hostLine)
            if (pluginLines.isNotEmpty()) appendLine(pluginLines)
            summaries.forEach { summary ->
                appendLine(
                    "- ${summary.id} · ${summary.name} · 已缓存 ${summary.cachedToolCount} 个工具 · " +
                        when (summary.processState) {
                            top.wkbin.taixu.core.model.McpConnectionState.SLEEPING -> "已就绪（休眠）"
                            top.wkbin.taixu.core.model.McpConnectionState.WAKING -> "正在唤醒"
                            top.wkbin.taixu.core.model.McpConnectionState.OFFLINE -> "进程离线"
                            else -> if (summary.connected) "工具目录已就绪" else "未发现工具"
                        },
                )
            }
            append("用 inspect 查看某能力域的工具清单与参数，用 call 调用。")
        }
    }

    private suspend fun inspect(args: JsonObject): Pair<Boolean, String> {
        val serverId = args.stringArg("server")?.trim().orEmpty()
        if (serverId.isBlank()) return false to "inspect 需要 server 参数（先用 list 查看可用的能力域 id）"
        if (serverId.startsWith("plugin.")) return plugins?.inspect(serverId) ?: (false to "插件目录未初始化")
        if (serverId.lowercase() == HostCapabilityDirectory.SERVER_ID) {
            return true to HostCapabilityDirectory.inspectListing()
        }
        val manager = mcpManager ?: return false to "未初始化 MCP 管理器"
        // 缓存为空 = 服务尚未连接过：按需发现一次（唯一会拉起进程的 inspect 场景——
        // 模型无从得知未连接服务的工具名，必须给它完整清单才能构造 call）
        var tools = manager.cachedToolsOf(serverId)
        if (tools.isEmpty()) {
            tools = manager.discoverServerTools(serverId)
        }
        if (tools.isEmpty()) {
            val lastError = manager.lastErrorForCall(serverId)
            return false to "MCP[$serverId] 工具发现失败或服务不可用${lastError?.let { "：$it" } ?: "（未启用或不存在）"}。" +
                "可稍后重试 inspect，或检查该服务的设置与沙箱环境。"
        }
        val visibleTools = toolPolicy?.filterEnabled(serverId, tools) ?: tools
        if (visibleTools.isEmpty()) {
            return false to "MCP[$serverId] 的工具都已在设置中禁用；模型侧不提供任何可调用工具。" +
                "如需使用，请用户在 MCP 服务详情中重新启用。"
        }
        val rendered = visibleTools.joinToString("\n\n") { tool ->
            buildString {
                appendLine("### ${tool.name}")
                if (tool.description.isNotBlank()) appendLine(tool.description.trim().take(400))
                if (tool.parametersJson.isNotBlank() && tool.parametersJson != "{}") {
                    appendLine("参数：${tool.parametersJson.take(1200)}")
                }
            }
        }
        val body = if (rendered.length > MAX_INSPECT_CHARS) {
            rendered.take(MAX_INSPECT_CHARS) + "\n…[清单过长已截断，可直接按已知工具名 call]"
        } else {
            rendered
        }
        return true to "MCP[$serverId] 工具清单（${visibleTools.size} 个）：\n$body"
    }

    /**
     * call：内层工具经 [NestedCalls] 留下有界审计记录（参数/错误脱敏后入库 metadata，
     * 结果正文不重复存储）。被目录拒绝的尝试记为 blocked——"试图做什么"本身就是审计事实。
     */
    private suspend fun call(
        args: JsonObject,
        workspace: String,
        parentToolCallId: String?,
        operationId: String?,
        sessionId: String,
        metadata: MutableMap<String, String>,
    ): Pair<Boolean, String> {
        val serverId = args.stringArg("server")?.trim().orEmpty()
        val tool = args.stringArg("tool")?.trim().orEmpty()
        if (serverId.isBlank() || tool.isBlank()) {
            return false to "call 需要 server 与 tool 参数（先 inspect 查看可用的工具名与参数）"
        }
        val callArgs = args["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        // 默认路径完全不变；只有显式 auto_route=true 才走"执行 → 验收 → 重路由"闭环。
        if (!isTrue(args, "auto_route")) {
            return invokeCapability(serverId, tool, callArgs, workspace, parentToolCallId, operationId, sessionId, metadata)
        }
        return invokeWithCloseLoop(
            args, serverId, tool, callArgs, workspace, parentToolCallId, operationId, sessionId, metadata,
        )
    }

    /**
     * 内层能力调用核心：host deferred 校验 / MCP 分派 + 有界嵌套留痕。
     * 直接 call 与 codemode 脚本共用——审批与留痕语义不因编排形态改变。
     */
    internal suspend fun invokeCapability(
        serverId: String,
        tool: String,
        callArgs: JsonObject,
        workspace: String,
        parentToolCallId: String?,
        operationId: String?,
        sessionId: String,
        metadata: MutableMap<String, String>,
    ): Pair<Boolean, String> {
        val logicalName = "${serverId.lowercase()}.$tool"
        val startedAt = System.currentTimeMillis()

        val outcome: Pair<Boolean, String> = if (serverId.lowercase() == HostCapabilityDirectory.SERVER_ID) {
            val deferred = HostCapabilityDirectory.deferredAction(tool)
            if (deferred == null || HostCapabilityDirectory.isHidden(tool)) {
                val reason = if (HostCapabilityDirectory.isHidden(tool)) {
                    "工具「$tool」已在当前构建中停用。"
                } else {
                    "宿主能力域没有工具「$tool」。用 inspect 查看 host 域的可用清单；" +
                        "高频查询类动作（status/logcat/screen_click 等）请直接调用 host 工具。"
                }
                NestedCalls.append(
                    metadata, parentToolCallId, logicalName, NestedCalls.STATUS_BLOCKED,
                    System.currentTimeMillis() - startedAt, callArgs.toString(), reason, argRedactor,
                )
                return false to reason
            }
            hostExecutor(HostCapabilityDirectory.flattenHostArgs(tool, callArgs), operationId, sessionId, metadata)
        } else if (serverId.startsWith("plugin.")) {
            plugins?.call(serverId, tool, top.wkbin.taixu.harness.plugins.PluginToolRequest(callArgs, sessionId, workspace, operationId))
                ?: (false to "插件目录未初始化")
        } else {
            val manager = mcpManager ?: run {
                val reason = "未初始化 MCP 管理器"
                NestedCalls.append(
                    metadata, parentToolCallId, logicalName, NestedCalls.STATUS_BLOCKED,
                    System.currentTimeMillis() - startedAt, callArgs.toString(), reason, argRedactor,
                )
                return false to reason
            }
            if (toolPolicy?.policyFor(serverId, tool)?.enabled == false) {
                val reason = "MCP[$serverId] 工具「$tool」已在设置中禁用，本次调用未执行。请改用其他能力或让用户重新启用。"
                NestedCalls.append(
                    metadata, parentToolCallId, logicalName, NestedCalls.STATUS_BLOCKED,
                    System.currentTimeMillis() - startedAt, callArgs.toString(), reason, argRedactor,
                )
                return false to reason
            }
            manager.executeCapabilityTool(serverId, tool, callArgs, workspace)
        }

        NestedCalls.append(
            metadata = metadata,
            parentToolCallId = parentToolCallId,
            name = logicalName,
            status = if (outcome.first) NestedCalls.STATUS_OK else NestedCalls.STATUS_ERROR,
            durationMs = System.currentTimeMillis() - startedAt,
            arguments = callArgs.toString(),
            error = if (outcome.first) null else outcome.second,
            redact = argRedactor,
        )
        return outcome
    }
}

/** 本文件内共享的字符串参数读取：复用 [JsonArgs]，去首尾空白，空串视作缺省。 */
private fun JsonObject.stringArg(key: String): String? =
    JsonArgs.optionalString(this, key)?.trim()?.takeIf { it.isNotEmpty() }

/** inspect 清单输出上限：超出截断并指引直接 call。 */
private const val MAX_INSPECT_CHARS = 16_000

/**
 * 从 MCP 缓存解析审批注解：use_capability(call) 按 (server, tool) 查缓存；
 * legacy mcp__<server>__<tool> 直接名按名段解析。未缓存（服务尚未连接）或解析
 * 失败一律返回 null——保守不升级，绝不臆造注解。
 */
suspend fun McpManager?.annotationsForCall(args: JsonObject, rawToolName: String?): McpToolAnnotations? {
    val manager = this ?: return null
    val server: String
    val tool: String
    if (rawToolName == "use_capability") {
        if (args.stringArg("action")?.lowercase() != "call") return null
        server = args.stringArg("server")?.trim().orEmpty()
        tool = args.stringArg("tool")?.trim().orEmpty()
    } else {
        // legacy 兼容路径：mcp__<server 段>__<tool>（编码名含 hash 尾段，取中段足够）
        val parts = rawToolName?.split("__").orEmpty()
        if (parts.size < 3 || parts[0] != "mcp") return null
        server = parts[1]
        tool = parts[2]
    }
    if (server.isBlank() || tool.isBlank()) return null
    return manager.cachedToolsOf(server).firstOrNull { it.name == tool }?.annotations
}
