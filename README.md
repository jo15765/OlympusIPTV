<div align="center">

# Olympus-IPTV

**Beta.** Feedback is greatly appreciated.

### For the latest and greatest, build your own APK from this code.

The latest signed file is **0.2.35** (versionCode 36). Install that over the copy already on the TV. It keeps the recent guide and player features, shifts EPG titles into view (with taller wrap rows when needed), and fixes the lag and freeze regressions from 0.2.34.

**This is a player only. You must provide your own streams.**

Olympus-IPTV does not include channels, movies, shows, or stream addresses. It plays an M3U playlist or an Xtream Codes account you already have.

</div>

---

<div align="center">

## Contents

</div>

- [Start here](#start-here)

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

Olympus-IPTV is an Android TV app for a remote. It browses live TV, movies, and series from your playlist. While it is open, it hosts a home dashboard so a phone or computer on the same Wi-Fi can edit the same lineup.

| | |
| --- | --- |
| Signed file | `C:\Users\chalu\Downloads\olympus-iptv-0.2.35.apk` |
| What that file is | versionName **0.2.35**, versionCode **36** |
| What to run for current work | That signed file, or a newer APK you build from this source |
| Install | Over the existing copy. Uninstalling wipes the saved account, playlist, and favorites. |
| Launcher name | Two centered lines: Olympus over IPTV |

The signed file is **0.2.35**. A TV that already has an account opens on the left rail, and the group cards fill in when the group names are ready. The login screen is created only when no account is stored. Live TV shows Favorites setup, Queue, New / Just Added, Reminders, and Parental above the groups.

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
- Runs on a Shield, an Onn streaming box, and a Google TV Streamer from one APK. Minimum is Android 7 (API 24). The APK includes 32-bit and 64-bit ARM. Shield Experience 9.2.4 is Android 11. The number in parentheses on that Shield screen is NVIDIA’s build, not the Android version.
- Signs in with an email and a 5-digit US ZIP. The ZIP is only used to place local channels. Include VOD starts checked. Include International starts unchecked, so foreign groups stay out until you turn that on.
- Creates a device id shaped `OI-XXXX-XXXX-XXXX`. One email can have more than one device. Each device keeps its own lineup, favorites, catalog, and recordings.
- Pulls the provider catalog once per list (live, movies, shows), shows a percent in steps of 5, then the counts, then the auto-add cards. Later opens skip the favorites splash when the catalog is already on the device. Favorites setup, at the top of Live TV, can open that screen again without Continue. Back from there returns focus to that chip.
- Lets the first account be saved from the dashboard address on the login screen. The page shows **Set up this TV** until an account exists. Saving it stores the account on the set. The login screen moves on as soon as that save lands. Opening the app again reads that saved account once and opens the group screen directly. The login screen is created only when no account is stored.
- Browses with a remote: **Search, Live TV, Movies, Shows, Recordings, Admin, and About.** On Live TV, the top of the groups screen has Favorites setup, Queue, New / Just Added, Reminders, and Parental. There is no Settings item and no Sign out.

---

<div align="center">

## The live guide

</div>

When an account is already saved, the app opens on two panes: the settings rail and the group cards. The login screen is not created. The rail is on screen while the group names load, and the cards fill in when those names are ready. The channel list and the hour-by-hour guide stay hidden until a card is opened. It does not start the last channel. The rail stays open, and its divider sits at the end of the longest label. The title on that rail is **Olympus IPTV** on one line. The launcher name under the icon stays on two lines.

1. **TV, Movies, or Shows.** Group cards fill the space beside the rail, three smaller cards per row. Choosing one of those on the rail opens its cards and moves focus to the top-left card. A card shows the group name and, once counted, how many channels or titles it holds. Recently watched says Resume watching. Continue says Pick up where you left off. Moving across the cards does not load channels. The counts are read once and kept until the library changes.
2. **OK on a card**, or Right from a card on the right edge, opens that group and loads it then. Focus leaves the group card. On TV it lands on the program that is on now for the first channel. Movies and Shows land on the first title. The other groups are not listed on that screen. Back from About or Admin returns focus to that same item on the left rail.
3. **Right** from a channel moves into the guide and later in time. **Left** from the first time slot returns to the channel list. Left from a later slot steps one slot back.
4. **Back** from the channel list or the guide returns to the group cards. Back from the cards returns to the rail. Back on the rail does not quit the app.
5. **Left** during full-screen playback returns to the channel list. **Back** during live playback returns to the channel and guide for that group. Back from there returns to the group cards. Back during movie or show playback still leaves the player and returns to the title you started.

Hold OK on a live channel or a program for favorites, move up, move down, record, and **Remind me**. Movies and Shows use that same hold, without move up or move down, and add **Add to Queue** when the title can be queued. Remind me asks for 15, 30, 45, or 60 minutes before that program starts. When that time arrives, a card appears over the current screen: the channel number, the show, and the start time, with Ignore, Remind me in 5 minutes, and Tune to channel. Tune opens that channel. **Reminders** is a chip at the top of Live TV. It lists every reminder. Edit changes the minutes. Delete removes it. The same list is on the dashboard Reminders tab.

On the channel list or the program guide, the number keys open a large Channel dial in the middle of the screen. The digits stay up while you type. After a short pause, or OK, the guide jumps to that channel. The number is the row on screen, or the channel’s own number when that matches. A number that is not in the open list stays on screen briefly and says that channel is not in this list. Back clears the digits before it leaves the guide.

A phone Save that hides, renames, or strips names is applied to the guide you already have open. Favorites update when you add or remove one.

Each channel row is one line: number, logo, and the lineup name, such as `GA | Atlanta | Fox 5`. The row is the same height as the program bar beside it, and the two lists scroll together. On the full guide the channel column grows to the longest name on screen and the times start at the end of that name. Adding channels measures only the new names.

The header shows the date and clock from the TV’s Date & time setting, including that setting’s time zone, then `Group :` and the group name. The half-hour labels and the now line use the same clock. The show under the line is the listing whose start and end cover that time. Changing the date, clock, or time zone on the TV moves the guide to match. Program titles sit in rounded bars. When a program started before the visible window, its title shifts into the on-screen part of that bar so you can still read what is airing. If that visible slice is too narrow for the name, the guide rows grow taller and wrap the title onto a second line. The selected bar is the light highlight. The clock stays on the half hour above the bars. The time under a title is not repeated on the bar.

**Now** sits in that header while the guide is open. It scrolls the timeline back to the current half-hour, focuses the program that is on now for the row you were on, and keeps the now line on screen. Up and Down in the guide follow clock time across channels, so a long program that started before the current half-hour stays selected when it is still airing. Focusing that bar does not jump the scroll to the end of the program or to the next title.

Empty stretches are filled. A channel whose listings are only its own name, or that has no guide data, shows that channel name in hour blocks instead of a gap or a single “No EPG data” sliver. Real program titles are left as the provider sent them.

OK on a past program plays catch-up when the provider marked that channel for archive or catch-up (Xtream `tv_archive`, or M3U `catchup` / `catchup-source` / `catchup-days`). Channels without that mark leave past slots as guide only.

There is no preview window in the corner. The focused live channel can be warmed quietly so full-screen playback starts on that same player.

**Continue** is the first group on Movies and Shows when a movie or episode was stopped in the middle. It lists that title and the minute you reached. OK on a movie resumes it. OK on a show resumes the episode. The group is hidden when nothing is in progress. **Recently watched** stays the first group on TV, and the next group on Movies and Shows, directly above Favorites, even before anything has been played. **Favorites** lists only the favorites for that section. Live TV also shows a favorites quick-zap strip under the group pills: OK on a chip plays that favorite and keeps the strip as the zap list. **Guide density** on EPG edits switches Comfortable or Compact row height.

That hold is one menu. Live TV includes move up and move down. Movies and Shows leave those two off and include Add to Queue. Record opens its own window. The star, record, up, and down buttons are not on the channel row, because Right opens the guide.

---

<div align="center">

## Movies, shows, and New

</div>

Movies are video-on-demand titles that are not part of a series. Shows are series. Both open on the same group cards as TV. OK on a card opens that group’s poster grid and leaves focus on the first title.

Opening a show shows its seasons as a row of posters. It does not start playing on its own. A season poster is checked when every episode in it has been watched. Select a season to see that season’s episodes, each with its description when the provider sends one. An episode title is the season and episode code plus the episode name, such as `S02-E01 - Episode Name`. The series name is not repeated. An episode is checked when playback passes 95 percent. Hold the center button on an episode to check or clear that episode. **Mark this season watched** checks every episode in the open season. Hold the center button on a season poster to do the same thing. When that season is already checked, the control clears the checks. Back from the episode list returns to the seasons. Back from the seasons returns to the shows.

When the provider sends them, a movie or episode shows a short line with plot, year, and length before it plays.

When an episode ends, the player counts down from 10. At 0 it plays the next episode in that season, then the first episode of the next season. **Skip intro** starts that episode about one minute in, or jumps a minute ahead if the theme is already playing. The last episode of a show does not loop back to the first. Back during the countdown leaves the player. Up or Down also leaves the countdown and changes episode.

### New this week

**New / Just Added** is a chip at the top of Live TV. It is a Sunday-through-Saturday list of US streaming premieres from Watchmode.

| | |
| --- | --- |
| Services | Netflix, Prime Video, Disney+, Hulu, Max, Apple TV+, Peacock, Paramount+ |
| What Watchmode does | Names, dates, artwork, and descriptions. It does not host video. |
| What gets a poster | Only a premiere whose title is already in your Movies or TV Shows. The same title with a year or a season on the end still counts. Anything you cannot play is left off. |
| The grid | Four posters per row. Each card shows the whole poster, the title, and the service underneath. The artwork is the same poster used in Movies or Shows. The list scrolls. |
| Select a poster | Opens the service, the day, and the description. From there a movie plays, or a series opens its episode list. Back from that page returns to the grid. Back from the grid returns to Live TV. Hold the center button to add it to Queue. |
| Refresh | The week is prepared in the background when the app opens and again after the catalog is on the device, so the grid is ready when you open New. A same-week copy is shown immediately and refreshed about once a day. |
| Key | `watchmode.apiKey` in `local.properties`. It is copied in when you build. It is not written into the repository. Without it, the screen says the releases cannot load. |

A movie or episode you left in the middle shows Continue and the minute you reached, both on its poster and in the Continue group. A spot under 15 seconds, or within 20 seconds of the end, starts from the beginning and is left out of Continue. Live TV does not resume a minute mark, and opening the app does not start the last channel. It opens on the group cards.

---

<div align="center">

## Names, groups, and countries

</div>

The guide shows the names you set, not the raw provider strings, everywhere those names appear: the channel column, the group cards, and the dimmed caption at the top of playback. If a program title is only the provider’s channel name, that caption uses the lineup name too.

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

Search uses the section the card is under. On the dashboard, the favorites you already have are listed above the search box. Up and Down move one row and save that order immediately. Search stars append one channel or remove that one channel. They do not replace the list. Movies and shows show up in dashboard search after the TV has opened once and published them.

Open the guide after a dashboard favorite change. The TV keeps favorites it still has, adds ones that exist only on the dashboard, and drops ones the dashboard removed. If both sides have the same favorites in a different order, the TV writes its order back.

Auto-add can pull local major networks, major sports networks, teams you pick, and preset lineups. The presets include **DurictTV Streamer**, **YourFriendlyTV**, and **HoolaHoop Live TV**. YourFriendlyTV adds the frndlyTV-style family lineup (Hallmark, Lifetime, classic TV, and the rest of that package) when those channels are in your library. HoolaHoop Live TV adds the Hulu + Live TV lineup, including your local ABC, CBS, FOX, NBC, CW, and PBS stations. A channel that is not in your library is skipped. The ZIP is used for the local stations. Continue stays on that screen after the channels are added.

### Queue

**Queue** is a chip at the top of Live TV. Hold the center button on a movie or a series in Movies or Shows and choose **Add to Queue** from that menu. Search and New offer the same choice. The same hold on an item in Queue offers **Remove from queue**. OK on a queued movie plays it and keeps the rest of the Queue ready, so when that movie ends the player counts down to the next movie unless you choose Back to menu. OK on a queued series opens its episodes. The queue stays on the device.

---

<div align="center">

## Search

</div>

Search looks through TV, Movies, and Shows and groups the hits under those headings. Search or the keyboard search key hides the on-screen keyboard and moves to the first result. Hide keyboard does the same. A word, or the start of a word, matches anywhere in the name. A single character matches a name that has a word starting with that character. It does not require FTS5.

---

<div align="center">

## The player

</div>

The player is Media3 ExoPlayer on its own HTTP stack. Logos use Coil. Catalog and dashboard calls use OkHttp. The player does not send your streams through that client.

The top caption shows the lineup name, the current program, and the next program. Up and Down change channel inside the group, through the live list, or along the list that was on screen, depending on how you opened it. OK while the program is playing opens Audio, Subtitles, Record, Picture, **Last**, **Go live**, and **Multi-view** along the bottom. Audio picks a language carried in the stream. The player prefers **English (US)** when that track exists, remembers the language you last chose for a channel, and reuses that choice the next time the channel opens. Subtitles picks a track in the stream or an English file, and the language you last chose is remembered the same way. Record asks what to record. Picture picks Fit, Fill, or Stretch and keeps that choice for the next stream. **Last** is for live TV. It returns to the live channel you were on before this one. The remote’s Last Channel key does the same. You need at least two live channels in that trail before Last has somewhere to go. Pause on live keeps you in the timeshift window; Left and Right step about ten seconds inside that window when the stream allows it. **Go live** jumps back to the live edge. **Multi-view** shows the previous live channel beside the current one, muted, and keeps that preference. Catch-up and other non-live titles show a seek bar at the bottom. Back closes that row. The caption hides itself.

When a movie or episode ends, a countdown offers the next title. For a series that is the next episode, with **Skip intro**. For Queue or a favorites list that still has titles after this one, that is the next item, with **Play next**. **Back to menu** leaves the player and returns to the guide. If you do nothing, the next title starts when the countdown reaches 0.

A live channel that loses the source reconnects and keeps playing until you change the channel or leave. Movies and shows still report a playback error. A movie or episode writes its position about every eight seconds and again when you leave.

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

Parental controls are the **Parental** chip at the top of Live TV, and the **Parental controls** card on EPG edits. That screen lists Live TV, Movies, and Shows groups. OK on a group sets a 4-digit PIN, and a lock then sits on that group here and in the top right of its card. Remove lock clears it. Hold OK on a group card still opens the same PIN. The web Groups row has Lock for the same password. Opening a locked group asks for the password and stays on the group screen until it is correct. After a correct PIN, that group stays unlocked for the parental unlock time on EPG edits (5, 15, 30, or 60 minutes; default 15), then locks again. Cancel returns to the groups. A wrong password shows “Incorrect password. Try again.” over the box. The password is stored as a code in the lineup, shared by the TV and the web.

**EPG edits** on the web page is the same kind of card grid as the TV screen: Show/Hide Countries, Show/Hide Groups/Channels, Show everything again, Bulk Channel Stripper, Bulk Group Renamer, Sanitize Group & Channel Names, Parse channel names into the guide, and Remove images. Countries and Groups open those tabs. Every other card opens its own page. Save on that page is what sends the edit. Back returns to the cards.

**Sanitize Group & Channel Names** opens its own page. Back returns to the EPG edits cards. The two lists on the left are channel labels. The two on the right are group labels. In each pair, the right list is what Cleanup will remove. Select a label and use the arrows to move it into the left list, or back to the right. Cleanup stays on that page, shows Cleaning…, and flashes while it works. When it finishes, the page returns to the EPG edits cards. Plain words such as `HD` or `NBA` come off the start or the end. Phrases such as `Flo Football`, `US:`, and `GA | Atlanta |` are removed wherever they appear. `HD` still does not eat `FHD`. `USA |` starts on the group strip list.

Each section has its own gold **Save**. That button sends the lineup to the TV. Favorites are sent as you add or remove one. Record’s Save stores the network share. Backup’s Import saves the file you chose. There is no single Save in the side rail.

Leaving a page after a change, and before Save, opens a notice that those changes are not saved yet. OK continues to the other page. Save is what puts the lineup on the TV. A guide that is already open applies a hide, a rename, a strip, a password, or a favorite when that save lands. If the app is holding a newer lineup in memory, the newer revision wins. There is no minute timer for these edits. The 60-minute refresh downloads the provider catalog. It is not the lineup sync.

Admin, then Error log, shows the log in a window you can scroll and resize. A− and A+ change the text size. Download saves the file that is open. Older log files are listed under that window.

`portal/` is an older Node copy of the page for work on a PC. The TV serves `app/src/main/assets/dashboard/`. A signed install does not need the Node server.

---

<div align="center">

## Admin and About

</div>

| Screen | What it does |
| --- | --- |
| Refresh Options | Checks the provider and saves channels that are not already on this TV. The interval can be 30, 60, 120, 360, 720, or 1440 minutes. The default is 60. |
| Web-UI | Shows the email, the device id, and the dashboard address. Remove playlist clears this TV’s account, library, favorites, lineup edits, recordings list, watch history, queue, and reminders, keeps the device id, and opens sign-in. |
| EPG edits | On the TV and on the web page: countries, groups and channels, show everything again, the bulk stripper, the bulk group renamer, Sanitize Group & Channel Names, image removal, and the optional switch that fills an empty guide from channel names. The TV also has Parental controls, which lists the groups and sets the PIN; **Hide empty groups** (on by default), which removes group cards that have no visible channels after your country and hide rules; **Guide density** (Comfortable or Compact); and **Parental unlock time** (5–60 minutes). |
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
- Search catalog export for the dashboard is kept by library stamp in memory and on disk so a second publish does not rescan every channel.
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
- The favorites quick-zap strip keeps its channel list until favorites change, and skips rebuilding chips when nothing moved.
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

---

<div align="center">

## Remote

</div>

- Up and Down stay in the current list, or change channel in the player. On the group cards they move between rows.
- Left and Right move across the group cards. On the program guide, Right moves later in time and Left moves back, until the first slot, which returns to the channel list.
- Left during full-screen playback returns to the channel list, or steps live timeshift when the stream allows it. Right steps timeshift forward on live. Back from live playback returns to the channel and guide. Back from that screen returns to the group cards.
- OK or Enter activates the focused control. OK on a group card opens it. OK on a program plays it. OK on a New poster opens its description. OK on a queued title plays it or opens its episodes.
- Hold OK on live TV for favorites, move up, move down, record, and Remind me. That is one menu. Remind me can be 15, 30, 45, or 60 minutes before the program. The card offers Ignore, a 5-minute snooze, or Tune to channel. The Reminders chip at the top of Live TV lists those reminders, and Edit or Delete changes one. Hold OK on a movie or a show for favorites, record, and Add to Queue. Move up and move down are not on that menu. Hold OK on a season poster to check or clear that season. Hold OK on an episode to check or clear that episode. The Parental chip on Live TV, and Parental controls on EPG edits, list the groups. OK on a group there sets the 4-digit PIN and shows the lock. Hold OK on a group card, before the group is open, opens the same PIN.
- On the channel list or the program guide, number keys open a Channel dial. A short pause, or OK, jumps to that row. Back clears the digits first.
- Back steps one screen, including out of full-screen playback, out of an episode list back to its seasons, out of a New description, and out of the next-episode countdown.
- When an episode ends, Next episode counts down from 10. Skip intro jumps about one minute into the next episode.
- On an episode, Subtitles sits next to Record, and Audio sits beside them when the stream has an audio track. Subtitles lists tracks carried by the stream, then English subtitles from TVsubtitles.net and, when a key is set, OpenSubtitles. OK switches a track or adds the file and keeps your place. The picture keeps playing.
- The rename keyboard uses Sft, Spc, and DEL. Left and Right move through the text. Down moves to Save.

---

<div align="center">

## What is stored

</div>

On the TV, DataStore `olympus_iptv` holds the playlist, the session, favorites and their revision, the Queue, the device id, lineup edits, the last live channel, watch history (up to 60 rows per playlist, including resume points), whether favorites setup is done, recordings plus SMB settings, the Picture mode (Fit, Fill, or Stretch), the preferred audio language (default `en-US`) plus per-channel audio picks, subtitle language memory, guide density, quiet hours, parental unlock minutes, multi-view, and whether empty groups are hidden. Program reminders are kept in `reminders.json` under the app’s files. A reminder stores the channel and the program time, not the stream address. Admin → Web-UI → Remove playlist clears that account data and the SQLite library on this TV, keeps the device id, and returns to sign-in.

The library is SQLite `olympus-iptv.db` (schema version 8): categories, channels, a disk cache of guide rows, and watched episodes. Stream addresses stay in that database. They are not published to the dashboard.

Lineup edits cover hidden countries, groups, and channels, custom group and channel names, group and channel order, strip prefixes and suffixes, the bulk group find and replace text, the name-to-guide flag, Include International, and a revision.

The week’s premiere list is cached on the device for about a day. The Watchmode key is not stored in that cache. It is baked in at build time from `local.properties`.

The SMB password and the playlist password stay on the device. Do not commit a copy of the DataStore file, `local.properties`, or a dashboard settings export that still has secrets.

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

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Put a Watchmode key in `local.properties` as `watchmode.apiKey` before that build if you want **New** to load. Put an OpenSubtitles key there as `opensubtitles.apiKey` if you want that source in **Subtitles**. Neither key is written into the repository. The signed release is `C:\Users\chalu\Downloads\olympus-iptv-0.2.35.apk` (with `olympus-iptv-0.2.35.apk.idsig` beside it).

Android Studio: use a 1920×1080 Google TV emulator. Apply Changes does not pick up new screens. Stop the app, then Debug. Click the emulator window before the arrow keys. The emulator is a different device from a Shield or an Onn box, so it does not have that box’s account, playlist, or favorites.

Libraries: Kotlin, AndroidX, ViewBinding, Media3 ExoPlayer 1.5.1 (HLS and DASH), OkHttp, Gson, DataStore, Coil, and SMBJ for the network share. The TV dashboard is a page in the APK, not a separate package.

---

<div align="center">

## Look of the app

</div>

The UI is warm charcoal and honey.

| Role | Hex |
| --- | --- |
| Background | `#141210` |
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
| Episodes | Season posters, then that season’s episodes with descriptions. Check one episode or the whole season. |
| Admin | Refresh Options, Web-UI, EPG edits, and VPN, in two rows of two. The web dashboard has the same VPN card |
| About | Player-only notice, credits, coffee QR |
| Search | Find a channel, movie, or show and star it |
| Player | Play a channel, movie, episode, or recording |
| EPG edits | Countries, groups, stripper, group renamer, Sanitize Group & Channel Names, Parental controls, images, name-to-guide |
| Favorites setup | Auto-add, skipped when the catalog is already loaded |
| Record | View, schedule, or set the share |
| Reminders | Every set reminder, with Edit and Delete |

---

<div align="center">

## Version history

</div>

### 0.2.35

- EPG titles that start before the visible window stay readable; narrow slices grow the row and wrap the name.
- Fixes lag and freezes from 0.2.34 (safe EPG cache, bounded catalog ingest, no next-channel decoder preload, lighter scroll work).

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
- **0.2.35** is the latest signed file. Anything newer than that needs a build from this source.
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
