package com.winter.wplayer

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class PlaybackService: MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dataStore by lazy { applicationContext.dataStore }
    private var mediaSession: MediaSession? = null
    private val player: ExoPlayer by lazy { ExoPlayer.Builder(this).build() }

    private val listener = object: Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            IndexHolder.update(player.currentMediaItemIndex)
            persist()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persist()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            persist()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            persist()
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, player).build()
        mediaSession?.player?.addListener(listener)
        scope.launch { restore() }
        scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                if (player.isPlaying) persist()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /** Snapshots the live queue so it survives process death. */
    private fun persist() {
        if (player.mediaItemCount == 0) return // never overwrite the cache with an empty queue
        val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        val repeatMode = player.repeatMode
        val shuffle = player.shuffleModeEnabled
        scope.launch {
            try {
                dataStore.edit {
                    it[ConfigHolder.QUEUE] = QueueCodec.encode(items)
                    it[ConfigHolder.QUEUE_INDEX] = index
                    it[ConfigHolder.QUEUE_POSITION] = position
                    it[ConfigHolder.REPEAT_MODE] = repeatMode
                    it[ConfigHolder.SHUFFLE] = shuffle
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist playback state", e)
            }
        }
    }

    /** Restores the last queue without starting playback. */
    private suspend fun restore() {
        try {
            val prefs = dataStore.data.first()
            val items = QueueCodec.decode(prefs[ConfigHolder.QUEUE])
            if (items.isEmpty()) return
            // The user may already have picked a song while we were reading the cache.
            if (player.mediaItemCount != 0) return
            val index = (prefs[ConfigHolder.QUEUE_INDEX] ?: 0).coerceIn(0, items.lastIndex)
            val position = (prefs[ConfigHolder.QUEUE_POSITION] ?: 0L).coerceAtLeast(0L)

            // Go back to IDLE first: after a play request on an empty queue the player can be
            // stuck in ENDED, where prepare() is a no-op.
            player.stop()
            player.setMediaItems(items, index, position)
            player.repeatMode = prefs[ConfigHolder.REPEAT_MODE] ?: Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = prefs[ConfigHolder.SHUFFLE] ?: false
            player.prepare() // load, but stay paused
            if (player.playWhenReady) player.play() // honor a play press that raced the restore
            IndexHolder.update(index)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore playback state", e)
        }
    }

    private companion object {
        private const val TAG = "PlaybackService"
        private const val SAVE_INTERVAL_MS = 5_000L
    }
}

object IndexHolder {
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    fun update(value: Int){
        _currentIndex.value = value
    }
}

private object QueueCodec {
    fun encode(items: List<MediaItem>): String {
        val array = JSONArray()
        for (item in items) {
            val uri = item.localConfiguration?.uri?.toString().orEmpty()
            if (uri.isEmpty()) continue
            array.put(JSONObject().put("id", item.mediaId).put("uri", uri))
        }
        return array.toString()
    }

    fun decode(raw: String?): List<MediaItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val uri = obj.optString("uri")
                    if (uri.isEmpty()) continue
                    add(
                        MediaItem.Builder()
                            .setMediaId(obj.optString("id"))
                            .setUri(uri)
                            .build()
                    )
                }
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }
}
