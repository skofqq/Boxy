# Box for Root (Boxy edition)

## v1.10.9

- The service tells Boxy right away when it starts, stops or restarts, so the app's notification, tile and
  widgets update at once while the app checks the state much less often (saves battery)

- Сервис сразу сообщает Boxy о запуске, остановке и перезапуске: уведомление, плитка и виджеты приложения
  обновляются мгновенно, а приложение проверяет состояние гораздо реже (экономит батарею)

## v1.10.8

- Fixed: updating a core that is not the active one (for example xray while clash runs) restarted the service
  with that core, which usually failed and left the service stopped. A core update now restarts the service
  only when that core is the one running

- Исправлено: обновление неактивного ядра (например, xray при работающем clash) перезапускало сервис с этим
  ядром, обычно неудачно, и сервис оставался остановлен. Теперь сервис перезапускается, только если обновлено
  именно работающее ядро

## v1.10.7

- `box.tool upkernel` no longer downloads a core again when the installed one already reports the latest
  version (mihomo, sing-box, xray, v2fly, hysteria); `box.tool upkernel force` still reinstalls it

- `box.tool upkernel` больше не скачивает ядро заново, если установленное уже последней версии
  (mihomo, sing-box, xray, v2fly, hysteria); `box.tool upkernel force` по-прежнему переустанавливает

## v1.10.6

- `box.tool updnscrypt` no longer downloads dnscrypt-proxy again when the latest release is already installed
  (`box.tool updnscrypt force` still reinstalls it)

- `box.tool updnscrypt` больше не скачивает dnscrypt-proxy заново, если уже установлена последняя версия
  (`box.tool updnscrypt force` по-прежнему переустанавливает)

## v1.10.5

- Fixed: TUN and Mixed modes with clash did not start when the config listed `exclude-package` / `include-package`
  one package per line ("yaml: did not find expected key"). The list is now rewritten whole, not just its first line
- Fixed: without `tun.device` in the config the module looked for a "meta" interface while mihomo creates "Meta",
  so the forwarding rules for the hotspot were not added in TUN mode

- Исправлено: режимы TUN и Mixed с clash не запускались, если в конфигурации список `exclude-package` / `include-package`
  записан по одному пакету в строке («yaml: did not find expected key»). Теперь список переписывается целиком, а не только первая строка
- Исправлено: без `tun.device` в конфигурации модуль искал интерфейс «meta», а mihomo создаёт «Meta»,
  поэтому в режиме TUN не добавлялись правила пересылки для точки доступа

## v1.10.4

- Fixed: start at boot turned itself back on after a module update. The installer did not carry over the `manual`
  file that keeps the service from starting at boot (Boxy → Settings → start at boot)

- Исправлено: после обновления модуля автозапуск при загрузке снова включался. Установщик не переносил файл `manual`,
  который отключает запуск сервиса при загрузке (Boxy → Настройки → автозапуск)

## v1.10.3

- Updating over an installed module asks nothing: settings.ini, cores, configs, app lists and the GitHub mirror
  are kept as they are (Vol DOWN within 3 s still opens the full setup with all questions).
  Before, an update asked up to 9 questions with 10 s timeouts and downloaded clash when nobody answered
- Fixed: every update moved the previous backup into the new one, so backups nested and grew
  (2 GB after ~10 updates on a test device) and slowed the installation down. Only the latest backup
  is kept now, without the cores (they are moved back, not copied). Update time on the test device: 47 s → 6 s

- Обновление поверх установленного модуля проходит без вопросов: settings.ini, ядра, конфигурации, списки приложений
  и зеркало GitHub остаются как были (Громкость − в течение 3 с открывает полную настройку со всеми вопросами).
  Раньше обновление задавало до 9 вопросов с ожиданием по 10 с и без ответа скачивало clash
- Исправлено: каждое обновление переносило прошлую резервную копию внутрь новой, копии вкладывались друг в друга
  и росли (2 ГБ после ~10 обновлений на тестовом устройстве), замедляя установку. Теперь хранится только последняя копия,
  без ядер (они переносятся обратно, а не копируются). Обновление на тестовом устройстве: 47 с → 6 с

## v1.10.2-ru.9

- Start and stop about 3x faster (Pixel 9 Pro with 75 apps in the list: start 26 s → 7.5 s, stop 6.5 s → 3.6 s):
  - iptables rules are added in one `iptables-restore --noflush` call instead of hundreds of separate commands
    (falls back to one by one if the batch is rejected)
  - cleanup removes the module's rules in one call based on `iptables-save`, and is skipped when there is nothing to remove
  - the app list is resolved to UIDs in one pass; `start` no longer runs a full stop when nothing is running
  - the core is considered up as soon as its proxy port listens (no fixed ~3 s wait), dnscrypt-proxy as soon as its port listens
  - stopping waits for the cores to exit (~0.1 s) instead of a fixed 0.5 s; logging no longer starts a process per line
- Nothing changes while the service runs: no new background work

- Запуск и остановка примерно в 3 раза быстрее (Pixel 9 Pro, 75 приложений в списке: запуск 26 с → 7,5 с, остановка 6,5 с → 3,6 с):
  - правила iptables добавляются одним вызовом `iptables-restore --noflush` вместо сотен отдельных команд
    (если пакет отклонён, правила добавляются по одному, как раньше)
  - очистка убирает правила модуля одним вызовом по `iptables-save` и пропускается, если убирать нечего
  - список приложений переводится в UID за один проход; `start` больше не делает полную остановку, если ничего не запущено
  - ядро считается запущенным, как только слушает порт прокси (без фиксированного ожидания ~3 с), dnscrypt-proxy — как только слушает свой порт
  - остановка ждёт завершения ядер (~0,1 с) вместо фиксированных 0,5 с; журнал больше не запускает процесс на каждую строку
- Во время работы ничего не меняется: новой фоновой работы нет

## v1.10.2-ru.8

- Wi-Fi hotspot no longer switches itself off when it is turned on while the service runs: clash `dns.listen`
  on port 53 is moved to 1053 at start, so Android's tethering DNS (dnsmasq) can bind port 53.
  The DNS hijack follows the port, nothing else changes

- Точка доступа больше не выключается сама, если её включить при работающем сервисе: `dns.listen` clash
  с порта 53 при запуске переносится на 1053, и DNS точки доступа (dnsmasq) может занять порт 53.
  Перехват DNS переключается на новый порт сам, остальное не меняется

## v1.10.2-ru.7

- A failed subscription update (download, core check, unknown format) is reported to Boxy with `am broadcast`,
  so the app can show a notification; nothing happens when Boxy is not installed

- Сбой обновления подписки (скачивание, проверка ядром, неизвестный формат) передаётся в Boxy через `am broadcast`,
  приложение показывает уведомление; если Boxy не установлен, ничего не происходит

## v1.10.2-ru.6

- `quic="disable"` (now a settings.ini key) blocks QUIC only for apps; the core and dnscrypt-proxy keep UDP 443,
  so Hysteria2 / TUIC servers on port 443 and DNSCrypt servers on 443 keep working
- DNSCrypt with sing-box: a `dnscrypt` DNS server is added to the active config and made `dns.final`
  (1.12+ and legacy server formats), removed again when DNSCrypt is off

- `quic="disable"` (теперь ключ settings.ini) блокирует QUIC только приложениям; ядро и dnscrypt-proxy сохраняют UDP 443,
  поэтому серверы Hysteria2 / TUIC и DNSCrypt на порту 443 продолжают работать
- DNSCrypt с sing-box: в активную конфигурацию добавляется DNS-сервер `dnscrypt` и становится `dns.final`
  (форматы 1.12+ и старый), при выключении DNSCrypt он убирается

## v1.10.2-ru.5

- Optional DNSCrypt: `dnscrypt="true"` starts dnscrypt-proxy (bin/dnscrypt-proxy, `dnscrypt/dnscrypt-proxy.toml`) on 127.0.0.1:5354 before the core;
  with clash the active config's `dns.nameserver` is pointed at it and restored when DNSCrypt is off; `box.tool updnscrypt` downloads it
- yq download fixed: the upstream link has no x86 builds (404), x86 devices now get the official static build; short "Not Found" files are rejected

- DNSCrypt по желанию: `dnscrypt="true"` запускает dnscrypt-proxy на 127.0.0.1:5354 перед ядром; для clash `dns.nameserver`
  активной конфигурации переключается на него и возвращается при выключении; `box.tool updnscrypt` скачивает его
- Исправлена загрузка yq: у исходной ссылки нет сборок для x86 (404), теперь берётся официальная статическая сборка

## v1.10.2-ru.4

- `box.tool subs` exits with 0 after a successful update, apps no longer report a failure (upstream #219)
- sing-box: only the active config is formatted, local rule sets in the folder no longer break the start (upstream #203)
- `dns_hijack=false`: port 53 is left to the system or another module such as AdGuard Home (upstream #213)
- `user_agent` for downloads and subscriptions in settings.ini (upstream #138)

- `box.tool subs` завершается с кодом 0 после успешного обновления, приложения больше не пишут об ошибке (#219)
- sing-box: форматируется только активная конфигурация, локальные наборы правил в папке больше не ломают запуск (#203)
- `dns_hijack=false`: порт 53 остаётся системе или другому модулю, например AdGuard Home (#213)
- `user_agent` для загрузок и подписок в settings.ini (#138)

## v1.10.2-ru.3

- Updates come from github.com/skofqq/BFR (updateJson), not from upstream, so the ru changes are not replaced
- Subscription update: the new config is tested by the core first; if it fails, the previous config is kept
- Mobile data rules by SIM operator: `use_sim_matching`, `use_sim_list_mode`, `sim_operators_list` (name or MCC+MNC)
- Installer tests the active config with an installed core and reports errors
- `config_docs.yaml` comments in English

- Обновления модуля идут из github.com/skofqq/BFR, а не из оригинального репозитория, поэтому ru-изменения не затираются
- Обновление подписки: новый конфиг сначала проверяется ядром, при ошибке остаётся прежний
- Правила для мобильной сети по оператору SIM: `use_sim_matching`, `use_sim_list_mode`, `sim_operators_list` (название или MCC+MNC)
- Установщик проверяет активную конфигурацию установленным ядром и сообщает об ошибках
- Комментарии в `config_docs.yaml` на английском

## v1.10.2-ru.2

- Hotspot client MAC filter, Wi-Fi matching by router MAC (BSSID), installer keeps settings.ini values
