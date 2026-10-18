package org.familycircle.android.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.familycircle.android.util.Logger
import java.io.IOException

/**
 * "Let my phone ring": plays the default alarm sound (fallback: ringtone, notification sound) on
 * the alarm stream – audible in silent mode – at full alarm volume for up to [MAX_MS] or until
 * stopped (notification action, in-app banner). The alarm volume is restored afterwards.
 *
 * Works from any context of the process (location service, alert service, catch-up worker): it
 * only needs the application context and keeps the CPU awake while playing.
 */
class Ringer(private val context: Context, private val scope: CoroutineScope) {
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var timeout: Job? = null
    private var savedVolume: Int? = null

    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    /** Starts (or restarts the 60 s of) ringing. */
    fun start(actorName: String, self: Boolean) {
        synchronized(lock) {
            if (player == null) {
                val audio = context.getSystemService(AudioManager::class.java)
                if (audio != null && savedVolume == null) {
                    try {
                        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                    } catch (e: SecurityException) {
                        // Do-not-disturb policy may forbid volume changes; ring at the current volume.
                        Logger.w(TAG, "cannot raise alarm volume", e)
                    }
                }
                player = createPlayer()
                if (player == null) Logger.w(TAG, "no sound to play")
            }
            _ringing.value = true
            timeout?.cancel()
            timeout = scope.launch {
                delay(MAX_MS)
                stop()
            }
        }
        AlertNotifier.postRing(context, AlertNotifier.ring(context, actorName, self))
        Logger.i(TAG, "ringing")
    }

    fun stop() {
        synchronized(lock) {
            timeout?.cancel()
            timeout = null
            player?.let {
                try {
                    it.stop()
                } catch (_: IllegalStateException) {
                    // Not started (prepare failed half-way) – release below is enough.
                }
                it.release()
            }
            player = null
            savedVolume?.let { volume ->
                try {
                    context.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
                } catch (e: SecurityException) {
                    Logger.w(TAG, "cannot restore alarm volume", e)
                }
            }
            savedVolume = null
            if (!_ringing.value) return
            _ringing.value = false
        }
        AlertNotifier.cancelRing(context)
        Logger.i(TAG, "ringing stopped")
    }

    private fun createPlayer(): MediaPlayer? {
        val candidates = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        ).distinct()
        for (uri in candidates) {
            play(uri)?.let { return it }
        }
        return null
    }

    private fun play(uri: Uri): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.setDataSource(context, uri)
            mp.isLooping = true
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.prepare()
            mp.start()
            mp
        } catch (e: IOException) {
            Logger.w(TAG, "sound not playable", e)
            mp.release()
            null
        } catch (e: RuntimeException) {
            // IllegalStateException / IllegalArgumentException / SecurityException from the media stack.
            Logger.w(TAG, "sound not playable", e)
            mp.release()
            null
        }
    }

    companion object {
        private const val TAG = "Ringer"
        const val MAX_MS = 60_000L
    }
}
