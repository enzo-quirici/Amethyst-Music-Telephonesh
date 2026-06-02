package com.qualcomm_toolbox.amethyst.player

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.LibraryResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.qualcomm_toolbox.amethyst.MainActivity

@UnstableApi
class MusicPlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        syncMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        syncMediaSession()
        
        mediaSession?.let { session ->
            onUpdateNotification(session, true)
        }
        
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Log.d(TAG, "onGetSession from ${controllerInfo.packageName}")
        syncMediaSession()
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = MusicPlayer.activePlayer
        if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun syncMediaSession() {
        val player = MusicPlayer.activePlayer ?: return

        if (mediaSession == null) {
            Log.d(TAG, "syncMediaSession: creating new MediaLibrarySession")
            mediaSession = MediaLibrarySession.Builder(this, player, AmethystLibraryCallback())
                .setSessionActivity(createSessionActivityIntent())
                .build()
            addSession(mediaSession!!)
        } else if (mediaSession?.player !== player) {
            Log.d(TAG, "syncMediaSession: player instance changed, recreating session")
            removeSession(mediaSession!!)
            mediaSession?.release()
            mediaSession = MediaLibrarySession.Builder(this, player, AmethystLibraryCallback())
                .setSessionActivity(createSessionActivityIntent())
                .build()
            addSession(mediaSession!!)
        }
    }

    private fun createSessionActivityIntent(): PendingIntent {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private class AmethystLibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ConnectionResult {
            Log.d(TAG, "onConnect from ${controller.packageName}")
            val commands = ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .addAllCommands()
                .build()
            return ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetLibraryRoot from ${browser.packageName}")
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }
    }

    companion object {
        private const val TAG = "MusicPlaybackService"
        const val ACTION_SYNC = "com.qualcomm_toolbox.amethyst.SYNC_SESSION"
    }
}
