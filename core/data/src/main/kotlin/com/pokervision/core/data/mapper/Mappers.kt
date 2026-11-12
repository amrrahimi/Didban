package com.pokervision.core.data.mapper

import com.pokervision.core.database.entity.GameEntity
import com.pokervision.core.database.entity.MediaAssetEntity
import com.pokervision.core.model.game.GameSource
import com.pokervision.core.model.game.PokerGame
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.model.media.MediaAsset
import com.pokervision.core.model.media.MediaOrigin
import com.pokervision.core.model.media.MediaType

internal inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
    enumValues<E>().firstOrNull { it.name == name }

/** Returns null for rows with unknown enum values (e.g. written by a newer app version). */
internal fun MediaAssetEntity.toModelOrNull(): MediaAsset? {
    val type = enumOrNull<MediaType>(type) ?: return null
    val origin = enumOrNull<MediaOrigin>(origin) ?: return null
    return MediaAsset(
        id = id,
        type = type,
        origin = origin,
        localPath = localPath,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMillis = durationMillis,
        createdAtMillis = createdAtMillis,
        thumbnailPath = thumbnailPath,
    )
}

internal fun MediaAsset.toEntity() = MediaAssetEntity(
    id = id,
    type = type.name,
    origin = origin.name,
    localPath = localPath,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMillis = durationMillis,
    createdAtMillis = createdAtMillis,
    thumbnailPath = thumbnailPath,
)

internal fun GameEntity.toModelOrNull(): PokerGame? {
    val variant = enumOrNull<PokerVariant>(variant) ?: return null
    val source = enumOrNull<GameSource>(source) ?: return null
    return PokerGame(
        id = id,
        variant = variant,
        source = source,
        playerCount = playerCount,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        mediaAssetId = mediaAssetId,
        notes = notes,
    )
}

internal fun PokerGame.toEntity() = GameEntity(
    id = id,
    variant = variant.name,
    source = source.name,
    playerCount = playerCount,
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    mediaAssetId = mediaAssetId,
    notes = notes,
)
