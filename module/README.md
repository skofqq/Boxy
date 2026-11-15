# Box for Root module (Boxy edition)

Based on [taamarin/box_for_magisk](https://github.com/taamarin/box_for_magisk) v1.10.2 (GPL-3.0).

Changes on top of v1.10.2:

- Unreleased upstream fixes up to `a872449`: masked subscription tokens in logs, simpler `enhanced-mode` rule,
  safer network-control state checks, simplified `net.inotify`
- Installer and action messages in English and Russian (system language, or `BOX_LANG=ru|en`)
- Installer keeps your `settings.ini` values (and `gid.list.cfg`) when there is no key input
- Wi‑Fi matching also by router MAC: `wifi_bssids_list`
- Hotspot client MAC filter: `mac_filter`, `mac_mode` (whitelist / blacklist), `macs_list`
- Boot start also waits for `sys.boot_completed` (devices without a boot animation)
- Updates from this repository (`updateJson` → `module/update.json`), so upstream releases do not replace these changes
- Subscription update keeps the previous config when the core rejects the new one
- Mobile data rules by SIM operator: `use_sim_matching`, `use_sim_list_mode`, `sim_operators_list`
- Installer tests the active config with an installed core
- `config_docs.yaml` comments in English
- Optional DNSCrypt: `dnscrypt=true`, `box.tool updnscrypt`, config in `dnscrypt/dnscrypt-proxy.toml`;
  with clash (`dns.nameserver`), sing-box (`dns.final`) and xray (`xray/zz-boxy-dnscrypt.json`: the config's `dns`
  with DNSCrypt as its only server, `tcp+local`, past the routing) the active config is pointed at it and restored when it is off
- `quic="disable"` in settings.ini blocks QUIC for apps only; the core and dnscrypt-proxy keep UDP 443
- Fixes for upstream issues: `box.tool subs` exits with 0 on success (#219), sing-box formats only the active config (#203),
  `dns_hijack=false` leaves DNS to AdGuard Home and similar (#213), `user_agent` in settings.ini (#138)

Build: `python module/build.py` → `module/box_for_root-<version>.zip`.

## Folder structure

Module working directory: `/data/adb/box/`

```
/data/adb/box/
├── bin/                  # cores and tools: xclash/ (mihomo), sing-box, xray, v2fly, hysteria, yq, curl, dnscrypt-proxy
├── clash/                # mihomo (clash) configs, dashboard/, proxy providers, rule sets
├── sing-box/             # sing-box configs
├── xray/                 # xray configs
├── v2fly/                # v2fly configs
├── hysteria/             # hysteria configs
├── dnscrypt/             # dnscrypt-proxy config (optional DNSCrypt)
├── scripts/              # module scripts
│   ├── box.service       # start / stop / restart of the core
│   ├── box.iptables      # transparent proxy rules (tproxy, redirect, tun)
│   ├── box.tool          # updates: cores, subscriptions, geo databases, web UI
│   ├── box.inotify       # reacts to turning the module on / off in the manager
│   ├── ctr.inotify       # network control: Wi-Fi and SIM rules
│   ├── ctr.utils         # helpers for network control (SSID, BSSID, SIM operator)
│   ├── net.inotify       # keeps local address rules current when the network changes
│   └── start.sh          # start at boot
├── run/                  # runtime state and logs: box.pid, runs.log, <core>.log
├── settings.ini          # main settings
├── package.list.cfg      # apps for the blacklist / whitelist mode
├── ap.list.cfg           # hotspot and tethering interfaces to proxy or ignore
├── gid.list.cfg          # group IDs for the blacklist / whitelist
├── crontab.cfg           # scheduled tasks (subscription and geo updates)
└── manual                # when present, the service does not start at boot
```

Module files (scripts installed by the manager) are in `/data/adb/modules/box_for_root/`.

