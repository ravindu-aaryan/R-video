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

    /** A plain single-colour photo. */
    fun solid(name: String, color: Int, size: Int = 720, seconds: Float = 2f): MediaClip =
        save(name, Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, seconds)

    /**
     * A square photo with four coloured quarters (top-left red, top-right green, bottom-left blue,
     * bottom-right yellow) and fine stripes, so every kind of effect visibly changes it.
     */
    fun pattern(name: String, size: Int = 720, seconds: Float = 2f): MediaClip {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val h = size / 2f
        val f = size.toFloat()
        paint.color = Color.RED; canvas.drawRect(0f, 0f, h, h, paint)
        paint.color = Color.GREEN; canvas.drawRect(h, 0f, f, h, paint)
        paint.color = Color.BLUE; canvas.drawRect(0f, h, h, f, paint)
        paint.color = Color.YELLOW; canvas.drawRect(h, h, f, f, paint)
        paint.color = Color.WHITE
        var x = 0f
        while (x < f) {
            canvas.drawRect(x, 0f, x + 6f, f, paint)
            x += 60f
        }
        return save(name, bitmap, seconds)
    }

    private fun save(name: String, bitmap: Bitmap, seconds: Float): MediaClip {
        val file = File(context.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return MediaClip(
            id = Ids.next(),
            uri = Uri.fromFile(file),
            mimeType = "image/png",
            type = ClipType.IMAGE,
            name = name,
            width = bitmap.width,
            height = bitmap.height,
            sourceDurationUs = 0L,
            hasAudio = false,
            imageDurationUs = (seconds * 1_000_000).toLong(),
        )
    }

    /** The exported video's frame nearest to [seconds]. */
    fun frame(file: File, seconds: Float): Bitmap {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val bitmap = r.getFrameAtTime((seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
        r.release()
        return checkNotNull(bitmap) { "No frame at $seconds s in ${file.name}" }
            .copy(Bitmap.Config.ARGB_8888, false)
    }

    fun durationSeconds(file: File): Float {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release()
        return ms / 1000f
    }

    /**
     * Average red, green, blue (0..255) of a region given as fractions of the frame
     * (left, top, right, bottom).
     */
    fun average(bitmap: Bitmap, l: Float = 0f, t: Float = 0f, r: Float = 1f, b: Float = 1f): FloatArray {
        val x0 = (l * bitmap.width).toInt()
        val x1 = (r * bitmap.width).toInt().coerceAtMost(bitmap.width)
        val y0 = (t * bitmap.height).toInt()
        val y1 = (b * bitmap.height).toInt().coerceAtMost(bitmap.height)
        var red = 0L; var green = 0L; var blue = 0L; var n = 0L
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = bitmap.getPixel(x, y)
                red += Color.red(p); green += Color.green(p); blue += Color.blue(p); n++
                x += 4
            }
            y += 4
        }
        return floatArrayOf(red.toFloat() / n, green.toFloat() / n, blue.toFloat() / n)
    }

    /** Mean absolute difference per colour channel (0..255) between two same-size frames. */
    fun difference(a: Bitmap, b: Bitmap): Float {
        var sum = 0L; var n = 0L
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                sum += kotlin.math.abs(Color.red(p) - Color.red(q)) +
                    kotlin.math.abs(Color.green(p) - Color.green(q)) +
                    kotlin.math.abs(Color.blue(p) - Color.blue(q))
                n += 3
                x += 4
            }
            y += 4
        }
        return sum.toFloat() / n
    }
}
