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

// Persist metadata only. Never store packet payloads or HTTP bodies.
@Entity(tableName = "queries", indices = [Index(value = ["timestamp", "id"])])
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
    @Query(
        "SELECT * FROM queries WHERE (:search = '' OR domain LIKE '%' || :search || '%') AND (:status = -1 OR blocked = :status) AND (:uid = -1 OR uid = :uid) ORDER BY timestamp DESC, id DESC"
    )
    fun pagingSource(
        search: String = "",
        status: Int = -1,
        uid: Int = -1,
    ): PagingSource<Int, QueryEntity>

    @Insert suspend fun insertBatch(events: List<QueryEntity>)

    @Query("SELECT * FROM queries WHERE domain = :domain ORDER BY id DESC LIMIT 1")
    suspend fun latest(domain: String): QueryEntity?

    @Query("DELETE FROM queries WHERE timestamp < :before") suspend fun deleteBefore(before: Long)
}

@Database(entities = [QueryEntity::class], version = 2, exportSchema = true)
abstract class QueryDatabase : RoomDatabase() {
    abstract fun queries(): QueryDao

    companion object {
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
