package dev.ahmedmohamed.hayaitts.lab

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/** Every 15 minutes (Android's minimum; it may wait longer), asks the lab server for questions and notifies about new ones. */
class QuestionsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val url = Questions.prefs(applicationContext).getString("url", "").orEmpty()
        if (url.isBlank()) return Result.success()
        val open = runCatching { LabServer(url, File(applicationContext.filesDir, "cache")).questions().filterNot { it.answered } }
            .getOrElse { return Result.success() } // server off: try again next time
        Questions.notifyNew(applicationContext, open)
        return Result.success()
    }
}

object Questions {
    private const val CHANNEL = "questions"
    private const val NOTIFIED = "notified_questions"
    const val EXTRA_TAB = "tab"

    fun prefs(context: Context) = context.getSharedPreferences("lab", Context.MODE_PRIVATE)

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<QuestionsWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("questions", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun markSeen(context: Context, ids: List<String>) {
        val p = prefs(context)
        p.edit().putStringSet(NOTIFIED, p.getStringSet(NOTIFIED, emptySet()).orEmpty() + ids).apply()
    }

    /** Posts one notification when [open] holds questions not notified before; remembers them either way. */
    fun notifyNew(context: Context, open: List<LabServer.Question>) {
        val p = prefs(context)
        val seen = p.getStringSet(NOTIFIED, emptySet()).orEmpty()
        val fresh = open.filter { it.id !in seen }
        p.edit().putStringSet(NOTIFIED, seen + fresh.map { it.id }).apply()
        if (fresh.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening questions", NotificationManager.IMPORTANCE_DEFAULT))
        val open3 = PendingIntent.getActivity(
            context, 0, Intent(context, LabActivity::class.java).putExtra(EXTRA_TAB, 3).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sets = fresh.mapNotNull { it.set }.distinct()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("${fresh.size} new listening question${if (fresh.size == 1) "" else "s"}")
            .setContentText(if (sets.isNotEmpty()) sets.joinToString() else fresh.first().question)
            .setContentIntent(open3)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // checked above
        NotificationManagerCompat.from(context).notify(1, n)
    }
}
