package com.skofqq.boxy.configs

import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.RootTask
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Move to Xray" for V2Ray users: Xray reads V2Ray's (v4) JSON, so the v2fly folder's configs are copied into
 * xray/ (what was there goes to xray/backup-<time>/), the folder is checked with `xray -test`, and only then is
 * bin_name switched. A failed check puts the old xray folder back and leaves V2Ray as it was.
 */
object XrayMigrate {
    private const val BOX = BoxModule.BOX_DIR

    suspend fun run(log: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (!Shell.cmd("[ -x $BOX/bin/xray ]").exec().isSuccess) {
            log("✗ xray is not installed: Tools → Update → Core → Xray")
            return@withContext false
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        val backup = "$BOX/xray/backup-$stamp"
        val prep = Shell.cmd(
            "mkdir -p $backup && for f in $BOX/xray/*.json $BOX/xray/.*.json; do [ -f \"\$f\" ] && mv \"\$f\" $backup/; done; " +
                "cp $BOX/v2fly/*.json $BOX/xray/ && ls $BOX/xray/*.json",
        ).exec()
        if (!prep.isSuccess) {
            log("✗ copy failed")
            restore(backup)
            return@withContext false
        }
        prep.out.forEach { log("→ ${it.substringAfterLast('/')}") }
        val name = BoxModule.unquote(BoxModule.readSetting("name_v2fly_config")) ?: "config.json"
        val r = Shell.cmd("XRAY_LOCATION_ASSET=$BOX/xray timeout 30 $BOX/bin/xray -test -confdir $BOX/xray 2>&1").exec()
        r.out.map { RootTask.stripAnsi(it) }.filter { it.isNotBlank() }.takeLast(6).forEach(log)
        if (!r.isSuccess) {
            log("✗ Xray does not accept the config: V2Ray stays, xray/ is restored")
            restore(backup)
            return@withContext false
        }
        // Geo files the config may use come along when xray has none yet.
        Shell.cmd("for g in geoip.dat geosite.dat; do [ -f $BOX/xray/\$g ] || cp $BOX/v2fly/\$g $BOX/xray/ 2>/dev/null; done").exec()
        BoxModule.writeSetting("name_xray_config", name)
        BoxModule.writeSetting("bin_name", "xray")
        if (BoxModule.state().running) BoxModule.restart()
        log("✓ bin_name = xray (backup: ${backup.substringAfterLast('/')})")
        true
    }

    private fun restore(backup: String) {
        Shell.cmd("rm -f $BOX/xray/*.json; for f in $backup/*.json $backup/.*.json; do [ -f \"\$f\" ] && mv \"\$f\" $BOX/xray/; done; rmdir $backup 2>/dev/null").exec()
    }
}
