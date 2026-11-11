package com.videomaker.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.videomaker.app.R
import com.videomaker.app.model.ClipEffect
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.ColorAdjust
import com.videomaker.app.model.ColorGrading
import com.videomaker.app.model.FilterPreset
import com.videomaker.app.model.FitMode
import com.videomaker.app.model.MediaClip
import com.videomaker.app.model.TransitionType
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Changes the clip; the String groups rapid changes (slider drags) into one undo step. */
typealias ClipUpdate = (key: String, transform: (MediaClip) -> MediaClip) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimPanel(
    clip: MediaClip,
    onUpdate: ClipUpdate,
    onTrimToPlayhead: (setStart: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (clip.type == ClipType.VIDEO) {
            val maxSeconds = clip.sourceDurationUs / 1_000_000f
            val minLength = MediaClip.MIN_CLIP_US / 1_000_000f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.trim), style = MaterialTheme.typography.labelLarge)
                Text(
                    "${formatDuration(clip.trimStartUs)} – ${formatDuration(clip.trimEndUs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            RangeSlider(
                value = (clip.trimStartUs / 1_000_000f)..(clip.trimEndUs / 1_000_000f),
                onValueChange = { range ->
                    if (range.endInclusive - range.start >= minLength) {
                        onUpdate("trim") {
                            it.copy(
                                trimStartUs = (range.start * 1_000_000).roundToLong().coerceAtLeast(0L),
                                trimEndUs = (range.endInclusive * 1_000_000).roundToLong()
                                    .coerceAtMost(it.sourceDurationUs),
                            )
                        }
                    }
                },
                valueRange = 0f..maxSeconds,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onTrimToPlayhead(true) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.start_here))
                }
                OutlinedButton(onClick = { onTrimToPlayhead(false) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.end_here))
                }
            }
            Text(
                stringResource(R.string.trim_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LabeledSlider(
                label = stringResource(R.string.show_for),
                valueText = String.format(java.util.Locale.US, "%.1fs", clip.imageDurationUs / 1_000_000f),
                value = clip.imageDurationUs / 1_000_000f,
                onValueChange = { seconds ->
                    // Half-second steps between 0.5 s and 20 s.
                    val halfSteps = (seconds * 2).roundToLong().coerceIn(1L, 40L)
                    onUpdate("duration") { it.copy(imageDurationUs = halfSteps * 500_000L) }
                },
                valueRange = 0.5f..20f,
            )
        }
    }
}

@Composable
fun SpeedPanel(clip: MediaClip, onUpdate: ClipUpdate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            options = MediaClip.SPEEDS,
            selected = clip.speed,
            label = { formatSpeed(it) },
            onSelect = { speed -> onUpdate("speed") { it.copy(speed = speed) } },
        )
        Text(
            stringResource(R.string.speed_result, formatDuration(clip.timelineDurationUs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun VolumePanel(clip: MediaClip, onUpdate: ClipUpdate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledSlider(
            label = stringResource(R.string.volume),
            valueText = "${(clip.volume * 100).roundToInt()}%",
            value = clip.volume,
            onValueChange = { v -> onUpdate("volume") { it.copy(volume = v) } },
            valueRange = 0f..2f,
        )
        OutlinedButton(onClick = { onUpdate("mute") { it.copy(volume = if (it.volume == 0f) 1f else 0f) } }) {
            Icon(Icons.AutoMirrored.Filled.VolumeOff, contentDescription = null)
            Text(
                stringResource(if (clip.volume == 0f) R.string.unmute else R.string.mute),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
fun FiltersPanel(
    clip: MediaClip,
    thumbnail: ImageBitmap?,
    onUpdate: ClipUpdate,
    onApplyToAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FilterPreset.entries, key = { it.name }) { preset ->
                val selected = clip.filter == preset
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(72.dp)
                        .clickable(role = Role.RadioButton) {
                            onUpdate("filter") { it.copy(filter = preset, filterStrength = 1f) }
                        },
                ) {
                    Thumbnail(
                        bitmap = thumbnail,
                        type = clip.type,
                        // Preview the filter itself (without the clip's manual adjustments).
                        colorFilter = colorFilterOf(ColorGrading.presetMatrix(preset)),
                        modifier = if (selected) {
                            Modifier.border(
                                BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                                RoundedCornerShape(12.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    Text(
                        preset.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (clip.filter != FilterPreset.NONE) {
            LabeledSlider(
                label = stringResource(R.string.strength),
                valueText = "${(clip.filterStrength * 100).roundToInt()}%",
                value = clip.filterStrength,
                onValueChange = { v -> onUpdate("strength") { it.copy(filterStrength = v) } },
                valueRange = 0f..1f,
            )
        }
        TextButton(onClick = onApplyToAll) { Text(stringResource(R.string.apply_to_all)) }
    }
}

@Composable
fun AdjustPanel(
    clip: MediaClip,
    onUpdate: ClipUpdate,
    onApplyToAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = clip.adjust
    fun pct(v: Float) = "${if (v > 0) "+" else ""}${(v * 100).roundToInt()}"
    Column(modifier) {
        LabeledSlider(
            stringResource(R.string.brightness), pct(a.brightness), a.brightness,
            { v -> onUpdate("brightness") { it.copy(adjust = it.adjust.copy(brightness = v)) } }, -1f..1f,
        )
        LabeledSlider(
            stringResource(R.string.contrast), pct(a.contrast), a.contrast,
            { v -> onUpdate("contrast") { it.copy(adjust = it.adjust.copy(contrast = v)) } }, -1f..1f,
        )
        LabeledSlider(
            stringResource(R.string.saturation), pct(a.saturation), a.saturation,
            { v -> onUpdate("saturation") { it.copy(adjust = it.adjust.copy(saturation = v)) } }, -1f..1f,
        )
        LabeledSlider(
            stringResource(R.string.warmth), pct(a.warmth), a.warmth,
            { v -> onUpdate("warmth") { it.copy(adjust = it.adjust.copy(warmth = v)) } }, -1f..1f,
        )
        Row {
            TextButton(onClick = { onUpdate("reset") { it.copy(adjust = ColorAdjust()) } }, enabled = !a.isNeutral) {
                Text(stringResource(R.string.reset))
            }
            TextButton(onClick = onApplyToAll) { Text(stringResource(R.string.apply_to_all)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RotatePanel(clip: MediaClip, onUpdate: ClipUpdate, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { onUpdate("rotate") { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) } }) {
            Icon(Icons.Filled.RotateRight, contentDescription = null)
            Text(stringResource(R.string.rotate_90), modifier = Modifier.padding(start = 8.dp))
        }
        FilterChip(
            selected = clip.flipHorizontal,
            onClick = { onUpdate("flipH") { it.copy(flipHorizontal = !it.flipHorizontal) } },
            label = { Text(stringResource(R.string.flip_horizontal)) },
        )
        FilterChip(
            selected = clip.flipVertical,
            onClick = { onUpdate("flipV") { it.copy(flipVertical = !it.flipVertical) } },
            label = { Text(stringResource(R.string.flip_vertical)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitPanel(clip: MediaClip, onUpdate: ClipUpdate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val modes = FitMode.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = clip.fit == mode,
                    onClick = { onUpdate("fit") { it.copy(fit = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) { Text(mode.label, maxLines = 1) }
            }
        }
        Text(
            stringResource(
                when (clip.fit) {
                    FitMode.FIT -> R.string.fit_hint
                    FitMode.FILL -> R.string.fill_hint
                    FitMode.BLUR -> R.string.blur_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledSlider(
            stringResource(R.string.zoom), String.format(java.util.Locale.US, "%.1f×", clip.zoom), clip.zoom,
            { v -> onUpdate("zoomScale") { it.copy(zoom = v) } }, 1f..3f,
        )
        LabeledSlider(
            stringResource(R.string.horizontal), "${(clip.offsetX * 100).roundToInt()}", clip.offsetX,
            { v -> onUpdate("offsetX") { it.copy(offsetX = v) } }, -1f..1f,
            enabled = clip.zoom > 1f,
        )
        LabeledSlider(
            stringResource(R.string.vertical), "${(clip.offsetY * 100).roundToInt()}", clip.offsetY,
            { v -> onUpdate("offsetY") { it.copy(offsetY = v) } }, -1f..1f,
            enabled = clip.zoom > 1f,
        )
        SwitchRow(
            label = stringResource(R.string.slow_zoom),
            supportingText = stringResource(R.string.slow_zoom_hint),
            checked = clip.slowZoom,
            onCheckedChange = { on -> onUpdate("zoom") { it.copy(slowZoom = on) } },
        )
    }
}

/** How this clip replaces the one before it. */
@Composable
fun TransitionPanel(
    clip: MediaClip,
    isFirst: Boolean,
    onUpdate: ClipUpdate,
    onApplyToAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isFirst) {
            Text(
                stringResource(R.string.transition_first),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.transition_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow(
                options = TransitionType.entries,
                selected = clip.transition,
                label = { it.label },
                onSelect = { type -> onUpdate("transition") { it.copy(transition = type) } },
            )
            LabeledSlider(
                stringResource(R.string.transition_length),
                String.format(java.util.Locale.US, "%.1fs", clip.transitionUs / 1_000_000f),
                clip.transitionUs / 1_000_000f,
                { v -> onUpdate("transitionLength") { it.copy(transitionUs = (v * 10).roundToInt() * 100_000L) } },
                0.2f..1.5f,
                enabled = clip.transition != TransitionType.NONE,
            )
        }
        Row {
            if (!isFirst) TextButton(onClick = onApplyToAll) { Text(stringResource(R.string.transition_all)) }
            TextButton(onClick = onShuffle) { Text(stringResource(R.string.transition_shuffle)) }
        }
    }
}

/** A moving or stylised effect over the whole clip. */
@Composable
fun EffectsPanel(clip: MediaClip, onUpdate: ClipUpdate, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipRow(
            options = ClipEffect.entries,
            selected = clip.effect,
            label = { it.label },
            onSelect = { fx -> onUpdate("effect") { it.copy(effect = fx) } },
        )
        if (clip.effect != ClipEffect.NONE && clip.effect != ClipEffect.MIRROR) {
            LabeledSlider(
                stringResource(R.string.strength), "${(clip.effectStrength * 100).roundToInt()}%", clip.effectStrength,
                { v -> onUpdate("effectStrength") { it.copy(effectStrength = v) } }, 0.05f..1f,
            )
        }
    }
}

/** A wrapping row of single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}
