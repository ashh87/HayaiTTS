package dev.ahmedmohamed.hayaitts.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import java.io.File
import java.io.IOException

/** A plain (blocking) Worker: downloads a big file (model.onnx, a voice bundle) as a foreground worker, so it keeps going while the app is in the background. */
class ModelDownload(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val url = inputData.getString(URL) ?: return Result.failure()
        val stamp = inputData.getString(STAMP) ?: return Result.failure()
        val name = inputData.getString(NAME) ?: return Result.failure()
        val path = inputData.getStringArray(PATH) ?: return Result.failure()
        setForegroundAsync(info(name, 0)).get()
        var shown = -1
        return try {
            LabServer(url, cacheDir(applicationContext)).file(name, stamp, *path) { f ->
                val pct = (f * 100).toInt()
                if (pct != shown) { // once per percent, not per 64 KB
                    shown = pct
                    setProgressAsync(workDataOf(PROGRESS to f))
                    setForegroundAsync(info(name, pct))
                }
            }
            Result.success()
        } catch (e: IOException) {
            Result.failure(workDataOf(ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun info(name: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $name")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId(name), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId(name), n)
    }

    companion object {
        private const val NAME = "name"
        private const val PATH = "path"
        private const val CHANNEL = "download"
        /** One notification per file, so downloads running together don't share a bar (1 is the questions notification). */
        private fun notificationId(name: String) = 2 + (name.hashCode() and 0xffff)
        private const val URL = "url"
        private const val STAMP = "stamp"
        private const val PROGRESS = "progress"
        private const val ERROR = "error"

        private fun cacheDir(context: Context) = File(context.filesDir, "cache")

        /**
         * Downloads the server's [path] to the cache as [name] (or joins that download if it's already running)
         * and waits for it; [progress] gets the fraction done.
         */
        suspend fun fetch(context: Context, url: String, name: String, stamp: String, vararg path: String, progress: (Float) -> Unit): File {
            val wm = WorkManager.getInstance(context)
            val req = OneTimeWorkRequestBuilder<ModelDownload>()
                .setInputData(workDataOf(URL to url, STAMP to stamp, NAME to name, PATH to path))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork("download-$name", ExistingWorkPolicy.KEEP, req).await()
            val done = wm.getWorkInfosForUniqueWorkFlow("download-$name")
                .mapNotNull { it.firstOrNull() }
                .onEach { w -> w.progress.getFloat(PROGRESS, -1f).takeIf { it >= 0 }?.let(progress) }
                .first { it.state.isFinished }
            if (done.state != WorkInfo.State.SUCCEEDED) {
                throw IOException(done.outputData.getString(ERROR) ?: "$name download ${done.state.name.lowercase()}")
            }
            return File(cacheDir(context), name)
        }
    }
}
