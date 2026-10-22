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
import androidx.graphics.shapes.toPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
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
    /** The illustration morphs between [shape] and this one. */
    val morphTo: RoundedPolygon,
    val title: Int,
    val body: Int?,
    val tint: @Composable () -> Color,
)

/** How long an info story stays before the next one opens by itself. */
private const val STORY_MS = 7000

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun stories(introOnly: Boolean): List<Story> = buildList {
    add(Story(StoryKind.INFO, BoxyIcons.Shield, MaterialShapes.Cookie12Sided, MaterialShapes.SoftBurst, R.string.story_welcome_title, R.string.story_welcome_body) { Tints.blue.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Power, MaterialShapes.Sunny, MaterialShapes.Cookie9Sided, R.string.story_home_title, R.string.story_home_body) { Tints.green.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Apps, MaterialShapes.Clover8Leaf, MaterialShapes.Flower, R.string.story_apps_title, R.string.story_apps_body) { Tints.purple.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Dashboard, MaterialShapes.SoftBurst, MaterialShapes.Cookie6Sided, R.string.story_panel_title, R.string.story_panel_body) { Tints.teal.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Build, MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, R.string.story_tools_title, R.string.story_tools_body) { Tints.orange.fg })
    add(Story(StoryKind.INFO, BoxyIcons.Bolt, MaterialShapes.Flower, MaterialShapes.Sunny, R.string.story_quick_title, R.string.story_quick_body) { Tints.amber.fg })
    if (!introOnly) {
        add(Story(StoryKind.AGREEMENT, BoxyIcons.Description, MaterialShapes.Cookie7Sided, MaterialShapes.Cookie7Sided, R.string.onboarding_agreement_title, null) { Tints.gray.fg })
        add(Story(StoryKind.PERMISSIONS, BoxyIcons.Memory, MaterialShapes.Cookie6Sided, MaterialShapes.Cookie6Sided, R.string.onboarding_permissions, null) { Boxy.colors.accent })
    }
}

/**
 * Welcome as stories, laid out like Musixmatch's first run: the app logo with one segment per story at the
 * top, a large headline and a line of text, then the illustration. Info stories move on by themselves; a tap
 * on the right goes forward, on the left back, holding pauses. On the first run the agreement and the
 * permissions follow as setup pages without segments ([introOnly] replays just the stories from Settings).
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit, introOnly: Boolean = false) {
    val list = remember(introOnly) { stories(introOnly) }
    val infoCount = list.count { it.kind == StoryKind.INFO }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val story = list[index]

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

    Column(Modifier.fillMaxSize().background(Boxy.colors.page).statusBarsPadding().navigationBarsPadding()) {
        androidx.compose.animation.AnimatedVisibility(story.kind == StoryKind.INFO) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppLogo(40.dp)
                Spacer(Modifier.width(16.dp))
                // One segment per story: done ones full, the current one filling up, the rest dim.
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(infoCount) { i ->
                        val fill = when {
                            i < index -> 1f
                            i > index -> 0f
                            else -> progress.value
                        }
                        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(Boxy.colors.accent.copy(alpha = 0.22f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(50)).background(Boxy.colors.accent))
                        }
                    }
                }
            }
        }

        AnimatedContent(
            index,
            Modifier.weight(1f).fillMaxWidth().pointerInput(story.kind) {
                // Setup pages have their own controls; only info stories react to taps.
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
            if (s.kind == StoryKind.INFO) {
                InfoStory(s)
            } else {
                SetupPage(s, isLast = i == list.lastIndex, enabled = s.kind != StoryKind.AGREEMENT || agreed, onNext = ::next) {
                    if (s.kind == StoryKind.AGREEMENT) AgreementStory(agreed) { agreed = it } else PermissionsStory()
                }
            }
        }
    }
}

/** Info story: large headline and text on the left, the illustration in a tinted card below. */
@Composable
private fun InfoStory(s: Story) {
    // Headline, text and card come in one after another with a spring.
    val enter = remember { List(3) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        enter.forEachIndexed { k, a ->
            launch {
                kotlinx.coroutines.delay(70L * k)
                a.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f))
            }
        }
    }
    fun Modifier.rise(k: Int) = graphicsLayer {
        alpha = enter[k].value.coerceIn(0f, 1f)
        translationY = (1f - enter[k].value) * 36.dp.toPx()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(s.title),
            Modifier.rise(0),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = Boxy.colors.text,
        )
        Spacer(Modifier.height(14.dp))
        Text(stringResource(s.body!!), Modifier.rise(1), style = MaterialTheme.typography.bodyLarge, color = Boxy.colors.text2)
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(bottom = 20.dp).rise(2).clip(RoundedCornerShape(32.dp))
                .background(s.tint().copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            StoryArt(s, 250.dp)
        }
    }
}

/** Setup page after the stories (agreement, permissions): centred logo and title, its controls, a button. */
@Composable
private fun SetupPage(s: Story, isLast: Boolean, enabled: Boolean, onNext: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(36.dp))
            AppLogo(72.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(s.title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Boxy.colors.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            content()
        }
        Text(
            stringResource(if (isLast) R.string.onboarding_start else R.string.onboarding_next),
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth().clip(RoundedCornerShape(50))
                .background(if (enabled) Boxy.colors.accent else Boxy.colors.outline)
                .clickable(enabled = enabled, onClick = onNext)
                .padding(vertical = 16.dp),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun AppLogo(size: Dp) {
    Box(Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(size * 1.5f))
    }
}

/**
 * Material 3 Expressive art: a large shape that keeps morphing between two shapes while it turns, small
 * shapes orbiting around it, and the symbol on a gently breathing cookie in the middle. Everything springs in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StoryArt(s: Story, size: Dp) {
    val tint = s.tint()
    val loop = rememberInfiniteTransition(label = "art")
    val turn by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "turn")
    val morph by loop.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(2400, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "morph",
    )
    val breathe by loop.animateFloat(
        0.94f,
        1.06f,
        infiniteRepeatable(tween(1700, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "breathe",
    )
    val shapes = remember(s) { androidx.graphics.shapes.Morph(s.shape, s.morphTo) }
    val path = remember { android.graphics.Path() }
    // The big shape, then each orbiting one, spring in one after another.
    val appear = remember { List(4) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        appear.forEachIndexed { k, a ->
            launch {
                kotlinx.coroutines.delay(90L * k)
                a.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 240f))
            }
        }
    }
    val satellites = remember { listOf(MaterialShapes.Pill, MaterialShapes.Gem, MaterialShapes.Sunny) }
    Box(Modifier.size(size * 1.35f), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(
            Modifier.size(size).graphicsLayer {
                rotationZ = turn
                scaleX = appear[0].value
                scaleY = appear[0].value
            },
        ) {
            path.rewind()
            shapes.toPath(morph, path)
            val p = path.asComposePath()
            scale(this.size.width, this.size.height, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                drawPath(p, tint.copy(alpha = 0.24f))
            }
        }
        satellites.forEachIndexed { k, shape ->
            val angle = Math.toRadians((turn * 1.6f + k * 120f).toDouble())
            val r = size.value * 0.6f
            val dot = size * (0.13f + 0.03f * k)
            Box(
                Modifier.size(dot).graphicsLayer {
                    translationX = (kotlin.math.cos(angle) * r).toFloat() * density
                    translationY = (kotlin.math.sin(angle) * r).toFloat() * density
                    rotationZ = -turn * 2
                    scaleX = appear[k + 1].value
                    scaleY = appear[k + 1].value
                }.clip(shape.toShape()).background(tint.copy(alpha = 0.45f + 0.2f * k)),
            )
        }
        Box(
            Modifier.size(size * 0.5f).graphicsLayer {
                val sc = appear[0].value * breathe
                scaleX = sc
                scaleY = sc
                rotationZ = -turn / 2
            }.clip(MaterialShapes.Cookie4Sided.toShape()).background(tint),
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
