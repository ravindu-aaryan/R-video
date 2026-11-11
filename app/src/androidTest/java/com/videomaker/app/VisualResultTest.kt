package com.videomaker.app

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videomaker.app.model.AspectRatio
import com.videomaker.app.model.ClipEffect
import com.videomaker.app.model.ColorAdjust
import com.videomaker.app.model.EditorDocument
import com.videomaker.app.model.FilterPreset
import com.videomaker.app.model.MediaClip
import com.videomaker.app.model.OutputSettings
import com.videomaker.app.model.OverlayLayer
import com.videomaker.app.model.Resolution
import com.videomaker.app.model.Template
import com.videomaker.app.model.TextItem
import com.videomaker.app.model.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Checks that edits really change the exported picture the way they should, by exporting small
 * test projects and measuring colours in the resulting video frames.
 */
@RunWith(AndroidJUnit4::class)
class VisualResultTest {

    private val square = OutputSettings(aspectRatio = AspectRatio.SQUARE, resolution = Resolution.HD)

    private fun export(name: String, vararg clips: MediaClip, edit: (EditorDocument) -> EditorDocument = { it }): File =
        Harness.export(edit(EditorDocument(clips = clips.toList(), output = square)), name)

    private fun rgb(file: File, seconds: Float, l: Float = 0f, t: Float = 0f, r: Float = 1f, b: Float = 1f): FloatArray =
        Harness.average(Harness.frame(file, seconds), l, t, r, b)

    private fun describe(c: FloatArray) = "rgb(${c[0].toInt()}, ${c[1].toInt()}, ${c[2].toInt()})"

    private fun assertRed(what: String, c: FloatArray) =
        assertTrue("$what should be red, was ${describe(c)}", c[0] > 150 && c[1] < 90 && c[2] < 90)

    private fun assertBlue(what: String, c: FloatArray) =
        assertTrue("$what should be blue, was ${describe(c)}", c[2] > 150 && c[0] < 90 && c[1] < 90)

    private fun assertGreen(what: String, c: FloatArray) =
        assertTrue("$what should be green, was ${describe(c)}", c[1] > 150 && c[0] < 110 && c[2] < 110)

    private fun red() = Harness.solid("red", Color.RED)
    private fun blue() = Harness.solid("blue", Color.BLUE)

    // ---- Transitions -----------------------------------------------------------------------------

    @Test
    fun hardCut_switchesAtTheCut() {
        val file = export("cut", red(), blue())
        assertRed("before the cut", rgb(file, 1.5f))
        assertBlue("after the cut", rgb(file, 2.5f))
        assertEquals("length", 4f, Harness.durationSeconds(file), 0.25f)
    }

    @Test
    fun crossfade_blendsBothClips() {
        // 2 s clips with a 0.8 s crossfade: the second clip starts at 1.2 s, midpoint 1.6 s.
        val file = export("crossfade", red(), blue().copy(transition = TransitionType.FADE, transitionUs = 800_000))
        assertRed("start", rgb(file, 0.5f))
        val mid = rgb(file, 1.6f)
        assertTrue("middle should mix red and blue, was ${describe(mid)}", mid[0] in 50f..210f && mid[2] in 50f..210f)
        assertBlue("end", rgb(file, 2.6f))
        assertEquals("length is shorter by the overlap", 3.2f, Harness.durationSeconds(file), 0.25f)
    }

    @Test
    fun slideLeft_newClipEntersFromTheRight() {
        val file = export("slide", red(), blue().copy(transition = TransitionType.SLIDE_LEFT, transitionUs = 800_000))
        val frame = Harness.frame(file, 1.6f)
        assertRed("left side mid-slide", Harness.average(frame, 0f, 0f, 0.25f, 1f))
        assertBlue("right side mid-slide", Harness.average(frame, 0.75f, 0f, 1f, 1f))
        assertBlue("end", rgb(file, 2.6f))
    }

    @Test
    fun slideUp_newClipEntersFromTheBottom() {
        val file = export("slideup", red(), blue().copy(transition = TransitionType.SLIDE_UP, transitionUs = 800_000))
        val frame = Harness.frame(file, 1.6f)
        assertRed("top mid-slide", Harness.average(frame, 0f, 0f, 1f, 0.25f))
        assertBlue("bottom mid-slide", Harness.average(frame, 0f, 0.75f, 1f, 1f))
    }

    @Test
    fun zoomAndSpin_blendAndFinishOnTheNewClip() {
        for (type in listOf(TransitionType.ZOOM, TransitionType.SPIN)) {
            val file = export("t_$type", red(), blue().copy(transition = type, transitionUs = 800_000))
            assertRed("$type start", rgb(file, 0.5f))
            val mid = rgb(file, 1.6f)
            assertTrue("$type middle should show both clips, was ${describe(mid)}", mid[0] > 30 && mid[2] > 30)
            assertBlue("$type end", rgb(file, 2.6f))
        }
    }

    @Test
    fun dipToBlack_goesDarkAtTheCut() {
        val file = export("dip", red(), blue().copy(transition = TransitionType.DIP_BLACK, transitionUs = 1_000_000))
        assertRed("start", rgb(file, 0.5f))
        val atCut = rgb(file, 2.0f)
        assertTrue("should be dark at the cut, was ${describe(atCut)}", atCut.max() < 70)
        assertBlue("end", rgb(file, 3.5f))
    }

    // ---- Effects, filters, adjustments -----------------------------------------------------------

    @Test
    fun everyEffect_changesThePicture() {
        val times = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1.1f, 1.3f)
        val base = export("fx_base", Harness.pattern("pat"))
        val baseFrames = times.map { Harness.frame(base, it) }
        // Compression alone changes pixels slightly; a real effect must change far more.
        val failures = mutableListOf<String>()
        for (fx in ClipEffect.entries.filter { it != ClipEffect.NONE }) {
            val file = export("fx_$fx", Harness.pattern("pat").copy(effect = fx, effectStrength = 0.9f))
            val change = times.indices.maxOf { Harness.difference(baseFrames[it], Harness.frame(file, times[it])) }
            android.util.Log.i(Harness.TAG, "effect $fx changes picture by $change")
            if (change < 3f) failures += "$fx (change $change)"
        }
        assertTrue("Effects with no visible result: $failures", failures.isEmpty())
    }

    @Test
    fun everyFilter_changesTheColours() {
        val base = Harness.frame(export("filter_base", Harness.pattern("pat")), 1f)
        val failures = mutableListOf<String>()
        for (filter in FilterPreset.entries.filter { it != FilterPreset.NONE }) {
            val frame = Harness.frame(export("filter_$filter", Harness.pattern("pat").copy(filter = filter)), 1f)
            val change = Harness.difference(base, frame)
            android.util.Log.i(Harness.TAG, "filter $filter changes picture by $change")
            if (change < 3f) failures += "$filter (change $change)"
        }
        assertTrue("Filters with no visible result: $failures", failures.isEmpty())
    }

    @Test
    fun monoFilter_removesColour() {
        val c = rgb(export("mono", red().copy(filter = FilterPreset.MONO)), 1f)
        assertTrue("should be grey, was ${describe(c)}", kotlin.math.abs(c[0] - c[1]) < 25 && kotlin.math.abs(c[1] - c[2]) < 25)
    }

    @Test
    fun brightness_makesItBrighterAndDarker() {
        val grey = Harness.solid("grey", Color.rgb(128, 128, 128))
        val normal = rgb(export("b0", grey), 1f)[1]
        val brighter = rgb(export("b1", grey.copy(adjust = ColorAdjust(brightness = 0.8f))), 1f)[1]
        val darker = rgb(export("b2", grey.copy(adjust = ColorAdjust(brightness = -0.8f))), 1f)[1]
        assertTrue("brighter $brighter vs normal $normal", brighter > normal + 20)
        assertTrue("darker $darker vs normal $normal", darker < normal - 20)
    }

    // ---- Framing ---------------------------------------------------------------------------------

    @Test
    fun pattern_quartersAreWhereExpected() {
        val frame = Harness.frame(export("quarters", Harness.pattern("pat")), 1f)
        assertRed("top-left", Harness.average(frame, 0.1f, 0.1f, 0.4f, 0.4f))
        assertGreen("top-right", Harness.average(frame, 0.6f, 0.1f, 0.9f, 0.4f))
    }

    @Test
    fun rotate90_turnsClockwise() {
        // Clockwise: the top-left quarter (red) moves to the top-right.
        val frame = Harness.frame(export("rot", Harness.pattern("pat").copy(rotationDegrees = 90)), 1f)
        val topRight = Harness.average(frame, 0.6f, 0.1f, 0.9f, 0.4f)
        assertTrue("top-right should be red after rotating, was ${describe(topRight)}", topRight[0] > 150 && topRight[1] < 120 && topRight[2] < 120)
    }

    @Test
    fun flipHorizontal_mirrors() {
        val frame = Harness.frame(export("flip", Harness.pattern("pat").copy(flipHorizontal = true)), 1f)
        assertGreen("top-left after flipping", Harness.average(frame, 0.1f, 0.1f, 0.4f, 0.4f))
    }

    @Test
    fun zoom_enlargesAndOffsetMoves() {
        // Zoom 2× and push fully to one corner: one quarter should fill the frame.
        val frame = Harness.frame(
            export("zoom", Harness.pattern("pat").copy(zoom = 2f, offsetX = 1f, offsetY = -1f)), 1f,
        )
        val all = Harness.average(frame, 0.1f, 0.1f, 0.9f, 0.9f)
        val spread = all.max() - all.min()
        assertTrue("one quarter should fill the frame, was ${describe(all)}", spread > 120)
    }

    // ---- Timing, text, overlay -------------------------------------------------------------------

    @Test
    fun photoDuration_setsTheLength() {
        val file = export("dur", Harness.solid("d", Color.RED, seconds = 3.5f))
        assertEquals(3.5f, Harness.durationSeconds(file), 0.25f)
    }

    @Test
    fun trimAndSpeed_changeVideoLength() {
        val source = Harness.video(export("speed_src", Harness.solid("s1", Color.RED, seconds = 4f)))
        val trimmed = export("trimmed", source.copy(trimStartUs = 1_000_000, trimEndUs = 3_000_000))
        assertEquals("trimmed to 2 s", 2f, Harness.durationSeconds(trimmed), 0.3f)
        val fast = export("fast", source.copy(speed = 2f))
        assertEquals("4 s at 2× is 2 s", 2f, Harness.durationSeconds(fast), 0.3f)
        val slow = export("slow", source.copy(trimEndUs = 2_000_000, speed = 0.5f))
        assertEquals("2 s at 0.5× is 4 s", 4f, Harness.durationSeconds(slow), 0.4f)
    }

    @Test
    fun caption_showsOnlyDuringItsTime() {
        val text = TextItem(
            id = 1, text = "HELLO REEL", colorArgb = Color.WHITE, sizeFraction = 0.15f,
            positionX = 0f, positionY = 0f, startUs = 1_000_000, endUs = 2_500_000,
        )
        val file = export("caption", Harness.solid("bg", Color.BLUE, seconds = 4f)) { it.copy(texts = listOf(text)) }
        val before = Harness.average(Harness.frame(file, 0.4f), 0.1f, 0.4f, 0.9f, 0.6f)
        val during = Harness.average(Harness.frame(file, 1.8f), 0.1f, 0.4f, 0.9f, 0.6f)
        val after = Harness.average(Harness.frame(file, 3.4f), 0.1f, 0.4f, 0.9f, 0.6f)
        assertTrue("no text before, was ${describe(before)}", before[0] < 40)
        assertTrue("white text during, was ${describe(during)}", during[0] > before[0] + 15)
        assertTrue("no text after, was ${describe(after)}", after[0] < 40)
    }

    @Test
    fun overlay_isBlendedByOpacityInItsCorner() {
        val overlay = OverlayLayer(
            clip = Harness.solid("ov", Color.RED), opacity = 0.5f, widthFraction = 0.5f, positionX = 1f, positionY = 1f,
        )
        val file = export("overlay", Harness.solid("bg2", Color.BLUE, seconds = 3f)) { it.copy(overlay = overlay) }
        val frame = Harness.frame(file, 1.5f)
        val corner = Harness.average(frame, 0.65f, 0.1f, 0.9f, 0.35f)
        assertTrue("top-right should mix red and blue, was ${describe(corner)}", corner[0] > 60 && corner[2] > 60)
        assertBlue("bottom-left untouched", Harness.average(frame, 0.1f, 0.65f, 0.35f, 0.9f))
    }

    // ---- Templates -------------------------------------------------------------------------------

    @Test
    fun everyTemplate_exports() {
        val clips = listOf(
            Harness.pattern("tp1"), Harness.solid("tp2", Color.RED), Harness.pattern("tp3"), Harness.solid("tp4", Color.BLUE),
        )
        val plain = EditorDocument(clips = clips, output = square)
        for (template in Template.entries) {
            val styled = template.applyTo(plain)
            if (template != Template.CLEAN) assertTrue("$template should change the project", styled != plain)
            Harness.export(styled, "template_$template")
        }
        assertEquals("Clean undoes a template's styling", Template.CLEAN.applyTo(plain), Template.CLEAN.applyTo(Template.GLITCH.applyTo(plain)))
    }
}
