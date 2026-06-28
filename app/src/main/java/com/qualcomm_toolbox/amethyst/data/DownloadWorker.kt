package com.qualcomm_toolbox.amethyst.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.qualcomm_toolbox.amethyst.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DownloadWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val tracksJson = inputData.getString("tracks_json") ?: return@withContext ListenableWorker.Result.failure()
        val trackIds = inputData.getIntArray("track_ids") ?: return@withContext ListenableWorker.Result.failure()
        val serverUrl = inputData.getString("server_url") ?: return@withContext ListenableWorker.Result.failure()
        val username = inputData.getString("username")
        val password = inputData.getString("password")
        val trustAll = inputData.getBoolean("trust_all", false)

        val trackArray = JSONArray(tracksJson)
        val tracks = (0 until trackArray.length()).map { i ->
            Track.fromJson(trackArray.getJSONObject(i))
        }

        if (tracks.isEmpty()) return@withContext ListenableWorker.Result.success()

        val downloader = TrackDownloader()
        val library = OfflineLibrary(applicationContext)
        val persistence = SessionPersistence(applicationContext)
        val cookieJar = PersistentCookieJar(serverUrl, persistence)
        val purple = PurpleClient(serverUrl, trustAll, cookieJar).apply {
            setCredentials(username, password)
        }

        var successCount = 0
        var failCount = 0

        tracks.forEachIndexed { index, track ->
            if (isStopped) return@withContext ListenableWorker.Result.retry()

            val displayTitle = "(${index + 1}/${tracks.size}) ${track.title}"
            try {
                setForeground(createForegroundInfo(displayTitle))
            } catch (e: Exception) {
                android.util.Log.e("DownloadWorker", "Failed to set foreground", e)
            }

            try {
                downloader.download(
                    httpClient = purple.okHttpClient,
                    purple = purple,
                    track = track,
                    serverUrl = serverUrl,
                    library = library,
                    onProgress = { progress ->
                        setProgressAsync(workDataOf(
                            "progress" to progress,
                            "current_id" to track.id,
                            "track_ids" to trackIds,
                            "index" to index,
                            "total" to tracks.size
                        ))
                    }
                )
                successCount++
            } catch (e: Exception) {
                android.util.Log.e("DownloadWorker", "Download failed for ${track.title}", e)
                failCount++
            }
        }

        if (failCount > 0 && successCount == 0) {
            ListenableWorker.Result.failure(workDataOf("error" to "All downloads failed"))
        } else {
            ListenableWorker.Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Starting downloads...")
    }

    private fun createForegroundInfo(content: String): ForegroundInfo {
        val id = "download_channel"
        val name = "Downloads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, id)
            .setContentTitle("Amethyst Music Download")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_music_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
