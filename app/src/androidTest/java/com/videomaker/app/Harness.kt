package com.videomaker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import com.videomaker.app.media.CompositionFactory
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.EditorDocument
import com.videomaker.app.model.Ids
import com.videomaker.app.model.MediaClip
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Helpers that run real previews and exports on a device, the same way the app does. */
@OptIn(UnstableApi::class, ExperimentalApi::class)
object Harness {
    const val TAG = "VMTest"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** A test photo saved as a JPEG file. */
    fun photo(name: String, width: Int, height: Int, color: Int, seconds: Float = 2f): MediaClip {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = Paint().apply { this.color = Color.WHITE; textSize = height / 6f }
        canvas.drawText(name, width / 10f, height / 2f, paint)
        val file = File(context.cacheDir, "$name.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return MediaClip(
            id = Ids.next(),
            uri = Uri.fromFile(file),
            mimeType = "image/jpeg",
            type = ClipType.IMAGE,
            name = name,
            width = width,
            height = height,
            sourceDurationUs = 0L,
            hasAudio = false,
            imageDurationUs = (seconds * 1_000_000).toLong(),
        )
    }

    /** Wraps an exported MP4 as a video clip. */
    fun video(file: File): MediaClip {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        r.release()
        return MediaClip(
            id = Ids.next(),
            uri = Uri.fromFile(file),
            mimeType = "video/mp4",
            type = ClipType.VIDEO,
            name = file.name,
            width = w,
            height = h,
            sourceDurationUs = durationMs * 1000,
            hasAudio = hasAudio,
        )
    }

    /**
     * Plays the document in a CompositionPlayer for [playMs] and returns a description of the
     * error, or null if it prepared and the playhead advanced.
     */
    fun preview(doc: EditorDocument, playMs: Long = 2500): String? {
        val thread = HandlerThread("reader").apply { start() }
        val reader = ImageReader.newInstance(doc.output.width, doc.output.height, PixelFormat.RGBA_8888, 3)
        var frames = 0
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); frames++ }, Handler(thread.looper))

        val ready = CountDownLatch(1)
        var error: String? = null
        lateinit var player: CompositionPlayer
        onMain {
            val composition = CompositionFactory.build(doc)
            val builder = CompositionPlayer.Builder(context)
            if (CompositionFactory.videoLayerCount(composition.sequences) > 1) builder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
            player = builder.build()
            player.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = "${e.errorCodeName}\n${Log.getStackTraceString(e)}"
                    Log.e(TAG, "preview error", e)
                    ready.countDown()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) ready.countDown()
                }
            })
            player.setVideoSurface(reader.surface, Size(doc.output.width, doc.output.height))
            player.setComposition(composition)
            player.prepare()
            player.play()
        }
        if (!ready.await(30, TimeUnit.SECONDS)) error = "Timed out waiting for the preview to become ready"
        if (error == null) {
            // Software rendering on an emulator can be slow: wait for the first frame, then play.
            val deadline = System.currentTimeMillis() + 40_000
            while (frames == 0 && error == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
            Thread.sleep(playMs)
        }
        val errorWhilePlaying = error
        var positionMs = 0L
        onMain {
            positionMs = player.currentPosition
            player.release()
        }
        error = errorWhilePlaying // Ignore anything reported during teardown.
        reader.close()
        thread.quitSafely()
        Log.i(TAG, "preview done: error=${error?.lineSequence()?.firstOrNull()} position=$positionMs frames=$frames")
        if (error == null && positionMs < 500) error = "Playhead did not advance (position=$positionMs ms, frames=$frames)"
        if (error == null && frames == 0) error = "No frames were rendered"
        return error
    }

    /** Exports the document like the app does; returns the file or throws with the cause. */
    fun export(doc: EditorDocument, name: String): File {
        val out = File(context.cacheDir, "$name.mp4").apply { delete() }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        onMain {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        failure = exportException
                        Log.e(TAG, "export error", exportException)
                        done.countDown()
                    }
                })
                .build()
                .start(CompositionFactory.build(doc), out.absolutePath)
        }
        if (!done.await(180, TimeUnit.SECONDS)) throw AssertionError("Export timed out")
        failure?.let { throw AssertionError("Export failed: ${Log.getStackTraceString(it)}") }
        check(out.length() > 10_000) { "Export produced an empty file" }
        Log.i(TAG, "export done: ${out.name} ${out.length()} bytes")
        return out
    }
}
