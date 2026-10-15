package com.skofqq.boxy.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.skofqq.boxy.R
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.Environment
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.launch
import com.skofqq.boxy.ui.components.withTap1

/** First-run flow: agreement, permissions, how to use. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(0) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = page > 0) { page-- }

    Column(Modifier.fillMaxSize().background(Boxy.colors.page).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(56.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium, color = Boxy.colors.text)
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text2)
                }
            }
            Spacer(Modifier.height(20.dp))
            when (page) {
                0 -> AgreementPage(agreed) { agreed = it }
                1 -> PermissionsPage()
                else -> UsagePage()
            }
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { i ->
                Box(Modifier.padding(end = 6.dp).size(if (i == page) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i == page) Boxy.colors.accent else Boxy.colors.outline))
            }
            Spacer(Modifier.weight(1f))
            val enabled = page != 0 || agreed
            Text(
                stringResource(if (page == 2) R.string.onboarding_finish else R.string.onboarding_next),
                Modifier.clip(RoundedCornerShape(50)).background(if (enabled) Boxy.colors.accent else Boxy.colors.outline)
                    .clickable(enabled = enabled) { if (page == 2) onFinish() else page++ }
                    .padding(horizontal = 26.dp, vertical = 13.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(22.dp)).background(Boxy.colors.card).padding(18.dp)) { content() }
}

@Composable
private fun AgreementPage(agreed: Boolean, onAgree: (Boolean) -> Unit) {
    Card {
        Text(stringResource(R.string.onboarding_agreement_title), style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_disclaimer), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.clickable { onAgree(!agreed) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(agreed, withTap1(onAgree), colors = CheckboxDefaults.colors(checkedColor = Boxy.colors.accent))
            Text(stringResource(R.string.onboarding_accept), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text)
        }
    }
}

@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf<Boolean?>(null) }
    var notifications by remember { mutableStateOf(notificationsGranted(context)) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    LaunchedEffect(Unit) { root = BoxModule.environment() != Environment.NO_ROOT }

    Text(stringResource(R.string.onboarding_permissions), style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text, modifier = Modifier.padding(bottom = 12.dp))
    PermissionCard(BoxyIcons.Memory, R.string.onboarding_perm_root, R.string.onboarding_perm_root_desc, root == true) {
        scope.launch { root = BoxModule.environment() != Environment.NO_ROOT }
    }
    PermissionCard(BoxyIcons.Notifications, R.string.onboarding_perm_notifications, R.string.onboarding_perm_notifications_desc, notifications) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else notifications = true
    }
    PermissionCard(BoxyIcons.Apps, R.string.onboarding_perm_apps, R.string.onboarding_perm_apps_desc, null, R.string.onboarding_open_settings) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    }
    PermissionCard(BoxyIcons.Wifi, R.string.onboarding_perm_wifi, R.string.onboarding_perm_wifi_desc, null, null) {}
}

private fun notificationsGranted(context: android.content.Context) =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionCard(icon: ImageVector, title: Int, desc: Int, granted: Boolean?, action: Int? = R.string.onboarding_grant, onClick: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = Boxy.colors.accent)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Boxy.colors.text)
            when {
                granted == true -> Icon(BoxyIcons.Check, null, tint = Tints.green.fg)
                action != null -> Text(
                    stringResource(action),
                    Modifier.clip(RoundedCornerShape(50)).background(Boxy.colors.accent.copy(alpha = 0.14f)).clickable(onClick = onClick)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    color = Boxy.colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = Boxy.colors.text2)
    }
}

@Composable
private fun UsagePage() {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Card {
            Text(stringResource(R.string.onboarding_usage_title), style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_usage_body), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
        }
        Card {
            Text(stringResource(R.string.onboarding_tips_title), style = MaterialTheme.typography.titleLarge, color = Boxy.colors.text)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.onboarding_tips_body), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
        }
    }
}
