package top.wkbin.taixu.harness.routing

/**
 * 文本切分：ASCII 词按非字母数字边界切，CJK 取单字 + 相邻双字。
 *
 * 不引入外部分词依赖（手机端体积与确定性优先），召回阶段强调**覆盖率**，
 * 精度交给后面的能力过滤与效用排序。
 */
internal object Tokenizer {
    private val ASCII_WORD = Regex("[a-z0-9_]+")
    private val CJK_RUN = Regex("[\\u4e00-\\u9fff]+")

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val tokens = ArrayList<String>()
        ASCII_WORD.findAll(lower).forEach { m ->
            val word = m.value
            if (word.length >= 2) tokens += word
        }
        CJK_RUN.findAll(lower).forEach { m ->
            val run = m.value
            run.forEach { ch -> tokens += ch.toString() }
            for (i in 0 until run.length - 1) tokens += run.substring(i, i + 2)
        }
        return tokens
    }
}

/**
 * BM25 索引：Okapi BM25（k1=1.2, b=0.75 为通用默认值）。
 *
 * 只对"客观文本"建索引（名称/描述/能力标签/约束），不索引连接状态或历史调用次数。
 */
internal class Bm25Index(
    descriptors: List<ToolDescriptor>,
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
) {
    private val docTokens: Map<String, List<String>> = descriptors.associate { it.toolId to Tokenizer.tokenize(it.searchableText) }
    private val docFrequency: Map<String, Int> = buildMap {
        docTokens.values.forEach { tokens ->
            tokens.toSet().forEach { token -> put(token, (get(token) ?: 0) + 1) }
        }
    }
    private val averageLength: Double =
        if (docTokens.isEmpty()) 0.0 else docTokens.values.sumOf { it.size }.toDouble() / docTokens.size
    private val documentCount: Int = docTokens.size

    fun search(queryTokens: List<String>, limit: Int): List<Pair<String, Double>> {
        if (queryTokens.isEmpty() || documentCount == 0) return emptyList()
        val unique = queryTokens.toSet()
        val scored = docTokens.map { (toolId, tokens) ->
            toolId to score(tokens, unique)
        }.filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        return if (limit > 0) scored.take(limit) else scored
    }

    private fun score(tokens: List<String>, query: Set<String>): Double {
        if (tokens.isEmpty()) return 0.0
        val lengthNorm = 1.0 - b + b * (tokens.size / averageLength.coerceAtLeast(1e-9))
        var total = 0.0
        query.forEach { term ->
            val tf = tokens.count { it == term }
            if (tf == 0) return@forEach
            val df = docFrequency[term] ?: 0
            val idf = kotlin.math.ln(1.0 + (documentCount - df + 0.5) / (df + 0.5))
            val denom = tf + k1 * lengthNorm
            if (denom > 0.0) total += idf * (tf * (k1 + 1.0)) / denom
        }
        return total
    }
}

/**
 * 多路召回器：关键词 / BM25 / 能力标签三路取并集。
 *
 * 召回阶段只看"是否可能相关"，不做取舍；过滤在前、排序在后，两阶段职责不混。
 */
internal class ToolRetriever(
    private val config: RoutingConfig = RoutingConfig(),
) {
    fun queryTokens(spec: SubTaskSpec): List<String> {
        val parts = buildList {
            add(spec.goal)
            addAll(spec.requiredOperations)
            addAll(spec.requiredDomains)
            addAll(spec.requiredInputTypes)
            addAll(spec.expectedOutputTypes)
            add(spec.taskClass)
        }
        return Tokenizer.tokenize(parts.joinToString(" "))
    }

    fun recall(eligible: List<ToolDescriptor>, spec: SubTaskSpec): List<ToolDescriptor> {
        if (eligible.isEmpty()) return emptyList()
        val tokens = queryTokens(spec)
        val tokenSet = tokens.toSet()
        val byId = eligible.associateBy { it.toolId }

        val keyword = eligible.filter { tool ->
            val docTokens = Tokenizer.tokenize(tool.searchableText)
            docTokens.any { it in tokenSet }
        }.map { it.toolId }

        val bm25 = Bm25Index(eligible).search(tokens, config.bm25CandidateLimit).map { it.first }

        val capability = eligible.filter { tool ->
            matchesAnyRequirement(tool, spec)
        }.map { it.toolId }

        // 三路并集；顺序稳定（先关键词、再 BM25、再能力），保证同输入同输出。
        val ordered = LinkedHashSet<String>()
        ordered.addAll(keyword)
        ordered.addAll(bm25)
        ordered.addAll(capability)
        return ordered.mapNotNull(byId::get)
    }

    private fun matchesAnyRequirement(tool: ToolDescriptor, spec: SubTaskSpec): Boolean {
        val caps = tool.capabilities
        return spec.requiredOperations.any { it in caps.operations } ||
            spec.requiredDomains.any { it in caps.domains } ||
            spec.requiredInputTypes.any { it in caps.inputTypes } ||
            spec.expectedOutputTypes.any { it in caps.outputTypes }
    }
}
