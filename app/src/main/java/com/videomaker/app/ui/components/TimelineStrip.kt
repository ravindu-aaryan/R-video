package com.videomaker.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.videomaker.app.R
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.ColorGrading
import com.videomaker.app.model.MediaClip
import com.videomaker.app.model.TransitionType

private val TILE_HEIGHT = 64.dp

/** Horizontal strip of clips; each tile's width follows the clip's length. */
@Composable
fun TimelineStrip(
    clips: List<MediaClip>,
    thumbnails: Map<Long, ImageBitmap>,
    selectedClipId: Long?,
    playingClipId: Long?,
    onClipClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Keep the selected clip visible.
    LaunchedEffect(selectedClipId) {
        val index = clips.indexOfFirst { it.id == selectedClipId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .height(TILE_HEIGHT + 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(clips, key = { _, clip -> clip.id }) { index, clip ->
            ClipTile(
                index = index,
                clip = clip,
                thumbnail = thumbnails[clip.id],
                selected = clip.id == selectedClipId,
                playing = clip.id == playingClipId,
                onClick = { onClipClick(clip.id) },
                modifier = Modifier.animateItem(),
            )
        }
        item(key = "add") {
            val label = stringResource(R.string.add_media)
            Box(
                modifier = Modifier
                    .size(TILE_HEIGHT)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(role = Role.Button, onClick = onAddClick)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

private fun tileWidth(clip: MediaClip): Dp {
    val seconds = clip.timelineDurationUs / 1_000_000f
    return (seconds * 18f).coerceIn(56f, 180f).dp
}

@Composable
private fun ClipTile(
    index: Int,
    clip: MediaClip,
    thumbnail: ImageBitmap?,
    selected: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        playing -> MaterialTheme.colorScheme.outline
        else -> Color.Transparent
    }
    val description = stringResource(R.string.clip_description, index + 1, formatDuration(clip.timelineDurationUs))
    Box(
        modifier = modifier
            .width(tileWidth(clip))
            .height(TILE_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .border(BorderStroke(if (selected) 3.dp else 1.5.dp, borderColor), RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = colorFilterOf(ColorGrading.matrixFor(clip)),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationZ = clip.rotationDegrees.toFloat()
                        scaleX = if (clip.flipHorizontal) -1f else 1f
                        scaleY = if (clip.flipVertical) -1f else 1f
                    },
            )
        } else {
            Icon(
                if (clip.type == ClipType.VIDEO) Icons.Filled.Movie else Icons.Filled.Image,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // Scrim + labels; these sit on top of footage, so they use fixed contrast colours.
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(
                buildString {
                    if (clip.speed != 1f) append(formatSpeed(clip.speed)).append("  ")
                    append(formatDuration(clip.timelineDurationUs))
                },
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (index > 0 && clip.transition != TransitionType.NONE) {
            Icon(
                Icons.Filled.Gradient,
                contentDescription = clip.transition.label,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .size(16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
            )
        }
        if (playing) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.06f)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
