package com.pokervision.core.domain.usecase

import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.media.MediaTypePolicy
import com.pokervision.core.model.game.GameSource
import com.pokervision.core.model.game.PokerGame
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.media.MediaOrigin
import com.pokervision.core.model.media.MediaType
import com.pokervision.core.model.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UseCasesTest {

    private val game = PokerGame("g1", PokerVariant.OMAHA, GameSource.VIDEO, 6, startedAtMillis = 0)

    @Test
    fun `dashboard aggregates counts and recent media newest first`() = runBlocking {
        val mediaRepo = FakeMediaRepository(
            listOf(
                media(1, MediaType.IMAGE, createdAt = 10),
                media(2, MediaType.VIDEO, createdAt = 30),
                media(3, MediaType.IMAGE, createdAt = 20),
            ),
        )
        val useCase = ObserveDashboardUseCase(
            mediaRepo,
            FakeGameRepository(listOf(game), hands = 7),
            FakeSettingsRepository(AppSettings(defaultVariant = PokerVariant.SHORT_DECK)),
        )
        val summary = useCase(recentLimit = 2).first()
        assertEquals(2, summary.imageCount)
        assertEquals(1, summary.videoCount)
        assertEquals(1, summary.gameCount)
        assertEquals(7, summary.handCount)
        assertEquals(PokerVariant.SHORT_DECK, summary.defaultVariant)
        assertEquals(listOf(2L, 3L), summary.recentMedia.map { it.id })
    }

    @Test
    fun `import rejects blank uri without touching repository`() = runBlocking {
        val repo = FakeMediaRepository()
        val result = ImportMediaUseCase(repo)("  ", MediaType.IMAGE)
        assertTrue(result.errorOrNull() is AppError.Validation)
        assertEquals(null, repo.lastImport)
    }

    @Test
    fun `import forwards to repository`() = runBlocking {
        val repo = FakeMediaRepository()
        val result = ImportMediaUseCase(repo)("content://x/1", MediaType.VIDEO)
        assertTrue(result.isSuccess)
        assertEquals("content://x/1" to MediaType.VIDEO, repo.lastImport)
    }

    @Test
    fun `captured media gets camera origin`() = runBlocking {
        val repo = FakeMediaRepository()
        val photo = RegisterCapturedMediaUseCase(repo)("/p/a.jpg", MediaType.IMAGE).getOrNull()!!
        val video = RegisterCapturedMediaUseCase(repo)("/p/b.mp4", MediaType.VIDEO).getOrNull()!!
        assertEquals(MediaOrigin.CAMERA_PHOTO, photo.origin)
        assertEquals(MediaOrigin.CAMERA_VIDEO, video.origin)
    }

    @Test
    fun `get missing media is not found`() = runBlocking {
        assertTrue(GetMediaAssetUseCase(FakeMediaRepository())(99).errorOrNull() is AppError.NotFound)
    }

    @Test
    fun `clear all wipes everything and resets settings`() = runBlocking {
        val mediaRepo = FakeMediaRepository(listOf(media(1, MediaType.IMAGE)))
        val gameRepo = FakeGameRepository(listOf(game), hands = 3)
        val settingsRepo = FakeSettingsRepository(AppSettings(analysisFps = 10))
        val result = ClearAllLocalDataUseCase(mediaRepo, gameRepo, settingsRepo)()
        assertEquals(AppResult.Success(Unit), result)
        assertTrue(mediaRepo.items.value.isEmpty())
        assertTrue(gameRepo.games.value.isEmpty())
        assertEquals(AppSettings(), settingsRepo.current)
    }

    @Test
    fun `clear all continues after a failure and reports it`() = runBlocking {
        val mediaRepo = FakeMediaRepository(listOf(media(1, MediaType.IMAGE))).apply { failDeleteAll = true }
        val gameRepo = FakeGameRepository(listOf(game))
        val result = ClearAllLocalDataUseCase(mediaRepo, gameRepo, FakeSettingsRepository())()
        assertTrue(result.errorOrNull() is AppError.Io)
        assertTrue("games still cleared", gameRepo.games.value.isEmpty())
    }

    @Test
    fun `mime policy`() {
        assertTrue(MediaTypePolicy.isSupported("image/jpeg", MediaType.IMAGE))
        assertTrue(MediaTypePolicy.isSupported("VIDEO/MP4", MediaType.VIDEO))
        assertTrue(MediaTypePolicy.isSupported("video/mp4; codecs=avc1", MediaType.VIDEO))
        assertFalse(MediaTypePolicy.isSupported("video/mp4", MediaType.IMAGE))
        assertFalse(MediaTypePolicy.isSupported("application/pdf", MediaType.IMAGE))
        assertFalse(MediaTypePolicy.isSupported(null, MediaType.IMAGE))
        assertEquals("jpg", MediaTypePolicy.extensionFor("image/jpeg"))
        assertEquals("video/mp4", MediaTypePolicy.mimeTypeForExtension("MP4"))
    }
}
