package com.guidehub.voice

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent
import com.guidehub.core.ButtonEvent
import com.guidehub.core.ButtonSource
import com.guidehub.core.Clock
import com.guidehub.core.EventBus
import com.guidehub.core.PttButton
import com.guidehub.protocol.ButtonPacket

/**
 * Earbud-button push-to-talk via an active [MediaSession] (FR-2). Android routes media-button
 * events to the most recently active playing session, so the session reports STATE_PLAYING.
 * Which key counts as PTT is configurable (earbuds map taps differently: play/pause, double tap = next).
 */
class PushToTalk(
    context: Context,
    private val bus: EventBus,
    private val clock: Clock,
    private val mapping: () -> PttButton,
) {
    private val session = MediaSession(context, "GuideHubPTT")

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val key: KeyEvent = (if (Build.VERSION.SDK_INT >= 33) mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                else @Suppress("DEPRECATION") mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)) ?: return false
                if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount > 0) return true
                if (matches(key.keyCode)) {
                    bus.emit(ButtonEvent(ButtonSource.EARBUD, key.keyCode, ButtonPacket.PRESS, clock.now()))
                    return true
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
    }

    fun matches(keyCode: Int): Boolean = when (mapping()) {
        PttButton.PLAY_PAUSE -> keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        PttButton.HEADSET_HOOK -> keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        PttButton.NEXT -> keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
        PttButton.PREVIOUS -> keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
    }

    fun activate() { session.isActive = true }
    fun deactivate() { session.isActive = false }
    fun release() = session.release()
}
