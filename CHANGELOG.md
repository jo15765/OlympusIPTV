# Revision History & Changelog

All notable changes across **ClearCast Tuner (Olympus-IPTV)** and its optional **Docker Cloud API (`cloud-api/`)** are documented here.

---

## Unreleased (Post-0.2.142 — 3-Tier Cloud API, Event-Window Hardening & LAN Dashboard Polish)

- **Monorepo Docker Backend (`cloud-api/`)**:
  - Consolidated the Node.js 22 API (`server.js`) and background Xtream ingestion worker (`worker.js`) into `cloud-api/` with full PostgreSQL schema (`schema.sql`) and an optional `--profile local-db` PostgreSQL 16 Docker service for one-command self-hosting.
  - Added **Brotli (q4) + Gzip** pre-compressed `LRUCache` with `ETag` / `304 Not Modified`, singleflight request coalescing, `?since=<epochMs>` delta sync, and real-time `LISTEN olympus_sync` cache invalidation.
  - Added **PostgreSQL GIN full-text search** (`idx_cloud_channels_name_fts`) and partial live-event index (`idx_cloud_channels_live_active`), plus **PostgreSQL session advisory locks** (`pg_try_advisory_lock`) in `worker.js` to prevent duplicate container sync contention.
  - Hardened security: **AES-256-GCM** encryption at rest for provider credentials (`CREDENTIALS_KEY`) with automatic plaintext migration on startup, **SSRF URL guard**, **Zod** input validation, **Pino** log redaction, localhost-only port binding (`127.0.0.1:8443:3000`), non-root container (`USER node`), `cap_drop: [ALL]`, and WAN static dashboard disabled by default (`ENABLE_WEB_DASHBOARD=false`).
- **Strict Live Event-Window & Dummy EPG Parity**:
  - Enforced unified **15-minute pre-event** and **30-minute post-event hard cutoff** across `NameStrip.kt`, `DummyEpg.kt`, `CatalogDb.kt`, and `cloud-api/server.js`.
  - Guaranteed **NOT LIVE / placeholder slots** (`NO EVENT`, `Off Air`, `No Broadcasting`, `Sendepause`, etc.) never appear in the guide, category counts, search, or mini-guide.
  - Added international city timezone & ISO/ordinal date parsing and sport-specific event durations.
- **Android TV / Mobile D-Pad & UI Fixes**:
  - Fixed D-Pad focus blackholes in Player OSD (`btnAudio` → `btnMenuBack`, `btnQuickPro` focus tracking), Parental Controls (`btnSetPin`/`btnClearPin` → `btnBack`), Browse nav rail → open group / Admin Tools, and uneven bottom rows in the category grid.
  - Wired the `MediaDetailActivity` profile button (`btnDetailUser`), fixed `24/7 Channels` UI hangs via indexed keyset pagination (`CatalogDb` v18), and fixed Search soft-keyboard invocation + cross-catalog Movies/TV Shows results.
  - Auto-registered TV logins into Cloud PostgreSQL (`POST /api/setup`) on initial cloud pull.
- **Embedded LAN Dashboard Refresh**:
  - Upgraded the in-app LAN dashboard (`app/src/main/assets/dashboard/index.html`) with the **Cinema Obsidian & Molten Gold** design system (atmospheric backdrop, frosted glass panels, glowing active nav rail, animated KPI cards with tabular numerals, and pulsing live connection indicator).

---

## What's in 0.2.142

- **Unified `24/7 Channels` Group**: Consolidated all live 24/7 channels into a single **24/7 Channels** group (`__247__`) across the TV/mobile guide and Web-UI, alphabetized by the actual channel/show name (ignoring `24/7`, `24-7`, `24x7`, and leading country/quality prefixes).
- **Accurate Live Event & Guide Sync (ESPN+ / Disney+ Events / PPV)**:
  - Fixed event-slot detection for sanitized (`16: ...`), country-prefixed (`USA | ESPN+ 37: ...`), and bare matchup names, plus `@ Month Day Time` (`@ Nov 25 7:00PM ET`) and `@ MM/DD Time` formats.
  - Updated `CatalogDb.insertMissing()` so refreshes overwrite stale channel names, event windows, and clear stale `epg_cache` entries instead of keeping old matchups frozen, and added `pruneMissingLiveChannels()` to remove ended/pruned event slots on cloud sync.
  - Moved automatic event date/time parsing, `cloud_epg` population, stale live-channel cleanup, and live-window filtering (`eventParser.js`) into `nodeapi` on a 15-minute worker cycle.
- **Split Channel & Group Name Sanitization**: Split Web-UI sanitization into two dedicated endpoints (`POST /api/sanitize/channels` and `POST /api/sanitize/groups`) using single-pass case-insensitive regex updates in PostgreSQL (`db.js`) plus immediate local SQLite group name updates on the TV (`CatalogDb.sanitizeGroupNames`), and removed horizontal scrollbars on the Sanitize panel.
- **TV Remote & Set-Top Box Layout Fixes**:
  - Fixed D-pad left/right focus navigation across all 4 tabs and right-side cards in **EPG Edits** (`activity_epg_edits.xml` & `EpgEditsFragment.kt`).
  - Redesigned the set-top box **Web-UI Access** screen (`browse_pane_device.xml`) into a compact 2-column TV layout so Email, Device ID, URL, setup steps, Auto-resume, and Remove Playlist all fit without vertical scrolling.
- **Build & Lint Cleanup**: Moved `kotlin { compilerOptions }` out of `android {}` in `app/build.gradle.kts`, migrated Android test dependencies to `libs.versions.toml`, guarded `JobInfo.Builder.setRequiresBatteryNotLow` for API 26+, and flattened redundant layout wrappers.

## What's in 0.2.137

- **Local Major Networks (`FOX`, `ABC`, `CBS`, `NBC`, `PBS`)**: Expanded SQLite local candidate matching across all IPTV naming formats (`ABC7`, `CBS2`, `NBC4`, `FOX5`, non-pipe `US Locals` groups), added offline 3-digit US ZIP fallback (`ZipLocator`), and updated smaller-city ZIPs to automatically resolve to the closest major city carrying all 5 major networks.
- **Cleaner First-Time Setup & Default Visibility**: Removed the redundant top starter-pack buttons during initial channel scanning. When `Include International` is set to No, only the ZIP code's home country (plus un-prefixed provider folders) stays active while foreign countries are hidden, and all groups for visible countries start active.
- **Self-Healing Group Names & Categories**: Fixed missing/numeric category IDs (`112`, `151`, `152`…) by deriving category rows from channel metadata during ingest, self-healing `readCategories()` and `dashboardGroups()`, and invalidating stale disk dashboard caches (`countryBasis = "sql3"`).
- **Revamped Web-UI Dashboard**:
  - Added a unified Settings sub-navigation bar and card-based drill-down layouts for Refresh & Maintenance, Web-UI Access, VPN Tunnel, Active Streams, and Error Log.
  - Cleaned up EPG & Cleanup drill-down views with a 1-click tool switcher pill bar so inactive cards stay hidden.
  - Redesigned **Sanitize Group & Channel Names** with interactive 1-click tag chips, custom tag input, and an explicit **Sanitize Now** button (no auto-run).
  - Added **Parse Channel Names into Guide** action button (`POST /api/epg/parse`) and **Remove Playlist** option (`POST /api/playlist/remove`) in Web-UI Settings.
- **Cloud API Security & Performance (`nodeapi`)**: Moved bulk sanitize, batch group rename, EPG parse, and per-device playlist removal to parameterized PostgreSQL endpoints on `nodeapi` with real-time `pg_notify` TV refresh, and added constant-time `X-Olympus-Api-Key` header validation (`BuildConfig.PORTAL_API_KEY` / `API_SECRET_KEY`).
- **Web-UI Startup & Code Cleanup**: Fixed a null-reference JS error on legacy EPG listeners that prevented the initial setup screen from rendering in the Web UI, and stripped inline code comments across the repository.

## What's in 0.2.132

- **Mobile Live TV Guide Alignment & Player Gestures**:
  - Locked the mobile Live TV channel column and side-by-side program timeline to identical row heights (`56dp` / `64dp` on touch), removed duplicate inline EPG lines from the left channel cell when the timeline is visible, fixed synchronous scroll locking (`paddingTop` offset + stopping competing flings), and added subtle alternating row bands so every channel aligns cleanly with its airing show.
  - Added mobile player **pinch-to-zoom** (`Zoom` / `Fit`), **long-press 2× speed boost** on VOD/Series, jump-free initial brightness swipes (reading system brightness), and Live TV channel zap HUD indicators (`CH ▲` / `CH ▼`).
  - Cached `DeviceForm.windowWidthDp()` and `MeteredNetwork.limited()` to eliminate repeated system IPC during scrolling and playback.
- **Set-Top TV UI Polish & Smooth Focus Flow**:
  - Replaced per-focus interpolator and `GradientDrawable` allocations in `FocusMotion` with a single smooth cubic ease-out (`PathInterpolator`) and cached focus ring, removing double-border artifacts on selector-backed cards.
  - Unwrapped nested `TransitionDrawable` layers in `BackdropGlow`, removed multi-pass `autoSizeTextType` from category cards, widened 10-foot nav rail items/chips, and unified leftover cold-blue player/poster overlays to warm obsidian (`#141210` / `#11100E`).
- **Google TV Streamer (4K) & Android TV 14 Optimizations**:
  - Added dedicated Google TV Streamer / high-RAM Android TV box detection (`DeviceForm.isGoogleTvStreamer` / `isHighEndTv`), Android 13/14+ `POST_NOTIFICATIONS` permission, and native `arm64-v8a` support.
  - Upgraded `matchFrameRate()` on API 31+ to use **HDMI 2.1a QMS seamless refresh-rate switching** (`CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`) for zero black-screen flicker on 23.976/50/59.94 Hz content.
  - Tuned high-end TV buffers (`1.5s` fast-start zap, `15s–45s` cushion, `10s` back-buffer), expanded Coil caches (`80 MB` RAM / `64 MB` disk) for 4 GB / 32 GB boxes, enabled Android TV 13/14+ Picture-in-Picture (`canEnterPip`), and mapped `KEYCODE_GUIDE` / `KEYCODE_TV_CONTENTS_MENU` to toggle the live Mini-Guide.
- **Fire TV Stick, Cloud Sync & System-Level Speed Optimizations**:
  - Switched `LineupClient.fetchCloudCatalog()` to Gson streaming `JsonReader` (no giant JSON string or DOM tree), shared `HttpClients.shared` + `Gson` across all cloud clients, and short-circuited unchanged cloud catalog syncs via persisted `cloud.stamp`.
  - Added a 60-second **IPv4-first DNS cache** (`fastDns`) in `HttpClients`, RAM-aware SQLite `cache_size` / `mmap_size` + `PRAGMA shrink_memory` in `CatalogDb`, and pre-playback cache trimming (`MemoryTrim.trimForPlayback`) on low-RAM sticks.
  - Disabled `BackdropGlow` motion, capped Coil/HTTP disk caches (`20 MB`), lowered Web-UI logo worker priority/count (`2` threads), trimmed EPG block cache (`36` entries), and tuned live start/back buffers (`1.5s` start, `4s` back-buffer) on low-RAM sticks.
  - Guarded `NotificationChannel` creation behind API 26+ (cached once per service) in `RecordingService` and `TunnelService` so Fire OS 6 (Android 7.1 / API 25) rooted Fire Sticks and older Android 7 TV boxes never crash when starting DVR or VPN, and expanded/cached `DeviceForm.isTelevision()` & `isGoogleTvStreamer()` to detect legacy/AOSP/non-Leanback set-top boxes (`FEATURE_TELEVISION` or no touchscreen) while avoiding redundant system IPC.
- **Web Dashboard, DVR & Reminders Overhaul**:
  - Redesigned the Web-UI with warm obsidian + amber styling, `DocumentFragment` batch rendering, shimmer skeletons, color-coded toasts, context-aware sticky Save & Sync, group prefetching, redesigned Web-UI & Active Streams (with cached Public WAN IP + VPN status), and fixed local TV Wi-Fi connection status.
  - Added **Google Drive & OneDrive DVR Cloud Upload** (`CloudDriveUploader` pure-OkHttp resumable chunked uploads + OAuth Device-Code sign-in from the Web-UI **Storage & Cloud** tab, 3-option storage overview cards, dynamic destination picker with live free-space/cloud/SMB labels in both Web-UI and TV/mobile `RecordPrompt`, optional BYO or `BuildConfig` Client ID, per-recording **Upload to Cloud** / cloud links, and an **"Auto-delete local copy after upload"** toggle for Fire TV Sticks).
  - Added **Pro Features & Profiles** (excluding auto-launch on boot): multi-user **Household Profiles** (with Kids Mode), **Preferred Audio & Subtitle Languages**, customizable **Subtitle Size & Background Opacity** with live preview, **Default VOD Playback Speed**, **Live TV Pause / Timeshift** RAM back-buffer (auto-clamped on low-RAM Fire TV Sticks), **Stats for Nerds** live stream HUD (codec, resolution, FPS, bitrate, buffer — zero overhead when off), **4-Way Quad Multi-View** capability flag (auto-capped to 2-way on low-RAM sticks), **External Player Handoff** (VLC, MX Player, Nova, Android chooser), **EPG Time-Shift Offset & Custom XMLTV URL**, **Backup Stream Auto-Failover** & **Spoiler-Free Sports Mode**, **Offline VOD/Episode Downloads** (256 KB buffered single worker with 250 MB / 100 MB storage guards), and **1-Click Full Backup & Restore** — all organized in a clean, tabbed **Pro & Profiles** Web-UI section.
  - Added **Universal Android Device Compatibility & Hardware Codec Health**: removed forced landscape orientation across 13 secondary activities for portrait phones, foldables, split-screen tablets, and Android 16+ large screens; added `x86_64` ABI + Android 15 **16 KB page-size** `jniLibs` packaging; added `DeviceCompat` Android 15 edge-to-edge cutout insets, Android 13+ `POST_NOTIFICATIONS` prompting, GMS-free Cast fallback, R8 `ShowReminder` keep rule, and a cached **Device & Hardware Codec Health** panel (`GET /api/diagnostics` + Web-UI badges for H.264, HEVC, VP9, AV1, Dolby AC-3/E-AC-3, RAM, storage, and ABI).
  - Upgraded the **Web-UI & App-Wide Performance**: added a **Home Command Center** landing tab (live status pills, 4 quick-action tiles, interactive setup checklist), **Global Quick-Jump Command Palette** (`Ctrl+K` / `/` with live channel search & 1-click play), **Floating Mini TV-Remote Drawer** (`R`), **Sticky Mobile Bottom Navigation Bar**, live sidebar badges + pulsing DVR dot, amber pill toggle switches, unsaved-changes ring, **Dark / Daylight Theme Toggle** (`T`), and `?` **Keyboard Shortcuts** modal; consolidated 10 `Gson` instances into a single `AppJson.gson` singleton, added `OlympusIptvApp.onTrimMemory` cache trimming, `ETag`/`304 Not Modified` instant Web-UI reloads, 5s diagnostics caching, and CSS `content-visibility: auto` for smooth 60–120 FPS long-list scrolling.
  - Added **Mobile Back-to-Guide & PiP Close (`✕`), Sub-2-Second Live Sync, and Guaranteed Local Channel Discovery**:
    - Added a dedicated top-left `← Guide` pill button and zero-delay `exitToGuide()` on mobile player (fixing the trap where tapping Back only hid the dock), plus native **PiP `✕` Close** and **Play/Pause** `RemoteAction` controls (`PictureInPictureParams` primed on API 31+ `onResume()` with a PiP-scoped `BroadcastReceiver`).
    - Tightened cloud sync (`2.5s` pre-login, `4s` offline, `15s` SSE, `60s` background) with foreground `nudgeCloudSyncNow()`, added a reactive `ProFeatureStore.watch()` bus for <50 ms live subtitle/HUD/guide updates, added `GET /api/live-status`, `POST /api/sync/now`, auto-saving Pro toggles, and a live **Now Playing on Device** strip in the Web-UI.
    - Created offline-capable **`ZipLocator`** (2.5s API + built-in US 3-digit ZIP prefix table `005–999`) wired into TV, mobile, cloud login, and Web-UI; upgraded **`AutoFavorites`** with precompiled regexes, single-pass network pre-indexing, all IPTV local channel naming formats, city aliases, and 3-tier fallback (closest DMA → same-state affiliates → US East/West feeds), plus a **📍 Home ZIP** bar in **Favorites → Auto Build**.
    - Hardened `RecordingFiles` and `OfflineDownloadManager` with safe `filesDir` fallback whenever `getExternalFilesDir(null)` is unavailable on AOSP boxes or Fire OS 6 devices.
    - Optimized Android Studio build speed & release APK size: added `-dontoptimize` in `proguard-rules.pro` (eliminates the 88% R8 stall), enabled `org.gradle.vfs.watch=true` + 6 GB G1GC daemon heap, added a signed `fastRelease` build variant for 3–6s local test installs, removed redundant blanket `-keep` rules for OkHttp/Media3/Coil/Cast/Play Services, scoped BouncyCastle/SMBJ keeps to runtime-used packages, and stripped `DebugProbesKt.bin` + unused `META-INF`/localized metadata.
    - Added **TiviMate-Grade Player & Guide Polish** (`PlayerQuickSheet`, `PlayerActivity`, `ChannelActions`): 1-tap **`⚡ Quick`** player sheet (Recent Channels last-10 switcher, live **Stats for Nerds** toggle, live **Subtitle Size** picker `S/M/L/XL`, **Household Profile** switcher, **Open in External Player**, and scannable **Web Dashboard QR Code**), long-press `Last` button for Recent Channels, live **`↻ Reconnecting (1/3)…`** countdown pill during transient drops, and **Download for Offline** + **Open in External Player** actions in the Guide long-press context menu.
  - Kept set-top TV guide scroll alignment (`scheduleGuideAlign` / `alignGuide`) scoped independently from mobile touch scroll locking so both TV and mobile stay perfectly aligned.
  - Hardened DVR (`RecordingService` auto-stop when idle, shared HTTP pool, pre-flight `< 250 MB` storage guard with automatic SMB fallback, mid-recording `100 MB` low-space safety cutoff + 256 KB buffered writes, auto-reconnect on transient direct-stream drops, accurate SMB byte sizes, 500-entry HLS dedup cap, immediate start on dashboard schedule save) and Reminders (`ReminderClock` alarm deduplication + cloud-sync arming), backed by unit tests.
  - Migrated build config to AGP 9.5 native DSL & built-in Kotlin with **0 compiler warnings**.

---

<div align="center">

## What's in 0.2.127

- **Amazon Fire TV Stick playlist optimization**:
  - Eliminated per-batch hash file rewrites during catalog ingest (reads once, writes once per bucket) to prevent flash I/O thrashing and memory pressure on Fire Sticks.
  - Low-RAM and Fire Stick devices now ingest Live TV first so the guide opens immediately, then load Shows and Movies sequentially only when VOD is enabled.
  - Added single-threaded streaming `JsonReader` parsing on 256 MB heap devices (`XtreamClient`) and explicit Amazon Fire TV / ≤2.2 GB RAM detection (`DeviceForm`).
- **Live TV playback stability (no more 15-second spooling/replays or freezes)**:
  - Removed the false-positive `frameFrozen` / `lastVideoFrameAt` watchdog check that tore down healthy streams and caused the provider to replay the last few seconds; recovery now only triggers if the player is genuinely stuck not playing for 20 seconds, and rebuilds the player if a hardware decoder wedges.
  - Removed `TvSingleHardwareSelector` so Android TV uses `MediaCodecSelector.DEFAULT` instead of dropping to CPU software decoding.
  - Switched `NetworkWatcher` to `registerDefaultNetworkCallback` and guarded `reconnectNow()` so background network events never interrupt a playing stream.
  - Added `WAKE_MODE_NETWORK`, disabled mid-stream live frame-rate surface switches, kept live speed at `0.98x–1.00x` so ExoPlayer never drains its own cushion, and tuned live buffers (`15s–40s`, `2s` start, `3.5s` rebuffer).
- **Web dashboard TV Remote & Play button fixes**:
  - Fixed TV Remote Left and Right (plus Up, Down, OK, and Back) on both the guide and player with proper `SOURCE_DPAD` key dispatch and `focusSearch` fallback.
  - Fixed the `▶` Play button across Channels, Favorites (Search & Manage), and TV Remote Favorites to resolve kind-prefixed IDs (`live:`/`vod:`/`series:`), launch playback immediately on the TV even from other screens, and show toast confirmation.
- **Web dashboard → set-top box sync & Favorites redesign**:
  - Fixed split-pane TV guide refreshes (`adoptRemoteLineup` and `reloadFavorites`), guaranteed revision bumps on local dashboard saves, mirrored local dashboard lineup saves to the cloud, and fixed stale DataStore preference cache reads.
  - Redesigned the web dashboard Favorites tab into **Auto Build**, **Search**, and **Manage** sub-tabs.
- **UI responsiveness & cloud crash reporting**:
  - Fixed Samsung adaptive refresh-rate switches (120Hz↔60Hz) from rebuilding browse grids on touch, eliminated redundant adapter layout passes, and added synchronous crash persistence/upload with log redaction.

---

<div align="center">

## What's in 0.2.118

- On a Galaxy Fold (SM-F976U), opening the inner screen redraws Live, Movies, and Shows across that full display. The cards get wider and taller, and more of them fit on a row. The cover screen stays the smaller layout.

---

<div align="center">

## What's in 0.2.117

- New signed build. Install it over 0.2.116. A television opens the guide instead of staying on the spinning circle, and a phone or Galaxy Fold fills the screen with cards when you rotate or unfold.

---

<div align="center">

## What's in 0.2.116

- New signed build of the television startup fix. Install it over 0.2.115. The guide opens instead of staying on the spinning circle.

---

<div align="center">

## What's in 0.2.115

- Opening the app on a television shows the guide instead of staying on the spinning circle. A dashboard connection that never sends a request is closed quietly, and it no longer blocks startup.

---

<div align="center">

## What's in 0.2.114

- On a phone, including a Galaxy Fold (SM-F976U), turning from portrait to landscape or opening the inner screen lets Live, Movies, and Shows use the full display. The cards grow to that width. The section you were on stays open. A television stays in landscape.

---

<div align="center">

## What's in 0.2.113

- Logo requests use their own threads, so the web dashboard does not stall while pictures download.
- A television uses one hardware video decoder and does not open a second live stream for the guide preview or the next channel. Changing channels closes the old socket first.
- Channel, guide, and category list updates are calculated off the main thread.
- Switching between Wi-Fi and cellular drops the old sockets immediately instead of waiting out a timeout.
- A catalog refresh rebuilds the search index once, after the rows are saved.
- With audio delay at zero, Dolby and 5.1 can pass through. The dashboard reuses an unchanged login, lineup, or favorites response.

---

<div align="center">

## What's in 0.2.112

- When a live channel is playing again, the Reconnecting label clears and the channel title comes back.

---

<div align="center">

## What's in 0.2.111

- Live playback keeps a larger buffer and waits longer before treating a pause as a frozen channel, so a brief stall does not restart the stream.
- The on-device dashboard address stays on the phone’s own page instead of the cloud API.

---

<div align="center">

## What's in 0.2.110

- Add locals for a ZIP such as 30677 uses the nearest market that actually has stations. Watkinsville matches Atlanta ABC, CBS, NBC, and FOX even when the group title is not the usual local group.
- A live channel that falls behind the broadcast, or hits a missing segment, jumps back to the live edge instead of logging a playback failure.

---

<div align="center">

## What's in 0.2.109

- Turning a phone from portrait to landscape rebuilt Live, Movies, and Shows in place. A Galaxy Fold still kept the narrow window, so the cards did not fill the inner screen. That is fixed in 0.2.114.

---

<div align="center">

## What's in 0.2.108

- Turning a phone from portrait to landscape rebuilds the guide in place. The section you were on stays open.
- A tap on Live, Movies, Shows, or the other left-rail items opens that screen on the first tap.

---

<div align="center">

## What's in 0.2.107

- On a phone, the guide loads the current program for the rows on screen, so scrolling and the channel buttons stay responsive.
- The favorite button stays on a portrait channel row.
- A favorite saved on the web dashboard refreshes the open channel list. Lineup edits still apply on the next cloud check. Reminder time changes and deletes from the web use the same reminder list the phone reads.

---

<div align="center">

## What's in 0.2.106

- When a frozen channel finishes reconnecting, the player clears the Reconnecting label, restores the channel title, and refreshes the guide line.

---

<div align="center">

## What's in 0.2.105

- The phone, tablet, and TV builds call `https://your-cloud-api.example.com`. There is no separate mobile API.
- Playlist login, lineup edits, favorites, recordings, reminders, and continue watching are stored in the Olympus-IPTV PostgreSQL database behind that hostname.
- When that database already has a catalog, live channels, movies, and shows load from `cloud_categories` and `cloud_channels` instead of calling the provider again.
- The guide prefetches the current program for the visible channels and shows the title on the row. Phone portrait still shows the progress bar and the next program.
- The home-network dashboard on the device is unchanged. Cloud setup and cloud edits use the hostname above.

---

<div align="center">

## What's in 0.2.103

- On the login screen, Down and the keyboard Next key move in order: email, server URL, username, password, ZIP, the two checkboxes, then Connect. Left and right stay on the field you are typing in. The window pans that field above the keyboard so the dashboard label does not cover it.
- Connect asks the provider to accept the URL, username, and password before it leaves this screen. If the provider does not answer within 20 seconds, or rejects the login, a popup says so and the form stays up. A good login opens auto-add favorites. The QR pairing step is gone.
- The cloud setup form stores the email, ZIP, host, username, and password against the device ID shown on the TV. The QR code and the copied address already include that ID, so the form is filled in when it is opened from the TV. Restart the cloud portal after deploying this server so that save is live.
- Every 10 seconds the app reads revision numbers from the cloud database. It rewrites the device only when a number is newer than the one it already applied. That covers the playlist login, lineup edits, favorites, recordings, reminders, and continue watching. The check runs before the device is set up, so a playlist saved on the cloud starts the channel list on the next pass. A live connection still applies a save as soon as the database has it.
- Remove playlist shows “Removing playlist…” and then returns to the login screen. Back from a Settings page returns to that Settings card. Back from the Settings card grid still returns to the left rail.

---

<div align="center">

## What's in 0.2.92

- Movies and shows open a title page before playback. The page shows the backdrop, poster, title, year, rating, network, content rating, genres, and synopsis when a TMDB key was built in.
- Play starts the movie or the next episode. Trailer opens the YouTube trailer when TMDB has one. The heart marks a favorite, the check marks it watched, and a series lists seasons with episode stills, air dates, and runtimes.
- Cast on that page uses the same chooser and format disclaimer as the player. Without a TMDB key the page still shows the poster, title, Play, favorite, and the episode list.

---

<div align="center">

## What's in 0.2.86

- **Live Games Now** sits on Live TV after For You. It gathers sports and event feeds that are on now, or start within 30 minutes, from every live group. The same event is listed together, with 4K and 1080 feeds first.
- Numbered event slots, and any live feed whose guide says No Event, Off Air, TBA, No Game Today, or a similar placeholder, stay off the list until 30 minutes before the listed start. They come back for the event and drop off when it ends. Group counts follow the channels that are actually showing. An empty live group says so in the guide pane.
- The live timeline is 90 minutes from the current half hour. A row shows the program on now and anything that starts in the next 30 minutes. Visible rows refresh about every 30 seconds as a program ends or is about to start. A saved guide row is refreshed after 5 minutes.
- A local name such as `NE | Lincoln | FOX KFXL` shows as `FOX KFXL (Lincoln, NE)`. A numbered slot keeps the slot and the event. Superscript marks such as HD, 4K, and Live are removed. A guide title that repeats the channel name drops that prefix.
- Event times understand Atlantic, UK, and Central European zones along with US zones. A slot with nothing in the next 30 minutes reads “No live event right now.” One that starts inside that window reads “Starting soon.”
- Search turns short sports names into the full name: Bucs, Pats, Niners, EPL, UCL, F1, and the other nicknames in that list.
- A live stream that is still buffering after about 6.5 seconds tries another feed of the same channel, including the same event on another provider. East and West labels are ignored when those feeds are matched.
- Group cards are 3 across on TV, 2 across from 600dp, and 1 across on a narrower phone. Cards use the group icon. The channel row does not draw a focus box.
- On the guide, Left from the current program returns to the group cards. Up and Down on the group grid stay on the cards.
- The library database is schema 14. An existing install adds the event-window columns. The account, favorites, and catalog stay.

---

<div align="center">

## What's in 0.2.83

- Admin is six image cards: Refresh, Web-UI, EPG edits, VPN, About, and Favorites. Provider status, quiet hours, backup, and Magic Cleanup open from the Setup card under the grid.
- The full guide’s channel column is as wide as the longest name on screen, plus the number and logo, and stops at 40% of the screen. Program titles stay on one line. Opening New, For You, Recently Watched, or Favorites starts the clock on the current half hour. It does not keep an older window and scroll into it (that had jumped the slot about 60 minutes) and it does not pad a title onto two lines.
- Player Audio has **Night mode** (off until you turn it on; Android 9 and newer), **Screen off, audio only**, and **Audio earlier / Audio later** (100 ms steps, up to half a second, kept per channel).
- M3U `#EXTVLCOPT:http-user-agent=` and `#EXTVLCOPT:http-referrer=` (or `http-referer`) are sent with that channel.
- The mini guide lists other channels airing the same title first, marked **Same show**. Search that misses a typed prefix tries again with punctuation ignored.
- A new recording starts **2 minutes early**. You pick an ending **5, 15, or 30 minutes** after the guide time. A finished recording remembers where you stopped.
- On a low-memory stick, a memory warning clears the logo cache and the in-memory guide. The guide on disk stays. Debug builds log main-thread disk reads. Release builds do not.
- The dashboard Backup button downloads one JSON file: favorites, group renames, parental PINs, strip rules, the rest of the lineup, and per-channel audio offsets. `portal/data/store.json` is not in a source zip made with `archive-source.ps1`.
- Phone and tablet layout, gestures, cellular limits, and the 6-hour Wi-Fi catalog job are described under [Phones and tablets](#phones-and-tablets). Those are in this signed file.

---
