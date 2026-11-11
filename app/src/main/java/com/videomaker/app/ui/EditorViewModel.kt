package com.videomaker.app.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.transformer.ExportException
import com.videomaker.app.media.CompositionFactory
import com.videomaker.app.media.MediaInspector
import com.videomaker.app.media.PreviewController
import com.videomaker.app.media.VideoExporter
import com.videomaker.app.model.AspectRatio
import com.videomaker.app.model.AudioLayer
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.EditorDocument
import com.videomaker.app.model.EditorUiState
import com.videomaker.app.model.ExportState
import com.videomaker.app.model.Ids
import com.videomaker.app.model.MediaClip
import com.videomaker.app.model.OutputSettings
import com.videomaker.app.model.OverlayLayer
import com.videomaker.app.model.PlaybackState
import com.videomaker.app.model.Template
import com.videomaker.app.model.TextItem
import com.videomaker.app.model.Tool
import com.videomaker.app.model.TransitionType
import com.videomaker.app.model.Transitions
import com.videomaker.app.model.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val inspector = MediaInspector(application)
    private val exporter = VideoExporter(application)
    private val preview = PreviewController(application)

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<Long, ImageBitmap>> = _thumbnails.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    val previewPlayer: StateFlow<Player?> = preview.player

    private var exportJob: Job? = null

    // ---- Undo / redo -----------------------------------------------------------------------------

    private val undoStack = ArrayDeque<EditorDocument>()
    private val redoStack = ArrayDeque<EditorDocument>()
    private var lastEditKey: String? = null
    private var lastEditTime = 0L

    /**
     * Applies an edit to the document and records it for undo. Rapid edits with the same [key]
     * (e.g. dragging a slider) are merged into one undo step.
     */
    private fun edit(key: String? = null, transform: (EditorDocument) -> EditorDocument) {
        val before = _uiState.value.doc
        val after = transform(before)
        if (after == before) return
        val now = SystemClock.elapsedRealtime()
        val merge = key != null && key == lastEditKey && now - lastEditTime < 1_500
        if (!merge) {
            undoStack.addLast(before)
            if (undoStack.size > 60) undoStack.removeFirst()
            redoStack.clear()
        }
        lastEditKey = key
        lastEditTime = now
        _uiState.update { it.copy(doc = after).withHistoryFlags().withValidSelection() }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_uiState.value.doc)
        lastEditKey = null
        _uiState.update { it.copy(doc = previous).withHistoryFlags().withValidSelection() }
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_uiState.value.doc)
        lastEditKey = null
        _uiState.update { it.copy(doc = next).withHistoryFlags().withValidSelection() }
    }

    private fun EditorUiState.withHistoryFlags() =
        copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())

    private fun EditorUiState.withValidSelection(): EditorUiState {
        val clipGone = selectedClipId != null && doc.clips.none { it.id == selectedClipId }
        val textGone = selectedTextId != null && doc.texts.none { it.id == selectedTextId }
        return copy(
            selectedClipId = if (clipGone) null else selectedClipId,
            selectedTextId = if (textGone) null else selectedTextId,
            activeTool = if (clipGone && activeTool?.isClipTool == true) null else activeTool,
        )
    }

    init {
        preview.onError = { msg -> _uiState.update { it.copy(previewError = msg) } }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { inspector.clearImportedCopies() }

        // Rebuild the preview shortly after the user stops changing things.
        viewModelScope.launch {
            _uiState.map { it.doc }.distinctUntilChanged().collectLatest { doc ->
                delay(350)
                refreshPreview(doc)
            }
        }
        // Track the playhead.
        viewModelScope.launch {
            while (true) {
                val next = PlaybackState(preview.positionUs, preview.isPlaying)
                if (next != _playback.value) _playback.value = next
                delay(if (next.isPlaying) 50 else 200)
            }
        }
    }

    private fun refreshPreview(doc: EditorDocument) {
        if (_uiState.value.export is ExportState.Running) return
        _uiState.update { it.copy(previewError = null) }
        if (doc.clips.isEmpty()) {
            preview.clear()
            return
        }
        try {
            preview.show(CompositionFactory.build(doc), doc.totalDurationUs)
        } catch (e: Exception) {
            preview.clear()
            _uiState.update { it.copy(previewError = "Preview unavailable: ${e.message}") }
        }
    }

    // ---- Playback ------------------------------------------------------------------------------

    fun togglePlay() = preview.togglePlay()

    fun seekTo(positionUs: Long) {
        preview.seekTo(positionUs)
        _playback.update { it.copy(positionUs = positionUs) }
    }

    // ---- Selection & tools -----------------------------------------------------------------------

    fun selectClip(id: Long?) {
        val state = _uiState.value
        if (id != null) {
            val index = state.doc.clips.indexOfFirst { it.id == id }
            if (index >= 0 && state.selectedClipId != id) {
                // Jump to the clip, slightly inside so its first frame shows.
                seekTo(state.doc.clipStartsUs()[index] + 50_000)
            }
        }
        _uiState.update {
            it.copy(
                selectedClipId = id,
                activeTool = when {
                    id == null -> null
                    it.activeTool?.isClipTool == true -> it.activeTool
                    else -> null
                },
            )
        }
    }

    fun openTool(tool: Tool?) = _uiState.update {
        it.copy(activeTool = if (it.activeTool == tool) null else tool)
    }

    // ---- Media ---------------------------------------------------------------------------------

    fun addTimelineMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMedia = true) }
            val clips = uris.mapNotNull { inspector.inspectVisual(it) }
            val skipped = uris.size - clips.size
            _uiState.update { it.copy(isLoadingMedia = false) }
            if (skipped > 0) showMessage("$skipped file(s) couldn't be read and were skipped")
            if (clips.isEmpty()) return@launch
            val isFirst = _uiState.value.doc.clips.isEmpty()
            edit { doc ->
                var output = doc.output
                if (isFirst) {
                    // Match the frame shape to the first clip (portrait phone videos → 9:16).
                    val first = clips.first()
                    output = output.copy(
                        aspectRatio = when {
                            first.aspectRatio < 0.8f -> AspectRatio.PORTRAIT
                            first.aspectRatio < 1.2f -> AspectRatio.SQUARE
                            else -> AspectRatio.LANDSCAPE
                        },
                    )
                }
                doc.copy(clips = doc.clips + clips, output = output)
            }
            // Point new projects at the one-tap templates.
            if (isFirst) _uiState.update { it.copy(activeTool = Tool.TEMPLATES) }
            clips.forEach { loadThumbnail(it) }
        }
    }

    // ---- Clip edits ------------------------------------------------------------------------------

    /** Changes the selected clip. [key] groups slider drags into one undo step. */
    fun updateSelectedClip(key: String, transform: (MediaClip) -> MediaClip) {
        val id = _uiState.value.selectedClipId ?: return
        edit("$key:$id") { doc -> doc.copy(clips = doc.clips.map { if (it.id == id) transform(it) else it }) }
    }

    /** Restyles the whole project with a one-tap template. */
    fun applyTemplate(template: Template) {
        edit { template.applyTo(it) }
        showMessage("${template.label} applied", canUndo = true)
    }

    /** Uses the selected clip's transition on every cut. */
    fun applyTransitionToAll() {
        val source = _uiState.value.selectedClip ?: return
        edit { doc ->
            doc.copy(clips = doc.clips.map { it.copy(transition = source.transition, transitionUs = source.transitionUs) })
        }
        showMessage("Transition applied to every cut", canUndo = true)
    }

    /** Gives every cut a different transition. */
    fun shuffleTransitions() {
        val choices = TransitionType.entries.filter { it != TransitionType.NONE }
        edit { doc ->
            doc.copy(clips = doc.clips.mapIndexed { i, clip -> clip.copy(transition = choices[(i * 3 + 1) % choices.size]) })
        }
        showMessage("Transitions mixed across all cuts", canUndo = true)
    }

    /** Copies the selected clip's look (filter + adjustments) to every clip. */
    fun applyLookToAll() {
        val source = _uiState.value.selectedClip ?: return
        edit { doc ->
            doc.copy(clips = doc.clips.map {
                it.copy(filter = source.filter, filterStrength = source.filterStrength, adjust = source.adjust)
            })
        }
        showMessage("Look applied to all clips", canUndo = true)
    }

    fun moveSelectedClip(offset: Int) {
        val id = _uiState.value.selectedClipId ?: return
        edit { doc ->
            val list = doc.clips.toMutableList()
            val from = list.indexOfFirst { it.id == id }
            val to = from + offset
            if (from < 0 || to !in list.indices) return@edit doc
            list.add(to, list.removeAt(from))
            doc.copy(clips = list)
        }
    }

    fun deleteSelectedClip() {
        val id = _uiState.value.selectedClipId ?: return
        edit { doc -> doc.copy(clips = doc.clips.filterNot { it.id == id }) }
        _uiState.update { it.copy(selectedClipId = null, activeTool = null) }
        showMessage("Clip deleted", canUndo = true)
    }

    fun duplicateSelectedClip() {
        val clip = _uiState.value.selectedClip ?: return
        val copy = clip.copy(id = Ids.next())
        edit { doc ->
            val list = doc.clips.toMutableList()
            list.add(list.indexOfFirst { it.id == clip.id } + 1, copy)
            doc.copy(clips = list)
        }
        _thumbnails.value[clip.id]?.let { bmp -> _thumbnails.update { it + (copy.id to bmp) } }
        showMessage("Clip duplicated", canUndo = true)
    }

    /** Splits the clip under the playhead into two. */
    fun splitAtPlayhead() {
        val doc = _uiState.value.doc
        val position = _playback.value.positionUs
        val (clip, startUs) = doc.clipAt(position) ?: return
        val offsetUs = position - startUs
        if (offsetUs < MediaClip.MIN_CLIP_US || clip.timelineDurationUs - offsetUs < MediaClip.MIN_CLIP_US) {
            showMessage("Move the playhead further inside the clip to split it")
            return
        }
        val second: MediaClip
        val first: MediaClip
        if (clip.type == ClipType.VIDEO) {
            val sourceSplit = clip.trimStartUs + (offsetUs * clip.speed).toLong()
            first = clip.copy(trimEndUs = sourceSplit)
            second = clip.copy(id = Ids.next(), trimStartUs = sourceSplit, transition = TransitionType.NONE)
        } else {
            first = clip.copy(imageDurationUs = offsetUs)
            second = clip.copy(
                id = Ids.next(),
                imageDurationUs = clip.imageDurationUs - offsetUs,
                transition = TransitionType.NONE,
            )
        }
        edit { d ->
            val list = d.clips.toMutableList()
            val index = list.indexOfFirst { it.id == clip.id }
            list[index] = first
            list.add(index + 1, second)
            d.copy(clips = list)
        }
        _thumbnails.value[clip.id]?.let { bmp -> _thumbnails.update { it + (second.id to bmp) } }
        _uiState.update { it.copy(selectedClipId = second.id) }
        showMessage("Clip split", canUndo = true)
    }

    /** Sets the trim start or end of the selected video to the playhead. */
    fun trimToPlayhead(setStart: Boolean) {
        val state = _uiState.value
        val clip = state.selectedClip ?: return
        if (clip.type != ClipType.VIDEO) return
        val index = state.doc.clips.indexOfFirst { it.id == clip.id }
        val startUs = state.doc.clipStartsUs()[index]
        val offset = _playback.value.positionUs - startUs
        if (offset !in 0..clip.timelineDurationUs) {
            showMessage("Move the playhead onto this clip first")
            return
        }
        val sourceUs = clip.trimStartUs + (offset * clip.speed).toLong()
        updateSelectedClip("trim") {
            if (setStart) {
                it.copy(trimStartUs = sourceUs.coerceAtMost(it.trimEndUs - MediaClip.MIN_CLIP_US))
            } else {
                it.copy(trimEndUs = sourceUs.coerceAtLeast(it.trimStartUs + MediaClip.MIN_CLIP_US))
            }
        }
    }

    // ---- Overlay -------------------------------------------------------------------------------

    fun setOverlay(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMedia = true) }
            val clip = inspector.inspectVisual(uri)
            _uiState.update { it.copy(isLoadingMedia = false) }
            if (clip == null) {
                showMessage("That file couldn't be used as an overlay")
                return@launch
            }
            // Keep the previous look (opacity, size, position) when swapping the overlay.
            edit { doc -> doc.copy(overlay = doc.overlay?.copy(clip = clip) ?: OverlayLayer(clip = clip)) }
            loadThumbnail(clip)
        }
    }

    fun updateOverlay(key: String, transform: (OverlayLayer) -> OverlayLayer) =
        edit("overlay:$key") { doc -> doc.copy(overlay = doc.overlay?.let(transform)) }

    fun removeOverlay() {
        edit { it.copy(overlay = null) }
        showMessage("Overlay removed", canUndo = true)
    }

    // ---- Text ------------------------------------------------------------------------------------

    fun addText() {
        val doc = _uiState.value.doc
        val start = _playback.value.positionUs.coerceIn(0L, (doc.totalDurationUs - 500_000L).coerceAtLeast(0L))
        val item = TextItem(
            id = Ids.next(),
            text = "Your text",
            startUs = start,
            endUs = minOf(start + 3_000_000L, doc.totalDurationUs).coerceAtLeast(start + 500_000L),
        )
        edit { it.copy(texts = it.texts + item) }
        _uiState.update { it.copy(selectedTextId = item.id) }
    }

    fun selectText(id: Long?) = _uiState.update { it.copy(selectedTextId = id) }

    fun updateSelectedText(key: String, transform: (TextItem) -> TextItem) {
        val id = _uiState.value.selectedTextId ?: return
        edit("text:$key:$id") { doc -> doc.copy(texts = doc.texts.map { if (it.id == id) transform(it) else it }) }
    }

    fun deleteSelectedText() {
        val id = _uiState.value.selectedTextId ?: return
        edit { doc -> doc.copy(texts = doc.texts.filterNot { it.id == id }) }
        _uiState.update { it.copy(selectedTextId = null) }
        showMessage("Text deleted", canUndo = true)
    }

    // ---- Music, transitions, format --------------------------------------------------------------

    fun setAudio(uri: Uri) {
        viewModelScope.launch {
            val audio = inspector.inspectAudio(uri)
            if (audio == null) showMessage("That audio file couldn't be read")
            else edit { doc -> doc.copy(audio = audio.copy(volume = doc.audio?.volume ?: audio.volume)) }
        }
    }

    fun updateAudio(key: String, transform: (AudioLayer) -> AudioLayer) =
        edit("audio:$key") { doc -> doc.copy(audio = doc.audio?.let(transform)) }

    fun removeAudio() {
        edit { it.copy(audio = null) }
        showMessage("Music removed", canUndo = true)
    }

    fun updateTransitions(key: String, transform: (Transitions) -> Transitions) =
        edit("transitions:$key") { doc -> doc.copy(transitions = transform(doc.transitions)) }

    fun updateOutput(key: String, transform: (OutputSettings) -> OutputSettings) =
        edit("output:$key") { doc -> doc.copy(output = transform(doc.output)) }

    // ---- Export ----------------------------------------------------------------------------------

    fun export() {
        val state = _uiState.value
        if (!state.canExport) return
        exportJob = viewModelScope.launch {
            preview.clear() // Free the video decoders for the export.
            _uiState.update { it.copy(export = ExportState.Running(progress = null)) }
            val result = try {
                val composition = CompositionFactory.build(state.doc)
                val saved = exporter.export(composition) { progress ->
                    _uiState.update { s ->
                        if (s.export is ExportState.Running) s.copy(export = ExportState.Running(progress)) else s
                    }
                }
                ExportState.Done(saved.uri, saved.fileName)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(export = ExportState.Idle) }
                refreshPreview(_uiState.value.doc)
                throw e
            } catch (e: ExportException) {
                ExportState.Failed("Export failed (${e.errorCodeName}). Try 720p or fewer layers.")
            } catch (e: Exception) {
                ExportState.Failed(e.message ?: "Export failed")
            }
            _uiState.update { it.copy(export = result) }
            refreshPreview(_uiState.value.doc)
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
    }

    fun dismissExportResult() = _uiState.update { it.copy(export = ExportState.Idle) }

    // ---- Misc ------------------------------------------------------------------------------------

    private fun showMessage(text: String, canUndo: Boolean = false) =
        _uiState.update { it.copy(message = UiMessage(text, canUndo)) }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun loadThumbnail(clip: MediaClip) {
        viewModelScope.launch {
            val bitmap = inspector.thumbnail(clip) ?: return@launch
            _thumbnails.update { it + (clip.id to bitmap.asImageBitmap()) }
        }
    }

    override fun onCleared() {
        preview.clear()
    }
}

val Tool.isClipTool: Boolean
    get() = this in setOf(
        Tool.TRIM, Tool.SPEED, Tool.VOLUME, Tool.FILTERS, Tool.ADJUST, Tool.ROTATE, Tool.FIT,
        Tool.TRANSITION, Tool.EFFECTS,
    )
