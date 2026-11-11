package com.videomaker.app.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.util.concurrent.atomic.AtomicLong

/** Unique ids for clips and captions (also used for split/duplicate copies). */
object Ids {
    private val next = AtomicLong(1)
    fun next(): Long = next.getAndIncrement()
}

enum class ClipType { VIDEO, IMAGE }

/** How a clip fills a frame whose shape differs from its own. */
enum class FitMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    BLUR("Blur background"),
}

/** How a clip enters, replacing the clip before it. */
enum class TransitionType(val label: String, val overlaps: Boolean) {
    NONE("None", false),
    FADE("Crossfade", true),
    DIP_BLACK("Dip to black", false),
    SLIDE_LEFT("Slide left", true),
    SLIDE_RIGHT("Slide right", true),
    SLIDE_UP("Slide up", true),
    SLIDE_DOWN("Slide down", true),
    ZOOM("Zoom", true),
    SPIN("Spin", true),
}

/** A visual effect applied to a whole clip. */
enum class ClipEffect(val label: String) {
    NONE("None"),
    GLITCH("Glitch"),
    RGB_SPLIT("RGB split"),
    VHS("VHS"),
    SHAKE("Shake"),
    PULSE("Beat zoom"),
    FLASH("Flash"),
    VIGNETTE("Vignette"),
    BLUR("Blur"),
    PIXELATE("Pixelate"),
    MIRROR("Mirror"),
    WAVE("Wave"),
}

/** Brightness, contrast, saturation and warmth, each from -1 to 1 (0 = unchanged). */
@Immutable
data class ColorAdjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
) {
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f
}

/** A video or photo on the main timeline, plus every edit applied to it. */
@Immutable
data class MediaClip(
    val id: Long,
    val uri: Uri,
    val mimeType: String,
    val type: ClipType,
    val name: String,
    /** Width and height as displayed (rotation metadata already applied), in pixels. */
    val width: Int,
    val height: Int,
    /** Full source length for videos; 0 for photos. */
    val sourceDurationUs: Long,
    val hasAudio: Boolean,
    // Timing
    val trimStartUs: Long = 0L,
    val trimEndUs: Long = sourceDurationUs,
    val imageDurationUs: Long = DEFAULT_IMAGE_DURATION_US,
    val speed: Float = 1f,
    // Sound
    val volume: Float = 1f,
    // Look
    val filter: FilterPreset = FilterPreset.NONE,
    val filterStrength: Float = 1f,
    val adjust: ColorAdjust = ColorAdjust(),
    // Framing
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val fit: FitMode = FitMode.FIT,
    /** Extra zoom (1 = none) and position shift (-1..1) inside the frame. */
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Slow "Ken Burns" zoom while the clip plays. */
    val slowZoom: Boolean = false,
    // Reel tools
    /** How this clip enters from the previous one (ignored for the first clip). */
    val transition: TransitionType = TransitionType.NONE,
    val transitionUs: Long = 500_000L,
    val effect: ClipEffect = ClipEffect.NONE,
    val effectStrength: Float = 0.6f,
) {
    /** How long this clip lasts in the final video (after trimming and speed). */
    val timelineDurationUs: Long
        get() = if (type == ClipType.IMAGE) imageDurationUs else ((trimEndUs - trimStartUs) / speed).toLong()

    /** Width / height as it will appear, including the user's rotation. */
    val aspectRatio: Float
        get() {
            if (width == 0 || height == 0) return 16f / 9f
            val rotated = rotationDegrees % 180 != 0
            return if (rotated) height.toFloat() / width else width.toFloat() / height
        }

    val hasColorChanges: Boolean
        get() = (filter != FilterPreset.NONE && filterStrength > 0f) || !adjust.isNeutral

    companion object {
        const val DEFAULT_IMAGE_DURATION_US = 3_000_000L
        const val MIN_CLIP_US = 300_000L
        val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
    }
}

/** The video or image layered on top of the main timeline. */
@Immutable
data class OverlayLayer(
    val clip: MediaClip,
    /** 0 = invisible, 1 = fully opaque. */
    val opacity: Float = 0.6f,
    /** Overlay width as a fraction of the output width. */
    val widthFraction: Float = 0.4f,
    /** Position in [-1, 1]; (0,0) is the centre, (1,1) the top-right corner. */
    val positionX: Float = 1f,
    val positionY: Float = 1f,
    /** When the overlay appears on the main timeline. */
    val startUs: Long = 0L,
    val includeAudio: Boolean = false,
    val volume: Float = 1f,
)

enum class TextStyle(val label: String) { PLAIN("Plain"), SHADOW("Shadow"), BOX("Box") }

/** How a caption appears and disappears. */
enum class TextAnimation(val label: String) { NONE("None"), FADE("Fade"), SLIDE("Slide up"), POP("Pop") }

/** A caption or title drawn on top of everything for a time range. */
@Immutable
data class TextItem(
    val id: Long,
    val text: String,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    /** Text height as a fraction of the frame's short side. */
    val sizeFraction: Float = 0.08f,
    val bold: Boolean = true,
    val style: TextStyle = TextStyle.SHADOW,
    val animation: TextAnimation = TextAnimation.FADE,
    val positionX: Float = 0f,
    val positionY: Float = -0.8f,
    val startUs: Long = 0L,
    val endUs: Long = 3_000_000L,
)

/** Background music or voice-over mixed into the result. */
@Immutable
data class AudioLayer(
    val uri: Uri,
    val name: String,
    val durationUs: Long,
    val volume: Float = 0.8f,
    val loop: Boolean = true,
    /** Where in the song to start playing. */
    val startOffsetUs: Long = 0L,
)

@Immutable
data class Transitions(
    val durationUs: Long = 500_000L,
    val fadeInAtStart: Boolean = false,
    val fadeOutAtEnd: Boolean = false,
    /** Fade all sound in at the start and out at the end. */
    val fadeAudio: Boolean = false,
)

enum class AspectRatio(val label: String, val widthUnits: Int, val heightUnits: Int) {
    LANDSCAPE("16:9", 16, 9),
    PORTRAIT("9:16", 9, 16),
    SQUARE("1:1", 1, 1),
    FOUR_FIVE("4:5", 4, 5),
}

enum class Resolution(val label: String, val shortSide: Int) {
    HD("720p", 720),
    FULL_HD("1080p", 1080),
}

@Immutable
data class OutputSettings(
    val aspectRatio: AspectRatio = AspectRatio.LANDSCAPE,
    val resolution: Resolution = Resolution.FULL_HD,
    val keepOriginalAudio: Boolean = true,
) {
    /** Output frame size in pixels; always even so every encoder accepts it. */
    val width: Int
        get() = even(resolution.shortSide * aspectRatio.widthUnits / shortUnits)
    val height: Int
        get() = even(resolution.shortSide * aspectRatio.heightUnits / shortUnits)

    val ratio: Float get() = width.toFloat() / height

    private val shortUnits: Int get() = minOf(aspectRatio.widthUnits, aspectRatio.heightUnits)
    private fun even(value: Int) = value - (value % 2)
}

/** Everything the user has built. Undo/redo snapshots this. */
@Immutable
data class EditorDocument(
    val clips: List<MediaClip> = emptyList(),
    val overlay: OverlayLayer? = null,
    val texts: List<TextItem> = emptyList(),
    val audio: AudioLayer? = null,
    val transitions: Transitions = Transitions(),
    val output: OutputSettings = OutputSettings(),
) {
    /**
     * How long each clip overlaps the one before it (0 for the first clip and for cuts without an
     * overlapping transition). Capped so a clip's entry and exit transitions never collide.
     */
    fun overlapsUs(): List<Long> = clips.mapIndexed { index, clip ->
        if (index == 0 || !clip.transition.overlaps) {
            0L
        } else {
            val previous = clips[index - 1]
            minOf(
                clip.transitionUs,
                (previous.timelineDurationUs * 0.45f).toLong(),
                (clip.timelineDurationUs * 0.45f).toLong(),
            ).coerceAtLeast(0L)
        }
    }

    /** Start time of each clip on the timeline, in the same order as [clips]. */
    fun clipStartsUs(): List<Long> {
        val overlaps = overlapsUs()
        var end = 0L
        return clips.mapIndexed { index, clip ->
            val start = end - overlaps[index]
            end = start + clip.timelineDurationUs
            start
        }
    }

    val totalDurationUs: Long
        get() {
            val last = clips.lastOrNull() ?: return 0L
            return clipStartsUs().last() + last.timelineDurationUs
        }

    /** The clip under timeline position [positionUs] (the incoming one during a transition), with its start. */
    fun clipAt(positionUs: Long): Pair<MediaClip, Long>? {
        if (clips.isEmpty()) return null
        val starts = clipStartsUs()
        val index = starts.indexOfLast { it <= positionUs }.coerceAtLeast(0)
        return clips[index] to starts[index]
    }
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Int?) : ExportState
    data class Done(val savedUri: Uri, val fileName: String) : ExportState
    data class Failed(val message: String) : ExportState
}

/** Tools shown in the bottom bar. */
enum class Tool {
    // Project tools (no clip selected)
    OVERLAY, TEXT, MUSIC, TRANSITIONS, FORMAT,
    // Clip tools
    TRIM, SPEED, VOLUME, FILTERS, ADJUST, ROTATE, FIT, TRANSITION, EFFECTS,
}

@Immutable
data class UiMessage(val text: String, val canUndo: Boolean = false)

@Immutable
data class EditorUiState(
    val doc: EditorDocument = EditorDocument(),
    val selectedClipId: Long? = null,
    val selectedTextId: Long? = null,
    val activeTool: Tool? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val export: ExportState = ExportState.Idle,
    val isLoadingMedia: Boolean = false,
    val message: UiMessage? = null,
    val previewError: String? = null,
) {
    val selectedClip: MediaClip? get() = doc.clips.firstOrNull { it.id == selectedClipId }
    val selectedText: TextItem? get() = doc.texts.firstOrNull { it.id == selectedTextId }
    val canExport: Boolean get() = doc.clips.isNotEmpty() && export !is ExportState.Running
}

/** Playhead state, kept apart from [EditorUiState] because it changes many times a second. */
@Immutable
data class PlaybackState(
    val positionUs: Long = 0L,
    val isPlaying: Boolean = false,
)
