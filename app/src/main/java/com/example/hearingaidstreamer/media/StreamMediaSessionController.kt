package com.example.hearingaidstreamer.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.TaskStackBuilder
import com.example.hearingaidstreamer.R
import com.example.hearingaidstreamer.audio.LoopbackAudioEngine
import com.example.hearingaidstreamer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages a platform [MediaSession] so the loopback stream integrates with system media controls.
 */
class StreamMediaSessionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val loopbackAudioEngine: LoopbackAudioEngine,
    private val onStopRequested: suspend () -> Unit,
    private val onMuteStateChanged: (Boolean) -> Unit
) {

    private val notificationManager = StreamNotificationManager(context)

    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(playbackAttributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                scope.launch { pause() }
            }
        }
        .build()

    private val mediaSession: MediaSession = MediaSession(context, SESSION_TAG).apply {
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setPlaybackToLocal(playbackAttributes)
        val sessionActivity = TaskStackBuilder.create(context).run {
            addNextIntent(Intent(context, MainActivity::class.java))
            getPendingIntent(REQUEST_CODE_SESSION_ACTIVITY, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        setSessionActivity(sessionActivity)
        setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, context.getString(R.string.app_name))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, context.getString(R.string.app_name))
                .build()
        )
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                scope.launch { play() }
            }

            override fun onPause() {
                scope.launch { pause() }
            }

            override fun onStop() {
                scope.launch { onStopRequested() }
            }
        })
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PLAYBACK_ACTIONS)
                .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private val compatToken: MediaSessionCompat.Token
        get() = MediaSessionCompat.Token.fromToken(mediaSession.sessionToken)

    @Volatile
    private var started = false

    @Volatile
    private var muted = false

    private suspend fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        return withContext(Dispatchers.Main) {
            manager.requestAudioFocus(focusRequest)
        } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private suspend fun abandonFocus() {
        val manager = audioManager ?: return
        withContext(Dispatchers.Main) {
            manager.abandonAudioFocusRequest(focusRequest)
        }
    }

    suspend fun play(): Boolean {
        val granted = requestFocus()
        if (!granted) {
            Log.w(SESSION_TAG, "Audio focus request was denied; continuing playback.")
        }

        if (!started) {
            loopbackAudioEngine.start(scope)
            started = true
        }
        setMutedInternal(false)
        withContext(Dispatchers.Main) {
            mediaSession.isActive = true
        }
        updatePlaybackState()
        notificationManager.show(compatToken, isPlaying = !muted)
        return granted
    }

    suspend fun pause() {
        setMutedInternal(true)
        updatePlaybackState()
        notificationManager.show(compatToken, isPlaying = !muted)
        abandonFocus()
    }

    suspend fun stop() {
        setMutedInternal(false, forceNotify = true)
        loopbackAudioEngine.stop()
        started = false
        updatePlaybackState()
        withContext(Dispatchers.Main) {
            mediaSession.isActive = false
        }
        notificationManager.dismiss()
        abandonFocus()
    }

    suspend fun setMuted(value: Boolean) {
        if (value) {
            pause()
        } else {
            play()
        }
    }

    fun isMuted(): Boolean = muted

    suspend fun release() {
        stop()
        withContext(Dispatchers.Main) {
            mediaSession.release()
        }
        notificationManager.dismiss()
    }

    private suspend fun updatePlaybackState() {
        withContext(Dispatchers.Main) {
            val state = when {
                !started -> PlaybackState.STATE_STOPPED
                muted -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_PLAYING
            }
            val playbackState = PlaybackState.Builder()
                .setActions(PLAYBACK_ACTIONS)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackState.STATE_PLAYING) 1f else 0f)
                .build()
            mediaSession.setPlaybackState(playbackState)
        }
    }

    private fun setMutedInternal(value: Boolean, forceNotify: Boolean = false) {
        if (!forceNotify && muted == value) return
        muted = value
        loopbackAudioEngine.setMuted(value)
        onMuteStateChanged(value)
    }

    companion object {
        private const val SESSION_TAG = "HearingAidStreamSession"
        private const val PLAYBACK_ACTIONS =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP
        private const val REQUEST_CODE_SESSION_ACTIVITY = 0x200
    }
}
