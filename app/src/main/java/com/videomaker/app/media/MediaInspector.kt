package com.videomaker.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import java.io.File
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
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
        val mimeType = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase(),
            )
            ?: return@withContext null
        val name = displayName(uri)
        when {
            mimeType.startsWith("image/") -> inspectImage(uri, name)
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

    /**
     * Photos are copied into the app as plain, upright JPEGs (PNG when they have transparency) of
     * a sensible size. That makes every photo behave the same in the preview and the export,
     * whatever the camera saved: HEIC, Ultra HDR, 50–200 MP shots, or rotated-by-metadata files.
     */
    private fun inspectImage(uri: Uri, name: String): MediaClip? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > MAX_IMAGE_SIDE) {
                val scale = MAX_IMAGE_SIDE.toFloat() / longSide
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 34) bitmap.gainmap = null // Drop Ultra HDR data.

        val id = Ids.next()
        val keepAlpha = bitmap.hasAlpha()
        val dir = File(context.cacheDir, IMPORT_DIR).apply { mkdirs() }
        val file = File(dir, "img_$id.${if (keepAlpha) "png" else "jpg"}")
        file.outputStream().use { out ->
            if (keepAlpha) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val clip = MediaClip(
            id = id,
            uri = Uri.fromFile(file),
            mimeType = if (keepAlpha) "image/png" else "image/jpeg",
            type = ClipType.IMAGE,
            name = name,
            width = bitmap.width,
            height = bitmap.height,
            sourceDurationUs = 0L,
            hasAudio = false,
        )
        bitmap.recycle()
        clip
    }.getOrNull()

    /** Removes photo copies left over from earlier sessions. */
    fun clearImportedCopies() {
        File(context.cacheDir, IMPORT_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "media"

    private companion object {
        const val MAX_IMAGE_SIDE = 2160
        const val IMPORT_DIR = "imports"
    }
}
