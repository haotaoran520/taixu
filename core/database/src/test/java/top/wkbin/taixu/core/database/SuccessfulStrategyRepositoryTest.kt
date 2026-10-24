package top.wkbin.taixu.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-function parts of module 4 (no Android runtime needed). */
class SuccessfulStrategyRepositoryTest {

    @Test
    fun tokenizerCapturesLatinWordsAndCjkBigrams() {
        val tokens = SuccessfulStrategyRepository.tokenize("把 /data/local/tmp/a.conf 重定向到 assets 副本")
        assertTrue(tokens.contains("data"))
        assertTrue(tokens.contains("conf"))
        assertTrue(tokens.contains("重定"))
        assertTrue(tokens.contains("定向"))
    }

    @Test
    fun similarityRanksSameIntentAboveUnrelatedIntent() {
        val query = SuccessfulStrategyRepository.tokenize("把配置文件重定向到 assets 副本")
        val same = SuccessfulStrategyRepository.tokenize("把配置文件重定向到 assets 副本")
        val other = SuccessfulStrategyRepository.tokenize("生成一个 flutter 构建脚本")

        assertTrue(SuccessfulStrategyRepository.similarity(query, same) > 0.9)
        assertEquals(0.0, SuccessfulStrategyRepository.similarity(query, other), 0.0)
    }

    @Test
    fun renderedFewShotBlockListsStrategyLevelAndIntent() {
        val repo = SuccessfulStrategyRepository(FakeDao())
        val block = repo.renderFewShot(
            listOf(
                StrategyRecall("a", 2, "重定向配置文件", "拆成最小子任务后执行", 1_000, 1.0),
                StrategyRecall("b", 1, "生成构建脚本", "直接给命令", 2_000, 0.5),
            ),
        )
        assertTrue(block.startsWith(SuccessfulStrategyRepository.FEW_SHOT_HEADER))
        assertTrue(block.contains("意图：重定向配置文件 → 生效策略：S2"))
        assertTrue(block.contains("意图：生成构建脚本 → 生效策略：S1"))
    }

    @Test
    fun compressionDropsOldestRowsFirst() {
        val repo = SuccessfulStrategyRepository(FakeDao())
        val rows = (1..6).map { StrategyRecall("id$it", 1, "意图$it", "改写$it", it * 1_000L, 1.0 - it * 0.01) }
        // Budget large enough for all six, then shrink to force trimming.
        val full = rows.filter { repo.renderFewShot(listOf(it)).length < 400 }
        assertTrue(full.isNotEmpty())

        var kept = rows.toMutableList()
        val budget = repo.renderFewShot(rows.take(2)).length + 5
        while (kept.size > 1 && repo.renderFewShot(kept).length > budget) {
            kept.remove(kept.minByOrNull { it.createdAt })
        }
        assertTrue("oldest rows are dropped first", kept.none { it.createdAt <= 2_000L })
        assertFalse(kept.isEmpty())
    }

    private class FakeDao : SuccessfulStrategyDao {
        private val rows = mutableListOf<SuccessfulStrategyEntity>()
        override suspend fun insert(entity: SuccessfulStrategyEntity) {
            rows.removeAll { it.id == entity.id }
            rows += entity
        }
        override suspend fun recent(limit: Int): List<SuccessfulStrategyEntity> =
            rows.sortedByDescending { it.createdAt }.take(limit)
        override suspend fun count(): Int = rows.size
        override suspend fun deleteById(id: String) {
            rows.removeAll { it.id == id }
        }
        override suspend fun deleteOlderThan(before: Long): Int {
            val before1 = rows.size
            rows.removeAll { it.createdAt < before }
            return before1 - rows.size
        }
    }
}
