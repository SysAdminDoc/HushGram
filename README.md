<div align="center">

  <img src="assets/readme-hero.png" alt="HushGram. Keep the moments. Cut the noise." width="900">

  <p>
    <img src="https://img.shields.io/badge/version-0.0.11-E1306C" alt="Version 0.0.11">
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License GPL-3.0"></a>
    <img src="https://img.shields.io/badge/platform-Android%209%2B-3DDC84" alt="Platform Android 9+">
    <img src="https://img.shields.io/badge/Instagram-451.0.0.53.80-E1306C" alt="Supported Instagram version">
    <img src="https://img.shields.io/badge/Morphe%20Manager-1.34.0%2B-8A2BE2" alt="Morphe Manager 1.34.0 or newer">
  </p>

  <p>
    <a href="https://github.com/SysAdminDoc/HushGram/releases/latest">Download patches</a>
    &nbsp;•&nbsp;
    <a href="https://morphe.software/add-source?github=SysAdminDoc%2FHushGram">Add to Morphe Manager</a>
    &nbsp;•&nbsp;
    <a href="CHANGELOG.md">Changelog</a>
  </p>

</div>

# 👋🧩 HushGram Patches

## ❓ About

HushGram is an independent collection of [Morphe](https://morphe.software) patches for Instagram on Android. It adds controls for privacy, ad blocking, feed cleanup, downloads, messaging, playback, and interface customization through an integrated settings screen.

HushGram builds on the foundation of [Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook) and is designed to work with [Morphe Manager](https://github.com/MorpheApp/morphe-manager). It is not affiliated with, endorsed by, or developed by Meta or the Morphe project.

### Current release and patch status

| Item | Current status |
|---|---|
| Latest tagged release | [`v0.0.11`](https://github.com/SysAdminDoc/HushGram/releases/tag/v0.0.11), published October 15, 2026 |
| Released patch count | **105 patches** |
| Morphe Manager simple mode | **100 patches**; 5 additional patches require Expert mode |
| Instagram target | `451.0.0.53.80` (`com.instagram.android`) |
| Morphe Manager requirement | `1.34.0` or newer |
| Minimum Android version | Android 9 (API 28) |
| Development status | Active. See [CHANGELOG.md](CHANGELOG.md) for unreleased changes; they may not be included in the latest tagged release. |
| Verified build variants | Five `arm64-v8a` builds and three x86/x86_64 builds, listed under [Supported Instagram builds](#supported-instagram-builds) |

The latest downloadable bundle is available from [GitHub Releases](https://github.com/SysAdminDoc/HushGram/releases/latest). For the newest source changes, see the [repository](https://github.com/SysAdminDoc/HushGram) and [changelog](CHANGELOG.md).

> [!NOTE]
> Most optional features are disabled until enabled in HushGram settings. Some features start enabled, while a small number of patches apply their changes during patching.

### How to use these patches

Add HushGram to Morphe Manager using this link:

**[Add HushGram to Morphe Manager](https://morphe.software/add-source?github=SysAdminDoc%2FHushGram)**

1. Install [Morphe Manager](https://github.com/MorpheApp/morphe-manager) `1.34.0` or newer.
2. Open the add-source link on your Android device. Alternatively, in Manager open **Sources**, tap **+**, choose **Remote**, and enter `github.com/SysAdminDoc/HushGram`. Use HushGram as your only Instagram patch source; combining it with another source can interfere with patching.
3. Download Instagram `451.0.0.53.80` from APKMirror. The recommended file is the **arm64-v8a, 480–640 dpi, Android 9.0+, build `385713073`** variant. See [Supported Instagram builds](#supported-instagram-builds) for other listed builds.
4. On a standard, non-rooted installation, uninstall the Play Store version of Instagram before installing the patched build. The patched APK is signed with your own key and normally cannot be installed over Meta's signed app. Uninstalling deletes local app data and signs you out, so make sure you can sign in again. On a rooted device, Morphe Manager's **Root Mount** can mount HushGram over the Play Store app instead.
5. In Morphe Manager, select the Instagram APK, choose the patches you want, and patch it.
6. Open Instagram and configure features in **HushGram settings**. You can find it at the top of **Settings and activity**; long-pressing the Instagram logo at the top of your feed may also open it.

> [!WARNING]
> **Read before installing or signing in**
>
> - Modified Instagram clients may violate [Instagram's Terms of Use](https://help.instagram.com/581066165581870). Account restrictions or suspension are possible; account safety cannot be guaranteed.
> - A patched APK uses your signing key rather than Meta's original certificate. It normally cannot be installed over the official Play Store app. Uninstalling the official app removes its local data and signs you out; Root Mount is an alternative on rooted devices.
> - Consider testing with a spare account if losing access would be a concern.
>
> HushGram does not post, like, follow, or message on your behalf, and it does not change how you sign in. Some features available in other Instagram modifications are deliberately not included. Reports of account restrictions with other modifications do not establish a specific cause, so no guarantee about account safety is possible.

### Keep your signing key

Morphe Manager signs the patched Instagram with a key stored on your device. Android only installs an update over your existing patched app when the update uses the same key. Keeping the same key allows your app data and sign-in to survive updates.

Back up the key after your first patch. In Morphe Manager, open **Settings → System → Import & export → Signing key**, choose **Export**, and store `Morphe.keystore` somewhere private. Anyone who obtains the key may be able to sign an APK that Android accepts as an update. On a new device, import the same key before patching. If you lose it or use a different key, you may need to uninstall the existing patched app, which deletes its local data and signs you out. See Morphe's [Backup and keystore guide](https://github.com/MorpheApp/morphe-manager/blob/main/docs/backup-and-keystore.md).

## 🩹 Patches list

HushGram provides **105 patches** for `com.instagram.android`, checked against Instagram `451.0.0.53.80`. Morphe Manager's simple mode includes 100 patches; five additional patches require Expert mode. Most patches expose their options through HushGram settings. A few apply changes as soon as they are included in the patched APK.

<!-- PATCHES_START EXPANDED -->

#### The patch list is generated automatically when the release workflow creates a release.

<!-- Do not modify this section by hand. The patch list is generated when release.yml creates a new release.
     If you wish for the patches list to be collapsed, remove the word 'EXPANDED' from the comment tag above.
     If you wish to manually keep this list updated, remove the PATCHES_START and PATCHES_END comment blocks entirely. -->

<!-- PATCHES_END -->

### Patch reference

The following table documents the features currently described by this README. Feature availability can depend on the selected patch set, the Instagram build, and whether the relevant setting is enabled.

| Patch | What it does | Simple mode |
|---|---|---|
| `Accent color` | Swaps Instagram's blue on Follow buttons, links and other highlights for purple, pink, red, orange, green or teal. Restart Instagram to see a change. Starts off. Turn it on in HushGram settings > Layout. | Included |
| `Allow screenshots` | Lets you take screenshots and record your screen where Instagram normally blocks it, such as disappearing photos and videos in chats. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Ask before a call` | Asks you to confirm before a call starts from a chat, so a stray tap on the call button doesn't ring anyone. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Ask before a like` | Asks you to confirm before the Like button under a post likes or unlikes it, so a stray tap doesn't do it for you. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Ask before a refresh` | Asks you to confirm before pulling down refreshes Home, Reels or another list, so an accidental pull doesn't replace what you were reading. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Back leaves Home` | Makes Back on Home leave Instagram at once, instead of first scrolling the feed to the top and reloading it. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Blur the bars around Reels` | Shows a blurred copy of a reel above and below it, or beside it, where Instagram would leave black bars. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Change the like animation` | Lets you pick the animation that plays when you double tap a post to like it. The choices are animations Instagram made for its Rings creators. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Change version code` | Stops Google Play from offering Meta's updates over your patched Instagram. Going back to the normal Instagram later means uninstalling first, which deletes its data. Later HushGram builds need this patch too. Works as soon as you patch it in, with no switch. | Opt-in |
| `Clean up Reels` | Hides extra buttons and prompts on reels, such as Follow, Edits, templates, Meta AI, Ray-Ban Meta glasses, friends' activity, the Add a comment bar, the reply bar on reels from chats, the Save button and the Reels tab's top bar. Each one has its own switch, and Burn-in guard can dim a reel's buttons while you aren't touching the screen. Keep clear mode on carries Instagram's clear mode to the next reels. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Clear the media cache` | Frees storage by deleting Instagram's saved copies of photos and videos once they pass 500 MB. Your sign-in, drafts and settings stay. A row clears them right away. Starts off. Turn it on in HushGram settings > Storage. | Included |
| `Control taps and volume on Reels` | Lets you choose what a single tap on a reel does: Instagram's default, pause, or mute. Can also keep a reel muted when you press volume up. Both start at Instagram's behavior. Choose them in HushGram settings > Playback. | Included |
| `Copy comment` | Adds Copy and Copy username to the menu you get on a comment, so you can copy its text or the commenter's username. Starts off. Turn it on in HushGram settings > Comments. | Included |
| `Default playback quality` | Plays videos, reels and video stories at the quality you choose, instead of the one Instagram picks as it plays. Nothing changes until you pick a quality. On by default. Turn it off in HushGram settings > Playback. | Included |
| `Data saver` | Saves mobile data by loading smaller photos and starting videos at the lowest quality. It uses Full resolution photos and Default playback quality, so it turns both on. It starts with mobile data only. Starts off. Turn it on in HushGram settings > Playback. | Included |
| `Disable analytics` | Stops Instagram from sending usage reports and crash reports to Instagram and Facebook. It also skips the contacts and location setup screens. Restart Instagram after you change it. On by default. Turn it off in HushGram settings > Ads and privacy. | Included |
| `Don't save recent searches` | Keeps the accounts and tags you open from search out of your Recent list. Searches already in Recent stay until you clear them. Starts off. Turn it on in HushGram settings > Explore. | Included |
| `Don't report screenshots` | Stops Instagram from telling people when you take a screenshot of their disappearing photo or video. Ghost mode, at the top of Ads and privacy, turns it on with the others. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Don't send reel watch history` | Stops Instagram from learning which reels you watched and how far you got. Reels you've already watched may come back. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Download any reel` | Adds Download to the menu of every reel and saves it without Instagram's watermark. You choose the quality, and best is the default. A separate Download cover switch also saves the reel's cover picture, the largest one the post carries. Download as video on a carousel with music saves every page, in order. A separate switch under Downloads puts a download icon under Save beside each reel. On by default. Turn it off in HushGram settings > Reels. | Included |
| `Download any story` | Adds Download to the menu of anyone's story. Videos save at the quality you choose and photos at their largest size. On by default. Turn it off in HushGram settings > Stories. | Included |
| `Download any video` | Adds Download to video posts in your feed, saving the video without Instagram's watermark. Other switches add Download for photos, Download cover, a Details row with the post's time and poster, a download icon beside Save under each post, and Hold to download, which saves a post or reel you hold two fingers on. Download all waits its turn, with up to ten posts queued, so two posts' pages never mix in your gallery. Starts off. Turn it on in HushGram settings > Downloads. | Included |
| `Download voice messages` | Adds Save to the menu you get when you hold a voice message in a chat. It saves the recording as an audio file. Starts off. Turn it on in HushGram settings > Downloads. | Included |
| `Emoji style` | Shows every emoji in Google's style instead of your phone's own. The full change shows after you restart Instagram. Starts off. Turn it on in HushGram settings > Layout. | Included |
| `Find the reel you came from` | Open a creator's profile from a reel, then their Reels tab, and it scrolls to that reel and outlines it for a moment. Stop looking ends the search, and Wait for a tap to find it holds it until you tap Find the reel. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Font` | Lets you pick the font Instagram's text uses: Instagram's own, your phone's, or a .ttf or .otf file. Restart Instagram to see a change. Starts on Instagram's font. Pick one in HushGram settings > Layout. | Included |
| `Full resolution photos` | Loads photos in the largest size Instagram sends instead of one picked for your screen. A second switch asks for a larger size on screens under 1440 pixels wide. Uses more data. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Full screen Reels and Home` | Hides the status bar and the navigation bar while Reels shows, and optionally while Home does. Swipe in from the edge to bring them back for a moment. Back and the keyboard work as usual. Starts off. Turn it on in HushGram settings > Reels (Full screen Reels) and Feed (Full screen Home). | Included |
| `Glass tab bar` | Draws the tab bar as a floating, rounded pill of frosted glass that blurs what's behind it (Android 12 and newer), with a highlight on the tab you're on. Content can optionally run down behind it. Starts off. Turn it on in HushGram settings > Tab bar, then restart Instagram. | Included |
| `Group Instagram's notifications` | Puts Instagram's notifications in one group, or in a group for each type, so they don't fill your notification shade. Tapping one still opens it. Starts off. Turn it on in HushGram settings > Notifications. | Included |
| `Hide ads` | Hides sponsored posts, reels and stories, and leaves no gap where an ad would have been. On by default. Turn it off in HushGram settings > Ads and privacy. | Included |
| `Hide AI-labeled reels` | Leaves out reels that carry Instagram's own AI label. A reel without that label stays. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Hide comments` | Takes the Comment button and the comment count off posts in your feed. Starts off. Turn it on in HushGram settings > Comments. | Included |
| `Hide group buttons on the share sheet` | Takes the New group button and the button that sends to several people as a group off the share sheet. You can still start a group from your messages. Starts off. Turn it on in HushGram settings > Sharing. | Included |
| `Hide highlights` | Takes the row of story highlights off profiles, yours and other people's. Bios, counts and posts stay. Starts off. Turn it on in HushGram settings > Profiles. | Included |
| `Hide Instants` | Takes the stack of Instants out of your messages. Instants is Instagram's no-edit camera for sharing quick photos with friends. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Hide Meta AI` | Removes Meta AI from Instagram's search bars, messages, home top bar, reels menus, share sheet and feed. Each part has its own switch. The search one and the Meta AI posts one start on, the rest start off. Change them in HushGram settings > Meta AI. | Included |
| `Hide Notes on profile pictures` | Takes the Notes bubble off profile pictures, yours included. Notes in Messages stay. Starts off. Turn it on in HushGram settings > Profiles. | Included |
| `Hide Reels in the feed` | Removes the rows of suggested reels from your home feed. A reel posted by someone you follow stays. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Hide the home feed` | Empties your home feed on purpose, so Home shows only the stories row. Profiles, Explore and Reels still show posts. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Hide the Explore grid` | Empties the grid of posts and reels on the Search tab. Search, recent searches and results stay. A second switch hides the suggested accounts that show when you tap the search bar. Starts off. Turn it on in HushGram settings > Explore. | Included |
| `Hide paid partnership Reels` | Leaves out reels with a Paid partnership label, and reels Instagram marks as boosted. Ads have their own switch. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Hide suggested accounts in DMs` | Takes the Accounts to follow section off the bottom of your messages. Your chats and follow requests stay. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Hide suggested accounts in Reels` | Leaves out the cards of accounts to follow that Instagram puts between reels. Every reel still plays. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Hide suggested people on profiles` | Takes Suggested for you and the Discover people button off profiles. Bios, counts and posts stay. More switches take Suggested for you off the followers and following lists, the Complete your profile cards off your own profile, and People you don't follow back and suggested threads and people off your notifications. Starts off. Turn it on in HushGram settings > Profiles. | Included |
| `Hide suggested posts` | Removes posts from accounts you don't follow, suggested accounts, surveys and shopping rows from Home. It also takes shop tiles out of Explore and the shopping bag off posts with tagged products. Posts from accounts you follow stay. Extra switches can also hide the 'Are you interested in this post?' question, all videos, photos, carousels, posts you've liked or posts from accounts you pick. A post's menu has Hide posts from this account to add one to that list, and those posts leave Home on the next refresh. On by default. Turn it off in HushGram settings > Feed. | Included |
| `Hide suggested stories` | Removes stories from accounts you don't follow from the row at the top of Home. More switches hide rewinds and memories, or the whole row. Starts on. Turn it off in HushGram settings > Stories. | Included |
| `Hide that you're typing` | Stops the people you're chatting with from seeing when you're typing. You still see when they type. Ghost mode, at the top of Ads and privacy, turns it on with the others. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Hide the notes row` | Takes the row of notes, and the Map bubble in it, off the top of your messages. Your chats stay. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Hide the Reels tab` | Takes the Reels tab off the tab bar. Reels in your feed and reels people send you still open. A second switch, Show the Reels tab, puts it back after Home when Instagram built your bar without one, and Hide wins if both are on. Restart Instagram to see the change. Starts off. Turn it on in HushGram settings > Reels. Under Tab bar, switches do the same for Home, Search, Create and Profile, two put Messages or Activity on the bar when Instagram left them off, switches take the Create and notifications buttons off Home's header, others add a Search, Messages or Ghost mode button to it or put Instagram's logo in its middle, two center the name at the top of profiles and the title on other screens, a list puts the tabs in your order, and another picks the tab Instagram opens on. | Included |
| `Hide the Repost button` | Takes the Repost button and its count off posts and reels, so nothing gets reposted by mistake. Share still works. Starts off. Turn it on in HushGram settings > Sharing. | Included |
| `Hide the Share button` | Takes the Share button and its count off posts in your feed and off reels. Starts off. Turn it on in HushGram settings > Sharing. | Included |
| `Hide the Threads button` | Takes the Threads button off the top of profiles. The menu and the other buttons stay. Starts off. Turn it on in HushGram settings > Profiles. | Included |
| `Hide your active status` | Stops people from seeing Active now for you while you use Instagram. You still see when they're active. Ghost mode, at the top of Ads and privacy, turns it on with the others. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `HushGram launch screen` | Shows HushGram's logo instead of Instagram's on the screen you see while Instagram starts, on Android 13 and newer. Starts off. Turn it on in HushGram settings > Layout. | Included |
| `HushGram settings` | Adds a HushGram settings page to Instagram. Open it by long-pressing the Instagram logo at the top of your feed or Instagram's icon, or from the top of Settings and activity. Turn features on or off there, pause HushGram and save diagnostics. Works as soon as you patch it in, with no switch. | Included |
| `Install next to Instagram` | Installs this build as a second Instagram beside your normal one, with its own data and sign-in. Pick its package name and app name in the patch's options, and whether its icon gets a HushGram badge. Works as soon as you patch it in, with no switch. | Opt-in |
| `Keep a seek bar on Reels` | Keeps a seek bar under every reel, short ones too, with the time played and the reel's length above it, like 0:10 / 0:55. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Keep in chat` | Keeps view once and replayable photos and videos in your chats after you open them, so they don't disappear. A photo or video you send stays Instagram's own sent bubble. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Keep Reels auto scroll on` | Keeps Instagram's auto scroll in Reels turned on after you leave Reels or restart Instagram, until you turn it off yourself. It can also move on to the next reel when one ends on accounts where Instagram doesn't offer auto scroll. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Keep the reel speed` | Keeps the 2x speed on for the next reels after you lock a reel at 2x (hold its edge, then slide down), until you slide the lock off. On by default. Turn it off in HushGram settings > Reels. | Included |
| `Lock your messages` | Covers your inbox and chats, all of Instagram, or just the single chats you pick under Locked chats, until your fingerprint, face or screen lock says it's you. Message notifications only say that a message came. Chats you pick under Hidden chats leave your inbox and their notifications don't post. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Long press to zoom` | Press and hold a photo in a feed to see it twice as big around your finger, and let go to close it. Pinch to zoom still works. Keep the zoom open leaves it up after you let go, to drag and pinch from 1x to 4x until a tap or Back closes it. While its switch is on, it takes the place of Instagram's own long press on a photo. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Loop a story` | Plays a story again from the start when it ends, instead of moving on to the next one. Tap or swipe to move on. Starts off. Turn it on in HushGram settings > Stories. | Included |
| `Open developer options` | A long press on the Home tab opens Instagram's own developer options, where you can change its hidden settings. A wrong change can break parts of Instagram until you reset it. On once you patch it in. Turn it off in HushGram settings > Developer. | Opt-in |
| `Open links in external browser` | Opens web links you tap in your usual browser instead of Instagram's built-in one, without Instagram's click tracker. Instagram pages and ads still open in the app. On by default. Turn it off in HushGram settings > Ads and privacy. | Included |
| `Profile posts as a list` | Opening someone's profile takes you on to their posts as a scrolling list of full posts. Back shows the grid. Your own profile keeps its grid. Starts off. Turn it on in HushGram settings > Profiles. | Included |
| `Pure black dark mode` | Makes Instagram's dark mode pure black instead of dark gray, on the menus that slide up from the bottom too. It looks deeper and can save battery on an OLED screen. Buttons keep their grays so they stay easy to see. Works as soon as you patch it in, with no switch. | Opt-in |
| `Read messages without the seen receipt` | Opening a chat no longer tells people you've seen their messages. You still see when they've seen yours. Mark as read still tells them. Ghost mode turns it on too. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Remove build expired popup` | Stops Instagram from locking you out with a screen that says this version is too old. A patched build can't update itself, so it would stop working after a few weeks. On by default. Turn it off in HushGram settings > Updates. | Included |
| `Remove the advertising ID` | Stops Instagram from reading your phone's advertising ID, which ad companies use to follow you between apps. Instagram gets a string of zeros instead. Works as soon as you patch it in, with no switch. | Included |
| `Remove the empty space at the bottom` | Takes away the empty gap Instagram leaves under its tab bar for a navigation bar that isn't there. Restart Instagram to see the change. Starts off. Turn it on in HushGram settings > Layout. | Included |
| `Restore trust on re-signed builds` | Lets Instagram's own checks of who signed the app pass on a patched build, so the parts that need them keep working. Threads, Facebook and Messenger patched with the same key work too. Works as soon as you patch it in, with no switch. | Included |
| `Resume long videos` | A video or reel longer than two minutes picks up where you left off the next time you play it on the same account. Live videos and ads start as usual. Starts off. Turn it on in HushGram settings > Playback. | Included |
| `Rounded post corners` | Rounds the corners of a post's photo, video and carousel pages in Home and Following, at a small or large size. Stories, profiles, Explore and Reels stay as Instagram draws them. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Sanitize sharing links` | Removes tracking tags from the links you copy or share, and opens bio links without Instagram's click tracker. The link still opens the same post, reel, story or profile. On by default. Turn it off in HushGram settings > Ads and privacy. | Included |
| `Save comment photo` | Adds Save to the menu on a comment that has its own photo or GIF, saving the largest size Instagram sent. A GIF is saved as a .gif in Pictures/Instagram, taken from Instagram's own copy, not Giphy. If Instagram lists only an animated WebP or an MP4 for it, you get that file instead, and an MP4 goes in Movies/Instagram like a video. Starts off. Turn it on in HushGram settings > Comments. | Included |
| `Save profile picture` | Adds Save profile picture, View profile picture, Copy username and Copy bio to the menu on someone's profile. View opens the picture full screen and lets you zoom. Starts off. Turn it on in HushGram settings > Downloads. | Included |
| `Saved on your profile` | Adds a bookmark to the tabs on your own profile, beside posts, reels and tagged, that opens Saved. Starts off. Turn it on in HushGram settings > Profiles, then restart Instagram. | Included |
| `Show a post's exact time` | Shows the date and time a post went up, like Oct 2, 3:45 PM, instead of how long ago. It does the same for comments. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Show a reel's exact time` | Adds the date and time a reel was posted under the author's name, like Oct 2, 3:45 PM, with seconds if you want them. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Show a story's exact time` | Shows the date and time a story was posted, like Oct 2, 3:45 PM, instead of how long ago. A choice under the switch can show the time left instead. Starts off. Turn it on in HushGram settings > Stories. | Included |
| `See who a story mentions` | Adds a small label in a story's header showing how many accounts it mentions. Tap it for a list and tap an account to open their profile. Starts off. Turn it on in HushGram settings > Stories. | Included |
| `Show hidden like counts` | Shows how many likes a post or reel has when its owner hid the count, as long as Instagram's server still sends the number. If it doesn't, nothing changes. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Show if a profile follows you` | Adds Follows you or Doesn't follow you beside the name on someone's profile. Other switches add a chip and mark accounts on your Following list that don't follow you back. The chip is blue for Follows you, green for Following each other and red for Doesn't follow you, and it can go small under the profile picture. On by default. Turn it off in HushGram settings > Profiles. | Included |
| `Show when someone was last active` | Shows the exact time someone was last on, like Active at 3:42 PM, in your messages and in a chat, instead of Active 3h ago. Active now stays. Starts off. Turn it on in HushGram settings > Messages. | Included |
| `Start Home on Following` | Opens Home on posts from accounts you follow instead of For you. A second switch removes For you from Home, and a third keeps Instagram's logo at the top of Home in place of the feed's name. Restart Instagram to see the change. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Start feed videos with sound` | Starts the first video on Home with its sound on, the same as tapping the video's speaker. Your phone's ringer and volume still count. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Start on x86 devices` | Stops Instagram from crashing or freezing on x86 devices that run its code through a translator, such as some Chromebooks and emulators. Phones and tablets with arm chips run it as before. Works as soon as you patch it in, with no switch. | Included |
| `Start stories with sound` | Opens stories with their sound on, the same as tapping Instagram's speaker. Your phone's ringer and volume still count. Starts off. Turn it on in HushGram settings > Stories. | Included |
| `Stop background wake-ups` | Stops two alarms that wake your phone while Instagram is in the background. One goes off every minute or two only to note that Instagram is still running, and the other wakes it five minutes later to upload usage events. Messages and notifications still arrive. Starts off. Turn it on in HushGram settings > Battery. | Included |
| `Stop Reels scrolling` | Stops a swipe in Reels from moving to the next reel, so you stay on the one you opened. A second switch lets you watch 20 reels, then stops swiping for 15 minutes. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Stop Story auto-advance` | Keeps each story on screen until you tap or swipe. Starts off. Turn it on in HushGram settings > Stories. | Included |
| `Stop swipe to create` | Stops a sideways swipe on Home from opening the camera. The + button still opens it. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Stop swiping between tabs` | Stops a sideways swipe from moving between Home, Reels and the other main tabs. Tapping the tab bar still works. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Story ring size` | Draws the rings in the stories row at the top of Home smaller, so more fit on screen, or larger, and the ring of a story you haven't watched in a color you pick. Nothing changes until you pick a size or a color. On by default. Turn it off in HushGram settings > Stories. | Included |
| `Tap a feed video to pause it` | A tap on the video playing in a feed pauses it in place instead of opening it in Reels. Ads keep Instagram's own tap. Starts off. Turn it on in HushGram settings > Feed. | Included |
| `Tap to play` | Makes videos, reels and stories wait for your tap instead of starting by themselves, like Instagram does when it saves mobile data. A choice under the switch can leave Reels out or hold only Reels. Starts off. Turn it on in HushGram settings > Playback. | Included |
| `Turn off double tap to like` | Stops a double tap on a post or reel from liking it, and the heart doesn't show. The Like button still works. Separate switches cover posts, reels, comments and chat messages. Starts off. Turn it on in HushGram settings > Reels. | Included |
| `Upload stories at full quality` | Sends the video stories you post at up to 1080 pixels wide instead of 720, as Instagram does with its own Upload at highest quality setting on. Starts off. Turn it on in HushGram settings > Stories. | Included |
| `Turn off HDR brightness boosts` | Stops HDR photos and reels from making the screen brighter than everything else. Starts off. Turn it on in HushGram settings > Playback. | Included |
| `View DM photos and videos anonymously` | Holds back the Opened notice for view once photos and videos in messages, so the sender doesn't see you opened them. Ghost mode turns it on too. Starts off. Turn it on in HushGram settings > Ads and privacy. | Opt-in |
| `Spoof location` | Tells Instagram your phone is at a place you pick, for the location sticker, nearby places and maps. Photos keep their own places. You need to pick a place too. Starts off. Turn it on in HushGram settings > Ads and privacy. | Included |
| `View live anonymously` | Keeps you off the viewer list of the lives you watch, so the host isn't told you're there. Your own lives still count their viewers. Ghost mode turns it on too. Starts off. Turn it on in HushGram settings > Stories. | Included |
| `View stories anonymously` | Keeps you off the viewer list of the stories you watch. Replying or reacting still shows you. An optional Mark as seen button lets you choose. Ghost mode turns it on too. Starts off. Turn it on in HushGram settings > Stories. | Included |

The patches with a switch keep their controls in **HushGram settings**, so Morphe Manager includes the settings support whenever one of those patches is selected. The following patches apply their changes during patching and do not have a normal feature switch: HushGram settings, Restore trust on re-signed builds, Remove the advertising ID, Start on x86 devices, Change version code, Install next to Instagram, and Pure black dark mode.

### Settings

Open HushGram settings by tapping it at the top of Instagram's **Settings and activity** menu. A long press on the Instagram logo at the top of the feed may also open it. From the home screen, long-press Instagram's app icon and choose **HushGram settings** when that shortcut is available.

The settings screen groups controls into categories such as Ads and privacy, Feed, Meta AI, Explore, Messages, Reels, Stories, Playback, Sharing, Comments, Profiles, Layout, Tab bar, Notifications, Storage, Downloads, Battery, Updates, and Developer. The controls shown depend on which patches you selected.

- **Pause** disables features controlled by switches without discarding your choices. Changes applied at patch time, such as removing the advertising ID, remain in the APK.
- **Ghost mode** toggles a group of privacy controls together; each control can also be managed individually.
- **Downloads** are saved to the relevant media folders. The exact folder depends on the download type and Android version.
- **Settings backup** exports settings to a JSON file. It does not include accounts, signing keys, or playback history.
- **Pause and diagnostics** can copy a quick report or save a fuller report locally. Review diagnostic text for private information before sharing it.
- **Developer** exposes Instagram's developer and MetaConfig tools. Incorrect server-flag changes can break parts of the app until reset.

If Instagram crashes within a minute of starting three times in a row, HushGram pauses itself. Re-patch or disable the patch responsible before enabling it again.

### Troubleshooting

- **Can't find settings:** Try the Instagram app-icon shortcut or the entry at the top of **Settings and activity**. If the entry is missing, refresh the source in Manager and patch again.
- **Switches do nothing:** Refreshing a source only downloads patch definitions. To add a patch to the installed app, patch and install again. Some settings require an Instagram restart.
- **Package conflict or “App not installed”:** Android will not update an app signed with a different key. Use the original signing key or uninstall the differently signed app first. Uninstalling removes local app data.
- **Unsupported Version:** Morphe Manager may recognize only the configured build number even when other listed variants can patch. Check the build number in [Supported Instagram builds](#supported-instagram-builds). Other versions have not been verified here.
- **Patching stops on one patch:** Confirm the Instagram build is supported and try with HushGram as the only enabled patch source. If the error persists, open an issue with the patch name, Manager version, and error text.
- **Instagram closes before settings can open:** Create an empty file named `hushgram-safe-mode` in `Android/data/com.instagram.android/files`. HushGram should start paused until the file is removed.
- **The Play Store keeps offering an update:** Open Instagram's Play Store page, tap the three-dot menu, and disable **Enable auto update**. The optional **Change version code** patch can stop update offers, but read its warning before selecting it.

### Supported Instagram builds

| Field | Value |
|---|---|
| Package | `com.instagram.android` |
| Version | `451.0.0.53.80` |
| `arm64-v8a` builds | `385713073` (the build Manager is configured to recognize), `385713078`, `385713082`, `385713109`, `385713116` |
| x86 / x86_64 builds | `385713117` (x86), `385713111` and `385713118` (x86_64), for Chromebooks and emulators |
| Minimum Android version | Android 9 (API 28) |
| Download source | [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/) |

Other Instagram versions have not been verified here. Meta can publish different APKs under one version name, so check the build number as well.

Meta signs genuine Instagram builds with one of two certificates. Morphe Manager warns before patching an APK signed with a different key. To check a base APK, run `apksigner verify --print-certs` from Android's build tools and compare the SHA-256 certificate digest:

```text
5f3e50f435583c9ae626302a71f7340044087a7e2c60adacfc254205a993e305  Instagram Inc. (checked by Android 7 to 12L)
3a10c50c18ba937506c2875e9047be74f8fd8b86fae2ff9feaa50a81fcb4c014  Meta Platforms Inc. (checked by Android 13 and newer)
```

## 🚀 Getting development started

Start with the [Morphe development documentation](https://github.com/MorpheApp/morphe-documentation/blob/main/docs/morphe-development/README.md) and the [patcher environment setup guide](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/2_1_setup.md#-prepare-the-environment).

Development requires JDK 17 or newer, the Android SDK, and a GitHub token with `read:packages` access so Gradle can download the Morphe patcher from GitHub Packages. The current project build configuration uses Gradle 9.8.1, Android Gradle Plugin 9.1.0, Kotlin 2.4.20, and Morphe patcher 1.15.1.

Before making changes, review [CONTRIBUTING.md](CONTRIBUTING.md), the repository's issue templates under `.github/ISSUE_TEMPLATE`, and the source/provenance records described below.

## 🧑‍💻 Dev usage

- **Make development changes on the `dev` branch** and follow the workflow documented in [CONTRIBUTING.md](CONTRIBUTING.md).
- Use semantic commit messages. The project uses `feat:` and `fix:` for user-facing changes and `chore:` for maintenance that should not create a user-facing changelog entry.
- Use the existing semantic-release workflow (`release.yml`) for releases. Do not manually upload bundles or create releases by hand; the workflow updates generated release artifacts.
- Do not manually edit generated files such as `patches-list.json`, `patches-bundle.json`, or `CHANGELOG.md`. Update the source files and let the release workflow regenerate them.
- Avoid force-pushing semantic-release commits, as this can break later releases. If a release is broken, prefer a corrective follow-up release.
- When a development build is ready for a stable release, follow the merge and release procedure in `CONTRIBUTING.md` rather than inventing a separate release process.

### Where things live

- `patches/src/main/kotlin/app/morphe/patches/instagram/` contains patches grouped by feature area. `misc/extension/` contains target lookup and shared hook helpers; `shared/compat/AppCompatibilities.kt` defines supported builds and Meta signing certificates.
- `extensions/instagram/` contains Java code that runs inside Instagram through injected hooks. `extensions/shared/library/` contains the settings screen, storage, translations, and diagnostics.
- `patches/src/test/kotlin/` and `extensions/instagram/src/test/java/` contain patch and runtime tests.
- `fixtures/` is for local Instagram APKs. These files are large and ignored; do not commit them.
- `sources/instagram-sources.json`, `provenance.json`, and `NOTICE` document code provenance, licences, and required notices.
- `patches-list.json` is the generated patch catalog. `patches-bundle.json` is the Morphe source index and changes through the release workflow.

## 🤓 Tips

- Read the [Morphe patcher documentation](https://github.com/MorpheApp/morphe-patcher/blob/main/docs/1_patcher_intro.md) for patch and fingerprint examples.
- Verify downloads using the release's `SHA256SUMS.txt`. Each release includes hashes for the `.mpp` bundle, SBOM (`.cdx.json`), and release receipt. Download the checksum file and bundle from the same release, then run:

  ```bash
  sha256sum -c SHA256SUMS.txt --ignore-missing
  ```

  On Windows, `Get-FileHash -Algorithm SHA256 .\patches-<version>.mpp` prints the hash to compare with the bundle's entry.
- HushGram does not collect data and has no backend server. Its own media requests are made over HTTPS to Meta media hosts (`cdninstagram.com`, `fbcdn.net`, and `fbsbx.com`); other media hosts are refused. Other named addresses are used for links opened when the user requests them or for the local loopback address `127.0.0.1` used by the analytics-disabling patch.
- Diagnostic reports stay on the device until copied or shared by the user. Review them before sharing.
- For a factory baseline, compare behavior against the original Meta-signed APK for the exact build on a clean emulator profile without HushGram. Record Android version, ABI, display density, Instagram build, locale, and sign-in state. Turning all HushGram switches off does not remove injected patch hooks.
- Translations are stored as UTF-8 TSV files under `extensions/shared/library/src/main/l10n`. The scripts `scripts/crowdin-l10n.py`, `scripts/sync-l10n.py`, and `scripts/gen-l10n.py` support worksheet preparation, import, and class generation. They require Python 3.12 or newer; see [docs/translations.md](docs/translations.md).
- Review [docs/sources.md](docs/sources.md) and [sources/instagram-sources.json](sources/instagram-sources.json) for patch-source details and licence records.

### Where the patches come from

| Source | What came from it |
|---|---|
| [SysAdminDoc/Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook) at `c15d4f7` | Gradle build, shared extension library and settings screen, pause and diagnostics, bytecode helpers, link cleaner, launcher shortcut, and checks that apply patches to a real Instagram build. |
| [SysAdminDoc/Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook) at `3a47363`, `814acd2`, and `830f949` | Media-save pipeline, Tap to play timing and start rules, Resume long videos state, and Default playback quality logic. |
| [andrewliang25/morphe-patches](https://github.com/andrewliang25/morphe-patches) at `5db2e57`, by way of Hushfacebook | Re-signed-build compatibility and Open links in external browser. |
| [SysAdminDoc/hushfeed](https://github.com/SysAdminDoc/hushfeed), [tiktok-patches-for-morphe](https://github.com/icysymmetra/tiktok-patches-for-morphe), [Morphe](https://github.com/MorpheApp), and [ReVanced](https://gitlab.com/ReVanced/revanced-patches) | Upstream foundation and related patching infrastructure. |

HushGram's MetaConfig flag names are based on [piko's](https://github.com/crimera/piko) mapping for Instagram 447, and the Recommended flags page follows piko's community-maintained GPL-3.0 list with HushGram-specific names. File headers and `provenance.json` identify code origins and licences.

### 🛠️ Building locally

Build the Android patch bundle with Gradle. Set `GITHUB_ACTOR` to your GitHub username and `GITHUB_TOKEN` to a token with `read:packages` access:

```bash
export GITHUB_ACTOR=<your GitHub user>
export GITHUB_TOKEN=<a token with read:packages>
./gradlew :patches:generatePatchesList
./gradlew :patches:buildAndroid
./gradlew :patches:test :extensions:instagram:testDebugUnitTest
```

The bundle is written to `patches/build/release/patches-<version>.mpp`, alongside its SHA-256 file and CycloneDX SBOM. Run `generatePatchesList` before `buildAndroid` so the bundle includes its Android payload. Set `HUSHGRAM_FIXTURE_DIR` to a directory containing stock Instagram builds to run tests that inspect real APKs; without it, those tests are skipped.

To apply every patch to a real build and compare the patched manifest with Meta's, run:

```powershell
scripts/verify-all-patches.ps1 -Apk <instagram .apks> -DesktopJar <morphe-desktop jar> -WorkDir <scratch folder>
```

For local device builds, `scripts/patch-for-device.ps1` accepts `-Keystore` and `-KeyAlias`. Use `-KeystorePassword ''` for an empty store password. A separate key password can be provided through `HUSHGRAM_SIDELOAD_KEY_PASSWORD` or `-KeyPassword`; otherwise, the store password is used. Passwords pass through a temporary argument file that is removed when patching exits. Keep the installed app's signing key so updates preserve its data.

## 📜 License

HushGram is licensed under [GNU General Public License v3.0](LICENSE), with the Morphe section 7 notices carried in [NOTICE](NOTICE). Instagram, Facebook, and Meta are trademarks of Meta Platforms, Inc.
