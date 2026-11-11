package com.videomaker.app.media

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UpdateAppearance
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import com.videomaker.app.model.ColorGrading
import com.videomaker.app.model.TextAnimation
import com.videomaker.app.model.TextItem
import com.videomaker.app.model.TextStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Time-based effects. Media3 hands every effect the frame's position on the final timeline
 * (in microseconds), so each effect is told where its clip starts and ends on that timeline.
 */

/**
 * Applies a colour matrix (filter + adjustments) and optional fades to black at the clip's
 * edges, in a single GPU pass.
 */
@OptIn(UnstableApi::class)
class ColorAndFadeEffect(
    private val colorMatrix: FloatArray,
    private val clipStartUs: Long,
    private val clipEndUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
) : RgbMatrix {

    private val steady = ColorGrading.toGlMatrix(colorMatrix)

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val brightness = fadeFactor(presentationTimeUs)
        return if (brightness >= 1f) steady else ColorGrading.toGlMatrix(colorMatrix, brightness)
    }

    private fun fadeFactor(t: Long): Float {
        var f = 1f
        if (fadeInUs > 0) f = minOf(f, (t - clipStartUs).toFloat() / fadeInUs)
        if (fadeOutUs > 0) f = minOf(f, (clipEndUs - t).toFloat() / fadeOutUs)
        return f.coerceIn(0f, 1f)
    }
}

/** A caption shown between [TextItem.startUs] and [TextItem.endUs], with a short fade. */
@OptIn(UnstableApi::class)
class CaptionOverlay(
    item: TextItem,
    frameShortSidePx: Int,
) : TextOverlay() {

    private val text: SpannableString = buildText(item, frameShortSidePx)
    private val startUs = item.startUs
    private val endUs = item.endUs
    private val x = item.positionX.coerceIn(-1f, 1f)
    private val y = item.positionY.coerceIn(-1f, 1f)
    private val animation = item.animation

    override fun getText(presentationTimeUs: Long): SpannableString = text

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val animUs = 280_000f
        val inP = ((presentationTimeUs - startUs) / animUs).coerceIn(0f, 1f)
        val outP = ((endUs - presentationTimeUs) / animUs).coerceIn(0f, 1f)
        val visible = presentationTimeUs in startUs until endUs
        val edge = minOf(inP, outP)
        var alpha = if (visible) 1f else 0f
        var scale = 1f
        var shiftY = 0f
        when (animation) {
            TextAnimation.NONE -> Unit
            TextAnimation.FADE -> alpha *= edge
            TextAnimation.SLIDE -> {
                alpha *= edge
                val eased = 1f - (1f - inP) * (1f - inP)
                shiftY = -(1f - eased) * 0.18f
            }
            TextAnimation.POP -> {
                alpha *= minOf(1f, edge * 2f)
                // Grows past full size, then settles.
                scale = 0.6f + 0.4f * inP + 0.18f * kotlin.math.sin(Math.PI.toFloat() * inP)
            }
        }
        return StaticOverlaySettings.Builder()
            .setAlphaScale(alpha)
            .setScale(scale, scale)
            .setOverlayFrameAnchor(x, y)
            .setBackgroundFrameAnchor(x * 0.92f, (y * 0.9f + shiftY).coerceIn(-1f, 1f))
            .build()
    }

    private companion object {
        fun buildText(item: TextItem, frameShortSidePx: Int): SpannableString {
            val content = item.text.replace('\n', ' ').ifBlank { " " }
            val padded = if (item.style == TextStyle.BOX) " $content " else content
            val span = SpannableString(padded)
            val all = 0 to padded.length
            fun addSpan(what: Any) = span.setSpan(what, all.first, all.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val sizePx = (item.sizeFraction * frameShortSidePx).toInt().coerceAtLeast(12)
            addSpan(AbsoluteSizeSpan(sizePx))
            addSpan(ForegroundColorSpan(item.colorArgb))
            if (item.bold) addSpan(StyleSpan(Typeface.BOLD))
            when (item.style) {
                TextStyle.PLAIN -> Unit
                TextStyle.SHADOW -> addSpan(ShadowSpan(radius = sizePx * 0.08f, offset = sizePx * 0.04f))
                TextStyle.BOX -> addSpan(BackgroundColorSpan(0x99000000.toInt()))
            }
            return span
        }
    }
}

/** Soft drop shadow so captions stay readable on bright footage. */
private class ShadowSpan(private val radius: Float, private val offset: Float) :
    CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.setShadowLayer(radius, offset, offset, 0xCC000000.toInt())
    }
}

/**
 * Scales loudness of every channel. Each media item needs its own instance.
 */
@OptIn(UnstableApi::class)
fun volumeProcessor(volume: Float): AudioProcessor =
    androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
        for (channels in 1..8) {
            val diagonal = FloatArray(channels * channels) { i ->
                if (i / channels == i % channels) volume else 0f
            }
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix(channels, channels, diagonal))
        }
    }

/**
 * Fades the final mix in at the start and out at the end of the video. Used as a
 * composition-level processor, so positions are on the final timeline.
 */
@OptIn(UnstableApi::class)
class FadeAudioProcessor(
    private val totalDurationUs: Long,
    private val fadeUs: Long,
) : BaseAudioProcessor() {

    private var framesProcessed = 0L
    private var startOffsetUs = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET // Leave other formats untouched.
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        startOffsetUs = streamMetadata.positionOffsetUs
        framesProcessed = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * format.channelCount
        val frames = remaining / frameSize
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        for (frame in 0 until frames) {
            val timeUs = startOffsetUs + (framesProcessed + frame) * 1_000_000L / format.sampleRate
            val gain = gainAt(timeUs)
            for (ch in 0 until format.channelCount) {
                if (isFloat) {
                    output.putFloat(input.getFloat() * gain)
                } else {
                    output.putShort((input.getShort() * gain).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        // Copy any trailing partial frame unchanged.
        while (input.hasRemaining()) output.put(input.get())
        framesProcessed += frames
        output.flip()
    }

    private fun gainAt(timeUs: Long): Float {
        if (fadeUs <= 0) return 1f
        val fadeIn = timeUs.toFloat() / fadeUs
        val fadeOut = (totalDurationUs - timeUs).toFloat() / fadeUs
        return minOf(1f, fadeIn, fadeOut).coerceIn(0f, 1f)
    }
}
