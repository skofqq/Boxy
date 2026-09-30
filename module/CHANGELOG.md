# Box for Root (Boxy edition)

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
