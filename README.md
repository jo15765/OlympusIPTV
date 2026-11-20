<div align="center">

# Olympus-IPTV

**Beta.** Feedback is greatly appreciated.

### For the latest and greatest, build your own APK from this code.

The latest signed file is **0.2.66** (versionCode 67). **One APK** installs on Android TV / set-top boxes and on phones and tablets. Includes playlist reorder, Top 10 watched favorites, video decoder modes, phone PiP gestures, Chromecast (with IPTV caveat), multi-source VOD ratings/reviews (TMDB + Watchmode), YouTube trailers, Simkl/Trakt scrobble (mutex), New/Just Added playable-only + D-pad focus trap, player play/pause + progress auto-hide (3 s), parental Remove lock, plus Fresh/player/ratings performance opts and release R8 minify. Optional keys in `local.properties`: `tmdb.apiKey`, `watchmode.apiKey`, `opensubtitles.apiKey`, `sendgrid.apiKey` + `sendgrid.fromEmail`.

**This is a player only. You must provide your own streams.**

Olympus-IPTV does not include channels, movies, shows, or stream addresses. It plays an M3U playlist or an Xtream Codes account you already have.

</div>

---

<div align="center">

## Contents

</div>

- [Start here](#start-here)

- [Phones and tablets](#phones-and-tablets)

- [Video walkthrough](#video-walkthrough)

- [What it does](#what-it-does)

- [The live guide](#the-live-guide)

- [Movies, shows, and New](#movies-shows-and-new)

- [Names and groups](#names-groups-and-countries)

- [Favorites](#favorites)

- [Search](#search)

- [The player](#the-player)

- [Recording](#recording)

- [Reminders](#reminders)

- [Dashboard](#the-home-dashboard)

- [Admin and About](#admin-and-about)

- [Build](#build-and-run)

- [Limits](#limits)

---

<div align="center">

## Start here

</div>

Olympus-IPTV is one APK for Android TV / set-top boxes and for phones and tablets. On TV it is built for a remote. On a phone it adapts for touch. While the app is open, it hosts a home dashboard so a phone or computer on the same Wi-Fi can edit the same lineup.

| | |
| --- | --- |
| Signed file | `C:\Users\chalu\Downloads\olympus-iptv-0.2.66.apk` |
| What that file is | versionName **0.2.66**, versionCode **67** |
| Source in this tree | versionName **0.2.66**, versionCode **67** |
| What to run | Install the signed 0.2.66 APK, or build this source |
| Install | Over the existing copy (TV or phone). Uninstalling wipes the saved account, playlist, and favorites. Downloader works with the same file. |
| Launcher name | Two centered lines: Olympus over IPTV |

**0.2.66** is the current release: Fresh opens without blocking on warm, playable checks skip when the library stamp is unchanged, ratings/trailer/seek and browse paging are leaner, plus 0.2.65 focus/chrome behavior.

### What’s new since 0.2.56 (enhancements)

| Feature | Where |
| --- | --- |
| Reorder playlists | Home account list — ▲/▼ or Left/Right |
| Top 10 watched favorites | Group card on Live / Movies / Shows (favorites you watch most) |
| Video decoder | EPG edits — Hardware / Hardware+extensions / Failsafe / Software prefer |
| Phone PiP | Player **PiP**, long-press **Open in PiP**, swipe up on a title, leave player for Home (Admin **PiP gestures**) |
| Chromecast | Player **Cast** — shows a format/codec disclaimer first; many IPTV HLS links still fail on the default receiver |
| Ratings & reviews | See [Where ratings appear](#where-ratings-appear) — browse chip, long-press sheet, player caption (TMDB + Watchmode) |
| TMDB items limit | EPG edits — how many TMDB search hits to keep (5 / 10 / 20 / 40) |
| YouTube trailers | Player **Trailer**, or from the ratings sheet (needs `tmdb.apiKey`) |
| Simkl / Trakt | EPG edits **Scrobbler** — only one provider at a time; paste token (+ optional client id) |
| New playable-only | New/Just Added only lists premieres that have a playable stream (series season must exist) |
| New focus trap | On New/detail, D-pad stays on the page; **Back** is the exit |
| Player chrome | Play/Pause + progress show briefly, then hide after **3 seconds**; center/tap shows them again; **Info** / phone **Menu** opens the full row |
| Faster Fresh open | New opens from cache without waiting on warm; playable re-check skipped when the library stamp is unchanged |

---

<div align="center">

## Phones and tablets

</div>

There is **no separate mobile APK**. The same `olympus-iptv-0.2.66.apk` detects whether it is running on a television (Leanback / UI mode) or a touch-first phone or tablet.

| On phones and tablets | What changed |
| --- | --- |
| Guide chrome | Top **Back** + title + **Admin** / **About** / **Search**; bottom Live / Movies / Shows tabs; sticky Groups header with Search; left TV rail hidden; groups are a single tall list |
| Group filter | Typeahead field under Groups — jump to a group by name without leaving Live |
| Now playing | After leaving the player, a resume strip shows the last title; tap Resume to open it again |
| Live “now” | Channel rows show one line of what’s on (no TV EPG grid) |
| Empty CTAs | Empty Favorites opens Favorites setup; empty Continue points you to Movies/Shows |
| Pull to refresh | Swipe down on the groups list to refresh the catalog quietly |
| Haptics | Light feedback on Play, group open, PIN set / unlock / wrong PIN |
| Live play | Opening a Live group shows a full-width channel list (not the TV EPG split); tap a row to play |
| Long-press | Bottom sheet for Favorite / Record / Remind / Queue / **Ratings & reviews** (Movies/Shows) / **Open in PiP** (TV still uses the dialog menu) |
| PiP | Picture-in-picture while you browse: Open in PiP, swipe up on a title, or leave the player for Home when **PiP gestures** is on |
| Skeletons | Placeholder rows while the first page of a group loads |
| Scroll / memory | Remembers position per group; return from player lands on the same row; leaving a huge group drops the in-memory page over ~400 rows |
| Look | Soft charcoal gradient background; raised Continue strip and bottom tabs; honey focus rings (TV also scales the focused card slightly) |
| Lighter open | No AutoTransition when columns change; no side-by-side EPG pane; cold start skips dashboard/Watchmode warm (New / web still trigger it); no Live auto-preview on focus (saves a second decoder); smaller Coil caches; phone EPG prefetch waits ~220 ms after focus settles |
| Visible Back | Guide (opened group / PIN / Admin / About), Fresh, Parental, Favorites setup, Search, and the player all show an on-screen Back |
| Touch scroll | Favorites setup, Episodes, Queue, Search, and Admin/Refresh cards scroll without focus traps |
| Player | Portrait dock; soft top overlay scrim; play/pause + scrubber auto-hide after 3 s (tap shows again); **Connecting…** only while truly waiting; error card with Retry / Next / close; sleep timer; Cast / Trailer / PiP on the menu; one ExoPlayer only |
| System navigation bar | Player and main screens pad for system bars so the 3-button or gesture bar does not cover play controls or key chrome |
| Touch | Tap the video to show or hide controls; orientation is unlocked by default |
| Immersive watch | While controls are hidden, system bars hide; swipe shows them briefly; when the menu is open, bars stay visible and controls sit above them |

TV remotes and landscape-first layouts are unchanged on set-top boxes. Phone PiP is not used on Leanback TV.

---

<div align="center">

## Video walkthrough

</div>

<div align="center">

[![Olympus IPTV — Onn / Google TV setup and how-to](https://vumbnail.com/1248359692.jpg)](https://vimeo.com/1248359692)

</div>

It walks through enabling unknown sources on an Onn box, installing Downloader by AFTV, entering a download code (provided separately), signing in, waiting for the catalog, using Favorites setup / Queue / New / Parental, browsing the live guide, and the main remote actions (OK, Hold OK, Back). Use it alongside this README if you are new to IPTV apps or Android TV remotes. Click the preview to watch on Vimeo.

---

<div align="center">

## What it does

</div>

- Plays live channels, movies, and series from an M3U or M3U8 URL, or from an Xtream host, username, and password.
- Runs from **one APK** on a Shield, Onn, Google TV Streamer, and other Android TVs, and on Android phones and tablets (including install via Downloader). Minimum is Android 7 (API 24). The APK includes 32-bit and 64-bit ARM. Shield Experience 9.2.4 is Android 11. The number in parentheses on that Shield screen is NVIDIA’s build, not the Android version.
- Signs in with an email and a 5-digit US ZIP. The ZIP is only used to place local channels. Include VOD starts checked. Include International starts unchecked, so foreign groups stay out until you turn that on.
- Creates a device id shaped `OI-XXXX-XXXX-XXXX`. One email can have more than one device. Each device keeps its own lineup, favorites, catalog, and recordings.
- Pulls the provider catalog once per list (live, movies, shows), shows a percent in steps of 5, then the counts, then the auto-add cards. Later opens skip the favorites splash when the catalog is already on the device. Favorites setup, at the top of Live TV, can open that screen again without Continue. Back from there returns focus to that chip.
- Lets the first account be saved from the dashboard address on the login screen. The page shows **Set up this device** until an account exists. Saving it stores the account on the device and opens the full dashboard so favorites and filters can finish in the browser and sync back. The login screen on the player moves on as soon as that save lands. Opening the app again reads that saved account once and opens the group screen as soon as the saved live groups are ready. Dashboard warm-up and “New” card prep wait until after that first paint. The login screen is created only when no account is stored.
- Browses with a remote: **Search, Live TV, Movies, Shows, Recordings, Admin, and About.** On Live TV, the top of the groups screen has Favorites setup, Queue, New / Just Added, Reminders, and Parental, plus the current date and 12-hour time on the right. There is no favorite-channel name strip across the top, no Settings item, and no Sign out. Home can reorder saved playlists. Movies/Shows can show ratings and trailers when `tmdb.apiKey` is built in.

---

<div align="center">

## The live guide

</div>

When an account is already saved, the app opens on two panes: the settings rail and the group cards. The login screen is not created. The rail is on screen while the group names load, and the cards fill in as soon as those names are ready from the local library. Heavy work (dashboard snapshot, image cleanup, Watchmode “New” cards) starts after that paint so a returning launch stays quick. The channel list and the hour-by-hour guide stay hidden until a card is opened. It does not start the last channel. The rail stays open, and its divider sits at the end of the longest label. The title on that rail is **Olympus IPTV** on one line. The launcher name under the icon stays on two lines. Above the group cards, Live TV shows the current date and time (`MM/DD/YYYY  hh:mm AM/PM`) instead of a row of favorite channel names.

1. **TV, Movies, or Shows.** Group cards fill the space beside the rail, three smaller cards per row. Choosing one of those on the rail opens its cards and moves focus to the top-left card. A card shows the group name and, once counted, how many channels or titles it holds. Recently watched says Resume watching. Continue says Pick up where you left off. Moving across the cards does not load channels. The counts are read once and kept until the library changes.
2. **OK on a card**, or Right from a card on the right edge, opens that group and loads it then. Focus leaves the group card. On TV, a Live group under 250 channels opens the side-by-side EPG and lands on the program that is on now for the first channel. A Live group with **250 or more** channels (common for a single provider “24/7 Shows” folder) still opens the guide, but fills it with **hour blocks of each channel name** (no provider EPG download). Channels page in **20** at a time as you scroll, so a thousand-row folder stays usable. Movies and Shows land on the first title. The other groups are not listed on that screen. Back from About or Admin returns focus to that same item on the left rail.
3. **Right** from a channel moves into the guide and later in time. **Left** from the first time slot returns to the channel list. Left from a later slot steps one slot back.
4. **Back** from the channel list or the guide returns to the group cards. Back from the cards returns to the rail. Back on the rail does not quit the app.
5. **Left** during full-screen playback returns to the channel list. **Back** during live playback returns to the channel and guide for that group. Back from there returns to the group cards. Back during movie or show playback still leaves the player and returns to the title you started.

Hold OK on a live channel or a program for favorites, move up, move down, record, and **Remind me**. Movies and Shows use that same hold, without move up or move down, and add **Add to Queue** when the title can be queued. Remind me asks for 15, 30, 45, or 60 minutes before that program starts. When that time arrives, a card appears over the current screen: the channel number, the show, and the start time, with Ignore, Remind me in 5 minutes, and Tune to channel. Tune opens that channel. **Reminders** is a chip at the top of Live TV. It lists every reminder. Edit changes the minutes. Delete removes it. The same list is on the dashboard Reminders tab.

On the channel list or the program guide, the number keys open a large Channel dial in the middle of the screen. The digits stay up while you type. After a short pause, or OK, the guide jumps to that channel. The number is the row on screen, or the channel’s own number when that matches. A number that is not in the open list stays on screen briefly and says that channel is not in this list. Back clears the digits before it leaves the guide.

A phone Save that hides, renames, or strips names is applied to the guide you already have open. Favorites update when you add or remove one.

Each channel row is one line: number, logo, and the lineup name, such as `GA | Atlanta | Fox 5`. The row is the same height as the program bar beside it, and the two lists scroll together. On the full guide the channel column grows to the longest name on screen and the times start at the end of that name. Adding channels measures only the new names.

The header shows the date and clock from the TV’s Date & time setting, including that setting’s time zone, then `Group :` and the group name. The half-hour labels and the now line use the same clock. The show under the line is the listing whose start and end cover that time. Changing the date, clock, or time zone on the TV moves the guide to match. Program titles sit in rounded bars. When a program started before the visible window, its title shifts into the on-screen part of that bar so you can still read what is airing. If that visible slice is too narrow for the name, the guide rows grow taller and wrap the title onto a second line. The selected bar is the light highlight. The clock stays on the half hour above the bars. The time under a title is not repeated on the bar.

**Now** sits in that header while the guide is open. It scrolls the timeline back to the current half-hour, focuses the program that is on now for the row you were on, and keeps the now line on screen. Up and Down in the guide follow clock time across channels, so a long program that started before the current half-hour stays selected when it is still airing. Focusing that bar does not jump the scroll to the end of the program or to the next title.

Empty stretches are filled. A channel whose listings are only its own name, or that has no guide data, shows that channel name in hour blocks instead of a gap or a single “No EPG data” sliver. Real program titles are left as the provider sent them. Mega Live folders (250+ channels, such as provider **24/7 Shows**) always use those hour-name blocks for the whole group and do **not** download provider EPG for that folder, so the guide stays scrollable and selectable while channels page in.

OK on a past program plays catch-up when the provider marked that channel for archive or catch-up (Xtream `tv_archive`, or M3U `catchup` / `catchup-source` / `catchup-days`). Channels without that mark leave past slots as guide only.

There is no preview window in the corner on phones. On TV, the focused live channel can be warmed quietly so full-screen playback starts on that same player.

**Continue** is the first group on Movies and Shows when a movie or episode was stopped in the middle. It lists that title and the minute you reached. OK on a movie resumes it. OK on a show resumes the episode. The group is hidden when nothing is in progress. **Recently watched** stays the first group on TV, and the next group on Movies and Shows, directly above Favorites, even before anything has been played. **Top 10 watched** appears when you have favorites and watch history — it ranks those favorites by how often they show up in Recently watched. **Favorites** lists only the favorites for that section. Live TV shows the current date and 12-hour clock above the group cards instead of a favorites quick-zap strip. **Guide density** on EPG edits switches Comfortable or Compact row height.

That hold is one menu. Live TV includes move up and move down. Movies and Shows leave those two off and include Add to Queue. Record opens its own window. The star, record, up, and down buttons are not on the channel row, because Right opens the guide.

---

<div align="center">

## Movies, shows, and New

</div>

Movies are video-on-demand titles that are not part of a series. Shows are series. Both open on the same group cards as TV. OK on a card opens that group’s poster grid and leaves focus on the first title.

Opening a show keeps seasons as a horizontal strip across the top and that season’s episodes listed underneath. It does not start playing on its own. A season chip is checked when every episode in it has been watched. Changing the season refreshes the episode list under it. Each episode shows its description and length when the provider sends them. An episode title is the season and episode code plus the episode name, such as `S02-E01 - Episode Name`. The series name is not repeated. An episode is checked when playback passes 95 percent. Hold the center button on an episode to check or clear that episode. **Mark this season watched** checks every episode in the open season. Hold the center button on a season chip to do the same thing. When that season is already checked, the control clears the checks. Back from the show returns to the previous screen.

When the provider sends them, a movie or episode shows a short line with plot, year, and length before it plays.

### Where ratings appear

With `tmdb.apiKey` built in (and `watchmode.apiKey` for Watchmode scores on the sheet):

| Place | What you see |
| --- | --- |
| Movies / Shows browse | After focus settles on a title, a short score line under it (e.g. `TMDB 7.4`), scores only — no reviews while scrolling |
| Long-press → **Ratings & reviews** | Full sheet: TMDB and Watchmode scores, overview, up to three TMDB review snippets, plus **Trailer** and **Play** |
| Player caption | While watching Movies/Shows, the top meta line can append the same short score chip when data is cached or fetched |

**TMDB items limit** on EPG edits controls how many search hits to keep for that lookup. Without the TMDB key, chips and the sheet stay empty; the rest of the player still works.

When an episode ends, the player counts down from 10. At 0 it plays the next episode in that season, then the first episode of the next season. **Skip intro** starts that episode about one minute in, or jumps a minute ahead if the theme is already playing. The last episode of a show does not loop back to the first. Back during the countdown leaves the player. Up or Down also leaves the countdown and changes episode.

### New this week

**New / Just Added** is a chip at the top of Live TV. It is a Sunday-through-Saturday list of US streaming premieres from Watchmode.

| | |
| --- | --- |
| Services | Netflix, Prime Video, Disney+, Hulu, Max, Apple TV+, Peacock, Paramount+ |
| What Watchmode does | Names, dates, artwork, and descriptions. It does not host video. |
| What gets a poster | Only a premiere that matches a title in your Movies or TV Shows **and** has a playable stream. Series premieres for a season that is not in your library yet are left off. Exact matches, the same title with a year or season on the end, and shared short heads still count for matching. |
| The grid | Four posters per row. Each card shows the whole poster, the title, and the service underneath. The artwork is the same poster used in Movies or Shows. The list scrolls. D-pad focus stays on this page; **Back** leaves. |
| Select a poster | Opens the poster, service, day, and description on one detail card. A movie keeps Play there. A series keeps that card and adds seasons in a horizontal strip under it; choosing a season lists that season’s playable episodes underneath with a thumbnail, description, length, and Play. Focus stays on seasons/episodes (edges do not drop focus); **Back** returns to the grid, then to Live TV. Hold the center button to add it to Queue. |
| Refresh | The week is prepared in the background when the app opens and again after the catalog is on the device. Opening New uses the cached list immediately (it does not wait on warm). Playable checks re-run on warm when the library stamp changes, so a season that was missing can appear later. |
| Key | `watchmode.apiKey` in `local.properties` (see [Build and run](#build-and-run)). Without it, the screen says the releases cannot load. Watchmode ratings in the VOD sheet use the same key. |

A movie or episode you left in the middle shows Continue and the minute you reached, both on its poster and in the Continue group. A spot under 15 seconds, or within 20 seconds of the end, starts from the beginning and is left out of Continue. Live TV does not resume a minute mark, and opening the app does not start the last channel. It opens on the group cards.

---

<div align="center">

## Names, groups, and countries

</div>

The guide shows the names you set, not the raw provider strings, everywhere those names appear: the channel column, the group cards, and the dimmed caption at the top of playback. If a program title is only the provider’s channel name, that caption uses the lineup name too.

Live groups stay **provider-split by category id**. The app does not merge folders that share a display name. If another IPTV player shows several small 24/7 groups and Olympus shows one large “24/7 Shows” card with 1000+ channels, that card is one category from the panel — not a name lump in this app. Hide or rename that group on the dashboard if you want it out of the way; Bulk Group Renamer only changes the label, not how channels are counted.

When you **open** a Live group card, that group’s channels load for the group you chose (hidden channels still apply). Opened groups use a direct paged read so a card that shows 1102 channels does not open empty. Include International is enforced on the groups board and on All channels, not by wiping an opened 24/7 folder.

- **Bulk Channel Stripper.** Comma-separated prefixes and suffixes, such as `USA |, HD`. A plain word comes off the start or the end and does not eat a longer word (`HD` does not eat `FHD`). A token with a space or a mark such as `|` or `:` is removed wherever it appears. The saved tokens are listed one per line on the TV and in the dashboard, and they stay after you leave the screen.
- **Bulk Group Renamer.** Find and replace across group names. Capitalization is ignored. A bar that looks like `|` matches the `|` key. Save rewrites the stored group name, so the guide still shows the new text after you leave and come back. Back leaves the names unchanged.
- The on-screen keyboard labels the wide keys **Sft**, **Spc**, and **DEL** so they stay on one line.
- **Remove images.** Emoji, icons, and picture characters can be stripped from channel names and from group names, including the group title stored on each channel. Words and numbers stay.
- A group or channel can still be renamed on its own. A name you set for an id is kept when the provider later sends a different name for that same id.
- Countries lists every country the app knows, including ones that are not in the current download. The number is how many live channels belong to that country. A group is matched by its name, the country stored on its channels, its group title, or the start of a channel name when the group still has none. That summary is saved and reused until the library changes, so opening the list does not count the channels again. Checking the country shows that country’s groups and the channels in them. Include International left off keeps those countries available to turn on. It does not remove them from the list. Live groups that are not the home country, including a group with no country, stay off the TV and the web until that country or Include International is turned on.

On the TV, hiding a country, group, or channel updates the guide when you finish. On the web, the gold Save sends that change. A one-off rename stays with the lineup. Bulk Group Renamer is the one that rewrites the stored group names, and it does that when you press Save. Show everything again clears hidden countries, groups, and channels. It does not clear custom names or strip rules.

---

<div align="center">

## Favorites

</div>

A favorite is a key.

| Where you starred it | Key | Where it shows |
| --- | --- | --- |
| TV | `live:<id>` | TV favorites. An older bare id still counts as TV. |
| Movies | `vod:<id>` | Movies favorites |
| Shows | `series:<id>` | Shows favorites |

Search uses the section the card is under. On the dashboard, the favorites you already have are listed with a count in the Favorites header. Drag or use the arrows to reorder; Remove syncs immediately. While the device catalog is still loading, auto-add and search stay disabled with a clear banner. Search stars append one channel or remove that one channel. They do not replace the list. Movies and shows show up in dashboard search once those categories are in the local SQLite library (after the device has loaded them).

Open the guide after a dashboard favorite change. The TV keeps favorites it still has, adds ones that exist only on the dashboard, and drops ones the dashboard removed. If both sides have the same favorites in a different order, the TV writes its order back.

Auto-add can pull local major networks, major sports networks, teams you pick, and preset lineups. The presets include **DurictTV Streamer**, **YourFriendlyTV**, and **HoolaHoop Live TV**. YourFriendlyTV adds the frndlyTV-style family lineup (Hallmark, Lifetime, classic TV, and the rest of that package) when those channels are in your library. HoolaHoop Live TV adds the Hulu + Live TV lineup, including your local ABC, CBS, FOX, NBC, CW, and PBS stations. A channel that is not in your library is skipped. The ZIP is used for the local stations. Continue stays on that screen after the channels are added.

### Queue

**Queue** is a chip at the top of Live TV. Hold the center button on a movie or a series in Movies or Shows and choose **Add to Queue** from that menu. Search and New offer the same choice. The same hold on an item in Queue offers **Remove from queue**. OK on a queued movie plays it and keeps the rest of the Queue ready, so when that movie ends the player counts down to the next movie unless you choose Back to menu. OK on a queued series opens its episodes. The queue stays on the device.

---

<div align="center">

## Search

</div>

Search looks through TV, Movies, and Shows and groups the hits under those headings. Results update as you type (typeahead after the first character). Search or the keyboard search key hides the on-screen keyboard and moves to the first result. Hide keyboard does the same. A word, or the start of a word, matches anywhere in the name. A single character matches a name that has a word starting with that character. It does not require FTS5.

---

<div align="center">

## The player

</div>

The player is Media3 ExoPlayer on its own HTTP stack. Logos use Coil. Catalog and dashboard calls use OkHttp. The player does not send your streams through that client.

On a phone or tablet, tap the picture to show or hide the transport chrome (caption, play/pause, scrubber). That chrome auto-hides after **3 seconds**. Portrait can show a dock with scrubber, Back, Play/Pause, and Menu while chrome is up. Phone **Menu** (or TV **Info**) opens the full action row. An on-screen **Back** closes chrome/menu; another Back (or the system gesture) leaves the player. While chrome is hidden, the system bars hide for immersive watching. A first-run tip explains tap-for-controls. Sleep → Lock rotation remembers portrait or landscape for the next open.

The top caption shows the lineup name, the current program, and the next program. On Movies/Shows it can also show a cached rating line when TMDB data is available. Up and Down change channel inside the group, through the live list, or along the list that was on screen, depending on how you opened it. Live neighbors are prepared in the background so channel changes feel quicker. **OK / center** while watching shows the caption, play/pause, and progress for 3 seconds (press again while they are up to pause/resume). **Info** on TV (or **Menu** on phone) opens Audio, Subtitles, Record, Picture, **Last**, **Go live**, **Multi-view**, **Sleep**, **Cast**, **Trailer**, and **PiP** along the bottom. Audio picks a language carried in the stream. The player prefers **English (US)** when that track exists, remembers the language you last chose for a channel, and reuses that choice the next time the channel opens. Subtitles picks a track in the stream or an English file, and the language you last chose is remembered the same way. Record asks what to record. Picture picks Fit, Fill, or Stretch and keeps that choice for the next stream. **Sleep** offers Off / 30 / 60 / 90 minutes, then pauses and leaves the player. **Cast** opens a Chromecast picker after a disclaimer that IPTV/HLS formats often fail on the default Cast receiver. **Trailer** opens YouTube for the current VOD/series title when TMDB finds one. **PiP** (phones) shrinks playback into a system picture-in-picture window so you can keep watching while browsing. If reconnect fails repeatedly, an on-screen **Playback stopped** card offers **Retry**, **Next** (live zap), or close, with the same provider-vs-app wording. **Last** is for live TV. It returns to the live channel you were on before this one. The remote’s Last Channel key does the same. You need at least two live channels in that trail before Last has somewhere to go. Pause on live keeps you in the timeshift window; Left and Right step about ten seconds inside that window when the stream allows it. **Go live** jumps back to the live edge. **Multi-view** shows the previous live channel beside the current one, muted, and keeps that preference (TV only). Catch-up and other non-live titles use the same auto-hiding progress bar.

With **Scrobbler** set on EPG edits, Simkl or Trakt receives a start after about 1.5 seconds on a title (so rapid zaps do not spam the API) and a stop when you leave. Only one of Simkl or Trakt can be active. The **video decoder** chosen on EPG edits is applied when the player opens; spare players rebuild lazily if you change it.

When a movie or episode ends, a countdown offers the next title. For a series that is the next episode, with **Skip intro**. For Queue or a favorites list that still has titles after this one, that is the next item, with **Play next**. **Back to menu** leaves the player and returns to the guide. If you do nothing, the next title starts when the countdown reaches 0.

A live channel that drops briefly reconnects a few times and keeps playing. **HTTP 401 or 403** (provider denial) stops immediately: the player shows the **Playback stopped** card with Retry / Next / close instead of reconnecting forever. After three failed recovers of any other kind, the same card appears. **Connecting…** clears on the first video frame, when playback is ready, or when the stream is playing; it is not shown for ordinary live rebuffers after that. Movies and shows still report a playback error. A movie or episode writes its position about every eight seconds and again when you leave.

Left from full screen returns to the channel list for the group you opened, unless live timeshift can step back first. Back from full screen leaves the player and returns to the title you started. Back during the next-episode countdown leaves instead of playing the next one. Back from the subtitle list returns to the episode. Left does not leave while that subtitle list is open.

On an episode, the player caption has **Audio** when the stream has an audio track, and **Subtitles** next to Record. Audio lists the languages in the stream. Subtitles lists any subtitle track in the stream. A live channel only offers the languages already carried in that stream. For a series episode it also searches TVsubtitles.net for that show, season, and episode, and OpenSubtitles when `opensubtitles.apiKey` is in `local.properties`. The downloaded list is English. OK on a row switches the track, or saves that file, and keeps the minute you were on. **Subtitles off** removes it. Up and Down move through the list instead of changing episode or channel. The picture keeps playing while the list is open.

When an episode finishes, **Next episode in 10** counts down in the corner. **Skip intro** is on that card. Press it during the countdown to open the next episode about one minute in. If the countdown finishes, the next episode starts at the beginning and **Skip intro** stays until that minute has passed.

---

<div align="center">

## Recording

</div>

Record can view recordings, schedule one, or set a network share.

A recording follows a program. It starts one minute before the program and stops one minute after it ends. If the program is already on, it starts immediately and still stops one minute after the scheduled end.

Start one from the long-press menu, from Record on the player caption, from Schedule a recording, or from the dashboard Record tab. Record upcoming episodes queues later airings of the same title on that channel, up to 24, ignoring season and episode marks. The shared title has to be at least three characters. That choice also watches for new airings of the same title on that channel and schedules them when the guide updates. Recordings lists those watches; Stop watching ends the pickup. Already scheduled items stay. One recording runs at a time, so the next episode waits. If the new window overlaps another scheduled or active recording on this TV, a dialog names both titles and the channel already booked. Cancel leaves the list unchanged. Record anyway still schedules the new one.

Files can stay on the TV or go to an SMB share on the LAN. The share needs a server, a share name, a folder, and optionally a username and password. A blank password on a later save keeps the password already stored. A finished recording plays on the TV. A file on the share is read over SMB. The Recordings screen shows how much space those files use and how much free space remains on that folder. The dashboard can download or delete a finished file while the app is open.

---

<div align="center">

## Reminders

</div>

**Reminders** is a chip at the top of Live TV. Each set reminder is a card with the show, the channel, the start time, and how many minutes before it. Edit offers 15, 30, 45, or 60 minutes before the start. Delete removes that reminder. **Quiet hours** on that screen (23:00–7:00 by default) holds due reminders until quiet hours end, then shows them. The dashboard has the same list on its Reminders tab. The reminder card that appears at the chosen time is unchanged: Ignore, Remind me in 5 minutes, or Tune to channel.

HLS streams are saved by following the playlist. A direct stream is copied until the end time. If the window passes before a recording can start, it is marked missed.

The recorder is a foreground service started after the guide has opened, so a scheduled recording can start when you are not on that screen.

---

<div align="center">

## The home dashboard

</div>

While the app process is running it serves the dashboard on the home network. It starts on port **8765**. If that port is already taken, it moves to the next free port and the address on the login screen updates. Admin, then Web-UI, shows the same address. Port 8443 stays with Cast on a Shield, an Onn box, and a Google TV Streamer. The address on screen is `http://` plus the TV’s own Wi-Fi address, for example `http://192.168.x.x:8765`. A phone on the same Wi-Fi opens that address. The server stops when the app process stops. Clients that are not on the home network are refused. Admin on the phone has the same VPN card as the TV, including a backup city. A VPN sends this app’s internet through that tunnel. Addresses on the home network, including this dashboard, stay on the LAN. If the first city does not answer, the backup city is tried. After a successful connection, a drop keeps the tunnel up and tries the other city. New streams wait until one of them answers, and they do not fall back onto the home connection. The Reminders tab lists the same reminders as the TV and can change the minutes or delete one. A running app keeps the page it already loaded until that process starts again.

Sign in with the email and the device id from Admin, then Web-UI. Opening a device loads groups, countries, a channel count, the lineup, favorites, and recordings from one saved copy. The group list, including each group’s country and the country numbers, is reused until the library changes. Checking a country updates that card. Checking or hiding one group or one channel updates that row. A group you have not reordered shows its first 400 channels. A group you have reordered keeps that order. Search, rename, and drag redraw the list they changed. Stream addresses are not sent to the page.

The sign-in page shows the Olympus-IPTV mark, the address for this Wi-Fi, and the current log. The page can edit countries, groups, channels, strip rules, bulk group rename, image removal, favorites, recordings, reminders, and a JSON export or import of the lineup. Backup export and import is how the same lineup moves to another device on the account. Import first shows a short preview of hidden countries, groups, channels, renames, and parental PINs, then asks before it writes. The groups list uses the same rules as the TV, so a group that is off the guide is off the web. On the TV, Admin is two rows of two cards: Refresh Options, Web-UI, EPG edits, and VPN. The web Admin page uses the same large cards, plus the error log, each with a short status. Each card opens its own page. Back returns to the cards. Each log stays one file. At 400 KB the current log is renamed with the date and time and a new file starts. Older files are kept. Passwords and stream credentials are not written into the log.

Parental controls are the **Parental** chip at the top of Live TV, and the **Parental controls** card on EPG edits. That screen lists only the Live TV groups that are visible on the guide (same hide and country rules as the group cards). Groups are multi-select cards. Select one or more, then **Set PIN** or **Remove lock**. Set PIN opens an on-screen 0–9 keypad; digits are numeric only, and the dialog closes when the fourth digit is entered. A lock then sits on those groups here and in the top right of their Live TV cards. Hold OK on a locked group card still asks for the unlock PIN. The web Groups row has Lock for the same password. Opening a locked group shows **Enter PIN** with the on-screen number pad (focus stays on the pad on TV; tap works on phones). After a correct PIN, that group opens and stays unlocked for the parental unlock time on EPG edits (5, 15, 30, or 60 minutes; default 15), then locks again. Cancel returns to the groups. A wrong PIN shows an **Incorrect PIN** modal; OK closes every PIN dialog and returns to the group cards. The PIN is stored as a code in the lineup, shared by the TV and the web. After web setup, Favorites polls until the device catalog is ready and shows a clear “catalog still loading” state so auto-add is not empty or confusing.

**EPG edits** on the web page is the same kind of card grid as the TV screen: Show/Hide Countries, Show/Hide Groups/Channels, Show everything again, Bulk Channel Stripper, Bulk Group Renamer, Sanitize Group & Channel Names, Parse channel names into the guide, and Remove images. Countries and Groups open those tabs. Every other card opens its own page. Save on that page is what sends the edit. Back returns to the cards.

**Sanitize Group & Channel Names** opens its own page. Back returns to the EPG edits cards. The two lists on the left are channel labels. The two on the right are group labels. In each pair, the right list is what Cleanup will remove. Select a label and use the arrows to move it into the left list, or back to the right. Cleanup stays on that page, shows Cleaning…, and flashes while it works. When it finishes, the page returns to the EPG edits cards. Plain words such as `HD` or `NBA` come off the start or the end. Phrases such as `Flo Football`, `US:`, and `GA | Atlanta |` are removed wherever they appear. `HD` still does not eat `FHD`. `USA |` starts on the group strip list.

Each section has its own gold **Save**. That button sends the lineup to the TV. Favorites are sent as you add or remove one. Record’s Save stores the network share. Backup’s Import saves the file you chose. There is no single Save in the side rail.

Leaving a page after a change, and before Save, opens a notice that those changes are not saved yet. OK continues to the other page. Save is what puts the lineup on the TV. A guide that is already open applies a hide, a rename, a strip, a password, or a favorite when that save lands. If the app is holding a newer lineup in memory, the newer revision wins. There is no minute timer for these edits. The 60-minute refresh downloads the provider catalog. It is not the lineup sync.

Admin, then Error log, shows the log in a window you can scroll and resize. Entries are shown newest first (by date and time). A− and A+ change the text size. Download saves the file that is open (file order on disk is still append-only). Older log files are listed under that window.

`portal/` is an older Node copy of the page for work on a PC. The TV serves `app/src/main/assets/dashboard/`. A signed install does not need the Node server.

---

<div align="center">

## Admin and About

</div>

| Screen | What it does |
| --- | --- |
| Refresh Options | Checks the provider and saves channels that are not already on this TV. The interval can be 30, 60, 120, 360, 720, or 1440 minutes. The default is 60. |
| Web-UI | Shows the email, the device id, and the dashboard address. Remove playlist clears this TV’s account, library, favorites, lineup edits, recordings list, watch history, queue, and reminders, keeps the device id, and opens sign-in. |
| EPG edits | On the TV and on the web page: countries, groups and channels, show everything again, the bulk stripper, the bulk group renamer, Sanitize Group & Channel Names, image removal, and the optional switch that fills an empty guide from channel names. The TV also has Parental controls (multi-select PIN locks); **Hide empty groups** (on by default); **Guide density** (Comfortable or Compact); **Parental unlock time** (5–60 minutes); **Video decoder** (Hardware / Hardware+extensions / Failsafe / Software prefer); **TMDB items limit** (5–40); **PiP gestures** (phones — swipe up / leave for Home → PiP); and **Scrobbler** (Simkl or Trakt — one at a time, paste token). |
| VPN | On the TV and on the web dashboard. The five providers are large cards, two across, in the same style as the Admin cards. PIA VPN, Proton VPN, Surfshark, and NordVPN take the account username and password, then a city, then Connect. Nord can use the access token from its manual setup page in the password box. WireGuard takes a profile instead of a password. The last city stays at the top of the list, and typing filters the rest. Backup city stores a second city. If the first city does not answer, that backup is tried. After a successful connection, a drop keeps the tunnel up and switches to the other city. New streams wait until one answers. Connect sends this app’s internet through that tunnel. The Admin card and the guide show the connected city. The VPN notification includes Disconnect. Home-network addresses, including the dashboard on port 8765, stay on the LAN. The first connection has to be allowed on the TV. |
| Favorites setup | A chip at the top of Live TV. Opens the auto-add screen again. When the catalog is already loaded it skips the pulling-channels splash. Continue stays on the first-time setup only. Back from that chip returns focus to Favorites setup. |
| About | Says the app is a player only, credits @bobgoblin, and links https://buymeacoffee.com/avocadopeaches. A square QR code opens that page. |

---

<div align="center">

## Optimizations

</div>

- Guide data is stored on the device and reused. A copy newer than an hour is used as-is. An older copy is shown immediately and refreshed in the background.
- The program guide loads missing EPG for the rows on screen (plus a few neighbors), eight at a time and then four, instead of the whole open group.
- Program bars reuse their TextViews when a row rebinds. Appending channels to an open group updates the guide list without rebuilding every row.
- Focus restore after an EPG fill is debounced so the highlight does not thrash while rows land.
- Channel and program lists realign when the first visible row changes or when scrolling stops, not on every pixel.
- Channel logos keep the last loaded image while you scroll; idle refresh only reloads rows that still need art.
- Visible channel pages ask the database for larger filtered batches, and live country filters use a `country_id` index.
- Recordings, Queue, and stamp maps keep a parsed copy in memory until the stored JSON changes.
- The recording service skips remote sync and guide pickup when nothing is scheduled or recording, and only syncs again after a change or about two minutes.
- Episode lists use short cache file names, a slim on-disk row, and an LRU memory map. Shows prefetch waits until scrolling settles.
- The app does not load every channel into memory to publish a search catalog on launch. Dashboard search reads groups and channels from SQLite. That old full-catalog POST was discarded by `/api/catalog` and could OOM a ~192MB TV heap; it is gone. The process also requests a larger Java heap (`largeHeap`) and keeps Coil and SQLite caches smaller.
- Changing the guide time window clears block math and rebinds rows without tearing the list down.
- Logo prefetch skips URLs already in Coil memory or disk cache.
- Dashboard country matching for live groups is reused until the library stamp changes.
- Favorites and Recently watched warm a few live EPG rows in the background so those lists open with programs ready.
- Hour blocks for a channel are reused while you move around that row.
- The guide does not build an in-memory index of every live channel before it can draw. Channel rows are paged from the database.
- The focused live stream can be prepared with no preview window and handed to the player when you go full screen.
- Catalog lists are still one download each. Each list is split into chunks of 1,000 items and those chunks are parsed on several threads while the TV saves them.
- The dashboard request reader uses `String(bytes, UTF-8)`. The older `ByteArrayOutputStream.toString(Charset)` call exists only on Android 13 and newer, and it left Android 12 and older with an empty response.
- A connected VPN encrypts each packet in buffers it keeps, and the home-network routes are calculated once. Outgoing and incoming packets are handled at the same time. Refreshing the handshake keeps the current connection until the server answers. If that server drops, the tunnel stays up and tries the backup city when one is set, and new streams wait instead of using the home connection. The tunnel runs ahead of the guide so a stream is not waiting on the screen.
- Live now/next EPG in the guide is capped with an LRU so a long session does not keep every channel forever.
- Returning launches paint live groups before dashboard warm-up and “New” card prep so the spinner is not waiting on that background work.
- Group counts skip a redraw when the stamp-cached map is unchanged.
- Device prefs (hide empty, density, quiet hours, parental timeout, multi-view, picture, audio, subtitles) load once into memory and are reused until a save clears them.
- Quiet hours on the reminder clock uses that in-memory snapshot instead of waking DataStore on every tick.
- Reminder list stays in memory after the first read; disk is written only when the list changes.
- Recordings folder size is cached and refreshed after a local file is written or deleted.
- Multi-view borrows an idle ExoPlayer when one is free; next-channel stream prepare stays off while multi-view is using a decoder.
- Dashboard tabs rebuild only when the lineup, favorites, or recordings revision changes, and strip paint waits until EPG edits needs it.
- The guide clock tick only rewrites the date header when the half-hour changes.
- Guide open no longer blocks the main thread on DataStore; account resolve runs on IO before bootstrap.
- Catalog batch ingest hands batches through an unlimited channel with `trySend`, so parse threads do not `runBlocking` on every chunk.
- Watch progress skips a DataStore write when the playhead moved less than about 30 seconds.
- EPG program lists in memory are trimmed past 256 channels.
- Watch history JSON reuses a parsed map until the stored string changes.
- Large catalog inserts run a passive WAL checkpoint and `ANALYZE channels` afterward.
- Dashboard sanitize token lists build only when Sanitize opens, and long group/channel lists render an 80-row window.
- TMDB / Watchmode title ratings share one disk + memory cache (`TitleRatings`); browse focus requests scores only, and the full reviews sheet reuses that cache.
- Xtream VOD/series `titleLine` lookups are cached about six hours per playlist+id.
- Watchmode, Scrobbler, and Xtream HTTP share the same OkHttp connection pool as other app clients.
- Release builds use R8 minify and resource shrink (signed APK ~12.5 MB).
- Phone Live groups do not auto-preview on focus (avoids a second decoder while scrolling).
- Scrobbler start is delayed ~1.5 s so rapid channel changes do not spam Simkl/Trakt.
- Decoder preference changes rebuild spare ExoPlayers lazily, not on every settings save.

---

<div align="center">

## Remote

</div>

- Up and Down stay in the current list, or change channel in the player. On the group cards they move between rows.
- Left and Right move across the group cards. On the program guide, Right moves later in time and Left moves back, until the first slot, which returns to the channel list.
- Left during full-screen playback returns to the channel list, or steps live timeshift when the stream allows it. Right steps timeshift forward on live. Back from live playback returns to the channel and guide. Back from that screen returns to the group cards.
- OK or Enter activates the focused control. OK on a group card opens it. OK on a program plays it. OK on a New poster opens its description. OK on a queued title plays it or opens its episodes. In the player, OK/center shows play/pause and progress for 3 seconds (again toggles pause); Info opens the full menu.
- Hold OK on live TV for favorites, move up, move down, record, and Remind me. That is one menu. Remind me can be 15, 30, 45, or 60 minutes before the program. The card offers Ignore, a 5-minute snooze, or Tune to channel. The Reminders chip at the top of Live TV lists those reminders, and Edit or Delete changes one. Hold OK on a movie or a show for favorites, record, Add to Queue, and **Ratings & reviews**. Move up and move down are not on that menu. Hold OK on a season chip to check or clear that season. Hold OK on an episode to check or clear that episode. The Parental chip on Live TV, and Parental controls on EPG edits, list visible Live TV groups as multi-select cards. Select groups, then Set PIN (on-screen numeric pad, auto-closes at four digits) or Remove lock. Hold OK on a locked group card, before the group is open, asks for the unlock PIN.
- On the channel list or the program guide, number keys open a Channel dial. A short pause, or OK, jumps to that row. Back clears the digits first.
- Back steps one screen, including out of full-screen playback, out of a show’s seasons and episodes, out of a New description, and out of the next-episode countdown.
- When an episode ends, Next episode counts down from 10. Skip intro jumps about one minute into the next episode.
- On an episode, Subtitles sits next to Record, and Audio sits beside them when the stream has an audio track. Subtitles lists tracks carried by the stream, then English subtitles from TVsubtitles.net and, when a key is set, OpenSubtitles. OK switches a track or adds the file and keeps your place. The picture keeps playing.
- The rename keyboard uses Sft, Spc, and DEL. Left and Right move through the text. Down moves to Save.

---

<div align="center">

## What is stored

</div>

On the TV, DataStore `olympus_iptv` holds the playlist (order included), the session, favorites and their revision, the Queue, the device id, lineup edits, the last live channel, watch history (up to 60 rows per playlist, including resume points), whether favorites setup is done, recordings plus SMB settings, the Picture mode (Fit, Fill, or Stretch), the preferred audio language (default `en-US`) plus per-channel audio picks, subtitle language memory, guide density, quiet hours, parental unlock minutes, multi-view, empty-group hide, video decoder preference, TMDB items limit, PiP gesture preference, and Scrobbler provider/tokens. Program reminders are kept in `reminders.json` under the app’s files. A reminder stores the channel and the program time, not the stream address. Admin → Web-UI → Remove playlist clears that account data and the SQLite library on this TV, keeps the device id, and returns to sign-in.

The library is SQLite `olympus-iptv.db` (schema version 8): categories, channels, a disk cache of guide rows, and watched episodes. Stream addresses stay in that database. They are not published to the dashboard.

Lineup edits cover hidden countries, groups, and channels, custom group and channel names, group and channel order, strip prefixes and suffixes, the bulk group find and replace text, the name-to-guide flag, Include International, and a revision.

The week’s premiere list is cached on the device for about a day. Title ratings (TMDB/Watchmode) also keep a short disk cache. API keys themselves are not stored in those caches — they are baked in at build time from `local.properties` (`tmdb.apiKey`, `watchmode.apiKey`, and the others below).

The SMB password, playlist password, and Scrobbler tokens stay on the device. Do not commit a copy of the DataStore file, `local.properties`, or a dashboard settings export that still has secrets.

---

<div align="center">

## Build and run

</div>

Build from this folder when you want the latest source on a device.

| | |
| --- | --- |
| Android Gradle Plugin | 9.5.1 |
| Kotlin | 2.2.10 |
| Gradle | 9.9.1 |
| SDK | minSdk 24, compileSdk and targetSdk 35 |
| JDK | Android Studio’s JBR |

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Optional keys go in **`local.properties`** at the project root (`clearcast-tuner/local.properties`). They are read by `app/build.gradle.kts` via `localProperty(...)` and copied into `BuildConfig` at compile time. They are **not** committed to the repository.

| Property in `local.properties` | What it enables | Where it lands in code |
| --- | --- | --- |
| `tmdb.apiKey` | VOD ratings, review snippets, YouTube trailer lookup | `BuildConfig.TMDB_API_KEY` → `TitleRatings` / `TmdbClient` |
| `watchmode.apiKey` | **New this week** + Watchmode score on ratings sheet | `BuildConfig.WATCHMODE_API_KEY` |
| `opensubtitles.apiKey` | OpenSubtitles source under player **Subtitles** | `BuildConfig.OPENSUBTITLES_API_KEY` |
| `sendgrid.apiKey` + `sendgrid.fromEmail` | Email after two wrong parental PINs | `BuildConfig.SENDGRID_*` |

### TMDB API key

1. Create a free account at [themoviedb.org](https://www.themoviedb.org/).
2. Open **[Account → Settings → API](https://www.themoviedb.org/settings/api)** (or follow [TMDB Getting Started](https://developer.themoviedb.org/docs/getting-started)).
3. Request a **Developer** API key, accept the terms, and copy the **API Key** (v3). For **Application URL**, TMDB requires a non-empty URL even for personal apps that are not publicly hosted — use your GitHub profile or repo (e.g. `https://github.com/your-username`), not a LAN/dashboard address.
4. Add it to `local.properties`:

```properties
tmdb.apiKey=YOUR_TMDB_V3_API_KEY
```

5. Rebuild the app. `app/build.gradle.kts` maps that property to `buildConfigField("String", "TMDB_API_KEY", ...)`. Without the key, the browse score chip, ratings sheet, player caption scores, and **Trailer** stay empty or unavailable; the rest of the player still works. See [Where ratings appear](#where-ratings-appear).

The latest signed release is `C:\Users\chalu\Downloads\olympus-iptv-0.2.66.apk`.

Android Studio: use a 1920×1080 Google TV emulator. Apply Changes does not pick up new screens. Stop the app, then Debug. Click the emulator window before the arrow keys. The emulator is a different device from a Shield or an Onn box, so it does not have that box’s account, playlist, or favorites.

Libraries: Kotlin, AndroidX, ViewBinding, Media3 ExoPlayer (HLS and DASH), OkHttp, Gson, DataStore, Coil, Google Cast / MediaRouter, and SMBJ for the network share. The TV dashboard is a page in the APK, not a separate package.

---

<div align="center">

## Look of the app

</div>

The UI is warm charcoal and honey. From **0.2.48–0.2.50** the main shell uses a soft vertical charcoal gradient (`bg_screen`), honey focus rings, a short TV-only focus scale with dimmed unfocused group text, a raised Continue strip, raised phone bottom tabs and now-playing strip, skeleton rows on group open, rounded/letterboxed posters, a soft player overlay scrim, a honey **Connecting…** badge while buffering, and a player error card — still without blur or glass libraries.

| Role | Hex |
| --- | --- |
| Background | `#141210` (gradient lifts toward `#1B1814`) |
| Side menu | `#1B1814` |
| Raised surface | `#221F1A` |
| Panel | `#2C271F` |
| Stroke | `#433B31` |
| Text | `#F6F1E7` |
| Secondary text | `#C9BBA8` |
| Accent | `#E2A15A` |
| Accent pressed | `#B07A38` |
| Focus | `#F0C48A` |
| Danger | `#E07A72` |
| Chip | `#3A3126` |

The dashboard uses the same palette.

Admin shows a one-line parental summary (`N locked · unlock M min · quiet hours on/off`). Parental **Remove lock** requires entering a PIN that matches every selected lock. Optional PIN-fail email: put `sendgrid.apiKey` and `sendgrid.fromEmail` in `local.properties` (verified SendGrid sender). Two wrong unlock PINs trigger one alert, rate-limited to about 15 minutes. After setup, phones may show a one-time tip to use Web-UI Backup export.

Web Favorites: while the device catalog is still loading, auto-add cards and search stay disabled, a banner explains why, and the favorites board shows a waiting empty state; when ready, the header shows the favorite count.

Avoid for now: frosted-glass packs, full Compose rewrite, Lottie on every screen, dual ExoPlayer on phones, AutoTransition on touch.

---

<div align="center">

## Project layout

</div>

```text
clearcast-tuner/
  app/                         Android TV app, package com.olympus.iptv
    src/main/java/com/olympus/iptv/
      data/                    Models, favorites, watch history, lineup, recordings, DataStore
      playlist/                Xtream, M3U, XMLTV, SQLite catalog, EPG, Watchmode, dashboard client
      record/                  Schedule, capture, SMB, playback of recordings
      portal/                  Dashboard served by the TV while the app is open
      ui/                      Login, guide, New, search, player, lineup, countries, EPG edits, About, Admin
    src/main/assets/dashboard/ Dashboard page the TV serves
  portal/                      Older Node copy of the dashboard, for work on a PC
```

| Screen | Role |
| --- | --- |
| Login | Email, ZIP, M3U or Xtream, VOD, international |
| Guide | TV, Movies, Shows, Queue, New, Parental, side menu, program guide |
| New this week | Sunday–Saturday posters you can already play, in the same poster grid as Shows |
| Queue | Movies and series you held the center button on |
| Episodes | Seasons across the top, episodes underneath with descriptions and length. Check one episode or the whole season. |
| Parental | Visible Live TV groups as multi-select cards; Set PIN and Remove lock both use the on-screen numeric pad |
| Home | Account / playlist list with ▲▼ reorder |
| Admin | Refresh Options, Web-UI, EPG edits, and VPN, in two rows of two. The web dashboard has the same VPN card |
| About | Player-only notice, credits, coffee QR |
| Search | Find a channel, movie, or show and star it |
| Player | Play a channel, movie, episode, or recording; auto-hiding play/pause + progress; Cast, Trailer, PiP (phone), Audio/Subtitles, sleep |
| New / Just Added | Playable premieres only; series seasons/episodes; D-pad focus trapped until Back |
| EPG edits | Countries, groups, stripper, group renamer, Sanitize, Parental, images, name-to-guide, video decoder, TMDB items limit, PiP gestures, Scrobbler |
| Favorites setup | Auto-add, skipped when the catalog is already loaded |
| Record | View, schedule, or set the share |
| Reminders | Every set reminder, with Edit and Delete |

---

<div align="center">

## Version history

</div>

### 0.2.66

- New/Just Added opens from cache without waiting on background warm; playable re-checks skip when the week and library stamp are unchanged.
- Series detail and playable matching prefer the episode cache; Fresh posters share the same logo loader as the rest of the guide.
- Ratings sheet and trailers are leaner (shared TMDB client, score cache reuse, disk writes less often).
- Player seek updates slower while paused; Watchmode plot text prefers the week cache; browse paging appends without copying the full channel list.
- README documents where TMDB/Watchmode ratings appear (browse chip, long-press sheet, player caption).
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.66.apk` (versionCode 67). Install over the existing release — same signing key, account/playlist data kept.

### 0.2.65

- New/Just Added keeps D-pad focus on the page; only Back leaves (no focus loss at list/grid edges).
- Player play/pause + progress auto-hide after 3 seconds; center or tap shows them again (center again toggles pause). Full menu via Info / phone Menu.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.65.apk` (versionCode 66).

### 0.2.64

- Fixes crash on VOD play (`PatternSyntaxException` in title cleanup for TMDB ratings) that bounced New/Just Added episode play back to Live TV.
- New/Just Added only keeps premieres that have a playable stream (series season must exist in the library).
- Parental **Remove lock** on PIN pad, Parental screen, and dashboard.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.64.apk` (versionCode 65).

### 0.2.63

- Parental **Remove lock** on the group PIN pad, Parental controls, and web dashboard.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.63.apk` (versionCode 64).

### 0.2.62

- Signed release ships with TMDB v3 API key baked from `local.properties` so ratings, reviews, and trailers work out of the box.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.62.apk` (versionCode 63).

### 0.2.61

- Shared OkHttp pool for Watchmode / Scrobbler / Xtream; Xtream title-line cache (~6 h); release R8 minify + resource shrink (~12.5 MB APK).
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.61.apk` (versionCode 62).

### 0.2.57 – 0.2.60 (enhancement set)

- Playlist reorder on Home; **Top 10 watched** favorites group; video decoder modes on EPG edits.
- Phone PiP (menu, long-press Open in PiP, swipe up, leave-for-Home when PiP gestures on).
- Chromecast with IPTV format disclaimer; YouTube trailers via TMDB; multi-source ratings/reviews sheet (TMDB + Watchmode).
- TMDB items limit; Simkl / Trakt scrobble with one-provider mutex; ratings score-only on focus + disk/memory cache.
- Signed progression through `olympus-iptv-0.2.60.apk`; superseded by **0.2.61**.

### 0.2.56

- Mega Live groups (250+, e.g. 24/7 Shows) keep the side-by-side guide filled with hour increments of each channel name; provider EPG is not fetched for those folders.
- Channel column for those groups still pages **20** at a time with prefetch near the end so you can scroll and select.
- Connecting clears on first video frame / READY / playing; live rebuffers no longer keep the badge over a working stream.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.56.apk` (versionCode 57).

### 0.2.55

- Restores side-by-side EPG for normal Live groups (0.2.54 treated missing counts as “huge” and hid the guide).
- Opened groups use `readGroupPage` (direct category paging); mega-folders still load 20 at a time with prefetch near the end.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.55.apk` (versionCode 56). Install over the existing release — same signing key, account/playlist data kept.

### 0.2.54

- Huge Live groups page **20** channels at a time; the next page loads when you reach about 5 rows from the end (e.g. channel 15 of 20).
- Opening a concrete Live group lists that group’s channels (no Include International wipe that left “No channels” under a 1102 count). Empty UI waits until paging is truly exhausted.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.54.apk` (versionCode 55).

### 0.2.53

- Untagged / blank-country Live channels stay visible when Include International is off (fixes empty 24/7 folders that still showed a count on the card). Covered by `LineupApplierCountryTest` (including a 1102-row untagged folder case).
- CHANNELS mode is list-only on TV (no leftover EPG pane); unknown group size opens as a list until counted.
- Connecting badge clears when playback is READY or playing; warm/spare handoff syncs the badge; short live rebuffers no longer keep Connecting on screen.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.53.apk` (versionCode 54). Install over the existing copy (TV or phone).

### 0.2.52

- Large Live groups (250+ channels) open as a channel list, not the side-by-side EPG; page size drops to 60 and empty filtered pages are capped so mega-folders (e.g. provider 24/7 Shows) stay responsive.
- Live HTTP 401/403 (and recover after 3 tries) hard-stop with the playback error card — no endless reconnect spam.
- Groups stay provider-split by category id; a single 1100+ “24/7 Shows” card means the provider sent one category, not an app name-merge.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.52.apk` (versionCode 53).

### 0.2.51

- Speed: phone Live pages of 100 (TV stays 200); DiffUtil on group filter; Coil logo decode clamped to row size.
- UX: search recent queries; phone “Back to group” chip; Connecting badge wait/poor-network copy; haptics on phone zap and error Retry/Next; web Favorites Undo after remove.
- Product: Admin quiet-hours toggle + schedule presets; Backup export shortcut; Parental chip shows lock count; offline hint when catalog refresh fails.
- Signed APK: `C:\Users\chalu\Downloads\olympus-iptv-0.2.51.apk` (versionCode 52).

### 0.2.50

- Phone: now-playing resume strip; Live channel “now” line; groups typeahead filter; empty Favorites/Continue CTAs; backup/export tip once after Admin open.
- Player: **Playback stopped** card with Retry / Next / close (replaces toast-only failure).
- Perf: Coil `dispose` on recycle; phone EPG prefetch deferred ~220 ms; drop in-memory channel pages over ~400 rows when returning to groups.
- Parental: Remove lock requires a matching PIN for every selected lock; Admin parental line includes quiet hours.
- Web Favorites: catalog-loading banner, disabled search/auto-add until groups arrive, favorite count in the header, clearer empty states.
- Focus fixes for player error buttons, empty CTA (touch-only), and phone login M3U focus.

### 0.2.49

- Player honey **Connecting…** badge while buffering; Movies/Shows posters use rounded corners and letterbox (`fitCenter`).
- Group open shows skeleton rows; TV dims unfocused group text; phone long-press uses a bottom sheet; scroll position remembered per group and restored after player.
- Admin parental summary line; optional SendGrid email after two wrong unlock PINs (`sendgrid.apiKey` / `sendgrid.fromEmail`, 15-minute cooldown).
- Shipped inside the 0.2.50 signed APK (no separate 0.2.49 APK).

### 0.2.48

- Visual polish: soft charcoal screen gradient; stronger honey focus rings; TV focus scale on group/channel/Continue cards; raised Continue strip and bottom tabs; soft player overlay scrim (no blur library).
- Phone: pull-to-refresh on groups; Search in the top bar and Groups header; light haptics on Play, PIN set/unlock, and wrong PIN.

### 0.2.47

- Phone Live: opening a group uses a full-width channel list (CHANNELS mode) so taps play; the hidden EPG pane no longer steals layout weight and leaves a black screen.
- Phone top bar: **Admin** and **About** (Back returns to the current section).
- Missing stream URL shows a toast and closes the player instead of a silent black screen.

### 0.2.46

- Phone guide: bottom Live / Movies / Shows tabs, single-column list groups (taller rows, no focus-select), no AutoTransition, no side-by-side EPG pane; Movies/Shows posters use one column.
- Phone cold start: skips dashboard and Watchmode warm (New / web still load them); delayed image cleanup only; smaller Coil memory/disk caches; lighter logo and EPG prefetch while scrolling.
- Phone player: one ExoPlayer — no multi-view and no next/prev pre-buffer; portrait dock and sleep timer unchanged.
- TV remotes, dual-player zap, Continue focus, Skip intro memory, and catalog-stamp warm skip are unchanged.

### 0.2.45

- Continue watching strip is on the remote focus path; OK/Play resumes; hold OK opens Continue/Recent. Movies/Shows land focus on the strip when it is visible.
- Remembers the last Live / Movies / Shows section across opens.
- Skip intro is remembered per series and applied on the next episode / auto-play.
- Unit tests cover Continue focus rules, resume thresholds, and parental unlock.

### 0.2.44

- Live zap pre-buffers the next and previous channel; group open paints the first page without a blank “updating” flash; return from player keeps the channel row.
- Player **Sleep** timer (30 / 60 / 90 / off); reconnect failures show a plain provider-vs-app toast; Picture (aspect) stays in the menu.
- Search typeahead starts after the first character; Queue chip and Continue/Recent cards show counts; **PIN set** toast after locking.
- Phone: larger chips, touch scroll on Episodes / Queue / Search / Admin, remembered rotation lock from Sleep menu.
- Cold start skips dashboard/Watchmode warm when the catalog stamp is unchanged.

### 0.2.43

- Locked group unlock: **Enter PIN** with on-screen 0–9 pad (TV focus + phone tap). Wrong PIN → **Incorrect PIN** modal; OK closes all PIN UI and returns to groups.
- Phone guide: top Back/title, Live/Movies/Shows chips, hidden TV rail, two-column group cards; Back on Fresh, Favorites setup, opened groups, and PIN.
- Portrait player dock (scrubber + Back / Play-Pause / Menu) and a one-time “tap for controls” tip.
- Continue watching strip above group cards; cold start defers dashboard warm and Watchmode after Favorites/groups paint.
- Web Favorites: catalog-still-loading banner and poll until groups arrive.

### 0.2.42

- One APK for Android TV and phones/tablets: detects Leanback/TV vs touch, pads UI for system navigation bars, immersive player with on-screen Back, tap to toggle controls, and unlocked orientation on phones.
- Favorites setup scrolls by finger; Browse / Fresh / Parental clear the system bar on phones.
- Parental Set PIN keeps focus inside the pad until Cancel or four digits.
- Web setup wording uses “device”; after save the dashboard opens so favorites and filters can finish in the browser and sync to the player.

### 0.2.41

- First open focuses the Live TV Favorites group card instead of the left rail.
- New / Just Added: thicker focus rings on title cards and episode rows; grid focus lands on a real card and returns to the same card after detail.

### 0.2.40

- Player writes stream start, spooling/rebuffers, HTTP load failures, and playback errors to the Error log, with a sanitized URL and a provider-vs-app cause hint.

### 0.2.39

- New this week series navigation: clearer season/episode focus rings, season changes no longer freeze the remote, and Play starts the selected episode in that season’s queue.

### 0.2.38

- New this week series detail stays on one screen: poster and description, seasons horizontal under that, then episodes with thumbnail, description, length, and Play.

### 0.2.37

- Parental, Fresh, Episodes, Watchmode, and catalog paths tightened for speed and crash safety (OOM cleanup, destroyed-activity checks, capped premiere lookups, batch PIN saves).
- Hot-path comments stripped from those screens.

### 0.2.36

- Parental controls list only visible Live TV groups as multi-select cards. Set PIN uses an on-screen numeric keypad and closes after four digits.
- New this week matches shorter or longer catalog titles against Watchmode names (shared title heads).
- Opening a series keeps seasons horizontal across the top and episodes vertical underneath with description and length.

### 0.2.35

- EPG titles that start before the visible window stay readable; narrow slices grow the row and wrap the name.
- Fixes lag and freezes from 0.2.34 (safe EPG cache, bounded catalog ingest, no next-channel decoder preload, lighter scroll work).
- Returning accounts paint Live TV groups as soon as the saved names are ready; dashboard warm-up and “New” prep wait until after that.
- Groups header shows `MM/DD/YYYY  hh:mm AM/PM` instead of the favorites quick-zap channel-name strip.
- Stops loading the full channel catalog into memory on launch to publish search data (that path could OOM ~192MB heaps). Dashboard search uses SQLite. `largeHeap` is on; Coil and SQLite caches are smaller.
- Web Error log shows newest entries first by date and time.

### 0.2.34

- Quiet hours on Reminders, favorites quick-zap strip, subtitle language memory, guide density, and parental unlock time.
- Catch-up seek bar, live timeshift with Go live, multi-view, recording storage gauge, and backup import preview.
- Guide, player, DataStore, catalog ingest, and dashboard list speed-ups from the recent optimization passes.

### 0.2.33

- Choosing Live TV, Movies, or Shows moves focus to the top-left group card and keeps trying until that card has it.
- Favorites setup from the Live TV chip does not show Continue. Continue stays on the first-time setup. Back from that chip returns focus to Favorites setup.

### 0.2.32

- The guide opens again after 0.2.31 failed to start. Country name matching uses a split pattern Android accepts.

### 0.2.31

- Show/Hide Countries counts live channels. A group is matched by its name, the country stored on its channels, its group title, or the start of a channel name when the group still has none. That summary is saved until the library changes. Checking a country shows that country’s groups and channels.
- Choosing Live TV, Movies, or Shows moves focus to the top-left group card. Choosing the same section again focuses that card.
- Back during live playback returns to the channel and guide for that group. Back from there returns to the group cards.

### 0.2.30

- Hold OK on a title is one menu. Add to Queue is on that menu. Movies and Shows do not offer Move up or Move down.
- Parental controls are the Parental chip at the top of Live TV and the Parental controls card on EPG edits. OK on a group sets or clears the 4-digit PIN, and the group card shows a lock.
- The country number is the live channels for that country, from the country stored on the channel or in the group name. Checking the country shows those groups and their channels.
- Opening a group leaves the group card. Live TV lands on the program that is on now for the first channel. Movies and Shows land on the first title.
- Back from New this week leaves that screen. Back from a title on that screen returns to the grid.
- Sanitize Group & Channel Names is its own page. Four lists show what will be kept and what will be stripped. Cleanup flashes Cleaning… and then returns to the EPG edits cards.

### 0.2.29

- The country number is the live channels in that country’s groups. A country with no groups still shows 0. Checking it is what adds those channels to the guide. Checking a country updates that card.
- Each web page Save button is the gold control that sends that page. Web edits no longer send themselves. Leaving a page with unsaved changes shows a notice. OK continues.
- Admin, Error log, reads the log in a window that scrolls and resizes. A− and A+ change the text size, and Download saves the file that is open.
- Checking or hiding one group or one channel updates that row. Search, rename, and drag still redraw the list.
- Opening a device reads the saved lineup, favorites, and recordings together.
- A group you have not reordered loads its first 400 channels. A reordered group still reads the wider list so that order stays intact.

### 0.2.28

- A web edit to countries, groups, channels, a group password, a strip rule, or a favorite reaches the open guide as soon as it is sent. Those edits send themselves about half a second after the change. The 60-minute refresh is still only the provider catalog.
- OK while a program is playing opens Audio, Subtitles, and Record along the bottom. Each one opens that control.
- Opening a TV group leaves the group cards and puts the remote on the program that is on now for the first channel.
- A group with a password shows a lock in the top right of its card. Hold OK on the card to set or clear that password.

### 0.2.27

- Opening a device on the web reuses the saved group summary. Each group’s country is stored with that summary, and the country list is built once.
- OK on a TV group moves focus to the program that is on now for the first channel.
- Back from About or Admin returns focus to that item on the left rail.
- New posters show the full artwork from Movies or Shows, instead of a cropped strip.
- VPN providers are large cards, in the same style as the Admin cards.
- The web sign-in page shows the address and the current log beside the lineup card. Web Admin uses the same large cards as the TV, with a status on each card.
- The country list includes every country the app knows, even when Include International is off.
- The web groups list follows the TV. A live group with no country stays off both while Include International is off.
- The Live TV groups screen has Favorites setup, Queue, New / Just Added, and Reminders across the top. Settings and the old Favorites setup rail item are gone. The left rail is Search, Live TV, Movies, Shows, Recordings, Admin, and About.
- A group can take a 4-digit password on the TV and on the web. The channel list stays closed until that password is correct.

### 0.2.26

- Opening the app reads the saved account once. A TV that already has an account opens on the settings rail, and the group cards fill in when the group names are ready. The login screen is created only when no account is stored.

### 0.2.25

- A guide that is already open applies a hide, a rename, or a strip as soon as the phone saves it.
- Hold OK on live TV includes Remind me. Choose 15, 30, 45, or 60 minutes before the program. A card then shows the channel number, the show, and the start time, with Ignore, Remind me in 5 minutes, and Tune to channel. Reminders on the left rail, and the dashboard Reminders tab, list those reminders. Edit changes the minutes. Delete removes one.
- On the channel list or the program guide, the number keys show a Channel dial. A short pause, or OK, jumps to that channel. A number that is not in the open list is shown briefly.
- VPN can store a backup city. If the first city does not answer, the backup is tried. After a successful connection, a drop keeps the tunnel up and switches to the other city. The dashboard stays on the home network. The phone VPN page has the same backup control.
- The player caption has Audio for a language carried by the stream, and Subtitles includes a subtitle track carried by the stream as well as the English files it already searches for. The picture keeps playing.
- EPG edits on the TV includes Sanitize Group & Channel Names again. It adds the sports and US labels and removes `USA |` from the stored group names.
- Bulk Group Renamer on the TV rewrites the stored group name when you apply it.
- New uses the same four-across poster cards as Shows, and the list scrolls. The next row of posters is prepared before you reach it.
- A show opens from the saved episode list, then refreshes that list in the background. Shows already on screen save that list before you select one.
- Continue is the first group on Movies and Shows for a movie or episode stopped in the middle. It shows the minute you reached.
- When an episode ends, the player counts down from 10 and then plays the next episode, including the first episode of the next season. Skip intro jumps about one minute into that episode.
- Episodes have Subtitles next to Record. TVsubtitles.net is searched for that show. OpenSubtitles is searched when `opensubtitles.apiKey` is in `local.properties`. OK adds the file without starting the episode over.
- Sanitize and Bulk Group Renamer read the names first, then write only the names that change.
- TV, Movies, and Shows open on group cards. OK opens the channel list, and TV keeps the hour-by-hour guide beside it. Left in full-screen playback returns to that channel list, and Back there returns to the cards. The left rail ends at its longest label, and the title inside the app is Olympus IPTV on one line.
- A group’s channels load when its card opens, not while you move across the cards. The channel counts stay cached until the library changes.
- The app opens on two panes, the settings rail and the group cards, three cards per row. It does not start the last channel, and the channel list stays hidden until a card is opened.
- Opening a TV group lands on the program that is on now for the first channel. Movies and Shows land on the first title. The other groups are not shown across the top of that screen.
- The guide clock, the half hours, and the show marked as on now follow the TV’s Date & time setting and its time zone. Changing that setting moves the guide with it.
- Admin is two rows of two: Refresh Options, Web-UI, EPG edits, and VPN. The web dashboard Admin page has the same VPN card, so the username, password, or WireGuard profile can be entered from a phone. PIA, Proton, Surfshark, and Nord ask for the account username and password, then a city, then Connect. A backup city is used when the first city does not answer. WireGuard asks for a profile. The last city stays at the top, and typing filters the list. The Admin card and the guide show the connected city. Connect sends this app’s internet through that tunnel. Addresses on the home network, including the dashboard, stay on the LAN. If the server drops after a successful connection, the tunnel stays up and tries the other city, and new streams wait until it answers. Stream packets are encrypted in buffers the tunnel keeps, and a handshake refresh keeps the stream on the current connection until the server answers. The first connection has to be allowed on the TV.

### 0.2.24

- A show opens on a row of season posters. Selecting a season lists that season’s episodes with the description underneath.
- Hold the center button on an episode to mark or clear that episode. Mark this season watched still covers the whole season.
- An episode title is `S02-E01 - Episode Name`. The series name is not repeated on the row.

### 0.2.23

- The left rail no longer lists hidden countries, channels, and groups, or the Home Dashboard address. That address stays on the login screen and under Admin, then Web-UI. Web-UI also has Remove playlist for a full local start over without uninstalling.

### 0.2.22

- Bulk Group Renamer rewrites the stored group name. Save on that page is what makes the change stick. Back leaves it unchanged.
- Admin opens each item on its own page. The card list no longer keeps the description underneath.
- The web address checks the home network and moves to a free port when the usual one is already in use.

### 0.2.21

- New this week looks up premiere titles in the catalog instead of reading every movie and series. The week list and the dashboard warm start together.
- The saved week list stays in memory, so opening New does not read that file again.
- Group-name cleanup runs once per library. Later launches skip that scan.

### 0.2.20

- The web EPG edits page uses the same card grid as the TV. Sanitize Group & Channel Names applies the sports and US stream labels and removes `USA |` from group names.
- Countries, Groups, Channels, Favorites, and EPG edits each have their own Save. Record’s Save stores the network share.
- Favorites presets include DurictTV Streamer, YourFriendlyTV, and HoolaHoop Live TV.
- The login screen’s dashboard address opens **Set up this TV** when the set has no account. Saving there stores the account on the TV, and the login screen continues.
- New this week keeps poster shape, shows five across, and prepares the week in the background so the grid is ready when you open it.
- An episode is checked at 95 percent. Mark this season watched checks every episode in that season.
- Queue is on the left menu. Hold the center button on a movie or a series to add it, and hold it again in Queue to remove it.

### 0.2.19

- Opening a device on the web dashboard uses a saved summary of groups and countries, so the admin page comes up without scanning the whole library again.

### 0.2.18

- **New** on the left menu. Sunday through Saturday, three posters per row, six to a page. A poster is artwork and title. Selecting it shows the service, the day, and the description. Only titles already in Movies or TV Shows are listed.
- Back from full-screen playback returns to the live program, the movie, or the show that was started.
- Favorites setup from the menu skips the pulling splash when live, movies, and shows are already loaded.
- Guide rows and program times stay locked while you scroll.
- Empty guide time is filled. A 24/7 name-only channel shows hour blocks of that channel name.
- The live guide uses the three steps above. The corner preview is gone.
- Bulk strip tokens and bulk group find-and-replace text stay on the lineup and show on both the TV and the dashboard. The playback caption uses the lineup name.
- Episode lists, watched checks, a plot and year and length line, guide data cached on disk for an hour, and a quiet warm start of the focused live stream.

### 0.2.17

- The live guide starts on the TV menu. Right collapses that menu to icons and focuses the groups. Right again opens the full guide on the program that is on now for the first channel. Up and down move through the channels, right moves later in time, and left returns to the groups. Back steps one screen, and Back from full-screen playback returns to the guide.
- The preview window in the corner is gone. On the icon rail, only the icons show. The text at the bottom of the rail shows only while the far-left menu is open.
- Channel rows and program times scroll together. Empty time in a row is filled, and a channel that only repeats its own name shows that name in hour blocks.
- Bulk Channel Stripper lists each saved prefix and suffix on its own line, and leaving the screen does not drop that list.

**0.2.16.** After favorites setup is finished once, later launches go straight to the guide. Settings can open that screen again. A saved account stays on that device. Another device starts empty.

**0.2.15.** Clock line, date, `Group :`, channel number, logo, and name, rounded program titles, light focus, thin now line.

**0.2.14.** The channel column fits the longest name on screen, and the times start at the end of that name.

**0.2.13.** Channel rows line up with the program bars. The channel column sits further left.

**0.2.12.** One-line channel names, long-press menu, Recently watched above Favorites, shorter title-only program bars.

**0.2.11.** Logs rotate at 400 KB without dropping older files. The dashboard lists each file.

**0.2.10.** Continue no longer closes the app while the guide screen is still being created.

**0.2.9.** The dashboard answers on Android 11 and older. The same APK covers Shield, Onn, and Google TV Streamer. The address is HTTP on port 8765.

**0.2.8.** A dashboard startup failure no longer closes the app.

**0.2.7.** The dashboard moved off port 8443, which Cast already owns.

**0.2.6.** Crashes and errors are written to a downloadable log. Passwords are redacted.

**0.2.5.** Catalog percent in steps of 5. International off can skip foreign categories. The dashboard can save the first account. Continue stays on auto-add.

**0.2.4.** The dashboard listens on the TV’s Wi-Fi address.

**0.2.3.** Catalog progress, Include International, the coffee QR, and chunked catalog parsing.

**0.2.2.** The first catalog pull waits on a loading screen. A live source error reconnects.

**0.2.1.** The dashboard page and its host are inside the APK. This PC does not have to stay on.

---

<div align="center">

## Limits

</div>

- This is a player only. You must provide your own streams. The app does not ship channels, movies, shows, or playlist credentials.
- It is a beta. Feedback is greatly appreciated.
- **0.2.66** is the latest signed file in Downloads (`olympus-iptv-0.2.66.apk`, versionCode 67).
- Chromecast often cannot play provider IPTV/HLS the way the in-app player can; the Cast button warns before connecting.
- Ratings, trailers, and New this week need the matching keys in `local.properties` at build time (`tmdb.apiKey`, `watchmode.apiKey`).
- Live groups follow the provider’s category ids. Olympus does not split or merge folders by display name; a huge 24/7 card is usually one panel category.
- Live groups with 250+ channels keep the guide as hour blocks of each channel name (no provider EPG for that folder) and page channels 20 at a time.
- Opening a group shows that group’s channels; Include International does not empty an opened folder that already had a card count.
- **Connecting…** clears on first frame / ready / playing; a working live picture should not keep the badge up.
- The dashboard is only for the home network, and only while the app process is running.
- The TV has to be opened, and left able to run its recorder, for a dashboard schedule to record and for a Record-tab guide to load.
- One recording at a time. Series record is a queue, not several streams at once.
- Series record watches for new airings of the same title on that channel after the first schedule.
- A dashboard lineup edit is applied to a guide that is already open. A hide, a rename, or a strip shows up without leaving that guide.
- Resume applies to movies and episodes. Opening the app does not start the last live channel.
- New this week lists Sunday–Saturday premieres only when the title is already in Movies or Shows. Watchmode does not host the video. The feed itself returns at most 250 premieres for the week.
- Search matches a word, or the start of a word, anywhere in the name. A single character matches a word that starts with that character.
- Provider guide listings are kept. Empty time and name-only channels are what get filled in.
- Skip intro jumps one minute into the next episode. The provider does not send the start time of a theme.
- Subtitles downloaded for an episode are English. A subtitle track already inside the stream can be another language. TVsubtitles.net has to be reachable from the TV. OpenSubtitles is used only when the build has `opensubtitles.apiKey`. The provider does not have to send a subtitle track of its own.
