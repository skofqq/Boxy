# Boxy

🇬🇧 **English** · 🇷🇺 [Русский](README.ru.md)

Open-source Android app for managing the [Box for Root](https://github.com/taamarin/box_for_magisk) module
(Magisk / KernelSU / APatch). The design follows BFR by boxproxy; the code is written from scratch in Kotlin and Jetpack Compose.

Languages: English, Русский, Українська, Беларуская, Қазақша, Deutsch, Español, Français, Italiano, Türkçe, 中文, 日本語.

## Features

**Home**
- Service status with Start / Stop / Restart, uptime, service details (PID, core version, memory, CPU, affinity) and config reload; DNSCrypt status
- Switch core, network mode and IPv6 while the service is stopped
- Panel and Logs shortcuts, SubStore shortcut when the `sub_store` module is installed
- Latency to three configurable targets
- IP card (LAN or public WAN with country flag), public IPv4/IPv6 details
- Net speed with a live graph (network counters or Clash API, optional chain filter)
- Subscription usage (subscription URLs, proxy-providers or core API)
- System card with CPU and RAM of the core, system environment (kernel, memory, IPSet)
- Home layout editor: hide sections, reorder and hide metric cards

**Apps**
- Blacklist / whitelist proxy mode (`package.list.cfg`)
- All Android users: main, second space, work profile, app clones
- Search, sort, filters (system / user, network permission, users), select all, invert, smart select

**Tools**
- Config: choose the active config of the current core, file manager for `/data/adb/box`
  (search, create, rename, delete, download by URL) and a code editor with search
- Config check: the core tests a file (`mihomo -t`, `sing-box check`, …) before it is selected or saved, and shows the error line
- Subscription import: QR code (camera or picture), pasted link, `clash://install-config` and `sing-box://import-remote-profile` links
- DNS: DNS hijack switch; optional DNSCrypt with dnscrypt-proxy as the core's upstream DNS, download, on / off, server list with filters and latency sorting, config editor and a check of which resolver websites see
- Network control: Wi‑Fi rules, SSID / BSSID lists with nearby Wi‑Fi scan, proxy on/off for hotspot clients, hotspot client MAC filter, SIM operator rules
- Update: cores (Mihomo, Sing-box, Xray, V2Ray, Hysteria), subscription, web UI with live output
- Subscription settings: URLs, provider files, interval, crontab
- Logs: module log files with auto refresh, colour highlighting, search, level filter and sharing
- Traffic statistics: proxy traffic by day and by month (Clash API)
- Automation: start / stop schedule, intents for Tasker, MacroDroid and adb

**Panel**
- Core web UI (Zashboard or MetaCubeXD, switchable), custom panels, SubStore, cache cleaning

**Settings**
- Theme (light, dark, system, Material You), true black, language picker with flags
- Liquid glass navigation bar with backdrop blur and lens, sheet blur, UI scale, system bars
- Navigation: Apps or Logs as a fourth tab
- GitHub mirror for all downloads (module scripts included)
- Notifications: service status with actions and failed subscription updates (each can be turned off), Quick Settings tile, home screen widgets, start at boot switch, open panel on launch
- Backup and restore of module files and app preferences
- App updates (GitHub releases) and module updates (`updateJson`)

## Module

The `module/` folder holds the Box for Root module used with Boxy (upstream v1.10.2 plus fixes, Russian installer,
BSSID matching and hotspot MAC filter). See [module/README.md](module/README.md).

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

## Build

Requirements: Android Studio (JDK 17+), Android SDK 37.

```
./gradlew assembleDebug
./gradlew assembleRelease
```

Release signing reads `keystore.properties` in the project root (not committed):

```
storeFile=keystore/boxy-release.jks
storePassword=...
keyAlias=boxy
keyPassword=...
```

Without it the release build is signed with the debug key.

## Credits

- Design: BFR by boxproxy
- Module: Box for Root by taamarin
- Libraries: Jetpack Compose, [libsu](https://github.com/topjohnwu/libsu), [Sora Editor](https://github.com/Rosemoe/sora-editor)
