package com.qualcomm_toolbox.amethyst.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.qualcomm_toolbox.amethyst.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

class MusicPlayer(private val appContext: Context) {
    private var exoPlayer: ExoPlayer = buildExoPlayer(null)

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    var queue: List<Track> = emptyList()
        private set
    private var queueIndex = 0
    var loopMode: Int = 0
    var shuffle: Boolean = false

    private var streamUrlProvider: ((Track) -> String)? = null
    private var incrementPlayCallback: ((Int) -> Unit)? = null
    private var coverUrlProvider: ((Track) -> String?)? = null

    fun setOkHttpClient(client: OkHttpClient?) {
        val track = _currentTrack.value
        val wasPlaying = exoPlayer.isPlaying
        val position = exoPlayer.currentPosition
        detachFromSession()
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        exoPlayer = buildExoPlayer(client)
        exoPlayer.addListener(listener)
        activePlayer = exoPlayer
        if (track != null && streamUrlProvider != null) {
            // Restore full queue into the new player
            val items = queue.map { buildMediaItem(it, streamUrlProvider!!(it)) }
            exoPlayer.setMediaItems(items, queueIndex, position)
            exoPlayer.prepare()
            if (wasPlaying) exoPlayer.play()
        }
        if (_currentTrack.value != null) {
            startPlaybackService()
        }
    }

    fun setPlaybackCallbacks(
        streamUrl: (Track) -> String,
        onIncrementPlay: (Int) -> Unit,
        coverUrl: (Track) -> String? = { null },
    ) {
        streamUrlProvider = streamUrl
        incrementPlayCallback = onIncrementPlay
        coverUrlProvider = coverUrl
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) {
                startPlaybackService()
            } else if (_currentTrack.value == null) {
                stopPlaybackService()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Keep our currentTrack / queueIndex in sync when ExoPlayer advances
            // the playlist natively (e.g. via notification next/prev buttons).
            val newIndex = exoPlayer.currentMediaItemIndex
            if (newIndex != queueIndex && newIndex in queue.indices) {
                queueIndex = newIndex
                _currentTrack.value = queue[queueIndex]
                incrementPlayCallback?.invoke(queue[queueIndex].id)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                // ExoPlayer playlist exhausted – handle loop/stop
                val urlProvider = streamUrlProvider ?: return
                val increment = incrementPlayCallback ?: return
                if (loopMode == 1) {
                    // Loop all: seek back to beginning of playlist
                    exoPlayer.seekTo(0, 0)
                    exoPlayer.play()
                    queueIndex = 0
                    _currentTrack.value = queue.getOrNull(0)
                    _currentTrack.value?.let { increment(it.id) }
                } else {
                    exoPlayer.pause()
                    _isPlaying.value = false
                    stopPlaybackService()
                }
            }
        }
    }

    init {
        exoPlayer.addListener(listener)
        attachToSession()
    }

    private fun buildExoPlayer(okHttp: OkHttpClient?): ExoPlayer {
        val builder = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)

        if (okHttp != null) {
            val httpFactory = OkHttpDataSource.Factory(okHttp)
            val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        }
        return builder.build()
    }

    private fun buildMediaItem(track: Track, streamUrl: String): MediaItem {
        val cover = coverUrlProvider?.invoke(track)
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.genre)
        if (!cover.isNullOrBlank()) {
            metadataBuilder.setArtworkUri(Uri.parse(cover))
        }
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(track.id.toString())
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun attachToSession() {
        activePlayer = exoPlayer
    }

    private fun detachFromSession() {
        if (activePlayer === exoPlayer) {
            activePlayer = null
        }
    }

    private fun startPlaybackService() {
        activePlayer = exoPlayer
        val intent = Intent(appContext, MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SYNC
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    private fun stopPlaybackService() {
        appContext.stopService(Intent(appContext, MusicPlaybackService::class.java))
    }

    fun updateProgress() {
        _positionMs.value = exoPlayer.currentPosition.coerceAtLeast(0L)
        val duration = exoPlayer.duration
        if (duration > 0) {
            _durationMs.value = duration
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, streamUrl: (Track) -> String) {
        queue = tracks
        queueIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        if (tracks.isEmpty()) return

        // Load the entire queue into ExoPlayer so notification controls work natively
        val mediaItems = tracks.map { buildMediaItem(it, streamUrl(it)) }
        exoPlayer.setMediaItems(mediaItems, queueIndex, 0L)
        exoPlayer.prepare()
        exoPlayer.play()

        _currentTrack.value = tracks[queueIndex]
        _isPlaying.value = true
        startPlaybackService()
    }

    fun playTrackAt(index: Int, streamUrl: (Track) -> String) {
        if (queue.isEmpty()) return
        queueIndex = index.coerceIn(0, queue.lastIndex)
        _currentTrack.value = queue[queueIndex]

        // Seek within the already-loaded playlist when possible
        if (exoPlayer.mediaItemCount == queue.size) {
            exoPlayer.seekTo(queueIndex, 0L)
        } else {
            val mediaItems = queue.map { buildMediaItem(it, streamUrl(it)) }
            exoPlayer.setMediaItems(mediaItems, queueIndex, 0L)
            exoPlayer.prepare()
        }
        exoPlayer.play()
        _isPlaying.value = true
        startPlaybackService()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (_currentTrack.value != null) {
                startPlaybackService()
            }
            exoPlayer.play()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    fun next(streamUrl: (Track) -> String) {
        if (queue.isEmpty()) return
        if (loopMode == 2) {
            seekTo(0)
            exoPlayer.play()
            startPlaybackService()
            return
        }
        if (queueIndex < queue.lastIndex) {
            queueIndex++
            exoPlayer.seekTo(queueIndex, 0L)
            exoPlayer.play()
            _currentTrack.value = queue[queueIndex]
            startPlaybackService()
        } else if (loopMode == 1) {
            queueIndex = 0
            exoPlayer.seekTo(0, 0L)
            exoPlayer.play()
            _currentTrack.value = queue[0]
            startPlaybackService()
        } else {
            exoPlayer.pause()
            _isPlaying.value = false
        }
    }

    fun previous(streamUrl: (Track) -> String) {
        if (queue.isEmpty()) return
        if (exoPlayer.currentPosition > 3000) {
            seekTo(0)
            return
        }
        queueIndex = if (queueIndex > 0) queueIndex - 1 else queue.lastIndex
        exoPlayer.seekTo(queueIndex, 0L)
        exoPlayer.play()
        _currentTrack.value = queue[queueIndex]
        startPlaybackService()
    }

    fun toggleLoop(): Int {
        loopMode = (loopMode + 1) % 3
        // Keep ExoPlayer repeat mode in sync
        exoPlayer.repeatMode = when (loopMode) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        return loopMode
    }

    fun toggleShuffle(): Boolean {
        shuffle = !shuffle
        exoPlayer.shuffleModeEnabled = shuffle
        return shuffle
    }

    fun playbackState(): Int = exoPlayer.playbackState

    fun stop() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _isPlaying.value = false
        _currentTrack.value = null
        _positionMs.value = 0L
        _durationMs.value = 0L
        stopPlaybackService()
    }

    // Kept for compatibility; with the playlist approach this is handled by onPlaybackStateChanged
    fun handleTrackEnded(streamUrl: (Track) -> String, onIncrementPlay: (Int) -> Unit) {
        // no-op: ExoPlayer handles playlist advancement automatically
    }

    fun release() {
        stop()
        exoPlayer.removeListener(listener)
        detachFromSession()
        exoPlayer.release()
    }

    companion object {
        @Volatile
        var activePlayer: ExoPlayer? = null
    }
}
