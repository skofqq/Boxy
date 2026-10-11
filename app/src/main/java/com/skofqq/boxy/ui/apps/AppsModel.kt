package com.skofqq.boxy.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.skofqq.boxy.root.BoxModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed app in one Android user. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val userId: Int,
    val system: Boolean,
    val network: Boolean,
    val installTime: Long,
    /**
     * For loading the label and icon: this user's ApplicationInfo, or for apps that exist only in another
     * user (work profile, second space) one read from the app's APK.
     */
    val info: ApplicationInfo?,
) {
    /** Entry as stored in package.list.cfg: "pkg" for user 0, "id:pkg" otherwise. */
    val key: String get() = if (userId == 0) packageName else "$userId:$packageName"
}

data class AndroidUser(val id: Int, val name: String)

enum class ProxyMode { BLACKLIST, WHITELIST }

data class AppRules(val mode: ProxyMode, val keys: Set<String>, val rawLines: List<String>)

object AppsRepository {
    const val PKG_CONFIG = "${BoxModule.BOX_DIR}/package.list.cfg"

    suspend fun users(): List<AndroidUser> = withContext(Dispatchers.IO) {
        val (_, out) = BoxModule.exec("pm list users")
        val re = Regex("UserInfo\\{(\\d+):([^:}]*)")
        out.mapNotNull { line -> re.find(line)?.let { AndroidUser(it.groupValues[1].toInt(), it.groupValues[2]) } }
            .ifEmpty { listOf(AndroidUser(0, "Owner")) }
    }

    suspend fun apps(context: Context, users: List<AndroidUser>): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val own = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).associateBy { it.packageName }
        val result = mutableListOf<AppEntry>()
        own.values.forEach { p ->
            val ai = p.applicationInfo ?: return@forEach
            result += AppEntry(
                packageName = p.packageName,
                label = ai.loadLabel(pm).toString(),
                userId = 0,
                system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                network = p.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true,
                installTime = p.firstInstallTime,
                info = ai,
            )
        }
        // Other users (second space, work profile, app clones) are listed through the package manager shell,
        // with APK paths (-f): an app installed only there gets its label and icon from its APK.
        users.filter { it.id != 0 }.forEach { user ->
            val (_, all) = BoxModule.exec("pm list packages -f --user ${user.id}")
            val (_, sys) = BoxModule.exec("pm list packages -s --user ${user.id}")
            val systemSet = sys.map { it.removePrefix("package:").trim() }.toSet()
            all.mapNotNull { line ->
                val entry = line.removePrefix("package:").trim()
                val eq = entry.lastIndexOf('=')
                if (eq <= 0) null else entry.substring(eq + 1) to entry.substring(0, eq)
            }.filter { (pkg, _) -> pkg.isNotEmpty() && !pkg.contains(' ') }.forEach { (pkg, apk) ->
                val base = own[pkg] ?: fromApk(pm, apk)
                result += AppEntry(
                    packageName = pkg,
                    label = base?.applicationInfo?.loadLabel(pm)?.toString() ?: pkg,
                    userId = user.id,
                    system = pkg in systemSet,
                    network = base?.requestedPermissions?.contains(android.Manifest.permission.INTERNET) ?: true,
                    installTime = base?.firstInstallTime ?: 0,
                    info = base?.applicationInfo,
                )
            }
        }
        result
    }

    /** Package info read from an APK file (APKs under /data/app are readable by apps). */
    private fun fromApk(pm: PackageManager, apk: String): android.content.pm.PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(apk, PackageManager.GET_PERMISSIONS)?.also { p ->
            p.applicationInfo?.sourceDir = apk
            p.applicationInfo?.publicSourceDir = apk
        }
    }.getOrNull()

    suspend fun loadRules(): AppRules = withContext(Dispatchers.IO) {
        val text = BoxModule.readFile(PKG_CONFIG).orEmpty()
        val lines = text.lines()
        val mode = lines.firstOrNull { it.startsWith("mode:") }?.substringAfter("mode:")?.trim()?.lowercase()
        val keys = lines.map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("mode:") && it.contains('.') }
            .map { it.split(Regex("\\s+")).first() }
            .toSet()
        AppRules(if (mode == "whitelist" || mode == "white") ProxyMode.WHITELIST else ProxyMode.BLACKLIST, keys, lines)
    }

    /** Rewrites the list, keeping the module's comment header as it was. */
    suspend fun saveRules(rules: AppRules, mode: ProxyMode, keys: Collection<String>): Boolean {
        val modeLine = "mode:" + if (mode == ProxyMode.WHITELIST) "whitelist" else "blacklist"
        val header = rules.rawLines.filter { it.startsWith("#") || it.startsWith("mode:") || it.isBlank() }.dropLastWhile { it.isBlank() }
            .map { if (it.startsWith("mode:")) modeLine else it }
            .ifEmpty { listOf("# black/white list mode.", modeLine) }
            .let { if (it.none { l -> l.startsWith("mode:") }) listOf(modeLine) + it else it }
        val body = header + keys.sorted()
        return BoxModule.writeFile(PKG_CONFIG, body.joinToString("\n") + "\n")
    }

    /** Only the mode line changes (used by the mode switch). */
    suspend fun saveMode(mode: ProxyMode): Boolean {
        val value = if (mode == ProxyMode.WHITELIST) "whitelist" else "blacklist"
        return BoxModule.exec(
            "if grep -q '^mode:' $PKG_CONFIG; then sed -i 's/^mode:.*/mode:$value/' $PKG_CONFIG; else sed -i '1i mode:$value' $PKG_CONFIG; fi",
        ).first
    }

    /**
     * A simple guess of which apps to select: in whitelist mode, well-known foreign services that
     * usually need a proxy; in blacklist mode, local banking and domestic services that should bypass it.
     */
    fun smartPick(apps: List<AppEntry>, mode: ProxyMode): Set<String> {
        val prefixes = if (mode == ProxyMode.WHITELIST) PROXY_PREFIXES else BYPASS_PREFIXES
        return apps.filter { a -> !a.system || a.packageName in ALWAYS_USER_FACING }
            .filter { a -> prefixes.any { a.packageName.startsWith(it) } }
            .map { it.key }
            .toSet()
    }

    private val ALWAYS_USER_FACING = setOf("com.android.chrome", "com.google.android.youtube", "com.google.android.gm")

    private val PROXY_PREFIXES = listOf(
        "com.android.chrome", "org.mozilla.", "com.brave.", "com.microsoft.emmx", "com.opera.",
        "com.google.android.youtube", "com.google.android.apps.youtube", "com.google.android.gm", "com.google.android.apps.bard",
        "org.telegram.", "org.thunderdog.challegram", "com.whatsapp", "com.instagram.", "com.facebook.", "com.twitter.android",
        "com.discord", "com.reddit.", "com.linkedin.", "com.spotify.", "com.netflix.", "com.openai.", "com.anthropic.",
        "com.github.", "com.medium.", "com.pinterest", "tv.twitch.", "com.snapchat.", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.quora.", "com.notion.", "com.dropbox.", "com.patreon.", "com.soundcloud.", "com.duolingo",
    )

    private val BYPASS_PREFIXES = listOf(
        "ru.sberbankmobile", "ru.sberbank.", "ru.vtb24.", "com.idamob.tinkoff", "ru.tinkoff.", "ru.alfabank.", "ru.raiffeisen",
        "ru.gazprombank", "ru.psbank", "ru.rosbank", "ru.mts.", "ru.megafon.", "ru.beeline.", "ru.tele2.", "ru.rt.",
        "ru.yandex.", "com.yandex.", "ru.mail.", "com.vkontakte.", "com.vk.", "ru.ok.", "ru.ozon.", "com.wildberries.",
        "ru.wildberries.", "ru.avito.", "ru.gosuslugi.", "ru.rostel", "ru.nspk.", "ru.dublgis.", "ru.kinopoisk", "ru.rutube.",
        "com.tencent.", "com.eg.android.AlipayGphone", "com.taobao.", "com.tmall.", "com.jingdong.", "com.sankuai.", "com.xunmeng.",
        "com.sina.", "com.baidu.", "com.netease.", "com.ss.android.article", "com.ss.android.ugc.aweme", "com.xiaomi.", "com.miui.",
        "com.huawei.", "com.bilibili", "tv.danmaku.bili",
    )
}
