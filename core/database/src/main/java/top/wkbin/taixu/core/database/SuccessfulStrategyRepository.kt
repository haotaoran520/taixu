package top.wkbin.taixu.core.database

import kotlin.math.sqrt

/** A recalled strategy row with its local relevance score. */
data class StrategyRecall(
    val id: String,
    val strategyLevel: Int,
    val originalPrompt: String,
    val rewrittenPrompt: String,
    val createdAt: Long,
    val score: Double,
)

/**
 * Reads/writes `successful_strategy` and turns rows into a bounded few-shot block.
 *
 * Budget rule: candidates are ranked by local keyword overlap, then the OLDEST rows are dropped
 * first whenever the rendered block would exceed the char budget (compression priority).
 */
class SuccessfulStrategyRepository(private val dao: SuccessfulStrategyDao) {

    suspend fun record(
        id: String,
        originalPrompt: String,
        strategyLevel: Int,
        rewrittenPrompt: String,
        responseDigest: String,
        createdAt: Long,
    ) {
        dao.insert(
            SuccessfulStrategyEntity(
                id = id,
                originalPrompt = originalPrompt,
                strategyLevel = strategyLevel,
                rewrittenPrompt = rewrittenPrompt,
                responseDigest = responseDigest,
                createdAt = createdAt,
            ),
        )
    }

    suspend fun count(): Int = dao.count()

    /** 设置页展示用：最近若干条成功策略（不参与召回打分）。 */
    suspend fun recentRows(limit: Int = 20): List<SuccessfulStrategyEntity> = dao.recent(limit)

    /** 清空经验回放库（仅作用于本进程的既有 Room 实例）。 */
    suspend fun clear(): Int = dao.deleteOlderThan(Long.MAX_VALUE)

    suspend fun recall(
        query: String,
        limit: Int = 10,
        maxChars: Int = 1600,
    ): List<StrategyRecall> {
        if (query.isBlank()) return emptyList()
        val queryTokens = tokenize(query)
        val scored = dao.recent(RECALL_SCAN_LIMIT)
            .map { row ->
                StrategyRecall(
                    id = row.id,
                    strategyLevel = row.strategyLevel,
                    originalPrompt = row.originalPrompt,
                    rewrittenPrompt = row.rewrittenPrompt,
                    createdAt = row.createdAt,
                    score = similarity(queryTokens, tokenize(row.originalPrompt)),
                )
            }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<StrategyRecall> { it.score }.thenByDescending { it.createdAt })
            .take(limit)
            .toMutableList()

        // Compression: drop the oldest rows first until the rendered block fits the budget.
        while (scored.size > 1 && renderedLength(scored) > maxChars) {
            val oldest = scored.minByOrNull { it.createdAt } ?: break
            scored.remove(oldest)
        }
        return scored
    }

    /** Renders the few-shot block; empty string when nothing was recalled. */
    fun renderFewShot(rows: List<StrategyRecall>): String {
        if (rows.isEmpty()) return ""
        return buildString {
            append(FEW_SHOT_HEADER)
            rows.forEach { row ->
                append("\n- 意图：")
                append(row.originalPrompt.replace('\n', ' ').take(MAX_PROMPT_CHARS))
                append(" → 生效策略：S")
                append(row.strategyLevel)
                append("；改写要点：")
                append(row.rewrittenPrompt.replace('\n', ' ').take(MAX_REWRITE_CHARS))
            }
        }
    }

    private fun renderedLength(rows: List<StrategyRecall>): Int = renderFewShot(rows).length

    companion object {
        const val FEW_SHOT_HEADER: String = "[经验回放] 以下为本机已验证成功的改写路径，优先直接沿用："
        const val RECALL_SCAN_LIMIT: Int = 200
        private const val MAX_PROMPT_CHARS: Int = 120
        private const val MAX_REWRITE_CHARS: Int = 80

        /** Local lightweight tokenizer: latin words + CJK bigrams (no model, no network). */
        fun tokenize(text: String): Set<String> {
            val lower = text.lowercase()
            val tokens = HashSet<String>()
            Regex("[a-z0-9_]{2,}").findAll(lower).forEach { tokens += it.value }
            val cjk = lower.filter { it.code in 0x4E00..0x9FFF }
            for (i in 0 until cjk.length - 1) tokens += cjk.substring(i, i + 2)
            return tokens
        }

        fun similarity(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val overlap = a.count { it in b }
            if (overlap == 0) return 0.0
            return overlap / sqrt(a.size.toDouble() * b.size.toDouble())
        }
    }
}
