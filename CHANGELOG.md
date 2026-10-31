# Changelog

Every HushGram release, newest first.

## Unreleased

### Features

* **Instagram:** `Skip the ad data reminder` is a new patch. With its switch on under Ads and privacy, Instagram's reminder to review whether it can process your data for ads stops opening by itself. Your ad choice doesn't change and nothing is answered for you. It starts off. Asked for in #331.

### Improvements

* **Tooling:** `preflight.ps1 -Version` now refuses a source census past its 14 days. Until now the index push was the first thing to say so, after the gate, the walk and the publish.
* **Tooling:** `count_tests.py --description` prints only the validation sentence on stdout and its counts on stderr, so saving its output gives `release_notes.py` the one line it wants.

## 0.0.18 (2026-10-31)

### Features

* **Instagram:** `Stop videos looping` is a new patch under Playback. Feed videos and reels stop on their last frame instead of playing again. Reels still loop while auto scroll is on, so it can move on. It starts off. Asked for in #248 and #175.
* **Instagram:** `Hide Subscribe on profiles` and `Hide the arrow at the end of the buttons` are new under Profiles. The other buttons fill the row, and the arrow's menu goes with it. Both start off. Asked for in #248.
* **Instagram:** `Skip the contacts and location popups` is a new patch. With its switch on under Ads and privacy, the screens asking you to sync contacts and turn on location stop opening at every start. It starts off. Asked for in #295, #329 and #421.
* **Instagram:** `Emoji style` gets `Emoji pack` under Layout. Keep your phone's emoji, download Twemoji, OpenMoji or Blobmoji, or pick a color emoji font, then restart Instagram. A pack only switches in once its download checks out. Asked for in #166.
* **Instagram:** `Smooth story opening` is a new patch under Stories. A story you tap in the stories row zooms open more slowly and stops without the little bounce at the end, and it closes the same way. It starts off. Asked for in #409.
* **Instagram:** `App-only proxy` is a new patch under Ads and privacy. Type an HTTP proxy's address and port in `Proxy server`, restart, and only Instagram goes through it. It takes plain HTTP proxies that need no password. Starts off. Asked for in #219.
* **Instagram:** `Copy text button on reels` and `Copy text button on stories` add a Lens icon that opens Google Lens on the reel or story to copy its words. Without the Google app it opens the share sheet. Both start off. Asked for in #303.
* **Instagram:** `Ambient light in stories` is a new switch under Stories. A story that doesn't fill the screen gets a soft glow in its own colors in the black bands, and it changes with each photo or video. Starts off. Asked for in #420.
* **Instagram:** `Ambient light` is a new patch under Feed, off to start. Posts in Home and Following glow softly in their own colors, and so do the bands around a reel that doesn't fill the screen. Asked for in #416.
* **Instagram:** `Carousel arrows` is a new patch. Its switch under Feed puts a back and a next arrow on a carousel post, so a tap moves to the next photo or video without swiping. It starts off. Asked for in #417.
* **Instagram:** `Carousel arrows` gets `Page dots on a pill`, a new switch right under the arrows. A carousel's page dots move onto a round pill at the bottom, with your page in blue. Starts off. Asked for in #419.
* **Instagram:** `Keep Home's top bar` gets `See-through top bar`, a new switch right under it. Home's top bar turns to frosted glass and your posts show through it as they scroll, whether the bar stays or slides away. Needs a restart. Asked for in #418.
* **Instagram:** `Hide ads` now hides the ad that shows up among a post's or reel's comments too. Asked for in #415.
* **Instagram:** In `Stop Reels scrolling`, `Stop after 20 reels` is now `Stop after a set number of reels`. Sliders pick the count (5 to 100) and how long Instagram sits in the background before swiping works again (5 to 120 minutes). Asked for in #405.
* **Instagram:** `Keep a viewing history` under Ads and privacy keeps a list of the posts, reels, stories and profiles you looked at, on this phone only. `Clear viewing history` empties it. Starts off. Asked for in #228.
* **Instagram:** The `App icon` list now shows each choice's real icon in its own colors next to its name, with the one in use marked, so you can see what you're picking before you leave Instagram. Asked for in #321 and #327.
* **Instagram:** The `Accent color` list on Android 11 and older marks the color in use again.
* **Instagram:** On Android 11 and older, `Material You` now stays in the `Color` list, greyed out, with the reason on it. Before, it just wasn't there. Asked for in #187 and #264.
* **Instagram:** `Clean up Reels` adds `Start every reel in clear mode` under `Keep clear mode on`, so reels open in clear mode with no tap. Instagram blocks screenshots there unless `Allow screenshots` is on. Starts off. Asked for in #138.
* **Instagram:** `Icons in the accent color` is a new switch under `Accent color`, off to start. After a restart the top bar, profile, tab bar and post icons take your color. Text keeps its own. Asked for in #8.
* **Instagram:** `Explore without personalization` is a new patch for the EU, where Instagram offers that grid. One switch under Explore opens it every time. Another hides its menu button. Both start off. Asked for in #311.
* **Instagram:** `Keep Featured off the heart's dot` under Notifications stops a Featured item from lighting the red dot on the heart. Likes, comments and follows still do. Starts off. Asked for in #189.
* **Instagram:** `Pinned chats` under Messages pins the chat you opened last to the top of your inbox. Pins stay on this phone, per account, and the same row unpins them. Asked for in #345.
* **Instagram:** `App icon` adds Hand-drawn Instagram, a pencil sketch of Instagram's glyph on white. Themed icons show the same sketch. Asked for in #406.
* **Instagram:** `Chat history tools` is a new patch. Its switch under Messages lets you long press a chat's name to jump to its first message or a date, loading older messages with a count and Cancel. Starts off. Asked for in #246.
* **Instagram:** `Chat history tools` gets a second switch, `Export a chat as text`. Long press a chat's name and pick Export chat to save the whole chat to Downloads/HushGram, one line per message. Starts off. Asked for in #247.
* **Instagram:** `Save comment photo` gets a second switch under Comments, `Reply with a comment's GIF`. A comment's menu gets a row that sends its GIF back as your reply, animated. Starts off. Asked for in #402.
* **Instagram:** `Use the highest refresh rate` is a new patch. Its switch, under Layout, asks Android to run Instagram at your screen's fastest refresh rate at the same resolution. It takes effect the moment you flip it, no restart. Starts off. Asked for in #401.
* **Instagram:** `Read messages without the seen receipt` gets a switch, `Mark a chat seen when you reply`. Replying lets that person see you read their earlier messages. Chats you don't answer stay unseen. Starts off. Reported in #365.
* **Instagram:** `Hide captions` is a new patch. Its switch, under Feed, takes the caption off feed posts and reels and leaves the post. The comments sheet still shows it. Off to start. Asked for in #395.
* **Instagram:** `Keep Home's top bar` is a new patch. Its switch under Feed keeps the Instagram logo and its buttons at the top of Home while you scroll. Restart Instagram after you flip it. Starts off. Asked for in #408, #367 and #223.
* **Instagram:** `Keep the Reels top bar` is a new patch. Its switch, under Reels, keeps the Reels title, the Friends tab and the buttons beside them at the top while you swipe through reels. No restart needed. Starts off. Asked for in #411.
* **Instagram:** `Mark one-time photos and videos` is a new patch. Its switch, under Messages, puts a border and Instagram's view once icon on one-time photos and videos in a chat. Only the look changes. Starts off. Asked for in #165.
* **Instagram:** `Pinch to zoom on Reels` is a new patch. Its switch, `Pinch to zoom` under Reels, lets two fingers zoom into a playing reel the way a photo zooms, and it goes back when you lift them. Starts off. Asked for in #382.
* **Instagram:** `HushGram settings` adds `Descriptions behind an info button` under Settings entry. It shows each switch by name alone, with a small i to tap for what it does. Off to start. Asked for in #130.
* **Instagram:** `Swipe through a chat's reels` adds `Stop after the chat's reels` under Messages, which ends reels you open from a chat at the last one shared there. No suggested reels follow. Starts off. Asked for in #390.
* **Instagram:** In `Download any video`, Copy media link under Details now says the link stops working after a few days, since Instagram's file addresses expire. Asked for in #364.
* **Instagram:** `Download any video` adds `Never save AV1 video` under Downloads, for a phone that plays AV1 badly. A video Instagram has at full size only as AV1 is saved as VP9, or at its largest H.264 size. Starts off. Asked for in #379 and #348.
* **Instagram:** `Download any video` adds `Never save HDR video` under Downloads. An HDR-only video is saved at its largest standard size instead, so it skips the yellow or green cast some apps give HDR. Starts off. Reported in #352.
* **Instagram:** `Seek bar on feed videos` is a new patch. A bar along the bottom of the video playing in your feed lets you drag to any point in it. The switch is under Feed and starts off. Asked for in #389.
* **Instagram:** In `Glass tab bar`, `Blur strength` and `Tab bar opacity` under Tab bar are sliders (2 to 48 dp, 0 to 150 percent), and the pill follows as you drag. Both start at the old look. Older backups still restore. Asked for in #391.
* **Instagram:** When Instagram crashes in the first minute after it opens, HushGram now keeps what crashed, and the diagnostics report you save from `HushGram settings` includes it. Turn on Debug logging to keep later crashes too.
* **Instagram:** `Group Instagram's notifications` adds `Mute notification types` under Notifications. It stops the types you tick, like the betting and promo pushes Instagram's own notification settings can't turn off, and works with grouping off. Nothing's muted to start.
* **Instagram:** `Lock your messages` adds `Fingerprint or face only` to Unlock with under Messages, on Android 11 and up. Its prompt has no PIN, pattern or password. Remove every fingerprint and face and your screen lock unlocks again.
* **Instagram:** `Full screen Reels and Home` adds `Show the whole reel` under Reels. Every reel fits inside the screen with black bars where its shape differs, so nothing's cut off at the sides. Starts off.
* **Instagram:** `Skip the collections tray` is a new patch. With its switch on under Feed, saving a post or reel shows Instagram's Saved message instead of the tray that asks you to start a collection. Starts off.
* **Instagram:** `Hide the Reels tab` adds `Settings button on Home's header` under Tab bar. A gear at the top of Home opens HushGram settings with one tap. Starts off. Asked for in #404.
* **Instagram:** `Download any story` adds `Download button on stories` under Stories. A button at the top of a story, beside the three dots, saves the photo or video in front with one tap. Starts off. Asked for in #400.
* **Instagram:** `Start reels with sound` adds `Start reels muted` under Playback. Every reel starts silent, even from a link or a notification, and the volume keys or speaker icon unmute it. Starts off. Asked for in #342.
* **Instagram:** `View live anonymously` adds `Mark as seen button on lives` under Stories. While you watch a live anonymously, an eye beside the three dots puts you on that live's viewer list when you tap it. Starts off. Asked for in #412.
* **Instagram:** `Glass tab bar` adds `Bend light at the edges` under Tab bar. With `Liquid glass look` on, the pill's rim bends what passes under it the way real glass does, on Android 13 and up. Starts off. Asked for in #369.
* **Instagram:** `Glass tab bar` adds `Run Reels behind the tab bar` under Tab bar. The reel fills the screen behind the pill, with its caption, buttons and seek bar just above it. Starts off. Asked for in #372.
* **Instagram:** `Clean up Reels` adds `Hide suggested searches on reels`, `Hide shopping buttons on reels` and `Hide poll prompts on reels` under Reels. Each takes its prompt away from beside the reel's author. All three start off. Asked for in #332.
* **Instagram:** `Start Home on Following` adds `Fall back to Following` under Feed. When For you brings an account only suggested posts, page after page, Home opens on Following for that account. A feed you pick stays picked. Starts off. Asked for in #308.

### Bug Fixes

* **Instagram:** `Instagram logo on Home` works without `Start Home on Following` now. Turned on, the logo stays put as you scroll instead of switching to "Home" and flashing blank in between. Reported in #413.
* **Instagram:** `Turn off HDR brightness boosts` also stops the brighter white Instagram 452 and later give some reels on phones with HDR headroom, like the Galaxy S25 FE. Reported in #85 and #26.
* **Instagram:** `Full size covers` asks for covers up to 1440 pixels wide now. It used to stop at 1080, and some picked covers are 1290 wide. Reported in #214.
* **Instagram:** Turning `Full size covers` on or off now reaches reels you'd already opened. Instagram keeps the cover sizes it was sent across restarts, so the first start after a change now drops those saved answers. Reported in #214.
* **Instagram:** `Hide Featured in notifications` works on Instagram 453 again, Threads suggestions included. Featured rows were back at the top of Notifications. Reported in #259.
* **Instagram:** With `Hide the Muse card`, the Meet Muse card stays off Home and your profile when Instagram sends it some other way. It still showed up now and then. Reported in #294.
* **Instagram:** With `Pure black dark mode`, a story now opens straight on black. It flashed Instagram's dark blue for a moment first. Reported in #360.
* **Instagram:** With `Pure black dark mode`, the screen Instagram shows while it starts up is black in dark mode too. It stayed dark gray. Reported in #410.
* **Instagram:** With `Gray out stories you've watched`, your own `Your story` now stays first in the tray, ahead of the watched stories pushed to the back. Before, it could end up among them. Reported in #241.
* **Instagram:** With `Pure black dark mode`, HushGram's own settings cards and dialogs are black now. They stayed dark gray. Reported in #374.
* **Instagram:** With `Glass tab bar` and `Hide the tab bar on Reels`, a reel fills the screen again once the pill slides away. It left a black band where the pill had been. Reported in #335.
* **Instagram:** A save that comes out in lower quality because your phone's storage is low now says so in its toast, and a carousel's summary counts how many. It used to blame only Instagram's quality. Reported in #348.
* **Instagram:** With `Tab bar layout`, picking Classic or Swipe under Tab bar works on Instagram 453 again. On 0.0.17 it changed nothing, since the patch was answering an Instagram setting the tab bar never reads. Reported in #356.
* **Instagram:** With `Tab bar layout`, a long press on the classic bar's Search tab opens search with the keyboard up again. Instagram 453 waited for pages only the swipe bar has, so the press just switched to Explore. Reported in #383.
* **Instagram:** `Full screen Reels and Home` makes room for the status bar on a profile again. With `Full screen Reels` on, going back from a reel you'd opened there left the person's name behind the clock. Reported in #385.
* **Instagram:** Typing fast in the `HushGram settings` search keeps every letter. The list used to take the focus from the field while it filtered, so a letter could go missing or come out twice.
* **Instagram:** With `Gray out stories you've watched` on in `View stories anonymously`, a story you've just watched keeps its place in the tray until you pull down to refresh. It used to jump right the moment you closed it. Reported in #377.
* **Instagram:** On Instagram 453, `See who a story mentions` puts its pill just under the story's header, lined up with the name. In the name row it pushed the name up into the progress bars. Reported in #405.
* **Instagram:** With Hide shopping on in `Hide suggested posts`, Explore no longer leaves blank squares where a shop tile was. A shop tile inside a bigger block now takes the whole block out, so the grid closes up. Reported in #393.
* **Instagram:** `Show all recent searches` now lists every recent search on accounts where Instagram builds the search screen the newer way. Those still stopped at five. Reported in #375.
* **Instagram:** `Swipe through a chat's reels` makes a second tap on a post or reel in a chat wait for the first to open. On a slow phone each extra tap opened another copy. Reported in #390.
* **Instagram:** With `Logo and titles at the start` and `Hide Create on Home's header` both on, Instagram's logo no longer touches the screen's left edge. It keeps the gap the heart has on the other side. Reported in #407.
* **Instagram:** With the `Glass tab bar` pill floating, coming back to Reels from a reel's profile no longer lays the Reels screens out again. That second layout could leave the video stuck on its cover while the sound kept playing. Reported in #378.
* **Instagram:** `See who a story mentions` no longer cuts the account's name short. When the name's row can't fit the pill, like on a highlight with a full date, the pill moves just under the header. Refs #405.
* **Tooling:** When patching for a phone stops on an Instagram build with no reviewed coverage, the message now names the builds that have it, so you know which one to patch.
* **Tooling:** Patching for a phone without `-Apk` takes the declared Instagram build from the fixture folder the way the release gate does, `.apks` bundles included. It used to skip `.apks` and take any file with the version in its name.
* **Tooling:** The whole-app walk takes one more look, after a short pause, at a settings heading it caught mid-animation. It used to call the heading missing and fail the walk when a section was still opening or folding.
* **Tooling:** When a settings heading isn't there, the whole-app walk says so as soon as the list stops moving. It used to keep swiping up past the top of the list, 24 times each way.
* **Tooling:** Patch results, records and the patch catalog are read as strict JSON. A file that names a field twice could read as a pass and a failure at once, so it's refused.
* **Tooling:** The release notes check refuses a bullet scoped to a patch name in any section after 0.0.17, Unreleased included, so each bullet names its patch in the text. It used to pass any dated section.
* **Tooling:** The native runner reads the user limit the way Android 17 prints it, "Maximum supported switchable users", so it runs on an API 37 emulator. It used to stop there, saying the device takes one user.
* **Tooling:** The release check reads an index description naming only the Instagram build as the published release's facts, still under that version's heading. A retarget held at the published version used to fail every push.
* **Tooling:** The release check holds a published receipt to the coverage census its own commit reviewed. Once a retarget replaced the old build's entry, the shipped receipt read as uncertified and the check failed on every push.
* **Tooling:** The source audit reports a source it couldn't read past a rate limit as unread, and fails GitLab code search when a limit keeps a hit's project unread. Both used to be treated as gone.
* **Tooling:** The source audit waits out a rate limit for as long as the forge's Retry-After asks, up to five minutes. A longer one, like GitLab's hourly limit, fails that source at once and says when it resets.
* **Tooling:** The source audit leaves a source unread when a rate limit stops a branch comparison, instead of reporting the branch as new work. A server error on a GitLab hit's project now fails the search too.

### Improvements

* **Instagram:** In Morphe Manager, `Ask before a like` and `Long press to zoom` now sit under Interaction beside `Change the like animation`, which keeps the Feed group short. Their switches are still on the Feed settings page.
* **Tooling:** Updated to Morphe patcher 2.1.0, so patching needs Morphe Manager 2.3.0 or newer. On a computer, use morphe-desktop 1.20.0 or newer. The dependency audit now reviews Morphe Desktop 1.20.0.
* **Instagram:** `See who a story mentions` opens its list again when you come back from an account's profile, so you can go down the list without reopening it each time. Close ends it. Asked for in #403.
* **Instagram:** HushGram now patches Instagram 453.0.0.57.79 instead of 453.0.0.55.79. Get APKMirror's (arm64-v8a) (480-640dpi) (Android 9.0+) variant, build 385914557. Its armeabi-v7a (385914556) and x86 (385914558) builds are declared too. Meta published no x86_64 build this time.
* **Instagram:** Manager lists `Keep a seek bar on Reels` under Playback now, next to `Seek bar on feed videos`, and the Reels group gets one row shorter. Its switch is still under Reels.
* **Instagram:** Manager lists `Start feed videos with sound` and `Keep the reel speed` under Playback now, out of the long Feed and Reels groups. Their switches haven't moved.
* **Instagram:** In `Download any reel`, Download audio's line in the diagnostics report says when a reel's only sound is xHE-AAC, which some players can't play. The file is still saved as it is, never re-encoded. A follow-up to #216.
* **Tooling:** The native runner has a share case for the share sheet stand-in. The share and its sender always come through. Instagram's links are the only ones cleaned, and just while `Sanitize sharing links` is on.

## 0.0.17 (2026-10-28)

### Features

* **Instagram - Keep your place in Home:** New switch under Feed. Coming back to Instagram leaves Home on the post you were on, however long you were away. Instagram no longer reloads it or resets the app to the top. Pull down still refreshes. Starts off. Asked for in #354.
* **Instagram - Glass tab bar:** `Hide the tab bar as you scroll` works with the glass off too. Scroll down and Instagram's own bar slides away while the page fills its space. Scroll up and it's back. Reels keep their bar. Asked for in #343.
* **Instagram - Rounded post corners:** `Rounded corners on Reels` under Feed rounds a reel's video in the Reels tab and full screen, at the same Corner size. The seek bar, captions and buttons stay whole. Starts off. Asked for in #362.
* **Instagram - Rounded post corners:** `Rounded corners on stories` under Feed rounds a story's photo or video, whether it opens from the tray, a profile or a highlight, at the same Corner size. The progress bar, names and stickers stay whole. Starts off. Asked for in #363 and #368.
* **Instagram - Rounded post corners:** `Peek at the next carousel page` under Feed sits each carousel page in from the edges, with the start of the next page showing at the right. Starts off. Asked for in #361.
* **Instagram - Thicker story progress bar:** new patch. `Thicker story progress bar` under Stories draws the bar along the top of a story twice or three times as thick. Its segments and timing don't change. Starts off. Asked for in #360.
* **Instagram - Start reels with sound:** new patch. `Start reels with sound` under Playback plays a reel you tap open from a post on Home with sound, and Home is muted again when you go back. Starts off. Asked for in #342.
* **Instagram - Pick the app icon:** `App icon` adds Instagram in black on white, a white icon with Instagram's glyph in black. Themed icons still show Instagram's glyph. Asked for in #353.
* **Instagram - Tab bar layout:** new patch. `Tab bar layout` under Tab bar keeps the newer bar, where you swipe between every tab, or the classic one, even when Instagram switches your account. Starts on Instagram's choice. Asked for in #356.
* **Instagram - Lock your messages:** `Double-tap Messages for hidden chats` under Messages opens your hidden chats from two quick taps on the Messages button, after the lock when one's on. One tap still opens Messages. Starts off. Asked for in #358.
* **Instagram - HushGram settings:** A build without Pure black dark mode now says under Layout how to get it: pick it in Morphe Manager next time you patch. Searching settings for "amoled" or "oled" finds it too. Asked for in #359.
* **Instagram - Download any video:** `Choose a folder` under Downloads sends every photo and video to a folder you pick, a hidden one too, and it works on Android 9 without the storage permission. `Use Movies and Pictures` puts saves back. Asked for in #333.
* **Instagram - Seek bar on stories:** new patch. `Seek bar on stories` under Stories puts a bar near the bottom of a video story. Drag it to jump anywhere in the video and the story plays on from there. Starts off. Asked for in #355.
* **Instagram - Swipe through a chat's reels:** new patch. With `Swipe through a chat's reels` on under Messages, swiping up on a reel opened from a chat goes through the chat's other shared reels before any suggested ones. Starts off. Asked for in #350.
* **Instagram - Hide suggested stories:** `Filter the Stories tray by verified accounts` under Stories keeps only verified accounts in the row at the top of Home, or only the ones that aren't. Your own story stays. Starts off. Asked for in #340.

### Bug Fixes

* **Instagram - Show if a profile follows you:** On your Following list, Doesn't follow you now comes before the name. A long name used to push it off the end of the line. Reported in #351.
* **Instagram:** `Hide the emoji row` and `Hide Follow on posts` now work the moment you switch them on. Before, nothing changed until you left Instagram and came back.
* **Instagram:** `Hide suggested stories` adds `Keep the Stories tray in the feed` under Stories. On some accounts the row floated over Home mid-feed and stayed put while posts scrolled under it. Now it stays in the feed. Starts off. Reported in #357.
* **Instagram:** Morphe Manager now knows the armeabi-v7a, x86 and x86_64 builds of Instagram too, so an older 32-bit phone is pointed at the build it can install instead of the arm64 one. Reported in #346.
* **Instagram:** `Glass tab bar` no longer leaves a dark band under a reel opened from the feed, where Instagram hides the tab bar. Reels opened from the Reels tab still stop above the bar. Reported in #347.
* **Instagram:** `Start stories with sound` turns Instagram's sound back off when you leave a story, so Feed and Reels stay muted. Coming back to the story turns it on again, and a story you muted yourself stays muted. Reported in #342.
* **Instagram:** `Download any story` also adds Download to the menu of your own highlights, which Instagram builds differently from other story menus. Reported in #339.
* **Instagram:** `Hide Follow on feed posts` also filters Subscribe before the feed header builds it. The author's name, time and menu keep their native paths.
* **Instagram:** `Tap to play` keeps a Reels auto-scroll allowance for the next Reel. Feed and unidentified players can't spend it, and a real tap on Feed still works.
* **Instagram:** `Group Instagram's notifications` keeps summaries while their notifications remain. Turning grouping off or removing one notification no longer dismisses the others. Pending posts stay protected while Android catches up. One notification uses the singular count label.
* **Instagram:** Turning off profile posts as a list, or pausing HushGram, cancels an automatic opening that was still waiting for the grid.
* **Instagram:** Canceling a download can finish while DNS, proxy discovery or response setup is still waiting. Late responses can't write a file, and repeated cancellations keep a bounded number of workers.
* **Instagram:** The downloader closes opened response streams on success and failure. Cache bytes stay only after a successful close.
* **Instagram:** Closing `Save profile picture` stops its fetch even when the general background workers are busy.
* **Instagram:** `Download any video` adds source metadata to JPEG photos saved through Save all and Choose pages, as it does for a single photo. Videos and other image formats stay unchanged.
* **Instagram:** Download history repairs a partially written record before its next write. Later saves remain listed after a restart.
* **Instagram:** A failed PIN save keeps the setup form open and reports the failure. The PIN and its retry counters are saved together.
* **Instagram:** PIN retry limits still apply in the current process when their counters can't be saved.
* **Instagram:** `HushGram settings` saves a timed lock and its wait together. Lock, unlock and wait actions no longer report success after a failed write.
* **Instagram:** `HushGram settings` checks the current lock before an editor or delayed action saves. Imports and Undo check again at their write boundary, and a held Undo remains available until its original deadline.
* **Instagram:** Font imports finish reading and closing their provider before replacing a live font. Failed or stale imports leave the current files alone, and reopening settings can't start a competing copy.
* **Instagram:** Replacing an imported app font keeps Restart now available even when the font choice hasn't changed.
* **Instagram:** Settings backups restore large selections of hidden Instagram settings rows. Selecting all 161 supported rows no longer causes that choice to be skipped.
* **Instagram:** `Hide suggested posts` keeps native paging when a filtered page retains a real post, even before its list is displayed. Header-only pages can still finish.
* **Instagram:** `Glass tab bar` blur recovers after a temporary capture failure on a still screen. Automatic retries stay bounded, and pending work stops when the bar leaves or HushGram pauses.
* **Instagram:** `Story text fonts` puts local fonts before Instagram's paid section, so selecting one doesn't open a Plus offer. Instagram's paid fonts keep their subscription checks. Reported in #275 and #325.
* **Instagram:** `View stories anonymously` keeps your own story, Create and special tray entries in their original positions during watched-story sorting. Ordinary story rings keep their order within the unseen and watched groups.
* **Instagram:** `HushGram settings` requires Android 9 on every ABI, including the two 32-bit APKs Instagram ships for Android 8. Manifest checks verify that floor and preserve any higher vendor minimum.
* **Instagram:** `Show if a profile follows you` waits for unique followers to match the account's count before recording recent unfollowers. Short lists and older queued saves can't replace a newer snapshot, and failed saves can be retried.
* **Instagram:** `HushGram settings` lets go of a closed screen or dialog. The moving title used to keep every one you'd opened in memory until Instagram restarted.
* **Instagram:** `Convert to keep the full size` asks the phone's encoder for limited range BT.709 color. Left to choose, some encoders write a file other apps show with shifted colors.

* **Tooling:** Cached patch lookups now refresh when classes are added, replaced or removed. Unchanged classes keep their cached reads.
* **Tooling:** Release walkthroughs require matching bundle, candidate and installed APK evidence, with visible content for each required destination. Incomplete receipts, changed installs and covered pages can't authorize publication.
* **Tooling:** Walk navigation checks the chosen tap point, including covers over a partly visible control. Settings labels keep their own location when a row handles the tap.
* **Tooling:** The walk accepts a loaded Explore grid under Instagram's empty Compose layer. A labeled, clickable, focusable, filled or differently built layer still counts as a cover.
* **Tooling:** The release walk expands each settings category in place and checks it folds again, since Back now closes settings. A row only counts under its own heading, and every row is checked against the settings source.
* **Tooling:** The source census keeps a relicensed source's old licence pinned and records code it took from elsewhere. InstaEclipse is GPL-3.0 now, and the HushGram downloader it carries counts as ours. Stylus dropped its Instagram patch.
* **Tooling:** The build moves to the Morphe patches plugin 2.0.0 and Kotlin 2.4.21, held to one version across the plugin's classpath. Patched apps come out the same, and the bundle carries Kotlin stdlib 2.4.21.

### Improvements

* **Instagram:** HushGram now patches Instagram 453.0.0.55.79 instead of 452.1.0.53.64. Get APKMirror's (arm64-v8a) (480-640dpi) (Android 9.0+) variant, build 385914283. The armeabi-v7a build (385914282) is declared too, and so are the x86 and x86_64 builds (385914284 and 385914285). Thanks to toidicakhia for #366.
* **Instagram:** HushGram's settings use Instagram's own blues instead of pink. Switches and buttons take its indigo button color, while icons and headings use the lighter blue Instagram gives links on a dark screen.
* **Instagram:** Every HushGram title wears Instagram's gradient now, including the settings bar, its dialogs and the lock screen. The colors drift slowly and a sheen sweeps across every few seconds, even with Android's animations turned off.
* **Instagram:** Settings categories expand their controls directly underneath. Several sections can stay open, and tapping a heading collapses it. Search finds controls across every section and restores your open sections when cleared.
* **Instagram:** Settings put switches with the screen they change. The Notifications page's suggestion switches moved to Notifications, the Reels tab's to Tab bar, and anonymous DM photos to Messages. Searching their old section still finds them.
* **Instagram:** The Updates section in settings is now called Version warnings, since all it holds is the switch for Instagram's "this version is too old" screen. Searching for Updates still finds it.
* **Instagram:** Settings open on Ads and privacy now. Settings entry moved down next to backup and the other upkeep sections, after the everyday controls.
* **Instagram:** Pause and diagnostics folds like the other sections now, so Debug logging and the report rows don't fill the overview. It opens by itself while HushGram is paused, the settings are locked or a save was interrupted.
* **Instagram:** Settings opens with Search, the list view option and a compact category list with shorter descriptions. Quick setups and the active-status banner are gone. Larger text still wraps.
* **Tooling:** The patching help explains the small-heap limit and keeps Manager's embedded VM workaround.
* **Instagram:** Download file names support `{shortcode}`, using the parent post's code for carousel pages. Unknown codes are left out. Photo names use the template when it includes that token.
* **Instagram:** `Date in backup file names` adds the local date and time to settings exports. It starts off, keeping the existing backup name until enabled.
* **Tooling:** Updated to Morphe patcher 2.0.1 and its matching Smali library. Morphe Manager 2.2.0 or newer is required. The dependency audit separately reviews Morphe Desktop 1.19.1.
* **Instagram:** Added Traditional Chinese (Taiwan), contributed by JhouHank in #313. Traditional Chinese script preferences and Hong Kong or Macao locales use the new table.
* **Tooling:** GitHub release notes now read like Hushfacebook's. A short summary comes first, then plain grouped bullets with no Instagram label, the update steps and what was checked.
* **Tooling:** The bundle now carries Gson 2.15.0. The patch catalog comes out byte for byte the same, and new tests hold its key order, nulls and unescaped text, including emoji.
* **Instagram:** Hide the emoji row, Hide Follow on posts, Rounded corners on Reels and Thicker story progress bar now share one check before each frame. With several on, Instagram does that work once per frame instead of once for each.

## 0.0.16 (2026-10-24)

### Features

* **Instagram - Accent color:** `Material You` is a new choice in the `Color` list on Android 12 and newer. Buttons, links and highlights take your wallpaper's colors, in light and dark mode, after a restart. Asked for in #187 and #264.
* **Instagram - Accent color:** New `Badges in the accent color` switch under it, off to start. The red dots and counts for new messages and notifications take the color you picked, after a restart. Asked for in #315.
* **Instagram - Story text fonts:** new patch, off to start. Add .ttf or .otf files under `Your story fonts` in Stories, and they show up by file name in the story text editor after Instagram's own fonts. Asked for in #275.
* **Instagram - Story text fonts:** New `Emoji font in story text` switch under Stories, off to start. Turning it on asks for an emoji font file (.ttf), and the emoji you type in story text draw in it. Asked for in #275.
* **Instagram - Pick the app icon:** new patch. `App icon` under Layout swaps the home screen icon for HushGram's, or Instagram's glyph on black or plum. It changes once you leave Instagram. Starts on Instagram's own. Asked for in #284.
* **Instagram - Hide rows in Instagram's settings:** new patch. Open Instagram's Settings and activity once, then untick the rows you never use under `Rows in Instagram's settings` in Layout and they're gone from that screen. Every row shows to start. Asked for in #235 and #250.
* **Instagram - Full screen Reels and Home:** New `Full screen Messages` switch under Messages. Your messages and open chats hide the bars picked in `Bars to hide`, and a swipe in from the edge brings them back for a moment. Typing and Back work as usual. Asked for in #288.
* **Instagram - Full screen Reels and Home:** New `Fill the screen` switch under Full screen Reels, off to start. Tall reels that showed black bars above and below fill the screen instead, trimmed a little at the sides. Square and wide videos still fit. Reported in #288.
* **Instagram - Save profile picture:** New `Profile extras button` switch, off to start. It puts a small button next to the three dots on a profile that opens the profile actions you turned on. The three-dot menu keeps its rows. Asked for in #234.
* **Instagram - Change the like animation:** The `Like animation` list now shows a heart under it and plays each one as you tap it. OK saves the one you like, and Cancel keeps the old one. Asked for in #299.
* **Instagram - Stop Reels scrolling:** New `Take a scroll break` list under Reels, off to start. After 10 to 60 minutes on Home, Reels and Explore, a break screen covers them for 5 minutes. Messages, your profile and the tab bar still work. Asked for in #297.
* **Instagram - HushGram settings:** New `Lock these settings` row under Pause HushGram. Pick a 1 to 72 hour wait and every setting greys out, Pause included. `Ask to unlock` starts the wait (a tap stops it), then the page opens for 10 minutes. Asked for in #301.
* **Instagram - HushGram settings:** A small dot now marks settings added since you last looked, and the category row that holds them. It clears once you've been through that page. A fresh install shows none. Asked for in #274.
* **Instagram - Clean up Reels:** New `Hide the Friends tab on Reels` switch under Reels, off to start. It takes the Friends tab off the top of the Reels tab, and Reels moves over to fill the space. No restart needed. Asked for in #300.
* **Instagram - Hide the Reels tab:** New `Logo and titles at the start` switch under Tab bar, for accounts where Instagram moved the Home logo and bar titles to the middle. It puts them back at the start. Asked for in #310.
* **Instagram - Hide the Reels tab:** New `Hide the Messages tab` switch under Tab bar takes the Messages button off the tab bar, no restart needed. If Home's header has no Messages button, HushGram adds one so your chats stay a tap away. Asked for in #298 and #307.
* **Instagram - Hide suggested posts:** New `Hide the caught up card` switch under Feed, off to start. It folds the "You've seen the latest posts" card, so Home runs straight on into the rest of the feed. With no new posts, the card stays. Reported in #307.
* **Instagram - Download any video:** Details has a Copy caption without hashtags row for captions that have them. It drops a closing block of tags and the dots above it, and a tag inside a sentence keeps its word. Reported in #282.
* **Instagram - Swipe left to open the profile:** new patch, off to start. Turned on under Reels, a left swipe on a reel slides in the creator's profile, like Instagram used to. A right swipe or Back on that profile returns to the reel. Takes a restart. Asked for in #245 and #283.
* **Instagram - Full resolution photos:** New `Full size covers` switch under Feed. Reel covers and grid pictures load at full size, so Download cover saves 1080 pixels wide, not 240 to 480. It uses a lot more data, so it starts off. Takes a restart. Asked for in #214.
* **Instagram - Glass tab bar:** With `Liquid glass look` on, the active tab sits in a raised glass bubble that swells while it moves and bends what's behind it on Android 13 and up. New `Blur strength` row sets how much the pill blurs. Asked for in #289.
* **Instagram - Hide profile tabs:** `Tabs on profiles` is now two rows, `Tabs on your profile` and `Tabs on other profiles`, so Reels can go from yours and stay on everyone else's. Your old pick covers both until you change one. Asked for in #296.

### Bug Fixes

* **Instagram - Glass tab bar:** The outline Instagram draws round the message count and the dots on the tab bar is clear now, so it doesn't sit on the glass as a solid ring. The dot grows a little into the space the outline took. Reported in #315.
* **Instagram - Hide suggested posts:** `Hide 'Are you interested?' bubbles` takes the question off Reels too, the Not interested and Interested row under a reel. Before, only Home's bubble went. (#131)
* **Instagram - Glass tab bar:** With `Full screen Reels` on, the floating pill dropped by the navigation bar's height every time you switched to Reels, then jumped back up on the other tabs. It stays at one height on every tab now. Reported in #285.
* **Instagram - Glass tab bar:** A video playing under the glass pill no longer turns the pill black. Some videos play on a layer the blur couldn't see, so it blurs a small copy of the video's picture now. Reported in #144.
* **Instagram - Change the like animation:** The animation you picked plays on Instagram 452's feed and Reels hearts again, for a double tap and for the heart button. 452 had been showing its usual heart there. Reported in #299.
* **Instagram - View stories anonymously:** With `Gray out stories you've watched` on, stories you've watched now move behind the new ones in the story row at the top of Home too. Before, only the stories inside the feed were reordered. (#241)
* **Instagram - Read messages without the seen receipt:** With `Keep chats unread here` off, a chat you opened could still flip back to unread after a refresh. Now it stays read until a new message comes in. (#186)
* **Instagram - Download any reel:** The Details row in a reel's menu shows Instagram's info icon now. It used to wear Download's arrow. (#282)
* **Instagram - Download any reel:** A photo post opened on the Reels tab or from a hashtag page gets Details in its menu too, under Download. Until now only videos and photos with music had it. (#282)
* **Instagram - Start Home on Following:** With `Only accounts you follow` on, Back to home at the end of your Following feed takes you back up to the newest post. Before, the link did nothing.
* **Instagram - HushGram settings:** Opening an Instagram link or a notification while HushGram settings are up closes them, so you land where it leads. A link that arrives while they're still opening wins too. The tab used to change behind them.
* **Instagram - Clean up Reels:** With `Keep clear mode on`, starting a swipe back and letting the reel snap back into place could end clear mode on that reel and bring its buttons back. It stays in clear mode now. Reported in #233.
* **Instagram - HushGram settings:** With a HushGram PIN set, importing a settings file no longer calls `Lock your messages` and `Lock all of Instagram` unknown settings. Those switches stay as they are until you change them on the settings page.
* **Instagram - Download any video:** The Details row in a feed post's menu shows Instagram's info icon now, not Download's arrow.
* **Instagram - Hide suggested people on profiles:** `Hide Suggested for you on follow lists` now also takes the Suggested tab off other people's follow lists, and the Suggested for you block under their mutual followers. Before, it only reached the bottom of the followers and following lists. Reported in #302.
* **Instagram - Blur the bars around Reels:** The band behind the clock and the gesture bar's strip stayed black on Reels. Both show the reel blurred now, a little darker behind the clock under white icons so the time stays easy to read. Reported in #258.
* **Instagram - Sanitize sharing links:** Share and Copy link in Instagram's in-app browser move an Instagram page's link to your Sharing domain now, as the share sheet and Copy link on a post already did. Those two had still said instagram.com. Thanks to @Aholicknight for the fix in #304.
* **Instagram - Find the reel you came from:** A trial reel from someone else isn't on their profile, so the search used to keep scrolling and then give up. Once the grid has gone past the reel's date it stops and says the reel isn't there. Reported in #225.

### Improvements

* **Instagram:** With `Send downloads to another app` on, the first download after each start shows a short note saying this switch opened the share sheet and where to turn it off. It looked just like Instagram's own Share. Reported in #305.
* **Instagram - Upload stories at full quality:** The switch's description now says what you actually get. Video stories still go up at 1080p, but Instagram shrinks every story video to 720p on its servers, so viewers see only a small difference. (#37)
* **Tooling:** HushGram builds on Gradle 9.8.2 now, with androidx.annotation 1.11.0 and commons-lang3 3.21.0. Each new download was checked against its publisher's checksums before it went on the verification list. The patches themselves don't change.
* **Tooling:** The HushGram settings code is split into one file per category, so a row is easier to find. Every category lists the same rows in the same order, and search finds the same ones.

## 0.0.15 (2026-10-22)

### Features

* **Instagram:** New switch under Downloads, `Add the source to saved photos`, off to start. A saved JPEG photo carries the account that posted it, the post date and the post's link in its EXIF details. The picture doesn't change.
* **Instagram - Download any reel:** Reels on the Reels tab get Details and Copy caption in their menu now, the same as feed posts. (#282)

### Bug Fixes

* **Instagram - Hide Follow on feed posts:** The Subscribe button next to a creator's name on feed posts goes too now. On Reels it goes with Clean up Reels' follow switch. A creator's own profile keeps its button. (#248)
* **Instagram - Clean up Reels:** With `Keep clear mode on`, a long reel in clear mode no longer keeps its seek bar along the bottom. The bar comes back once you leave clear mode. (#277)
* **Instagram - Clean up Reels:** With `Keep clear mode on`, the buttons, author row and top bar could pop back up a few swipes in while a reel was still in clear mode. They stay hidden now, and come back when clear mode ends. Reported in #233.
* **Instagram - Hide the Explore grid:** Turning on `Hide suggested accounts in search` took your recent searches away too. Now it hides only the Suggested for you accounts. (#272)
* **Instagram - Turn off HDR brightness boosts:** On Instagram's armeabi-v7a builds (for 32-bit phones) the switch now turns off SDR super white and Ultra HDR always on too. Before, only the arm64 build had those two options turned off. (#85)
* **Instagram - HushGram settings:** The settings shortcut on the app icon no longer shows a second of Reels (or whatever tab Instagram opens on) before the settings come up. The screen stays black until they're drawn. (#55)
* **Instagram - Download any video:** Saved reels play with sound in more apps. Some reels carry only xHE-AAC sound, which many players turn silent. When the post also has a plain video file, the save keeps the best picture and takes that file's sound. Refs #242.
* **Instagram - Glass tab bar:** With the pill floating, a reel stops above the pill again, as in 0.0.13, so there's no wide black band over the video. The seek bar stays clear of the pill and the gesture handle. (#280)
* **Instagram - Glass tab bar:** A reel keeps one size while comments, the keyboard and a paused reel's controls come and go, and the glass doesn't draw the reel's video into its blur anymore. Both made a playing reel resize. (#222)
* **Instagram - Glass tab bar:** If the blur ever drops to the plain dark tint while you scroll, it comes back by itself a moment later. Before, it stayed that way until Instagram restarted. (#262)
* **Instagram - Start Home on Following:** Tapping Back to home at the end of your Following feed closed Instagram. Now it switches Home to For you, like picking it from the top menu. With `Only accounts you follow` on, Home just stays on Following.
* **Instagram - Pure black dark mode:** Stories are black around the edges too. The thin strip above a story and the bar behind its reply field kept Instagram's dark gray before. The story itself keeps its colors. (#279)

### Improvements

* **Instagram:** HushGram now patches Instagram 452.1.0.53.64 instead of 452.0.0.48.64. Get APKMirror's (arm64-v8a) (480-640dpi) (Android 9.0+) variant, build 385811006. The armeabi-v7a build (385811005) patches too, and so do the x86 and x86_64 builds (385811007 and 385811008), which Instagram is shipping again.
* **Instagram:** HushGram now builds on Morphe patcher 2.0.0, so update Morphe Manager to 2.0.0 or newer before patching. On a computer, use morphe-desktop 1.19.0 or newer. Patching with every patch on takes about 40% less time there. (#60)
* **Instagram - Find the reel you came from:** Trial reels (shown to people who don't follow the creator) aren't on the creator's profile, so the search could only give up. Tapping the creator of one now shows a short note and skips the search. Reported in #225.
* **Instagram - Download any video:** Skip what's already saved reads its history file in small, fixed amounts now. A damaged file can't slow a save down, and it gets written out fresh at the next save, so newer saves aren't lost behind the damage. (#150)
* **Instagram - Turn off double tap to like:** Patching takes about 4% less time with every patch on. The patch reads code only in the places its targets can be, instead of every method in the app. The patched app comes out the same. Refs #173.
* **Instagram - View stories anonymously:** Patching with every patch on takes about 3% less time and 5% less processor work. Its checks for other callers now read only the classes that can hold one. The patched app doesn't change. Refs #173.
* **Instagram:** HushGram launch screen and Emoji style now ask the patcher's index where their code is, instead of reading every method. With every patch on, that's about 3% less processor work. Same patched app as before. Refs #173.
* **Instagram:** Patches that find their code by a piece of text, or by Instagram's internal labels, now look it up directly instead of checking every string. Patching is about 2% quicker. The patched app is the same. Refs #173.
* **Instagram:** Copy comment and Save comment photo now find the comment menu from the patcher's index, where they used to read every method. Patching with every patch on takes about 4% less time, and the patched app doesn't change. Refs #173.
* **Instagram - View stories anonymously:** The patch does about 40% less work while patching, since its check for other callers asks the patcher's index a narrower question. Total patching time barely moves. The patched app is the same. Refs #173.
* **Instagram - Change the like animation:** Finding the feed's hearts now reads only the code that calls them, where it used to read the whole app. The patch does almost none of that work now, though total patching time barely moves. Same patched app. Refs #173.
* **Instagram - Install next to Instagram:** Finding where Instagram names its content providers takes one look through the app's text now, not one per provider. Patching with every patch on takes about 4% less time, and the patched app is the same. Refs #173.
* **Instagram - Lock your messages:** The patch finds the inbox's unread badge through the patcher's index now, instead of checking every method in the app. That part takes almost no time anymore, and the patched app doesn't change. Refs #173.
* **Tooling:** `scripts/native-runner.ps1` runs checks inside the installed patched Instagram on an offline emulator, as a throwaway signed-out Android user. Each verdict names the exact APK it ran on. Two controls (build identity, a missing hook) must hold before feature checks count.
* **Tooling:** The native runner's first feature checks cover Hide your active status. They build Instagram's own presence requests without sending them, and only Active may turn Idle, only while the switch is on and HushGram isn't paused.
* **Tooling:** The native runner checks View DM photos and videos anonymously on Instagram's receipt handler. Switched on, the handler finishes a stand-in receipt on the phone without building its request. Voice receipts, a paused HushGram and the switch off stay Instagram's.
* **Tooling:** Nothing goes public until the whole app has been walked with that exact bundle on a release phone or an emulator. `publish-release.ps1` stops before GitHub without a full, crash-free walk of that bundle.
* **Tooling:** `scripts/find-release-for-identity.ps1` reads the `hg1:` build identity from a diagnostics report and names the release it came from, or says the build isn't one of ours, so a repackaged fork shows up before anyone digs in.

## 0.0.14 (2026-10-20)

### Features

* **Instagram - Stop swipe up to reply:** New patch. A switch under Stories, off to start, stops a swipe up on someone's story from opening the reply box and its reactions. Tap Send message when you do want to reply.
* **Instagram - Hide the search bar in Messages:** New patch. A switch under Messages, off to start, takes the search bar, its Filter button and the Meta AI bar off the top of your messages. Notes, requests and chats stay. Reopen Messages to see it.
* **Instagram - Message box buttons:** new patch. `Buttons in the message box` under Messages lets you untick Gallery, Stickers or Voice message to take them off the box you type in. Every button shows to start, and the text box and Send always stay. (#217)
* **Instagram - Ask before a call:** a new switch, `Hide the call buttons in chats`, takes the voice and video call buttons off the top of a chat, so the name keeps the room. Off to start, under Messages. (#217)
* **Instagram - Show all recent searches:** new patch. A switch under Explore lists every recent search under the search bar, not just the first few. See all, the x on each row and `Don't save recent searches` work as before. Starts off. Asked for in #257.
* **Instagram - Hide profile tabs:** new patch. `Tabs on profiles` under Profiles lets you take the Reels, Reposts and Tagged tabs off profiles, yours and other people's. The grid always stays, and every tab shows until you untick it. Asked for in #254.
* **Instagram - Hide suggested people on profiles:** `Hide Featured in notifications` under Profiles takes the Featured section, the posts and threads Instagram picks for you, off the top of your notifications. Everything else there stays. It starts off and works apart from `Hide suggestions in notifications`. Asked for in #259.
* **Instagram - Hide group buttons on the share sheet:** `Buttons on the share sheet` under Sharing lets you untick round buttons like Add to story, WhatsApp, Copy link or Download. Unticked ones leave the row with no gap. Every button starts ticked, and ones Instagram adds still show. Asked for in #252.
* **Instagram - Open comments without the keyboard:** New patch. Comments open with the keyboard down instead of popping it up on its own. A tap in the comment box, or on Reply, still brings it up. The switch is under Comments and starts off. Asked for in #248.
* **Instagram - HushGram settings:** Each way into HushGram has its own switch under Settings entry. `Settings shortcut on the app icon` and `Open settings from the logo` both start on, and the last way still on stays on, greyed out. Asked for in SysAdminDoc/HushFacebook#146.
* **Instagram - HushGram settings:** `Hide the HushGram row in Instagram's menu` steps aside while another way into HushGram is on. A shortcut on a home screen app that can't show app shortcuts doesn't count.
* **Instagram - Clean up Reels:** `Stay dim when I tap` sits under Burn-in guard. Once the buttons fade, they stay faded on Reels whatever you tap or swipe, the next reel too, and come back when you leave Reels. It starts off. Asked for in #162.
* **Instagram - Glass tab bar:** `Keep the pill full size` stops the liquid pill from shrinking to two tabs as you scroll down, so every tab stays where it was. It starts off. Asked for in #256.
* **Instagram - Hide the Threads button:** `Hide the Create button` under Profiles takes the plus off the top of your own profile. The menu stays. It starts off. Asked for in #254.
* **Instagram - Hide the Threads button:** `Hide Share profile` under Profiles takes that button off your profile, and Edit profile stretches to fill the row. It starts off. Asked for in #253.
* **Instagram - HushGram settings:** `Switches Ghost mode turns` sits under Ghost mode and picks which switches it flips. The settings row, the button on Home and the long presses all follow it, and the rest stay as you set them. Asked for in #251.
* **Instagram - HushGram settings:** A tab's long press can turn Ghost mode on or off now, with a toast saying which way it went. Choose the tab under Ghost mode. It's off to start. Asked for in #255.
* **Instagram - Download any reel:** `Not interested button on reels` under Reels puts a Not interested icon in the column beside a reel. One tap does what Not interested in the reel's menu does. It starts off. Asked for in #191.
* **Instagram - Download any reel:** `Block button on reels` under Reels puts a Block icon beside someone else's reel. A tap opens Instagram's own block screen for that account, so you still confirm there. It starts off. Asked for in #191.
* **Instagram - Download any reel:** `Hide reels by sound` under Reels puts a music icon beside a reel. Tap it and the Reels tab skips other reels with that sound. `Hidden sounds` lists them so you can bring one back. It starts off. Asked for in #191.
* **Instagram - Hide suggested stories:** `Hide Add Yours cards between stories` sits under Stories and leaves out the Popular Add Yours and Join a trending Add Yours cards between stories. The Music for you card keeps its own switch. It starts off. Asked for in #111.
* **Instagram - Copy comment:** `Copy without the @names at the start` sits under Copy comment. When a reply opens with someone's @name, Copy skips it and copies just what they wrote. Names further in stay. It starts off. Asked for in #270.

### Bug Fixes

* **Instagram - View stories anonymously:** With `Gray out stories you've watched` on too, a story you've watched now moves to the back of Home's story tray instead of staying up front gray. Turn either switch off and the tray keeps Instagram's order. (#241)
* **Instagram - Hide the Reels tab:** While HushGram was paused, `Tab order` showed Instagram's own order and tabs instead of yours, and changing anything there saved over your order. It shows what you picked now.
* **Instagram - HushGram settings:** A setting two levels under a switch that's off greys out now. `Keep the pill full size` and the haptic style stayed tappable with the glass tab bar off whenever the switch right above them was on.
* **Instagram - Hide suggested stories:** With `Hide Suggested for you between stories` on, a full-screen Suggested for you page still came up between stories and after the last one. Instagram builds that page a different way, and it's left out now too. Reported in #154.
* **Instagram - Install next to Instagram:** The copy now also gives its own package name to the report Instagram sends the system about which app loaded its code, on phones that make that report. It was still naming Instagram. Seen while chasing #205.
* **Instagram - Glass tab bar:** Reels kept changing size as the pill slid away and back, and a paused reel's footer squeezed the video too. Reels stay one size behind the pill now, with the caption and seek bar just above it. Reported in #221 and #260.
* **Instagram - Glass tab bar:** A reel's buttons and caption fell back behind the pill once a sheet over the reel closed, like the More menu. They stay above it now. Reported in #221 and #260.
* **Instagram - Glass tab bar:** With `Show content behind the tab bar` on, a reel's comments, Share or More resized Reels twice, and reels could get stuck on their cover. Sheets sit above the navigation bar now, so the reel keeps its size. Reported in #222.
* **Instagram - Hide suggested people on profiles:** A profile's Follow and Message buttons, or Edit profile and Share profile on your own, still twitched for a split second whenever the profile refreshed. They hold still now. Reported in #220.
* **Instagram - Download any video:** `Convert to keep the full size` did nothing unless `Save videos other apps can open` was on too, so a reel still saved as AV1. It works by itself now. Reported in #236.
* **Instagram - Download any video:** Photos from the feed and from profiles failed with `Download failed` on phones with smaller screens, because every size Instagram sent looked like a thumbnail. The largest one saves now. Reported in #261.
* **Instagram - Hide suggested people on profiles:** `Hide suggestions in notifications` took single rows but left the Suggested for you section at the bottom of your notifications. That goes too now, and follow requests stay. Reported in #112.
* **Instagram - Hide suggested people on profiles:** The Follow suggestions row in your notifications stayed with `Hide suggestions in notifications` on. It's hidden now. Reported in #112.
* **Instagram - HushGram settings:** On Instagram Plus accounts, holding the feed logo opened Instagram's app icon picker and never HushGram settings. The logo opens HushGram settings on every account now, and taps still go to Instagram.
* **Instagram - Glass tab bar:** The floating pill blurred a flipped copy of the strip above it, so over a dark row it could still glow with the photo higher up. It blurs what's actually behind it now. Reported in #262.
* **Instagram - Clean up Reels:** Keep clear mode on could still drop out partway through a scroll, when Instagram readied the next reels before a swipe landed. It waits for the swipe to land now, and for buttons that show up late. Reported in #233.
* **Instagram - Tap to play:** Dragging the slider in the Notes or story music picker stopped the song for good, since Tap to play took the slider's pause for yours. The song keeps playing from the new spot now. Reported in #263.
* **Instagram - Tap to play:** Tapping a feed video itself, not its play button, started it with the play icon still drawn over it on some posts. The icon goes away while it plays and comes back when it stops. Reported in #229.
* **Instagram - Blur the bars around Reels:** With `Show content behind the tab bar` off, the glass pill sat on a black strip on Reels. The reel shows blurred round it now, like on Instagram's own tab bar. Reported in #258.
* **Instagram - Hide suggested posts:** Home could flash the Welcome to Instagram card for a few seconds after a while away or an account switch. It keeps its loading row while the posts from people you follow load now, up to six seconds. Reported in #126.
* **Instagram - Turn off HDR brightness boosts:** The switch also turns off two of Instagram's own options, SDR super white on HDR content and Ultra HDR always on, which can make some reels brighter than everything else. The diagnostic report counts each time. Reported in #85.

### Improvements

* **Instagram:** Disable analytics is gone. It sent Instagram's usage and crash reports to a dead address, which Instagram can notice. The contacts and location setup screens it skipped show again, and old settings files still import.
* **Instagram:** Open developer options is gone, along with the Developer category and its flag tools. An account running server flags Instagram never sent it stands out. Settings files that still hold the developer switches import fine.
* **Instagram - HushGram settings:** The diagnostic report shows the playback quality, font and reel tap choice you picked, not just whether their switches are on. Asked for in #85.
* **Instagram - Download any video:** When `Convert to keep the full size` leaves a VP9 or AV1 video unconverted, the diagnostic report now says why. Asked about in #236.
* **Instagram - Hide Meta AI:** `Hide the Muse card on profiles` is now `Hide the Muse card`, and it takes the Meet Muse card, with its Try Muse button, off Home too. Your other cards stay.
* **Instagram - Hide suggested stories:** `Stop loading stories` is now `Stop loading stories and highlights`, and its description says highlights on profiles stay empty while it's on. Searching settings for highlights finds it. From #244.
* **Instagram:** HushGram now patches Instagram 452.0.0.48.64 instead of 451.0.0.53.80. Get APKMirror's (arm64-v8a) (640dpi) (Android 9.0+) variant, build 385810270. The two armeabi-v7a builds, 385810231 and 385810269, patch too.

## 0.0.13 (2026-10-18)

### Features

* **Instagram - Hide Follow on feed posts:** New patch. Feed posts show a Follow or Subscribe button next to the author's name, and this takes it off. The name, time and menu stay. The switch is under Feed and starts off. Asked for in #248.
* **Instagram - Hide the comment emoji row:** New patch. The comment box has a row of quick emoji above it, and this takes it off. The box stays. The switch is under Comments and starts off. Asked for in #248.
* **Instagram - Hide Follow on stories:** New patch. Stories from accounts you don't follow show a Follow button at the top again, and this takes it off. The name, time and menu stay. The switch is under Stories and starts off. Asked for in #237.

### Bug Fixes

* **Instagram - HushGram settings:** Importing a settings file, or undoing one, shows Restart now when a choice needs a restart, and the message asks for one only while it's owed. Before, nothing offered the restart.

* **Instagram - Download any reel:** Download cover saved a small copy, 360 pixels wide, since Instagram stopped sending larger ones. When the cover is the reel's first frame, it's taken from the video now, at the video's full size. Reported in #214.

* **Instagram - Hide suggested people on profiles:** A profile's buttons keep their spacing when Instagram redraws the row during a refresh. With the switch off or HushGram paused, Instagram's own button sizes stay. Reported in #220.

* **Instagram - HushGram settings:** Flipping Pause HushGram, or turning HushGram back on from the card at the top, shows Restart now right away. It used to show nothing to tap. Restart now also stopped hanging on "Instagram isn't responding" when HushGram was paused. Reported in #146.

* **Instagram - HushGram settings:** The Brazilian Portuguese settings read more naturally, reworded by votsz. From #230.

* **Instagram - Clean up Reels:** With Burn-in guard faded, a tap that paused or played a reel lit the buttons up for up to a second before they dimmed again. They stay dim through the tap now. Reported in #162.

* **Instagram - Show if a profile follows you:** With the small label under the picture, the name and counts beside it slid down on some profiles. They stay where Instagram puts them now. Reported in #157.

* **Instagram - Glass tab bar:** With the glass bar on, opening a reel's comments, Share or More could leave the video stuck on its cover, or frozen while the sound played on. The reel underneath is left alone when a sheet opens now. Reported in #222 and #232.

* **Instagram - Clean up Reels:** Keep clear mode on missed some reels and left their buttons up when the next reel's controls showed up late. It waits for them now. Reported in #233.

* **Instagram - Find the reel you came from:** The search goes much further down a profile's reels before giving up, so an older reel many pages down is still found. It also waits out a slow page while Instagram's spinner shows. Reported in #225.

* **Instagram - Find the reel you came from:** A photo reel now works too. Instagram keeps those in a profile's posts instead of the Reels tab, so the search looks there for them. With Profile posts as a list on, the grid stays up for it. Reported in #225.

* **Instagram - Hide suggested posts:** With `Hide videos` or another Home switch on, a page with a post or two, or none at all, left Home on an endless spinner or the Welcome card. Home asks for the next page by itself now. Reported in #28.

* **Instagram - Hide suggested posts:** On Instagram 451 the "You've seen the latest posts" card draws the Suggested for you title itself, so it stayed with nothing under it. It goes away there too now, and Older posts stays. Reported in #104.

* **Instagram - Glass tab bar:** With Hide the tab bar on Reels, a reel given the room back once the pill slid away ran under the gesture bar or the navigation buttons, where its seek bar couldn't be dragged. It stops just above them now. Reported in #221.

* **Instagram - Glass tab bar:** On Android 17 the pill could stay over an open story and cover its reply box and reactions. It now steps aside whenever a story is open and comes back when you leave. Reported in #224.

* **Instagram - Glass tab bar:** With Hide the tab bar on Reels, dragging a reel's seek bar while the pill is away moves through the reel instead of only bringing the pill back. A tap there still brings it back. Reported in #221.

### Improvements

* **Instagram - Story ring size:** Ring size goes from 50% to 150% now, in steps of ten, and the list shows each size as a percent. A size you picked before stays the same. Asked for in #223.

* **Instagram - Tap a feed video to pause it:** The switch's description now says that tapping the paused video again plays it, which it already did. Asked for in #137.

* **Instagram - Read messages without the seen receipt:** With Keep chats unread here off, a chat you open now stays read on this phone until a newer message comes in. Before, it went back to unread once your messages refreshed. The sender still sees no Seen. Asked for in #186.

* **Instagram - Blur the bars around Reels:** The strip under the tab bar on Reels now shows the reel blurred instead of black, under the floating glass pill and on Instagram's own tab bar. Home and the other tabs keep their usual bar. Asked for in #197.

* **Instagram - Glass tab bar:** New `Tab bar corners` choice, right under Tab bar height. Rounder or Fully round gives the pill and its highlight rounder ends. Starts on Standard. The Liquid look is always round, so the choice rests while it's on. Asked for in #144.

* **Instagram - Glass tab bar:** `Slide across the tabs` works on Instagram's own tab bar now, with the glass off. Slide a finger along the tabs and a light box follows it. Let go and that tab opens. Asked for in #201.

* **Instagram - Full screen Reels and Home:** New `Bars to hide` choice, right under Full screen Reels. Full screen can hide both bars as before, or only the status bar at the top, or only the navigation bar at the bottom. Asked for in #231.

* **Instagram - Download any video:** New `Convert to keep the full size` switch, under Save videos other apps can open. A full size reel or story Instagram only has as VP9 or AV1 is converted to H.264 on your phone instead of saved smaller. Reported in #236.

* **Instagram - Download any video:** New `Choose carousel pages` switch. A carousel's menu and the download icon get Choose pages, right after Save all. Tick the pages you want and only those save, in order. Pages are numbered, with a small picture once it loads.

* **Instagram - Tap to play:** New `Wait after auto scroll` switch, under Where videos wait. With Instagram's auto scroll on, the reel it moves to waits for your tap too. Off, it plays reel after reel as before. Asked for in #175.

* **Instagram - Clean up Reels:** Two new switches under Reels, both off by default. One hides the Create (+) button at the top of every reel, the other hides Your algorithm on the Reels tab, and the bar closes up around them. Asked for in #177 and #217.

* **Instagram:** The diagnostic report now lists every card type the stories viewer checks, ads included, and how often Instagram asked to show the Music for you card. From #111 and #154.

* **Instagram:** What HushGram writes to Android's system log and its error toasts now leaves out tokens, signed links and files from your storage, the same way the exported report does. Error traces are checked too.

* **Instagram:** The diagnostic report listed Long press to zoom, Read messages without the seen receipt, Start Home on Following and Glass tab bar as off when only the switches under them were off. They show as on now.

* **Tooling:** An everyday push now builds once and runs only the tests its changes can reach, plus the checks that read every source. A change no test reaches stops the push. A release, or `HUSHGRAM_FULL_GATE=1`, still gets the full gate.

* **Tooling:** The release preflight now builds the bundle and runs every patch on the declared Instagram build, so a patch that stopped applying shows up in a few minutes rather than an hour into the release.

* **Tooling:** A push that changes where a patch hooks Instagram, but not `scripts/injected-mutation-contracts.txt`, now prints a one-line hint naming the patch. It never stops the push, and it shows on a skipped push too.

* **Tooling:** The release receipt now audits the Morphe Desktop JAR it patches with, tied to that file's SHA-256. Desktop's own Bouncy Castle and Guava are checked, and an advisory whose classes minimization removed isn't counted as exposure.

* **Tooling:** A Desktop advisory can only be accepted by a review that names the JAR's SHA-256, so a new Desktop gets read again. All five Bouncy Castle advisories the gate holds against Desktop 1.18.1 are reviewed as unreachable.

## 0.0.12 (2026-10-16)

### Features

* **Instagram - Download any story:** A video story you filmed gets Download as photo under Download now, which saves one still picture from it at full size. Download still saves the video. Asked for in #215.

* **Instagram - Download any reel:** New switch under Download, Download audio, off by default. It adds Download audio to a reel's menu, which saves just the sound as an M4A file to Recordings (Music on Android 11 and older). Asked for in #216.

* **Instagram - Hide the Reels tab:** New switch under Tab bar, Hide Messages on Home's header, off by default. It's for accounts that still have Instagram's Messages button at the top of Home and turned on Show the Messages tab, which leaves Messages in two places. Asked for in #210.

* **Instagram - Show if a profile follows you:** New switch under Profiles, Recent unfollowers, off by default. Scroll to the end of your own Followers list and anyone missing since last time shows under Who unfollowed you, with the date. Asked for in #208.

* **Instagram - Show if a profile follows you:** Recent unfollowers only reads the list Instagram already loaded, so nothing extra is asked of Instagram. The list stays on your phone, kept apart for each account, and Clear unfollower list wipes it. Asked for in #208.

* **Instagram - Story ring size:** Ring color has Teal now, a blue-green that stays easy to see on white and black pages alike. Asked for in #206.

* **Instagram - Hide reels by caption words:** New patch with a switch and a Blocked words list under Reels, off and empty by default. A reel in the Reels tab goes when its caption holds one of your words or phrases, hashtags included. Asked for in #192.

* **Instagram - Hide reels by caption words:** Case doesn't matter. Put a comma or a new line between words, so a list from Hushfeed pastes straight in. Reels you open from a profile, search or a chat still play. Asked for in #192.

* **Instagram - Hide reels by caption words:** Also on Home under the list hides posts on Home with those words in their caption too. That part needs Hide suggested posts in your build. Asked for in #192.

* **Instagram - HushGram settings:** HushGram's settings come in Italian when your phone is set to Italian. Like the other translations, it's a first draft that a native reader can still improve. Asked for in #194.

* **Instagram - Download any video:** New switch under Downloads, Save every page without asking, off by default. With Download button on feed posts on, a tap on a carousel saves all of its pages at once instead of asking which. Asked for in #190.

* **Instagram - Show a reel's exact time:** New switch, Show how long ago a reel was posted, off by default. It adds a line under the reel's time like 1d 2h 5m ago, in HushGram's language. Searching the patch's name finds both its choices now. Asked for in #195.

* **Instagram - Hide the Reels tab:** Tab order under Tab bar opens a new list, Show and reorder tabs. Check a tab to put it on the bar and drag its handle to move it. Reset brings back Instagram's tabs. Nothing changes until you tap OK. Asked for in #207.

### Bug Fixes

* **Instagram - Hide the Reels tab:** Always Home opens Home again with your tabs reordered in Tab order, and after you swipe Instagram away from recent apps. Both used to open Reels. Coming back while Instagram is still open keeps you where you were. Reported in #55.

* **Instagram - Hide the Reels tab:** Start tab set to Search, Reels or Profile opens on its tab with the tabs reordered too. The diagnostic report counts each start and why a start tab was skipped. Reported in #55.

* **Instagram - Keep a seek bar on Reels:** With Seek bar like DM videos or Bigger seek bar on, the played part and the handle slide along steadily. They used to jump about ten times a second, as often as Instagram updates it. Reported in #10.

* **Instagram - Glass tab bar:** With Show content behind the tab bar on, a reel's comment box sat under the navigation bar until the keyboard came up, and other sheets could lose their bottom row. Sheets get their usual space back now. Reported in #209.

* **Instagram - Keep a seek bar on Reels:** A new reel's seek bar starts at the beginning right away. It used to show the last reel's spot for a moment and then jump back to the start, which was most of what you'd see when swiping quickly. Reported in #10.

* **Instagram - Font:** A color emoji font picked as the Font shows in the emoji row above the comment box now, not only in the box. That row used to keep Instagram's old emoji pictures, even after a restart. Reported in #166.

* **Instagram - Font:** A text font or a black and white emoji font leaves that row alone, since it would draw it in black. Emoji the file doesn't have still look like your phone's. Reported in #166.

* **Instagram - Font:** The bio under a profile's name takes the font you picked too. Instagram draws it with its newer text system, which loaded its own font and skipped the swap, and other text drawn that way follows now as well. Reported in #166.

* **Instagram - Glass tab bar:** Once Hide the tab bar on Reels slides the pill away, the reel runs to the bottom of the screen instead of leaving a black band where the pill was. It makes room again when the pill comes back. Reported in #170.

* **Instagram - Hide suggested posts:** Hide videos, Hide photos, Hide carousels, Hide posts you've liked and Hidden accounts now catch suggested posts on Home too. Instagram 451 wraps each suggested post in a unit those switches didn't look inside. Found while working on #192.

* **Instagram - Hide the Reels tab:** With Hide the Search tab on, the Search button on Home's header opens search again on phones that swipe between tabs. It used to show a note asking you to turn the switch off. Back returns to Home. Reported in #204.

* **Instagram - Download any reel:** The Download button beside a reel now uses Instagram's own icon made for that column, the one Save's is drawn from, so it's the same size and line weight as the buttons above it instead of bigger and bolder. Reported in #198.

* **Instagram - Glass tab bar:** With the floating glass bar on, a chat's message box sat too low, half under the keyboard or behind navigation buttons. Chats keep the right space again, and leaving one no longer leaves a black strip under the pill. Reported in #196.

* **Instagram - Full screen Reels and Home:** With full screen on, opening Instagram straight onto Reels, or coming back to Reels from HushGram settings or a chat, could leave black strips at the top and bottom until you switched tabs. Reels fills the screen either way now. Reported in #200.

* **Instagram - Full screen Reels and Home:** With Full screen Home on, the first tab you opened from Home, like Messages or your profile, slid up under the status bar until you switched again. That tab leaves room for the bar now. Reported in #203.

* **Instagram - Show if a profile follows you:** On a profile with no name, the Follows you or Doesn't follow you label sat on top of the post and follower counts. It takes the line where the name would be now. Reported in #157.

* **Instagram - Full screen Reels and Home:** With full screen on, the keyboard covered a reel's comment box, which sat one navigation bar too low once the keyboard brought that bar back. It sits right above the keyboard now, with the glass tab bar on too. Reported in #209.

### Improvements

* **Instagram - Clean up Reels:** Pausing, playing or liking a reel with a tap, and tapping Save or Download, no longer bring Burn-in guard's faded buttons back. Other buttons, the caption, the bars and swiping to the next reel still do. Asked for in #162.

* **Instagram - Clean up Reels:** New Dim after choice under Burn-in guard sets how long it waits before fading, from at once to 10 seconds. It starts at 5, as before. Asked for in #162.

* **Instagram - Clean up Reels:** Burn-in guard dims a reel's seek bar now, and the time HushGram shows next to it fades along with it. The Not interested and Interested buttons under some reels fade too. Reported in #162.

* **Instagram - HushGram settings:** Searching for Download any video now also finds Download button on feed posts and the switch under it.

* **Instagram - Hide the Reels tab:** Listed under Interface in Morphe's patch list now, next to the glass tab bar, since most of its switches are about the tab bar. That keeps Reels short enough to scan with the new caption words patch in it.

## 0.0.11 (2026-10-15)

### Features

* **Instagram - Hide the Reels tab:** New switch under Tab bar, Hide the Home tab, off by default. Home leaves the tab bar and your Start tab opens in its place, from the icon, Back, links or notifications. It needs another Start tab on your bar. Asked for in #90.

* **Instagram - Glass tab bar:** New Slide across the tabs switch, off by default. Slide a finger along the pill and the highlight follows it. Let go and that tab opens. Taps and long presses work as before. Asked for in #136.

* **Instagram - Glass tab bar:** New Liquid glass look switch, off by default. The pill gets round ends and Search gets its own glass circle. Scroll down and the bar shrinks to your tab and Search. Scroll up or tap it to grow it back. Asked for in #65.

* **Instagram - Keep a seek bar on Reels:** New Seek bar like DM videos switch under Reels, off by default. Reels get the seek bar Instagram shows on videos in chats, with a big round handle and the time above it. It wins over Bigger seek bar. Asked for in #10.

* **Instagram - HushGram settings:** New Quick setups row turns on a Quiet or Privacy set of switches in one tap, or puts every switch back the way it started. Your locks stay put. A long press hides it until Settings backup brings it back.

* **Instagram - HushGram settings:** Long-press Instagram's feed logo to open HushGram settings, even while paused. Taps work as before. If Instagram has its own long press there, like its app icon picker, Instagram's wins.

* **Instagram - HushGram settings:** Report a problem opens a GitHub bug report with your versions, device and switched-on settings already filled in.

* **Instagram - Install next to Instagram:** New Mark the icon option, off by default. It puts a small HushGram badge on the second Instagram's home screen icon, and on the other icons Instagram lets you pick. Asked for in #145.

* **Instagram - Hide the Reels tab:** Two new switches under Tab bar, off by default. Center the name on profiles puts the username in the middle of a profile's top bar, and Center titles on other screens does the same for screens like notifications. Asked for in #140 and #161.

* **Instagram - Hide the Reels tab:** New switch under Tab bar, Messages button on Home's header, off by default. When Instagram moved Messages to your tab bar, a Messages button comes back at the top of Home and opens your messages like the tab. Asked for in #156.

* **Instagram - Hide the Reels tab:** New switch under Tab bar, Show the Activity tab, off by default. Activity, the screen behind Home's heart, goes on your tab bar before Profile, and Tab order can move it. Hide notifications on Home's header drops the heart. Asked for in #156.

* **Instagram - Hide the Reels tab:** New switch under Tab bar, Show the Messages tab, off by default. If Instagram left Messages off your tab bar, it goes back on before Profile, and Tab order can move it. Asked for in #160.

* **Instagram - Hide the Reels tab:** Start tab has a new Always Home choice. Instagram reopens on Reels when that's where you left it, and Always Home opens it on Home every time you start it from its icon. Asked for in #55.

* **Instagram - Show if a profile follows you:** New Follow label choice under Profiles. Under the counts keeps the chip where it was. Small, under the picture puts a smaller chip under the profile picture, or under the counts when there's no room. Asked for in #157.

* **Instagram - Clean up Reels:** New Burn-in guard, off by default. It fades a reel's buttons and bars after 5 idle seconds, and can also move the screen a step every 2 minutes. Asked for in #162.

* **Instagram - Clean up Reels:** New switch under Reels, Hide the reply bar on reels from chats, off by default. A reel you open from a chat loses its Reply to box, quick reactions and plus button. The chat itself stays the same. Asked for in #177.

* **Instagram - Clean up Reels:** New switch under Reels, Keep clear mode on, off by default. With it on, Instagram's clear mode carries from one reel to the next until you leave it yourself. Asked for in #138.

* **Instagram - Download any reel:** New switch under Downloads, Download button on reels, off by default. It puts a download icon under Save beside each reel, and a tap saves it like Download in its menu. Asked for in #178.

* **Instagram - Download any video:** New switch under Downloads, Skip what's already saved, off by default. Something you saved isn't saved twice unless you ask again within 30 seconds. It remembers your newest 15,000 saves, and Clear download history forgets the list. Asked for in #150.

* **Instagram - Long press to zoom:** New Keep the zoom open choice under it, off by default. The zoom stays up after you let go, so you can drag around and pinch from 1x to 4x. A tap or Back closes it. Asked for in #149.

* **Instagram - Read messages without the seen receipt:** New Keep chats unread here switch under it, on by default. A chat you open stays unread on this phone, along with newer messages in it, until you long press it and tap Mark as read. Asked for in #184.

* **Instagram - Lock your messages:** Locked and hidden chats belong to the account you picked them on, so a group two of your accounts share can be locked on one and open on the other. Each account's list and notifications follow that account.

* **Instagram - Hide suggested people on profiles:** Two new switches under Profiles. Hide Suggested for you on follow lists leaves that section off followers and following lists, and Hide Complete your profile takes those cards off your own profile. Asked for in #139 and #159.

* **Instagram - Hide suggested people on profiles:** New switches under Profiles, off by default. Hide People you don't follow back takes that section off your notifications, and Hide suggestions in notifications drops rows that only suggest something. Likes, comments and new followers stay. Asked for in #161 and #181.

* **Instagram - Hide suggested stories:** A new switch under Stories, Hide Suggested for you between stories, leaves out the cards of accounts to follow that Instagram slips between the stories you tap through. Asked for in #154.

* **Instagram - Story ring size:** New Ring color list under it. Pick Blue, Purple, Red, Orange or Gold for the rings of stories you haven't watched. Watched rings stay gray and Close Friends stays green. It starts on Instagram's own colors. Asked for in #176.

* **Instagram - Hide the Explore grid:** A new switch under Explore, Hide suggested accounts in search, keeps Suggested for you accounts out of what shows when you tap the search bar. Your recent searches stay. Asked for in #143.

* **Instagram - Find the reel you came from:** A Stop looking button shows while the grid scrolls, and tapping it leaves the grid where it is. A new switch, Wait for a tap to find it, holds the search until you tap Find the reel. Off by default. Asked for in #30.

* **Instagram - Glass tab bar:** New Space under the tab bar choice after Show content behind the tab bar. A little more or More lifts the floating pill higher off the bottom of the screen, and it keeps its size. Starts on Standard. Asked for in #87.

* **Instagram - Glass tab bar:** New Hide the tab bar on Reels choice, off by default. Pick 2, 3, 5 or 8 seconds and the pill slides away once a reel plays that long untouched. Tap the bottom edge or press Back and it's back. Asked for in #170.

* **Instagram - Show a reel's exact time:** New patch. A reel says when it was posted on a line under the author's name, like Oct 2, 3:45 PM. The switch is under Reels and starts off. A second switch adds the seconds. Asked for in #172.

* **Instagram - Tap a feed video to pause it:** New patch. A tap on the video playing in a feed pauses it in place instead of opening it in Reels. The switch is under Feed and starts off. Asked for in #137.

* **Instagram - Keep Reels auto scroll on:** New switch, Move on when a reel ends. The next reel plays instead of a loop, whether or not Instagram offers Auto scroll, and its switch can't stop it. Starts off, needs a restart. Asked for in #175.

* **Instagram - Font:** New patch. A Font list under Layout picks Instagram's font, your phone's font, or a .ttf or .otf file. Bold and italic stay, emoji keep their style, and a file that isn't a font changes nothing. Needs a restart. Asked for in #166 and #5.

* **Instagram - Hide AI-labeled reels:** New switch under Reels, off by default. Reels carrying Instagram's own AI label stay out of the Reels tab. Unlabeled reels stay, even if AI made them, and reels opened from search, profiles, Saved or chats still play. Asked for in #174.

### Bug Fixes

* **Instagram - Hide suggested accounts in Reels:** The Friends tab in Reels no longer fills up with people to follow when it has no reels for you. With the switch on you get Instagram's own empty Friends tab instead. Reported in #167.

* **Instagram - Hide suggested people on profiles:** With the Discover people button hidden, the buttons left in a profile's row, like Edit profile and Share profile, now stretch to fill it. A gap used to sit where the button had been. Reported in #87.

* **Instagram - HushGram launch screen:** Instagram's own logo and "from Meta" no longer flash on a gray screen after the HushGram logo. Instagram skips its opening animation, so HushGram's screen hands straight over. Reported in #5.

* **Instagram - Open developer options:** A long press of Home no longer leaves Instagram's spinner up for good. If the developer options haven't loaded after 12 seconds, the MetaConfig editor opens instead, with a toast saying why. Reported in #158.

* **Instagram - Hide suggested posts:** Hide posts from this account in a post's menu shows a crossed-out eye now, not Download's arrow.

* **Instagram - Hide the Threads button:** Instagram 451 moved the Threads link into a chip under the bio, with the Threads logo and the account's name. With the switch on that chip stays out now too, and the other chips next to it stay put.

* **Instagram - Hide suggested posts:** Hide 'Are you interested?' bubbles now also catches the bubble Instagram adds from a second part of its feed code, when the question comes without a subtitle. Reported in #131 and #182.

* **Instagram - Hide suggested posts:** Once you've seen the latest posts on Home and every suggestion after them is hidden, the empty Suggested for you title goes away. The Older posts link stays. Reported in #104.

* **Instagram - Hide Meta AI:** Search results open the reel you tapped again. Hiding Meta AI's summary had shifted later results onto earlier reels. The summary now folds away without changing their positions. Reported in #152.

* **Instagram - Turn off HDR brightness boosts:** Instagram gets no spare HDR brightness from the screen and treats it as a non-HDR screen, so it picks SDR video and drops its HDR look. Some reels used to light up anyway. HDR recording in the camera stays. Reported in #85.

* **Instagram - Glass tab bar:** Feed videos started with a tap stay in place. On some phones they dropped behind the tab bar, leaving a black gap. Reported in #136.

* **Instagram - Glass tab bar:** The bar keeps its glass appearance over videos. Its opacity and edge no longer change as videos scroll past. Video directly beneath the bar may show through without blur on some phones. Reported in #144.

* **Instagram - Glass tab bar:** With Show content behind the tab bar on, screens run to the bottom edge with no plain strip under the pill, and the pill sits above the gesture handle. Chats and the camera keep their usual space. Reported in #144.

* **Instagram - Glass tab bar:** With Show content behind the tab bar on, Reels stops above the bar again. Its seek bar, caption and buttons stay visible and take taps, and Home and Profile respond across the full pill. Refs #65, reported in #163 and #136.

* **Instagram - Glass tab bar:** Hide the tab bar as you scroll keeps the bar in place on every reel, where it used to slide away on short ones, and works on the Activity tab now. Reported in #170, refs #65.

* **Instagram - Glass tab bar:** Picking a new look, height or Space under the tab bar now says it needs a restart, and the Restart now bar offers one. Before, the pick seemed to do nothing until Instagram started again.

* **Instagram - Glass tab bar:** On accounts where Instagram puts a dark capsule behind the selected tab, that capsule no longer sits on top of the glass bar's own highlight.

* **Instagram - Glass tab bar:** With Show content behind the tab bar on, a reel's More button and name keep working after visiting HushGram settings. Instagram's seek-bar touch strip had landed over them and intercepted taps.

* **Instagram - Glass tab bar:** Closed screens are let go of again. Every restyled tab bar used to stay in memory with its screen and its blurred backdrop, so memory crept up each time Instagram rebuilt a screen with the bar on it.

* **Instagram - Hide the Reels tab:** The tab names in Tab order's list are easy to read now. They were dark gray on a dark list. Reported in #55.

* **Instagram - Hide the Reels tab:** The Ghost mode and Search buttons on Home's header take Instagram's own icon color. With Instagram's dark mode set differently from the phone's, they could come out dark on dark or white on white.

* **Instagram - Hide the Reels tab:** The Search button's note fits on screen when Search is hidden from a swipeable tab bar. It explains which setting restores Search, or when a restart is needed. Android previously cut the message short.

* **Instagram - HushGram settings:** Recovery and sign-in notices stay above settings, and Support stays last, including after a search. Native row sorting no longer reverses those positions.

* **Instagram - HushGram settings:** Enabled actions and choices keep their full color when first shown. Android's background tint pass no longer leaves them looking disabled until their state changes.

* **Instagram - HushGram settings:** Invalid Place and Sharing domain entries stay open with an explanation so you can correct them. Save and the keyboard's Done button use the same validation. Coordinates keep their order in right-to-left layouts.

* **Instagram - HushGram settings:** A keyboard or a D-pad reaches every row now. The arrow keys used to get stuck on the page itself after the search box, and the whole screen turned gray while they were.

* **Instagram - HushGram settings:** Search behaves with the keyboard up. The search box moves to the top, the keyboard no longer covers the results or Restart now, and Back ends the search on the page you started from.

* **Instagram - HushGram settings:** The HushGram row can now hide when settings opens through a long press on your profile's menu. Turning off both long press entries, or pausing HushGram, brings it back. Reported in #148.

* **Instagram - HushGram settings:** Importing a settings file no longer fails because of one choice this version can't use. That choice, or a sharing domain, place or tab order that doesn't check out, is skipped and counted while the rest applies.

* **Instagram - Keep a seek bar on Reels:** Bigger seek bar on reels now draws at about twice the standard thickness and runs through the handle. Only the played portion turns white. Reported in #10.

* **Instagram - Full screen Reels and Home:** Reels and Home now use the space left by hidden system bars. The empty strips at the top and bottom are gone. Reported in #147 and #14.

* **Instagram - Lock your messages:** The unlock prompt now waits until Instagram is the thing you're looking at. The cover still goes up right away, but pulling down the notification shade or swiping home doesn't bring the prompt up over them. Reported in #153.

* **Instagram - Lock your messages:** With the phone in a right-to-left language like Arabic or Hebrew, the cover stays over the messages. It used to slide to the right edge on a new layout, which could leave part of the inbox showing.

* **Instagram - Lock your messages:** If the HushGram PIN dialog goes away without an answer, say because Instagram closed the screen behind it, Unlock on the cover asks again right away. Before, it did nothing for about a minute.

* **Instagram - Lock your messages:** A message notification that arrives without its own small icon now loses its text like any other, instead of showing the message.

* **Instagram - Lock your messages:** A chat no longer keeps a placeholder name it saw before the chat loaded. The real name replaces it.

* **Instagram - Lock your messages:** Turning the phone while its own screen lock check is up no longer loses your answer. Unlocking there opens your messages, where it used to ask again. A setting you were changing at the time takes one more tap.

* **Instagram - Lock your messages:** Turning on Lock your messages or Lock all of Instagram no longer opens a chat you locked on its own. That chat stays covered until you unlock it, can't leave the list without the unlock, and its notifications stay hidden.

* **Instagram - Lock your messages:** HushGram settings stay out of recent apps once a lock is on. Settings left open while you stepped away are covered when the lock comes back, and ask for your unlock before they can be used again.

* **Instagram - Lock your messages:** A list, a set of choices or Set a PIN left open over settings closes when the lock comes back, without saving anything. Cancelling the unlock leaves the cover with its Unlock button instead of asking again straight away.

* **Instagram - Lock your messages:** A settings file or an Undo that arrives while Instagram is locked waits for the unlock, so a backup can't switch a lock off without it.

* **Instagram - Lock your messages:** With a HushGram PIN, making Lock again wait longer asks for the PIN every time, even when Instagram is already unlocked. A wrong PIN or Cancel keeps the wait you had, and a shorter wait still applies right away.

* **Instagram - Lock your messages:** A diagnostics report saved with Debug logging on no longer names the chats you lock, unlock, hide or show again. Names with a line break or unusual text-direction marks in them are left out too.

* **Instagram - See who a story mentions:** The mentions pill sits at the end of the name and time row, not under the name, so the header keeps its height on stories with mentions. A tap around the pill opens the list too. Reported in #151.

* **Instagram - View stories anonymously:** The Mark as seen button in a story's header is easier to hit. Its touch area is wider, and the header is no taller for it.

* **Instagram:** A failed download says what to check when it can tell. A dropped connection points to the connection, and a file cut short or not stored points to the connection and free storage. Anything else says "Download failed".

* **Instagram:** The Hide suggested stories and Hide Meta AI descriptions said they start off, but their main switches start on. They now say which switches start on, and a test checks every description's "Starts on" and "Starts off".

* **Instagram - Download any reel:** When Instagram gives no sizes for a cover, Download cover now goes by the size each address names, so a thumbnail listed first doesn't save in place of the full picture. From #155.

* **Instagram - Download any video:** Save all's result shows each of its lines in turn, so the lower quality and already saved notes aren't cut off on Android 12 and newer.

* **Instagram - Pure black dark mode:** The menus that slide up from the bottom are black now, a reel's menu included. They kept Instagram's dark gray before. Buttons keep their grays. Reported in #171.

* **Instagram - Find the reel you came from:** The search waits for a profile's first reels to load instead of giving up after four seconds, and waits at the end of the loaded reels for the next page. Diagnostics says why a search ended. Reported in #30.

### Improvements

* **Instagram:** HushGram now patches Instagram 451.0.0.53.80 instead of 450.0.0.50.77. Get APKMirror's (arm64-v8a) (480-640dpi) (Android 9.0+) variant, build 385713073. All eight 451 builds patch, the x86 and x86_64 ones included.

* **Tooling:** The fixture tests run against Instagram 451's eight builds. Facts that differ from build to build, like which builds look a string up in a shared pool instead of loading it, were measured again on 451.

* **Instagram:** Patching takes about 4% less time with every patch on. Finding Instagram's lookups in its shared string pools starts from the patcher's index now, instead of reading every class that uses a pool. The patched app comes out the same. Refs #173.

* **Tooling:** Device builds support separate keystore and key passwords, including an empty store password. The installer also picks one ADB executable when the path lists more than one.

* **Instagram - HushGram settings:** When a change needs a restart, a Restart now bar shows at the bottom of settings. A tap restarts Instagram and reopens settings on the page you were on, and nothing restarts without that tap. Asked for in #146.

* **Instagram - HushGram settings:** Hide inbox suggestions and Ask for larger photos now show Restart now when you change them.

* **Instagram - HushGram settings:** Search lists the settings named for what you typed before the ones that only mention it, and works inside categories too. Rotating keeps your page and place. Empty results explain why an install may not have a setting.

* **Instagram - HushGram settings:** Settings get clearer category descriptions, a rounded search field, pink icon tiles and compact switches. Focus, selection and pressed states look the same on cards, dialogs and actions, and everything stays readable at larger text sizes.

* **Instagram - Hide your active status:** Its description says the change applies the next time you come back to Instagram, instead of asking for a restart it doesn't need.

* **Instagram - Hide suggested accounts in Reels:** Diagnostics now names every other kind of unit Reels shows besides reels and ads. If a page of suggestions still gets through, a saved report says what it was.

* **Instagram - Lock your messages:** PIN forms scroll and keep their labels visible. The keyboard can move to confirmation or submit, incomplete entries don't spend an attempt, and a retry countdown enables entry when the wait ends. Closing a form clears its digits.

* **Instagram - Lock your messages:** Chats locked or hidden before this update stay that way on every account until you unlock them there. Their old names stay hidden until you open the chat again.

* **Instagram - Save profile picture:** Close and Save have their own space below the preview, clear of system bars and edge gestures. Save stays unavailable while the picture loads, with a visible message and a spoken label.

* **Instagram - Find the reel you came from:** Reel search actions share the same focus and press feedback as other controls.

* **Instagram - HushGram settings:** When HushGram pauses itself because Instagram kept crashing at start, a message now says so on the first screen. Every switch is off until it's turned back on, so Ghost mode and the locks would otherwise stop without a word.

* **Instagram - HushGram settings:** The two About rows are in Korean now, and two error messages read like the rest ("Couldn't" rather than "Could not"). Four Korean rows that spelled HushGram or Instagram wrong are fixed.

* **Instagram - HushGram settings:** Set when you patched uses plain names now. Re-signed build fix is Instagram accepts this build, and Version code raised is No Play Store updates. The last row only repeated the intro, so it's gone.

* **Instagram - HushGram settings:** Three switches have clearer names. Hide Accounts to follow is now Hide suggested accounts in Messages, Hide 'Are you interested' questions is Hide 'Are you interested?' bubbles, and Show a Reel seek thumb is Round handle on the seek bar.

* **Instagram - HushGram settings:** The Before you sign in card shows on the main settings page only, not atop every category, and Tap to hide this sits on its own line so translations don't run it into the last sentence.

* **Instagram - HushGram settings:** Recommended flags no longer shows Keep Reels auto scroll between sessions next to the Keep auto scroll on setting, unless you'd already set it. Then it stays so you can set it back.

* **Instagram - HushGram settings:** The Support HushGram row at the end of settings is just its name now. The line under it is gone, and a search for coffee or Ko-fi still finds it.

* **Instagram - Glass tab bar:** The bar has 12dp corners now instead of fully round ends, and the highlight behind the selected tab has 8dp corners. Tabs still respond in the same places.

* **Instagram - Hide the Reels tab:** TalkBack reads the Ghost mode button on Home's header as "Ghost mode" with an on or off state, the way it reads a switch, instead of a sentence about what tapping does.

* **Instagram - Accent color:** Mentions, hashtags and links in captions, comments, search results and bios take the color you picked, and so do the Activity tab's Follow buttons and a selected pill's pale blue. It works on Instagram's older look too. Asked for in #8.

* **Instagram:** The context HushGram shares between its features is always Instagram's application now, never a screen. A closed screen can't be held in memory through it, whatever hands it in.

* **Tooling:** Unreleased version headings now work with both the changelog checker and the release notes generator. Full-screen patch checks also verify control flow when jumps need a wider encoding.

* **Tooling:** The build moves to Gradle 9.8.1, fixing three high severity advisories in 9.8.0 (GHSA-xwqc-3h47-hg64, GHSA-mvvg-497x-hmj8, GHSA-j5m7-59rp-24f5). The dependency audit now checks Gradle itself too. Gradle only runs at build time, so the bundle was never affected.

* **Tooling:** The README is much shorter now, a user guide with the install steps, the settings, every patch and the supported builds. The long version, with every caveat and the build internals, is in the repository history.

## 0.0.9 (2026-10-11)

### Features

* **Instagram - Lock your messages:** New Unlock with choice. Pick HushGram PIN and a 4 to 8 digit PIN of your own unlocks your messages instead of your phone's lock. It stays on your phone and can't be recovered. Asked for in #134.

* **Instagram - Hide suggested posts:** New switch under Feed, Hide 'Are you interested' questions, off by default. It removes the bubble under a Home post that asks if you're interested, with no gap left and nothing answered for you. Asked for in #131.

* **Instagram - Show when someone was last active:** New patch with a switch under Messages, off by default. Active 5m ago under a name in your messages becomes the time itself, like Active at 3:42 PM. It uses the time Instagram already has, so nothing new is requested. Asked for in #132.

* **Instagram - Clean up Reels:** New switch under Reels, Hide the Save button on reels, off by default. The Save button and its count leave the buttons beside a reel, and the rest close up. Posts in your feed keep theirs.

* **Instagram - Download any video:** New switch under Downloads, Hold to download, off by default. Hold two fingers on a feed post or a reel for a second and it saves what's under them. Instagram's one-finger long press doesn't change.

* **Instagram - HushGram settings:** New switch under Settings entry, Open settings with a long press on your profile's menu, off by default. Holding the three lines on your own profile opens HushGram, and a tap still opens Instagram's menu. Asked for in #84.

* **Instagram - Hide the Reels tab:** New switch under Tab bar, Center the logo on Home's header, off by default. Instagram's logo sits in the middle of Home's top bar without the newer header's other changes. Asked for in #50.

* **Instagram - Rounded post corners:** New switch under Feed, Rounded corners on posts, off by default, with a small or large size. Photos, videos and carousels in Home and Following get rounded corners. Asked for in #135.

* **Instagram - Upload stories at full quality:** New patch that posts video stories at up to 1080 pixels wide and 9 Mbps instead of 720. Off by default, under Stories in HushGram settings. Asked for in #37.

* **Instagram - Hide paid partnership Reels:** New switch under Reels in HushGram settings, off by default, that keeps Paid partnership and boosted reels out of the Reels tab. Reels you open from search, a profile, Saved or a message still play. Asked for in #12.

* **Instagram - Accent color:** New patch that turns Instagram's blue into purple, pink, red, orange, green or teal after a restart. Off by default, under Layout in HushGram settings. Asked for in #8.

* **Instagram - HushGram launch screen:** New patch that shows HushGram's logo while Instagram starts on Android 13 and newer. Off by default, under Layout in HushGram settings. Asked for in #5.

* **Instagram - Find the reel you came from:** New patch. Tap a reel's author, open their Reels tab, and it scrolls to that reel and outlines it. Off by default, under Reels in HushGram settings. Asked for in #30.

* **Instagram - Open developer options:** MetaConfig now lists flag names instead of numbers, and Developer has a Recommended flags page of named On or Off switches that needs Allow importing overrides. Asked for in #91, #25, #110, #50, #37 and #13.

* **Instagram - Install next to Instagram:** New patch that installs a second Instagram beside the first, with its own data and sign-in. It starts unselected in Manager. Asked for in #34, #61, #73 and #121.

* **Instagram - Save comment photo:** It now covers a comment's GIF too, saved as a .gif in Pictures/Instagram, or as the animated WebP or MP4 when that's all Instagram has. Off by default, under Comments in HushGram settings. Asked for in #1.

* **Instagram - Full screen Reels and Home:** New patch that hides the status and navigation bars on Reels and Home, and a swipe from the edge brings them back. Both switches are off by default, under Reels and Feed. Asked for in #14.

* **Instagram - Long press to zoom:** New switch under Feed in HushGram settings, off by default. Press and hold a photo in a feed to see it twice as big, and let go to close it. Asked for in #63.

* **Instagram - Start feed videos with sound:** New switch under Feed in HushGram settings, off by default, that starts the first video on Home with its sound on. Your phone's ringer and volume still count. Asked for in #103.

* **Instagram - Glass tab bar:** Tab bar can now hide as you scroll, and has Tab bar opacity and Tab bar height lists. They're off or Standard by default, under Tab bar in HushGram settings, and a restart shows a change. Refs #87, #65.

* **Instagram - Back leaves Home:** New switch under Feed in HushGram settings, off by default. Back on Home then leaves like Instagram does at the top of the feed, with no scroll up and no reload. Asked for in #54.

* **Instagram - Start Home on Following:** New switch under Feed, Instagram logo on Home, off by default. It needs Start Home on Following and puts Instagram's logo where the feed's name would be. Asked for in #126.

* **Instagram - Hide the Reels tab:** Tab bar has new switches to hide Search, Create or Profile, a Start tab list for where Instagram opens and a Tab order list. Everything starts off or at Instagram's order. Restart Instagram to see a change. Asked for in #20, #55 and #90.

* **Instagram - Hide the Reels tab:** Tab bar has four new Home header switches that hide Create or notifications, or add a Search or Ghost mode button. All start off, and a restart shows them. Asked for in #70 and #118.

* **Instagram - Start stories with sound:** New switch under Stories in HushGram settings, off by default, that opens a story with its sound on. Your phone's ringer and volume still count. Asked for in #103.

* **Instagram - Control taps and volume on Reels:** New choice under Playback, A tap on a reel, that can pause or mute instead of Instagram's default. A switch below it keeps reels muted on the volume keys. Both start at Instagram's behavior.

* **Instagram - Blur the bars around Reels:** New switch under Reels in HushGram settings, off by default, that fills the bars around a reel that doesn't fill the screen with a blurred copy of it. Open Reels again after turning it on.

* **Instagram - Hide suggested posts:** New Hide posts you've liked switch and Hidden accounts list under Feed, off or empty by default. A post's menu has Hide posts from this account to add one. Posts leave Home on the next refresh.

* **Instagram - Show hidden like counts:** New patch. Turn on Show like counts the poster hid, under Feed, and the like row shows the number whenever Instagram's server sent it. Off by default.

* **Instagram - HushGram settings:** The end of HushGram settings has a new row, Support HushGram, that opens my Ko-fi page. It also turns up if you search for coffee or Ko-fi.

* **Instagram - Profile posts as a list:** New patch, off by default, under Profiles in HushGram settings. Opening someone's profile takes you to their posts as a scrolling list, and Back shows the grid.

* **Instagram - Hide your active status:** New patch with a switch under Messages, off by default, so people you chat with don't see Active now for you. Restart Instagram. Ghost mode turns it on too.

* **Instagram - Hide Meta AI:** Three new switches, off by default: Hide the Muse button on your profile, Hide Blend on reels and Hide the Muse card on profiles.

* **Instagram - Default playback quality:** New row under Playback to pick the playback quality with a long press on a tab, from Auto to Highest. Off by default. Asked for in [#93](https://github.com/SysAdminDoc/HushGram/issues/93).

* **Instagram:** Press and hold New message at the top of your inbox to turn all the Ghost mode switches on or off together. A tap still starts a new message.

* **Instagram - Show if a profile follows you:** New switch under Profiles, Put accounts that don't follow you back first, off by default. It puts them at the top of your Following list. Asked for in #40.

* **Instagram - Keep a seek bar on Reels:** New switch under Reels, Bigger seek bar on reels, off by default. It makes Instagram's seek bar thicker, with a round handle and the time above it. Asked for in #10.

* **Instagram - Lock your messages:** It can now hide single chats (Hidden chats) or lock them (Locked chats), under Messages in HushGram settings. Open a chat in Instagram first so it's on the list.

* **Instagram - Download any video:** New switch, Download button on feed posts, off by default. It puts a download icon beside Save under each post and needs Download feed videos or photos. Asked for in #97, #116 and #117.

* **Instagram - Hide the Reels tab:** New switch, Show the Reels tab, off by default. It puts the tab back after Home when Instagram built your bar without it, and Hide wins if both are on. Asked for in #120.

* **Instagram - Clean up Reels:** New switch under Reels, Hide the comment bar on all reels, off by default. The comment button still opens the comments. Asked for in #119.

* **Instagram - Clean up Reels:** New switch under Reels, Hide the top bar on the Reels tab, off by default. The row with the Reels title and camera goes away and the reel stays put. Reels opened elsewhere keep their bar. Asked for in #14.

* **Instagram - Glass tab bar:** New patch, from @sherifrahim. It draws the tab bar as a floating pill of frosted glass with a highlight that slides to your tab. Off by default, under Tab bar, and a restart shows it. Refs #89, #65.

* **Instagram - Saved on your profile:** New patch, from @sherifrahim. It adds a bookmark to your profile's tabs that opens Saved. Turn it on under Profiles in HushGram settings and restart Instagram. Refs #89.

* **Instagram - Stop background wake-ups:** New patch, from @sherifrahim. Two switches under Battery, off by default, stop the alarms that wake your phone in the background. Notifications still arrive. Refs #89.

* **Instagram - Hide suggested stories:** New switch under Stories in HushGram settings, Hide the Music for you card, off by default. Asked for in #111.

* **Instagram - Hide Notes on profile pictures:** New patch with a switch under Profiles in HushGram settings, off by default, that takes the Notes bubble off profile pictures, including the prompt to post one on your own. Notes in Messages stay.

### Bug Fixes

* **Instagram - Open developer options:** Import flag names now applies on every Instagram 450 build. On four of them (the x86 and x86_64 builds and two others) it was left out, so MetaConfig kept showing numbers. Asked for in #91.

* **Instagram - Lock your messages:** Ticking or unticking a chat under Hidden chats now shows in your inbox as soon as you go back to it. Before, the inbox kept its old rows until you pulled to refresh or a new message came in.

* **Instagram - Hide ads:** Sponsored posts inside a page of posts, like Home's Older Posts page and Explore's chain of posts, are gone too, and so are rows of several ads between posts. Diagnostics counts both.

* **Instagram - HushGram settings:** Typing in the settings search box no longer freezes Instagram on a slow phone.

* **Instagram - HushGram settings:** A settings export that hits an error now says Couldn't export HushGram settings and lets you try again. Recommended flags does the same.

* **Instagram - Hide suggested posts:** Home's For you feed no longer grows one post at a time when most of a page is suggestions. It asks for the next page sooner. Reported in #52.

* **Instagram - See who a story mentions:** The label now shows on stories in Instagram's older format, and tapping it opens the list without pausing or skipping the story. Refs #125.

* **Instagram - Download any reel:** Download as video on a carousel with music saves every page, in order, instead of just the first. Reported in #78.

* **Instagram - Hide suggested posts:** Hiding videos, photos, carousels, liked posts or accounts no longer shows the empty feed card while Home is still loading.

* **Instagram - Lock your messages:** The cover shows its whole title and the Unlock link again on a phone screen.

* **Instagram - Open developer options:** Export overrides no longer writes a file that Import overrides refuses. Reported in #67.

* **Instagram - Show if a profile follows you:** The chip under a profile's counts grows with your phone's font size instead of cutting its words off. Reported in #24.

* **Instagram - Hide Meta AI:** With Hide Meta AI in search and Home's bar on, a keyword search's results no longer end in an Ask a follow-up bar.

* **Instagram - Hide suggested posts:** Home no longer sits on gray loading boxes when your feed is all suggestions, like a new account that follows nobody. Asked about in #105 and #104.

* **Instagram - Keep in chat:** A view once or replayable photo or video you send now stays Instagram's own sent bubble instead of turning into an empty one. Refs #114.

* **Instagram - Pure black dark mode:** It now reaches the comments sheet, which stayed gray. Refs #108.

* **Instagram - Save profile picture:** Save profile picture and Copy username and bio now work in the newer pop-up list a profile's three dots open on some accounts. Asked for in #29.

* **Instagram:** HushGram does less background work. Full screen Reels and Home, Saved on your profile, Lock your messages and Find the reel you came from stop checking the screen when there's nothing to do. Reported in #129.

* **Tooling:** The release facts check reads the push gate's kept run again when the pre-push hook starts it in a fresh PowerShell.

### Improvements

* **Instagram:** The check that proves each hook landed in the right place now holds on all seven 450 builds, since it finds methods by what they load and call instead of names that change between builds.

* **Instagram:** HushGram settings open as a list of categories, and a tap opens one on its own page. A button under the status card switches to one long list. Thanks to @talhaeenss for #123. Asked for in #80 and #100.

* **Instagram:** HushGram settings look tidier. Each category's page starts with a line saying what it's for, and every row now has an icon. On the list of categories, the rows share one rounded card the way a section's rows do. Asked for in #130.

* **Instagram - Show if a profile follows you:** The chip under a profile's counts has a color for each answer, blue, green or red, darker on a light profile. Thanks to @talhaeenss for #124.

* **Instagram - Download any video:** Download all now waits its turn, with up to ten posts queued, so two posts' pages never mix in your gallery. Download all on reels works the same. Asked for in #115.

* **Instagram - Download any reel:** Download cover now saves the largest picture a post carries, so a reel with a custom cover never saves its first frame instead.

* **Instagram:** When a patch fails on a type it didn't expect, the diagnostic report now names both types.

* **Instagram - Lock your messages:** The cover's Unlock link is darker on a light screen, easier to tap and readable to screen readers.

* **Instagram:** Several patches now look up what they need once instead of every time the screen redraws.

* **Instagram:** The diagnostic report counts each kind of card the stories row loads, so a card no switch covers yet, like the Music for you card in #111, shows up by name.

* **Instagram - Hide suggested posts:** Hide shopping now also takes shop tiles out of Explore and the shopping bag off posts with tagged products.

* **Tooling:** The patched APK check holds Hide the comment bar on all reels' guard in the right place, and the Clean up Reels tests cover all seven Instagram 450 builds.

* **Tooling:** The patch tests no longer need Instagram 449 or 439 in the fixture folder, and read 450's declared build instead.

* **Tooling:** The all-builds patch run takes only the builds of the Instagram version the catalog declares, and names older ones as skipped.

* **Tooling:** The release preflight finds tests a code-only commit left stale before any Gradle run does, in under a second.

* **Tooling:** Patch tests run by area, so Gradle can reuse results for areas whose inputs haven't changed.

* **Tooling:** Release notes come out in the shape Morphe Manager draws, as short scoped bullets under their type heading, and the release check holds every bullet to that. Asked for in #86.

* **Tooling:** The release checks that apply every patch as one Instagram leave out Install next to Instagram, and its own run checks that the result is a different package.

* **Tooling:** The release notes check now stops a bullet that runs over 40 words, not counting its closing credit, so Manager's card stays short. Asked for in #106.

## 0.0.8 (2026-10-09)

* **Instagram:** HushGram 0.0.8 adds 1 patch, for 79 in all, and stays on Instagram 450.0.0.50.77 with Morphe Manager 1.34.0 or newer. All seven builds of that version patch: the arm64 ones (385611395, 385611400, 385611404, 385611431 and 385611438) and the x86 and x86_64 ones (385611439 and 385611440).

* **Instagram:** New patch, Hide suggested accounts in DMs. Turn on its switch under Messages and restart Instagram, and the Accounts to follow section at the bottom of your messages isn't built. Your chats and follow requests stay, and so does a section of people who follow you. It's in the default selection with its switch off. Asked for in #112.

* **Instagram:** Copy the commenter's username only shows up in HushGram settings when Copy username actually went into your build. On an Instagram build where it couldn't, Copy comment still works, the extra switch stays out of the way, and the diagnostic report says why.

* **Tooling:** The patch contract check can count a name Instagram asks its shared string pool for, on the rules marked for it. Some 450 builds (385611395, 400, 439 and 440) ask the pool for a few names that 438 loads itself, so the check turned down the story link parser, the setup screen presenter and the share sheet's target list there even with the patch in the right place. Other rules still count only names a method loads itself, so a pool ask elsewhere can't confuse them.

* **Tooling:** Release notes now have to open with a short What's new list of 5 to 8 one-line bullets, with the full list of changes under it. The notes builder won't write them without one, or with one that's grown too long to scan. Asked for in #86 and #106.

* **Instagram:** With Hide the Stories tray on, the tray could still show up at the top of Home for a few seconds after you watched a story you opened in DMs. When you come back to Home partway down the feed, Instagram floats a copy of the tray over it, and that copy now stays hidden too. Reported in #88.

* **Instagram:** Disable analytics is stricter about which saved address it treats as the crash reporter's. It only follows the address when it's saved right after it's built, so a later Instagram build that saves something else there won't have that value changed. Both places Instagram 450 saves it still qualify.

* **Tooling:** Hide ads has tests of its own. One checks that only an on switch with HushGram ready and not paused hides an ad. The other checks that each supported Instagram build has exactly one place where ads go into a feed, and that HushGram's check is the first thing that runs there.

* **Instagram:** Disable analytics won't apply to an Instagram build where it finds none of the addresses reports are sent to, even if it finds the contacts and location setup screens or Instagram's live event stream. Skipping those screens protects nothing by itself, and turning the stream off only moves events to the regular upload. When a build has only some of what Disable analytics or Sanitize sharing links work on, the switch's description in HushGram settings now says how many it covers.

* **Instagram:** Opening HushGram's settings from the launcher shortcut while Instagram was restarting its screen could crash Instagram, because the screen still had Instagram's startup look and couldn't draw text yet. The settings now wait until the screen is ready and then open.

* **Instagram:** View stories anonymously has a new switch, Gray out stories you've watched. It starts off, so a story you watch keeps its colored ring as it does now. Turn it on and a watched story turns gray and moves to the end of the row on your phone, while Instagram still isn't told you watched it. Asked for in #113 and on #92.

* **Instagram:** Sanitize sharing links now also cleans a link that's copied to the clipboard as a link item rather than as text, which kept its share id before. Thanks to @Aholicknight, who spotted it and sent the approach in #107.

* **Instagram:** With Hide suggested posts on, Home no longer sits on gray loading boxes after you switch to an account whose Home is all suggestions, such as a new account that follows nobody. Home used to remember every post it had kept since Instagram started, so the first account's posts kept it from ending. Now only Home's latest load counts. Reported in #104 and #105.

* **Tooling:** The Instagram internals reference now includes measured network traffic, background activity and unplugged battery discharge, with exact installed-build evidence and the limits of each measurement. The observation uses an installation reporting HushGram 0.0.5 and does not establish savings against stock or validate the current release.

* **Instagram:** Messages and notices are plainer too. Import and export results, save progress, the sign-in notice and the Developer page now say what happened and what to do next, without terms like "flag" or "cache". All six languages were updated.

* **Instagram:** The rows in HushGram settings now explain themselves in plain English. Each summary says what you'll notice and, where it matters, to restart Instagram to see the change. The German, Spanish, Indonesian, Brazilian Portuguese, Turkish and Korean text was updated to match.

* **Instagram:** Every patch description in Morphe Manager is rewritten in plain English. Each one says what changes, why you might want it, and ends with where its switch is, or that it works with no switch.

* **Instagram:** Morphe Manager's simple mode now picks 75 of the 79 patches, so you don't need Expert mode to get a feature. Every patch with a switch in HushGram settings is in, and one that comes in this way changes nothing until you turn its switch on. The 25 that joined are Clean up Reels, Don't send reel watch history, Download any video, Hide group buttons on the share sheet, Hide Reels in the feed, Hide suggested accounts in Reels, Hide suggested people on profiles, Hide that you're typing, Hide the Explore grid, Hide the Reels tab, Hide the Repost button, Keep a seek bar on Reels, Keep Reels auto scroll on, Loop a story, Read messages without the seen receipt, Remove the empty space at the bottom, See who a story mentions, Show a post's exact time, Show a story's exact time, Spoof location, Start Home on Following, Stop Story auto-advance, Tap to play, Turn off double tap to like and View stories anonymously. Change version code, Open developer options, Pure black dark mode and View DM photos and videos anonymously stay out, and the README says why.

* **Instagram:** Coming from an earlier build, 24 switches start off now where they used to start on once you picked their patch. Under Feed, Start Home on Following and Show a post's exact time. Under Explore, Hide the Explore grid. Under Reels, Hide Reels in the feed, Hide suggested accounts, Hide the Follow button, Hide creation and promotion pills, Hide friends' activity and comment previews, Don't send reel watch history, Turn off double tap to like, Hide the Reels tab, Keep a seek bar and Keep auto scroll on. Under Stories, Stop Story auto-advance, Loop a story, Show a story's exact time, See who a story mentions and View stories anonymously. Under Playback, Tap to play. Under Sharing, Hide group buttons and Hide the Repost button. Under Profiles, Hide suggested people. Under Layout, Remove the empty space at the bottom. Under Downloads, Download feed videos. If you used one and never changed its switch, turn it on again in HushGram settings, or import a settings backup you exported before updating. A switch you'd set yourself keeps your choice.

* **Instagram:** Each patch's description in Morphe Manager now says where its switch is in HushGram settings and whether it starts on or off, and the install steps end with opening HushGram settings to turn on what you want.

* **Instagram:** Expert mode groups the patches under 15 headings instead of 8, so Interface no longer holds 43 of them. The new ones are Ghost mode, Messages, Stories, Reels, Playback, Interaction and Profiles, and most match a section in HushGram settings.

* **Instagram:** Download any story now also looks at the type a story was posted as before it picks the menu's rows, the way Instagram's own story viewer tells a photo story with music from a filmed video. Instagram sends most photo stories with music as a video, and only some of them carry the flag the menu checked until now, so the rest got a single Download that saved the video. A story posted as a photo that arrives as a video gets Download as video and Download as photo. Plain photo and video stories keep their one Download, and the diagnostic report counts which kind of story each menu was for. Reported in #98.

* **Tooling:** The push gate, the release receipt, the dependency audit and the all-patches check wait their turn in the machine's build queue now, when it has one, instead of starting Gradle or the patcher straight away. The gate holds one slot from its first build to its last patch run, and a push made with `HUSHGRAM_ALLOW_RELEASE=1` goes ahead of everyday builds in the line. `BUILD_QUEUE_SCRIPT` names the queue and `HUSHGRAM_BUILD_WRAPPER` the script that runs Gradle. Without them the scripts run as they did before and say so.

* **Tooling:** The push gate runs its quick checks first. The catalog, both lints and the runtime tests go in one build, and the patch tests (most of a quarter hour) and the bundle come after it, so a lint slip or a stale catalog stops the push in a few minutes. A declared build with no fixture stops it before anything is built.

* **Tooling:** The push gate keeps its results when it removes its worktree, pass or fail. The test results, the bundle and its SBOM, each declared build's patch reports and its passing patch run go in a folder named for the commit, under `%LOCALAPPDATA%\HushGram\gate` unless `HUSHGRAM_GATE_CACHE` names another place, with a manifest of what the gate ran and how far it got. The newest three are kept. Before, a gate's test results went with its worktree, and the release check had to run the patch tests again.

* **Tooling:** `build-release-receipt.ps1 -FromGate` and `validate-release-facts.ps1 -FromGate` read the gate's run of the commit they check instead of doing its work again. The receipt takes the gate's bundle and SBOM when none was built here, and reads each declared build's patch run back when the gate made it with the same bundle, catalog, desktop CLI and APK. The release check takes the gate's test results, bundle and receipt when the checkout has none, and the index push passes `-FromGate` by itself. A run is read only when its manifest, tree, hashes and test counts still hold up for that commit, and every check runs on what's read. Anything else and the scripts build, test and patch as before.

* **Tooling:** The index push that follows a release doesn't build and test the release's sources all over again. When every file it pushes is an index file and the only lines changed since a commit the gate passed are the index, the README's latest release sentence and the bug form's version, the gate is skipped. The release switch, the commit checks, the suites and the release check against the published release still run. The gate runs anyway when the kept run doesn't hold up, or when it would patch a build here that the kept run didn't patch with the same fixture and desktop CLI.

* **Tooling:** The gate's kept run is held to more before a push or a release leans on it. An index push is gated in full when the fixture folder has changed since the gate ran, since a build added or replaced there changes what the patch tests read. The same goes when `pre-push.ps1` or `gate-evidence.ps1` changed after the run was made, or when a kept patched APK, patch report or merge no longer matches what the gate stamped. Two gates of one commit no longer write into the same folder. The second one stops until the first is done. Pruning still keeps the newest three runs, and failed runs go before passed ones, so a release's passed run survives a busy day of pushes.

* **Tooling:** When the fixture folder holds more than one copy of the Instagram version the bundle declares, the gate patches the declared build, picked by its version code. It used to take whichever file sorted first, so a second full bundle kept beside the declared one sent the patch step to the wrong build.

* **Tooling:** `scripts/release/` holds the helpers a release used to need by hand. `preflight.ps1` runs the quick checks in a few minutes before the source push (a clean checkout on main, every declared build in the fixture folder, the desktop CLI, the release facts, the CHANGELOG section and the patch tests that read no Instagram build) and says how long each took. `patch-all-builds.ps1` patches every Instagram build in the fixture folder with the whole catalog and reports the patches each applied and missed and the CLI's warnings, patching a build kept as a bundle from the bundle rather than its base split, and reading the gate's run back with `-FromGate`. `release_notes.py` builds the GitHub notes from a CHANGELOG section with every bullet in it, and `count_tests.py` counts the test results, the gate's included, and prints the index description's validation sentence. The release steps in CONTRIBUTING.md use all four.

* **Tooling:** A push that changes a PowerShell script has every tracked script parsed before anything else runs, and a script that doesn't parse stops the push. Most of them run only for a release or with a phone, so a slip such as a variable name run into a colon used to surface there first.

* **Instagram:** Sanitize sharing links now also takes off the share id Instagram began adding to copied post, reel and profile links under a different short name each time, such as ?obrf= or ?mdxt=. Every other part of the link stays as Instagram wrote it.

* **Instagram:** README's Troubleshooting has a section for an Instagram that feels slow right after a Root Mount install. Android's compiled copy of the app was made from Meta's code, so it's thrown out and Instagram runs uncompiled until it's compiled again, and the section gives the root shell command that does it straight away.

## 0.0.7 (2026-10-08)

* **Instagram:** HushGram 0.0.7 adds 3 patches, for 78 in all, and stays on Instagram 450.0.0.50.77 with Morphe Manager 1.34.0 or newer. Besides build 385611438, the other arm64 builds of that version (385611395, 385611400, 385611404 and 385611431) and the x86 and x86_64 ones (385611439 and 385611440) patch now too.

* **Instagram:** Show if a profile follows you now goes by what the profile screen itself heard, the same answer Instagram's own menu on that profile uses to offer Remove follower. On a few profiles the label said Doesn't follow you while that menu offered Remove follower, because the label read an older follow status Instagram had saved for the account. The saved status only counts now when the screen has no answer yet, and the chip under the counts goes by the same answer. Reported in #40.

* **Instagram:** Open developer options adds Import flag names under Developer. Pick a name list, such as a file in Instagram's own id_name_mapping.json format or a text file of `config=name` lines, and Instagram's MetaConfig editor shows those names in its list and its search in place of numbers like _23355. Searching by a config's number still works. The names stay in HushGram's private folder and only change the editor's labels, so Instagram's schema and overrides keep the numbers, and so does an override export. Remove flag names puts the numbers back. HushGram doesn't ship a name list. Asked for in #50, #67 and #91.

* **Instagram:** With View stories anonymously on, a story you watch keeps its colored ring and stays where it was in the tray. Before, the view was held back but Instagram still noted on the phone that you'd seen it, so the ring went gray and moved to the end of the row, which looked as if the view had gone out. A story you mark as seen still grays once its mark goes out. Reported in #92.

* **Instagram:** The other arm64 builds of 450.0.0.50.77, 385611395, 385611400, 385611404 and 385611431, patch the way 385611438 does. Each build is compiled on its own, and Instagram's compiler moves a different set of strings into shared tables in each one and puts some code in other classes, so on 385611395 six patches and the story links part of Sanitize sharing links couldn't find what they change, and on 385611400 the HushGram row couldn't find the Settings screen. They read those tables now and look where the code moved, so they find the same places on all five builds. Morphe Manager still calls the four an unsupported version, since it can only be told one build number for each kind of phone. Reported in #77. The x86 and x86_64 builds, 385611439 and 385611440, patch that way too. The same change lets the HushGram row find their Settings screen. Read messages without the seen receipt no longer needs a helper 385611440 doesn't have, and on 385611439 Sanitize sharing links finds the copy link parser and Turn off double tap to like finds all three comment rows. Start on x86 devices now lets those two builds run their code protection step, since their code is x86 and nothing gets translated. Reported in #95.

* **Instagram:** Download any video has a new switch under Downloads, Download video covers, that starts off. With it on, the menu of a feed post with a video, or of a carousel on a video page, gets Download cover. It saves the still picture you see before the video plays, at the largest size Instagram lists, the way Download cover on a reel does. Asked for in #94.

* **Instagram:** Hide Meta AI has a new switch, Hide Meta AI in the share sheet, that starts off. Turn it on and Meta AI's target, which some accounts see as Muse, is gone from the row at the bottom of the share sheet. The other targets stay where they were.

* **Instagram:** Turn off double tap to like has an On messages switch under it, off to start. With it on, a double tap on a message in a chat doesn't send a reaction. A long press still shows the reactions, and you can pick one there.

* **Instagram:** Copy comment can also copy the username of whoever wrote a comment. Turn on Copy the commenter's username under Comments, then open a comment's menu and pick Copy username. It starts off and copies the name exactly. Asked for in #35.

* **Instagram:** Settings entry has a new switch, Hide the HushGram row in Instagram's menu, for people who open HushGram from a tab long press and don't want the extra row at the top of Instagram's Settings and activity. It starts off. The row only goes while the tab you picked can open HushGram, so turning the long press off, or pausing HushGram, brings it back and there's always a way in. Asked for in #84.

* **Instagram:** With Hide suggested posts on, Home could open on Instagram's Welcome to Instagram card for a few seconds, the one it shows when you follow nobody, before your posts loaded. A suggestion taken out anywhere in the app, or from what Home saved last time, was enough to make Home look finished while it was still waiting for its first page. Now only suggestions taken out of Home's own posts can end it, so it shows its usual loading placeholder until your posts arrive. Reported in #28.

* **Instagram:** Open settings with a tab long press works as soon as you pick a tab. Before, the choice saved but only reached the tabs after Instagram restarted, so until then the tab's long press still did what it always had, which looked like the choice hadn't stuck. Profile could also go on opening Instagram's account switcher, even after a restart, because Instagram puts that long press on the Profile button a second way. That one follows your choice now too. Reported in #82.

* **Instagram:** Download cover no longer fails with Download failed on a reel whose cover address has its size written into it, which the photo save mistook for a thumbnail. It saves the largest size the reel lists for its cover. Reported in #79, and thanks to @talhaeenss for finding the cause in #81.

* **Instagram:** Download any reel saves every page of a carousel that turns up in Reels, in order. Before, it saved the first page whichever one you were looking at. Download as photo on a carousel with music saves them all too. Reported in #78.

* **Instagram:** On Android 14 and newer, Turn off HDR brightness boosts also holds back the extra brightness Instagram asks for when it draws a video on a layer of its own. That layer shows the video brighter than the rest of the screen, and the switch didn't reach it before (#85).

* **Instagram:** New patch, Hide the Threads button. Turn on its switch under Profiles and the Threads button at the top of a profile, yours or anyone else's, isn't built at all, so there's no gap where it was. The menu and the other buttons stay put. It's in the default selection with its switch off. Asked for in #50.

* **Instagram:** New patch, Emoji style. Instagram already loads Google's emoji font through Google Play services, but it only uses it for emoji your phone's own font is missing. Turn on Google's emoji everywhere under Layout and every emoji Instagram runs through Android's emoji support draws in Google's style instead. It's in the default selection with its switch off, and a change shows fully after Instagram restarts.

* **Instagram:** New opt-in patch, See who a story mentions. A story that mentions someone gets a pill under the name in its header, like 2 mentions, even when the mention sticker is hidden or off screen. Tap it to see who's mentioned, with their picture, name and username, and tap someone to open their profile. Its switch is under Stories. Asked for in #13 and #44.

* **Instagram:** Hide suggested posts gets Hide videos, Hide photos and Hide carousels under Feed, and they start off. Each takes every post of its type out of Home, from accounts you follow too, and leaves profiles, Explore and Reels as they were. Reels count as videos.

* **Instagram:** Save profile picture has a third switch under Downloads, Copy username and bio, that starts off. With it on, the menu on someone's profile also gets Copy username and Copy bio, which copy the account's username or bio exactly as it's written, emoji and line breaks included. An account with no bio gets no Copy bio. Asked for in #29.

* **Instagram:** With Name saves by account and post time on, a saved profile picture is named for the account and the time you saved it, like `username_profile_20261007_105151.jpg`, instead of IG_IMG_. Off, it keeps the IG_IMG_ name.

* **Instagram:** Hide the comment bar on reposted reels now keeps the bar hidden after Instagram tucks it away for a moment and brings it back, which some reposted reels do and which used to put the bar back under them. Asked for in #64.

* **Instagram:** Save profile picture has a second switch under Downloads, View profile picture, that starts off. With it on, the menu on someone's profile also gets View profile picture, which opens their picture full screen at the largest size Instagram has, with pinch zoom and a Save button. Back closes it.

## 0.0.6 (2026-10-07)

* **Instagram:** HushGram 0.0.6 adds 27 patches, for 75 in all, and moves to Instagram 450.0.0.50.77 (build 385611438, arm64-v8a) on Android 9 and newer. It needs Morphe Manager 1.34.0 or newer.
* **Instagram:** New patch, Download voice messages, in simple mode with its switch off. Turn it on under Downloads and holding a voice message in a chat brings up Save, which keeps the recording as an M4A audio file in Recordings/Instagram, or Music/Instagram before Android 12. It only shows on a voice message Instagram marks as one to keep, so one sent to be played once never gets it. Asked for in #20.

* **Instagram:** Show if a profile follows you has a new switch under Profiles, Show it as a chip, that starts off. With it on, the answer sits in an outlined chip under the profile's posts, followers and following counts instead of the gray line by the name, and when you follow each other it says Following each other. The label by the name stays the default. Asked for in #24.

* **Instagram:** Clean up Reels has a fourth switch, Hide the comment bar on reposted reels, that starts off. With it on, a reel you open from a profile's reposts has no Add comment bar under it, and the comment button still opens the comments. Asked for in #64.

* **Instagram:** Details in a post's menu now has Copy username and Copy caption under the post's details. They copy who posted it and the caption exactly as it was written, emoji and right-to-left text included, and each shows only when the post has one. On a carousel the caption comes from the post itself. Asked for in #29.

* **Instagram:** Save comment photo now follows Name saves by account and post time. A comment's photo is named for the person who wrote the comment and when they wrote it, like `username_20261005_143012`, and Folder per account files it under that person's name. A comment whose author or time can't be read keeps the usual `IG_IMG_` name. Asked for in #20.

* **Instagram:** Ask before a refresh now also asks on Home, in your messages inbox and the other lists that pull down with Instagram's newer refresh layout. The question comes up once a pull goes far enough to refresh, and Cancel puts the spinner away there too.

* **Instagram:** Resume long videos forgets a signed-out account's places in the background, so signing out never waits on its file.

* **Instagram:** Resume long videos doesn't save a place again for an account that just signed out. Its videos close a moment after the sign-out clears its places, and that close used to put one back.

* **Instagram:** Download any video has a new switch under Downloads, Details in a post's menu, that starts off. With it on, a feed post's menu gets Details, which shows when the post went up, who posted it, its media ID and the size Download would save, with Copy media link to put the post's file's direct address on the clipboard. When Download would build a sharper video from the picture and sound Instagram streams separately, Details shows that size and says the linked file's own. On a carousel it shows the page you're on.

* **Instagram:** Clear the media cache leaves a clear over the limit for a later start when the last one crashed early, since that start might be the one that turns safe mode on.

* **Instagram:** New patch, Save profile picture, in simple mode with its switch off. Turn it on under Downloads and the menu on someone's profile gets Save profile picture, which saves their picture at the largest size Instagram has.

* **Instagram:** Show a post's exact time, a new patch you pick in Manager. A post in your feed says when it went up, like Oct 2, 3:45 PM, instead of 3 hours ago, and each comment says when it was written instead of 3h, in your phone's language and 12 or 24-hour setting. Its switch sits under Feed and starts on.

* **Instagram:** Resume long videos forgets an account's places as soon as that account signs out or is removed from Instagram, and a resume that was about to happen when you switch accounts is dropped, so a video on the new account never jumps to the old account's place. Places of an account you only switched away from stay for when you're back.

* **Instagram:** Name saves by account and post time, a new switch under Downloads that starts off. With it on, every photo and video you save is named for the account that posted it and the time the post went up, like `username_20261005_143012`, so an account's saves sort by date. Each carousel page gets its number on the end, and saving the same thing twice adds the time of the second save instead of overwriting or piling up numbered copies. A save that doesn't know who posted or when keeps its usual name. Asked for in #20.

* **Instagram:** Clear the media cache now reads its switch even when Instagram opens its video cache before HushGram's settings are loaded, so a clear left for the next start isn't put off start after start.

* **Instagram:** After you tap Call, Ask before a call lets nothing else through. Instagram asks for the microphone and camera on its call screen once the call has started, so there's no repeat to wave through, and a tap on a call button right after you hang up is asked about like any other.

* **Instagram:** A call question left up on one screen no longer holds call taps on a screen opened over it, like a chat you open from a notification. That tap gets its own question and the old one closes.

* **Instagram:** Ask before a call no longer stops every later call when its question was left behind by a screen that closed without dismissing it, as switching dark mode while it was up can do. The next call is asked about again.

* **Instagram:** Open in another player, a new switch under Downloads that starts off. With it on, a reel's menu and a feed video's menu get Open in another player next to Download, which opens Android's chooser so a player such as VLC plays the video. A reel gets the row with Download on reels off too, even one Instagram won't let you download. The player gets the same file a save would pick at your Download quality.

* **Instagram:** New patch, Ask before a like, in simple mode with its switch off. Turn it on under Feed and the Like button under a post waits for a question before it likes or unlikes the post, so a stray tap doesn't. Continue goes ahead and Cancel doesn't.

* **Instagram:** New patch, Ask before a refresh, in simple mode with its switch off. Turn it on under Feed and pulling down to refresh Home, Reels or another list asks first. Refresh reloads it, and Cancel stops the spinner and keeps what's on screen.

* **Instagram:** Patching is a lot quicker again. The patches that still read all of Instagram's code to find their spots (the seen receipts, typing, comments, downloads, Repost, sharing links, auto scroll, the seek bar, the Reels tab, the story ring and tray, developer options, analytics and the shortcut calls) now ask the patcher's index which classes to read first. On a desktop with Instagram 450, running every patch went from about 48 to 56 seconds down to 13 to 15. In Morphe Manager on a Galaxy S22, patching every default patch now takes under a minute, where the last build took about three minutes for fewer patches. Reported in #60.

* **Instagram:** New patch, Ask before a call, in simple mode with its switch off. Turn it on under Messages and a call started from a chat waits for a question first, so a stray tap on a call button doesn't ring anyone. Call starts it and Cancel doesn't.

* **Instagram:** Clear the media cache no longer deletes the videos at the next start when you've turned its switch off since the clear that asked for it. Videos you asked to go with Clear now still go at the next start either way. A video cache that's a link no longer leaves its request behind for every later start.

* **Instagram:** After you tap Call, Ask before a call lets only that call through without a second question, and only once: Instagram coming back to start it again goes ahead, while a call to someone else or the other kind of call is asked about even right after. A second tap while the question is up waits for it instead of opening another, so two quick taps can't start two calls.

* **Instagram:** Ask before a call now hands the call's video flag to its check as a number, the way every other hook takes a yes or no, so Android's verifier has nothing to refuse when the chat's call code loads.

* **Instagram:** Sanitize sharing links now reaches Share and Copy link in the menu of Instagram's in-app browser. The address of the page they hand out loses fbclid and every utm_ key, on any site, which is what an ad's page opens with. The page itself still loads with them, and any other key stays.

* **Instagram:** New patch, Change the like animation, in simple mode with its switch off. Turn it on under Reels, pick one of the animations Instagram made for Instagram Rings creators, and the heart that pops up when you double tap a post plays it.

* **Instagram:** Open links in external browser checks a link again after it's unwrapped from Instagram's and Facebook's click trackers, right before it goes to your browser. Only an http or https address that names a host leaves. One with no host, or with a scheme that only starts with http, stays in Instagram's own browser.

* **Instagram:** Sanitize sharing links has a Sharing domain row, blank to start. Type a domain there and links to instagram.com that you copy or share go out on it, for sites that show Instagram posts and reels in chat apps.

* **Instagram:** Open developer options checks, when you patch, that what Export and Validate call into Instagram reads overrides and never writes them. It follows each call four steps deep, overrides included, and leaves Export, Validate and Import out of the build if any step writes a file, makes a folder or changes or reloads overrides.

* **Instagram:** Open developer options keeps its long press and its MetaConfig and Whitehat rows when an Instagram update moves what Export, Validate or Import use. Only the rows that need the moved part are left out, and the diagnostic report says which.

* **Instagram:** New patch, Hide the Share button, in simple mode with its switch off. Turn it on under Sharing and the Share button and its count come off the posts in your feed and off reels. Asked for in #1.

* **Instagram:** Turn off double tap to like has an On comments switch under it, off to start. With it on, a double tap on a comment doesn't like it.

* **Instagram:** Resume long videos keeps each signed-in account's places apart, so a video another account on the phone left partway starts at the beginning for you. Places saved before this update are cleared once, since they don't say which account they belong to.

* **Instagram:** New patch, Hide comments, in simple mode with its switch off. Turn it on under Comments and the Comment button and comment count come off the posts in your feed.

* **Instagram:** Stop loading stories, a new switch under Stories that starts off. With it on, nothing in the row of stories at the top of Home loads, your own story included, which saves data. A story ring on a profile or in a chat still opens its stories.

* **Instagram:** Hide suggested stories has two more switches under Stories, both off to start. Hide story rewinds takes the rewind cards that bring back old highlights out of the row of stories at the top of Home, and Hide memories and recaps does the same for Instagram's memories, your week, the year in review, follow anniversaries and birthday cards. Stories people post stay either way.

* **Instagram:** Lock your messages is harder to get around. When it locks again, message notifications already in the shade lose their text, and the update Instagram posts after an inline reply is hidden too. On Android 9 to 12 an open inbox or chat now stays out of the recent apps picture, which also keeps screenshots of it out while it's open. Pausing HushGram or safe mode no longer turns a lock off. While paused, a lock that's on covers all of Instagram. A second Instagram screen opening over a locked one doesn't uncover it anymore, screen readers skip what's under a cover, and Android 10 asks through the phone's own lock screen check, which also takes over whenever the prompt over Instagram can't ask.

* **Instagram:** Open categories as pages, a new switch under Settings entry that starts off, lists HushGram's settings by category and opens each one on its own page. Back returns to the list where you left it, and search still looks through every category (#19).

* **Instagram:** New patch, Turn off HDR brightness boosts, in simple mode with its switch off. Turn it on under Playback and HDR photos and reels stop brightening the screen above everything else (#26).

* **Instagram:** Folder per account, a new switch under Downloads that starts off, puts each save in a folder named for the account that posted it, inside your save folder (#20).

* **Instagram:** Send downloads to another app, a new switch under Downloads that starts off. With it on, Download on a reel, a feed post or a story opens the share sheet with the item's link, for a downloader app such as Seal or YTDLnis, instead of saving it here.

* **Instagram:** Download any reel has a Download cover switch under Download on reels, off to start. With it on, a reel's menu gets Download cover under Download, which saves the still picture Instagram shows before the reel plays at its largest size (#48).

* **Instagram:** New opt-in patch, Spoof location. Set a place under Ads and privacy and turn it on, and Instagram is told the phone is there, for the location sticker, nearby places and maps. Photos keep their own places.

* **Instagram:** New patch, Don't save recent searches, in simple mode with its switch off. With it on, what you open from search stays out of Recent, both in the app and on Instagram's side.

* **Instagram:** New patch, Data saver, in simple mode with its switch off. With it on, photos load at a smaller size and videos, reels and stories start at the lowest quality, on mobile data only unless you turn that second switch off.

* **Instagram:** Stop Reels scrolling has a second switch, Stop after 20 reels, off to start. After you swipe to 20 new reels in a session, swiping in Reels stops until Instagram has been in the background for 15 minutes. Going back to a reel you've seen doesn't count again, and turning the switch off gives the swipes back at the next touch. A reel you open from a message still plays.

* **Instagram:** New patch, Clear the media cache, in simple mode with its switch off. With it on, once Instagram goes to the background with more than 500 MB in its image and video caches, the images are deleted then and the videos the next time Instagram starts, before its video player opens them. Nothing else in its cache folder is touched. A Clear the cache now row does it at once and shows what it freed. Your sign-in, drafts and settings stay.

* **Instagram:** New patch, Group Instagram's notifications, in simple mode with its switch off. With Group notifications on, every notification Instagram posts joins one group that shows how many it holds, so they stop filling the shade. Group by type gives each kind its own group. Tapping a notification still opens what it did.

* **Instagram:** New View live anonymously patch. With its switch on under Stories, a live you watch doesn't put you on the host's viewer list, because the heartbeat that says you're watching never goes out. Commenting or reacting still shows you, and your own lives count their viewers as before. It's in simple mode with the switch off, and Ghost mode turns it with the other switches.

* **Instagram:** HushGram settings have a Ghost mode switch at the top of Ads and privacy. One tap turns View stories anonymously, View live anonymously, Read messages without the seen receipt, View DM photos and videos anonymously, Hide that you're typing and Don't report screenshots on or off together, each of their rows shows the change, and a toast says so. Pause still turns them all off. It shows when at least two of those patches are in your build.

* **Instagram:** New Keep in chat patch. With its switch on under Messages, view once and replayable photos and videos stay in the chat as if they'd been sent with Keep in chat, so you can open them again. It's in simple mode with the switch off.

* **Instagram:** New Allow screenshots patch. With its switch on under Messages, screenshots and screen recordings work wherever Instagram turned them black, like disappearing photos and videos. It's in simple mode with the switch off.

* **Instagram:** New Don't report screenshots patch. With its switch on under Messages, Instagram doesn't notice your screenshots, so whoever sent you a disappearing photo or video isn't told you took one. It's in simple mode with the switch off.

* **Instagram:** Developer has a Reset all overrides row beside Import overrides. It takes every override in the signed-in session away so Instagram goes back to its own flags, and the overrides it removes are saved for Restore previous overrides first.

* **Instagram:** Lock your messages can lock all of Instagram now. Its new Lock all of Instagram switch covers every screen until your phone's lock says it's you, and Lock again lets it wait 1, 5 or 15 minutes or an hour after you leave. Turning a lock on waits until you next leave Instagram, and HushGram settings ask your phone's lock first while one is locked.

* **Instagram:** New Lock your messages patch. Turn its switch on under Messages and your inbox and chats stay covered until your fingerprint, face or screen lock says it's you. They lock again when you leave Instagram. While they're locked, message notifications say only New message, and the banner for a new message inside Instagram waits. It's in simple mode with the switch off.

* **Instagram:** Hide suggested posts has another new switch, Hide shopping, on to start, for the rows of products to shop and live shopping that Instagram puts between posts in Home.

* **Instagram:** Hide suggested posts has a new switch, Hide surveys, on to start, for the cards between posts that ask you to rate what you saw. Hide Threads posts also takes out the two Threads units that are new in Instagram 450, its picked posts and its topic rows.

* **Instagram:** Patching takes a little less time. HushGram settings' entry on the tab bar and Disable analytics' skip of the setup screens now find their places through the patcher's index rather than reading all of Instagram's code. Reported in #60.

* **Instagram:** Save comment photo now offers Save on photo comments in Instagram 450. Instagram 450 leaves the media type out of a comment's photo, and Save used to wait for it, so the row never showed. It now goes by the photo itself, and GIFs, videos and text replies still don't get the row. Asked for in #1.

* **Instagram:** Full resolution photos has a second switch, Ask for larger photos. On a phone under 1440 pixels wide, Instagram tells its server your screen is 1440 pixels wide and asks for photos at that width, so a 1080-wide phone has a larger size to load. It's off to start, it uses more data, and it takes effect after you restart Instagram. Asked for in #20.

* **Instagram:** New Stop swiping between tabs patch. With its switch on, a sideways swipe no longer moves you between Home, Reels and the other main tabs, so you can't land on Reels by accident. Tapping the tab bar still works, and carousels keep their swipe. It's in simple mode with its switch off, under Feed in HushGram settings.

* **Instagram:** New Hide the home feed patch. With its switch on, Home shows your stories row and nothing under it, so you can check stories and messages without the endless feed. Profiles, Explore and Reels still show posts. It's in simple mode with its switch off, under Feed in HushGram settings. Pull to refresh Home after turning it on.

* **Instagram:** New Change version code patch, off by default. It gives the patched build the highest version code Android allows, so Google Play stops offering Meta's updates over it, and Instagram's own checks of its version still see the real code. Once it's installed, going back to an unpatched Instagram means uninstalling first, which deletes Instagram's data on your phone, and later HushGram builds need the patch too or they won't install over it. Settings lists it under Set when you patched, and the diagnostic report names the code Meta built. Asked in #68.

* **Instagram:** Open developer options adds Open Whitehat settings under Developer in HushGram settings. It opens Instagram's own Whitehat screen, whose switch lets Instagram trust the certificates installed on your phone for 24 hours so you can check the app's traffic. Turn it on there and restart Instagram. Instagram turns it back off by itself once the day is up, and HushGram doesn't force or stretch that trust. Asked in #1.

* **Instagram:** New Full resolution photos patch. With its switch on, photos in your feed, in carousels and in posts you open load at the largest size Instagram's server sends, up to 2048 pixels on the longer side, rather than the size Instagram picks for your screen. Stories and Reels load as before. It's in simple mode with its switch off, under Feed in HushGram settings. It can use more data. Asked for in #20.

* **Instagram:** Show a story's exact time has a new How the time shows choice under its switch. Date and time is what it already did, and it's where the choice starts, so nothing changes until you pick. Time left shows how long the story has before it expires, like 18h 14m left. Time posted shows only the time it went up, like 3:45 PM, for a story posted today by your phone's clock, and the date and time for one posted before today. A story that's already a day old shows the date and time in Time left too.

* **Instagram:** New Hide that you're typing patch. With its switch on, the people you're chatting with don't see the dots while you type, and you still see theirs. Instagram's own typing indicator setting turns off both. It's off until you pick it in Manager, and the switch, under Messages in HushGram settings, starts off. Asked for in #13.

* **Instagram:** New Read messages without the seen receipt patch. With its switch on, opening a chat doesn't tell people you've seen their messages, and you still see when they've seen yours. Instagram's own read receipts setting turns off both. It's off until you pick it in Manager, and the switch, under Messages in HushGram settings, starts off. View-once photos and videos keep their own patch. Asked for in #13.

* **Instagram:** While the Read messages without the seen receipt switch is on, a long press on a chat in your messages offers Mark as read wherever Instagram would offer Mark as unread. Tapping it sends Instagram's seen receipt for that chat's newest message, so the people in it see Seen under their messages, and every other chat stays held back. Picking several chats and marking them read together lets them know too, and the receipt still goes out when Instagram only sends it after a restart or once you're back online. Asked for in #45.

* **Instagram:** The README's troubleshooting now says how to stop the Play Store from offering, and trying to install, Meta's build over a patched Instagram: untick Enable auto update in the three-dot menu of Instagram's Play page. Asked in #68.

* **Instagram:** The Korean settings read more naturally. @BlackGold8282 reworked 121 of them in #66, including the download, notes and Meta AI rows.

* **Instagram:** A photo story with music now has two rows in its menu, Download as video and Download as photo. Instagram serves those stories as a short video with the song, so Download used to save only that. Download as photo saves the picture on its own at its largest size. Every other story keeps its one Download row. Asked for in #53.

* **Instagram:** New Hide Instants patch. Instagram's Instants, the no-edit camera for friends, put a stack of photos in your messages. With the switch on, Instagram treats your account as one without Instants, so that stack goes after a restart. It's in simple mode with the switch off, under Messages in HushGram settings. Asked for in #59.

* **Instagram:** HushGram now patches Instagram 450.0.0.50.77. Before patching, get the APKMirror variant labelled (arm64-v8a) (480dpi) (Android 9.0+) with build number 385611438. Another build sits under the same label, so check the number. All 48 patches carry over. Instagram 450 renamed and merged a lot of the code they hook, and each one now finds its spot in the new layout.

* **Instagram:** Tap to play can leave Reels out, or stick to Reels. A new Where videos wait choice under its switch picks Everywhere, Everywhere but Reels or Only in Reels. It starts at Everywhere, so nothing changes until you pick. A reel you see in the feed goes with the feed. Asked for in #39.

* **Instagram:** Hide Meta AI can take About this reel out of a reel's More menu, in Reels and in your feed. That's the summary at the top of the menu with its Sources and the Ask Meta AI box, and in your feed the audio row under it goes too. A second switch takes out only the Ask Meta AI box and keeps the summary. Both are under Meta AI in HushGram's settings and start off. The menu's other options stay where they are. Asked for in #42.

* **Instagram:** Mark who doesn't follow you back now waits for Instagram's server. While your Following list loads, HushGram has Instagram ask the server about every account on it, and a row is marked only when the server says that account doesn't follow you. Before, Instagram skipped asking about accounts it already had a status for from your feed or reels, and a stale one could mark someone who does follow you. Reported in #40.

* **Instagram:** Import overrides takes Instagram's own overrides file (mc_overrides.json) as well as a HushGram export, even when the file leaves the setting names blank, and up to 4,096 overrides at once. Each override has to match a setting of the same type in the Instagram you're running. Instagram keeps that file through app updates, so one saved on an older version can hold settings yours no longer has. Those are left out, and Validate and Import say how many. Leftovers like that in Instagram's own saved overrides no longer stop Export, Validate, Import or Restore either. A HushGram export still has to come from the same Instagram version. Picking an overrides file in the settings import now tells you to use Import overrides under Developer instead. Reported in #67.

* **Tooling:** AGP's host test tools use Netty 4.1.139, which fixes 13 advisories. They stay on the 4.1 line for now, since every AGP up to 9.4.1 and the 9.5 previews builds its emulator control on gRPC 1.69.1, and that needs Netty 4.1. None of this is in the shipped extension payload.

* **Tooling:** Moved to Morphe patcher 1.15.1, which fixes a reused patch match handing back stale classes and speeds up signing, DEX output and matching. With it, the patch step for all 48 patches takes 54 seconds on a desktop, down from 147 at v0.0.5. The next release needs Morphe Manager 1.34.0 or newer, the first Manager that carries this patcher. v0.0.5 keeps working in Manager 1.33.0.

* **Instagram:** Patching is quicker. Many patches used to read every one of Instagram's 200,000 classes to find their spot, and now they look it up in the patcher's index first. On a desktop, Hide Meta AI went from 32 seconds to under one, and the patch step for all 48 patches from 147 to 78 seconds. Reported in #60.

* **Instagram:** Keep the reel speed no longer speeds up video ads. After you locked a reel at 2x, Instagram's own reset on the scroll to the next reel handed an ad the kept speed too, and the ad played at 2x. An ad now goes back to normal speed when it starts, and the reels after it still play at your speed. Measured on a phone, the same ad ran twice as fast before the change.

* **Instagram:** Download any video adds Download to the menu on your own posts too, whenever a tap would save the video or photo. Instagram only lists its own Download on posts it allows outside downloads for, some accounts get it in the share sheet instead of the menu, and HushGram's row was only on other people's posts. Reported in #57.

* **Instagram:** Pure black dark mode now reaches the Direct inbox and the Notifications screen, which kept Instagram's near-black gray because they draw from a second color palette the patch didn't change. On a phone both screens went from the gray to pure black, and the tab bar icons stayed visible. Reported in #51 and #58.

* **Instagram:** Replaced navigation-tab listeners can no longer open settings or call an old native action. Each callback belongs to its current button binding, including tabs with no native long-press handler.

* **Instagram:** Settings backups now include the chosen navigation tab for opening HushGram. Import reports when the change needs a restart, and Undo preserves any choice made after the import. Older backups leave this choice unchanged.

* **Instagram:** About and the saved diagnostic report tell two builds with the same version number apart, since each names the exact build it came from. Neither one includes your account, media links or tokens.

* **Instagram:** Settings can be opened by a long press on one chosen navigation tab. The choice starts off and replaces that tab's usual long-press action. Normal taps and other tabs keep their native behavior. Turning it off or pausing restores the native handler. Restart Instagram after changing the chosen tab.

* **Instagram:** TalkBack reads each settings switch's name and explanation instead of announcing only its state. Action rows expose their current value or disabled reason too. TalkBack and Switch Access checks cover right-to-left layout, 200% text, paused choices and refusal of stale actions.

* **Instagram:** A late result from an earlier settings or override file picker can't cancel a newer picker or write to the older file. Each request keeps its own identity through screen recreation.

* **Instagram:** Show a Reel seek thumb adds an optional white circular handle to Instagram's own Reel seek bar. It starts off and works independently of Keep a seek bar. Native taps, dragging and accessibility seeking remain available. Turning it off or pausing restores Instagram's appearance.

* **Instagram:** Settings and diagnostic exports keep their progress and last result on the export row until Instagram restarts. Closing settings keeps a running export going, cancellation has its own message, and duplicate or stale callbacks can't replace a newer result.

* **Instagram:** Settings and pending backup or override file requests survive Instagram restarting their screen behind Android's file picker. Returning from the picker restores the original page and delivers its result once.

* **Instagram:** Expired playback positions are retried on the next video start if the worker queue is full or their private store can't be read or written. Cleanup only counts as finished after a confirmed disk write, even when a failed write already changed the in-memory preferences.

* **Instagram:** Settings recovery also handles a page that fails while Android restores it after rotation. A working page keeps its pending file-picker request through recreation.

* **Instagram:** Settings search keeps actions reachable when their labels change. Searching for remembered positions still shows Undo after clearing them, including when you reopen settings before its deadline.

* **Instagram:** Settings import and playback-history Undo follow Android's accessibility timeout and show their deadline. Reopening settings doesn't extend it. Old taps can't undo newer operations, and settings Undo keeps choices changed after the import.

* **Instagram:** If the settings page can't open, the dialog keeps an explanation, Retry and Back. Retry removes any partial page, and buttons from an old or closed dialog can't reopen it.

* **Tooling:** Verification fixtures find the compiler beside the selected Java executable when Java comes from PATH.

* **Instagram:** Hide the Repost button stays hidden in the feed. The button used to go away and then come back about half a second later, because Instagram redraws each post's buttons from a saved state that still said the post could be reposted. That state now says no from the moment it's made, so a redraw, a refresh or scrolling back can't bring the button back. It also covers another Feed layout and rows that skipped the hide check, and reused rows restore their previous state when you turn the switch off or pause HushGram. Share stays in place. Reported in #69.
* **Instagram:** Downloads work on a photo that plays music in the Reels viewer (Explore, search and Suggested). Download there used to say "Download failed" because the item has no video file. Its menu now has two rows instead: Download as photo saves the picture at its largest size, and Download as video makes an MP4 of the picture with its music, about 5 MB for 90 seconds, saved to Movies/Instagram. A photo without music in the same viewer saves with plain Download. Reported in #71.

* **Tooling:** Advisory checks now hold unread or conflicting severity for review, including CVSS 4 beside a low label. Package ratings stay tied to the queried dependency and version, and aliases retain the strongest evidence. Malformed, repeated or whitespace-terminated CVSS 3 metrics are refused. Known HIGH and CRITICAL ratings remain visible beside unread evidence.

* **Tooling:** The release check counts every top-level test class a Kotlin test file declares, so a second class in one file no longer reads as left over from a deleted one and the index push goes through.
* **Tooling:** The injected-code contracts no longer count a method another rule picks for the same hook as a stray call, so the two touch methods Stop swiping between tabs hooks each pass on their own. A rule can also say how many times its method calls the hook, which Hide the home feed needs since it filters both reads of Home's saved feed.
* **Tooling:** The patched APK check now holds Hide the comment bar on reposted reels' guards where they belong: first thing in the comment bar's show, and once in the step that sets the bar up.

## 0.0.5 (2026-10-03)

* **Instagram:** HushGram 0.0.5 adds two patches, for 48 in all, and still targets Instagram 449.0.0.52.84 (build 385511871, arm64-v8a) on Android 9 and newer.
* **Instagram:** HushGram now builds on Morphe patcher 1.15.0, so it needs Morphe Manager 1.33.0 or newer. Manager 1.32.0 asks for an update before it loads the bundle.
* **Tooling:** smali now matches the commit patcher 1.15.0 asks for. The old pin was one commit behind it but sorted higher, so Gradle had been compiling and testing against the older dexlib2. The fixture gates move to desktop CLI 1.18.0.
* **Instagram:** New patch, `Hide the notes row`, in Manager's simple mode with its switch off. Turn it on under Messages in HushGram settings and the row of notes at the top of your messages goes, along with the Map bubble that sits in it. Your chats, search and message requests stay where they are. Asked for in #17.
* **Instagram:** HushGram's settings come in Korean now, translated by @BlackGold8282 in #36.
* **Instagram:** Following-list labels now wait for Instagram's row friendship status instead of trusting a stale profile fallback, so accounts won't show "Doesn't follow you" until their profile refreshes.
* **Instagram:** Pure black dark mode now leaves bottom navigation icon colors alone when they share Instagram's dark Prism value, so the buttons stay visible on black backgrounds.
* **Tooling:** Wrapper checks find Git Bash when Git hooks resolve an internal Git executable. The authentic Windows and POSIX launchers and every refusal-before-execution check remain required.
* **Tooling:** Local builds default to two workers at low priority, with parallel project builds off. Patch tests use a 4 GB heap instead of 8 GB, and test JVMs limit their internal processor count to two. Build caching stays on.
* **Tooling:** Bug reports offer direct messages, profiles and Explore, with a link to local diagnostics and build details. The settings guide separates confirmed suggestion controls from optional profile rows, and the source guide matches the latest recorded census. Manager's story patch description matches the guide.
* **Instagram:** The source catalog makes six initially neutral controls available in Manager's simple mode. Copy comment, Save comment photo, Hide highlights, Stop swipe to create and Stop Reels scrolling still start off. Story ring size starts at Instagram's own size. Saved choices stay intact. Stop swipe to create checks its switch before making added native reads, so off, Pause and startup before settings are ready retain the original path.
* **Tooling:** Refuse ignored compiler and packaging inputs in production source sets before building or certifying a current dependency audit. Git ignore rules can no longer hide shipped code from its build identity. Staged development inputs still use their current working bytes, and release receipts still require clean sources. Debug, test and generated files remain excluded.
* **Instagram:** Keep Reels auto scroll on remembers a completed future timer immediately, so it stays on even if the timer expires before another reel checks it. Cancelled choices, expired timestamps and paused runs keep their usual behavior. Its startup summary appears once at each readiness state, including when checks run together.
* **Instagram:** Stop Story auto-advance checks the actual story loop flag before adding its guard. Hide highlights refuses branches into the row's type read or add. Native bundle checks pin the story guard's exact loop call and preserve the original handler's call.
* **Tooling:** Local patching checks each selected bundle's exact targets and dependencies before merging or changing an APK. Reports identify the bundle by its hash and attribute initializer failures to their dependency owner. Conflicting selected extension definitions are refused, while compatible addons keep their native initialization. Bug reports now ask which sources and bundle versions were selected.
* **Instagram:** Story retry patching checks that the native queue count and account reads leave pending batches alone. It refuses subclasses of the final story store, including inherited builder references hidden in encoded handles. A retry bridge with no executable DEX body also stops patching.
* **Instagram:** About and local diagnostic reports show the production build identity, so different source builds of the same version can be identified.
* **Tooling:** New release receipts map the canonical source, catalog and toolchain identity to final bundle and extension hashes. The identity includes Android release source sets, so a change there produces a different identity even when the version stays the same. Debug and test inputs stay excluded. Historical receipts keep their original reading rules.
* **Instagram:** Turning all three suggestion switches under Feed off restores Instagram's own empty feed behavior in the same run. Removing a suggestion earlier no longer keeps the filtered feed's end state active.
* **Tooling:** Every required Android boundary result now names its actual Android SDK, including the highest SDK in a test class. The gate refuses Android 16 results substituted for Android 17 and results with their platform label removed. All provider caller cases remain required. Self-tests also check missing, filtered, skipped, failed and duplicate results through the real Gradle task, then restore the original report.
* **Tooling:** Both Gradle launchers verify the reviewed wrapper JAR and distribution checksum before wrapper code runs. Altered, missing or unreviewed wrappers stop direct builds and scripted builds with a useful error. The push gate exercises both launchers with a runnable replacement JAR.
* **Instagram:** Settings ignore stale accessibility clicks after a containing section is removed or the page closes. Disabling a container disables its controls' accessibility actions. Search refuses edits and Clear after its page closes. A focus-clear action releases the search view hold even when accessibility is off. Android 9 and 17 regressions cover current screen changes and disabled ancestors.
* **Tooling:** Bind current dependency audits to source, catalog, toolchain, SBOM and bundle bytes, preserve resolved dependency edges, and update affected settings and UTP tools to compatible Commons Lang and HttpClient fixes.
* **Instagram:** Override recovery keeps the selected saved copy through interrupted saves and moves. Cleanup failures stay visible even when changes have already applied. Restore removes temporary replacement copies after recovery finishes, and Discard can remove a damaged backup or recovery record. It also recovers when a lone completion record can't be read. The terminal recovery journal is moved to a completion record, keeping imports blocked while its final storage flush fails even if no replacement file can be written.
* **Tooling:** The shipped SBOM now records reviewed Apache 2.0 licenses for its exact Gson, Kotlin stdlib and JetBrains annotations artifacts. Publisher POM and binary hashes bind each record. NOTICE names the carried libraries. Current dependency audits and new release receipts refuse omitted libraries, missing evidence or substituted hashes while historical receipts stay readable.
* **Instagram:** View stories anonymously keeps saved batches held if reading the switch, choosing marked stories or creating their batch fails, including an allocation failure. Every retry checks the batch again before Instagram takes ownership or creates a request. It no longer removes a held batch or falls back to sending its original contents. Turning the switch off or pausing keeps Instagram's usual behavior.
* **Instagram:** Import, Restore and Discard recheck their permission after reading native state. Turning Allow importing overrides off or pausing while an import waits now stops it before any typed write or recovery-file change. Permission is checked again after file staging and before the first native change, preserving the earlier saved copy if permission changed.
* **Instagram:** Added the opt-in patch `View DM photos and videos anonymously`, with a separate switch that starts off. It holds back the visual photo/video opened receipt and completes Instagram's queued visual task locally. Ordinary message and voice receipts keep their existing path.
* **Instagram:** `Hide Meta AI` now covers its optional message composer buttons through Instagram's existing visibility and layout path, and omits the optional inbox row through its native empty-row branch. Native fixture tests preserve ordinary controls and thread lists.
* **Instagram:** Same-key provider trust now checks the caller's actual current signing key after Instagram's native decision. Only named family apps in the same Android user can qualify. Other provider policies and the original deep-link certificate answers stay intact.
* **Instagram:** Disable analytics also skips contacts and location setup delivered through the direct screen presenter. It checks the same two setup screen identifiers and keeps the native path for ordinary screens and absent screen data.
* **Instagram:** Open MetaConfig overrides passed the signed-out Samsung check. Its row kept the complete unavailable reason and settings stayed open.
* **Instagram:** The reporter confirmed that Clean up Reels now hides the friends' activity and closed #7.
* **Instagram:** The reporter confirmed Discover people and suggested users in Reels in #15.
* **Tooling:** Added local Crowdin setup with resumable draft imports and a review package for German, Spanish, Indonesian, Brazilian Portuguese, Turkish and Korean. Setup requires the owner's token, preserves existing reviewer work and never approves translations. Translation tests now run before a related push.
* **Tooling:** Gave the native patch test suite enough heap for the expanded fixture proofs. The default test-worker heap ran out while reading whole APK methods.
* **Tooling:** Story retry proofs now reject callers that bypass the protected queue, including additional interfaces and stored method handles. They also reject changed ownership predicates and mismatched disk cleanup arguments. Native helper bodies identify the saved key and cleanup operation. The DEX contract checks the story store guard and the null branch before ownership changes.

## 0.0.4 (2026-10-02)

* **Instagram:** HushGram 0.0.4 adds eleven patches, for 46 in all, and still targets Instagram 449.0.0.52.84 (build 385511871, arm64-v8a) on Android 9 and newer.
* **Instagram:** New patch, `Loop a story`, off until you pick it in Manager. A story plays again from the start when it ends, until you tap or swipe, through the loop Instagram is already trying out. Ads still move on. With Stop Story auto-advance on too, the stories that can loop do, and the rest, like ads, stay on screen until you tap or swipe. Its switch is under Stories. Asked for in #1, and piko had it first.
* **Instagram:** New patch, `Show a story's exact time`, off until you pick it in Manager. A story's header says when it was posted, like Oct 2, 3:45 PM, instead of 3h, in your phone's language and with its 12 or 24-hour setting. Its switch is under Stories. Asked for in #1, and piko had it first.
* **Instagram:** New patch, `Stop Reels scrolling`, off until you pick it in Manager. A swipe in Reels no longer moves on to the next reel, and pulling down doesn't load new ones, so you stay on the reel you opened. Its switch is under Reels, and a change takes a restart. Instagram's own auto scroll still moves on when a reel ends if you've turned it on. Asked for in #23.
* **Instagram:** New patch, `Stop swipe to create`, off until you pick it in Manager. A sideways swipe on Home no longer slides the camera in, and neither does a quick flick. The + button and every other way into the camera still work, and a swipe back out of the camera does too. Its switch is under Feed.
* **Instagram:** New patch, `Hide highlights`, off until you pick it in Manager. The row of story highlights goes from profiles, yours and other people's. Its switch is under Profiles. Bios, counts and posts stay, and so does Add to highlight on your stories.
* **Instagram:** New patch, `Save comment photo`, off until you pick it in Manager. When a comment has a photo of its own, its menu gets a Save row that saves the largest size Instagram sent for that photo. It goes through the same downloader as Download, with your save folder and its Cancel button. A GIF, a video, a photo still uploading and the post itself don't get the row. Its switch is under Comments next to Copy comment and starts off. Asked for in #1.
* **Instagram:** The Threads buttons on a profile, View profile on Threads and the Threads chip under the bio, now open Threads when it's patched with the same key as HushGram, instead of showing "Sorry, we weren't able to load that website". Facebook and Messenger patched with the same key open from Instagram the same way, and Instagram trusts all three when they share to it or ask it for your sign-in. An app signed with any other key is still left to Instagram's own check. Reported in #22.
* **Instagram:** A Threads, Facebook or Messenger is trusted only while HushGram's key is the one it's signed with now. One that has moved to another key since is left to Instagram's own check, even though Android still lists HushGram's key first in its signing history. HushGram also learns its own key only from Android, never from a record that just carries Instagram's name.
* **Instagram:** Developer has a new switch, Allow importing overrides, which starts off. With it on, Import overrides applies a file from Export overrides for the same session and Instagram build through the typed table Instagram's own override editor writes, and Restore previous overrides puts back the copy saved just before the last import that went through. Nothing is written until the file passes the same checks as Validate and the session's stored overrides read the same twice, a few seconds apart, so a change you just made in Instagram's own editor isn't written over. The store is read back afterwards and put back if Instagram didn't keep the change, and a file that already matches changes nothing. If that put-back can't be confirmed, imports wait for Restore. Restore tells you how many overrides holding Instagram's null value it couldn't put back, and Discard saved overrides forgets the saved copy so imports can run again. Restart Instagram to apply an import. Asked for in #1.
* **Instagram:** View stories anonymously has a second switch under Stories, Mark as seen button, which starts off. It adds an eye button to the top of each story, and a story you tap it on is sent as seen, on its own, while the others stay held back. Tap again to take the mark back before it goes. A mark only counts on the account you made it on. View stories anonymously now also holds back the views Instagram saved in an earlier session to send again later. Asked for in #1 and #13.
* **Instagram:** On a phone set to a right-to-left language like Arabic, Hebrew, Persian or Urdu, every title and summary in HushGram's settings now lines up at the right. Wrapped English text used to sit flush left beside one-line titles on the right. Left-to-right layouts look the same as before.
* **Instagram:** Show if a profile follows you no longer fails as a whole when an Instagram build changes how its follow lists are drawn. The profile label still goes in, and Mark who doesn't follow you back is left out of settings, with a warning in the patch log and a line in the diagnostic report.
* **Tooling:** The translation scripts stop straight away on Python older than 3.12 with one line naming the version they need. On Python 3.11 under Windows, every import used to fail with "destination changed after validation", which pointed at the wrong problem. The same cause on a newer Python with an older Windows build now gets its own plain message too.
* **Instagram:** When Open MetaConfig overrides can't open (signed out, or a screen without Instagram's editor), its row now keeps the whole reason under it, since Android cuts the toast to two lines and dropped the part about signing in.
* **Instagram:** Show if a profile follows you has a second switch under Profiles, Mark who doesn't follow you back, which starts off. On your own Following list it adds Doesn't follow you after the name of each account Instagram says doesn't follow you back. Followers and other people's lists stay as they are, and nothing shows until Instagram has checked. When the name is long, Instagram shortens the line to fit, so the mark can end in an ellipsis. Asked for in #1.
* **Instagram:** New patch, `Hide suggested people on profiles`, off until you pick it in Manager. Suggested for you goes from profiles, yours and other people's, whether Instagram puts it in the Follow and Message area or in a row under the header, and so does the Discover people button beside those buttons. Its switch is under Profiles, and bios, counts, posts and follower lists stay. Asked for in #15 and #20.
* **Instagram:** New patch, `Hide suggested accounts in Reels`, off until you pick it in Manager. The cards of people and creators to follow that Instagram puts between reels are left out, and Your algorithm cards, ads and every reel stay. Its switch is under Reels. Asked for in #15 and #20.
* **Instagram:** New patch, `Keep a seek bar on Reels`, off until you pick it in Manager. Every ordinary reel keeps Instagram's seek bar under it, short ones too, where Instagram would otherwise draw none or hide it until you hold the reel, and the time played and the reel's length show just above the bar, like 0:10 / 0:55. Ads keep Instagram's own rules. Its switch is under Reels. Asked for in #10.
* **Instagram:** New patch, `Keep Reels auto scroll on`, off until you pick it in Manager. Once you turn on Instagram's auto scroll in Reels, it stays on after Instagram restarts or you leave Reels, and when you turn it off it stays off. Its switch is under Reels. Asked for in #21.
* **Instagram:** Keep a seek bar on Reels puts its time only on an ordinary reel's bar in Reels, never on an ad's bar or one elsewhere in the app, and each bar has one label that hides while its bar is hidden and leaves with it, so a scrolled or recycled reel can't keep an old or doubled time.
* **Instagram:** Keep Reels auto scroll on no longer takes Instagram's saved auto scroll setting by itself as a sign you turned it on. Where Instagram goes by a timer or by memory instead, a long press on the Reels tab could leave that setting on, and turning auto scroll off with its switch then didn't stick.
* **Instagram:** Copy comment only takes a comment menu renderer whose middle argument fits one register. A future build that made it a long or double would have shifted the selected comment's register, so it's refused before anything changes.
* **Instagram:** Keep Reels auto scroll on now checks that Instagram's auto scroll switch saves the very choice it was handed, passed along only by plain copies, before it reads that choice. A build that saved anything else would have left HushGram remembering the wrong choice, so it's refused before anything changes.
* **Instagram:** Keep Reels auto scroll on remembers what you pick with Instagram's own auto scroll switch, on or off, the moment Instagram takes it, instead of waiting until something asks again. Turning auto scroll on from the Playback menu and closing Instagram before a reel ended used to lose it. The Reels viewer and picture in picture, which read Instagram's in-memory setting directly, now get the remembered answer too.
* **Instagram:** Hide highlights now follows the highlights row's type from the name Instagram gives it to the field it's kept in, instead of taking whichever row type is stored next. It also checks that nothing reads the row's number after the row it skips. A build set up any other way could have hidden the wrong row or crashed on profiles, so it's refused before anything changes.
* **Instagram:** With Tap to play on, Instagram's own auto scroll in Reels no longer stops after one reel. When auto scroll moves on, that counts as a tap on the reel it moves to, so the reel plays, and when it ends auto scroll moves on again. The reel it moved to used to sit on its first frame and auto scroll never moved again. Feed videos, stories and a reel you open without a tap still wait for one, and a reel you pause still stops auto scroll there. Found while checking #21.
* **Tooling:** The injected-register verifier no longer clears a shared phone's log buffer. It writes its own marker line, reads only what follows it, and stops with a plain message if the buffer has already rotated past the marker.
* **Instagram:** Save all only shows on a carousel when at least one page would save with your Downloads switches. An all-photo carousel used to offer it with Download feed photos off, then report every page as skipped.
* **Tooling:** Device identity checks read ADB's standard output separately from diagnostic messages. A successful daemon startup no longer rejects the selected phone or emulator, while command failures keep their full diagnostics and invalid identities still refuse installation.
* **Instagram:** A failed sign-in notice dismissal asks for a retry without claiming that storage was restored. Its earlier message could make that claim even when the write landed and rollback failed. All five translations carry the corrected feedback.
* **Instagram:** Concurrent Android 9 saves keep separate hidden files and cleanup identities. Cancelling one leaves the other's bytes alone, and completed saves take different final names. A refused cleanup record removes its reserved file without touching finished media.
* **Instagram:** Turning HushGram back on from its status card saves in the background instead of blocking settings. Both recovery controls stay disabled until the write finishes, duplicate taps are ignored, and failures leave the saved Pause choice visible for retry. Closing settings during recovery leaves detached controls alone.
* **Tooling:** Troubleshooting now checks the Instagram build and other enabled patch sources before blaming a changed app. Report #11 succeeded after its other source was removed. Signing-conflict guidance explains which updates preserve data and that removing a differently signed app deletes its local data.
* **Instagram:** Copy comment proves the original field-name and returned-object paths before applying its hook. Empty or null replacement text removes stale Copy actions while retaining the stock menu.
* **Instagram:** Diagnostic export descriptions show the folder the writer actually uses. Android 9 shows Instagram's own external-files folder. Android 10 and newer show Download/Morphe. The row and chooser share the same path, including translated descriptions.
* **Instagram:** A refused sign-in notice dismissal keeps the notice visible and explains the save failure. The diagnostic report chooser closes with its settings page, and a late tap cannot reopen it after teardown.
* **Tooling:** Device installs and verifier runs require an exclusive lease and verified device identity. Updates keep installed data and permissions, replacement uninstalls refuse, and verification selects the device's actual instruction set. Windows emulator identity replies accept their native line endings.
* **Tooling:** Protect translation replacement with an enforced Windows file transaction. Stale edits and conflicting writers refuse the import, existing readers retain complete old bytes, and unsupported storage refuses writes. Unchanged imports still preserve exact bytes.
* **Tooling:** Exercise every native override writer and reload prohibition with its own failing DEX fixture. Calls inside Instagram's native package remain allowed, and all existing shortcut checks stay in place.
* **Instagram:** Add read-only MetaConfig override export and file validation tied to the exact Instagram build and typed schema. Validation reports its result without applying any overrides.
* **Tooling:** Refresh the settings, diagnostics and About captures from the published v0.0.3 bundle. Document their actual patch selection and verified build tools, and label source-only controls as unreleased.
* **Instagram:** Add an optional Copy action to the common comment menu, with an off-by-default Comments switch. An explicit tap copies the original text, including literal markup and line breaks, and uses Instagram's own menu dismissal.
* **Instagram:** Default playback quality is included in Manager's simple mode. Its initial Auto choice leaves playback unchanged. Patches that change behavior right away stay opt-in.
* **Tooling:** Accept reordered numbered arguments within mixed translation formats while keeping bare arguments in their original order. Changed types, indices and argument counts still stop the import.
* **Instagram:** Direct MetaConfig discovery now verifies the native editor and navigation objects reach the presenter, refusing overwritten values and unsafe branch entries before changing the app.
* **Tooling:** Export the English catalog and translations as Crowdin-compatible JSON, then validate complete or explicit partial local imports before replacing a table. Unchanged round trips keep their exact bytes. Format, escape, plural and input limits are all checked. Imports coordinate through owned locks. Hosted translation approval remains separate.
* **Instagram:** Save recovery can retire an already-removed Samsung gallery row even when its next delete reports no access. It needs a successful exact-row query first. Pending or unreadable rows stay queued for cleanup, and published files are kept.
* **Instagram:** Open MetaConfig overrides from HushGram's Developer section without enabling Home long press or changing a flag. Unsupported or signed-out screens keep settings open with an explanation, and successful navigation closes them.
* **Instagram:** Save all keeps a carousel's ordered pages in one cancellable job, with the same download quality throughout and a 32-page limit. The current-page Download stays available. Downloads keeps the complete saved, failed and skipped counts for this process, including cancellation and lower-quality warnings. Cancel keeps finished files and removes unfinished resources.
* **Instagram:** Search installed settings by their translated labels and descriptions or English patch names, including contacts, location setup, Following and Reels. Pause, recovery and running saves' Cancel remain available. Clearing restores the same controls and choices, and filtering leaves the live Cancel button in place as progress changes. Search stays offline. Queries stay in the open page and aren't serialized when Android rebuilds it.
* **Tooling:** Android 9 and Android 17 regression cases exercise settings opening, large text, right-to-left layout, system bars, recovery, diagnostic export, storage and save cancellation with Instagram 449's target SDK 36. The local push check refuses missing or skipped boundary cases, including search focus, filtered Cancel, carousel cancellation across pages and phases, and cleanup of Android 9's hidden unfinished files without deleting finished media.
* **Instagram:** Imports close the picked file before applying settings and retain the complete result across settings reopening. Saves record a separate one-byte outcome marker, so failed preference writes don't turn completion or Cancel into an interruption.
* **Instagram:** After an interrupted save, cleanup removes its unfinished resources and the next opening explains how to save again. One opaque marker tracks each save without keeping media or account IDs. Completed and cancelled saves leave no interruption notice. Failed cleanup stays pending, including a gallery row whose deletion was refused.
* **Instagram:** Export and import the installed patches' settings through Android's document picker. Imports validate a bounded typed file before applying values together, report unsupported keys and restart requirements, and offer a one-use Undo for 10 seconds. Failed writes say whether rollback worked. Private state and history aren't exported.
* **Instagram:** Following, story size, Meta AI search and bottom-space settings now carry the restart requirements their descriptions already stated. Imported choices and Undo report the same requirements, and complete import feedback stays visible after its toast disappears.

## 0.0.3 (2026-10-01)

* **Instagram:** HushGram 0.0.3 adds fourteen patches, for 35 in all, and still targets Instagram 449.0.0.52.84 (build 385511871, arm64-v8a) on Android 9 and newer.
* **Tooling:** The release tooling's own tests pass while a release is being prepared. Their copy of the checkout left the bug form on the published version and their index commit dropped the CHANGELOG's earlier releases, so the source commit of every release after the first was refused.
* **Instagram:** Tapping an expired Undo for remembered positions reports its expiration and leaves newly remembered positions intact, even when the settings row hasn't refreshed yet.
* **Instagram:** Clear remembered positions deletes the local playback history and cancels pending restores, even while playback is off or paused. Undo restores its bounded snapshot once within 10 seconds. A restart discards it, and newly remembered positions win.
* **Instagram:** Only accounts you follow, Ring size and Playback quality explain which parent switch enables them while it is off. Their saved choices stay intact, and shared Download quality remains available.
* **Tooling:** Require project-plugin graphs in imported dependency reports and bind each advisory exception to one reviewed tooling scope. Per-scope verdicts keep the same library's other uses visible.
* **Instagram:** Diagnostic reports show matched/expected patch targets and fixed missing-target labels for Disable analytics, Sanitize sharing links and Start on x86 devices. The counts describe patch-time code matches, not live request suppression.
* **Tooling:** Fixture verification and schema 3 release receipts read those counts from the patched APK. Required targets are pinned to the exact fixture and a missing one stops certification even when its patch applied. Optional absences stay visible. Forced runs on unreviewed builds record counts without certifying them. Historical receipts keep their original schema checks.
* **Tooling:** AGP's host test tools use Netty 4.1.138, and the settings plugin uses JDOM 2.0.6.1 and jose4j 0.9.7, removing the affected versions found by the dependency audit. These libraries aren't in the shipped extension payload.
* **Tooling:** The dependency audit reports the resolved settings, plugin, build, test and host-contract graphs separately from the shipped SBOM. Missing dependencies stop it. OSV results include a reviewed publisher supplement for Guava, whose settings-plugin copy and shared-extension test graphs now resolve to 33.7.2. Host contracts don't certify an installed Manager or Desktop.
* **Instagram:** The Before you sign in notice and the README's sign-in advice stick to what's known. They used to call a Root Mount install the lowest-risk path and say an older account is treated better, which nobody outside Meta can show. Now they say a re-signed Instagram can't pass Google's Play Store check whatever the patches do, that a Root Mount install keeps the sign-in you already have, that updating over the top with the same key keeps Instagram's data, and that a spare account keeps yours out of it. The notice is updated in all five translations.
* **Instagram:** The HushGram settings row at the top of Settings and activity wraps its title onto a second line at a large text size or in a long translation, instead of cutting it off at the edge. At 200% text in a narrow window even the English title used to lose its last letters.
* **Instagram:** `Turn off double tap to like` has two switches under it, On posts and On reels, both on to start. Turn one off and a double tap likes there again while the other stays held back, so you can keep double tap to like on posts and still not like a reel by accident. With the main switch off, both are greyed out and every double tap likes as it used to. Asked for in #6.
* **Instagram:** `Clean up Reels` hides friends' activity in two more places. The floating bubbles of friends who liked or commented could still show, because the patch only kept them from sitting above the author's name, and the Liked by and Followed by line with friends' faces had no switch at all. Now the bubbles aren't built anywhere, and Instagram's own check for that line leaves it out when it's about people you know. A follower count, a seller's rating or a line about strangers still shows. Reported in #7.
* **Instagram:** New patch, `Show if a profile follows you`. Someone's profile says Follows you or Doesn't follow you beside their name, in the small line Instagram keeps there for pronouns, after the pronouns if they've set any. It comes from the friendship status Instagram checks when a profile opens, so nothing shows until it has, and your own profile gets nothing. Its switch is in a new Profiles section and starts on. Asked for in #1, and piko had it first.
* **Instagram:** `Start Home on Following` has a second switch, Only accounts you follow, off to start. With it on, For you comes out of the picker at the top of Home, so Home stays on Following or Favorites, and a For you you'd picked before opens Following instead. Asked for in #1.
* **Instagram:** New patch, `Remove the empty space at the bottom`, off until you pick it in Manager. When a phone hides its navigation bar completely, or Instagram runs in a pop-up window, Android tells Instagram there's no bar under it. Instagram then asks whether the phone has one at all, and if so it leaves that much empty room under its tab bar anyway. With the patch, that guess counts as no room, so the tab bar sits on the bottom edge. Full screen on a phone showing three buttons or a gesture handle, Instagram gets the real size and nothing changes. Its switch is in a new Layout section, and a change shows once Instagram restarts. Asked for in #1, and MyInsta and piko had the fix first.
* **Instagram:** New patch, `Hide the Repost button`, off until you pick it in Manager. The Repost button and its count are gone from posts and reels, so nothing gets reposted to your followers by mistake, and Share still sends a post or reel to someone. Instagram is told each post can't be reposted, wherever its buttons ask. Its switch is in the Sharing section. Asked for in #1.
* **Instagram:** New patch, `Story ring size`, off until you pick it in Manager. The rings in the stories row at the top of Home can be drawn smaller, so more fit on the screen, or larger, from 70% to 130% of Instagram's size. The picture in each ring and the space around it follow along. The switch and a Ring size list are in the Stories section, and the size starts as Instagram's, so picking the patch changes nothing until you choose one. Asked for in #1.
* **Instagram:** New patch, `Hide group buttons on the share sheet`, off until you pick it in Manager. The New group button beside the share sheet's search bar is gone, whether Instagram shows it as a button of its own or as an icon inside the bar, and the search bar takes its room. Once you pick several people, the button that would send to them as a group stays away too, so Send separately is what's left. Its switch is in a new Sharing section. Asked for in #1.
* **Instagram:** New patch, `Pure black dark mode`, off until you pick it in Manager. Instagram's dark mode gets a pure black background in place of its near-black gray, in its themes and in the colors Instagram's newer screens are drawn with. Menus, sheets and buttons keep their own grays, so nothing disappears into the background. It's set when you patch and shows under Set when you patched. On a test phone Home, Search, profiles, messages, settings and the reel viewer went black, and comments, the share sheet and menus kept their grays.
* **Instagram:** New patch, `Open developer options`, off until you pick it in Manager. A long press on the Home tab opens Instagram's own developer options, where its server flags (MetaConfig and quick experiments) can be looked at and overridden on your phone. A switch in the new Developer section turns the long press back to what it did. On a test phone a long press of Home opened them, and with the switch off it did what it did before. piko had the idea first.
* **Instagram:** New patch, `Start on x86 devices`, from Hushfacebook. It keeps Instagram from crashing or freezing on an x86 device that runs its arm code through Android's translator, like an x86 Chromebook or an emulator. Instagram runs the same code protection step Facebook does, which under the translator leaves the device's own framework code unable to run, so the patch skips that step there. Phones and tablets with arm chips run it as before. It's in Morphe Manager's default selection and has no switch, so Pause doesn't change it. It hasn't been tried on an x86 device yet, and on an arm test phone Instagram starts and runs as before.
* **Instagram:** New patch, `Keep the reel speed`. Lock a reel at 2x with Instagram's own lock (hold its edge, then slide down) and the next reels play at 2x too. Sliding the lock off (hold the locked reel's edge again, slide down and let go), or holding the edge and letting go, takes them back to normal speed, and so does restarting Instagram. Ads start at normal speed. Its switch is under Reels.
* **Instagram:** New patch, `Hide suggested posts`. The posts and reels Instagram puts in your home feed from accounts you don't follow, labeled Suggested for you or Suggested Reel, are gone, and so are the rows of accounts, shops and hashtags it suggests you follow and the posts, accounts and communities from Threads it mixes in. Each has its own switch in a new Feed section, and all three start on. Posts from accounts you follow stay, and Explore keeps its posts. On a test phone, Home went from mostly Suggested for you posts to none, and they came back with the switch off. A row of suggested accounts Instagram sent there came out too. If nobody you follow has posted lately, Home has nothing left to show, and you get Instagram's own Welcome to Instagram card there in place of a loading placeholder that never went away. Following, with Start Home on Following, stops at its End of following card, where a spinner used to stay under it.
* **Instagram:** New patch, `Hide Meta AI`. The Search tab's bar goes back to a plain Search, without the Meta AI icon or "Search with Meta AI", and the bar at the top of your messages loses "Search or ask Meta AI" and the Meta AI ring at its end. A search's results lose the "Ask a follow-up" bar and the topic pills above it, and keep their header with Back and your search. Home's top bar loses Meta AI's button and the Meta AI chats button some accounts get there. Meta AI's videos, chats and pictures of you are taken out of your home feed too. Each half has its own switch in a new Meta AI section, both on to start, and the search one shows after Instagram restarts. On a test phone both search bars went plain, a search for "lions" ended without the follow-up bar, and Meta AI came back with the switch off. The test account doesn't get Home's Meta AI buttons, so that part is checked against the app's code only.
* **Instagram:** New patch, `Hide the Explore grid`. The Search tab keeps its search bar, Add interests and the Saved button, and the grid of posts and reels under them is gone, along with the load more button Instagram would put there. Explore's pages come in empty and Instagram isn't asked for more on its own. It's off until you pick it in Manager, and then its switch in a new Explore section starts on. On a test phone the grid went and the page stayed empty through a cold start and a pull to refresh, a search for "lions" still showed accounts and posts, and the grid came back on a refresh with the switch off.
* **Instagram:** New patch, `Start Home on Following`. Home opens on posts from accounts you follow, with Instagram's own feed picker at the top: tap Following there to switch to For you or Favorites, and Home opens on your pick from then on. It's off until you pick it in Manager, and then its switch under Feed starts on and takes effect after a restart. On a test phone Home opened on Following, a pick of For you was still there after a restart, and Home was back to the Instagram logo with the switch off.
* **Instagram:** New patch, `Hide suggested stories`. Stories from accounts you don't follow, and the accounts Instagram suggests, come out of the row of stories at the top of Home. A second switch, off to start, takes the whole row away, Your story included, and stories still open from a profile or a message. Both switches are under Stories. On a test phone the row went with the second switch on and came back with it off. The test accounts don't get suggested stories, so that half is checked in tests and against the app's code.
* **Instagram:** Download any video now works on a carousel. Open the menu on a page that's a video and Download is there, and it saves that page, not the first one. A photo page gets no row, the same as a photo post. On a test phone, each page of a three-video carousel saved its own video.
* **Instagram:** Disable analytics now also keeps Instagram from streaming usage events, one at a time, to its realtime gateway (test-gateway.instagram.com on a test phone). With the switch on, every event goes to the batch upload the patch already refuses. The gateway stays open because the app's realtime updates arrive through it too. On a test phone the stream stayed off with the switch on. It was small there to begin with. The gateway carried about 20 KB up in a minute of scrolling either way, nearly all of it in the first seconds after a start.
* **Instagram:** Disable analytics now skips the "Set up on new device" screens that ask for your contacts and location. Instagram shows them until it hears they were seen, and with the switch on it never hears that, so they'd come back on every start. Turn the switch off and they show as before. Nothing else that opens the same way is touched. The idea comes from piko, which found the same loop.
* **Instagram:** Download any video can save photos too. Turn on Download feed photos under Downloads in HushGram settings, and a photo post, or a carousel on a photo page, gets the same Download row. A tap saves the largest size Instagram has to Pictures. It's off by default, and a video's cover never saves in place of the video. On a test phone a photo post and a carousel's photo page each saved at full size.
* **Instagram:** Open links in external browser now keeps a link you follow inside an ad's page in the app, with the ad. Before, that link left for your browser and closed the ad.
* **Instagram:** The README now says what the in-app browser's own Copy link and Share hand out. They give the address of the page you're on, and Sanitize sharing links doesn't touch them. Instagram's own links open in the app instead of the browser, so the tracking keys it adds to those never leave this way. On a test phone a Help Center page shared as its plain address.
* **Tooling:** Every script that came over from Hushfacebook now says so. The 27 in `scripts/` that predate the release tooling, the patching, push gate, register gate and source ledger scripts among them, carry a note naming the commit they were taken from, and two new rules in `provenance.json` name them, so `ProvenanceTest` holds their headers to the ledger the way it already did for the release scripts. It reads a Python docstring and a Java program with no package line now too.
* **Tooling:** The patched-build check can now pin a hook whose method holds no string of its own, by the strings its class holds. That covers the story seen send, the reel watch progress record and the Reels tab list, so a build missing one of those hooks fails the check instead of only the fixture test.
* **Tooling:** The patched-build check can also tell two methods of the same shape apart by a call one of them makes. That pins Tap to play's release of a held story and the Reels tab's return to Home, two hooks that had no rule before, so a build missing either fails the check too.
* **Tooling:** Thirteen more hooks have a rule in the patched-build check: Tap to play's start and its pause in the player underneath, Resume long videos' pause and its end and loop callbacks, Restore trust's signer reader, the home feed item filter of Hide Reels in the feed, Hide suggested posts and Hide Meta AI, three of Download any story's menu methods, and Stop Story auto-advance's handler. A build that loses one of them now fails the check instead of only its fixture test. Every hook Download any story and Stop Story auto-advance place is now pinned.
* **Tooling:** The build moves to Gradle 9.8.0 and Guava 33.7.2. That Guava release fixes a flaw in how it reads serialized data (GHSA-xxph-c9ww-hj94), and every Guava the build used before, its own pin and the older ones the patcher and the test tools asked for, was in the affected range. Nobody was exposed: Guava only runs while the patches are built and tested, and the HushGram bundle doesn't ship it.
* **Instagram:** Tap to play and Download any reel now take Instagram's yes or no answers in a form that still loads if an Instagram update changes how it returns them. Before, an update like that could have crashed the patched app, and a test now stops a new hook from taking them the old way.
* **Instagram:** Checked Tap to play against a reel Instagram stops when you tap one of its stickers. Instagram's own tap away from the sticker starts it again, so Tap to play leaves it alone. That was read from Instagram 449's code, since no reel with a tappable sticker turned up on a phone.
* **Instagram:** Disable analytics now refuses Instagram's crash reports and startup pings to b-www.facebook.com too. On a test phone with the switch on, that host took about 175 KB up a minute before and gets no connection now. Instagram sends the crash reports it's been holding as it starts, before HushGram can read its switches, so those are refused even with the switch off or HushGram paused.
* **Instagram:** View stories anonymously now says what a phone showed: a story you've watched keeps its colored ring and keeps showing as new, right after you watch it and after a refresh. It used to say a watched story could show as new again.
* **Instagram:** Checked HushGram against the duplicate Download row another Morphe source fixed for Instagram 449, where a reel's menu also passes through the feed menu's builder. With Download on reels and Download feed videos on, nine menus on a phone each showed one Download row: the reel viewer's, feed videos' short and longer menus, and suggested reels in the feed.

## 0.0.2 (2026-09-30)

* **Instagram:** HushGram's first release: 21 patches for Instagram 449.0.0.52.84 (build 385511871, arm64-v8a), on Android 9 and newer.
* **Instagram:** New patch, `Hide ads`. Sponsored posts, reels and stories don't go in, and Instagram is told the ad didn't go in, so it doesn't leave a gap.
* **Instagram:** New patch, `Sanitize sharing links`. Copy link, the Android share sheet and the app buttons in Instagram's own share sheet (WhatsApp, SMS and the rest, which skip Android's) lose `stkn` (the per-share id Instagram 449 adds to every shared link), `igsh`, `igshid`, `utm_source` and Instagram's other tracking keys, and so do the post and story links Instagram's server hands out for sharing. Keys come off Instagram's own links only. A link in someone's bio opens its page directly instead of going through `l.instagram.com`, Instagram's click tracker.
* **Instagram:** New patch, `Disable analytics`. Instagram's usage events go to your phone's loopback address, which refuses them, instead of to Instagram's and Facebook's logging servers.
* **Instagram:** New patch, `Remove build expired popup`. A patched Instagram doesn't update itself, and without this Instagram locks it out after a few weeks with a screen that says the version is too old.
* **Instagram:** `Restore trust on re-signed builds`, from Hushfacebook. Instagram's own signature checks see Instagram's two original certificates on a build you signed yourself.
* **Instagram:** `HushGram settings`, from Hushfacebook: a switch for each feature, Pause, automatic safe mode after three quick crashes in a row, Debug logging and a diagnostic report that leaves out links, IDs, cookies and sign-in tokens. It opens from a shortcut on Instagram's launcher icon or from a row at the top of Instagram's own Settings and activity screen, and the shortcut works before you sign in too: when Instagram's sign-in screen comes up over it, the settings move in front.
* **Instagram:** Runs on Android 9 and newer, which is Instagram 449's own floor. The settings screen, the launcher shortcut, safe mode and the report export all have Android 9 and 10 paths, since Hushfacebook's code expected Android 11.
* **Instagram:** German, Spanish, Indonesian, Brazilian Portuguese and Turkish translations for everything HushGram shows.
* **Instagram:** Checked beside a patched Threads signed with the same key: neither declares anything the other does, and both open and run side by side.
* **Instagram:** New patch, `Clean up Reels`. The Follow button beside a reel's author goes, and so do the pills that push Edits, templates, Meta AI and Ray-Ban Meta glasses, and friends' activity with the comment preview. Each of the three has its own switch under Reels, and they're all on once the patch is in.
* **Instagram:** New patch, `Don't send reel watch history`. Instagram isn't told which reels you watched or how far into them you got. It only uses that to rank your Reels, so reels you've already seen may come back.
* **Instagram:** New patch, `Hide Reels in the feed`. The rows of suggested reels between posts in your home feed are gone, along with the other units that open the Reels viewer from there. A reel from someone you follow still shows as a post.
* **Instagram:** New patch, `Download any reel`. Every reel's more menu has Download, including the shorter menu some accounts get, and a tap saves the reel at your download quality (the best there is by default) without Instagram's watermark. Its switch is under Reels.
* **Instagram:** New patch, `Download any story`. The menu on anyone's story has Download. A video story saves at your download quality and a photo story at its largest size, into the same folders as reels. Its switch is under Stories.
* **Instagram:** New patch, `Download any video`, off until you pick it. Someone else's post in your feed that is a video gets Download in its menu, first in the short menu most posts open now, including the one under an "About this reel" summary. A tap saves it at your download quality without Instagram's watermark. On your own video, Instagram's own Download row, where it shows one, saves the same way. Its switch is under Downloads.
* **Instagram:** New patch, `Tap to play`, off until you pick it. Videos, reels and stories wait for your tap instead of starting on their own, and feed videos show a play button that goes away once your tap starts the video and comes back when it stops. A story or video that takes a few seconds to load after your tap still starts. In Reels, a tap on a reel that's waiting starts it and a tap on a playing one pauses it. A story that's waiting starts when you press, hold and let go, the way a paused one does. A video you started keeps playing when you drag its scrubber. Its switch is in a new Playback section.
* **Instagram:** New patch, `Resume long videos`. A video or reel over two minutes that you left partway picks up there the next time it plays, once. Drag the scrubber to start somewhere else. Live videos, ads and anything you watched to the end start as usual. Its switch under Playback starts off, and the spots it keeps are dropped after 30 days.
* **Instagram:** New patch, `Default playback quality`, off until you pick it, for anyone who wants to save data or always get the sharpest picture. Pick Data saver, Up to 480p, Up to 720p or Highest under Playback in HushGram's settings, and videos, reels and video stories start at that quality where Instagram offers it. A video with nothing at or under your pick plays the closest quality above. Instagram has no quality menu of its own, so this is the only way to choose one. Once it's in, the list starts at Auto, Instagram's own choice, so nothing changes until you pick a quality. Checked on a phone: a reel started at 240p with Data saver and at 1080p with Highest, and a video story took the pick too.
* **Instagram:** New patch, `View stories anonymously`, off until you pick it. Instagram isn't told which stories you watch, so you stay off their viewer lists. Replying or reacting still shows you, and a story you've watched can show as new again. Its switch is in the Stories section.
* **Instagram:** New patch, `Remove the advertising ID`. Instagram can't read your phone's advertising ID or tell Android's ad services which ads you saw or tapped, because the permissions for them are taken out of the build. It shows under Set when you patched.
* **Instagram:** New patch, `Turn off double tap to like`, off until you pick it. A double tap on a reel or on any post in the feed, whether it's a photo, a carousel or a video, doesn't like it, and the heart doesn't show. A single tap, double tap to skip and the Like button work as before. Its switch is under Reels.
* **Instagram:** New patch, `Open links in external browser`, on by default. A web link you tap opens in your default browser instead of Instagram's in-app browser, and a bio or caption link skips Instagram's click tracker on the way. Pages on Instagram, Facebook, Messenger, Threads and Meta stay in the app, and so do ads. Its switch is under Ads and privacy.
* **Instagram:** New patch, `Hide the Reels tab`, off until you pick it. Reels leaves the tab bar, and a start or a notification meant for it opens Home, including on accounts Instagram opens on Reels. Reels in your feed and shared reels still play. Its switch is under Reels and takes effect after Instagram restarts.
* **Instagram:** New Downloads section in HushGram's settings. It lists the saves that are running, each with a Cancel button, and holds the download quality, the save folder, the video file name and a switch that saves videos other apps can open.
* **Instagram:** New patch, `Stop Story auto-advance`. A story stays on screen until you tap or swipe, however long it runs. Turn its switch off for Instagram's timing.
* **Instagram:** `Disable analytics` also covers the analytics address Instagram's server can hand its push connection. Before, only the address built into the app went to the loopback address, and a different one from the server went through as is.
* **Instagram:** With Debug logging on, the diagnostic report says what `Disable analytics` did with each analytics address Instagram used, and which of Instagram's processes asked for it. Checked on a phone: with the switch on, nothing reached Instagram's logging server at all.
* **Instagram:** HushGram's settings screen opens with a Before you sign in note under the status line: how to sign in with the least risk to your account. The settings open before you sign in too, so it's there when it matters. A tap hides it for good, and pausing HushGram doesn't bring it back. Checked on a phone in English and German.
* **Instagram:** Morphe Manager and bundle sites like morphe-patches.software show HushGram's own icon next to the source instead of the owner's GitHub picture. The icon is `patches-bundle.png` in the repository's root.
* **Instagram:** README now leads with a Before you sign in section that spells out how to keep your account: the sign-in is the moment Instagram checks the app with Play Integrity and your phone's hardware, so use a seasoned account, prefer a Root Mount install if you're rooted, and don't clear the app's data afterward. The account section says plainly that a re-signed build can't pass those checks.
* **Tooling:** Every patched build is checked before it ships. Its resource table has to match the original's, each hook may only use registers that were free where it went in, and each call a patch adds has to be where the patch put it, once.
* **Tooling:** Added a HushGram logo and README banner in the family's style, using the pink from HushGram's settings.
* **Tooling:** The release checks keep working after the first release. A checkout with a published index no longer stops the release tooling's own tests, an ordinary push leaves an old local bundle unread instead of refusing it after the patcher pin moves (a pushed commit that still carries the older check gets that check's own switch), and the tests now run the exact checks the pre-push hook runs for the index push, network lookups and quoted test counts included. CONTRIBUTING's release steps say what that push needs: a full test run with the fixtures, and a bundle description and GitHub description that name the release.
* **Tooling:** The pre-push hook and the release scripts read git's output as UTF-8 every time. The machine-name scan and the hook's own git calls used the console's code page, which is 437 when a push starts in Git Bash, so a path with a letter past ASCII came back garbled. And with no console at all, Windows PowerShell 5.1 stopped with "The handle is invalid." before git ran. The fix comes from Hushfacebook.
* **Tooling:** Release tooling, from Hushfacebook. Before a release, `scripts/build-release-receipt.ps1` patches every Instagram build the catalog declares and writes a receipt saying what applied, which toolchain built the bundle and what the patches changed in the manifest. It asks OSV about every library the bundle carries, and it writes `SHA256SUMS.txt` for the files a release publishes. When the index goes out, the pre-push hook downloads those files and checks them against both. `scripts/validate-release-facts.ps1` keeps the README, the CHANGELOG and the bug form in line with the patch list, and it works before the first release too.
