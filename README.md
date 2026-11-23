<div align="center">

# Olympus-IPTV

**Beta.** Feedback is greatly appreciated.

### For the latest and greatest, build your own APK from this code.

The latest signed file is **0.2.105** (versionCode 105): `C:\Users\chalu\Downloads\olympus-iptv-0.2.105.apk`. **One APK** installs on Android TV / set-top boxes and on phones and tablets. Install it over 0.2.104. The signing key is the same, so the saved account and playlist stay. Cloud sync uses `https://stream-me-service-api.getjohnathan.com`, which reads and writes the Olympus-IPTV PostgreSQL database.

**This is a player only. You must provide your own streams.**

Olympus-IPTV does not include channels, movies, shows, or stream addresses. It plays an M3U playlist or an Xtream Codes account you already have.

</div>

---

<div align="center">

## Contents

</div>

- [Start here](#start-here)
- [What's in 0.2.105](#whats-in-02105)
- [What's in 0.2.103](#whats-in-02103)
- [What's in 0.2.92](#whats-in-0292)
- [What's in 0.2.86](#whats-in-0286)
- [What's in 0.2.83](#whats-in-0283)
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

Olympus-IPTV is one APK for Android TV / set-top boxes and for phones and tablets. On TV it is built for a remote. On a phone it adapts for touch. A television that also has a touchscreen stays on the TV layout. While the app is open, it hosts a home dashboard so a phone or computer on the same Wi-Fi can edit the same lineup.

| | |
| --- | --- |
| Signed file | `C:\Users\chalu\Downloads\olympus-iptv-0.2.105.apk` |
| What that file is | versionName **0.2.105**, versionCode **105** |
| Source in this tree | versionName **0.2.105**, versionCode **105** |
| Cloud API | `https://stream-me-service-api.getjohnathan.com` |
| What to run | Install the signed 0.2.105 APK, or run this source from Android Studio |
| Install | Over the existing copy (TV or phone). Uninstalling wipes the saved account, playlist, and favorites. Downloader works with the same file. |
| Launcher name | Two centered lines: Olympus over IPTV |

**0.2.105** is the current signed release. Phones, tablets, and Android TV use this one APK, and both talk to `https://stream-me-service-api.getjohnathan.com`. That API reads and writes the Olympus-IPTV PostgreSQL tables (`accounts`, `devices`, `provider_logins`, `lineup_configs`, `favorites`, `recording_books`, `reminders`, and `watch_history`). Cloud setup and later cloud edits are described in [What's in 0.2.105](#whats-in-02105) and [What's in 0.2.103](#whats-in-02103). The channel-loading screen leaves once live channels are saved, and the guide spinner clears if that load fails. A frozen or stuttering live channel reloads itself, then switches to another feed of that same channel. The guide still paints the last group list immediately. Movies, shows, cloud sync, ratings, and the dashboard snapshot wait until that grid is up. The release copy in Downloads is the signed APK only. Movies and shows open the title page under [What's in 0.2.92](#whats-in-0292). It also restores cloud lineup push, the remote lineup revision, and recording sync, and keeps the live-games list, off-air hiding, and the shorter guide under [What's in 0.2.86](#whats-in-0286). Live Games Now skips 24/7 replay channels. Group counts use the stored event window and still leave out hidden channels. Two wrong parental PINs can email through Mailgun when that is configured. Sticks with a small heap still use the 16 MB image cache, RGB_565 posters, and a 10 second buffer. Larger boxes pre-warm only the next channel.

---

<div align="center">

## What's in 0.2.105

</div>

- The phone, tablet, and TV builds call `https://stream-me-service-api.getjohnathan.com`. There is no separate mobile API.
- Playlist login, lineup edits, favorites, recordings, reminders, and continue watching are stored in the Olympus-IPTV PostgreSQL database behind that hostname.
- When that database already has a catalog, live channels, movies, and shows load from `cloud_categories` and `cloud_channels` instead of calling the provider again.
- The guide prefetches the current program for the visible channels and shows the title on the row. Phone portrait still shows the progress bar and the next program.
- The home-network dashboard on the device is unchanged. Cloud setup and cloud edits use the hostname above.

---

<div align="center">

## What's in 0.2.103

</div>

- On the login screen, Down and the keyboard Next key move in order: email, server URL, username, password, ZIP, the two checkboxes, then Connect. Left and right stay on the field you are typing in. The window pans that field above the keyboard so the dashboard label does not cover it.
- Connect asks the provider to accept the URL, username, and password before it leaves this screen. If the provider does not answer within 20 seconds, or rejects the login, a popup says so and the form stays up. A good login opens auto-add favorites. The QR pairing step is gone.
- The cloud setup form stores the email, ZIP, host, username, and password against the device ID shown on the TV. The QR code and the copied address already include that ID, so the form is filled in when it is opened from the TV. Restart the cloud portal after deploying this server so that save is live.
- Every 10 seconds the app reads revision numbers from the cloud database. It rewrites the device only when a number is newer than the one it already applied. That covers the playlist login, lineup edits, favorites, recordings, reminders, and continue watching. The check runs before the device is set up, so a playlist saved on the cloud starts the channel list on the next pass. A live connection still applies a save as soon as the database has it.
- Remove playlist shows “Removing playlist…” and then returns to the login screen. Back from a Settings page returns to that Settings card. Back from the Settings card grid still returns to the left rail.

---

<div align="center">

## What's in 0.2.92

</div>

- Movies and shows open a title page before playback. The page shows the backdrop, poster, title, year, rating, network, content rating, genres, and synopsis when a TMDB key was built in.
- Play starts the movie or the next episode. Trailer opens the YouTube trailer when TMDB has one. The heart marks a favorite, the check marks it watched, and a series lists seasons with episode stills, air dates, and runtimes.
- Cast on that page uses the same chooser and format disclaimer as the player. Without a TMDB key the page still shows the poster, title, Play, favorite, and the episode list.

---

<div align="center">

## What's in 0.2.86

</div>

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

</div>

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

<div align="center">

## Phones and tablets

</div>

There is **no separate mobile APK**. Compact means a touch device whose smallest width is under 600dp. A tablet is touch and 600dp or wider. TV is Leanback or the television UI mode, even if the panel also has a touchscreen.

| On phones and tablets | What this source does |
| --- | --- |
| Guide chrome | Compact phones: top **Back**, title, **About**, **Search**, and **Cast**. **Admin** is not on that top bar. Bottom tabs are **Live**, **Movies**, **Shows**, **Library**, and **Admin**. The left TV rail stays hidden. Tablets keep the group list beside the channels. A wide TV uses a flexible group column, not the tablet layout’s fixed 260dp column |
| Columns | Category and poster grids follow the width: 1 column under 600dp, 2 from 600dp, 3 from 900dp. TV stays at 3 |
| Group filter | Typeahead under Groups. Portrait Live also shows group chips |
| Now playing | The resume strip above the groups stays hidden. The live channel you were watching stays marked in the list. Search can still show that stream |
| Live “now” | A portrait phone shows the current title, a gold progress bar, and the next title on the row. A landscape phone and a TV keep the hour-by-hour guide |
| Pull to refresh | Swipe down on the groups list or the channel list to refresh quietly |
| Haptics | Light feedback on Play, group open, PIN set / unlock / wrong PIN, pull to refresh, and the player gesture pill |
| Live play | A portrait phone opens the channel list. A landscape phone opens the hour guide |
| Playlist links | **Paste playlist URL** on the phone login screen. An `.m3u` or `.m3u8` link, or `olympusiptv://add?url=`, fills Add Playlist. The phone keyboard opens on search and URL fields. TV keeps the keyboard off until it is asked for |
| First run | The QR stays on the first login screen. Continue on a phone goes to favorites and does not open a second QR page. TV still gets the pairing step. Turning the phone does not cancel an open guide |
| Local channels | ZIP matching uses the four nearest markets, including Athens, Augusta, Macon, Savannah, and Columbus GA next to Atlanta, so 30677 and 30605 can find a Georgia market |
| Cellular | Automatic catalog refresh and host warm-up wait on a metered network. A manual refresh and the first connect still run. Guide prefetch is not blocked. Cellular video is about 2.5 Mbps, 6 Mbps when the link reports at least 15 Mbps, and unlimited at about 50 Mbps or on Wi-Fi. TV is not capped |
| Background sync | On a phone, a job every 6 hours refreshes the catalog on unmetered Wi-Fi when the battery is not low, including after reboot. Televisions do not schedule it. This is Android’s job scheduler. WorkManager is not in the offline build |
| Battery saver | On a phone or tablet, saver mode skips the poster glow crossfade and decodes large posters at 320px. TV ignores the system saver |
| Long-press | Bottom sheet for Favorite / Record / Remind / Queue / **Ratings & reviews** / **Open in PiP**. TV still uses the dialog menu |
| Player | Portrait dock; play/pause and the scrubber hide after 3 seconds; **Cast** on the dock; one ExoPlayer |
| Gestures | Left edge sets brightness, right edge sets volume, with a gold pill and a tick about every 10%. Double-tap seeks ±15 seconds when the title has a duration. A single tap shows or hides controls |
| Lock screen | The playback notification has channel down, play/pause, channel up, and a 30-minute sleep toggle. Bluetooth previous and next change the channel. Cast stays on its own chooser, with the format disclaimer |
| PiP | Open in PiP, swipe up on a title, or leave the player for Home when **PiP gestures** is on and video or radio is playing. Not used on TV |

---

<div align="center">

## Video walkthrough

</div>

<div align="center">

[![Olympus IPTV — Onn / Google TV setup and how-to](https://vumbnail.com/1248359692.jpg)](https://vimeo.com/1248359692)

</div>

It walks through enabling unknown sources on an Onn box, installing Downloader by AFTV, entering a download code (provided separately), signing in, waiting for the catalog, using Favorites setup / Queue / New / Parental, browsing the live guide, and the main remote actions (OK, Hold OK, Back). The video is the TV flow. Phone chrome in this source is newer than that recording.

---

<div align="center">

## What it does

</div>

- Plays live channels, movies, and series from an M3U or M3U8 URL, or from an Xtream host, username, and password.
- Runs from **one APK** on a Shield, Onn, Google TV Streamer, and other Android TVs, and on Android phones and tablets. Minimum is Android 7 (API 24). A release APK includes 32-bit and 64-bit ARM. Debug builds also include x86 so the emulator runs. Shield Experience 9.2.4 is Android 11. The number in parentheses on that Shield screen is NVIDIA’s build, not the Android version.
- Signs in with an email and a 5-digit US ZIP. The ZIP is only used to place local channels. Include VOD starts checked. Include International starts unchecked, so foreign groups stay out until you turn that on.
- Creates a device id shaped `OI-XXXX-XXXX-XXXX`. One email can have more than one device. Each device keeps its own lineup, favorites, catalog, and recordings.
- Pulls the provider catalog once per list (live, movies, shows), shows a percent in steps of 5, then the counts, then the auto-add cards. Later opens skip the favorites splash when the catalog is already on the device.
- Lets the first account be saved from the dashboard address on the login screen. The page shows **Set up this device** until an account exists. Opening the app again reads that saved account and opens the group screen as soon as the saved live groups are ready. The login screen is created only when no account is stored.
- On TV the rail is **Search, Live TV, Movies, Shows, My Library, Recordings, and Admin.** About and Favorites setup live under Admin. Live TV still has Queue, New / Just Added, Reminders, Parental, and Favorites setup chips, plus the date and 12-hour time. There is no favorite-channel name strip, no Settings item, and no Sign out.

---

<div align="center">

## The live guide

</div>

When an account is already saved, the app opens on the rail and the group cards. The channel list and the guide stay hidden until a card is opened. It does not start the last channel. The rail title is **Olympus IPTV** on one line. Above the group cards, Live TV shows the current date and time (`MM/DD/YYYY  hh:mm AM/PM`).

1. **TV, Movies, or Shows.** Group cards fill the space beside the rail. The number of cards in a row follows the screen: 3 on a TV, and fewer on a phone (see [Phones and tablets](#phones-and-tablets)). Live TV also has **Live Games Now** after For You. A card shows the group name and, once counted, how many channels or titles it holds. Live counts leave out hidden channels and event slots that are off air. Moving across the cards does not load channels.
2. **OK on a card**, or Right from a card on the right edge, opens that group. On TV, and on a landscape phone, a Live group under 250 channels opens the side-by-side EPG and lands on the program that is on now. A portrait phone opens the channel list, with now and next on each row. A Live group with **250 or more** channels still opens the guide, but fills it with **hour blocks of each channel name** (no provider EPG download). Channels page in **20** at a time. Movies and Shows land on the first title.
3. **Right** from a channel moves into the guide and later in time. **Left** from the program on now returns to the group cards.
4. **Back** from the channel list or the guide returns to the group cards. Back from the cards returns to the rail. Back on the rail does not quit the app.
5. **Left** during full-screen playback returns to the channel list, or steps live timeshift when the stream allows it. **Back** during live playback returns to the channel and guide.

Opening For You, Recently Watched, Favorites, or New re-anchors the guide to the current half hour and scrolls to the start of that window. Program titles stay on one line. A title that began before the visible window is padded so the words stay in the part you can see. The row does not grow, and the text does not wrap, when you scroll back inside a long program.

Hold OK on a live channel or a program for favorites, move up, move down, record, and **Remind me**. Movies and Shows use that same hold, without move up or move down, and add **Add to Queue**. Remind me asks for 15, 30, 45, or 60 minutes before that program starts. The card that appears offers Ignore, Remind me in 5 minutes, and Tune to channel.

On the channel list or the program guide, the number keys open a Channel dial. After a short pause, or OK, the guide jumps to that channel. Back clears the digits first.

Each channel row is one line: number, logo, and the lineup name. The row is the same height as the program bar beside it, and the two lists scroll together. The header shows a breadcrumb, the date and clock, then the group. The half-hour labels and a glowing **NOW** line use that clock. A synopsis dock shows the focused program’s title, time, optional `SxxExx`, plot, and **Record** / **Remind**. Program bars tint by kind and can show `[Live]`, `[New]`, `[Sports]`, or `[Movie]`.

**Now** in that header scrolls the timeline back to the current half hour. Up and Down follow clock time across channels. Empty stretches are filled with the channel name in hour blocks. Real program titles are left as the provider sent them. Mega Live folders (250+ channels) always use those hour-name blocks and do not download provider EPG for that folder.

OK on a past program plays catch-up when the provider marked that channel for archive or catch-up (Xtream `tv_archive`, or M3U `catchup` / `catchup-source` / `catchup-days`).

There is no auto-preview on phones. On TV, the focused live channel can warm quietly. After **Back** from live playback, that stream keeps running in the bottom-right guide corner with **LIVE • Return to Fullscreen**. Search can show the same stream in a muted dock.

On Live TV the first cards are **For You**, **Live Games Now**, then **Recently watched**. Movies and Shows still open with Recently watched. **Top 10 watched** appears when you have favorites and watch history. **Favorites** lists only the favorites for that section. The Continue watching strip above the groups is hidden, and Movies and Shows do not add a Continue group card. A movie or episode you left in the middle still resumes from its poster and from Recently watched. A spot under 15 seconds, or within 20 seconds of the end, starts from the beginning. **Guide density** on EPG edits switches Comfortable or Compact row height.

---

<div align="center">

## Movies, shows, and New

</div>

Movies are video-on-demand titles that are not part of a series. Shows are series. OK on a group card opens that group’s poster grid. OK on a poster opens the title page described in [What's in 0.2.92](#whats-in-0292).

Opening a show keeps seasons as a horizontal strip and that season’s episodes underneath. It does not start playing on its own. A season chip is checked when every episode in it has been watched. An episode title is the season and episode code plus the episode name, such as `S02-E01 - Episode Name`. An episode is checked when playback passes 95 percent. Hold the center button on an episode, or on a season chip, to check or clear it.

### Where ratings appear

With `tmdb.apiKey` built in (and `watchmode.apiKey` for Watchmode scores on the sheet):

| Place | What you see |
| --- | --- |
| Movies / Shows browse | After focus settles, a short score line (for example `TMDB 7.4`). No reviews while scrolling |
| Long-press → **Ratings & reviews** | TMDB and Watchmode scores, overview, up to three review snippets, plus **Trailer** and **Play** |
| Player caption | The same short score when it is already cached or fetched |

**TMDB items limit** on EPG edits is 5, 10, 20, or 40. Without the TMDB key, the chips stay empty. The player still works.

When an episode ends, the player counts down from 10 and then plays the next episode. **Skip intro** starts that episode about one minute in. The last episode of a show does not loop. Back during the countdown leaves the player.

### New this week

**New / Just Added** is a chip at the top of Live TV. It is a Sunday-through-Saturday list of US streaming premieres from Watchmode.

| | |
| --- | --- |
| Services | Netflix, Prime Video, Disney+, Hulu, Max, Apple TV+, Peacock, Paramount+ |
| What gets a poster | Only a premiere that matches a title in your Movies or Shows and has a playable stream |
| The grid | Posters in the same column count as the rest of the app. D-pad focus stays on this page. **Back** leaves |
| Select a poster | Opens artwork, service, day, and description. A series adds seasons and playable episodes. Hold the center button to add it to Queue |
| Refresh | Opening New uses the cached list. It does not wait on a warm. Playable checks re-run when the library stamp changes |
| Key | `watchmode.apiKey` in `local.properties`. Without it, the screen says the releases cannot load |

---

<div align="center">

## Names, groups, and countries

</div>

The guide shows the names you set. Live groups stay split by the provider’s category id. The app does not merge folders that share a display name. Bulk Group Renamer changes the label, not which channels are in the folder.

- **Bulk Channel Stripper.** Comma-separated prefixes and suffixes, such as `USA |, HD`. A plain word comes off the start or the end and does not eat a longer word (`HD` does not eat `FHD`).
- **Bulk Group Renamer.** Find and replace across group names. Capitalization is ignored. Save rewrites the stored group name.
- **Remove images.** Emoji and picture characters can be stripped from channel names and group names.
- A name you set for an id is kept when the provider later sends a different name for that same id.
- Countries lists every country the app knows. Include International left off keeps those countries available to turn on. It does not remove them from the list.

Show everything again clears hidden countries, groups, and channels. It does not clear custom names or strip rules.

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

On the dashboard, drag or use the arrows to reorder. Remove syncs immediately. Search stars append or remove one channel. They do not replace the list.

Auto-add can pull local major networks, major sports networks, teams you pick, and preset lineups. The presets include **DurictTV Streamer**, **YourFriendlyTV**, and **HoolaHoop Live TV**. The ZIP picks the local stations from the four nearest markets. A channel that is not in your library is skipped. Continue stays on first-time setup only.

### Queue

**Queue** is a chip at the top of Live TV, and a pill inside My Library. Hold the center button on a movie or a series and choose **Add to Queue**. OK on a queued movie plays it and can count down to the next queued movie. OK on a queued series opens its episodes.

---

<div align="center">

## Search

</div>

Search looks through TV, Movies, and Shows. Results update as you type, with a 150 ms debounce, using FTS when the device has it and a punctuation-insensitive retry when the typed prefix misses. A **Mic** button starts system voice search when the device has it. After you leave live playback, Search can show that stream in a muted dock.

---

<div align="center">

## The player

</div>

The player is Media3 ExoPlayer. Logos use Coil. Catalog and dashboard calls use OkHttp. The player does not send your streams through that client.

On a phone, tap the picture to show or hide the transport chrome. The left edge sets brightness and the right edge sets volume. Double-tap jumps 15 seconds when the title has a length. Chrome hides after 3 seconds. Portrait can show a dock with the scrubber, Back, Play/Pause, Menu, and Cast. Leaving the player for Home enters picture-in-picture when PiP gestures are on and video or radio is playing. A lock-screen notification offers channel down, play/pause, channel up, and a 30-minute sleep toggle.

The Audio list has **Night mode**, **Screen off, audio only**, and **Audio earlier / Audio later**. The caption shows the lineup name, the current program, and the next program. Channel Up/Down paints a zap line from the local guide immediately. **Info** on TV, or **Menu** on a phone, opens Audio, Subtitles, Record, Picture, **Last**, **Go live**, **Multi-view**, **Sleep**, **Cast**, **Trailer**, and **PiP**. Audio prefers English (US) when that track exists and remembers the last language for that channel. Picture is Fit, Fill, or Stretch. **Sleep** is Off, 30, 60, or 90 minutes. **Cast** shows a disclaimer first: many IPTV HLS links fail on the default receiver. **Multi-view** is TV only. **Last** returns to the previous live channel.

With **Scrobbler** set, Simkl or Trakt gets a start after about 1.5 seconds and a stop when you leave. Only one provider is active. The video decoder chosen on EPG edits is applied when the player opens.

A live channel that drops reconnects with a 1s, 2s, then 4s wait. The next stall rebuilds the player on the software decoder. **HTTP 401, 403, or 458** stops and offers Check account. A timeout or 502 can offer the backup. **Connecting…** clears on the first frame. In the last 25 seconds of a movie or episode, Up Next counts down.

M3U user-agent and referrer options on a channel are sent with that stream. On a phone, a metered network caps video as described above. Wi-Fi and TV are not capped.

---

<div align="center">

## Recording

</div>

A new recording starts two minutes before the program. You choose whether it stops 5, 15, or 30 minutes after the guide end. Older scheduled items keep the one-minute pad they were saved with. A finished recording remembers where you stopped, and a still is shown on the row when the file can produce one.

Start one from the long-press menu, from Record on the player, from Schedule a recording, or from the dashboard Record tab. Record upcoming episodes queues later airings of the same title on that channel, up to 24. One recording runs at a time. Files can stay on the device or go to an SMB share. The dashboard can download or delete a finished file while the app is open.

HLS streams are saved by following the playlist. A direct stream is copied until the end time. The recorder is a foreground service, so a scheduled recording can start when you are not on that screen. The TV has to be on and able to run that service.

---

<div align="center">

## Reminders

</div>

**Reminders** is a chip at the top of Live TV and a pill inside My Library. Each card shows the show, the channel, the start time, and how many minutes before it. Edit offers 15, 30, 45, or 60 minutes. **Quiet hours** (23:00–7:00 by default) holds due reminders until quiet hours end. The card that appears offers Ignore, Remind me in 5 minutes, or Tune to channel.

---

<div align="center">

## The home dashboard

</div>

While the app process is running it serves the dashboard on the home network. It starts on port **8765**. If that port is taken, it tries 8766 through 8776. Admin → Web-UI shows the address. Port 8443 stays with Cast. The address is `http://` plus the device’s own Wi-Fi address. HTML and JSON are gzip-compressed when the browser asks. The page itself is stored pre-compressed as `dashboard/portal-page.pack` so the asset packer does not treat it as a second `index.html`. `portal/data/store.json` is never packaged. Login and the header offer **Copy TV URL**. The server stops when the app process stops. Clients that are not on the home network are refused.

The same page on `https://stream-me-service-api.getjohnathan.com` saves the playlist login for the device ID in the address. That site reads and writes the Olympus-IPTV PostgreSQL database. The app checks those revision numbers every 10 seconds and applies a section only when it changed. Sign in with the email and the device id from Web-UI. Stream addresses are not sent to the page. Backup export downloads one JSON file of favorites, renames, parental PINs, strip rules, the rest of the lineup, and per-channel audio offsets. Import previews, then asks before it writes that file back.

The web Admin cards match the TV, and add Active Streams and the error log. Active Streams shows the provider’s active and max connection count. Addresses are listed when the login response includes them. Otherwise the page lists streams this device still has open. Logs rotate at 400 KB. Passwords and stream credentials are not written into the log.

A VPN (PIA, Proton, Surfshark, Nord, or WireGuard) sends this app’s traffic through the tunnel. Home-network addresses, including the dashboard, stay on the LAN. If the first city does not answer, the backup city is tried. New streams wait until one answers.

`portal/` is the cloud server. The device also serves `app/src/main/assets/dashboard/` on the home network. A playlist typed on the cloud is stored for that device ID and pulled by the app. A playlist typed on the home-network page is saved on the device directly.

---

<div align="center">

## Admin and About

</div>

| Screen | What it does |
| --- | --- |
| Refresh Options | Checks the provider and saves channels that are not already on this device. The interval can be 30, 60, 120, 360, 720, or 1440 minutes. The default is 60. |
| Web-UI | Email, device id, and dashboard address. Remove playlist shows a spinner, then clears this device’s account, library, favorites, lineup edits, recordings list, watch history, queue, and reminders, keeps the device id, and opens sign-in. |
| EPG edits | Countries, groups and channels, show everything again, the bulk stripper, the bulk group renamer, Sanitize, image removal, and the switch that fills an empty guide from channel names. Also Parental controls, **Hide empty groups** (on by default), **Guide density**, **Parental unlock time** (5–60 minutes), **Video decoder**, **TMDB items limit**, **PiP gestures**, and **Scrobbler**. |
| VPN | PIA, Proton, Surfshark, Nord, and WireGuard, two cards across. Nord can use its access token in the password box. WireGuard takes a profile. Backup city is tried when the first city drops. |
| Favorites setup | Opens auto-add again. When the catalog is already loaded it skips the pulling-channels splash. |
| About | Player-only notice, credit @bobgoblin, and https://buymeacoffee.com/avocadopeaches. A QR opens that page. |
| Setup | Under the Admin grid. Provider status (active and max connections, expiration, last sync), quiet hours, Backup export, and Magic Cleanup. |
| Active Streams | Web Admin only. The provider’s active and max connection count, plus addresses when the login includes them. |

Parental controls list the Live TV groups that are visible on the guide. Set PIN uses an on-screen pad and closes at four digits. After a correct PIN, the group stays unlocked for the parental unlock time (default 15 minutes). Two wrong PINs can email you when `mailgun.apiKey`, `mailgun.domain`, and `mailgun.fromEmail` are built in, at most about once every 15 minutes. Until those three values are set, no email is sent.

---

<div align="center">

## Remote

</div>

- Up and Down stay in the current list, or change channel in the player.
- Left and Right move across the group cards. On the program guide, Right moves later and Left moves back. Left from the program on now returns to the group cards. Up and Down on the cards stay in the grid.
- OK on a group card opens it. OK on a program plays it. In the player, OK shows play/pause and progress for 3 seconds. Info opens the full menu.
- Hold OK on live TV for favorites, move up, move down, record, and Remind me. Hold OK on a movie or show for favorites, record, Add to Queue, and Ratings & reviews.
- On the channel list or the program guide, number keys open the Channel dial.
- Back steps one screen. Inside Settings, Back returns to the Settings card you opened. Back on the rail does not quit the app.
- Hold Up or Down for 1.2 seconds on a long channel list, or press Right past a movie or show column, to open the A–Z jump.

---

<div align="center">

## What is stored

</div>

DataStore `olympus_iptv` holds the playlist, the session, favorites, the Queue, the device id, lineup edits, the last live channel, watch history (up to 60 rows per playlist), recordings plus SMB settings, picture mode, audio and subtitle language, guide density, quiet hours, parental unlock minutes, multi-view, the video decoder, the TMDB limit, PiP gestures, Scrobbler tokens, and per-channel lip-sync offsets. Reminders are in `reminders.json`. Admin → Web-UI → Remove playlist clears that account data and the SQLite library, keeps the device id, and returns to sign-in.

The library is SQLite `olympus-iptv.db` (schema version 16): categories, channels, a disk cache of guide rows, watched episodes, and event-window columns used to hide off-air slots. Stream addresses stay in that database. They are not published to the dashboard. Once a day, guide rows older than 24 hours are deleted, then the database is vacuumed and the WAL is truncated.

The SMB password, playlist password, and Scrobbler tokens stay on the device. Do not commit `local.properties`, the DataStore file, or a dashboard export that still has secrets.

---

<div align="center">

## Build and run

</div>

| | |
| --- | --- |
| Android Gradle Plugin | 9.5.2 |
| Kotlin | 2.2.10 |
| Gradle | 9.9.1 |
| SDK | minSdk 24, compileSdk 37, targetSdk 35 |
| JDK | Android Studio’s JBR |

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Optional keys go in `clearcast-tuner/local.properties`. They are copied into `BuildConfig` and are not committed.

| Property | What it enables |
| --- | --- |
| `tmdb.apiKey` | VOD ratings, review snippets, YouTube trailer lookup |
| `watchmode.apiKey` | New this week, and the Watchmode score on the ratings sheet |
| `opensubtitles.apiKey` | OpenSubtitles under player Subtitles |
| `mailgun.apiKey` + `mailgun.domain` + `mailgun.fromEmail` | Email after two wrong parental PINs |

Release builds use R8, resource shrinking, and optimized resource shrinking. They keep `armeabi-v7a` and `arm64-v8a` only, and English resources only. A baseline profile covers the guide and the player. Gradle is set to work offline. The latest signed release is `C:\Users\chalu\Downloads\olympus-iptv-0.2.105.apk`. The cloud address is `portal.cloudUrl` in `local.properties`, and the build falls back to `https://stream-me-service-api.getjohnathan.com` when that line is empty.

Libraries: Kotlin, AndroidX, ViewBinding, Media3 ExoPlayer (HLS and DASH), OkHttp, Gson, DataStore, Coil, profileinstaller, Google Cast / MediaRouter, and SMBJ. WorkManager is not a dependency. The dashboard is a page in the APK.

---

<div align="center">

## Look of the app

</div>

The UI is warm charcoal and honey.

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

The dashboard uses the same palette. TV focus scales a card slightly. Compact phones skip that column animation.

---

<div align="center">

## Project layout

</div>

```text
clearcast-tuner/
  app/                         Android app, package com.olympus.iptv
    src/main/java/com/olympus/iptv/
      data/                    Models, favorites, watch history, lineup, recordings
      playlist/                Xtream, M3U, XMLTV, SQLite catalog, EPG
      record/                  Schedule, capture, SMB
      portal/                  Dashboard served while the app is open
      ui/                      Login, guide, player, search, admin tools
    src/main/assets/dashboard/ Dashboard page (index.html). The gzipped copy is generated as portal-page.pack
  portal/                      Older Node copy of the dashboard, for work on a PC
```

| Screen | Role |
| --- | --- |
| Login | Email, ZIP, M3U or Xtream, VOD, international. Phone: paste URL. TV: pairing QR step |
| Guide | Live TV, Movies, Shows, My Library, Recordings, Admin |
| My Library | Queue, New, and Reminders on one screen |
| Player | Channel, movie, episode, or recording. Phone gestures, PiP, and lock-screen controls |
| Admin | Image cards, plus Setup for status, quiet hours, backup, and Magic Cleanup |
| EPG edits | Countries, groups, stripper, renamer, Sanitize, Parental, decoder, Scrobbler |

---

<div align="center">

## Limits

</div>

- This is a player only. You must provide your own streams.
- It is a beta.
- **0.2.105** is the latest signed file (`olympus-iptv-0.2.105.apk`, versionCode 105). Install it over 0.2.104. Phones and TVs both use `https://stream-me-service-api.getjohnathan.com`.
- `portal/data/store.json` is for the Node portal on a PC. It is not packaged in the APK.
- Chromecast often cannot play provider IPTV/HLS. The Cast button warns before connecting.
- Ratings, trailers, and New this week need the matching keys in `local.properties` at build time.
- Live groups follow the provider’s category ids. A huge 24/7 card is usually one panel category.
- Live groups with 250 or more channels use hour blocks of each channel name and page 20 channels at a time.
- The on-device dashboard is only for the home network, and only while the app process is running. The cloud portal stores the playlist login and lineup edits for the device ID, and the app checks those revisions every 10 seconds.
- The device has to stay able to run its recorder for a scheduled recording to start.
- One recording at a time.
- Resume applies to movies and episodes. Opening the app does not start the last live channel. There is no Continue strip above the groups.
- New this week lists Sunday–Saturday premieres only when the title is already in Movies or Shows. Watchmode does not host the video.
- Skip intro jumps one minute. The provider does not send the start time of a theme.
- Subtitles downloaded for an episode are English. OpenSubtitles is used only when the build has `opensubtitles.apiKey`.
- Phone picture-in-picture, gestures, and the lock-screen channel controls are not used on a television.
- The 6-hour catalog job runs on phones, on unmetered Wi-Fi, when the battery is not low. It does not run on TV.
