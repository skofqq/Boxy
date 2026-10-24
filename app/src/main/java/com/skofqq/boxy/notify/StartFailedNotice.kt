package com.skofqq.boxy.notify

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.skofqq.boxy.BoxyApp
import com.skofqq.boxy.MainActivity
import com.skofqq.boxy.R
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.util.withAppLocale

/**
 * The service was asked to start from outside the app (widget, tile, status notification, automation, schedule)
 * and did not come up: a notification with the module's reason. In the app the home page shows it instead.
 */
object StartFailedNotice {
    private const val NOTIFICATION_ID = 3

    /** Checks the service after a start or restart and tells the user when it is not running. */
    suspend fun check(context: Context) {
        if (BoxyApp.foreground) return
        if (runCatching { BoxModule.state().running }.getOrDefault(true)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val reason = BoxModule.startFailure()
        val ctx = context.withAppLocale()
        ModuleEventReceiver.createChannel(ctx)
        val missingCore = reason != null && Regex("kernel .* is missing", RegexOption.IGNORE_CASE).containsMatchIn(reason)
        val text = ctx.getString(if (missingCore) R.string.start_failed_core_missing else R.string.start_failed_notify)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, ModuleEventReceiver.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(ctx.getString(R.string.start_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (reason != null) "$text\n$reason" else text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }
}
