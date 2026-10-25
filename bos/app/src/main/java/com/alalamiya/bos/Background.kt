package com.alalamiya.bos

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alalamiya.bos.data.BosDatabase
import java.util.concurrent.TimeUnit

object Background {
    const val CHANNEL = "due_reminders"

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "مواعيد السداد", NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun scheduleAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork("due_reminders", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<DueWorker>(1, TimeUnit.DAYS).build())
        setAutoBackup(ctx, Prefs.autoBackup(ctx))
    }

    fun setAutoBackup(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (on) {
            wm.enqueueUniquePeriodicWork("auto_backup", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build())
        } else {
            wm.cancelUniqueWork("auto_backup")
        }
    }
}

class DueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val db = BosDatabase.get(applicationContext)
        val due = db.customers().dueCustomers(System.currentTimeMillis() + 24L * 3600 * 1000)
        if (due.isEmpty()) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()
        val pi = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, Background.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("مواعيد سداد")
            .setContentText("${due.size} عميل حان موعد سداده أو تأخر")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(1, n)
        return Result.success()
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            Backup.autoBackup(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
