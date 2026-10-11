package com.skofqq.boxy.ui.apps

import android.app.Application
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skofqq.boxy.root.BoxModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppTypeFilter { ALL, SYSTEM, USER }
enum class NetworkFilter { ALL, ONLY, EXCLUDE }
enum class UserFilter { MAIN, OTHER, ALL, WORK_CLONE }
enum class AppSort { NAME_ASC, NAME_DESC, INSTALL_ASC, INSTALL_DESC }

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    var loading by mutableStateOf(true)
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var users by mutableStateOf<List<AndroidUser>>(emptyList())
        private set
    var rules by mutableStateOf<AppRules?>(null)
        private set

    var mode by mutableStateOf(ProxyMode.BLACKLIST)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var dirty by mutableStateOf(false)
        private set
    /** True when routing is done by the core itself (tun), so the module ignores app rules. */
    var coreRouting by mutableStateOf(false)
        private set

    var query by mutableStateOf("")
    var typeFilter by mutableStateOf(AppTypeFilter.USER)
    var networkFilter by mutableStateOf(NetworkFilter.ALL)
    var userFilter by mutableStateOf(UserFilter.ALL)
    var sort by mutableStateOf(AppSort.NAME_ASC)

    var message by mutableStateOf<Int?>(null)
    var showRestartTip by mutableStateOf(false)
    var smartSelecting by mutableStateOf(false)
        private set

    private val icons = LruCache<String, ImageBitmap>(300)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            val u = AppsRepository.users()
            users = u
            val r = AppsRepository.loadRules()
            rules = r
            mode = r.mode
            selected = r.keys
            dirty = false
            coreRouting = BoxModule.readSetting("network_mode") == "tun"
            apps = AppsRepository.apps(getApplication(), u)
            loading = false
        }
    }

    val visible: List<AppEntry>
        get() {
            val q = query.trim().lowercase()
            val workIds = users.filter { isWorkOrClone(it) }.map { it.id }.toSet()
            val filtered = apps.filter { a ->
                (q.isEmpty() || a.label.lowercase().contains(q) || a.packageName.lowercase().contains(q)) &&
                    when (typeFilter) {
                        AppTypeFilter.ALL -> true
                        AppTypeFilter.SYSTEM -> a.system
                        AppTypeFilter.USER -> !a.system
                    } &&
                    when (networkFilter) {
                        NetworkFilter.ALL -> true
                        NetworkFilter.ONLY -> a.network
                        NetworkFilter.EXCLUDE -> !a.network
                    } &&
                    when (userFilter) {
                        UserFilter.ALL -> true
                        UserFilter.MAIN -> a.userId == 0
                        UserFilter.OTHER -> a.userId != 0
                        UserFilter.WORK_CLONE -> a.userId in workIds
                    }
            }
            val sorted = when (sort) {
                AppSort.NAME_ASC -> filtered.sortedBy { it.label.lowercase() }
                AppSort.NAME_DESC -> filtered.sortedByDescending { it.label.lowercase() }
                AppSort.INSTALL_ASC -> filtered.sortedBy { it.installTime }
                AppSort.INSTALL_DESC -> filtered.sortedByDescending { it.installTime }
            }
            return sorted
        }

    private fun isWorkOrClone(u: AndroidUser): Boolean =
        u.id == 999 || u.name.contains("work", true) || u.name.contains("clone", true) || u.name.contains("dual", true)

    fun isWork(id: Int): Boolean = users.firstOrNull { it.id == id }?.let { it.name.contains("work", true) } ?: false

    fun userName(id: Int): String = users.firstOrNull { it.id == id }?.name ?: id.toString()

    fun toggle(app: AppEntry) {
        selected = if (app.key in selected) selected - app.key else selected + app.key
        dirty = true
    }

    fun selectAll() {
        selected = selected + visible.map { it.key }
        dirty = true
    }

    fun invert() {
        val keys = visible.map { it.key }.toSet()
        selected = (selected - keys) + (keys - selected)
        dirty = true
    }

    fun smartSelect(replace: Boolean) {
        viewModelScope.launch {
            smartSelecting = true
            val picked = withContext(Dispatchers.Default) { AppsRepository.smartPick(apps, mode) }
            selected = if (replace) picked else selected + picked
            dirty = true
            smartSelecting = false
        }
    }

    fun changeMode(newMode: ProxyMode) {
        if (newMode == mode) return
        viewModelScope.launch {
            if (AppsRepository.saveMode(newMode)) {
                mode = newMode
                rules = rules?.copy(mode = newMode)
                showRestartTip = true
            } else {
                message = com.skofqq.boxy.R.string.apps_mode_failed
            }
        }
    }

    fun save() {
        val r = rules ?: return
        viewModelScope.launch {
            // Keep entries for apps that are not installed any more, so nothing is silently dropped.
            val known = apps.map { it.key }.toSet()
            val keep = r.keys.filter { it !in known }
            val ok = AppsRepository.saveRules(r, mode, (selected + keep).toSet())
            if (ok) {
                rules = AppsRepository.loadRules()
                dirty = false
                showRestartTip = true
            } else {
                message = com.skofqq.boxy.R.string.apps_save_failed
            }
        }
    }

    fun restartService() {
        viewModelScope.launch { BoxModule.restart() }
    }

    fun icon(app: AppEntry): ImageBitmap? {
        icons.get(app.key)?.let { return it }
        val context = getApplication<Application>()
        val pm = context.packageManager
        val drawable = if (app.userId == 0) {
            runCatching { app.info?.loadIcon(pm) }.getOrNull()
        } else {
            val user = android.os.UserHandle.getUserHandleForUid(app.userId * 100000)
            // A work profile's launcher icon, already badged with the briefcase (only for profiles of this user).
            runCatching {
                context.getSystemService(android.content.pm.LauncherApps::class.java)
                    .getActivityList(app.packageName, user).firstOrNull()?.getBadgedIcon(0)
            }.getOrNull()
                ?: runCatching { app.info?.loadIcon(pm)?.let { pm.getUserBadgedIcon(it, user) } }.getOrNull()
        } ?: return null
        return runCatching { drawable.toBitmap(96, 96, Bitmap.Config.ARGB_8888).asImageBitmap() }
            .getOrNull()?.also { icons.put(app.key, it) }
    }
}
