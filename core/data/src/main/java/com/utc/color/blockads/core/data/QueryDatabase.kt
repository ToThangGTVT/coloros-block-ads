package com.utc.color.blockads.core.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "daily_stats")
data class DailyStats(@PrimaryKey val day: Long, val total: Long = 0, val blocked: Long = 0)

data class TopDomain(val domain: String, val count: Long, val totalBlocked: Long = count)

data class AppQueryStats(val uid: Int?, val total: Long, val blocked: Long)

// Persist metadata only. Never store packet payloads or HTTP bodies.
@Entity(
    tableName = "queries",
    indices = [Index(value = ["timestamp", "id"]), Index(value = ["uid", "timestamp", "id"])],
)
data class QueryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val domain: String,
    val uid: Int?,
    val blocked: Boolean,
    val matchedRule: String?,
    @androidx.room.ColumnInfo(defaultValue = "'dns'") val protocol: String = "dns",
)

@Dao
interface QueryDao {
    @Query("SELECT * FROM daily_stats WHERE day >= :since ORDER BY day")
    fun observeHistory(since: Long): Flow<List<DailyStats>>

    @Query("SELECT * FROM daily_stats WHERE day = :day")
    fun observeStats(day: Long): Flow<DailyStats?>

    @Query(
        "INSERT INTO daily_stats(day, total, blocked) VALUES(:day, :total, :blocked) ON CONFLICT(day) DO UPDATE SET total = total + :total, blocked = blocked + :blocked"
    )
    suspend fun addStats(day: Long, total: Long, blocked: Long)

    @Query(
        """SELECT domain, COUNT(*) AS count,
            (SELECT COUNT(*) FROM queries WHERE timestamp >= :since AND blocked = 1) AS totalBlocked
            FROM queries WHERE timestamp >= :since AND blocked = 1
            GROUP BY domain ORDER BY count DESC, domain LIMIT :limit"""
    )
    fun topDomains(since: Long, limit: Int): Flow<List<TopDomain>>

    @Query(
        """SELECT uid, COUNT(*) AS total,
            SUM(CASE WHEN blocked = 1 THEN 1 ELSE 0 END) AS blocked
            FROM queries WHERE timestamp >= :since GROUP BY uid"""
    )
    fun observeAppStats(since: Long): Flow<List<AppQueryStats>>

    @Query("DELETE FROM queries") suspend fun clearLogs()

    @Query(
        "SELECT * FROM queries WHERE (:search = '' OR domain LIKE '%' || :search || '%') AND (:status = -1 OR blocked = :status) ORDER BY timestamp DESC, id DESC"
    )
    fun pagingSource(
        search: String = "",
        status: Int = -1,
    ): PagingSource<Int, QueryEntity>

    @Query("SELECT * FROM queries WHERE uid IS :uid AND (:search = '' OR domain LIKE '%' || :search || '%') AND (:status = -1 OR blocked = :status) ORDER BY timestamp DESC, id DESC")
    fun appPagingSource(uid: Int?, status: Int, search: String = ""): PagingSource<Int, QueryEntity>

    @Insert suspend fun insertBatch(events: List<QueryEntity>): List<Long>

    @Query("SELECT * FROM queries WHERE domain = :domain ORDER BY id DESC LIMIT 1")
    suspend fun latest(domain: String): QueryEntity?

    @Query("DELETE FROM queries WHERE timestamp < :before") suspend fun deleteBefore(before: Long)
}

@Database(entities = [QueryEntity::class, DailyStats::class], version = 4, exportSchema = true)
abstract class QueryDatabase : RoomDatabase() {
    abstract fun queries(): QueryDao

    companion object {
        val Migration3To4 =
            object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_queries_uid_timestamp_id ON queries (uid, timestamp, id)")
                }
            }
        val Migration2To3 =
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS daily_stats (day INTEGER NOT NULL, total INTEGER NOT NULL, blocked INTEGER NOT NULL, PRIMARY KEY(day))"
                    )
                }
            }
        val Migration1To2 =
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE queries ADD COLUMN protocol TEXT NOT NULL DEFAULT 'dns'"
                    )
                }
            }
    }
}
