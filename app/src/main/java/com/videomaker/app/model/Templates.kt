package com.videomaker.app.model

/**
 * One-tap styles. A template restyles every clip in the project — transitions, effect, filter,
 * framing and fades — and can be undone in one step. Your clips, trims, text, music and overlay
 * are left as they are.
 */
enum class Template(val label: String, val description: String) {
    SMOOTH_STORY("Smooth story", "Soft crossfades, warm colours, gentle zoom, fade in and out."),
    HYPE("Hype reel", "Quick cuts with slides and zooms, vivid colours and a beat zoom."),
    GLITCH("Glitch", "Glitch and RGB-split effects with fast zoom and spin cuts."),
    RETRO("Retro VHS", "VHS look with vintage colours and dips to black."),
    CINEMATIC("Cinematic", "Moody colours, vignette, long crossfades and slow zoom."),
    TRAVEL("Travel vlog", "Bright colours, sliding cuts and blurred background for mixed shapes."),
    NOIR("Black & white", "Classic monochrome with crossfades and a vignette."),
    PARTY("Party", "Flashes, a different transition on every cut and punchy colours."),
    CLEAN("Clean (reset)", "Removes all transitions, effects, filters and fades.");

    fun applyTo(doc: EditorDocument): EditorDocument {
        val clips = doc.clips.mapIndexed { index, clip -> style(reset(clip), index) }
        return doc.copy(clips = clips, transitions = fades())
    }

    private fun reset(clip: MediaClip) = clip.copy(
        transition = TransitionType.NONE,
        transitionUs = 500_000L,
        effect = ClipEffect.NONE,
        effectStrength = 0.6f,
        filter = FilterPreset.NONE,
        filterStrength = 1f,
        adjust = ColorAdjust(),
        slowZoom = false,
    )

    private fun style(clip: MediaClip, index: Int): MediaClip = when (this) {
        SMOOTH_STORY -> clip.copy(
            transition = TransitionType.FADE, transitionUs = 600_000L,
            filter = FilterPreset.WARM, filterStrength = 0.7f,
            slowZoom = clip.type == ClipType.IMAGE,
        ).photoSeconds(3f)

        HYPE -> clip.copy(
            transition = cycle(index, TransitionType.SLIDE_LEFT, TransitionType.ZOOM, TransitionType.SLIDE_UP, TransitionType.ZOOM),
            transitionUs = 300_000L,
            filter = FilterPreset.VIVID,
            effect = ClipEffect.PULSE, effectStrength = 0.5f,
        ).photoSeconds(1.5f)

        GLITCH -> clip.copy(
            transition = cycle(index, TransitionType.ZOOM, TransitionType.SPIN),
            transitionUs = 300_000L,
            filter = FilterPreset.DRAMA, filterStrength = 0.8f,
            effect = cycle(index, ClipEffect.GLITCH, ClipEffect.RGB_SPLIT), effectStrength = 0.6f,
        ).photoSeconds(2f)

        RETRO -> clip.copy(
            transition = TransitionType.DIP_BLACK, transitionUs = 600_000L,
            filter = FilterPreset.VINTAGE,
            effect = ClipEffect.VHS, effectStrength = 0.6f,
        ).photoSeconds(3f)

        CINEMATIC -> clip.copy(
            transition = TransitionType.FADE, transitionUs = 900_000L,
            filter = FilterPreset.MATTE,
            adjust = ColorAdjust(contrast = 0.15f, saturation = -0.1f),
            effect = ClipEffect.VIGNETTE, effectStrength = 0.5f,
            slowZoom = true,
        ).photoSeconds(4f)

        TRAVEL -> clip.copy(
            transition = cycle(index, TransitionType.SLIDE_LEFT, TransitionType.SLIDE_UP, TransitionType.SLIDE_RIGHT, TransitionType.SLIDE_DOWN),
            transitionUs = 500_000L,
            filter = FilterPreset.VIVID, filterStrength = 0.8f,
            fit = FitMode.BLUR,
            slowZoom = clip.type == ClipType.IMAGE,
        ).photoSeconds(2.5f)

        NOIR -> clip.copy(
            transition = TransitionType.FADE, transitionUs = 700_000L,
            filter = FilterPreset.NOIR,
            effect = ClipEffect.VIGNETTE, effectStrength = 0.6f,
        ).photoSeconds(3f)

        PARTY -> clip.copy(
            transition = TransitionType.entries.filter { it.overlaps }.let { it[index % it.size] },
            transitionUs = 400_000L,
            filter = FilterPreset.VIVID,
            effect = cycle(index, ClipEffect.FLASH, ClipEffect.PULSE, ClipEffect.SHAKE), effectStrength = 0.6f,
        ).photoSeconds(1.5f)

        CLEAN -> clip
    }

    private fun fades(): Transitions = when (this) {
        SMOOTH_STORY, CINEMATIC, NOIR, RETRO ->
            Transitions(durationUs = 800_000L, fadeInAtStart = true, fadeOutAtEnd = true, fadeAudio = true)
        TRAVEL -> Transitions(fadeOutAtEnd = true, fadeAudio = true)
        HYPE, GLITCH, PARTY, CLEAN -> Transitions()
    }

    private fun <T> cycle(index: Int, vararg options: T): T = options[index % options.size]

    private fun MediaClip.photoSeconds(seconds: Float): MediaClip =
        if (type == ClipType.IMAGE) copy(imageDurationUs = (seconds * 1_000_000).toLong()) else this
}
