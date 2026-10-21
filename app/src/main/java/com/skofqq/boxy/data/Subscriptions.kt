package com.skofqq.boxy.data

import android.Manifest
import android.app.NotificationChannel
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
import com.skofqq.boxy.net.Net
import com.skofqq.boxy.net.SubscriptionInfo
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.util.Format
import com.skofqq.boxy.util.withAppLocale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Subscription quotas (traffic left, expiry) from the source chosen in Settings, the cache the Home card
 * shows, and a warning when one is about to run out. The check rides on the existing 15-minute traffic
 * alarm (which only fires while the phone is awake anyway) and asks at most every 6 hours.
 */
object Subscriptions {
    private const val PREFS = "subscriptions"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val CHANNEL_ID = "subscription"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    suspend fun fetch(prefs: Prefs, core: String? = null): List<SubscriptionInfo> = when (prefs.subscriptionSource) {
        SubscriptionSource.CORE_API -> Net.clashApi(core ?: BoxModule.readSetting("bin_name"))?.let { Net.clashProviders(it) }.orEmpty()
        SubscriptionSource.PROVIDERS -> {
            val urls = Net.providerUrls()
            coroutineScope { urls.map { (name, url) -> async { Net.subscription(url)?.copy(name = name) } }.awaitAll() }.filterNotNull()
        }
        SubscriptionSource.URL -> {
            val urls = BoxModule.parseArray(BoxModule.readSettingRaw("subscription_url_clash"))
            coroutineScope { urls.map { url -> async { Net.subscription(url) } }.awaitAll() }.filterNotNull()
        }
    }

    fun cached(prefs: Prefs): List<SubscriptionInfo> = runCatching {
        val arr = JSONArray(prefs.subscriptionCache ?: return emptyList())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SubscriptionInfo(
                o.optString("name").ifBlank { null }, o.optLong("up"), o.optLong("down"),
                o.optLong("total"), o.optLong("expire"), o.optLong("at"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(prefs: Prefs, list: List<SubscriptionInfo>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("name", it.name ?: "").put("up", it.upload).put("down", it.download)
                    .put("total", it.total).put("expire", it.expire).put("at", it.updatedAt),
            )
        }
        prefs.subscriptionCache = arr.toString()
    }

    /** Background check (from the traffic alarm): warns once when traffic or time is about to run out. */
    suspend fun check(context: Context) {
        val prefs = BoxyApp.instance.prefs
        if (!prefs.notifySubsEnding) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("checked_at", 0) < CHECK_EVERY_MS) return
        // The core's numbers need it running; the URL sources work anyway.
        if (prefs.subscriptionSource == SubscriptionSource.CORE_API && runCatching { BoxModule.state().running }.getOrDefault(false).not()) return
        val list = runCatching { fetch(prefs) }.getOrDefault(emptyList())
        sp.edit().putLong("checked_at", now).apply()
        if (list.isEmpty()) return
        save(prefs, list)
        warn(context, list)
    }

    fun warn(context: Context, list: List<SubscriptionInfo>) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ctx = context.withAppLocale()
        val now = System.currentTimeMillis()
        list.forEachIndexed { i, s ->
            val name = s.name ?: ctx.getString(R.string.card_subscription)
            if (s.total > 0 && s.remaining <= s.total / 10) {
                once(sp, "t|$name|${s.total}") {
                    notify(
                        ctx, 100 + i * 2,
                        ctx.getString(R.string.notify_sub_ending_title, name),
                        ctx.getString(R.string.notify_sub_traffic_left, Format.bytes(ctx, s.remaining), Format.bytes(ctx, s.total)),
                    )
                }
            }
            if (s.expire > 0) {
                val left = s.expire * 1000 - now
                if (left in 0..(3 * DAY_MS)) {
                    once(sp, "e|$name|${s.expire}") {
                        notify(
                            ctx, 101 + i * 2,
                            ctx.getString(R.string.notify_sub_ending_title, name),
                            ctx.getString(
                                R.string.notify_sub_expires,
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.expire * 1000)),
                                (left / DAY_MS).toInt(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private inline fun once(sp: android.content.SharedPreferences, key: String, block: () -> Boolean) {
        if (sp.getBoolean("n|$key", false)) return
        if (block()) sp.edit().putBoolean("n|$key", true).apply()
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, ctx.getString(R.string.notify_channel_subscription), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            id,
            NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
        return true
    }
}
