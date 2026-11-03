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
)

@Dao
interface QueryDao {
    @Query("SELECT * FROM queries ORDER BY timestamp DESC, id DESC")
    fun pagingSource(): PagingSource<Int, QueryEntity>

    @Insert suspend fun insertBatch(events: List<QueryEntity>)

    @Query("DELETE FROM queries WHERE timestamp < :before") suspend fun deleteBefore(before: Long)
}

@Database(entities = [QueryEntity::class], version = 1, exportSchema = true)
abstract class QueryDatabase : RoomDatabase() {
    abstract fun queries(): QueryDao
}
