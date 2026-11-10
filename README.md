# Video Maker (Android)

An on-device video editor built with Kotlin, Jetpack Compose and Media3 (Transformer + CompositionPlayer).

## How it works for the user

1. **Add videos & photos.** The frame shape is picked from your first clip (portrait → 9:16).
2. **Watch the live preview.** Every edit shows up there within a moment, exactly as it will export.
3. **Tap a clip** in the timeline strip to edit it, or use the project tools when nothing is selected.
4. **Export.** The MP4 is saved to `Movies/VideoMaker`, plays back in the app and can be shared.

Undo/redo covers every edit, and quick actions (delete, split, duplicate) offer *Undo* in the snackbar.

## Features

**Clip tools** (tap a clip)

| Tool | What it does |
|---|---|
| Trim / Duration | Range slider, plus *Start / End at playhead* for frame-accurate cuts. Photos: 0.5–20 s. |
| Split | Cuts the clip under the playhead in two. |
| Speed | 0.25× to 4×, pitch preserved. |
| Volume | 0–200 %, mute. |
| Filters | 10 looks (Vivid, Warm, Cool, Vintage, Matte, Drama, Mono, Noir, Sepia…) with strength; thumbnails preview each look. *Apply to all clips*. |
| Adjust | Brightness, contrast, saturation, warmth. |
| Rotate | 90° steps, flip horizontal/vertical. |
| Fit | Fit (bars), Fill (crop) or Blur background. Photos: slow "Ken Burns" zoom. |
| Move, Duplicate, Delete | Reorder and manage clips. |

**Project tools** (nothing selected)

| Tool | What it does |
|---|---|
| Text | Titles/captions: colour, size, bold, plain/shadow/box style, 3×3 position grid + fine sliders, on-screen time range. |
| Music | Background track with volume, start offset within the song, loop to fit. |
| Overlay | Second video or image on top: opacity, size, position, start time, trim, optional sound. |
| Fades | Dip to black between clips, fade in at start / out at end, adjustable length, fade all sound in & out. |
| Format | 16:9, 9:16, 1:1, 4:5; 720p or 1080p; keep or drop original sound. |

## Build and run

1. Open the `VideoMaker` folder in Android Studio (Narwhal or newer) and let Gradle sync.
2. Run on a phone with Android 10 or later. A real device is strongly recommended — the emulator's
   video codecs are slow and the live preview may stutter there.
3. For an installable APK: *Build ▸ Generate App Bundles or APKs ▸ Generate APKs*.

Toolchain: AGP 8.13, Kotlin 2.2.20, Gradle 8.14.3, compileSdk/targetSdk 36, minSdk 29,
Media3 1.11.1, Compose BOM 2026.05.00. Versions live in `gradle/libs.versions.toml`.

## Architecture

```
app/src/main/java/com/videomaker/app/
├── MainActivity.kt
├── model/
│   ├── EditorModels.kt        EditorDocument (what undo snapshots), clips, text, overlay, music, UI state
│   └── ColorGrading.kt        filters + adjustments as one colour matrix (shared by export and thumbnails)
├── media/
│   ├── CompositionFactory.kt  document → Media3 Composition (used for preview AND export)
│   ├── CustomEffects.kt       colour/fade GPU effect, slow zoom, captions, audio fade, volume
│   ├── PreviewController.kt   CompositionPlayer for the live preview
│   ├── MediaInspector.kt      size, length, rotation, thumbnails
│   └── VideoExporter.kt       Transformer export + save to gallery
└── ui/
    ├── EditorViewModel.kt     edits, undo/redo, selection, preview refresh, export
    ├── EditorScreen.kt        preview, timeline strip, tool panel, tool bar
    └── components/            panels, tool bars, timeline, preview, dialogs
```

Composition layout: sequence 0 is the overlay (Media3 draws the first video sequence on top,
padded with gaps and hidden outside its window), then the main timeline, then music. Captions
and the sound fade are composition-level effects, so they sit on top of everything.

Time-based effects (fades, slow zoom, captions) use the frame's position on the final timeline,
which is what Media3 passes to effects during export.

## Notes

- `CompositionPlayer` is still marked experimental in Media3; the preview is rebuilt ~0.35 s after
  you stop editing. If a device can't preview a particular edit, a message says so and export still
  works.
- Export runs while the app is open; very long projects could move export into a foreground service.
- Ideas for later: crossfade transitions (overlapping clips), stickers, voice-over recording,
  keyframed overlay motion, saving projects as drafts.
