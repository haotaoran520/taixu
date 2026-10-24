package top.wkbin.taixu.harness

import top.wkbin.taixu.core.database.SuccessfulStrategyRepository
import top.wkbin.taixu.core.database.StrategyRecall

/**
 * Local experience replay (module 4): reuses the existing Room instance via
 * [SuccessfulStrategyRepository] — no second database, no new store.
 *
 * The recalled rows become a few-shot block appended to the TAIL of the system prompt, so the
 * stable prefix (and its prompt-cache hit) is preserved. The block is bounded by [maxChars]; when
 * the context policy needs room, the oldest few-shot rows are dropped first (handled in the repo).
 */
class StrategyReplay(
    private val repository: SuccessfulStrategyRepository,
    private val limit: Int = 10,
    private val maxChars: Int = 1600,
) {

    /** Persists a refusal->success escalation captured by [RefusalCascade]. */
    suspend fun record(observation: StrategyObservation) {
        repository.record(
            id = buildString {
                append(observation.createdAt)
                append('-')
                append(observation.strategyCode)
                append('-')
                append(observation.responseDigest.take(12))
            },
            originalPrompt = observation.originalPrompt,
            strategyLevel = observation.strategyLevel,
            rewrittenPrompt = observation.rewrittenPrompt,
            responseDigest = observation.responseDigest,
            createdAt = observation.createdAt,
        )
    }

    suspend fun recall(query: String): List<StrategyRecall> =
        repository.recall(query = query, limit = limit, maxChars = maxChars)

    /** Appends the few-shot block to the system message tail; returns the transcript unchanged when empty. */
    suspend fun inject(messages: List<ApiMessage>, query: String): List<ApiMessage> {
        if (messages.isEmpty() || query.isBlank()) return messages
        val block = repository.renderFewShot(recall(query))
        if (block.isBlank()) return messages
        val systemIndex = messages.indexOfFirst { it.role == ROLE_SYSTEM }
        if (systemIndex < 0) return messages
        val system = messages[systemIndex]
        val merged = buildString {
            append(system.content.orEmpty())
            if (isNotEmpty()) append("\n\n")
            append(block)
        }
        return messages.toMutableList().also { it[systemIndex] = system.copy(content = merged) }
    }

    companion object {
        const val ROLE_SYSTEM: String = "system"
    }
}
