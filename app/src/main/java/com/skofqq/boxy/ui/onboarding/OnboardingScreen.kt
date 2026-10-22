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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.RoundedPolygon
import com.skofqq.boxy.R
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.Environment
import com.skofqq.boxy.ui.components.withTap1
import com.skofqq.boxy.ui.theme.Boxy
import com.skofqq.boxy.ui.theme.BoxyIcons
import com.skofqq.boxy.ui.theme.Tints
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class StoryKind { INFO, AGREEMENT, PERMISSIONS }

private class Story(
    val kind: StoryKind,
    val icon: ImageVector,
    val shape: RoundedPolygon,
    val title: Int,
    val body: Int?,
    val tint: @Composable () -> Color,
)

/** How long an info story stays before the next one opens by itself. */
private const val STORY_MS = 7000

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun stories(introOnly: Boolean): List<Story> = buildList {
    add(Story(StoryKind.INFO, BoxyIcons.Shield, MaterialShapes.Cookie12Sided, R.string.story_welcome_title, R.string.story_welcome_body) { Tints.blue.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Power, MaterialShapes.Sunny, R.string.story_home_title, R.string.story_home_body) { Tints.green.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Apps, MaterialShapes.Clover8Leaf, R.string.story_apps_title, R.string.story_apps_body) { Tints.purple.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Dashboard, MaterialShapes.SoftBurst, R.string.story_panel_title, R.string.story_panel_body) { Tints.teal.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Build, MaterialShapes.Cookie9Sided, R.string.story_tools_title, R.string.story_tools_body) { Tints.orange.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Bolt, MaterialShapes.Flower, R.string.story_quick_title, R.string.story_quick_body) { Tints.amber.fg })
    if (!introOnly) {
        add(Story(StoryKind.AGREEMENT, BoxyIcons.Description, MaterialShapes.Cookie7Sided, R.string.onboarding_agreement_title, null) { Tints.gray.fg })
        add(Story(StoryKind.PERMISSIONS, BoxyIcons.Memory, MaterialShapes.Cookie6Sided, R.string.onboarding_permissions, null) { Boxy.colors.accent })
    }
}

/**
 * Welcome as stories: what Boxy is for and where things are, then the agreement and permissions (first run
 * only; [introOnly] replays just the stories from Settings). Info stories move on by themselves; a tap on the
 * right goes forward, on the left back, holding pauses.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit, introOnly: Boolean = false) {
    val list = remember(introOnly) { stories(introOnly) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val story = list[index]
    val firstStep = list.indexOfFirst { it.kind != StoryKind.INFO }.takeIf { it >= 0 }

    fun next() {
        if (index < list.lastIndex) {
            forward = true
            index++
        } else {
            onFinish()
        }
    }

    fun prev() {
        if (index > 0) {
            forward = false
            index--
        }
    }

    LaunchedEffect(index) {
        progress.snapTo(0f)
        if (story.kind != StoryKind.INFO) return@LaunchedEffect
        snapshotFlow { paused }.collectLatest { p ->
            if (!p) {
                progress.animateTo(1f, tween(((1f - progress.value) * STORY_MS).toInt(), easing = LinearEasing))
                next()
            }
        }
    }
    BackHandler(enabled = index > 0) { prev() }

    val tint = story.tint()
    Box(
        Modifier.fillMaxSize().background(Boxy.colors.page)
            .background(Brush.verticalGradient(0f to tint.copy(alpha = 0.30f), 0.65f to Boxy.colors.page)),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Segments: done, current (filling up), still to come.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEachIndexed { i, s ->
                    val fill = when {
                        i < index -> 1f
                        i > index -> 0f
                        s.kind == StoryKind.INFO -> progress.value
                        else -> 1f
                    }
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.text.copy(alpha = 0.18f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Boxy.colors.text.copy(alpha = 0.85f)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    Modifier.padding(start = 12.dp).weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Boxy.colors.text,
                )
                if (story.kind == StoryKind.INFO) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        Modifier.clip(RoundedCornerShape(50)).clickable {
                            if (firstStep == null) {
                                onFinish()
                            } else {
                                forward = true
                                index = firstStep
                            }
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = Boxy.colors.text2,
                    )
                }
            }

            AnimatedContent(
                index,
                Modifier.weight(1f).fillMaxWidth().pointerInput(story.kind) {
                    // Agreement and permissions have their own controls; only info stories react to taps.
                    if (story.kind != StoryKind.INFO) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            paused = true
                            tryAwaitRelease()
                            paused = false
                        },
                        onTap = { if (it.x < size.width / 3f) prev() else next() },
                    )
                },
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (fadeIn(tween(260)) + slideInHorizontally(tween(320)) { dir * it / 5 }) togetherWith
                        (fadeOut(tween(180)) + slideOutHorizontally(tween(320)) { -dir * it / 5 })
                },
                label = "story",
            ) { i ->
                val s = list[i]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (s.kind == StoryKind.INFO) Arrangement.Center else Arrangement.Top,
                ) {
                    Spacer(Modifier.height(if (s.kind == StoryKind.INFO) 0.dp else 12.dp))
                    StoryArt(s, if (s.kind == StoryKind.INFO) 210.dp else 120.dp)
                    Spacer(Modifier.height(if (s.kind == StoryKind.INFO) 36.dp else 20.dp))
                    Text(
                        stringResource(s.title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Boxy.colors.text,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    when (s.kind) {
                        StoryKind.INFO -> Text(
                            stringResource(s.body!!),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Boxy.colors.text2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        StoryKind.AGREEMENT -> AgreementStory(agreed) { agreed = it }
                        StoryKind.PERMISSIONS -> PermissionsStory()
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            val enabled = story.kind != StoryKind.AGREEMENT || agreed
            Text(
                stringResource(if (index == list.lastIndex) R.string.onboarding_start else R.string.onboarding_next),
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth().clip(RoundedCornerShape(50))
                    .background(if (enabled) Boxy.colors.accent else Boxy.colors.outline)
                    .clickable(enabled = enabled) { next() }
                    .padding(vertical = 16.dp),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Expressive art: a slowly turning shape in the story colour with the symbol on a second shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StoryArt(s: Story, size: Dp) {
    val tint = s.tint()
    val turn by rememberInfiniteTransition(label = "art").animateFloat(
        0f,
        360f,
        infiniteRepeatable(tween(30_000, easing = LinearEasing)),
        label = "turn",
    )
    val appear = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
    Box(Modifier.size(size).graphicsLayer { scaleX = appear.value; scaleY = appear.value }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).graphicsLayer { rotationZ = turn }.clip(s.shape.toShape()).background(tint.copy(alpha = 0.22f)))
        Box(
            Modifier.size(size * 0.5f).graphicsLayer { rotationZ = -turn / 2 }.clip(MaterialShapes.Cookie4Sided.toShape()).background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(s.icon, null, Modifier.size(size * 0.24f).graphicsLayer { rotationZ = turn / 2 }, tint = Color.White)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(22.dp)).background(Boxy.colors.card).padding(18.dp)) { content() }
}

@Composable
private fun AgreementStory(agreed: Boolean, onAgree: (Boolean) -> Unit) {
    Card {
        Text(stringResource(R.string.onboarding_disclaimer), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text2)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onAgree(!agreed) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(agreed, withTap1(onAgree), colors = CheckboxDefaults.colors(checkedColor = Boxy.colors.accent))
            Text(stringResource(R.string.onboarding_accept), style = MaterialTheme.typography.bodyMedium, color = Boxy.colors.text)
        }
    }
}

@Composable
private fun PermissionsStory() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf<Boolean?>(null) }
    var notifications by remember { mutableStateOf(notificationsGranted(context)) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    LaunchedEffect(Unit) { root = BoxModule.environment() != Environment.NO_ROOT }

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
