package org.familycircle.android.data.queue

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Abstraction over the persistent upload queue (Room in the app, a fake in tests). */
interface LocationQueue {
    /** Oldest points first (by fix time), at most [limit]. */
    suspend fun oldest(limit: Int): List<PendingLocation>

    suspend fun delete(ids: Collection<String>)

    suspend fun count(): Int
}

@Dao
interface PendingLocationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: PendingLocation)

    @Query("SELECT * FROM pending_locations ORDER BY recorded_at_ms ASC, created_at_ms ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingLocation>

    @Query("DELETE FROM pending_locations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM pending_locations")
    suspend fun count(): Int

    @Query("SELECT * FROM pending_locations ORDER BY recorded_at_ms DESC LIMIT 1")
    suspend fun newest(): PendingLocation?

    @Query("SELECT COUNT(*) FROM pending_locations")
    fun countFlow(): Flow<Int>

    /** The server rejects points older than 7 days anyway; keeps the queue bounded while offline. */
    @Query("DELETE FROM pending_locations WHERE recorded_at_ms < :cutoffMs")
    suspend fun deleteRecordedBefore(cutoffMs: Long): Int

    @Query("DELETE FROM pending_locations")
    suspend fun clear()
}

@Database(entities = [PendingLocation::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingLocations(): PendingLocationDao
}

class RoomLocationQueue(private val dao: PendingLocationDao) : LocationQueue {
    override suspend fun oldest(limit: Int): List<PendingLocation> = dao.oldest(limit)

    override suspend fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        // SQLite limits bound variables (999 on old versions); our batches are ≤ 200 but stay safe.
        ids.chunked(500).forEach { dao.deleteByIds(it) }
    }

    override suspend fun count(): Int = dao.count()
}
