#!/system/bin/sh

# Script configuration variables
SKIPUNZIP=1
SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=false
LATESTARTSERVICE=true

# Installer language: Russian when the system language is Russian, English otherwise.
# BOX_LANG=ru|en can force a language.
if [ -z "$BOX_LANG" ]; then
  sys_locale=$(getprop persist.sys.locale)
  [ -z "$sys_locale" ] && sys_locale=$(getprop ro.product.locale)
  [ -z "$sys_locale" ] && sys_locale=$(getprop persist.sys.language)
  case "$sys_locale" in
    ru*) BOX_LANG="ru" ;;
    *) BOX_LANG="en" ;;
  esac
fi

# t "English" "Русский"
t() {
  if [ "$BOX_LANG" = "ru" ]; then
    printf '%s' "$2"
  else
    printf '%s' "$1"
  fi
}

ask_keys() {
  ui_print "— $(t '[ Vol UP(+): Yes ]' '[ Громкость +: Да ]')"
  ui_print "— $(t '[ Vol DOWN(-): No ]' '[ Громкость −: Нет ]')"
}

# Check installation conditions
if [ "$BOOTMODE" != true ]; then
  abort "-----------------------------------------------------------"
  ui_print "! $(t 'Please install in Magisk/KernelSU/APatch Manager' 'Устанавливайте через Magisk/KernelSU/APatch')"
  ui_print "! $(t 'Install from recovery is NOT supported' 'Установка из recovery НЕ поддерживается')"
  abort "-----------------------------------------------------------"
elif [ "$KSU" = true ] && [ "$KSU_VER_CODE" -lt 10670 ]; then
  abort "-----------------------------------------------------------"
  ui_print "! $(t 'Please update your KernelSU and KernelSU Manager' 'Обновите KernelSU и KernelSU Manager')"
  abort "-----------------------------------------------------------"
fi

service_dir="/data/adb/service.d"
if [ "$KSU" = "true" ]; then
  ui_print "— $(t 'KernelSU version' 'Версия KernelSU'): $KSU_VER ($KSU_VER_CODE)"
  [ "$KSU_VER_CODE" -lt 10683 ] && service_dir="/data/adb/ksu/service.d"
elif [ "$APATCH" = "true" ]; then
  APATCH_VER=$(cat "/data/adb/ap/version")
  ui_print "— $(t 'APatch version' 'Версия APatch'): $APATCH_VER"
else
  ui_print "— $(t 'Magisk version' 'Версия Magisk'): $MAGISK_VER ($MAGISK_VER_CODE)"
fi
ui_print "— $(t 'Installer language: English' 'Язык установщика: русский')"

# Set up service directory and clean old installations
mkdir -p "${service_dir}"
if [ -d "/data/adb/modules/box_for_magisk" ]; then
  rm -rf "/data/adb/modules/box_for_magisk"
  ui_print "— $(t 'Old module deleted.' 'Старый модуль удалён.')"
fi

# Extract files and configure directories
ui_print "— $(t 'Installing Box for Magisk/KernelSU/APatch' 'Установка Box для Magisk/KernelSU/APatch')"
unzip -o "$ZIPFILE" -x 'META-INF/*' -x 'webroot/*' -d "$MODPATH" >&2
if [ -d "/data/adb/box" ]; then
  ui_print "— $(t 'Backup existing box data' 'Резервная копия текущих данных box')"
  # Backups live in /data/adb/box/backups/<YYYYMMDD-HHMMSS>/: everything else in /data/adb/box moves into a new
  # one (backups/ itself never does, so they do not nest), and the last 3 are kept.
  bak_root="/data/adb/box/backups"
  mkdir -p "${bak_root}" && chmod 0700 "${bak_root}"
  now_stamp=$(date +%Y%m%d-%H%M%S)
  temp_dir="${bak_root}/${now_stamp}"
  # Backups of older versions (box.XXXXXXXXXX next to the data): moved into backups/ under their date (that of
  # their settings.ini, which the folder's own date does not keep), or dropped when a newer one is already there.
  for old_bak in /data/adb/box/box.??????????; do
    [ -d "${old_bak}" ] || continue
    stamp=$(date -r "${old_bak}/settings.ini" +%Y%m%d-%H%M%S 2>/dev/null || date -r "${old_bak}" +%Y%m%d-%H%M%S 2>/dev/null)
    newest=$(ls -1 "${bak_root}" 2>/dev/null | sort | tail -n 1)
    if [ -n "${stamp}" ] && [ "${stamp}" \< "${now_stamp}" ] && { [ -z "${newest}" ] || [ "${stamp}" \> "${newest}" ]; } \
      && [ ! -e "${bak_root}/${stamp}" ]; then
      mv "${old_bak}" "${bak_root}/${stamp}"
      # Older versions moved the previous backup into the new one: those copies inside are dropped.
      for nested in "${bak_root}/${stamp}"/box.??????????; do
        [ -d "${nested}" ] && rm -rf "${nested}"
      done
    else
      rm -rf "${old_bak}"
    fi
  done
  # Two installs within one second: the second backup gets its own folder.
  [ -e "${temp_dir}" ] && temp_dir="${temp_dir}-$$"
  mkdir -p "${temp_dir}"
  for item in /data/adb/box/* /data/adb/box/.[!.]*; do
    [ -e "${item}" ] || continue
    [ "${item}" = "${bak_root}" ] && continue
    mv "${item}" "${temp_dir}/"
  done
  mv "$MODPATH/box/"* /data/adb/box/
  # The newest 3 stay.
  ls -1 "${bak_root}" | sort -r | tail -n +4 | while read -r old; do
    [ -n "${old}" ] && rm -rf "${bak_root:?}/${old}"
  done
  ui_print "     ↳  backups/${temp_dir##*/}"
  backup_box="true"
else
  mv "$MODPATH/box" /data/adb/
fi

# Directory creation and file extraction
ui_print "— $(t 'Create directories...' 'Создание папок...')"
mkdir -p /data/adb/box/ /data/adb/box/run/ /data/adb/box/bin/xclash/
mkdir -p $MODPATH/system/bin

ui_print "— $(t 'Extracting...' 'Распаковка...')"
ui_print "     ↳  uninstall.sh → $MODPATH"
ui_print "     ↳  box_service.sh → ${service_dir}"
ui_print "     ↳  sbfr → $MODPATH/system/bin"
unzip -j -o "$ZIPFILE" 'uninstall.sh' -d "$MODPATH" >&2
unzip -j -o "$ZIPFILE" 'box_service.sh' -d "${service_dir}" >&2
unzip -j -o "$ZIPFILE" 'sbfr' -d "$MODPATH/system/bin" >&2

# Set permissions
ui_print "— $(t 'Setting permissions...' 'Настройка прав доступа...')"
set_perm_recursive $MODPATH 0 0 0755 0644
# Everything but backups/ (copies of earlier versions, left as they are)
set_perm /data/adb/box 0 3005 0755
for item in /data/adb/box/* /data/adb/box/.[!.]*; do
  [ -e "${item}" ] || continue
  [ "${item}" = "/data/adb/box/backups" ] && continue
  if [ -d "${item}" ]; then set_perm_recursive "${item}" 0 3005 0755 0644; else set_perm "${item}" 0 3005 0644; fi
done
set_perm_recursive /data/adb/box/scripts/ 0 3005 0755 0700
set_perm ${service_dir}/box_service.sh 0 0 0755
set_perm $MODPATH/uninstall.sh 0 0 0755
set_perm $MODPATH/system/bin/sbfr 0 0 0755

chmod ugo+x ${service_dir}/box_service.sh $MODPATH/uninstall.sh /data/adb/box/scripts/*

# Update over an installed module: settings, cores, configs and the mirror are kept and no questions
# are asked. Vol DOWN within 3 s still opens the full setup with all questions.
is_update=false
if [ "${backup_box}" = "true" ] && [ -f "${temp_dir}/settings.ini" ]; then
  is_update=true
  ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  ui_print "— $(t 'Update: settings, cores and configs are kept' 'Обновление: настройки, ядра и конфигурации сохраняются')"
  ui_print "— $(t '[ Vol DOWN(-) within 3 s: full setup with questions ]' '[ Громкость − в течение 3 с: полная настройка с вопросами ]')"
  timeout 1 getevent -cl >/dev/null
  START_TIME=$(date +%s)
  while [ $(( $(date +%s) - START_TIME )) -lt 3 ]; do
    timeout 1 getevent -lc 1 2>&1 | grep KEY_VOLUME > "$TMPDIR/events"
    if grep -q KEY_VOLUMEDOWN "$TMPDIR/events"; then
      is_update=false
      ui_print "— $(t 'Full setup' 'Полная настройка')"
      break
    fi
  done
fi

# The GitHub mirror of the installed module (set by the installer or by Boxy) stays as it was.
keep_mirror() {
  local old="${temp_dir}/scripts/box.tool" new="/data/adb/box/scripts/box.tool" line
  for key in url_ghproxy use_ghproxy; do
    line=$(grep -m1 "^${key}=" "${old}" 2>/dev/null)
    [ -n "${line}" ] && sed -i "s|^${key}=.*|${line}|" "${new}"
  done
  if grep -q '^use_ghproxy="true"' "${new}"; then
    ui_print "— $(t 'GitHub mirror kept' 'Зеркало GitHub сохранено'): $(grep -m1 '^url_ghproxy=' "${new}" | cut -d= -f2 | tr -d '"')"
  else
    ui_print "— $(t 'GitHub mirror: off (kept)' 'Зеркало GitHub: выключено (как было)')"
  fi
}

apply_mirror() {
  ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  ui_print "— $(t "Do you want to use the 'ghfast.top' ?" 'Использовать зеркало «ghfast.top»?')"
  ui_print "     ↳  $(t 'mirror to speed up downloads' 'ускоряет загрузку с GitHub')"
  ask_keys
  START_TIME=$(date +%s)
  while true ; do
    NOW_TIME=$(date +%s)
    timeout 1 getevent -lc 1 2>&1 | grep KEY_VOLUME > "$TMPDIR/events"
    if [ $(( NOW_TIME - START_TIME )) -gt 9 ]; then
      ui_print "— $(t 'No input detected after 10 seconds...' 'Нет ответа за 10 секунд...')"
      ui_print "— $(t 'ghfast acceleration enabled.' 'Ускорение ghfast включено.')"
      sed -i 's/use_ghproxy=.*/use_ghproxy="true"/' /data/adb/box/scripts/box.tool
      break
    elif $(cat $TMPDIR/events | grep -q KEY_VOLUMEUP); then
      ui_print "— $(t 'ghfast acceleration enabled.' 'Ускорение ghfast включено.')"
      sed -i 's/use_ghproxy=.*/use_ghproxy="true"/' /data/adb/box/scripts/box.tool
      break
    elif $(cat $TMPDIR/events | grep -q KEY_VOLUMEDOWN); then
      ui_print "— $(t 'ghfast acceleration disabled.' 'Ускорение ghfast выключено.')"
      sed -i 's/use_ghproxy=.*/use_ghproxy="false"/' /data/adb/box/scripts/box.tool
      break
    fi
  done
}

if [ "${is_update}" = "true" ]; then
  keep_mirror
else
  apply_mirror
  timeout 1 getevent -cl >/dev/null
fi

find_bin() {
  bin_dir="$temp_dir"

  check_bin() {
    local name="$1"
    local path="$bin_dir/bin/$name"
    if [ -e "$path" ]; then
        ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        ui_print "— $name → ⭕ $(t 'FOUND' 'НАЙДЕН')"
    else
        ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        ui_print "— $name → ❌ $(t 'NOT FOUND' 'НЕ НАЙДЕН')"
    fi
  }

  handle_download() {
    local bin="$1"
    local action=""
    case "$bin" in
      yq) action="upyq" ;;
      curl) action="upcurl" ;;
      *) action="all $bin" ;;
    esac

  START_TIME=$(date +%s)
  while true; do
    NOW_TIME=$(date +%s)
    timeout 1 getevent -lc 1 2>&1 | grep KEY_VOLUME > "$TMPDIR/events"

    if [ $(( NOW_TIME - START_TIME )) -gt 9 ]; then
      ui_print "— $(t 'No input detected after 10 seconds...' 'Нет ответа за 10 секунд...')"
      if [ "$bin" = "clash" ]; then
        ui_print "— $(t 'Download enabled for clash.' 'Загрузка clash включена.')"
        /data/adb/box/scripts/box.tool $action
      else
        ui_print "— $(t "Download disabled for $bin." "Загрузка $bin пропущена.")"
      fi
      break
    elif grep -q KEY_VOLUMEUP "$TMPDIR/events"; then
      ui_print "— $(t 'Download enabled.' 'Загрузка включена.')"
      /data/adb/box/scripts/box.tool $action
      break
    elif grep -q KEY_VOLUMEDOWN "$TMPDIR/events"; then
      ui_print "— $(t 'Download disabled.' 'Загрузка пропущена.')"
      break
    fi
    done
  }

  # List of binaries to check
  for bin in yq curl sing-box xray hysteria; do
    timeout 1 getevent -cl >/dev/null

    check_bin "$bin"
    ui_print "— $(t 'Do you want to download or update it?' 'Скачать или обновить?')"
    ask_keys
    handle_download "$bin"
    sleep 1
  done

  # Special case for clash
  if [ -e "$bin_dir/bin/xclash/mihomo" ]; then
      ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
      ui_print "— mihomo → ⭕ $(t 'FOUND' 'НАЙДЕН')"
      ui_print "— $(t 'Do you want to download or update clash?' 'Скачать или обновить clash?')"
      ask_keys
      handle_download "clash"
  else
      ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
      ui_print "— mihomo → ❌ $(t 'NOT FOUND' 'НЕ НАЙДЕН')"
      ui_print "— $(t 'Do you want to download or update mihomo?' 'Скачать или обновить mihomo?')"
      ask_keys
      handle_download "clash"
  fi
}

if [ "${is_update}" = "true" ]; then
  ui_print "— $(t 'Cores and tools: kept, update them from Boxy or box.tool when needed' 'Ядра и утилиты сохранены, обновить их можно из Boxy или box.tool')"
else
  find_bin
  timeout 1 getevent -cl >/dev/null
fi

restore_ini() {
  backup_ini="$temp_dir/settings.ini"
  target_ini="/data/adb/box/settings.ini"
  [ -f "${backup_ini}" ] || return 0
  # Every setting of the old file is carried over, not a fixed list: a key=value line (a multi-line
  # array "key=(" up to its ")" too) whose old value is literal replaces the new file's line, as long as the
  # new file's value is literal as well (lines built from other values, like xray_config="${box_dir}/…" or
  # proxy_mode=$(…), stay the new version's). Settings the new file no longer has are appended at the end.
  awk '
    function start(line) { return match(line, /^[A-Za-z_][A-Za-z0-9_]*=/) }
    function opens(line,  v) { v = substr(line, index(line, "=") + 1); return (v ~ /^\(/ && v !~ /\)[[:space:]]*(#.*)?$/) }
    FNR == 1 { file++ }
    file == 1 {
      if (inblk) { blk = blk "\n" $0; if ($0 ~ /\)[[:space:]]*(#.*)?$/) { inblk = 0; old[key] = blk } ; next }
      if (start($0)) {
        key = substr($0, 1, index($0, "=") - 1); order[++n] = key
        if (opens($0)) { inblk = 1; blk = $0 } else old[key] = $0
      }
      next
    }
    {
      if (skip) { if (!keepnew) { if ($0 ~ /\)[[:space:]]*(#.*)?$/) skip = 0; next } ; print; if ($0 ~ /\)[[:space:]]*(#.*)?$/) skip = 0; next }
      if (start($0)) {
        k = substr($0, 1, index($0, "=") - 1); seen[k] = 1
        multi = opens($0)
        keepnew = !((k in old) && old[k] !~ /\$/ && $0 !~ /\$/)
        if (!keepnew) { print old[k]; carried++ } else print
        if (multi) { skip = 1; if (!keepnew) next } ; next
      }
      print
    }
    END {
      for (i = 1; i <= n; i++) { k = order[i]; if (!(k in seen) && (k in old) && old[k] !~ /\$/) { if (!hdr++) print "\n# Kept from the previous version"; print old[k]; seen[k] = 1; added++ } }
      printf "%d %d\n", carried, added > "/dev/stderr"
    }
  ' "${backup_ini}" "${target_ini}" > "${target_ini}.new" 2> "${TMPDIR:-/data/local/tmp}/ini_count"
  if [ -s "${target_ini}.new" ] && /system/bin/sh -n "${target_ini}.new" 2>/dev/null; then
    mv -f "${target_ini}.new" "${target_ini}"
    read -r carried added < "${TMPDIR:-/data/local/tmp}/ini_count"
    ui_print "— $(t "Settings kept: ${carried:-0}, carried from the old version: ${added:-0}" "Сохранено настроек: ${carried:-0}, перенесено из старой версии: ${added:-0}")"
  else
    rm -f "${target_ini}.new"
    ui_print "— $(t 'settings.ini could not be merged, the new one is used: check your settings' 'settings.ini не удалось объединить, используется новый: проверьте настройки')"
  fi
  rm -f "${TMPDIR:-/data/local/tmp}/ini_count"
}

apply_ini() {
  ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  ui_print "— $(t 'Would you like to restore settings.ini?' 'Восстановить settings.ini?')"
  ask_keys
  START_TIME=$(date +%s)
  while true ; do
    NOW_TIME=$(date +%s)
    timeout 1 getevent -lc 1 2>&1 | grep KEY_VOLUME > "$TMPDIR/events"
    if [ $(( NOW_TIME - START_TIME )) -gt 9 ]; then
      ui_print "— $(t 'No input detected, keeping your settings.ini values' 'Нет ответа, ваши значения settings.ini сохраняются')"
      restore_ini
      break
    elif $(cat $TMPDIR/events | grep -q KEY_VOLUMEUP); then
      restore_ini
      break
    elif $(cat $TMPDIR/events | grep -q KEY_VOLUMEDOWN); then
      ui_print "— $(t 'Skipped restoring settings.ini' 'Восстановление settings.ini пропущено')"
      break
    fi
  done
}

if [ "${is_update}" = "true" ]; then
  ui_print "— $(t 'Restoring settings.ini...' 'Восстановление settings.ini...')"
  restore_ini
else
  [ "${backup_box}" = "true" ] && [ -f "$temp_dir/settings.ini" ] && apply_ini
  timeout 1 getevent -cl >/dev/null
fi

# Restore backup configurations if present
if [ "${backup_box}" = "true" ]; then
  ui_print "— $(t 'Restoring configurations...' 'Восстановление конфигураций...')"
  ui_print "     ↳  xray"
  ui_print "     ↳  hysteria"
  ui_print "     ↳  clash"
  ui_print "     ↳  sing-box"
  ui_print "     ↳  v2fly"
  restore_config() {
    config_dir="$1"
    [ -d "${temp_dir}/${config_dir}" ] && cp -rf "${temp_dir}/${config_dir}/"* "/data/adb/box/${config_dir}/"
  }
  for dir in clash xray v2fly sing-box hysteria dnscrypt; do
    restore_config "$dir"
  done

  restore_kernel() {
    kernel_name="$1"
    if [ ! -f "/data/adb/box/bin/$kernel_name" ] && [ -f "${temp_dir}/bin/${kernel_name}" ]; then
      ui_print "— $(t "Restoring kernel ${kernel_name}..." "Восстановление ядра ${kernel_name}...")"
      mv -f "${temp_dir}/bin/${kernel_name}" "/data/adb/box/bin/${kernel_name}"
    fi
  }

  for kernel in curl yq xray sing-box v2fly hysteria xclash/mihomo xclash/premium dnscrypt-proxy; do
    restore_kernel "$kernel"
  done

  ui_print "— $(t 'Restoring...' 'Восстановление...')"
  ui_print "     ↳  *.logs"
  ui_print "     ↳  box.pid"
  ui_print "     ↳  uid.list"
  cp -rf "${temp_dir}/run/"* "/data/adb/box/run/"

  # The backup keeps configs and lists; cores were moved back, the rest of its bin/ is not needed
  rm -rf "${temp_dir}/bin"

  # Restored cores and tools stay executable (set_perm above made bin/ 0644)
  chmod 0755 /data/adb/box/bin/* /data/adb/box/bin/xclash/* 2>/dev/null

  # bin/clash is a symlink to the chosen clash build, not restored with the cores above: without it box.tool
  # (check, reload, upkernel) found no clash core until the next service start recreated it.
  opt=$(grep -m1 '^xclash_option=' /data/adb/box/settings.ini 2>/dev/null | cut -d= -f2 | tr -d '"')
  [ -f "/data/adb/box/bin/xclash/${opt:-mihomo}" ] || opt=mihomo
  [ -f "/data/adb/box/bin/xclash/${opt}" ] && ln -sf "/data/adb/box/bin/xclash/${opt}" /data/adb/box/bin/clash

  ui_print "— $(t 'Restoring...' 'Восстановление...')"
  ui_print "     ↳  ap.list.cfg"
  ui_print "     ↳  crontab.cfg"
  ui_print "     ↳  package.list.cfg"
  ui_print "     ↳  gid.list.cfg"
  cp -rf "${temp_dir}/gid.list.cfg" "/data/adb/box/gid.list.cfg"
  cp -rf "${temp_dir}/ap.list.cfg" "/data/adb/box/ap.list.cfg"
  cp -rf "${temp_dir}/crontab.cfg" "/data/adb/box/crontab.cfg"
  cp -rf "${temp_dir}/package.list.cfg" "/data/adb/box/package.list.cfg"

  # Start at boot off (Boxy → Settings → start at boot, or the "manual" file) stays off after an update
  if [ -f "${temp_dir}/manual" ]; then
    cp -f "${temp_dir}/manual" "/data/adb/box/manual"
    ui_print "     ↳  manual ($(t 'start at boot stays off' 'автозапуск остаётся выключенным'))"
  fi
fi

# Test the active config with the core that is already installed, so a broken file is reported now
check_active_config() {
  local ini="/data/adb/box/settings.ini" core name dir bin
  core=$(grep -m1 '^bin_name=' "$ini" | cut -d= -f2 | tr -d '"')
  case "$core" in
    clash) name=$(grep -m1 '^name_clash_config=' "$ini" | cut -d= -f2 | tr -d '"'); bin="/data/adb/box/bin/xclash/$(grep -m1 '^xclash_option=' "$ini" | cut -d= -f2 | tr -d '"')" ;;
    sing-box) name=$(grep -m1 '^name_sing_config=' "$ini" | cut -d= -f2 | tr -d '"'); bin="/data/adb/box/bin/sing-box" ;;
    *) return 0 ;;
  esac
  [ "$core" = "clash" ] && [ ! -x "$bin" ] && bin="/data/adb/box/bin/xclash/mihomo"
  dir="/data/adb/box/$core"
  [ -x "$bin" ] && [ -f "$dir/$name" ] || return 0
  ui_print "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  ui_print "— $(t 'Checking the active config' 'Проверка активной конфигурации'): $core → $name"
  if [ "$core" = "clash" ]; then
    out=$(timeout 60 "$bin" -t -d "$dir" -f "$dir/$name" 2>&1)
  else
    out=$(timeout 60 "$bin" check -D "$dir" -c "$dir/$name" 2>&1)
  fi
  if [ $? -eq 0 ]; then
    ui_print "— ✅ $(t 'Config is valid' 'Конфигурация в порядке')"
  else
    ui_print "! ❌ $(t 'The core rejected the config, the service will not start with it:' 'Ядро не приняло конфигурацию, с ней сервис не запустится:')"
    echo "$out" | grep -iE 'error|fatal|failed|yaml:' | tail -n 3 | while read -r line; do ui_print "     ${line}"; done
  fi
}
check_active_config

# Module description in the manager's module list
if [ "$BOX_LANG" = "ru" ]; then
  sed -i "s/^description=.*/description=Прокси-туннель на Android через mihomo (clash), sing-box, xray и hysteria/" $MODPATH/module.prop
fi

# Update module description if no kernel binaries are found
[ -z "$(find /data/adb/box/bin -type f)" ] && sed -Ei "s/^description=(\[.*][[:space:]]*)?/description=[ 😱 $(t 'Module installed but manual Kernel download required' 'Модуль установлен, но ядро нужно скачать вручную') ] /g" $MODPATH/module.prop

# Customize module name based on environment
if [ "$KSU" = "true" ]; then
  sed -i "s/name=.*/name=Box for KernelSU/g" $MODPATH/module.prop
elif [ "$APATCH" = "true" ]; then
  sed -i "s/name=.*/name=Box for APatch/g" $MODPATH/module.prop
else
  sed -i "s/name=.*/name=Box for Magisk/g" $MODPATH/module.prop
fi
unzip -o "$ZIPFILE" 'webroot/*' -d "$MODPATH" >&2

# Clean up temporary files
ui_print "— $(t 'Cleaning up leftover files' 'Удаление временных файлов')"
rm -rf /data/adb/box/bin/.bin $MODPATH/box $MODPATH/sbfr $MODPATH/box_service.sh

ui_print ""
# Create a symbolic link to run /dev/sbfr as a shortcut to sbfr
ln -sf "$MODPATH/system/bin/sbfr" /dev/sbfr
ui_print "— $(t "Shortcut '/dev/sbfr' created." 'Создан ярлык «/dev/sbfr».')"
ui_print "     ↳  $(t 'You can now run: su -c /dev/sbfr' 'Теперь можно запускать: su -c /dev/sbfr')"
ui_print ""
# Complete installation
ui_print "— $(t 'Installation complete. Please reboot your device.' 'Установка завершена. Перезагрузите устройство.')"
ui_print "— $(t 'Report issues: github.com/skofqq/Boxy/issues' 'О проблемах сообщайте: github.com/skofqq/Boxy/issues')"
