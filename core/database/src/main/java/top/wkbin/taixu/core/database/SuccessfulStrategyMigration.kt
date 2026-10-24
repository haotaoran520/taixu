package top.wkbin.taixu.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v75: local strategy replay (few-shot) table, reused by the SSE refusal cascade. */
val MIGRATION_74_75 = object : Migration(74, 75) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `successful_strategy` (
                `id` TEXT NOT NULL,
                `original_prompt` TEXT NOT NULL,
                `strategy_level` INTEGER NOT NULL,
                `rewritten_prompt` TEXT NOT NULL,
                `response_digest` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_successful_strategy_created_at` ON `successful_strategy` (`created_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_successful_strategy_strategy_level` ON `successful_strategy` (`strategy_level`)")
    }
}
