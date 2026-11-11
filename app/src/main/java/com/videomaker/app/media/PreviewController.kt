package com.videomaker.app.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays the current edit with Media3's CompositionPlayer, so every change shows up in the
 * preview exactly as it will be exported. Must be used from the main thread.
 *
 * A fresh player is created for each new composition (as Media3's own demo does), keeping the
 * playhead and play/pause state.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class PreviewController(private val context: Context) {

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private var current: CompositionPlayer? = null
    var onError: ((String) -> Unit)? = null

    val positionUs: Long get() = (current?.currentPosition ?: 0L) * 1000
    val isPlaying: Boolean get() = current?.isPlaying == true

    fun show(composition: Composition, durationUs: Long) {
        val keepPositionMs = ((current?.currentPosition ?: 0L))
            .coerceIn(0L, (durationUs / 1000 - 100).coerceAtLeast(0L))
        val keepPlaying = current?.playWhenReady == true
        releasePlayer()

        val builder = CompositionPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
        if (CompositionFactory.videoLayerCount(composition.sequences) > 1) {
            // Needed to combine several video layers (overlay, transitions).
            builder.setVideoGraphFactory(MultipleInputVideoGraph.Factory())
        }
        val newPlayer = builder.build()
        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                var root: Throwable = error
                while (root.cause != null && root.cause !== root) root = root.cause!!
                val detail = root.message?.take(120) ?: root.javaClass.simpleName
                onError?.invoke("Preview unavailable for this edit. Export may still work.\n${error.errorCodeName}: $detail")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Rewind when the end is reached so Play starts over.
                if (playbackState == Player.STATE_ENDED) {
                    newPlayer.pause()
                    newPlayer.seekTo(0)
                }
            }
        })
        newPlayer.setComposition(composition, keepPositionMs)
        newPlayer.prepare()
        newPlayer.playWhenReady = keepPlaying
        current = newPlayer
        _player.value = newPlayer
    }

    fun togglePlay() {
        val p = current ?: return
        p.playWhenReady = !p.playWhenReady
    }

    fun pause() {
        current?.pause()
    }

    fun seekTo(positionUs: Long) {
        current?.seekTo((positionUs / 1000).coerceAtLeast(0L))
    }

    fun clear() = releasePlayer()

    private fun releasePlayer() {
        val old = current ?: return
        current = null
        _player.value = null
        old.stop()
        old.release()
    }
}
