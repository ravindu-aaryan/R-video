package com.videomaker.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders a [Composition] to an MP4 and saves it to the gallery (Movies/VideoMaker). */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    data class Saved(val uri: Uri, val fileName: String)

    /**
     * Exports the composition. Must be called from the main thread (Transformer needs a Looper).
     * [onProgress] receives 0..100, or null while progress isn't known yet.
     */
    suspend fun export(composition: Composition, onProgress: (Int?) -> Unit): Saved {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        exportDir.listFiles()?.forEach { it.delete() } // keep the cache small
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VideoMaker_$stamp.mp4"
        val outFile = File(exportDir, fileName)

        render(composition, outFile, onProgress)
        val uri = saveToGallery(outFile, fileName)
        outFile.delete()
        return Saved(uri, fileName)
    }

    private suspend fun render(
        composition: Composition,
        outFile: File,
        onProgress: (Int?) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val finished = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            // H.264 + AAC plays everywhere (WhatsApp, Instagram, YouTube, PCs).
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    finished.completeExceptionally(exportException)
                }
            })
            .build()

        transformer.start(composition, outFile.absolutePath)

        val progressJob = launch {
            val holder = ProgressHolder()
            while (isActive) {
                val state = transformer.getProgress(holder)
                onProgress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                delay(250)
            }
        }
        try {
            finished.await()
        } catch (e: CancellationException) {
            transformer.cancel()
            outFile.delete()
            throw e
        } finally {
            progressJob.cancel()
        }
    }

    private suspend fun saveToGallery(file: File, fileName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoMaker")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Couldn't create the video in the gallery")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't write the video to the gallery")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri
    }
}
