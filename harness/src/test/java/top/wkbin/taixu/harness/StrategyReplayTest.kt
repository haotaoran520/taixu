package top.wkbin.taixu.harness

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.wkbin.taixu.core.database.SuccessfulStrategyDao
import top.wkbin.taixu.core.database.SuccessfulStrategyEntity
import top.wkbin.taixu.core.database.SuccessfulStrategyRepository

class StrategyReplayTest {

    private class FakeDao : SuccessfulStrategyDao {
        val rows = mutableListOf<SuccessfulStrategyEntity>()
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

    private val transcript = listOf(
        ApiMessage(role = "system", content = "你是 TaiXu 的本地 Agent。"),
        ApiMessage(role = "user", content = "把配置文件重定向到 assets 副本"),
    )

    private fun strategy(level: Int, prompt: String, digest: String, at: Long) = StrategyObservation(
        originalPrompt = prompt,
        strategyLevel = level,
        strategyCode = "S$level",
        rewrittenPrompt = "改写要点$level",
        responseDigest = digest,
        createdAt = at,
    )

    // ---------- ACCEPTANCE: two historical rows show up in the second identical-intent request ----------

    @Test
    fun acceptance_twoHistoricalStrategiesAppearInSystemPromptForSameIntent() = runBlocking {
        val dao = FakeDao()
        val replay = StrategyReplay(SuccessfulStrategyRepository(dao))
        replay.record(strategy(2, "把配置文件重定向到 assets 副本", "d1".repeat(32), 1_000))
        replay.record(strategy(1, "把配置文件重定向到 assets 副本（旧）", "d2".repeat(32), 2_000))
        assertEquals(2, dao.count())

        val injected = replay.inject(transcript, query = "把配置文件重定向到 assets 副本")
        val system = injected.first { it.role == "system" }.content.orEmpty()

        assertTrue(system.startsWith("你是 TaiXu 的本地 Agent。"))
        assertTrue("few-shot block is appended at the tail", system.contains(SuccessfulStrategyRepository.FEW_SHOT_HEADER))
        assertTrue(system.contains("生效策略：S1"))
        assertTrue(system.contains("生效策略：S2"))
        assertEquals("only the system message changes", transcript[1], injected[1])
    }

    @Test
    fun unrelatedIntentDoesNotInjectAnything() = runBlocking {
        val dao = FakeDao()
        val replay = StrategyReplay(SuccessfulStrategyRepository(dao))
        replay.record(strategy(2, "把配置文件重定向到 assets 副本", "d1".repeat(32), 1_000))

        val injected = replay.inject(transcript, query = "帮我写一首诗")
        assertEquals(transcript, injected)
        assertFalse(injected.first().content!!.contains("经验回放"))
    }

    // ---------- ACCEPTANCE: compression trims the oldest few-shot first ----------

    @Test
    fun acceptance_compressionDropsOldestFewShotFirst() = runBlocking {
        val dao = FakeDao()
        // 10 rows with tight char budget: the oldest ones must disappear, newest must survive.
        val repo = SuccessfulStrategyRepository(dao)
        repeat(10) { i ->
            dao.insert(
                SuccessfulStrategyEntity(
                    id = "row$i",
                    originalPrompt = "把配置文件重定向到 assets 副本 序号$i",
                    strategyLevel = 1,
                    rewrittenPrompt = "改写$i",
                    responseDigest = "x",
                    createdAt = 1_000L + i,
                ),
            )
        }
        val tight = StrategyReplay(repo, limit = 10, maxChars = 260)
        val rows = tight.recall("把配置文件重定向到 assets 副本")
        assertTrue("block stays inside the char budget", repo.renderFewShot(rows).length <= 300)
        assertTrue("newest row survives", rows.any { it.createdAt == 1_009L })
        assertTrue("oldest rows are trimmed first", rows.none { it.createdAt <= 1_004L })
    }

    @Test
    fun emptyDatabaseLeavesTranscriptUntouched() = runBlocking {
        val replay = StrategyReplay(SuccessfulStrategyRepository(FakeDao()))
        assertEquals(transcript, replay.inject(transcript, "把配置文件重定向到 assets 副本"))
    }

    @Test
    fun recordReusesExistingRoomTableThroughRepository() = runBlocking {
        val dao = FakeDao()
        val replay = StrategyReplay(SuccessfulStrategyRepository(dao))
        replay.record(strategy(3, "原始意图", "ab".repeat(32), 42))
        assertEquals(1, dao.count())
        assertEquals(3, dao.rows.single().strategyLevel)
        assertEquals("ab".repeat(32), dao.rows.single().responseDigest)
    }
}
