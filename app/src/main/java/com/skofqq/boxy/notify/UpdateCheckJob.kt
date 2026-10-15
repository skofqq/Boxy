package com.skofqq.boxy.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.skofqq.boxy.BoxyApp
import com.skofqq.boxy.MainActivity
import com.skofqq.boxy.R
import com.skofqq.boxy.data.UpdateInterval
import com.skofqq.boxy.net.Updates
import com.skofqq.boxy.util.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background check for new versions of the app (GitHub releases) and the module (its updateJson),
 * as often as Settings → Automatic update check says. A found version is announced once.
 */
class UpdateCheckJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                check(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val TAG = "UpdateCheckJob"
        private const val JOB_ID = 4207
        const val CHANNEL_ID = "updates"
        private const val APP_NOTIFICATION_ID = 3
        private const val MODULE_NOTIFICATION_ID = 4
        private const val SEEN = "update_check"

        /** Schedules (or cancels) the periodic check for the interval in the preferences. */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val interval = BoxyApp.instance.prefs.updateInterval
            if (interval == UpdateInterval.OFF) {
                js.cancel(JOB_ID)
                return
            }
            // Rescheduling restarts the period, so an already planned job with the same interval stays.
            if (js.getPendingJob(JOB_ID)?.intervalMillis == interval.millis) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setPeriodic(interval.millis, interval.millis / 8)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        suspend fun check(context: Context) {
            val seen = context.getSharedPreferences(SEEN, Context.MODE_PRIVATE)
            val current = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return
            Updates.appReleases()?.firstOrNull { !it.prerelease && it.apkUrl != null }?.version
                ?.takeIf { Updates.isNewer(it, current) && it != seen.getString("app", null) }
                ?.let { version ->
                    if (notify(context, APP_NOTIFICATION_ID, R.string.notify_update_app_title, R.string.notify_update_app_text, version)) {
                        seen.edit().putString("app", version).apply()
                    }
                }
            runCatching { Updates.moduleUpdate()?.first }.getOrNull()
                ?.takeIf { it.versionCode != seen.getLong("module", 0) }
                ?.let { u ->
                    if (notify(context, MODULE_NOTIFICATION_ID, R.string.notify_update_module_title, R.string.notify_update_module_text, u.version)) {
                        seen.edit().putLong("module", u.versionCode).apply()
                    }
                }
        }

        private fun notify(context: Context, id: Int, title: Int, text: Int, version: String): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val ctx = context.withAppLocale()
            createChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx,
                id,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(ctx.getString(title))
                .setContentText(ctx.getString(text, version))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
            return true
        }

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notify_channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
