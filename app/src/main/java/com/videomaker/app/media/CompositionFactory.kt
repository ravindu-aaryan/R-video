package com.videomaker.app.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.SpeedParameters
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.videomaker.app.model.AudioLayer
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.ColorGrading
import com.videomaker.app.model.EditorDocument
import com.videomaker.app.model.FitMode
import com.videomaker.app.model.MediaClip
import com.videomaker.app.model.OverlayLayer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the editor document into a Media3 [Composition]. The same composition is used for the
 * live preview and for export.
 *
 * Sequences (order matters):
 *  - 0: the overlay layer, if any. Media3 draws the first video sequence on top. It's padded with
 *    gaps so it spans the whole timeline and hidden (alpha 0) outside its visible window.
 *  - next: the main timeline — videos and photos one after another, each with its own effects.
 *  - last: background music.
 * Captions and the audio fade are composition-level effects applied to the final picture/mix.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
object CompositionFactory {

    private const val FRAME_RATE = 30

    /** Keeps the overlay just inside the frame edges when it is pushed to a corner. */
    const val EDGE_MARGIN = 0.94f

    fun build(doc: EditorDocument): Composition {
        require(doc.clips.isNotEmpty()) { "Add at least one video or photo" }
        val output = doc.output
        val totalUs = doc.totalDurationUs

        val sequences = mutableListOf<EditedMediaItemSequence>()
        var overlayWindow: LongRange? = null

        doc.overlay?.let { overlay ->
            buildOverlaySequence(overlay, totalUs, output.width)?.let { (sequence, window) ->
                sequences += sequence
                overlayWindow = window
            }
        }

        sequences += buildMainSequence(doc)
        doc.audio?.let { sequences += buildMusicSequence(it, totalUs) }

        val compositionVideoEffects = mutableListOf<Effect>()
        if (doc.texts.isNotEmpty()) {
            val shortSide = minOf(output.width, output.height)
            val captions: List<TextureOverlay> = doc.texts
                .filter { it.text.isNotBlank() && it.endUs > it.startUs }
                .map { CaptionOverlay(it, shortSide) }
            if (captions.isNotEmpty()) compositionVideoEffects += OverlayEffect(captions)
        }
        val compositionAudio = mutableListOf<AudioProcessor>()
        if (doc.transitions.fadeAudio) {
            compositionAudio += FadeAudioProcessor(totalUs, fadeUs = minOf(1_500_000L, totalUs / 3))
        }

        return Composition.Builder(sequences)
            .setVideoCompositorSettings(
                LayerCompositorSettings(output.width, output.height, doc.overlay, overlayWindow),
            )
            .setEffects(Effects(compositionAudio, compositionVideoEffects))
            // The compositor works in SDR; convert HDR phone recordings so they can be mixed.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    // ---- Main timeline -------------------------------------------------------------------------

    private fun buildMainSequence(doc: EditorDocument): EditedMediaItemSequence {
        val output = doc.output
        val transitions = doc.transitions
        val starts = doc.clipStartsUs()
        val lastIndex = doc.clips.lastIndex

        val items = doc.clips.mapIndexed { index, clip ->
            val startUs = starts[index]
            val durationUs = clip.timelineDurationUs
            // Fades can take at most a third of a clip so short clips stay visible.
            val maxFade = durationUs / 3
            val fadeIn = when {
                index == 0 && transitions.fadeInAtStart -> transitions.durationUs
                index > 0 && transitions.fadeBetweenClips -> transitions.durationUs / 2
                else -> 0L
            }.coerceAtMost(maxFade)
            val fadeOut = when {
                index == lastIndex && transitions.fadeOutAtEnd -> transitions.durationUs
                index < lastIndex && transitions.fadeBetweenClips -> transitions.durationUs / 2
                else -> 0L
            }.coerceAtMost(maxFade)

            val effects = mutableListOf<Effect>()
            transformFor(clip)?.let { effects += it }
            if (clip.type == ClipType.IMAGE && clip.slowZoom) {
                effects += SlowZoomEffect(startUs, durationUs)
            }
            val colorMatrix = ColorGrading.matrixFor(clip)
            if (!ColorGrading.isIdentity(colorMatrix) || fadeIn > 0 || fadeOut > 0) {
                effects += ColorAndFadeEffect(colorMatrix, startUs, startUs + durationUs, fadeIn, fadeOut)
            }
            effects += fitEffects(clip, output.width, output.height)

            editedItem(clip, effects, clip.volume)
        }

        val tracks = buildSet {
            add(C.TRACK_TYPE_VIDEO)
            if (output.keepOriginalAudio) add(C.TRACK_TYPE_AUDIO)
        }
        return EditedMediaItemSequence.Builder(tracks).addItems(items).build()
    }

    /** User rotation and flips, or null when there are none. */
    private fun transformFor(clip: MediaClip): Effect? {
        if (clip.rotationDegrees % 360 == 0 && !clip.flipHorizontal && !clip.flipVertical) return null
        return ScaleAndRotateTransformation.Builder()
            .setScale(if (clip.flipHorizontal) -1f else 1f, if (clip.flipVertical) -1f else 1f)
            // Media3 rotates counter-clockwise; the UI rotates clockwise.
            .setRotationDegrees((360 - clip.rotationDegrees % 360).toFloat())
            .build()
    }

    /** Puts the clip into the output frame using the chosen fit mode. */
    private fun fitEffects(clip: MediaClip, width: Int, height: Int): List<Effect> {
        val outRatio = width.toFloat() / height
        return when {
            clip.fit == FitMode.FILL ->
                listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))

            clip.fit == FitMode.BLUR && abs(clip.aspectRatio - outRatio) > 0.01f -> {
                // Size of the clip when fitted inside the frame.
                val (fitW, fitH) = if (clip.aspectRatio > outRatio) {
                    width to (width / clip.aspectRatio).roundToInt()
                } else {
                    (height * clip.aspectRatio).roundToInt() to height
                }
                listOf(
                    Presentation.createForWidthAndHeight(even(fitW), even(fitH), Presentation.LAYOUT_SCALE_TO_FIT),
                    // Draws the sharp clip in the middle of a blurred, stretched copy of itself.
                    GaussianBlurWithFrameOverlaid(
                        /* sigma= */ 24f,
                        /* scaleSharpX= */ even(fitW).toFloat() / width,
                        /* scaleSharpY= */ even(fitH).toFloat() / height,
                    ),
                    Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_STRETCH_TO_FIT),
                )
            }

            else ->
                listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))
        }
    }

    // ---- Overlay -------------------------------------------------------------------------------

    private fun buildOverlaySequence(
        overlay: OverlayLayer,
        totalUs: Long,
        outputWidth: Int,
    ): Pair<EditedMediaItemSequence, LongRange>? {
        val startUs = overlay.startUs.coerceIn(0L, totalUs)
        val availableUs = totalUs - startUs
        if (availableUs < MediaClip.MIN_CLIP_US) return null

        // Clip the overlay so it never runs past the end of the main timeline.
        val clip = overlay.clip
        val fitted = if (clip.type == ClipType.IMAGE) {
            clip.copy(imageDurationUs = availableUs)
        } else {
            clip.copy(trimEndUs = minOf(clip.trimEndUs, clip.trimStartUs + availableUs))
        }
        val visibleUs = fitted.timelineDurationUs

        // Resize the overlay to an exact pixel size that keeps its own aspect ratio.
        val overlayWidth = even((outputWidth * overlay.widthFraction).roundToInt()).coerceAtLeast(16)
        val overlayHeight = even((overlayWidth / clip.aspectRatio).roundToInt()).coerceAtLeast(16)
        val resize = Presentation.createForWidthAndHeight(
            overlayWidth, overlayHeight, Presentation.LAYOUT_STRETCH_TO_FIT,
        )

        val tracks = buildSet {
            add(C.TRACK_TYPE_VIDEO)
            if (overlay.includeAudio) add(C.TRACK_TYPE_AUDIO)
        }
        val builder = EditedMediaItemSequence.Builder(tracks)
        if (startUs > 0) builder.addGap(startUs)
        builder.addItem(editedItem(fitted, listOf(resize), overlay.volume))
        val tailUs = totalUs - startUs - visibleUs
        if (tailUs > 0) builder.addGap(tailUs)

        return builder.build() to (startUs until startUs + visibleUs)
    }

    // ---- Music ---------------------------------------------------------------------------------

    private fun buildMusicSequence(audio: AudioLayer, totalUs: Long): EditedMediaItemSequence {
        val startUs = audio.startOffsetUs.coerceIn(0L, (audio.durationUs - MediaClip.MIN_CLIP_US).coerceAtLeast(0L))
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionUs(startUs)
        if (!audio.loop && audio.durationUs - startUs > totalUs) {
            clipping.setEndPositionUs(startUs + totalUs) // Stop the music when the video ends.
        }
        val mediaItem = MediaItem.Builder()
            .setUri(audio.uri)
            .setClippingConfiguration(clipping.build())
            .build()
        val item = EditedMediaItem.Builder(mediaItem)
            .setDurationUs(audio.durationUs)
            .setEffects(Effects(listOf(volumeProcessor(audio.volume)), emptyList()))
            .build()
        return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            .addItem(item)
            .setIsLooping(audio.loop)
            .build()
    }

    // ---- Shared --------------------------------------------------------------------------------

    private fun editedItem(clip: MediaClip, videoEffects: List<Effect>, volume: Float): EditedMediaItem {
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(clip.uri)
            .setMimeType(clip.mimeType)
        if (clip.type == ClipType.IMAGE) {
            mediaItemBuilder.setImageDurationMs(clip.imageDurationUs / 1000)
        } else {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.trimStartUs)
                    .setEndPositionUs(clip.trimEndUs)
                    .build(),
            )
        }
        val audioProcessors: List<AudioProcessor> =
            if (volume != 1f) listOf(volumeProcessor(volume)) else emptyList()

        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            // Photos: time on screen. Videos: full source length (before trimming and speed).
            .setDurationUs(if (clip.type == ClipType.IMAGE) clip.imageDurationUs else clip.sourceDurationUs)
            .setFrameRate(FRAME_RATE)
            .setEffects(Effects(audioProcessors, videoEffects))
        if (clip.type == ClipType.VIDEO && clip.speed != 1f) {
            builder.setSpeed(SpeedParameters(ConstantSpeed(clip.speed), /* shouldMaintainPitch= */ true))
        }
        return builder.build()
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private fun even(value: Int) = value - (value % 2)

    /** Fixes the output size and places/hides the overlay layer (input 0). */
    private class LayerCompositorSettings(
        private val outputWidth: Int,
        private val outputHeight: Int,
        overlay: OverlayLayer?,
        private val overlayWindowUs: LongRange?,
    ) : VideoCompositorSettings {

        private val background: OverlaySettings = StaticOverlaySettings.Builder().build()
        private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        private val visible: OverlaySettings? = overlay?.let {
            val x = it.positionX.coerceIn(-1f, 1f)
            val y = it.positionY.coerceIn(-1f, 1f)
            StaticOverlaySettings.Builder()
                .setAlphaScale(it.opacity.coerceIn(0f, 1f))
                // Pin the matching point of the overlay to the matching point of the frame,
                // so (1,1) puts the overlay's top-right corner in the frame's top-right corner.
                .setOverlayFrameAnchor(x, y)
                .setBackgroundFrameAnchor(x * EDGE_MARGIN, y * EDGE_MARGIN)
                .build()
        }

        override fun getOutputSize(inputSizes: List<Size>): Size = Size(outputWidth, outputHeight)

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            val window = overlayWindowUs
            if (inputId != 0 || visible == null || window == null) return background
            return if (presentationTimeUs in window) visible else hidden
        }
    }
}
