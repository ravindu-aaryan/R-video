package com.videomaker.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videomaker.app.R
import com.videomaker.app.model.AspectRatio
import com.videomaker.app.model.AudioLayer
import com.videomaker.app.model.ClipType
import com.videomaker.app.model.OutputSettings
import com.videomaker.app.model.OverlayLayer
import com.videomaker.app.model.Resolution
import com.videomaker.app.model.TextAnimation
import com.videomaker.app.model.TextItem
import com.videomaker.app.model.TextStyle
import com.videomaker.app.model.Transitions
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ---- Overlay ---------------------------------------------------------------------------------------

@Composable
fun OverlayPanel(
    overlay: OverlayLayer?,
    thumbnail: ImageBitmap?,
    totalDurationUs: Long,
    onPick: () -> Unit,
    onUpdate: (key: String, transform: (OverlayLayer) -> OverlayLayer) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (overlay == null) {
        EmptyPanel(
            icon = { Icon(Icons.Filled.Layers, contentDescription = null) },
            text = stringResource(R.string.overlay_empty),
            action = stringResource(R.string.choose_overlay),
            onAction = onPick,
            modifier = modifier,
        )
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(thumbnail, overlay.clip.type, size = 44.dp)
            Text(
                overlay.clip.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            TextButton(onClick = onPick) { Text(stringResource(R.string.replace)) }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
        }
        LabeledSlider(
            stringResource(R.string.opacity), "${(overlay.opacity * 100).roundToInt()}%", overlay.opacity,
            { v -> onUpdate("opacity") { it.copy(opacity = v) } }, 0.05f..1f,
        )
        LabeledSlider(
            stringResource(R.string.size), "${(overlay.widthFraction * 100).roundToInt()}%", overlay.widthFraction,
            { v -> onUpdate("size") { it.copy(widthFraction = v) } }, 0.1f..1f,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PositionGrid(
                x = overlay.positionX,
                y = overlay.positionY,
                onPick = { x, y -> onUpdate("position") { it.copy(positionX = x, positionY = y) } },
            )
            Column(Modifier.weight(1f)) {
                LabeledSlider(
                    stringResource(R.string.horizontal), "${(overlay.positionX * 100).roundToInt()}", overlay.positionX,
                    { v -> onUpdate("x") { it.copy(positionX = v) } }, -1f..1f,
                )
                LabeledSlider(
                    stringResource(R.string.vertical), "${(overlay.positionY * 100).roundToInt()}", overlay.positionY,
                    { v -> onUpdate("y") { it.copy(positionY = v) } }, -1f..1f,
                )
            }
        }
        HorizontalDivider()
        val maxStartSeconds = ((totalDurationUs - 500_000L).coerceAtLeast(0L)) / 1_000_000f
        LabeledSlider(
            stringResource(R.string.starts_at), formatDuration(overlay.startUs), overlay.startUs / 1_000_000f,
            { v -> onUpdate("start") { it.copy(startUs = (v * 1_000_000).roundToLong()) } },
            0f..maxOf(maxStartSeconds, 0.1f),
            enabled = maxStartSeconds > 0f,
        )
        if (overlay.clip.type == ClipType.VIDEO) {
            OverlayTrim(overlay, onUpdate)
            if (overlay.clip.hasAudio) {
                SwitchRow(
                    label = stringResource(R.string.overlay_sound),
                    checked = overlay.includeAudio,
                    onCheckedChange = { on -> onUpdate("sound") { it.copy(includeAudio = on) } },
                )
                if (overlay.includeAudio) {
                    LabeledSlider(
                        stringResource(R.string.volume), "${(overlay.volume * 100).roundToInt()}%", overlay.volume,
                        { v -> onUpdate("volume") { it.copy(volume = v) } }, 0f..2f,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayTrim(overlay: OverlayLayer, onUpdate: (String, (OverlayLayer) -> OverlayLayer) -> Unit) {
    val clip = overlay.clip
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
            if (range.endInclusive - range.start >= 0.3f) {
                onUpdate("trim") {
                    it.copy(
                        clip = it.clip.copy(
                            trimStartUs = (range.start * 1_000_000).roundToLong(),
                            trimEndUs = (range.endInclusive * 1_000_000).roundToLong().coerceAtMost(it.clip.sourceDurationUs),
                        ),
                    )
                }
            }
        },
        valueRange = 0f..clip.sourceDurationUs / 1_000_000f,
    )
}

// ---- Text ------------------------------------------------------------------------------------------

private val EMOJI = listOf("🔥", "❤️", "✨", "😂", "😍", "🎉", "💯", "👍", "🙌", "😎", "🥳", "⭐", "👉", "✅")

private val TEXT_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFD60A, 0xFFFF453A, 0xFFFF9F0A, 0xFF32D74B, 0xFF64D2FF, 0xFF5E5CE6, 0xFFFF375F,
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TextPanel(
    texts: List<TextItem>,
    selected: TextItem?,
    totalDurationUs: Long,
    onAdd: () -> Unit,
    onSelect: (Long?) -> Unit,
    onUpdate: (key: String, transform: (TextItem) -> TextItem) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            texts.forEach { item ->
                FilterChip(
                    selected = item.id == selected?.id,
                    onClick = { onSelect(if (item.id == selected?.id) null else item.id) },
                    label = { Text(item.text.ifBlank { "…" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            Button(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.add_text), modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (selected == null) {
            Text(
                stringResource(if (texts.isEmpty()) R.string.text_empty else R.string.text_pick),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        OutlinedTextField(
            value = selected.text,
            onValueChange = { t -> onUpdate("text") { it.copy(text = t) } },
            label = { Text(stringResource(R.string.text_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Quick emoji / stickers: tap to add to the text.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EMOJI.forEach { emoji ->
                TextButton(onClick = { onUpdate("emoji$emoji") { it.copy(text = it.text + emoji) } }) {
                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        // Colour swatches
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TEXT_COLORS.forEach { argb ->
                val isSelected = selected.colorArgb == argb
                val swatchLabel = stringResource(R.string.color_swatch, Integer.toHexString(argb).uppercase())
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            BorderStroke(
                                if (isSelected) 3.dp else 1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                            CircleShape,
                        )
                        .clickable(role = Role.RadioButton) { onUpdate("color") { it.copy(colorArgb = argb) } }
                        .semantics { contentDescription = swatchLabel },
                )
            }
        }
        val styles = TextStyle.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            styles.forEachIndexed { index, style ->
                SegmentedButton(
                    selected = selected.style == style,
                    onClick = { onUpdate("style") { it.copy(style = style) } },
                    shape = SegmentedButtonDefaults.itemShape(index, styles.size),
                ) { Text(style.label) }
            }
        }
        Text(stringResource(R.string.text_animation), style = MaterialTheme.typography.labelLarge)
        ChipRow(
            options = TextAnimation.entries,
            selected = selected.animation,
            label = { it.label },
            onSelect = { anim -> onUpdate("animation") { it.copy(animation = anim) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabeledSlider(
                stringResource(R.string.size), "${(selected.sizeFraction * 100).roundToInt()}", selected.sizeFraction,
                { v -> onUpdate("size") { it.copy(sizeFraction = v) } }, 0.03f..0.2f,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = selected.bold,
                onClick = { onUpdate("bold") { it.copy(bold = !it.bold) } },
                label = { Text(stringResource(R.string.bold)) },
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PositionGrid(
                x = selected.positionX,
                y = selected.positionY,
                onPick = { x, y -> onUpdate("position") { it.copy(positionX = x, positionY = y) } },
            )
            Column(Modifier.weight(1f)) {
                LabeledSlider(
                    stringResource(R.string.horizontal), "${(selected.positionX * 100).roundToInt()}", selected.positionX,
                    { v -> onUpdate("x") { it.copy(positionX = v) } }, -1f..1f,
                )
                LabeledSlider(
                    stringResource(R.string.vertical), "${(selected.positionY * 100).roundToInt()}", selected.positionY,
                    { v -> onUpdate("y") { it.copy(positionY = v) } }, -1f..1f,
                )
            }
        }
        // When the text is on screen
        val total = totalDurationUs.coerceAtLeast(1L) / 1_000_000f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.show_between), style = MaterialTheme.typography.labelLarge)
            Text(
                "${formatDuration(selected.startUs)} – ${formatDuration(selected.endUs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        RangeSlider(
            value = (selected.startUs / 1_000_000f).coerceIn(0f, total)..(selected.endUs / 1_000_000f).coerceIn(0f, total),
            onValueChange = { range ->
                if (range.endInclusive - range.start >= 0.3f) {
                    onUpdate("time") {
                        it.copy(
                            startUs = (range.start * 1_000_000).roundToLong(),
                            endUs = (range.endInclusive * 1_000_000).roundToLong(),
                        )
                    }
                }
            },
            valueRange = 0f..total,
        )
        Row {
            TextButton(onClick = { onUpdate("all") { it.copy(startUs = 0L, endUs = totalDurationUs) } }) {
                Text(stringResource(R.string.whole_video))
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text(stringResource(R.string.delete_text), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

// ---- Music -----------------------------------------------------------------------------------------

@Composable
fun MusicPanel(
    audio: AudioLayer?,
    onPick: () -> Unit,
    onUpdate: (key: String, transform: (AudioLayer) -> AudioLayer) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (audio == null) {
        EmptyPanel(
            icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
            text = stringResource(R.string.music_empty),
            action = stringResource(R.string.add_music),
            onAction = onPick,
            modifier = modifier,
        )
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(audio.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatDuration(audio.durationUs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onPick) { Text(stringResource(R.string.replace)) }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
        }
        LabeledSlider(
            stringResource(R.string.volume), "${(audio.volume * 100).roundToInt()}%", audio.volume,
            { v -> onUpdate("volume") { it.copy(volume = v) } }, 0f..2f,
        )
        val maxStart = ((audio.durationUs - 1_000_000L).coerceAtLeast(0L)) / 1_000_000f
        LabeledSlider(
            stringResource(R.string.music_start), formatDuration(audio.startOffsetUs), audio.startOffsetUs / 1_000_000f,
            { v -> onUpdate("start") { it.copy(startOffsetUs = (v * 1_000_000).roundToLong()) } },
            0f..maxOf(maxStart, 0.1f),
            enabled = maxStart > 0f,
        )
        SwitchRow(
            label = stringResource(R.string.loop_music),
            checked = audio.loop,
            onCheckedChange = { on -> onUpdate("loop") { it.copy(loop = on) } },
        )
    }
}

// ---- Transitions -----------------------------------------------------------------------------------

@Composable
fun TransitionsPanel(
    transitions: Transitions,
    onUpdate: (key: String, transform: (Transitions) -> Transitions) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(
            label = stringResource(R.string.fade_in_start),
            checked = transitions.fadeInAtStart,
            onCheckedChange = { on -> onUpdate("in") { it.copy(fadeInAtStart = on) } },
        )
        SwitchRow(
            label = stringResource(R.string.fade_out_end),
            checked = transitions.fadeOutAtEnd,
            onCheckedChange = { on -> onUpdate("out") { it.copy(fadeOutAtEnd = on) } },
        )
        val anyFade = transitions.fadeInAtStart || transitions.fadeOutAtEnd
        LabeledSlider(
            stringResource(R.string.fade_length),
            String.format(java.util.Locale.US, "%.1fs", transitions.durationUs / 1_000_000f),
            transitions.durationUs / 1_000_000f,
            { v -> onUpdate("length") { it.copy(durationUs = ((v * 10).roundToInt() * 100_000L)) } },
            0.2f..2f,
            enabled = anyFade,
        )
        Text(
            stringResource(R.string.fades_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        SwitchRow(
            label = stringResource(R.string.fade_audio),
            supportingText = stringResource(R.string.fade_audio_hint),
            checked = transitions.fadeAudio,
            onCheckedChange = { on -> onUpdate("audio") { it.copy(fadeAudio = on) } },
        )
    }
}

// ---- Format ----------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatPanel(
    output: OutputSettings,
    onUpdate: (key: String, transform: (OutputSettings) -> OutputSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.aspect_ratio), style = MaterialTheme.typography.labelLarge)
        val ratios = AspectRatio.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ratios.forEachIndexed { index, ratio ->
                SegmentedButton(
                    selected = output.aspectRatio == ratio,
                    onClick = { onUpdate("ratio") { it.copy(aspectRatio = ratio) } },
                    shape = SegmentedButtonDefaults.itemShape(index, ratios.size),
                ) { Text(ratio.label) }
            }
        }
        Text(stringResource(R.string.resolution), style = MaterialTheme.typography.labelLarge)
        val resolutions = Resolution.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            resolutions.forEachIndexed { index, resolution ->
                SegmentedButton(
                    selected = output.resolution == resolution,
                    onClick = { onUpdate("resolution") { it.copy(resolution = resolution) } },
                    shape = SegmentedButtonDefaults.itemShape(index, resolutions.size),
                ) { Text(resolution.label) }
            }
        }
        Text(
            "${output.width} × ${output.height}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            label = stringResource(R.string.keep_original_sound),
            supportingText = stringResource(R.string.keep_original_sound_hint),
            checked = output.keepOriginalAudio,
            onCheckedChange = { on -> onUpdate("sound") { it.copy(keepOriginalAudio = on) } },
        )
    }
}

// ---- Shared ----------------------------------------------------------------------------------------

@Composable
private fun EmptyPanel(
    icon: @Composable () -> Unit,
    text: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon()
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}
