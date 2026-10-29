package top.wkbin.taixu.harness.routing

import top.wkbin.taixu.core.model.McpToolInfo

/**
 * 把 MCP 原生工具定义升级为路由器可用的 [ToolDescriptor]。
 *
 * 原则：原生 name/description/parametersJson 是**接口真相**，这里只叠加客户端侧能力画像，
 * 不改写 Schema、不根据工具自称"更可靠"来抬分。能力标签由名称/描述/注解推断，
 * 证据状态因此最多 DECLARED（工具自述）或 INFERRED（本地推断），绝不冒充实测。
 *
 * 匹配策略：ASCII 关键词按**词边界**精确匹配（避免 `run` 命中 `running`、
 * `db` 命中 `dbx` 这类子串误判）；CJK 关键词按子串匹配。一个关键词可映射到多个操作，
 * 例如 `query` 同时表示检索与读取。
 */
internal object ToolDescriptorFactory {

    /** 关键词 → 操作集合（一名多义）。 */
    private val OPERATION_KEYWORDS: Map<String, Set<String>> = linkedMapOf(
        "search" to setOf("search"),
        "find" to setOf("search"),
        "lookup" to setOf("search"),
        "检索" to setOf("search"),
        "搜索" to setOf("search"),
        "query" to setOf("search", "read"),
        "查询" to setOf("search", "read"),
        "read" to setOf("read"),
        "get" to setOf("read"),
        "fetch" to setOf("read"),
        "load" to setOf("read"),
        "dump" to setOf("read"),
        "读取" to setOf("read"),
        "获取" to setOf("read"),
        "list" to setOf("list"),
        "enumerate" to setOf("list"),
        "列出" to setOf("list"),
        "清单" to setOf("list"),
        "write" to setOf("write"),
        "create" to setOf("write"),
        "insert" to setOf("write"),
        "update" to setOf("write"),
        "save" to setOf("write"),
        "写入" to setOf("write"),
        "创建" to setOf("write"),
        "更新" to setOf("write"),
        "delete" to setOf("delete"),
        "remove" to setOf("delete"),
        "drop" to setOf("delete"),
        "unlink" to setOf("delete"),
        "删除" to setOf("delete"),
        "execute" to setOf("execute"),
        "exec" to setOf("execute"),
        "eval" to setOf("execute"),
        "run" to setOf("execute"),
        "执行" to setOf("execute"),
        "运行" to setOf("execute"),
        "transform" to setOf("transform"),
        "convert" to setOf("transform"),
        "render" to setOf("transform"),
        "转换" to setOf("transform"),
        "渲染" to setOf("transform"),
    )

    private val DOMAIN_HINTS: Map<String, Set<String>> = linkedMapOf(
        "database" to setOf("sql", "sqlite", "database", "db", "table", "数据库", "表"),
        "vcs" to setOf("git", "commit", "branch", "diff", "仓库"),
        "web" to setOf("http", "url", "browser", "page", "web", "网页", "浏览器"),
        "filesystem" to setOf("file", "path", "directory", "文件", "目录"),
        "memory" to setOf("memory", "entity", "relation", "记忆"),
        "reasoning" to setOf("thinking", "plan", "reason", "推理", "规划"),
    )

    /** 会改变外部状态的操作：出现即不再视为只读。`execute` 属于"无法证明只读"，按写处理。 */
    private val SIDE_EFFECT_OPERATIONS = setOf("write", "delete", "execute")

    fun fromMcpTools(
        serverId: String,
        serverName: String,
        tools: List<McpToolInfo>,
        enabledToolNames: Set<String>? = null,
    ): List<ToolDescriptor> = tools.map { tool ->
        val haystack = "${tool.name} ${tool.description}".lowercase()
        val operations = matchedOperations(haystack)
        val domains = matchedDomains(haystack)
        val declaredReadOnly = tool.annotations?.readOnlyHint
        val readOnly = declaredReadOnly ?: operations.none { it in SIDE_EFFECT_OPERATIONS }
        val destructive = tool.annotations?.destructiveHint == true
        val sideEffect = when {
            readOnly -> SideEffectLevel.NONE
            destructive -> SideEffectLevel.IRREVERSIBLE
            else -> SideEffectLevel.REVERSIBLE
        }
        val enabled = enabledToolNames?.contains(tool.name) ?: true
        ToolDescriptor(
            toolId = "$serverId.${tool.name}",
            serverId = serverId,
            name = tool.name,
            description = tool.description,
            familyId = familyOf(domains, operations),
            capabilities = ToolCapabilities(
                domains = domains,
                operations = operations,
                readOnly = readOnly,
                freshness = if ("web" in domains) UtilityScorer.FRESHNESS_LIVE else "",
            ),
            constraints = buildList {
                if (declaredReadOnly == null) add("读写属性未由服务端声明，按名称与描述推断")
                if (destructive) add("服务端标注为破坏性操作")
            },
            policy = ExecutionPolicy(
                idempotent = tool.annotations?.idempotentHint ?: readOnly,
                sideEffectLevel = sideEffect,
                requiresConfirmation = sideEffect != SideEffectLevel.NONE,
            ),
            availability = if (enabled) AvailabilityState.AVAILABLE else AvailabilityState.DISABLED,
            availabilityReason = if (enabled) "" else "用户在 MCP 服务设置中禁用了该工具",
            capabilityEvidence = if (declaredReadOnly != null) EvidenceState.DECLARED else EvidenceState.INFERRED,
        )
    }

    private fun matchedOperations(haystack: String): Set<String> = buildSet {
        OPERATION_KEYWORDS.forEach { (keyword, ops) ->
            if (contains(haystack, keyword)) addAll(ops)
        }
    }

    private fun matchedDomains(haystack: String): Set<String> = buildSet {
        DOMAIN_HINTS.forEach { (domain, keywords) ->
            if (keywords.any { contains(haystack, it) }) add(domain)
        }
    }

    /** ASCII 关键词按词边界匹配；含非 ASCII 字符的关键词按子串匹配。 */
    private fun contains(haystack: String, keyword: String): Boolean {
        if (keyword.any { it.code > 127 }) return haystack.contains(keyword)
        val pattern = Regex("(?<![a-z0-9_])" + Regex.escape(keyword) + "(?![a-z0-9_])")
        return pattern.containsMatchIn(haystack)
    }

    /** 同族判定：同一"领域 + 主操作"即视为互相替代的候选。 */
    private fun familyOf(domains: Set<String>, operations: Set<String>): String {
        val domain = domains.minOrNull() ?: "generic"
        val operation = operations.minOrNull() ?: "misc"
        return "$domain.$operation"
    }
}
