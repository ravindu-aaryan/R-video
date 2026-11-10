package com.videomaker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.videomaker.app.R
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.MediaClip
import com.videomaker.app.model.Tool

/** Actions that happen immediately instead of opening a panel. */
data class ClipActions(
    val onDeselect: () -> Unit,
    val onSplit: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onMoveLeft: () -> Unit,
    val onMoveRight: () -> Unit,
)

/** Bottom bar when nothing is selected: project-wide tools. */
@Composable
fun ProjectToolBar(
    activeTool: Tool?,
    onAddMedia: () -> Unit,
    onTool: (Tool) -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolRow(modifier) {
        ToolButton(Icons.Filled.AddPhotoAlternate, stringResource(R.string.tool_add), false, onAddMedia)
        ToolButton(Icons.Filled.TextFields, stringResource(R.string.tool_text), activeTool == Tool.TEXT) { onTool(Tool.TEXT) }
        ToolButton(Icons.Filled.LibraryMusic, stringResource(R.string.tool_music), activeTool == Tool.MUSIC) { onTool(Tool.MUSIC) }
        ToolButton(Icons.Filled.Layers, stringResource(R.string.tool_overlay), activeTool == Tool.OVERLAY) { onTool(Tool.OVERLAY) }
        ToolButton(Icons.Filled.Gradient, stringResource(R.string.tool_transitions), activeTool == Tool.TRANSITIONS) { onTool(Tool.TRANSITIONS) }
        ToolButton(Icons.Filled.AspectRatio, stringResource(R.string.tool_format), activeTool == Tool.FORMAT) { onTool(Tool.FORMAT) }
    }
}

/** Bottom bar when a clip is selected: tools for that clip. */
@Composable
fun ClipToolBar(
    clip: MediaClip,
    activeTool: Tool?,
    isFirst: Boolean,
    isLast: Boolean,
    onTool: (Tool) -> Unit,
    actions: ClipActions,
    modifier: Modifier = Modifier,
) {
    val isVideo = clip.type == ClipType.VIDEO
    ToolRow(modifier) {
        ToolButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.tool_done), false, actions.onDeselect)
        VerticalDivider(Modifier.padding(vertical = 12.dp))
        ToolButton(
            Icons.Filled.Timer,
            stringResource(if (isVideo) R.string.tool_trim else R.string.tool_duration),
            activeTool == Tool.TRIM,
        ) { onTool(Tool.TRIM) }
        ToolButton(Icons.Filled.ContentCut, stringResource(R.string.tool_split), false, actions.onSplit)
        if (isVideo) {
            ToolButton(Icons.Filled.Speed, stringResource(R.string.tool_speed), activeTool == Tool.SPEED) { onTool(Tool.SPEED) }
            if (clip.hasAudio) {
                ToolButton(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.tool_volume), activeTool == Tool.VOLUME) { onTool(Tool.VOLUME) }
            }
        }
        ToolButton(Icons.Filled.PhotoFilter, stringResource(R.string.tool_filters), activeTool == Tool.FILTERS) { onTool(Tool.FILTERS) }
        ToolButton(Icons.Filled.Tune, stringResource(R.string.tool_adjust), activeTool == Tool.ADJUST) { onTool(Tool.ADJUST) }
        ToolButton(Icons.Filled.Flip, stringResource(R.string.tool_rotate), activeTool == Tool.ROTATE) { onTool(Tool.ROTATE) }
        ToolButton(Icons.Filled.CropFree, stringResource(R.string.tool_fit), activeTool == Tool.FIT) { onTool(Tool.FIT) }
        ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.tool_move_left), false, actions.onMoveLeft, enabled = !isFirst)
        ToolButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.tool_move_right), false, actions.onMoveRight, enabled = !isLast)
        ToolButton(Icons.Filled.ContentCopy, stringResource(R.string.tool_duplicate), false, actions.onDuplicate)
        ToolButton(Icons.Filled.Delete, stringResource(R.string.tool_delete), false, actions.onDelete)
    }
}

@Composable
private fun ToolRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
