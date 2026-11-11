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
import androidx.media3.effect.GaussianBlur
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
import com.videomaker.app.model.TransitionType
import com.videomaker.app.model.ClipEffect
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
        val starts = doc.clipStartsUs()
        val overlaps = doc.overlapsUs()

        val sequences = mutableListOf<EditedMediaItemSequence>()
        var overlayWindow: LongRange? = null

        doc.overlay?.let { overlay ->
            buildOverlaySequence(overlay, totalUs, output.width)?.let { (sequence, window) ->
                sequences += sequence
                overlayWindow = window
            }
        }
        val overlayInputs = sequences.size

        val items = buildMainItems(doc, starts, overlaps)
        val mainTracks = buildSet {
            add(C.TRACK_TYPE_VIDEO)
            if (output.keepOriginalAudio) add(C.TRACK_TYPE_AUDIO)
        }
        // Overlapping transitions need two clips on screen at once, so the clips alternate between
        // two tracks ("A/B roll"). Without such transitions a single track is enough.
        val twoTracks = overlaps.any { it > 0 }
        if (twoTracks) {
            for (track in 0..1) {
                val builder = EditedMediaItemSequence.Builder(mainTracks)
                var cursorUs = 0L
                for (index in items.indices) {
                    if (index % 2 != track) continue
                    val gapUs = starts[index] - cursorUs
                    if (gapUs > 0) builder.addGap(gapUs)
                    builder.addItem(items[index])
                    cursorUs = starts[index] + doc.clips[index].timelineDurationUs
                }
                // Both tracks must span the whole video.
                if (totalUs - cursorUs > 0) builder.addGap(totalUs - cursorUs)
                sequences += builder.build()
            }
        } else {
            sequences += EditedMediaItemSequence.Builder(mainTracks).addItems(items).build()
        }

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

        val builder = Composition.Builder(sequences)
        // Media3 only accepts layer settings when there is more than one video layer to combine;
        // with a single layer it refuses to play or export.
        if (videoLayerCount(sequences) > 1) {
            builder.setVideoCompositorSettings(
                LayerCompositorSettings(
                    outputWidth = output.width,
                    outputHeight = output.height,
                    overlay = doc.overlay,
                    overlayWindowUs = overlayWindow,
                    overlayInputs = overlayInputs,
                    twoTracks = twoTracks,
                    clips = doc.clips,
                    startsUs = starts,
                    overlapsUs = overlaps,
                ),
            )
        }
        return builder
            .setEffects(Effects(compositionAudio, compositionVideoEffects))
            // The compositor works in SDR; convert HDR phone recordings so they can be mixed.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    /** How many sequences carry video (the overlay and one or two main tracks). */
    fun videoLayerCount(sequences: List<EditedMediaItemSequence>): Int =
        sequences.count { C.TRACK_TYPE_VIDEO in it.trackTypes }

    // ---- Main timeline -------------------------------------------------------------------------

    private fun buildMainItems(
        doc: EditorDocument,
        starts: List<Long>,
        overlaps: List<Long>,
    ): List<EditedMediaItem> {
        val output = doc.output
        val fades = doc.transitions
        val lastIndex = doc.clips.lastIndex

        return doc.clips.mapIndexed { index, clip ->
            val startUs = starts[index]
            val durationUs = clip.timelineDurationUs
            val next = doc.clips.getOrNull(index + 1)
            // Fades can take at most a third of a clip so short clips stay visible.
            val maxFade = durationUs / 3
            val fadeIn = when {
                index == 0 && fades.fadeInAtStart -> fades.durationUs
                index > 0 && clip.transition == TransitionType.DIP_BLACK -> clip.transitionUs / 2
                else -> 0L
            }.coerceAtMost(maxFade)
            val fadeOut = when {
                index == lastIndex && fades.fadeOutAtEnd -> fades.durationUs
                next != null && next.transition == TransitionType.DIP_BLACK -> next.transitionUs / 2
                else -> 0L
            }.coerceAtMost(maxFade)

            val effects = mutableListOf<Effect>()
            transformFor(clip)?.let { effects += it }

            val fx = clip.effect
            val strength = clip.effectStrength.coerceIn(0f, 1f)
            val shake = if (fx == ClipEffect.SHAKE) strength else 0f
            val pulse = if (fx == ClipEffect.PULSE) strength else 0f
            if (clip.zoom != 1f || clip.slowZoom || shake > 0f || pulse > 0f) {
                effects += MotionEffect(
                    zoom = clip.zoom.coerceIn(1f, 4f),
                    offsetX = clip.offsetX,
                    offsetY = clip.offsetY,
                    slowZoom = clip.slowZoom,
                    shake = shake,
                    pulse = pulse,
                    clipStartUs = startUs,
                    clipDurationUs = durationUs,
                )
            }
            when {
                fx == ClipEffect.BLUR -> effects += GaussianBlur(2f + 22f * strength)
                ShaderFxEffect.supports(fx) -> effects += ShaderFxEffect(fx, strength)
            }
            val colorMatrix = ColorGrading.matrixFor(clip)
            if (!ColorGrading.isIdentity(colorMatrix) || fadeIn > 0 || fadeOut > 0) {
                effects += ColorAndFadeEffect(colorMatrix, startUs, startUs + durationUs, fadeIn, fadeOut)
            }
            effects += fitEffects(clip, output.width, output.height)

            editedItem(clip, effects, clip.volume)
        }
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

    /**
     * Fixes the output size, places/hides the overlay layer, and animates the two main tracks
     * through transitions. Inputs are numbered in sequence order and input 0 is drawn on top.
     */
    private class LayerCompositorSettings(
        private val outputWidth: Int,
        private val outputHeight: Int,
        overlay: OverlayLayer?,
        private val overlayWindowUs: LongRange?,
        private val overlayInputs: Int,
        private val twoTracks: Boolean,
        private val clips: List<MediaClip>,
        private val startsUs: List<Long>,
        private val overlapsUs: List<Long>,
    ) : VideoCompositorSettings {

        private val normal: OverlaySettings = StaticOverlaySettings.Builder().build()
        private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        private val overlayVisible: OverlaySettings? = overlay?.let {
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
            if (inputId < overlayInputs) {
                val window = overlayWindowUs
                if (overlayVisible == null || window == null) return hidden
                return if (presentationTimeUs in window) overlayVisible else hidden
            }
            if (!twoTracks) return normal
            val track = inputId - overlayInputs
            if (track > 1) return normal
            return trackSettings(track, presentationTimeUs)
        }

        /** Settings for main track 0 (on top) or 1 (below) at time [t]. */
        private fun trackSettings(track: Int, t: Long): OverlaySettings {
            // The clip of this track that is on screen at t, if any.
            var index = -1
            for (i in clips.indices) {
                if (i % 2 == track && t >= startsUs[i] && t < startsUs[i] + clips[i].timelineDurationUs) {
                    index = i
                    break
                }
            }
            if (index < 0) return hidden // This track is in a gap.

            val onTop = track == 0
            // Entering: this clip is replacing the previous one.
            val inUs = overlapsUs[index]
            if (inUs > 0 && t < startsUs[index] + inUs) {
                val p = (t - startsUs[index]).toFloat() / inUs
                return TransitionMath.settings(clips[index].transition, incoming = true, ease(p), onTop)
            }
            // Leaving: the next clip is replacing this one.
            val next = index + 1
            if (next < clips.size && overlapsUs[next] > 0 && t >= startsUs[next]) {
                val p = (t - startsUs[next]).toFloat() / overlapsUs[next]
                return TransitionMath.settings(clips[next].transition, incoming = false, ease(p), onTop)
            }
            return normal
        }

        private fun ease(p: Float): Float {
            val x = p.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
    }
}

/** Where each of the two clips sits during a transition, for progress p from 0 to 1. */
@OptIn(UnstableApi::class)
object TransitionMath {

    fun settings(type: TransitionType, incoming: Boolean, p: Float, onTop: Boolean): OverlaySettings {
        val b = StaticOverlaySettings.Builder()
        // Crossfade rule: only the upper layer changes opacity, revealing or covering the lower one.
        val crossfadeAlpha = when {
            !onTop -> 1f
            incoming -> p
            else -> 1f - p
        }
        when (type) {
            TransitionType.NONE, TransitionType.DIP_BLACK -> Unit
            TransitionType.FADE -> b.setAlphaScale(crossfadeAlpha)
            // Slides push the old clip out while the new one follows it in, edge to edge.
            TransitionType.SLIDE_LEFT -> slide(b, incoming, p, dx = -1f, dy = 0f)
            TransitionType.SLIDE_RIGHT -> slide(b, incoming, p, dx = 1f, dy = 0f)
            TransitionType.SLIDE_UP -> slide(b, incoming, p, dx = 0f, dy = 1f)
            TransitionType.SLIDE_DOWN -> slide(b, incoming, p, dx = 0f, dy = -1f)
            TransitionType.ZOOM -> {
                val scale = if (incoming) 1.5f - 0.5f * p else 1f + 0.5f * p
                b.setScale(scale, scale).setAlphaScale(crossfadeAlpha)
            }
            TransitionType.SPIN -> {
                val angle = if (incoming) 70f * (1f - p) else -70f * p
                val scale = 1f + 0.6f * (if (incoming) 1f - p else p)
                b.setRotationDegrees(angle).setScale(scale, scale).setAlphaScale(crossfadeAlpha)
            }
        }
        return b.build()
    }

    /**
     * Moves in direction (dx, dy). The outgoing clip's trailing edge and the incoming clip's
     * leading edge are pinned to the same moving line, so they travel together.
     */
    private fun slide(b: StaticOverlaySettings.Builder, incoming: Boolean, p: Float, dx: Float, dy: Float) {
        // The shared line starts at the edge the new clip comes from and ends at the opposite edge.
        val line = 1f - 2f * p
        val edge = if (incoming) 1f else -1f
        b.setOverlayFrameAnchor(dx * edge, dy * edge)
        b.setBackgroundFrameAnchor((-dx * line).coerceIn(-1f, 1f), (-dy * line).coerceIn(-1f, 1f))
    }
}
