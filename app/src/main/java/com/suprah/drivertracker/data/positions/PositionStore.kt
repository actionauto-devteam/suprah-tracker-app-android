package com.suprah.drivertracker.data.positions

import androidx.room.withTransaction
import com.suprah.drivertracker.domain.Position
import com.suprah.drivertracker.domain.SavedPosition
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Readings saved on the phone until Suprah has them (spec T-8). They survive no signal, the app being
 * closed and a phone restart.
 */
interface PositionStore {
    val count: Flow<Int>

    suspend fun add(positions: List<Position>)

    /** The oldest saved readings, oldest first. */
    suspend fun oldest(limit: Int): List<SavedPosition>

    /** The most recent reading: the driver's current position. */
    suspend fun newest(): SavedPosition?

    suspend fun remove(ids: Collection<Long>)

    /** Drops readings measured before [cutoffMs] (Suprah would refuse them anyway, spec T-12). */
    suspend fun dropMeasuredBefore(cutoffMs: Long)

    suspend fun size(): Int

    suspend fun clear()
}

class RoomPositionStore @Inject constructor(
    private val database: PositionDatabase,
) : PositionStore {
    private val dao = database.positionDao()

    override val count: Flow<Int> = dao.observeCount()

    override suspend fun add(positions: List<Position>) {
        if (positions.isEmpty()) return
        database.withTransaction {
            dao.insertAll(positions.map(PositionEntity::from))
            dao.keepNewest(MAX_SAVED)
        }
    }

    override suspend fun oldest(limit: Int): List<SavedPosition> = dao.oldest(limit).map { it.toSaved() }

    override suspend fun newest(): SavedPosition? = dao.newest()?.toSaved()

    override suspend fun remove(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.delete(ids.toList())
    }

    override suspend fun dropMeasuredBefore(cutoffMs: Long) = dao.deleteMeasuredBefore(cutoffMs)

    override suspend fun size(): Int = dao.count()

    override suspend fun clear() = dao.deleteAll()

    companion object {
        /** About 20 hours of readings at one every 15 seconds; beyond that the oldest go first (spec T-8). */
        const val MAX_SAVED = 5_000
    }
}
