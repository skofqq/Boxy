package com.skofqq.boxy.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.skofqq.boxy.R
import com.skofqq.boxy.data.Prefs
import com.skofqq.boxy.service.BoxStatusService
import com.skofqq.boxy.ui.components.NoticeCard
import com.skofqq.boxy.ui.components.PinnedLazyPage
import com.skofqq.boxy.ui.components.SectionCard
import com.skofqq.boxy.ui.components.SettingsRow
import com.skofqq.boxy.ui.components.SubPageHeader
import com.skofqq.boxy.ui.components.SwitchRow
import com.skofqq.boxy.ui.theme.BoxyIcons

/** Which notifications the app shows: the service status in the status bar and failed subscription updates. */
@Composable
fun NotificationsScreen(contentPadding: PaddingValues, prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    fun granted() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(granted()) }
    // The user may allow or block notifications in system settings and come back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permission = granted() }

    // What to switch on once the permission is granted.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permission = ok
        if (ok) pending?.invoke()
        pending = null
    }
    fun withPermission(action: () -> Unit) {
        if (granted()) {
            action()
        } else {
            pending = action
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    PinnedLazyPage(contentPadding, header = {
        SubPageHeader(stringResource(R.string.settings_notifications), stringResource(R.string.notify_page_sub), onBack)
    }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionCard(null) {
                if (!permission) {
                    NoticeCard(stringResource(R.string.update_tip_title), stringResource(R.string.notify_permission_off))
                    Spacer(Modifier.height(6.dp))
                }
                SwitchRow(
                    BoxyIcons.Notifications,
                    stringResource(R.string.notify_status),
                    stringResource(R.string.notify_status_sub),
                    prefs.notifications && permission,
                ) { on ->
                    if (on) {
                        withPermission {
                            prefs.updateNotifications(true)
                            BoxStatusService.sync(context, true)
                        }
                    } else {
                        prefs.updateNotifications(false)
                        BoxStatusService.sync(context, false)
                    }
                }
                SwitchRow(
                    BoxyIcons.SyncProblem,
                    stringResource(R.string.notify_subs),
                    stringResource(R.string.notify_subs_sub),
                    prefs.notifySubsFailed && permission,
                ) { on ->
                    if (on) withPermission { prefs.updateNotifySubsFailed(true) } else prefs.updateNotifySubsFailed(false)
                }
                SettingsRow(
                    BoxyIcons.Settings,
                    stringResource(R.string.notify_system),
                    stringResource(R.string.notify_system_sub),
                    showDivider = false,
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}
