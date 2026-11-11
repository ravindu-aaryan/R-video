package com.videomaker.app

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videomaker.app.model.AspectRatio
import com.videomaker.app.model.ClipEffect
import com.videomaker.app.model.EditorDocument
import com.videomaker.app.model.FilterPreset
import com.videomaker.app.model.FitMode
import com.videomaker.app.model.MediaClip
import com.videomaker.app.model.OutputSettings
import com.videomaker.app.model.OverlayLayer
import com.videomaker.app.model.Resolution
import com.videomaker.app.model.TextAnimation
import com.videomaker.app.model.TextItem
import com.videomaker.app.model.TransitionType
import com.videomaker.app.model.Transitions
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs real previews and exports on a device/emulator, the same way the app does. */
@RunWith(AndroidJUnit4::class)
class EditorDeviceTest {

    private val portrait = OutputSettings(aspectRatio = AspectRatio.PORTRAIT, resolution = Resolution.HD)

    private fun photos(): List<MediaClip> = listOf(
        Harness.photo("one", 1200, 1600, Color.rgb(200, 60, 60)),
        Harness.photo("two", 1600, 1200, Color.rgb(60, 120, 200)),
        Harness.photo("three", 1000, 1000, Color.rgb(60, 180, 90)),
    )

    private fun photosDoc() = EditorDocument(clips = photos(), output = portrait)

    /** A short real video made by exporting photos. */
    private fun sourceVideo(name: String): MediaClip = Harness.video(Harness.export(photosDoc(), name))

    private fun check(doc: EditorDocument, name: String) {
        assertNull("preview of $name", Harness.preview(doc))
        Harness.export(doc, name)
    }

    @Test
    fun photosOnly() = check(photosDoc(), "photos")

    @Test
    fun videoAndPhoto() {
        val doc = EditorDocument(
            clips = listOf(sourceVideo("src1"), Harness.photo("after", 1200, 1600, Color.DKGRAY)),
            output = portrait,
        )
        check(doc, "video_photo")
    }

    @Test
    fun everyTransition() {
        val types = TransitionType.entries.filter { it != TransitionType.NONE }
        val colors = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.MAGENTA, Color.CYAN, Color.YELLOW, Color.GRAY, Color.DKGRAY, Color.LTGRAY)
        val clips = (0..types.size).map { i ->
            Harness.photo("t$i", 900, 1600, colors[i % colors.size], seconds = 1.5f)
                .copy(transition = if (i == 0) TransitionType.NONE else types[i - 1])
        }
        check(EditorDocument(clips = clips, output = portrait), "transitions")
    }

    @Test
    fun videoTransitionsWithSpeed() {
        val video = sourceVideo("src2")
        val clips = listOf(
            video.copy(trimEndUs = 3_000_000),
            video.copy(id = 9001, trimStartUs = 2_000_000, speed = 2f, transition = TransitionType.FADE),
            Harness.photo("p", 1200, 1600, Color.BLUE).copy(transition = TransitionType.SLIDE_LEFT),
            video.copy(id = 9002, trimEndUs = 2_000_000, transition = TransitionType.ZOOM),
        )
        check(EditorDocument(clips = clips, output = portrait), "video_transitions")
    }

    @Test
    fun everyEffect() {
        val effects = ClipEffect.entries.filter { it != ClipEffect.NONE }
        val clips = effects.mapIndexed { i, fx ->
            Harness.photo("fx$i", 900, 1600, Color.rgb(40 + i * 15, 120, 200 - i * 10), seconds = 1f)
                .copy(effect = fx, effectStrength = 0.8f)
        }
        check(EditorDocument(clips = clips, output = portrait), "effects")
    }

    @Test
    fun looksFramingTextAndFades() {
        val clips = listOf(
            Harness.photo("a", 1600, 900, Color.RED).copy(
                filter = FilterPreset.VINTAGE, fit = FitMode.BLUR, slowZoom = true,
            ),
            Harness.photo("b", 1600, 900, Color.BLUE).copy(
                fit = FitMode.FILL, zoom = 1.8f, offsetX = 0.5f, rotationDegrees = 90, flipHorizontal = true,
                transition = TransitionType.DIP_BLACK,
            ),
        )
        val texts = TextAnimation.entries.mapIndexed { i, anim ->
            TextItem(id = 100L + i, text = "Hello $i 🔥", animation = anim, startUs = i * 800_000L, endUs = i * 800_000L + 1_500_000L, positionY = -0.8f + i * 0.4f)
        }
        val doc = EditorDocument(
            clips = clips,
            texts = texts,
            transitions = Transitions(fadeInAtStart = true, fadeOutAtEnd = true, fadeAudio = true),
            output = portrait,
        )
        check(doc, "looks")
    }

    @Test
    fun overlayWithTransitions() {
        val video = sourceVideo("src3")
        val clips = photos().mapIndexed { i, c -> if (i == 0) c else c.copy(transition = TransitionType.FADE) }
        val doc = EditorDocument(
            clips = clips,
            overlay = OverlayLayer(clip = video, opacity = 0.5f, startUs = 500_000),
            output = portrait,
        )
        check(doc, "overlay")
    }
}
