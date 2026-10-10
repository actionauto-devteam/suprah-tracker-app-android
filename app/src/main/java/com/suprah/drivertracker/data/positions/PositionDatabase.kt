package com.suprah.drivertracker.data.positions

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.suprah.drivertracker.domain.Position
import com.suprah.drivertracker.domain.SavedPosition
import kotlinx.coroutines.flow.Flow

/** A GPS reading waiting to be sent (spec T-8). */
@Entity(tableName = "positions", indices = [Index("measuredAtMs")])
data class PositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val measuredAtMs: Long,
    val accuracy: Float?,
    val speed: Float?,
    val heading: Float?,
) {
    fun toSaved() = SavedPosition(id, Position(lat, lng, measuredAtMs, accuracy, speed, heading))

    companion object {
        fun from(position: Position) = PositionEntity(
            lat = position.lat,
            lng = position.lng,
            measuredAtMs = position.measuredAtMs,
            accuracy = position.accuracy,
            speed = position.speed,
            heading = position.heading,
        )
    }
}

@Dao
interface PositionDao {
    @Insert
    suspend fun insertAll(positions: List<PositionEntity>)

    @Query("SELECT * FROM positions ORDER BY measuredAtMs ASC, id ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<PositionEntity>

    @Query("SELECT * FROM positions ORDER BY measuredAtMs DESC, id DESC LIMIT 1")
    suspend fun newest(): PositionEntity?

    @Query("SELECT COUNT(*) FROM positions")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM positions")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM positions WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM positions WHERE measuredAtMs < :cutoffMs")
    suspend fun deleteMeasuredBefore(cutoffMs: Long)

    /** Keeps only the [keep] most recent readings. */
    @Query(
        "DELETE FROM positions WHERE id NOT IN " +
            "(SELECT id FROM positions ORDER BY measuredAtMs DESC, id DESC LIMIT :keep)",
    )
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM positions")
    suspend fun deleteAll()
}

@Database(entities = [PositionEntity::class], version = 1, exportSchema = true)
abstract class PositionDatabase : RoomDatabase() {
    abstract fun positionDao(): PositionDao

    companion object {
        const val NAME = "positions.db"
    }
}
