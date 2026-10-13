`<div align="center">

  <img src="assets/readme-hero.png" alt="HushGram. Keep the moments. Cut the noise." width="900">

  <p>
    <a href="https://github.com/SysAdminDoc/HushGram/releases/latest">
      <img src="https://img.shields.io/github/v/release/SysAdminDoc/HushGram?color=E1306C" alt="Latest release">
    </a>
    <a href="LICENSE">
      <img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License GPL-3.0">
    </a>
    <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" alt="Android 9+">
    <img src="https://img.shields.io/badge/Instagram-450.0.0.50.77-E1306C" alt="Supported Instagram version">
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

# 📸 HushGram

HushGram is an independent collection of [Morphe](https://morphe.software) patches for Instagram on Android.

Customize your Instagram experience with controls for privacy, ad blocking, feed cleanup, downloads, messaging, playback, and interface customization, all from an integrated settings screen.

HushGram builds on the foundation of [Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook) and is designed to work with [Morphe Manager](https://github.com/MorpheApp/morphe-manager).

> [!NOTE]
> Most optional features are disabled until you enable them in HushGram settings. Some patches are enabled by default, and a small number apply their changes during patching.

HushGram is not affiliated with, endorsed by, or developed by Meta or the Morphe project.

## 📥 Installation

1. Install [Morphe Manager](https://github.com/MorpheApp/morphe-manager) **1.34.0 or newer**.
2. Add HushGram as a patch source using [this link](https://morphe.software/add-source?github=SysAdminDoc%2FHushGram). Alternatively, open **Sources → + → Remote** in Manager and enter `github.com/SysAdminDoc/HushGram`.
3. Download a supported Instagram APK. The configured target is [Instagram 450.0.0.50.77](https://www.apkmirror.com/apk/instagram/instagram-instagram/instagram-450-0-0-50-77-release/instagram-450-0-0-50-77-android-apk-download/).
4. Select the APK in Morphe Manager, choose the desired patches, and build the patched app.
5. Install the generated APK and open HushGram settings to configure your features.

Use HushGram as your only Instagram patch source. Combining it with another source containing conflicting patches may cause patching failures.

Alternatively, download the `.mpp` bundle from the [latest release](https://github.com/SysAdminDoc/HushGram/releases/latest) and add it through **Sources → + → Local**.

> [!WARNING]
> **Read before installing or signing in**
>
> - Modified Instagram clients may violate [Instagram's Terms of Use](https://help.instagram.com/581066165581870). Account restrictions or suspension are possible, and account safety cannot be guaranteed.
> - A patched APK is signed with your own signing key rather than Meta's original certificate. It cannot normally be installed over the official Play Store installation. Uninstalling the official app removes its local data and signs you out. Rooted devices can use Morphe Manager's Root Mount instead.
> - Consider testing with a spare account if account loss would be a concern.
> - Back up your Morphe signing key. Future updates must use the same key to install over your existing patched app without uninstalling it.

## 🩹 Patches list

HushGram provides **101 patches** for Instagram. Morphe Manager's Simple mode includes 96 patches; the remaining five require Expert mode.

<!-- PATCHES_START -->

> **[Latest release](https://github.com/SysAdminDoc/HushGram/releases/latest)** &nbsp; • &nbsp; `main` &nbsp; • &nbsp; 101 patches

<details>
<summary>📦 Instagram &nbsp; • &nbsp; 101 patches</summary>

<br>

**🎯 Supported versions**

| Property | Supported value |
|:---|:---|
| Package | `com.instagram.android` |
| Instagram version | `450.0.0.50.77` |
| ARM64 builds | `385611438`, `385611395`, `385611400`, `385611404`, `385611431` |
| x86 / x86_64 builds | `385611439`, `385611440` |
| Minimum Android | Android 9 (API 28) |

See [APKMirror](https://www.apkmirror.com/apk/instagram/instagram-instagram/) for available Instagram builds. Other versions have not been verified.

| 💊 Patch | 📜 Description | ⚙️ Manager mode |
|:---|:---|:---|
| `Accent color` | Changes Instagram's accent color across links, buttons, and highlights. | Simple |
| `Allow screenshots` | Enables screenshots and screen recording in areas where Instagram normally blocks them. | Simple |
| `Ask before a call` | Prompts for confirmation before starting a call from a chat. | Simple |
| `Ask before a like` | Confirms before liking or unliking a post. | Simple |
| `Ask before a refresh` | Confirms before refreshing Home, Reels, and other lists. | Simple |
| `Back leaves Home` | Exits Instagram when Back is pressed on Home instead of refreshing the feed. | Simple |
| `Blur the bars around Reels` | Fills empty Reel borders with a blurred background. | Simple |
| `Change the like animation` | Customizes the animation displayed when liking a post. | Simple |
| `Change version code` | Changes the app version code to prevent Play Store update prompts. Reverting requires uninstalling. | Expert |
| `Clean up Reels` | Hides selected Reel controls, prompts, and interface elements. | Simple |
| `Clear the media cache` | Clears Instagram's cached media when it exceeds the configured threshold or when requested. | Simple |
| `Control taps and volume on Reels` | Customizes single-tap behavior and mute handling in Reels. | Simple |
| `Copy comment` | Adds actions to copy a comment or its author's username. | Simple |
| `Data saver` | Reduces mobile data usage by loading smaller photos and lower-quality videos. | Simple |
| `Default playback quality` | Sets a preferred quality for videos, Reels, and video stories. | Simple |
| `Disable analytics` | Disables usage and crash reporting and skips contact and location setup screens. | Simple |
| `Don't report screenshots` | Prevents Instagram from reporting screenshots of disappearing chat media to other users. | Simple |
| `Don't save recent searches` | Prevents newly opened accounts and tags from being added to recent searches. | Simple |
| `Don't send reel watch history` | Stops Instagram from sending Reel watch-history information. | Simple |
| `Download any reel` | Adds Reel downloads without Instagram's watermark, with quality and cover options. | Simple |
| `Download any story` | Adds a download action to stories, including photos and videos. | Simple |
| `Download any video` | Adds download options for feed videos, photos, covers, and queued downloads. | Simple |
| `Download voice messages` | Adds an option to save voice messages as audio files. | Simple |
| `Emoji style` | Uses Google's emoji style inside Instagram. | Simple |
| `Find the reel you came from` | Locates and highlights a Reel when opening its creator's Reels tab. | Simple |
| `Full resolution photos` | Requests the highest available photo resolution from Instagram. | Simple |
| `Full screen Reels and Home` | Hides system bars while browsing Reels and, optionally, Home. | Simple |
| `Glass tab bar` | Adds a floating, frosted-glass bottom navigation bar on supported Android versions. | Simple |
| `Group Instagram's notifications` | Groups Instagram notifications to reduce notification-shade clutter. | Simple |
| `Hide ads` | Hides sponsored posts, Reels, and stories. | Simple |
| `Hide comments` | Hides comment buttons and comment counts in the feed. | Simple |
| `Hide group buttons on the share sheet` | Hides group-creation and group-sharing options from the share sheet. | Simple |
| `Hide highlights` | Hides story highlights on profiles. | Simple |
| `Hide Instants` | Hides Instants from the messages interface. | Simple |
| `Hide Meta AI` | Removes selected Meta AI elements from search, messages, the feed, and other areas. | Simple |
| `Hide Notes on profile pictures` | Hides Notes bubbles displayed on profile pictures. | Simple |
| `Hide paid partnership Reels` | Hides Reels marked as paid partnerships or boosted content. | Simple |
| `Hide Reels in the feed` | Removes suggested Reels from the Home feed while keeping followed accounts' posts. | Simple |
| `Hide suggested accounts in DMs` | Hides suggested accounts from the bottom of the inbox. | Simple |
| `Hide suggested accounts in Reels` | Removes suggested-account cards displayed between Reels. | Simple |
| `Hide suggested people on profiles` | Hides suggested accounts and profile-completion cards. | Simple |
| `Hide suggested posts` | Removes suggested content, surveys, and shopping elements from supported areas. | Simple |
| `Hide suggested stories` | Removes stories from accounts you do not follow. | Simple |
| `Hide the Explore grid` | Hides the Explore grid while keeping search and search results available. | Simple |
| `Hide the home feed` | Hides Home feed posts while keeping the stories row visible. | Simple |
| `Hide the notes row` | Hides the Notes row and associated Map bubble in messages. | Simple |
| `Hide the Reels tab` | Removes the Reels tab and provides additional tab-bar customization options. | Simple |
| `Hide the Repost button` | Hides the Repost button and count on posts and Reels. | Simple |
| `Hide the Share button` | Hides Share buttons and counts on posts and Reels. | Simple |
| `Hide the Threads button` | Hides the Threads button on profiles. | Simple |
| `Hide that you're typing` | Prevents your typing indicator from being shown to other users. | Simple |
| `Hide your active status` | Hides your active status from other users. | Simple |
| `HushGram launch screen` | Replaces Instagram's launch screen with HushGram branding on supported Android versions. | Simple |
| `HushGram settings` | Adds the integrated HushGram settings interface, feature controls, pause, and diagnostics. | Simple |
| `Install next to Instagram` | Installs HushGram as a separately named app alongside Instagram. | Expert |
| `Keep a seek bar on Reels` | Displays a seek bar and playback position for Reels. | Simple |
| `Keep in chat` | Keeps opened view-once and replayable chat media available after viewing. | Simple |
| `Keep Reels auto scroll on` | Preserves the Reels auto-scroll setting across navigation and restarts. | Simple |
| `Keep the reel speed` | Remembers the locked 2x playback speed for subsequent Reels. | Simple |
| `Lock your messages` | Locks Instagram or selected chats and supports hiding chats from the inbox. | Simple |
| `Long press to zoom` | Zooms into feed photos when pressing and holding them. | Simple |
| `Loop a story` | Replays each story from the beginning when it finishes. | Simple |
| `Open developer options` | Exposes Instagram's developer options and MetaConfig-related controls. | Expert |
| `Open links in external browser` | Opens supported web links in the default browser instead of Instagram's in-app browser. | Simple |
| `Profile posts as a list` | Opens other users' profile posts in a scrolling list instead of a grid. | Simple |
| `Pure black dark mode` | Applies a pure-black dark appearance to Instagram's interface. | Expert |
| `Read messages without the seen receipt` | Prevents chat openings from sending read receipts; manually marking messages as read still sends them. | Simple |
| `Remove build expired popup` | Removes the popup that blocks access when Instagram's build is considered expired. | Simple |
| `Remove the advertising ID` | Prevents Instagram from reading the device advertising ID by supplying a zeroed value. | Simple |
| `Remove the empty space at the bottom` | Removes unused space below Instagram's bottom navigation bar. | Simple |
| `Restore trust on re-signed builds` | Adjusts signing checks so supported functionality can work in re-signed builds. | Simple |
| `Resume long videos` | Remembers playback position for eligible long videos and Reels. | Simple |
| `Rounded post corners` | Adds configurable rounded corners to photos, videos, and carousel pages in supported feeds. | Simple |
| `Sanitize sharing links` | Removes tracking parameters from shared links and supported Instagram links. | Simple |
| `Save comment photo` | Adds an option to save photos or GIFs attached to comments. | Simple |
| `Save profile picture` | Adds options to view, zoom, save, and copy profile information. | Simple |
| `Saved on your profile` | Adds a shortcut to Saved content on your own profile. | Simple |
| `See who a story mentions` | Lists accounts mentioned in a story and provides shortcuts to their profiles. | Simple |
| `Show a post's exact time` | Shows exact publication times for posts and comments. | Simple |
| `Show a story's exact time` | Shows a story's publication time or remaining time. | Simple |
| `Show hidden like counts` | Displays hidden like counts when Instagram provides the underlying numbers. | Simple |
| `Show if a profile follows you` | Shows whether an account follows you and identifies non-mutual follows. | Simple |
| `Show when someone was last active` | Displays a more precise last-active time in messages. | Simple |
| `Spoof location` | Lets you select a location for Instagram's supported location features. | Simple |
| `Start feed videos with sound` | Starts feed videos with sound enabled. | Simple |
| `Start Home on Following` | Opens Home on followed accounts' posts and optionally removes the For You feed. | Simple |
| `Start on x86 devices` | Improves compatibility with supported x86 devices, emulators, and Chromebooks. | Simple |
| `Start stories with sound` | Starts stories with sound enabled. | Simple |
| `Stop background wake-ups` | Disables selected background alarms used by Instagram. | Simple |
| `Stop Reels scrolling` | Prevents swiping to the next Reel and offers an optional viewing limit. | Simple |
| `Stop Story auto-advance` | Keeps a story on screen until you navigate manually. | Simple |
| `Stop swipe to create` | Prevents sideways swipes on Home from opening the camera. | Simple |
| `Stop swiping between tabs` | Prevents sideways gestures from changing the selected main tab. | Simple |
| `Story ring size` | Customizes the size of story rings in the Home stories row. | Simple |
| `Tap to play` | Prevents supported videos from playing automatically until tapped. | Simple |
| `Turn off double tap to like` | Disables double-tap liking in supported areas while retaining the Like button. | Simple |
| `Turn off HDR brightness boosts` | Prevents HDR photos and Reels from triggering additional screen brightness. | Simple |
| `Upload stories at full quality` | Uploads video stories at up to 1080 pixels wide where supported. | Simple |
| `View DM photos and videos anonymously` | Hides the Opened notice for supported disappearing photos and videos in messages. | Expert |
| `View live anonymously` | Keeps you off the viewer list when watching Instagram Live broadcasts. | Simple |
| `View stories anonymously` | Keeps you off story viewer lists, with an optional manual mark-as-seen action. | Simple |

> **Manager mode:** `Simple` means the patch is available in Simple mode. `Expert` means it must be selected through Expert mode and **Choose patches**. This column does not indicate whether a feature switch is enabled by default.

</details>

<!-- PATCHES_END -->

## ⚙️ Settings

Open HushGram settings by long-pressing the Instagram logo at the top of Home, long-pressing the app icon and selecting **HushGram settings**, or using the entry in **Settings and activity**.

Available controls depend on the patches selected during installation. Settings include:

- **Privacy:** ads, tracking, read receipts, activity status, and anonymous viewing.
- **Experience:** feed cleanup, Meta AI, playback, stories, Reels, and navigation.
- **Downloads:** media-saving controls and download preferences.
- **Utilities:** pause, settings backup, and diagnostic reports.

Use **Pause** to temporarily disable supported HushGram features when troubleshooting.

## 🤝 Support

- **Bug reports:** [Open an issue](https://github.com/SysAdminDoc/HushGram/issues/new?template=bug_report.yml)
- **Feature requests:** [Suggest a feature](https://github.com/SysAdminDoc/HushGram/issues/new?template=feature_request.yml)
- **Questions and discussion:** [GitHub Discussions](https://github.com/SysAdminDoc/HushGram/discussions)
- **Morphe Manager issues:** [Morphe Manager issue tracker](https://github.com/MorpheApp/morphe-manager/issues)
- **Changelog:** [CHANGELOG.md](CHANGELOG.md)

For patching failures, include the Instagram build, Morphe Manager version, affected patch, and relevant error details. Remove sensitive information before sharing diagnostic reports.

## 🔒 Privacy

HushGram does not operate its own backend or collect analytics. Media requests initiated by supported features are sent over HTTPS to Meta's media hosts. Diagnostic reports remain on your device until you explicitly copy or share them.

## 🙌 Credits

HushGram builds on work from [Hushfacebook](https://github.com/SysAdminDoc/Hushfacebook), [Morphe](https://github.com/MorpheApp), and [ReVanced](https://github.com/ReVanced). Some MetaConfig flag mappings and recommendations are based on [piko](https://github.com/crimera/piko).

Source attribution, licensing information, and provenance records are maintained in the repository, including [NOTICE](NOTICE), [provenance.json](provenance.json), and the [source documentation](docs/sources.md).

## 📜 License

HushGram is licensed under the [GNU General Public License v3.0](LICENSE), with applicable additional notices and conditions recorded in [NOTICE](NOTICE).

Instagram, Facebook, and Meta are trademarks of Meta Platforms, Inc. HushGram is an independent third-party project and is not affiliated with Meta.

<p align="center">
  <a href="https://ko-fi.com/X8K126YVER">
    <img src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" height="42" alt="Support HushGram on Ko-fi">
  </a>
</p>`
