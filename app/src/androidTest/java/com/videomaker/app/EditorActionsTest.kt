package com.videomaker.app

import android.app.Application
import android.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videomaker.app.model.ClipEffect
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.EditorUiState
import com.videomaker.app.model.FilterPreset
import com.videomaker.app.model.Template
import com.videomaker.app.model.Tool
import com.videomaker.app.model.TransitionType
import com.videomaker.app.ui.EditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the editor the way the buttons do and checks each action changes the project correctly. */
@RunWith(AndroidJUnit4::class)
class EditorActionsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(what: String, vm: EditorViewModel, condition: (EditorUiState) -> Boolean): EditorUiState {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val state = onMain { vm.uiState.value }
            if (condition(state)) return state
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    /** A view model with three imported photos, like a user who just picked them. */
    private fun editorWithThreePhotos(): EditorViewModel {
        val uris = listOf(
            Harness.photo("a1", 900, 1600, Color.RED),
            Harness.photo("a2", 900, 1600, Color.GREEN),
            Harness.photo("a3", 900, 1600, Color.BLUE),
        ).map { it.uri }
        val vm = onMain { EditorViewModel(Harness.context.applicationContext as Application) }
        Thread.sleep(500) // Let start-up clean-up finish before importing.
        onMain { vm.addTimelineMedia(uris) }
        waitFor("photos imported", vm) { it.doc.clips.size == 3 }
        return vm
    }

    private fun state(vm: EditorViewModel) = onMain { vm.uiState.value }

    @Test
    fun appStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    @Test
    fun importingPhotos_setsUpTheProject() {
        val vm = editorWithThreePhotos()
        val s = state(vm)
        assertTrue(s.doc.clips.all { it.type == ClipType.IMAGE })
        assertEquals("portrait photos pick 9:16", "9:16", s.doc.output.aspectRatio.label)
        assertEquals("templates are offered first", Tool.TEMPLATES, s.activeTool)
        assertEquals(9_000_000L, s.doc.totalDurationUs)
        val ready = waitFor("preview settles", vm) { true }
        Thread.sleep(2500)
        assertNull("preview error", state(vm).previewError ?: ready.previewError)
    }

    @Test
    fun clipTools_changeOnlyTheSelectedClip() {
        val vm = editorWithThreePhotos()
        val second = state(vm).doc.clips[1].id
        onMain {
            vm.selectClip(second)
            vm.openTool(Tool.EFFECTS)
            vm.updateSelectedClip("effect") { it.copy(effect = ClipEffect.GLITCH) }
            vm.updateSelectedClip("filter") { it.copy(filter = FilterPreset.NOIR) }
            vm.updateSelectedClip("transition") { it.copy(transition = TransitionType.FADE) }
        }
        val s = state(vm)
        assertEquals(Tool.EFFECTS, s.activeTool)
        assertEquals(ClipEffect.GLITCH, s.doc.clips[1].effect)
        assertEquals(FilterPreset.NOIR, s.doc.clips[1].filter)
        assertEquals(TransitionType.FADE, s.doc.clips[1].transition)
        assertEquals(ClipEffect.NONE, s.doc.clips[0].effect)
        assertEquals(ClipEffect.NONE, s.doc.clips[2].effect)
        assertTrue("crossfade shortens the video", s.doc.totalDurationUs < 9_000_000L)
    }

    @Test
    fun applyToAll_copiesLookAndTransition() {
        val vm = editorWithThreePhotos()
        val second = state(vm).doc.clips[1].id
        onMain {
            vm.selectClip(second)
            vm.updateSelectedClip("filter") { it.copy(filter = FilterPreset.VIVID, filterStrength = 0.5f) }
            vm.applyLookToAll()
            vm.updateSelectedClip("transition") { it.copy(transition = TransitionType.SLIDE_LEFT) }
            vm.applyTransitionToAll()
        }
        var s = state(vm)
        assertTrue(s.doc.clips.all { it.filter == FilterPreset.VIVID && it.filterStrength == 0.5f })
        assertTrue(s.doc.clips.all { it.transition == TransitionType.SLIDE_LEFT })
        onMain { vm.shuffleTransitions() }
        s = state(vm)
        assertTrue("every cut has a transition", s.doc.clips.drop(1).all { it.transition != TransitionType.NONE })
        assertNotEquals(s.doc.clips[1].transition, s.doc.clips[2].transition)
    }

    @Test
    fun undoRedo_stepThroughEdits() {
        val vm = editorWithThreePhotos()
        val before = state(vm).doc
        onMain { vm.applyTemplate(Template.GLITCH) }
        val styled = state(vm).doc
        assertNotEquals(before, styled)
        assertTrue(state(vm).canUndo)
        onMain { vm.undo() }
        assertEquals(before, state(vm).doc)
        assertTrue(state(vm).canRedo)
        onMain { vm.redo() }
        assertEquals(styled, state(vm).doc)
    }

    @Test
    fun everyTemplate_stylesAllClips() {
        val vm = editorWithThreePhotos()
        for (template in Template.entries.filter { it != Template.CLEAN }) {
            onMain { vm.applyTemplate(template) }
            val clips = state(vm).doc.clips
            assertTrue("$template adds transitions", clips.drop(1).all { it.transition != TransitionType.NONE })
            assertTrue("$template changes the look", clips.all { it.filter != FilterPreset.NONE })
        }
        onMain { vm.applyTemplate(Template.CLEAN) }
        val clean = state(vm).doc.clips
        assertTrue(clean.all { it.transition == TransitionType.NONE && it.effect == ClipEffect.NONE && it.filter == FilterPreset.NONE })
    }

    @Test
    fun splitDuplicateMoveDelete() {
        val vm = editorWithThreePhotos()
        val ids = state(vm).doc.clips.map { it.id }

        // Split the first clip at 1 s.
        onMain {
            vm.seekTo(1_000_000)
            vm.splitAtPlayhead()
        }
        var s = state(vm)
        assertEquals(4, s.doc.clips.size)
        assertEquals(1_000_000L, s.doc.clips[0].imageDurationUs)
        assertEquals(2_000_000L, s.doc.clips[1].imageDurationUs)
        assertEquals("length is unchanged by a split", 9_000_000L, s.doc.totalDurationUs)

        // Duplicate the last clip.
        onMain { vm.selectClip(ids[2]); vm.duplicateSelectedClip() }
        s = state(vm)
        assertEquals(5, s.doc.clips.size)
        assertEquals(s.doc.clips[3].uri, s.doc.clips[4].uri)

        // Move it one place left, then delete it.
        onMain { vm.moveSelectedClip(-1) }
        s = state(vm)
        assertEquals(ids[2], s.doc.clips[2].id)
        onMain { vm.deleteSelectedClip() }
        s = state(vm)
        assertEquals(4, s.doc.clips.size)
        assertFalse(s.doc.clips.any { it.id == ids[2] })
        assertNull(s.selectedClipId)
    }

    @Test
    fun textAndFades() {
        val vm = editorWithThreePhotos()
        onMain {
            vm.addText()
            vm.updateSelectedText("text") { it.copy(text = "Hi") }
            vm.updateTransitions("in") { it.copy(fadeInAtStart = true) }
        }
        var s = state(vm)
        assertEquals("Hi", s.doc.texts.single().text)
        assertTrue(s.doc.transitions.fadeInAtStart)
        onMain { vm.deleteSelectedText() }
        s = state(vm)
        assertTrue(s.doc.texts.isEmpty())
    }
}
