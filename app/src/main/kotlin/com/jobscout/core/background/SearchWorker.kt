package com.jobscout.core.background

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
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.jobscout.R
import com.jobscout.app.MainActivity
import com.jobscout.domain.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class WorkScheduler @Inject constructor(@param:ApplicationContext private val context: Context) : BackgroundScheduler {
    override fun configure(enabled: Boolean, intervalHours: Long) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork(NAME); return }
        val request = PeriodicWorkRequestBuilder<SearchWorker>(intervalHours.coerceIn(3, 168), TimeUnit.HOURS)
            .setInitialDelay(intervalHours.coerceIn(3, 168), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
    companion object { const val NAME = "jobscout-periodic-search" }
}

@HiltWorker
class SearchWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ScoutRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!repository.preferences.first().background) return Result.success()
        return try {
            val summary = repository.search()
            if (summary.reports.all { it.error != null }) return if (runAttemptCount < 2) Result.retry() else Result.failure()
            val newIds = summary.newRelevantIds
            if (newIds.isNotEmpty() && canNotify()) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("matches", "Novas vagas", NotificationManager.IMPORTANCE_DEFAULT))
                val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                try {
                    NotificationManagerCompat.from(context).notify(1001, NotificationCompat.Builder(context, "matches")
                        .setSmallIcon(R.drawable.ic_notification).setContentTitle("${newIds.size} novas vagas compatíveis")
                        .setContentText("Abra o JobScout para conferir os links e os critérios de compatibilidade.")
                        .setContentIntent(pending).setAutoCancel(true).build())
                    repository.markNotified(newIds)
                } catch (_: SecurityException) {
                    // Permission may be revoked between checking it and posting. Search results remain stored.
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
    private fun canNotify() = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled()
}
