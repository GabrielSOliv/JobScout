package com.jobscout.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val payload: String,
    val saved: Boolean = false,
    val viewed: Boolean = false,
    val dismissed: Boolean = false,
    val status: String = "NOT_APPLIED",
    val notes: String = "",
    val statusAt: Long? = null,
    val notified: Boolean = false,
    val stale: Boolean = false,
)

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY rowid DESC") fun observe(): Flow<List<JobEntity>>
    @Query("SELECT * FROM jobs") suspend fun all(): List<JobEntity>
    @Query("SELECT * FROM jobs WHERE id = :id") suspend fun get(id: String): JobEntity?
    @Upsert suspend fun upsert(jobs: List<JobEntity>)
    @Upsert suspend fun upsert(job: JobEntity)
    @Query("UPDATE jobs SET saved = COALESCE(:saved, saved), viewed = COALESCE(:viewed, viewed), dismissed = COALESCE(:dismissed, dismissed), status = COALESCE(:status, status), notes = COALESCE(:notes, notes), statusAt = COALESCE(:statusAt, statusAt) WHERE id = :id")
    suspend fun track(id: String, saved: Boolean?, viewed: Boolean?, dismissed: Boolean?, status: String?, notes: String?, statusAt: Long?)
    @Query("UPDATE jobs SET notified = 1 WHERE id IN (:ids)") suspend fun notified(ids: List<String>)
    @Query("DELETE FROM jobs") suspend fun clear()
    @Query("DELETE FROM jobs WHERE saved = 0") suspend fun clearUnsaved(): Int
}

@Database(entities = [JobEntity::class], version = 1, exportSchema = true)
abstract class ScoutDatabase : RoomDatabase() { abstract fun jobs(): JobDao }
