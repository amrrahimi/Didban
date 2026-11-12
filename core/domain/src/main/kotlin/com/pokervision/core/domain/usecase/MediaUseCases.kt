package com.pokervision.core.domain.usecase

import com.pokervision.core.common.result.AppError
import com.pokervision.core.common.result.AppResult
import com.pokervision.core.domain.repository.MediaRepository
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaOrigin
import com.pokervision.core.model.media.MediaType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveMediaUseCase @Inject constructor(private val repository: MediaRepository) {
    operator fun invoke(type: MediaType? = null): Flow<List<MediaAsset>> =
        if (type == null) repository.observeAll() else repository.observeByType(type)
}

class GetMediaAssetUseCase @Inject constructor(private val repository: MediaRepository) {
    suspend operator fun invoke(id: Long): AppResult<MediaAsset> =
        repository.getById(id)?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.NotFound("Media asset $id not found"))
}

class ImportMediaUseCase @Inject constructor(private val repository: MediaRepository) {
    suspend operator fun invoke(sourceUri: String, type: MediaType): AppResult<MediaAsset> {
        if (sourceUri.isBlank()) return AppResult.Failure(AppError.Validation("Empty source URI"))
        return repository.importFromUri(sourceUri, type)
    }
}

class CreateCaptureFileUseCase @Inject constructor(private val repository: MediaRepository) {
    suspend operator fun invoke(type: MediaType): AppResult<String> = repository.createCaptureFile(type)
}

/** Registers a photo/video the in-app camera wrote to private storage. */
class RegisterCapturedMediaUseCase @Inject constructor(private val repository: MediaRepository) {
    suspend operator fun invoke(path: String, type: MediaType): AppResult<MediaAsset> {
        val origin = when (type) {
            MediaType.IMAGE -> MediaOrigin.CAMERA_PHOTO
            MediaType.VIDEO -> MediaOrigin.CAMERA_VIDEO
        }
        return repository.registerLocalFile(path, type, origin)
    }
}

class DeleteMediaUseCase @Inject constructor(private val repository: MediaRepository) {
    suspend operator fun invoke(id: Long): AppResult<Unit> = repository.delete(id)
}
