package com.suprah.drivertracker.data.positions

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.suprah.drivertracker.domain.Position
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec T-8 and T-12: readings saved on the phone. */
@RunWith(RobolectricTestRunner::class)
class RoomPositionStoreTest {
    private lateinit var database: PositionDatabase
    private lateinit var store: RoomPositionStore

    private fun at(ms: Long) = Position(lat = 40.0, lng = -111.0, measuredAtMs = ms)

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PositionDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = RoomPositionStore(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `readings come back oldest first and the newest is the current position`() = runBlocking<Unit> {
        store.add(listOf(at(3_000), at(1_000), at(2_000)))

        assertThat(store.oldest(2).map { it.position.measuredAtMs }).containsExactly(1_000L, 2_000L).inOrder()
        assertThat(store.newest()?.position?.measuredAtMs).isEqualTo(3_000L)
        assertThat(store.size()).isEqualTo(3)
    }

    @Test
    fun `sent readings are removed by id, so readings saved meanwhile stay`() = runBlocking<Unit> {
        store.add(listOf(at(1_000), at(2_000)))
        val sent = store.oldest(10)
        store.add(listOf(at(3_000)))

        store.remove(sent.map { it.id })

        assertThat(store.oldest(10).map { it.position.measuredAtMs }).containsExactly(3_000L)
    }

    @Test
    fun `at most 5000 readings are kept, dropping the oldest (T-8)`() = runBlocking<Unit> {
        store.add((1..RoomPositionStore.MAX_SAVED + 5).map { at(it * 1_000L) })

        assertThat(store.size()).isEqualTo(RoomPositionStore.MAX_SAVED)
        assertThat(store.oldest(1).single().position.measuredAtMs).isEqualTo(6_000L)
    }

    @Test
    fun `readings measured before the cutoff are dropped (T-12)`() = runBlocking<Unit> {
        store.add(listOf(at(1_000), at(5_000), at(9_000)))

        store.dropMeasuredBefore(5_000)

        assertThat(store.oldest(10).map { it.position.measuredAtMs }).containsExactly(5_000L, 9_000L).inOrder()
    }

    @Test
    fun `optional fields survive the round trip`() = runBlocking<Unit> {
        val full = Position(40.5, -111.5, 7_000, accuracy = 3.5f, speed = 12f, heading = 270f)
        store.add(listOf(full, at(8_000)))

        val saved = store.oldest(2).map { it.position }

        assertThat(saved[0]).isEqualTo(full)
        assertThat(saved[1].accuracy).isNull()
    }

    @Test
    fun `clear removes everything`() = runBlocking<Unit> {
        store.add(listOf(at(1_000), at(2_000)))

        store.clear()

        assertThat(store.size()).isEqualTo(0)
        assertThat(store.newest()).isNull()
    }
}
