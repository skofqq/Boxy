package com.skofqq.boxy.ui.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.skofqq.boxy.R
import com.skofqq.boxy.root.BoxModule
import com.skofqq.boxy.root.RootTask
import com.skofqq.boxy.ui.components.BoxySheet
import com.skofqq.boxy.ui.components.BoxyTextField
import com.skofqq.boxy.ui.components.SheetButtons
import com.skofqq.boxy.ui.components.TaskDialog
import com.skofqq.boxy.ui.components.addTaskLine
import kotlinx.coroutines.launch

/**
 * `xray tls ping`: the TLS handshake with and without SNI, the certificate and TLS 1.3 / X25519, which tells whether
 * a domain fits as an SNI or a REALITY target. Xray's own resolver does not work on Android, so the address is
 * looked up with busybox first; the CA store is Android's.
 */
@Composable
fun TlsPingSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var domain by remember { mutableStateOf("") }
    var title by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf<String>() }
    var done by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf(false) }
    val fits = stringResource(R.string.tls_ping_reality_ok)
    val notFits = stringResource(R.string.tls_ping_reality_no)

    if (title == null) {
        BoxySheet(stringResource(R.string.tls_ping_title), stringResource(R.string.tls_ping_sub), onDismiss) {
            BoxyTextField(domain, { domain = it.trim() }, "www.microsoft.com", Modifier.fillMaxWidth())
            SheetButtons(stringResource(R.string.tls_ping_run), {
                val d = domain.removePrefix("https://").substringBefore('/').trim()
                if (Regex("[A-Za-z0-9.-]+").matches(d)) {
                    title = d
                    lines.clear()
                    done = false
                    scope.launch {
                        ok = RootTask.run(script(d)) { lines.addTaskLine(it) }
                        // TLS 1.3 with SNI is what REALITY needs from its target.
                        val withSni = lines.dropWhile { "with SNI" !in it }
                        val good = withSni.any { "Handshake succeeded" in it } && withSni.any { "TLS 1.3" in it }
                        lines.add(if (good) "✓ $fits" else "✗ $notFits")
                        done = true
                    }
                }
            })
        }
    } else {
        TaskDialog(
            title!!, lines, done, ok,
            com.skofqq.boxy.ui.components.TaskTexts(
                stringResource(R.string.tls_ping_working), stringResource(R.string.tls_ping_ok), stringResource(R.string.tls_ping_failed),
            ),
        ) { if (done) onDismiss() }
    }
}

private fun script(domain: String): String {
    val b = BoxModule.BOX_DIR
    // A subshell: RootTask runs in the shared root shell, where "exit" would close it.
    return "(\n" + """
        BB=; for x in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do [ -x ${'$'}x ] && BB=${'$'}x && break; done
        [ -x $b/bin/xray ] || { echo "xray is not installed"; exit 1; }
        # Looked up as the core's user, which the module's rules let past the proxy and the DNS hijack.
        ug=${'$'}(grep -m1 '^box_user_group=' ${BoxModule.SETTINGS} | cut -d= -f2 | tr -d '"')
        look() { ${'$'}BB timeout 8 "${'$'}@" 2>/dev/null | ${'$'}BB awk '/^Name:/{n=1} n&&/^Address/{for(i=2;i<=NF;i++) if (${'$'}i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+${'$'}/) {print ${'$'}i; exit}}'; }
        ip=${'$'}(look ${'$'}BB setuidgid ${'$'}{ug:-root:net_admin} ${'$'}BB nslookup $domain 1.1.1.1)
        [ -n "${'$'}ip" ] || ip=${'$'}(look ${'$'}BB nslookup $domain)
        [ -n "${'$'}ip" ] || { echo "Cannot resolve $domain"; exit 1; }
        # As the core's user too, so the check itself does not go through the running proxy.
        SSL_CERT_DIR=/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts ${'$'}BB timeout 30 ${'$'}BB setuidgid ${'$'}{ug:-root:net_admin} $b/bin/xray tls ping -ip ${'$'}ip $domain
    """.trimIndent() + "\n)"
}
