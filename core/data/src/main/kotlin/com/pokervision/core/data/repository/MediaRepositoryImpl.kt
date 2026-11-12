package com.pokervision.core.data.repository

import android.net.Uri
import com.pokervision.core.common.logging.AppLogger
import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.data.di.IoDispatcher
import com.pokervision.core.data.mapper.toEntity
import com.pokervision.core.data.mapper.toModelOrNull
import com.pokervision.core.data.media.MediaFileStore
import com.pokervision.core.database.dao.MediaAssetDao
import com.pokervision.core.domain.media.MediaTypePolicy
import com.pokervision.core.domain.repository.MediaRepository
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaOrigin
import com.pokervision.core.model.media.MediaType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val dao: MediaAssetDao,
    private val store: MediaFileStore,
    private val logger: AppLogger,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MediaRepository {

    override fun observeAll(): Flow<List<MediaAsset>> = dao.observeAll().map { it.mapNotNull { e -> e.toModelOrNull() } }

    override fun observeByType(type: MediaType): Flow<List<MediaAsset>> =
        dao.observeByType(type.name).map { it.mapNotNull { e -> e.toModelOrNull() } }

    override suspend fun getById(id: Long): MediaAsset? = withContext(io) { dao.getById(id)?.toModelOrNull() }

    override suspend fun importFromUri(sourceUri: String, expectedType: MediaType): AppResult<MediaAsset> =
        withContext(io) {
            val uri = Uri.parse(sourceUri)
            val mime = store.mimeTypeOf(uri)
            if (!MediaTypePolicy.isSupported(mime, expectedType)) {
                return@withContext AppResult.Failure(
                    AppError.Validation("Unsupported ${expectedType.name.lowercase()} type: $mime"),
                )
            }
            val target = store.newFile(expectedType, MediaTypePolicy.extensionFor(mime!!))
            try {
                store.copyFromUri(uri, target)
            } catch (e: IOException) {
                logger.e(TAG, "Import copy failed", e)
                return@withContext AppResult.Failure(AppError.Io("Could not copy the selected file", e))
            }
            val displayName = store.displayNameOf(uri) ?: target.name
            insertForFile(target, expectedType, MediaOrigin.USER_IMPORT, mime, displayName)
        }

    override suspend fun createCaptureFile(type: MediaType): AppResult<String> = withContext(io) {
        val ext = if (type == MediaType.IMAGE) "jpg" else "mp4"
        try {
            val file = store.newFile(type, ext)
            AppResult.Success(file.absolutePath)
        } catch (e: SecurityException) {
            AppResult.Failure(AppError.Io("Cannot create capture file", e))
        }
    }

    override suspend fun registerLocalFile(path: String, type: MediaType, origin: MediaOrigin): AppResult<MediaAsset> =
        withContext(io) {
            val file = File(path)
            if (!store.isManaged(path) || !file.isFile) {
                return@withContext AppResult.Failure(AppError.Validation("Not a managed media file: $path"))
            }
            val mime = MediaTypePolicy.mimeTypeForExtension(file.extension)
                ?: return@withContext AppResult.Failure(AppError.Validation("Unknown extension: ${file.extension}"))
            insertForFile(file, type, origin, mime, file.name)
        }

    private suspend fun insertForFile(
        file: File,
        type: MediaType,
        origin: MediaOrigin,
        mime: String,
        displayName: String,
    ): AppResult<MediaAsset> {
        val meta = store.readMetadata(file, type)
        val thumbnail = if (type == MediaType.VIDEO) store.createVideoThumbnail(file) else null
        val asset = MediaAsset(
            type = type,
            origin = origin,
            localPath = file.absolutePath,
            displayName = displayName,
            mimeType = mime,
            sizeBytes = file.length(),
            width = meta.width,
            height = meta.height,
            durationMillis = meta.durationMillis,
            createdAtMillis = System.currentTimeMillis(),
            thumbnailPath = thumbnail,
        )
        return try {
            val id = dao.insert(asset.toEntity())
            logger.i(TAG, "Stored ${type.name} id=$id origin=${origin.name} bytes=${asset.sizeBytes}")
            AppResult.Success(asset.copy(id = id))
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            file.delete()
            thumbnail?.let { store.delete(it) }
            logger.e(TAG, "DB insert failed; file removed", e)
            AppResult.Failure(AppError.Io("Could not save media record", e))
        }
    }

    override suspend fun delete(id: Long): AppResult<Unit> = withContext(io) {
        val entity = dao.getById(id) ?: return@withContext AppResult.Failure(AppError.NotFound("Media $id"))
        dao.deleteById(id)
        if (!store.delete(entity.localPath)) logger.w(TAG, "File not deleted: ${entity.localPath}")
        entity.thumbnailPath?.let { store.delete(it) }
        AppResult.Success(Unit)
    }

    override suspend fun deleteAll(): AppResult<Unit> = withContext(io) {
        dao.deleteAll()
        if (store.deleteAll()) AppResult.Success(Unit) else AppResult.Failure(AppError.Io("Some media files could not be deleted"))
    }

    override suspend fun storageUsageBytes(): Long = withContext(io) { store.usageBytes() }

    private companion object {
        const val TAG = "MediaRepository"
    }
}
