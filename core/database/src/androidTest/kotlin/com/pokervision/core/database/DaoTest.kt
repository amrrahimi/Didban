package com.pokervision.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pokervision.core.database.entity.GameEntity
import com.pokervision.core.database.entity.HandHistoryEntity
import com.pokervision.core.database.entity.MediaAssetEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {

    private lateinit var db: PokerVisionDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PokerVisionDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun media(type: String, createdAt: Long, size: Long = 10) = MediaAssetEntity(
        type = type, origin = "USER_IMPORT", localPath = "/x/$createdAt", displayName = "f$createdAt",
        mimeType = "image/jpeg", sizeBytes = size, width = 1, height = 1, durationMillis = null,
        createdAtMillis = createdAt,
    )

    @Test
    fun mediaInsertObserveOrderAndSize() = runTest {
        val dao = db.mediaAssetDao()
        dao.insert(media("IMAGE", 1, size = 100))
        dao.insert(media("VIDEO", 3, size = 50))
        dao.insert(media("IMAGE", 2, size = 25))

        assertEquals(listOf(3L, 2L, 1L), dao.observeAll().first().map { it.createdAtMillis })
        assertEquals(2, dao.observeByType("IMAGE").first().size)
        assertEquals(175L, dao.totalSizeBytes())
    }

    @Test
    fun deletingMediaNullsGameReference() = runTest {
        val mediaId = db.mediaAssetDao().insert(media("VIDEO", 1))
        db.gameDao().upsert(GameEntity("g1", "TEXAS_HOLDEM", "VIDEO", 6, 0, null, mediaId, ""))
        db.mediaAssetDao().deleteById(mediaId)
        assertNull(db.gameDao().observeAll().first().single().mediaAssetId)
    }

    @Test
    fun deletingGameCascadesToHands() = runTest {
        db.gameDao().upsert(GameEntity("g1", "TEXAS_HOLDEM", "MANUAL", 6, 0, null, null, ""))
        db.handHistoryDao().insert(HandHistoryEntity(0, "g1", 1, "TEXAS_HOLDEM", "AsKd7h", "", null, "", "", 0))
        assertEquals(1, db.handHistoryDao().observeCount().first())
        db.gameDao().deleteById("g1")
        assertEquals(0, db.handHistoryDao().observeCount().first())
    }

    @Test
    fun emptyTableSizeIsZero() = runTest {
        assertEquals(0L, db.mediaAssetDao().totalSizeBytes())
    }
}
