package com.videomaker.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import com.videomaker.app.R
import com.videomaker.app.model.AudioLayer
import com.videomaker.app.model.EditorUiState
import com.videomaker.app.model.ExportState
import com.videomaker.app.model.MediaClip
import com.videomaker.app.model.OutputSettings
import com.videomaker.app.model.OverlayLayer
import com.videomaker.app.model.PlaybackState
import com.videomaker.app.model.TextItem
import com.videomaker.app.model.Tool
import com.videomaker.app.model.Transitions
import com.videomaker.app.ui.components.AdjustPanel
import com.videomaker.app.ui.components.ClipActions
import com.videomaker.app.ui.components.ClipToolBar
import com.videomaker.app.ui.components.EffectsPanel
import com.videomaker.app.ui.components.EmptyEditor
import com.videomaker.app.ui.components.ExportDoneDialog
import com.videomaker.app.ui.components.ExportFailedDialog
import com.videomaker.app.ui.components.ExportProgressDialog
import com.videomaker.app.ui.components.FiltersPanel
import com.videomaker.app.ui.components.FitPanel
import com.videomaker.app.ui.components.FormatPanel
import com.videomaker.app.ui.components.MusicPanel
import com.videomaker.app.ui.components.OverlayPanel
import com.videomaker.app.ui.components.PlaybackBar
import com.videomaker.app.ui.components.PreviewSurface
import com.videomaker.app.ui.components.ProjectToolBar
import com.videomaker.app.ui.components.RotatePanel
import com.videomaker.app.ui.components.SpeedPanel
import com.videomaker.app.ui.components.TextPanel
import com.videomaker.app.ui.components.TimelineStrip
import com.videomaker.app.ui.components.TransitionPanel
import com.videomaker.app.ui.components.TransitionsPanel
import com.videomaker.app.ui.components.TrimPanel
import com.videomaker.app.ui.components.VolumePanel

@Composable
fun EditorRoute(viewModel: EditorViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val player by viewModel.previewPlayer.collectAsStateWithLifecycle()

    // System photo picker: no storage permission needed.
    val pickTimeline = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.addTimelineMedia(uris) }
    val pickOverlay = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::setOverlay) }
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(viewModel::setAudio) }
    val visualRequest = remember {
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
    }

    val actions = remember(viewModel) {
        EditorActions(
            onAddMedia = { pickTimeline.launch(visualRequest) },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onTogglePlay = viewModel::togglePlay,
            onSeek = viewModel::seekTo,
            onSelectClip = viewModel::selectClip,
            onOpenTool = viewModel::openTool,
            onClipUpdate = viewModel::updateSelectedClip,
            onTrimToPlayhead = viewModel::trimToPlayhead,
            onApplyLookToAll = viewModel::applyLookToAll,
            onApplyTransitionToAll = viewModel::applyTransitionToAll,
            onShuffleTransitions = viewModel::shuffleTransitions,
            clipActions = ClipActions(
                onDeselect = { viewModel.selectClip(null) },
                onSplit = viewModel::splitAtPlayhead,
                onDuplicate = viewModel::duplicateSelectedClip,
                onDelete = viewModel::deleteSelectedClip,
                onMoveLeft = { viewModel.moveSelectedClip(-1) },
                onMoveRight = { viewModel.moveSelectedClip(1) },
            ),
            onPickOverlay = { pickOverlay.launch(visualRequest) },
            onOverlayUpdate = viewModel::updateOverlay,
            onRemoveOverlay = viewModel::removeOverlay,
            onAddText = viewModel::addText,
            onSelectText = viewModel::selectText,
            onTextUpdate = viewModel::updateSelectedText,
            onDeleteText = viewModel::deleteSelectedText,
            onPickAudio = { pickAudio.launch("audio/*") },
            onAudioUpdate = viewModel::updateAudio,
            onRemoveAudio = viewModel::removeAudio,
            onTransitionsUpdate = viewModel::updateTransitions,
            onOutputUpdate = viewModel::updateOutput,
            onExport = viewModel::export,
            onCancelExport = viewModel::cancelExport,
            onDismissResult = viewModel::dismissExportResult,
            onMessageShown = viewModel::messageShown,
        )
    }

    EditorScreen(
        state = state,
        thumbnails = thumbnails,
        playback = playback,
        player = player,
        actions = actions,
    )
}

/** All user actions, grouped so the screen stays a pure function of state. */
class EditorActions(
    val onAddMedia: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSelectClip: (Long?) -> Unit,
    val onOpenTool: (Tool?) -> Unit,
    val onClipUpdate: (key: String, transform: (MediaClip) -> MediaClip) -> Unit,
    val onTrimToPlayhead: (setStart: Boolean) -> Unit,
    val onApplyLookToAll: () -> Unit,
    val onApplyTransitionToAll: () -> Unit,
    val onShuffleTransitions: () -> Unit,
    val clipActions: ClipActions,
    val onPickOverlay: () -> Unit,
    val onOverlayUpdate: (key: String, transform: (OverlayLayer) -> OverlayLayer) -> Unit,
    val onRemoveOverlay: () -> Unit,
    val onAddText: () -> Unit,
    val onSelectText: (Long?) -> Unit,
    val onTextUpdate: (key: String, transform: (TextItem) -> TextItem) -> Unit,
    val onDeleteText: () -> Unit,
    val onPickAudio: () -> Unit,
    val onAudioUpdate: (key: String, transform: (AudioLayer) -> AudioLayer) -> Unit,
    val onRemoveAudio: () -> Unit,
    val onTransitionsUpdate: (key: String, transform: (Transitions) -> Transitions) -> Unit,
    val onOutputUpdate: (key: String, transform: (OutputSettings) -> OutputSettings) -> Unit,
    val onExport: () -> Unit,
    val onCancelExport: () -> Unit,
    val onDismissResult: () -> Unit,
    val onMessageShown: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    thumbnails: Map<Long, ImageBitmap>,
    playback: PlaybackState,
    player: Player?,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    val doc = state.doc
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = if (message.canUndo) undoLabel else null,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) actions.onUndo()
        actions.onMessageShown()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = actions.onUndo, enabled = state.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
                        }
                        IconButton(onClick = actions.onRedo, enabled = state.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo))
                        }
                        Button(
                            onClick = actions.onExport,
                            enabled = state.canExport,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(stringResource(R.string.export)) }
                    },
                )
                if (state.isLoadingMedia) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val content = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding()

        if (doc.clips.isEmpty()) {
            EmptyEditor(onAddMedia = actions.onAddMedia, modifier = content)
            return@Scaffold
        }

        val selectedClip = state.selectedClip
        val panelTool = when {
            selectedClip != null && state.activeTool?.isClipTool == true -> state.activeTool
            selectedClip == null && state.activeTool?.isClipTool == false -> state.activeTool
            else -> null
        }

        Column(content) {
            PreviewSurface(
                player = player,
                aspectRatio = doc.output.ratio,
                errorText = state.previewError,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            PlaybackBar(
                playback = playback,
                durationUs = doc.totalDurationUs,
                onTogglePlay = actions.onTogglePlay,
                onSeek = actions.onSeek,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            TimelineStrip(
                clips = doc.clips,
                thumbnails = thumbnails,
                selectedClipId = state.selectedClipId,
                playingClipId = doc.clipAt(playback.positionUs)?.first?.id,
                onClipClick = { id -> actions.onSelectClip(if (id == state.selectedClipId) null else id) },
                onAddClick = actions.onAddMedia,
            )
            AnimatedVisibility(visible = panelTool != null, modifier = Modifier.weight(1.15f, fill = false)) {
                if (panelTool != null) {
                    ToolPanel(
                        tool = panelTool,
                        state = state,
                        thumbnails = thumbnails,
                        actions = actions,
                    )
                }
            }
            if (selectedClip != null) {
                val index = doc.clips.indexOfFirst { it.id == selectedClip.id }
                ClipToolBar(
                    clip = selectedClip,
                    activeTool = state.activeTool,
                    isFirst = index == 0,
                    isLast = index == doc.clips.lastIndex,
                    onTool = actions.onOpenTool,
                    actions = actions.clipActions,
                )
            } else {
                ProjectToolBar(
                    activeTool = state.activeTool,
                    onAddMedia = actions.onAddMedia,
                    onTool = actions.onOpenTool,
                )
            }
        }
    }

    when (val export = state.export) {
        is ExportState.Running -> ExportProgressDialog(export, onCancel = actions.onCancelExport)
        is ExportState.Done -> ExportDoneDialog(
            state = export,
            aspectRatio = doc.output.ratio,
            onDismiss = actions.onDismissResult,
        )
        is ExportState.Failed -> ExportFailedDialog(export, onDismiss = actions.onDismissResult)
        ExportState.Idle -> Unit
    }
}

@Composable
private fun ToolPanel(
    tool: Tool,
    state: EditorUiState,
    thumbnails: Map<Long, ImageBitmap>,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    val doc = state.doc
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(tool.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                IconButton(onClick = { actions.onOpenTool(null) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.close))
                }
            }
            val clip = state.selectedClip
            when (tool) {
                Tool.TRIM -> clip?.let { TrimPanel(it, actions.onClipUpdate, actions.onTrimToPlayhead) }
                Tool.SPEED -> clip?.let { SpeedPanel(it, actions.onClipUpdate) }
                Tool.VOLUME -> clip?.let { VolumePanel(it, actions.onClipUpdate) }
                Tool.FILTERS -> clip?.let {
                    FiltersPanel(it, thumbnails[it.id], actions.onClipUpdate, actions.onApplyLookToAll)
                }
                Tool.ADJUST -> clip?.let { AdjustPanel(it, actions.onClipUpdate, actions.onApplyLookToAll) }
                Tool.ROTATE -> clip?.let { RotatePanel(it, actions.onClipUpdate) }
                Tool.FIT -> clip?.let { FitPanel(it, actions.onClipUpdate) }
                Tool.TRANSITION -> clip?.let {
                    TransitionPanel(
                        clip = it,
                        isFirst = doc.clips.firstOrNull()?.id == it.id,
                        onUpdate = actions.onClipUpdate,
                        onApplyToAll = actions.onApplyTransitionToAll,
                        onShuffle = actions.onShuffleTransitions,
                    )
                }
                Tool.EFFECTS -> clip?.let { EffectsPanel(it, actions.onClipUpdate) }
                Tool.OVERLAY -> OverlayPanel(
                    overlay = doc.overlay,
                    thumbnail = doc.overlay?.let { thumbnails[it.clip.id] },
                    totalDurationUs = doc.totalDurationUs,
                    onPick = actions.onPickOverlay,
                    onUpdate = actions.onOverlayUpdate,
                    onRemove = actions.onRemoveOverlay,
                )
                Tool.TEXT -> TextPanel(
                    texts = doc.texts,
                    selected = state.selectedText,
                    totalDurationUs = doc.totalDurationUs,
                    onAdd = actions.onAddText,
                    onSelect = actions.onSelectText,
                    onUpdate = actions.onTextUpdate,
                    onDelete = actions.onDeleteText,
                )
                Tool.MUSIC -> MusicPanel(
                    audio = doc.audio,
                    onPick = actions.onPickAudio,
                    onUpdate = actions.onAudioUpdate,
                    onRemove = actions.onRemoveAudio,
                )
                Tool.TRANSITIONS -> TransitionsPanel(doc.transitions, actions.onTransitionsUpdate)
                Tool.FORMAT -> FormatPanel(doc.output, actions.onOutputUpdate)
            }
        }
    }
}

private val Tool.titleRes: Int
    get() = when (this) {
        Tool.OVERLAY -> R.string.tool_overlay
        Tool.TEXT -> R.string.tool_text
        Tool.MUSIC -> R.string.tool_music
        Tool.TRANSITIONS -> R.string.tool_transitions
        Tool.FORMAT -> R.string.tool_format
        Tool.TRIM -> R.string.tool_trim
        Tool.SPEED -> R.string.tool_speed
        Tool.VOLUME -> R.string.tool_volume
        Tool.FILTERS -> R.string.tool_filters
        Tool.ADJUST -> R.string.tool_adjust
        Tool.ROTATE -> R.string.tool_rotate
        Tool.FIT -> R.string.tool_fit
        Tool.TRANSITION -> R.string.tool_transition
        Tool.EFFECTS -> R.string.tool_effects
    }
