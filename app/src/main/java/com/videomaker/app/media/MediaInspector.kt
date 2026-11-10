package com.videomaker.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import com.videomaker.app.model.AudioLayer
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.Ids
import com.videomaker.app.model.MediaClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads size, length, orientation and thumbnails of picked media. */
class MediaInspector(private val context: Context) {

    suspend fun inspectVisual(uri: Uri): MediaClip? = withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri) ?: return@withContext null
        val name = displayName(uri)
        when {
            mimeType.startsWith("image/") -> inspectImage(uri, mimeType, name)
            mimeType.startsWith("video/") -> inspectVideo(uri, mimeType, name)
            else -> null
        }
    }

    suspend fun inspectAudio(uri: Uri): AudioLayer? = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: return@runCatching null
                AudioLayer(uri = uri, name = displayName(uri), durationUs = durationMs * 1000)
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    suspend fun thumbnail(clip: MediaClip): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.loadThumbnail(clip.uri, Size(320, 320), null)
        }.getOrNull() ?: runCatching {
            if (clip.type == ClipType.VIDEO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, clip.uri)
                    retriever.getScaledFrameAtTime(
                        clip.trimStartUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320,
                    )
                } finally {
                    retriever.release()
                }
            } else {
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                context.contentResolver.openInputStream(clip.uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        }.getOrNull()
    }

    private fun inspectVideo(uri: Uri, mimeType: String, name: String): MediaClip? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rawW = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawH = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (durationMs <= 0 || rawW <= 0 || rawH <= 0) return@runCatching null
            val rotated = rotation % 180 != 0
            MediaClip(
                id = Ids.next(),
                uri = uri,
                mimeType = mimeType,
                type = ClipType.VIDEO,
                name = name,
                width = if (rotated) rawH else rawW,
                height = if (rotated) rawW else rawH,
                sourceDurationUs = durationMs * 1000,
                hasAudio = hasAudio,
            )
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun inspectImage(uri: Uri, mimeType: String, name: String): MediaClip? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            runCatching {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val rotated = orientation in setOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        )
        MediaClip(
            id = Ids.next(),
            uri = uri,
            mimeType = mimeType,
            type = ClipType.IMAGE,
            name = name,
            width = if (rotated) bounds.outHeight else bounds.outWidth,
            height = if (rotated) bounds.outWidth else bounds.outHeight,
            sourceDurationUs = 0L,
            hasAudio = false,
        )
    }.getOrNull()

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "media"
}
