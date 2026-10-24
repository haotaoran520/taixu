package top.wkbin.taixu.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One refusal->success escalation captured by the SSE refusal cascade (S0..S4 ladder).
 * Replayed later as a few-shot block so the same intent does not have to climb the ladder again.
 */
@Entity(
    tableName = "successful_strategy",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["strategy_level"]),
    ],
)
data class SuccessfulStrategyEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "original_prompt")
    val originalPrompt: String,
    @ColumnInfo(name = "strategy_level")
    val strategyLevel: Int,
    @ColumnInfo(name = "rewritten_prompt")
    val rewrittenPrompt: String,
    @ColumnInfo(name = "response_digest")
    val responseDigest: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Dao
interface SuccessfulStrategyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SuccessfulStrategyEntity)

    @Query("SELECT * FROM successful_strategy ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SuccessfulStrategyEntity>

    @Query("SELECT COUNT(*) FROM successful_strategy")
    suspend fun count(): Int

    @Query("DELETE FROM successful_strategy WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM successful_strategy WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long): Int
}
