#!/system/bin/sh
# Removing the module also removes /data/adb/box, but its settings and configs are kept: they are archived as
# /sdcard/Download/box-backup-<date>.tar.gz (without cores, logs and the module's own backups). Shared storage
# is not there yet this early in boot (and stays locked until the phone is unlocked), so the data is moved
# aside now and archived by a one-off background job once it is.

box_data_dir="/data/adb/box"
keep_dir="/data/adb/box.uninstalled"

busybox="/data/adb/magisk/busybox"
[ -f "/data/adb/ksu/bin/busybox" ] && busybox="/data/adb/ksu/bin/busybox"
[ -f "/data/adb/ap/bin/busybox" ] && busybox="/data/adb/ap/bin/busybox"

rm -f "/data/adb/ksu/service.d/box_service.sh" "/data/adb/service.d/box_service.sh"

[ -d "${box_data_dir}" ] || exit 0

rm -rf "${keep_dir}"
if ! mv "${box_data_dir}" "${keep_dir}"; then
  rm -rf "${box_data_dir}"
  exit 0
fi
# What the archive does not need goes right away.
rm -rf "${keep_dir}/bin" "${keep_dir}/run" "${keep_dir}/backups"

cat > "${keep_dir}.sh" <<EOF
#!/system/bin/sh
# Waits (once, at most 30 min) for the phone to finish booting and the user's storage to be unlocked.
n=0
until [ "\$(getprop sys.boot_completed)" = "1" ] && [ -d /data/media/0/Download ]; do
  [ \$n -ge 360 ] && break
  sleep 5
  n=\$((n + 1))
done
name="box-backup-\$(date +%Y%m%d-%H%M%S).tar.gz"
cd "${keep_dir}" || exit 0
if [ -d /data/media/0/Download ] && ${busybox} tar czf "/data/media/0/Download/\${name}" . 2>/dev/null; then
  chown 1023:1023 "/data/media/0/Download/\${name}" 2>/dev/null
  chmod 0664 "/data/media/0/Download/\${name}"
  am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Download/\${name}" >/dev/null 2>&1
else
  # Storage was not unlocked in time: the archive stays in /data/adb instead.
  ${busybox} tar czf "/data/adb/\${name}" . 2>/dev/null
fi
cd /
rm -rf "${keep_dir}" "${keep_dir}.sh"
EOF
chmod 0700 "${keep_dir}.sh"
${busybox} setsid /system/bin/sh "${keep_dir}.sh" </dev/null >/dev/null 2>&1 &
