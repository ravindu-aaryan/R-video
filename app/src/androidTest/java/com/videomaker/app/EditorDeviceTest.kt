package com.videomaker.app

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videomaker.app.model.AspectRatio
import com.videomaker.app.model.EditorDocument
import com.videomaker.app.model.OutputSettings
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs real previews and exports on a device/emulator. */
@RunWith(AndroidJUnit4::class)
class EditorDeviceTest {

    private val portrait = OutputSettings(aspectRatio = AspectRatio.PORTRAIT)

    private fun photosDoc() = EditorDocument(
        clips = listOf(
            Harness.photo("one", 1200, 1600, Color.rgb(200, 60, 60)),
            Harness.photo("two", 1600, 1200, Color.rgb(60, 120, 200)),
            Harness.photo("three", 1000, 1000, Color.rgb(60, 180, 90)),
        ),
        output = portrait,
    )

    @Test
    fun photosOnly_preview() {
        assertNull(Harness.preview(photosDoc()))
    }

    @Test
    fun photosOnly_export() {
        Harness.export(photosDoc(), "photos")
    }

    @Test
    fun videoAndPhoto_preview() {
        val video = Harness.video(Harness.export(photosDoc(), "source"))
        val doc = EditorDocument(
            clips = listOf(video, Harness.photo("after", 1200, 1600, Color.DKGRAY)),
            output = portrait,
        )
        assertNull(Harness.preview(doc))
    }
}
