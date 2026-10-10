/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;
import android.text.Layout;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.text.NumberFormat;
import java.text.Normalizer;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.direct.ChatLocks;
import app.hushgram.extension.instagram.direct.HiddenChats;
import app.hushgram.extension.instagram.direct.LockDelay;
import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.media.TapToPlayScope;
import app.hushgram.extension.instagram.media.ResumePlayback;
import app.hushgram.extension.instagram.misc.OverrideExchange;
import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.instagram.feed.LikeAnimation;
import app.hushgram.extension.instagram.misc.FlagNames;
import app.hushgram.extension.instagram.misc.MediaCache;
import app.hushgram.extension.instagram.misc.NotificationGroups;
import app.hushgram.extension.instagram.misc.OverrideImport;
import app.hushgram.extension.instagram.misc.SpoofLocation;
import app.hushgram.extension.instagram.share.SharingDomain;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.instagram.stories.StoryTimeMode;
import app.hushgram.extension.instagram.misc.HapticStyle;
import app.hushgram.extension.instagram.reels.ReelTapChoice;
import app.hushgram.extension.instagram.reels.StartTab;
import app.hushgram.extension.instagram.reels.TabOrder;
import app.hushgram.extension.instagram.download.FileNameTemplate;
import app.hushgram.extension.instagram.download.SaveControl;
import app.hushgram.extension.instagram.download.SaveLeftovers;
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.Setting;
import app.hushgram.extension.shared.settings.EnumSetting;
import app.hushgram.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.hushgram.extension.shared.settings.preference.ClearLogBufferPreference;
import app.hushgram.extension.shared.settings.preference.ExportDiagnosticReportPreference;
import app.hushgram.extension.shared.settings.preference.ExportStatus;
import app.hushgram.extension.shared.settings.preference.ImmediateAction;
import app.hushgram.extension.shared.settings.preference.LogBufferManager;

/**
 * The preference list, built in code rather than from an XML resource so the bundle adds no
 * resources to Instagram. A switch appears only when its patch is in this build
 * ({@link PatchFamily}); a patch that works entirely at patch time gets a line saying so, and what
 * Pause can't reach is listed under the Pause switch. Switches are keyed by their setting, which is
 * how the shared fragment keeps them in sync with stored values. Every word is read from
 * {@link L10n} in the phone's language; the product names and the address stay as they are.
 */
@SuppressWarnings("deprecation")
public final class HushgramPreferenceFragment extends AbstractPreferenceFragment {
    /** The repository as a link, and as a person reads it. */
    static final String SOURCE_URL = "https://github.com/SysAdminDoc/HushGram";
    static final String SOURCE_ADDRESS = SOURCE_URL.substring(SOURCE_URL.indexOf("://") + 3);
    /** Where Support HushGram goes: the maintainer's Ko-fi page. */
    static final String SUPPORT_URL = "https://ko-fi.com/X8K126YVER";
    static final String SUPPORT_ADDRESS = SUPPORT_URL.substring(SUPPORT_URL.indexOf("://") + 3);
    /** The key of Support HushGram, the settings home's last row. */
    static final String SUPPORT_ROW_KEY = "action_support_hushgram";
    /** The English of the row listing what Pause can't reach, and its key in {@link L10n}. */
    static final String STAYS_WHILE_PAUSED = "Stays in while paused";
    /** The Before you sign in notice's key, which finds it on the screen. */
    static final String SIGN_IN_NOTICE_KEY = "hushgram_sign_in_notice";
    static final String CLEAR_MEDIA_CACHE_NOW = "hushgram_clear_media_cache_now";
    /** The row that lists the chats locked one at a time. */
    static final String LOCKED_CHATS_ROW = "hushgram_locked_chats_row";
    /** The row that lists the chats hidden one at a time. */
    static final String HIDDEN_CHATS_ROW = "hushgram_hidden_chats_row";
    static final String HIDDEN_ACCOUNTS_ROW = "hushgram_hidden_accounts_row";
    private static final String SCREEN_KEY = "hushgram_settings_root";
    /** The keys of the rows that open each category's page, numbered in the page's order. */
    static final String CATEGORY_ROW_KEY = "hushgram_category_page_";

    /** The first row, which says whether HushGram runs now and whether the next start changes that. */
    @Nullable
    private Preference statusCard;
    private boolean resumingFromOverview;

    @Nullable private Row clearPositions;
    private boolean changingPositions;
    private final Handler undoRefresh = new Handler(Looper.getMainLooper());
    private final Runnable refreshUndo = () -> {
        showClearPositions();
        showConfiguration();
    };
    private static final int EXPORT_CONFIGURATION = 0x4847;
    private static final int IMPORT_CONFIGURATION = 0x4848;
    private static final int EXPORT_OVERRIDES = 0x4849;
    private static final int VALIDATE_OVERRIDES = 0x484a;
    private static final int IMPORT_OVERRIDES = 0x484b;
    private static final int IMPORT_FLAG_NAMES = 0x484c;
    /** Restore, Discard, Reset and Remove flag names need no document, so they never become a document request. */
    private static final int RESTORE_OVERRIDES = 0, DISCARD_OVERRIDES = -1, RESET_OVERRIDES = -2, REMOVE_FLAG_NAMES = -3;
    /** Framework fragment callbacks run on the main thread. Never reuse a code across pages. */
    private static int documentSequence = IMPORT_FLAG_NAMES;
    private int documentRequest;
    private int documentCode;
    @Nullable private String configurationExportToken;
    private boolean changingConfiguration;
    private boolean changingOverrides;
    /** Last operation's receipt lasts for this process, including closing/reopening settings. */
    @Nullable static volatile String importFeedback;
    @Nullable private Row exportConfiguration;
    @Nullable private ExportRow exportDiagnostics;
    private final Runnable exportChanges = this::showConfiguration;
    @Nullable private Row importConfiguration;
    @Nullable private Row undoConfiguration;
    @Nullable private Row exportOverrides;
    @Nullable private Row validateOverrides;
    @Nullable static volatile String overrideExportFeedback, overrideValidationFeedback;
    /** Shown only while Allow importing overrides is on; absent rows can't be found or searched. */
    @Nullable private Row importOverrides, restoreOverrides, discardOverrides, resetOverrides;
    @Nullable private PreferenceCategory developerSection;
    /** Ghost mode's switch, and the switches it turns, both set once the page is built. */
    @Nullable private SwitchPreference ghostMode;
    private List<BooleanSetting> ghostSwitches = new ArrayList<>();
    @Nullable static volatile String overrideImportFeedback, overrideRestoreFeedback, overrideDiscardFeedback, overrideResetFeedback;
    /** Import and Remove flag names, which only change HushGram's own copy of the names. */
    @Nullable private Row importFlagNames, removeFlagNames;
    @Nullable static volatile String flagNamesFeedback;

    private String searchQuery = "";
    @Nullable private SearchRow search;
    @Nullable private Row noSearchResults;
    /** The home page's last row, which opens the Ko-fi page in a browser. */
    @Nullable private Row support;
    /** Keep the actual row objects, including their values and listeners, while filtering. */
    private final Map<PreferenceCategory, List<Preference>> searchableRows = new LinkedHashMap<>();
    private final Map<Preference, String> searchAliases = new HashMap<>();
    /** With Open categories as pages on: the row that opens each category, made with the snapshot. */
    private final Map<PreferenceCategory, Row> categoryRows = new LinkedHashMap<>();
    /** The category whose page is open, or null for the list of categories. */
    @Nullable private PreferenceCategory openCategory;
    /** The button at the top that switches between the list of categories and one long list. */
    @Nullable private Row viewToggle;
    /** True while a category's page is open and the status card, view button and search row are off the screen. */
    private boolean topHidden;
    /** Where the list of categories was scrolled to when a page opened, for Back. */
    private int categoryListPosition, categoryListTop;

    /** The page's dialogs that may still be on screen, which would outlive it. */
    private final List<Dialog> shownDialogs = new ArrayList<>();

    /** The Downloads section, where the running saves are listed, or null when no download patch is in. */
    @Nullable
    private PreferenceCategory downloads;
    @Nullable private PreferenceCategory recovery;
    @Nullable private Row interruptedSaves;
    @Nullable private Row lastCarouselSave;

    /** The rows of the saves running now, by save number. */
    private final Map<Integer, SaveRow> saveRows = new HashMap<>();

    /** Keeps the rows in step with the saves while the page is showing. Saves tell it from their own thread. */
    private final SaveControl.Watcher saves = () -> Utils.runOnMainThread(this::showSaves);

    @Override
    public void onCreate(Bundle state) {
        if (state != null) {
            documentRequest = state.getInt("hushgram_document_request", 0);
            // Older saved pages used the operation itself as their Android request code.
            documentCode = state.getInt("hushgram_document_code", documentRequest);
            documentSequence = Math.max(documentSequence,
                    Math.max(documentCode, state.getInt("hushgram_document_sequence", 0)));
            configurationExportToken = state.getString("hushgram_configuration_export");
            if (documentRequest == EXPORT_CONFIGURATION) {
                ExportStatus.State current = ExportStatus.CONFIGURATION.state();
                if (current == null) {
                    // The process restarted with a still-pending Android picker, not export history.
                    configurationExportToken = ExportStatus.CONFIGURATION.begin(
                            L10n.t("Choose a file for the settings export."), true);
                } else if (!current.active || !current.choosing || !current.token.equals(configurationExportToken)) {
                    // This saved bundle predates a result already claimed by the original page.
                    documentRequest = 0;
                    documentCode = 0;
                }
            }
        }
        super.onCreate(state);
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("hushgram_document_request", documentRequest);
        state.putInt("hushgram_document_code", documentCode);
        state.putInt("hushgram_document_sequence", documentSequence);
        state.putString("hushgram_configuration_export", configurationExportToken);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ListView list = view.findViewById(android.R.id.list);
        if (list != null) {
            list.setItemsCanFocus(true);
            list.setDivider(null);
            list.setDividerHeight(0);
            list.setBackgroundColor(Color.BLACK);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        ExportStatus.CONFIGURATION.watch(exportChanges);
        ExportStatus.DIAGNOSTICS.watch(exportChanges);
        SaveControl.watch(saves);
        SaveLeftovers.showInterrupted(getContext());
        showSaves();
        showClearPositions();
        showConfiguration();
    }

    @Override
    public void onPause() {
        ExportStatus.CONFIGURATION.unwatch(exportChanges);
        ExportStatus.DIAGNOSTICS.unwatch(exportChanges);
        SaveControl.unwatch(saves);
        undoRefresh.removeCallbacks(refreshUndo);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        undoRefresh.removeCallbacks(refreshUndo);
        // A rebuilt view starts on the list of categories, under the dialog's own title.
        if (openCategory != null) {
            openCategory = null;
            filterSettings();
        }
        for (Dialog dialog : new ArrayList<>(shownDialogs)) dialog.dismiss();
        shownDialogs.clear();
        clearPositions = null;
        exportDiagnostics = null;
        exportConfiguration = importConfiguration = undoConfiguration = null;
        recovery = null;
        interruptedSaves = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        Activity activity = getActivity();
        boolean removed = isRemoving() || (getParentFragment() != null && getParentFragment().isRemoving());
        if (documentRequest == EXPORT_CONFIGURATION && (removed || (activity != null && activity.isFinishing()))) {
            ExportStatus.CONFIGURATION.finish(configurationExportToken, L10n.t("Settings export cancelled."));
        }
        super.onDestroy();
    }

    @Override
    protected void initialize() {
        // Loads the switches before the shared fragment syncs them to the screen.
        Settings.HIDE_ADS.get();

        // Every row inflates with the theme of the context it was built with. Instagram's activity
        // theme can be light, and its near-black text would vanish on this screen's black page.
        ScreenColors.shown = null;
        Context context = themed(getContext());
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        screen.setKey(SCREEN_KEY);
        setPreferenceScreen(screen);
        searchableRows.clear();
        searchAliases.clear();
        categoryRows.clear();
        openCategory = null;

        viewToggle = new Row(context);
        viewToggle.setKey("hushgram_view_toggle");
        viewToggle.setPersistent(false);
        viewToggle.setOnPreferenceClickListener(tapped -> {
            Preference own = findPreference(Settings.CATEGORY_PAGES.key);
            if (own instanceof TwoStatePreference) {
                ((TwoStatePreference) own).setChecked(!Settings.CATEGORY_PAGES.savedValue());
            } else {
                Settings.CATEGORY_PAGES.save(!Settings.CATEGORY_PAGES.savedValue());
                filterSettings();
            }
            return true;
        });
        screen.addPreference(statusCard(context));
        // The sign-in notice stays right under the status card, so the view button goes after it.
        if (!Settings.SIGN_IN_NOTICE_HIDDEN.savedValue()) screen.addPreference(signInNotice(context, screen));
        screen.addPreference(viewToggle);
        search = new SearchRow(context);
        search.setKey("hushgram_settings_search");
        search.setTitle(L10n.t("Search settings"));
        search.setPersistent(false);
        search.setSelectable(false);
        screen.addPreference(search);
        noSearchResults = new Row(context);
        noSearchResults.setPersistent(false);
        noSearchResults.setSelectable(false);
        noSearchResults.setKey("hushgram_settings_search_empty");
        noSearchResults.setOrder(search.getOrder() + 1);
        noSearchResults.setTitle(L10n.t("No matching settings"));
        noSearchResults.setSummary(L10n.t("Try another word or clear the search."));
        // The export row below reads this; registering twice keeps one.
        PatchFamily.registerDiagnostics();
        Set<PatchFamily> build = PatchFamily.inThisBuild();
        PreferenceCategory entry = category(screen, L10n.t("Settings entry"));
        entry.addPreference(navigationRow(context));
        entry.addPreference(toggle(context, Settings.HIDE_MENU_ROW, L10n.t("Hide the HushGram row in Instagram's menu"),
                L10n.t("While a tab long press opens HushGram, Instagram's Settings and activity screen leaves out "
                        + "the HushGram row. Turn the long press off and the row comes back.")));
        entry.addPreference(toggle(context, Settings.CATEGORY_PAGES, L10n.t("Open categories as pages"),
                L10n.t("Settings shows a list of its categories, and a tap opens one on its own page. "
                        + "Search still looks through all of them.")));

        List<Preference> privacy = new ArrayList<>();
        ghostSwitches = GhostMode.switches(build);
        if (GhostMode.offered(ghostSwitches)) {
            ghostMode = ghostModeRow(context);
            privacy.add(ghostMode);
        }
        if (build.contains(PatchFamily.HIDE_ADS)) {
            privacy.add(toggle(context, Settings.HIDE_ADS, L10n.t("Hide ads"),
                    L10n.t("Hides sponsored posts, reels and stories. No empty gap is left where an ad would have "
                            + "been.")));
        }
        if (build.contains(PatchFamily.SANITIZE_SHARING_LINKS)) {
            privacy.add(toggle(context, Settings.SANITIZE_SHARING_LINKS, L10n.t("Sanitize sharing links"),
                    withCoverage(L10n.t("Removes tracking tags from links you copy or share, and opens bio links without "
                            + "Instagram's click tracker. The link still opens the same post, reel or profile."),
                            SettingsStatus.sanitizeSharingLinksCoverage())));
            privacy.add(sharingDomainRow(context));
        }
        if (build.contains(PatchFamily.EXTERNAL_BROWSER)) {
            privacy.add(toggle(context, Settings.OPEN_LINKS_EXTERNALLY, L10n.t("Open links in external browser"),
                    L10n.t("Web links you tap open in your usual browser, without Instagram's click tracker. "
                            + "Instagram pages and ads still open in the app.")));
        }
        if (build.contains(PatchFamily.DISABLE_ANALYTICS)) {
            privacy.add(toggle(context, Settings.DISABLE_ANALYTICS, L10n.t("Disable analytics"),
                    withCoverage(L10n.t("Stops Instagram from sending usage reports and crash reports to Instagram and Facebook. "
                            + "Restart Instagram to see the change."), SettingsStatus.disableAnalyticsCoverage())));
        }
        if (build.contains(PatchFamily.DM_MEDIA_SEEN)) {
            privacy.add(toggle(context, Settings.VIEW_DM_MEDIA_ANONYMOUSLY,
                    L10n.t("View DM photos and videos anonymously"),
                    L10n.t("Stops people seeing that you opened their view once photos and videos. They still "
                            + "disappear after you view them. This is a test feature and starts off.")));
        }
        if (build.contains(PatchFamily.SPOOF_LOCATION)) {
            privacy.add(toggle(context, Settings.SPOOF_LOCATION, L10n.t("Spoof location"),
                    L10n.t("Tells Instagram your phone is at the place set below, for the location sticker, nearby "
                            + "places and maps. Photos keep their own places.")));
            privacy.add(placeRow(context));
        }
        if (!privacy.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Ads and privacy"));
            for (Preference row : privacy) section.addPreference(row);
        }

        boolean suggestions = build.contains(PatchFamily.FEED_SUGGESTIONS);
        boolean following = build.contains(PatchFamily.FOLLOWING_FEED);
        boolean swipe = build.contains(PatchFamily.SWIPE_TO_CREATE);
        boolean fullResolution = build.contains(PatchFamily.FULL_RESOLUTION);
        boolean homeFeed = build.contains(PatchFamily.HOME_FEED);
        boolean tabSwipe = build.contains(PatchFamily.TAB_SWIPE);
        boolean askLike = build.contains(PatchFamily.ASK_BEFORE_LIKE);
        boolean askRefresh = build.contains(PatchFamily.ASK_BEFORE_REFRESH);
        boolean postTime = build.contains(PatchFamily.POST_TIME);
        boolean hiddenLikes = build.contains(PatchFamily.HIDDEN_LIKE_COUNTS);
        PreferenceCategory feed = suggestions || following || swipe || fullResolution || homeFeed || tabSwipe
                || askLike || askRefresh || postTime || hiddenLikes ? category(screen, L10n.t("Feed")) : null;
        if (following) {
            feed.addPreference(toggle(context, Settings.START_ON_FOLLOWING, L10n.t("Start Home on Following"),
                    L10n.t("Opens Home on posts from accounts you follow instead of For you. Tap the top of Home to "
                            + "switch. Restart Instagram to see the change.")));
            feed.addPreference(toggle(context, Settings.ONLY_FOLLOWING, L10n.t("Only accounts you follow"),
                    L10n.t("Removes For you from the choices at the top of Home, so it stays on Following or "
                            + "Favorites. Needs Start Home on Following. Restart Instagram to see the change.")));
            feed.addPreference(toggle(context, Settings.LOGO_ON_FOLLOWING, L10n.t("Instagram logo on Home"),
                    L10n.t("Keeps Instagram's logo at the top of Home in place of the feed's name. Tap it to switch "
                            + "feeds. Needs Start Home on Following. Restart Instagram to see the change.")));
        }
        if (suggestions) {
            feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_ACCOUNTS, L10n.t("Hide suggested accounts"),
                    L10n.t("Hides the rows of accounts, shops and hashtags Instagram suggests you follow.")));
            feed.addPreference(toggle(context, Settings.HIDE_SUGGESTED_POSTS, L10n.t("Hide suggested posts"),
                    L10n.t("Hides posts and reels from accounts you don't follow, marked Suggested for you. Posts "
                            + "from accounts you follow stay.")));
            feed.addPreference(toggle(context, Settings.HIDE_THREADS_POSTS, L10n.t("Hide Threads posts"),
                    L10n.t("Hides the posts, accounts and communities from Threads that Instagram mixes into your "
                            + "feed.")));
            feed.addPreference(toggle(context, Settings.HIDE_FEED_SURVEYS, L10n.t("Hide surveys"),
                    L10n.t("Hides the cards between posts that ask you to rate what you saw.")));
            feed.addPreference(toggle(context, Settings.HIDE_FEED_SHOPPING, L10n.t("Hide shopping"),
                    L10n.t("Hides the rows of products to shop and live shopping that Instagram puts between posts, "
                            + "and the shop tiles in Explore. Posts with tagged products don't show the shopping "
                            + "bag.")));
            if (PatchFamily.feedTypesInBuild()) {
                feed.addPreference(toggle(context, Settings.HIDE_FEED_VIDEOS, L10n.t("Hide videos"),
                        L10n.t("Removes every single-video post and reel from Home, even from accounts you follow. "
                                + "Pull down on Home to refresh after changing it.")));
                feed.addPreference(toggle(context, Settings.HIDE_FEED_PHOTOS, L10n.t("Hide photos"),
                        L10n.t("Removes every single-photo post from Home, even from accounts you follow. Pull down "
                                + "on Home to refresh after changing it.")));
                feed.addPreference(toggle(context, Settings.HIDE_FEED_CAROUSELS, L10n.t("Hide carousels"),
                        L10n.t("Removes every post with more than one photo or video from Home, even from accounts "
                                + "you follow. Pull down on Home to refresh after changing it.")));
                feed.addPreference(toggle(context, Settings.HIDE_FEED_LIKED, L10n.t("Hide posts you've liked"),
                        L10n.t("Removes posts you've already liked from Home and Following. A post you like now "
                                + "stays until you pull down on Home to refresh.")));
                feed.addPreference(hiddenAccountsRow(context));
            }
        }
        if (homeFeed) {
            feed.addPreference(toggle(context, Settings.HIDE_HOME_FEED, L10n.t("Hide the home feed"),
                    L10n.t("Empties Home on purpose, so you see the stories row and nothing under it. Profiles, "
                            + "Explore and Reels still show posts. Pull down on Home to refresh after changing it.")));
        }
        if (swipe) {
            feed.addPreference(toggle(context, Settings.STOP_SWIPE_TO_CREATE, L10n.t("Stop swipe to create"),
                    L10n.t("A sideways swipe on Home no longer opens the camera. The + button and every other way "
                            + "into the camera still work.")));
        }
        if (tabSwipe) {
            feed.addPreference(toggle(context, Settings.STOP_TAB_SWIPING, L10n.t("Stop swiping between tabs"),
                    L10n.t("A sideways swipe no longer moves you between Home, Reels and the other main tabs. "
                            + "Tap the tab bar instead. Stop swipe to create covers the camera.")));
        }
        if (fullResolution) {
            feed.addPreference(toggle(context, Settings.FULL_RESOLUTION_PHOTOS, L10n.t("Full resolution photos"),
                    L10n.t("Loads photos in your feed, carousels and opened posts at the largest size Instagram "
                            + "offers. This can use more data.")));
            feed.addPreference(toggle(context, Settings.ASK_FOR_LARGER_PHOTOS, L10n.t("Ask for larger photos"),
                    L10n.t("On a phone under 1440 pixels wide, asks Instagram for photos sized for a wider screen. "
                            + "This uses more data. Restart Instagram to see the change.")));
        }
        if (askLike) {
            feed.addPreference(toggle(context, Settings.ASK_BEFORE_LIKE, L10n.t("Ask before a like"),
                    L10n.t("Asks before the Like button under a post likes or unlikes it, so a stray tap doesn't do "
                            + "it for you. Double taps aren't asked about.")));
        }
        if (askRefresh) {
            feed.addPreference(toggle(context, Settings.ASK_BEFORE_REFRESH, L10n.t("Ask before a refresh"),
                    L10n.t("Asks before pulling down refreshes Home, Reels or another list. Cancel keeps what's on "
                            + "screen.")));
        }
        if (postTime) {
            feed.addPreference(toggle(context, Settings.SHOW_POST_TIME, L10n.t("Show a post's exact time"),
                    L10n.t("Shows when a post and its comments went up, like Oct 2, 3:45 PM, instead of how long "
                            + "ago. Posts you load after a change show it.")));
        }
        if (hiddenLikes) {
            feed.addPreference(toggle(context, Settings.SHOW_HIDDEN_LIKE_COUNTS, L10n.t("Show like counts the poster hid"),
                    L10n.t("Shows how many likes a post or reel has when its owner hid the count, but only when "
                            + "Instagram still sends the number. If it doesn't, nothing changes. Posts you load "
                            + "after a change show it.")));
        }

        if (build.contains(PatchFamily.META_AI)) {
            PreferenceCategory metaAi = category(screen, L10n.t("Meta AI"));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_SEARCH, L10n.t("Hide Meta AI in search and Home's bar"),
                    L10n.t("Gives Search and your messages a plain search bar and removes Meta AI's buttons, "
                            + "follow-up bar and inbox row. Restart Instagram to see the change.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_POSTS, L10n.t("Hide Meta AI posts"),
                    L10n.t("Hides Meta AI's videos, chats and pictures of you that Instagram puts in your home feed.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_ABOUT_THIS_REEL, L10n.t("Hide About this reel"),
                    L10n.t("Removes the summary, Sources and Ask Meta AI box from a reel's more menu. In your feed "
                            + "the audio row goes too. Other options stay.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_ASK_META_AI, L10n.t("Hide Ask Meta AI in About this reel"),
                    L10n.t("About this reel keeps its summary and Sources, and only the Ask Meta AI box under them "
                            + "goes.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_SHARE_TARGET, L10n.t("Hide Meta AI in the share sheet"),
                    L10n.t("Removes Meta AI from the row at the bottom of the share sheet. Some accounts see it as "
                            + "Muse.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_PROFILE_BANNER, L10n.t("Hide the Muse card on profiles"),
                    L10n.t("Removes the Meet Muse card, with its Try Muse button, from profiles. Your other profile "
                            + "cards stay.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_PROFILE_BUTTON, L10n.t("Hide the Muse button on your profile"),
                    L10n.t("Removes the Muse button from the top bar of your profile. Your username and the menu "
                            + "stay.")));
            metaAi.addPreference(toggle(context, Settings.HIDE_META_AI_BLEND, L10n.t("Hide Blend on reels"),
                    L10n.t("Removes the Blend invite button from reels. Everything else on a reel stays.")));
        }

        if (build.contains(PatchFamily.EXPLORE_GRID) || build.contains(PatchFamily.RECENT_SEARCHES)) {
            PreferenceCategory explore = category(screen, L10n.t("Explore"));
            if (build.contains(PatchFamily.EXPLORE_GRID)) {
                explore.addPreference(toggle(context, Settings.HIDE_EXPLORE_GRID, L10n.t("Hide the Explore grid"),
                        L10n.t("Hides the grid of posts and reels under the Search tab's bar. Search, recent "
                                + "searches and results stay.")));
            }
            if (build.contains(PatchFamily.RECENT_SEARCHES)) {
                explore.addPreference(toggle(context, Settings.DONT_SAVE_RECENT_SEARCHES,
                        L10n.t("Don't save recent searches"),
                        L10n.t("What you open from search stays out of Recent, in the app and on Instagram's side. "
                                + "Searches already there stay until you clear them.")));
            }
        }

        if (build.contains(PatchFamily.NOTES_ROW) || build.contains(PatchFamily.INBOX_SUGGESTIONS)
                || build.contains(PatchFamily.INSTANTS)
                || build.contains(PatchFamily.THREAD_SEEN) || build.contains(PatchFamily.TYPING)
                || build.contains(PatchFamily.ACTIVE_STATUS)
                || build.contains(PatchFamily.MESSAGES_LOCK)
                || build.contains(PatchFamily.SCREENSHOT_REPORTS)
                || build.contains(PatchFamily.SCREENSHOT_BLOCK)
                || build.contains(PatchFamily.KEEP_IN_CHAT)
                || build.contains(PatchFamily.ASK_BEFORE_CALL)) {
            PreferenceCategory messages = category(screen, L10n.t("Messages"));
            if (build.contains(PatchFamily.NOTES_ROW)) {
                messages.addPreference(toggle(context, Settings.HIDE_NOTES_ROW, L10n.t("Hide the notes row"),
                        L10n.t("Takes the row of notes off the top of your messages, the Map bubble in it too. "
                                + "Your chats, search and requests stay.")));
            }
            if (build.contains(PatchFamily.INBOX_SUGGESTIONS)) {
                messages.addPreference(toggle(context, Settings.HIDE_INBOX_SUGGESTIONS,
                        L10n.t("Hide Accounts to follow"),
                        L10n.t("Takes the accounts Instagram suggests off the bottom of your messages. Your chats "
                                + "and follow requests stay. Restart Instagram to see the change.")));
            }
            if (build.contains(PatchFamily.INSTANTS)) {
                messages.addPreference(toggle(context, Settings.HIDE_INSTANTS, L10n.t("Hide Instants"),
                        L10n.t("Removes the stack of Instants (quick photos from friends) from your messages. "
                                + "Restart Instagram to see the change.")));
            }
            if (build.contains(PatchFamily.THREAD_SEEN)) {
                messages.addPreference(toggle(context, Settings.READ_WITHOUT_SEEN_RECEIPT,
                        L10n.t("Read messages without the seen receipt"),
                        L10n.t("Opening a chat doesn't tell people you've seen their messages, and you still see "
                                + "when they've seen yours. To let one chat know, long press it in your messages and "
                                + "tap Mark as read.")));
            }
            if (build.contains(PatchFamily.TYPING)) {
                messages.addPreference(toggle(context, Settings.HIDE_TYPING, L10n.t("Hide that you're typing"),
                        L10n.t("People you're chatting with don't see the typing dots while you write, and you "
                                + "still see theirs.")));
            }
            if (build.contains(PatchFamily.ACTIVE_STATUS)) {
                messages.addPreference(toggle(context, Settings.HIDE_ACTIVE_STATUS, L10n.t("Hide your active status"),
                        L10n.t("People don't see Active now for you while you use Instagram, and you still see when "
                                + "they're active. Restart Instagram to see the change.")));
            }
            if (build.contains(PatchFamily.SCREENSHOT_REPORTS)) {
                messages.addPreference(toggle(context, Settings.HIDE_SCREENSHOTS, L10n.t("Don't report screenshots"),
                        L10n.t("Instagram doesn't notice when you take a screenshot, so whoever sent you a disappearing photo or video isn't told. Your screenshots save as usual.")));
            }
            if (build.contains(PatchFamily.SCREENSHOT_BLOCK)) {
                messages.addPreference(toggle(context, Settings.ALLOW_SCREENSHOTS, L10n.t("Allow screenshots"),
                        L10n.t("Screenshots and screen recordings work wherever Instagram blocks them, like disappearing photos and videos. Turn on Don't report screenshots too if the sender shouldn't hear about it.")));
            }
            if (build.contains(PatchFamily.KEEP_IN_CHAT)) {
                messages.addPreference(toggle(context, Settings.KEEP_IN_CHAT, L10n.t("Keep in chat"),
                        L10n.t("Photos and videos sent as view once or replayable stay in the chat so you can open "
                                + "them again. Open the chat again to update ones already loaded.")));
            }
            if (build.contains(PatchFamily.ASK_BEFORE_CALL)) {
                messages.addPreference(toggle(context, Settings.ASK_BEFORE_CALL, L10n.t("Ask before a call"),
                        L10n.t("Tapping a call button in a chat asks first, so a stray tap doesn't ring anyone. "
                                + "Call starts it, Cancel doesn't.")));
            }
            if (build.contains(PatchFamily.MESSAGES_LOCK)) {
                messages.addPreference(lockToggle(context, Settings.LOCK_MESSAGES, L10n.t("Lock your messages"),
                        L10n.t("Your inbox and chats stay covered until your fingerprint, face or screen lock says it's "
                                + "you. Message notifications say only that a message came.")));
                messages.addPreference(lockToggle(context, Settings.LOCK_APP, L10n.t("Lock all of Instagram"),
                        L10n.t("All of Instagram stays covered until your fingerprint, face or screen lock says it's "
                                + "you, your messages too.")));
                messages.addPreference(lockDelayRow(context));
                messages.addPreference(lockedChatsRow(context));
                messages.addPreference(hiddenChatsRow(context));
            }
        }

        List<Preference> reels = new ArrayList<>();
        if (build.contains(PatchFamily.FEED_REELS)) {
            reels.add(toggle(context, Settings.HIDE_FEED_REELS, L10n.t("Hide Reels in the feed"),
                    L10n.t("The rows of suggested reels between posts in your home feed. A reel someone you "
                            + "follow posts stays.")));
        }
        if (build.contains(PatchFamily.REELS_SUGGESTIONS)) {
            reels.add(toggle(context, Settings.HIDE_REELS_SUGGESTIONS, L10n.t("Hide suggested accounts"),
                    L10n.t("The cards of people and creators to follow that Instagram puts between reels. "
                            + "Every reel still plays.")));
        }
        if (build.contains(PatchFamily.REEL_DECLUTTER)) {
            reels.add(toggle(context, Settings.HIDE_REEL_FOLLOW_BUTTON, L10n.t("Hide the Follow button"),
                    L10n.t("The Follow button beside a reel's author. Their profile still has one.")));
            reels.add(toggle(context, Settings.HIDE_REEL_CHIPS, L10n.t("Hide promotion buttons on reels"),
                    L10n.t("Hides the buttons that push Edits, templates, Meta AI and Ray-Ban Meta glasses. Live "
                            + "badges and state-controlled media labels stay.")));
            reels.add(toggle(context, Settings.HIDE_REEL_SOCIAL_FOOTER, L10n.t("Hide friends' activity and comment previews"),
                    L10n.t("The bubbles of friends who liked or commented, the Followed by and Liked by lines with "
                            + "their faces, the comment shown under a reel and the row of friends who saw it. Comments "
                            + "are still a tap away.")));
            reels.add(toggle(context, Settings.HIDE_REEL_COMMENT_BAR, L10n.t("Hide the comment bar on reposted reels"),
                    L10n.t("The Add comment bar under a reel you open from a profile's reposts. The comment button "
                            + "still opens the comments.")));
            reels.add(toggle(context, Settings.HIDE_REEL_COMMENT_BAR_EVERYWHERE, L10n.t("Hide the comment bar on all reels"),
                    L10n.t("The Add a comment bar under every reel, including the ones in the Reels tab. The comment "
                            + "button still opens the comments.")));
        }
        if (build.contains(PatchFamily.REEL_WATCH_HISTORY)) {
            reels.add(toggle(context, Settings.DONT_SEND_REEL_WATCH_HISTORY, L10n.t("Don't send reel watch history"),
                    L10n.t("Stops telling Instagram which reels you watched or how far you got. Instagram uses that "
                            + "to pick your Reels. Watched reels may come back.")));
        }
        if (build.contains(PatchFamily.REEL_DOWNLOAD)) {
            reels.add(toggle(context, Settings.DOWNLOAD_REELS, L10n.t("Download on reels"),
                    L10n.t("Adds Download to every reel's more menu, saved at your download quality. Off or paused, "
                            + "Instagram's own menu returns.")));
            reels.add(toggle(context, Settings.DOWNLOAD_REEL_COVER, L10n.t("Download cover"),
                    L10n.t("Adds Download cover under Download. It saves the still picture a reel shows before it "
                            + "plays, at its largest size.")));
        }
        if (build.contains(PatchFamily.DOUBLE_TAP_LIKE)) {
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE, L10n.t("Turn off double tap to like"),
                    L10n.t("A double tap on a post or reel no longer likes it or shows a heart. A single tap and "
                            + "the Like button work as before.")));
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_POSTS, L10n.t("On posts"),
                    L10n.t("A double tap on a post doesn't like it. Turn this off to keep double tap to like on posts.")));
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS, L10n.t("On reels"),
                    L10n.t("A double tap on a reel doesn't like it. Turn this off to keep double tap to like on reels.")));
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_COMMENTS, L10n.t("On comments"),
                    L10n.t("A double tap on a comment doesn't like it. Starts off, so comments keep double tap to like until you turn this on.")));
            reels.add(toggle(context, Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_MESSAGES, L10n.t("On messages"),
                    L10n.t("A double tap on a message in a chat doesn't react to it. Starts off. A long press still shows the reactions.")));
        }
        if (build.contains(PatchFamily.LIKE_ANIMATION)) {
            reels.add(toggle(context, Settings.CHANGE_LIKE_ANIMATION, L10n.t("Change the like animation"),
                    L10n.t("The heart that pops up when you double tap a post plays the animation you pick below. "
                            + "They're animations Instagram made for its Rings creators.")));
            reels.add(likeAnimationRow(context));
        }
        if (build.contains(PatchFamily.REELS_TAB)) {
            reels.add(toggle(context, Settings.HIDE_REELS_TAB, L10n.t("Hide the Reels tab"),
                    L10n.t("Takes Reels off the tab bar. Reels in your feed and reels people send you still open. "
                            + "Restart Instagram to see the change.")));
            reels.add(toggle(context, Settings.SHOW_REELS_TAB, L10n.t("Show the Reels tab"),
                    L10n.t("Puts Reels back on the tab bar if Instagram left it off yours. Hide the Reels tab wins if "
                            + "both are on. Restart Instagram to see the change.")));
        }
        if (build.contains(PatchFamily.KEEP_REEL_SPEED)) {
            reels.add(toggle(context, Settings.KEEP_REEL_SPEED, L10n.t("Keep the reel speed"),
                    L10n.t("Lock a reel at 2x (hold its edge, then slide down) and the next reels play at 2x too. "
                            + "Slide the lock off, or hold the edge and let go, to go back to normal speed.")));
        }
        if (build.contains(PatchFamily.REEL_SEEK_BAR)) {
            reels.add(toggle(context, Settings.REEL_SEEK_BAR, L10n.t("Keep a seek bar"),
                    L10n.t("Instagram's seek bar stays under every reel, short ones too, with the time played and "
                            + "the reel's length above it. Ads keep Instagram's own rules.")));
            reels.add(toggle(context, Settings.REEL_SEEK_THUMB, L10n.t("Show a Reel seek thumb"),
                    L10n.t("Adds a white round handle to the seek bar that you can drag. Ads keep their own bar.")));
            reels.add(toggle(context, Settings.BIG_REEL_SEEK_BAR, L10n.t("Bigger seek bar on reels"),
                    L10n.t("Makes the seek bar thicker, with a round white handle and the time above it, over a soft "
                            + "dark fade so it shows on any video. Drag it to seek. It also keeps the bar on short reels.")));
        }
        if (build.contains(PatchFamily.REEL_BLUR_BARS)) {
            reels.add(toggle(context, Settings.BLUR_REEL_BARS, L10n.t("Blur the bars around a reel"),
                    L10n.t("A reel that doesn't fill the screen shows a blurred copy of itself above and below it "
                            + "instead of black bars. Open Reels again after a change.")));
        }
        if (build.contains(PatchFamily.REEL_AUTO_SCROLL)) {
            reels.add(toggle(context, Settings.KEEP_REEL_AUTO_SCROLL, L10n.t("Keep auto scroll on"),
                    L10n.t("Once you turn on Instagram's auto scroll in Reels, it stays on after a restart or after "
                            + "you leave Reels, until you turn it off.")));
        }
        if (build.contains(PatchFamily.REEL_SCROLLING)) {
            reels.add(toggle(context, Settings.STOP_REELS_SCROLLING, L10n.t("Stop Reels scrolling"),
                    L10n.t("A swipe in Reels no longer moves on to the next reel, and pulling down doesn't load new "
                            + "ones. The reel you opened still plays. Restart Instagram to see the change.")));
            reels.add(toggle(context, Settings.REEL_CAP, L10n.t("Stop after 20 reels"),
                    L10n.t("After 20 reels, swiping in Reels stops until Instagram has been in the background for "
                            + "15 minutes. A reel you open from a message or a post still plays.")));
        }
        if (!reels.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Reels"));
            for (Preference row : reels) section.addPreference(row);
        }

        List<Preference> stories = new ArrayList<>();
        if (build.contains(PatchFamily.STORIES_TRAY)) {
            stories.add(toggle(context, Settings.HIDE_SUGGESTED_STORIES, L10n.t("Hide suggested stories"),
                    L10n.t("Hides stories from accounts you don't follow, and accounts Instagram suggests, in the "
                            + "row at the top of Home. Stories from accounts you follow stay.")));
            stories.add(toggle(context, Settings.HIDE_STORY_REWINDS, L10n.t("Hide story rewinds"),
                    L10n.t("Hides the rewind cards, which bring back old highlights, from the row of stories at the "
                            + "top of Home.")));
            stories.add(toggle(context, Settings.HIDE_STORY_RECAPS, L10n.t("Hide memories and recaps"),
                    L10n.t("Takes the memories, recaps, follow anniversaries and birthday cards Instagram makes out "
                            + "of the row of stories at the top of Home. Stories people post stay.")));
            stories.add(toggle(context, Settings.STOP_LOADING_STORIES, L10n.t("Stop loading stories"),
                    L10n.t("Nothing in the row of stories at the top of Home loads, your own story included, which "
                            + "saves data. A story ring on a profile or in a chat still opens its stories.")));
            stories.add(toggle(context, Settings.HIDE_STORIES_TRAY, L10n.t("Hide the Stories tray"),
                    L10n.t("Takes the whole row of stories off the top of Home, Your story included. Stories still "
                            + "open from a profile or a message.")));
        }
        if (build.contains(PatchFamily.STORY_RING)) {
            stories.add(toggle(context, Settings.STORY_RING, L10n.t("Story ring size"),
                    L10n.t("Draws the rings in the stories row at the top of Home at the size below. Restart "
                            + "Instagram to see the change.")));
            stories.add(storyRingRow(context));
        }
        if (build.contains(PatchFamily.STORY_AUTO_ADVANCE)) {
            stories.add(toggle(context, Settings.BLOCK_STORY_AUTO_ADVANCE, L10n.t("Stop Story auto-advance"),
                    L10n.t("A finished story stays on screen until you tap or swipe. Turn this off for Instagram's timing.")));
        }
        if (build.contains(PatchFamily.STORY_LOOP)) {
            stories.add(toggle(context, Settings.LOOP_STORIES, L10n.t("Loop a story"),
                    L10n.t("A story plays again from the start when it ends, until you tap or swipe to move on. "
                            + "Ads still move on. With Stop Story auto-advance on too, stories loop.")));
        }
        if (build.contains(PatchFamily.STORY_SOUND)) {
            stories.add(toggle(context, Settings.START_STORIES_WITH_SOUND, L10n.t("Start stories with sound"),
                    L10n.t("Stories open with their sound on, the same as tapping Instagram's speaker. Your phone's "
                            + "ringer and volume still count, so on silent or with the volume down, stories stay quiet.")));
        }
        if (build.contains(PatchFamily.STORY_TIME)) {
            stories.add(toggle(context, Settings.SHOW_STORY_TIME, L10n.t("Show a story's exact time"),
                    L10n.t("A story's header shows its time the way the choice below says, instead of how long ago. "
                            + "It follows your phone's language and 12 or 24-hour setting.")));
            stories.add(storyTimeModeRow(context));
        }
        if (build.contains(PatchFamily.STORY_MENTIONS)) {
            stories.add(toggle(context, Settings.SHOW_STORY_MENTIONS, L10n.t("See who a story mentions"),
                    L10n.t("A story's header says how many accounts it mentions, even when the mention is hidden. "
                            + "Tap that for the list, and tap someone to open their profile.")));
        }
        if (build.contains(PatchFamily.STORY_SEEN)) {
            stories.add(toggle(context, Settings.VIEW_STORIES_ANONYMOUSLY, L10n.t("View stories anonymously"),
                    L10n.t("Instagram isn't told which stories you watch, so you stay off their viewer lists. "
                            + "Replying or reacting still shows you, and stories you've watched keep showing as new.")));
            stories.add(toggle(context, Settings.MARK_STORIES_SEEN, L10n.t("Mark as seen button"),
                    L10n.t("Adds an eye button to the top of each story while you view anonymously. Tap it to show up "
                            + "on that story's viewer list. The other stories stay hidden.")));
            stories.add(toggle(context, Settings.GRAY_OUT_WATCHED_STORIES, L10n.t("Gray out stories you've watched"),
                    L10n.t("While you view anonymously, a story you've watched turns gray and moves to the end of the row, "
                            + "on this phone only. Instagram still isn't told you watched it.")));
        }
        if (build.contains(PatchFamily.LIVE_SEEN)) {
            stories.add(toggle(context, Settings.VIEW_LIVE_ANONYMOUSLY, L10n.t("View live anonymously"),
                    L10n.t("Lives you watch don't put you on the host's viewer list, so they aren't told you're there. Commenting or reacting still shows you. The viewer count you see stops updating, and a live that ends can keep looking live until you leave it.")));
        }
        if (build.contains(PatchFamily.STORY_DOWNLOAD)) {
            stories.add(toggle(context, Settings.DOWNLOAD_STORIES, L10n.t("Download on stories"),
                    L10n.t("Adds Download to the menu of anyone's story, photo or video, saved at your download quality. "
                            + "Off or paused, Instagram's own menu returns.")));
        }
        if (!stories.isEmpty()) {
            PreferenceCategory section = category(screen, L10n.t("Stories"));
            for (Preference row : stories) section.addPreference(row);
        }

        if (build.contains(PatchFamily.HDR_BOOST) || build.contains(PatchFamily.TAP_TO_PLAY)
                || build.contains(PatchFamily.RESUME_LONG_VIDEOS) || build.contains(PatchFamily.REEL_TAP_AND_VOLUME)
                || build.contains(PatchFamily.PLAYBACK_QUALITY) || build.contains(PatchFamily.DATA_SAVER)) {
            PreferenceCategory playback = category(screen, L10n.t("Playback"));
            if (build.contains(PatchFamily.TAP_TO_PLAY)) {
                playback.addPreference(toggle(context, Settings.TAP_TO_PLAY, L10n.t("Tap to play"),
                        L10n.t("Videos wait for your tap where the choice below says. Feed videos show a play button, "
                                + "as they do when you use less mobile data.")));
                playback.addPreference(tapToPlayScopeRow(context));
            }
            if (build.contains(PatchFamily.REEL_TAP_AND_VOLUME)) {
                playback.addPreference(reelTapChoiceRow(context));
                playback.addPreference(toggle(context, Settings.KEEP_REELS_MUTED, L10n.t("Keep reels muted on the volume keys"),
                        L10n.t("Volume up still raises the phone's volume, but a muted reel stays muted until you unmute it.")));
            }
            if (build.contains(PatchFamily.RESUME_LONG_VIDEOS)) {
                playback.addPreference(toggle(context, Settings.RESUME_LONG_VIDEOS, L10n.t("Resume long videos"),
                        L10n.t("Videos and reels over two minutes pick up where you left off. Seek to start elsewhere. "
                                + "Live videos and ads start as usual.")));
                clearPositions = new Row(context);
                clearPositions.setKey("hushgram_clear_resume_points");
                clearPositions.setPersistent(false);
                clearPositions.actsAtOnce = true;
                playback.addPreference(clearPositions);
                showClearPositions();
            }
            if (build.contains(PatchFamily.PLAYBACK_QUALITY)) {
                playback.addPreference(toggle(context, Settings.DEFAULT_PLAYBACK_QUALITY, L10n.t("Default playback quality"),
                        L10n.t("Videos, reels and stories play at the quality below, starting with the next one you open.")));
                playback.addPreference(playbackQualityRow(context));
                playback.addPreference(qualityTabRow(context));
            }
            if (build.contains(PatchFamily.DATA_SAVER)) {
                playback.addPreference(toggle(context, Settings.DATA_SAVER, L10n.t("Data saver"),
                        L10n.t("Loads smaller photos and starts videos, reels and stories at the lowest quality. "
                                + "Grid thumbnails stay as they are. It takes priority over Full resolution photos "
                                + "and the quality above.")));
                playback.addPreference(toggle(context, Settings.DATA_SAVER_MOBILE_DATA_ONLY,
                        L10n.t("Only on mobile data"),
                        L10n.t("Wi-Fi stays as it is. Turn this off to save data on every network.")));
            }
            if (build.contains(PatchFamily.HDR_BOOST)) {
                playback.addPreference(toggle(context, Settings.TURN_OFF_HDR_BOOSTS, L10n.t("Turn off HDR brightness boosts"),
                        L10n.t("HDR photos and reels stop brightening the screen above everything else. They show "
                                + "at the same brightness as the rest of Instagram.")));
            }
        }

        if (build.contains(PatchFamily.SHARE_SHEET) || build.contains(PatchFamily.REPOST_BUTTON)
                || build.contains(PatchFamily.HIDE_SHARE_BUTTON)) {
            PreferenceCategory sharing = category(screen, L10n.t("Sharing"));
            if (build.contains(PatchFamily.SHARE_SHEET)) {
                sharing.addPreference(toggle(context, Settings.HIDE_SHARE_SHEET_GROUP, L10n.t("Hide group buttons"),
                        L10n.t("Leaves New group out of the share sheet, and the button that sends to the people you "
                                + "picked as a group. Send separately stays, and you can still start a group from your messages.")));
            }
            if (build.contains(PatchFamily.REPOST_BUTTON)) {
                sharing.addPreference(toggle(context, Settings.HIDE_REPOST_BUTTON, L10n.t("Hide the Repost button"),
                        L10n.t("Takes Repost and its count off posts and reels, so nothing gets reposted to your "
                                + "followers by mistake. Share still sends a post or reel to someone.")));
            }
            if (build.contains(PatchFamily.HIDE_SHARE_BUTTON)) {
                sharing.addPreference(toggle(context, Settings.HIDE_SHARE_BUTTON, L10n.t("Hide the Share button"),
                        L10n.t("Takes the Share button and its count off the posts in your feed and off reels.")));
            }
        }

        if (build.contains(PatchFamily.COMMENT_COPY) || build.contains(PatchFamily.COMMENT_PHOTO)
                || build.contains(PatchFamily.HIDE_COMMENTS)) {
            PreferenceCategory comments = category(screen, L10n.t("Comments"));
            if (build.contains(PatchFamily.COMMENT_COPY)) {
                comments.addPreference(toggle(context, Settings.COPY_COMMENTS, L10n.t("Copy comment"),
                        L10n.t("Adds Copy to a selected comment's menu. Copies the original text, including line breaks.")));
                if (PatchFamily.commentAuthorInBuild()) {
                    comments.addPreference(toggle(context, Settings.COPY_COMMENT_AUTHORS, L10n.t("Copy the commenter's username"),
                            L10n.t("Adds Copy username to a selected comment's menu, for the account that wrote it.")));
                }
            }
            if (build.contains(PatchFamily.COMMENT_PHOTO)) {
                comments.addPreference(toggle(context, Settings.SAVE_COMMENT_PHOTOS, L10n.t("Save comment photo"),
                        L10n.t("Adds Save to a selected comment's menu when the comment has its own photo. Saves the largest size Instagram sent.")));
            }
            if (build.contains(PatchFamily.HIDE_COMMENTS)) {
                comments.addPreference(toggle(context, Settings.HIDE_COMMENTS, L10n.t("Hide comments"),
                        L10n.t("Takes the Comment button and the comment count off the posts in your feed.")));
            }
        }

        if (build.contains(PatchFamily.FRIENDSHIP_STATUS) || build.contains(PatchFamily.PROFILE_SUGGESTIONS)
                || build.contains(PatchFamily.PROFILE_HIGHLIGHTS) || build.contains(PatchFamily.THREADS_BUTTON)
                || build.contains(PatchFamily.SAVED_ON_PROFILE) || build.contains(PatchFamily.PROFILE_POSTS_LIST)) {
            PreferenceCategory profiles = category(screen, L10n.t("Profiles"));
            if (build.contains(PatchFamily.FRIENDSHIP_STATUS)) {
                profiles.addPreference(toggle(context, Settings.SHOW_FRIENDSHIP_STATUS, L10n.t("Show if a profile follows you"),
                        L10n.t("Adds Follows you or Doesn't follow you beside the name on someone's profile, after "
                                + "their pronouns if they've set any. Nothing shows until Instagram has checked.")));
                profiles.addPreference(toggle(context, Settings.FRIENDSHIP_STATUS_CHIP, L10n.t("Show it as a chip"),
                        L10n.t("Puts the answer in a chip under the profile's posts, followers and following counts "
                                + "instead, and says Following each other when you follow them too.")));
                if (PatchFamily.followingListMarkInBuild()) {
                    profiles.addPreference(toggle(context, Settings.MARK_FOLLOWING_LIST, L10n.t("Mark who doesn't follow you back"),
                            L10n.t("On your own Following list, adds Doesn't follow you after the name of each account that "
                                    + "doesn't follow you back. Nothing shows until Instagram has checked.")));
                    profiles.addPreference(toggle(context, Settings.FOLLOWING_NOT_BACK_FIRST, L10n.t("Put accounts that don't follow you back first"),
                            L10n.t("On your own Following list, moves the accounts that don't follow you back to the top and "
                                    + "keeps Instagram's order within each group. Accounts Instagram hasn't checked yet stay below.")));
                }
            }
            if (build.contains(PatchFamily.PROFILE_SUGGESTIONS)) {
                profiles.addPreference(toggle(context, Settings.HIDE_PROFILE_SUGGESTIONS, L10n.t("Hide suggested people"),
                        L10n.t("Takes Suggested for you and the Discover people button off profiles, yours included. "
                                + "Bios, counts, posts and follower lists stay.")));
            }
            if (build.contains(PatchFamily.PROFILE_HIGHLIGHTS)) {
                profiles.addPreference(toggle(context, Settings.HIDE_HIGHLIGHTS, L10n.t("Hide highlights"),
                        L10n.t("Takes the row of story highlights off profiles, yours included. Bios, counts and "
                                + "posts stay, and so does Add to highlight on your stories.")));
            }
            if (build.contains(PatchFamily.SAVED_ON_PROFILE)) {
                profiles.addPreference(toggle(context, Settings.SAVED_ON_PROFILE, L10n.t("Saved tab on your profile"),
                        L10n.t("Adds a bookmark to the tabs on your own profile that opens Saved. Restart Instagram "
                                + "after changing it.")));
            }
            if (build.contains(PatchFamily.THREADS_BUTTON)) {
                profiles.addPreference(toggle(context, Settings.HIDE_THREADS_BUTTON, L10n.t("Hide the Threads button"),
                        L10n.t("Takes the Threads button off the top of profiles, yours included. The menu and "
                                + "the other buttons stay where they were.")));
            }
            if (build.contains(PatchFamily.PROFILE_POSTS_LIST)) {
                profiles.addPreference(toggle(context, Settings.PROFILE_POSTS_LIST, L10n.t("Show profile posts as a list"),
                        L10n.t("Opening someone's profile takes you on to their posts as a scrolling list of full "
                                + "posts. Go Back for the grid. Your own profile keeps its grid.")));
            }
        }

        if (build.contains(PatchFamily.BOTTOM_SPACE) || build.contains(PatchFamily.EMOJI_STYLE)) {
            PreferenceCategory layout = category(screen, L10n.t("Layout"));
            if (build.contains(PatchFamily.BOTTOM_SPACE)) {
                layout.addPreference(toggle(context, Settings.REMOVE_BOTTOM_SPACE, L10n.t("Remove the empty space at the bottom"),
                        L10n.t("Removes the empty gap under Instagram's tab bar that appears when your phone hides "
                                + "its navigation bar or Instagram is in a pop-up window. Restart Instagram to see "
                                + "the change.")));
            }
            if (build.contains(PatchFamily.EMOJI_STYLE)) {
                layout.addPreference(toggle(context, Settings.NOTO_EMOJI, L10n.t("Google's emoji everywhere"),
                        L10n.t("Shows every emoji in Google's style instead of your phone's own. Restart Instagram "
                                + "to see the change.")));
            }
        }

        if (build.contains(PatchFamily.GLASS_TAB_BAR) || build.contains(PatchFamily.REELS_TAB)) {
            PreferenceCategory glass = category(screen, L10n.t("Tab bar"));
            if (build.contains(PatchFamily.REELS_TAB)) {
                glass.addPreference(toggle(context, Settings.HIDE_SEARCH_TAB, L10n.t("Hide the Search tab"),
                        L10n.t("Takes Search off the tab bar and closes up the gap. Restart Instagram to see the change.")));
                glass.addPreference(toggle(context, Settings.HIDE_CREATE_TAB, L10n.t("Hide the Create tab"),
                        L10n.t("Takes Create off the tab bar and closes up the gap. Restart Instagram to see the change.")));
                glass.addPreference(toggle(context, Settings.HIDE_PROFILE_TAB, L10n.t("Hide the Profile tab"),
                        L10n.t("Takes Profile off the tab bar. Your profile still opens from other places in the app. "
                                + "Restart Instagram to see the change.")));
                glass.addPreference(toggle(context, Settings.HIDE_HOME_CREATE_BUTTON, L10n.t("Hide Create on Home's header"),
                        L10n.t("Takes the plus button off the top of Home and closes up the gap. The Create tab and "
                                + "swiping to create are separate. Restart Instagram to see the change.")));
                glass.addPreference(toggle(context, Settings.HIDE_HOME_NOTIFICATIONS_BUTTON, L10n.t("Hide notifications on Home's header"),
                        L10n.t("Takes the heart off the top of Home and closes up the gap. Alerts still arrive. "
                                + "Restart Instagram to see the change.")));
                glass.addPreference(toggle(context, Settings.SEARCH_BUTTON_ON_HOME, L10n.t("Search button on Home's header"),
                        L10n.t("Adds a magnifier at the top of Home that opens Search, like the Search tab does. It works "
                                + "with the Search tab hidden too, unless you swipe between your tabs. Restart Instagram "
                                + "to see the change.")));
                if (!GhostMode.switches(build).isEmpty()) {
                    glass.addPreference(toggle(context, Settings.GHOST_BUTTON_ON_HOME, L10n.t("Ghost mode button on Home's header"),
                            L10n.t("Adds a ghost button at the top of Home, next to Messages when it's there and at the end "
                                    + "of the buttons when it isn't. Tap it to turn Ghost mode on or off. Restart Instagram "
                                    + "to see the change.")));
                }
                glass.addPreference(tabOrderRow(context));
                glass.addPreference(startTabRow(context));
            }
            if (build.contains(PatchFamily.GLASS_TAB_BAR)) {
                glass.addPreference(toggle(context, Settings.GLASS_TAB_BAR, L10n.t("Glass tab bar"),
                        L10n.t("Draws Instagram's tab bar as a floating, rounded glass pill with a highlight on the tab "
                                + "you're on. Restart Instagram after changing it.")));
                glass.addPreference(toggle(context, Settings.GLASS_TAB_BAR_BLUR, L10n.t("Blur behind the tab bar"),
                        L10n.t("Blurs what's behind the pill on Android 12 and newer. Off, the pill is a frosted tint.")));
                glass.addPreference(toggle(context, Settings.GLASS_TAB_BAR_HAPTICS, L10n.t("Haptics on the tab bar"),
                        L10n.t("A light tick each time the highlight slides past a tab, so it follows the bar as you tap "
                                + "or swipe between screens.")));
                glass.addPreference(hapticStyleRow(context));
                glass.addPreference(toggle(context, Settings.GLASS_TAB_BAR_FLOAT, L10n.t("Show content behind the tab bar"),
                        L10n.t("Home and the other tabs run down behind the pill, so the glass has real content to blur. "
                                + "Reels still stops above it.")));
            }
        }

        if (build.contains(PatchFamily.NOTIFICATION_GROUPS)) {
            PreferenceCategory notifications = category(screen, L10n.t("Notifications"));
            SwitchPreference grouping = toggle(context, Settings.GROUP_NOTIFICATIONS, L10n.t("Group notifications"),
                    L10n.t("Puts every notification from Instagram in one group that shows how many it holds, so they "
                            + "don't fill your notification shade. Tapping one still opens it."));
            // Turned off, HushGram's summaries come down at once. The notifications under them stay.
            grouping.setOnPreferenceChangeListener((row, value) -> {
                if (Boolean.FALSE.equals(value)) NotificationGroups.switchedOff(row.getContext());
                return true;
            });
            notifications.addPreference(grouping);
            notifications.addPreference(toggle(context, Settings.GROUP_NOTIFICATIONS_BY_TYPE, L10n.t("Group by type"),
                    L10n.t("A group for each kind of notification, such as comments or messages, in place of one group.")));
        }

        if (build.contains(PatchFamily.MEDIA_CACHE)) {
            PreferenceCategory storage = category(screen, L10n.t("Storage"));
            storage.addPreference(toggle(context, Settings.CLEAR_MEDIA_CACHE, L10n.t("Clear the media cache"),
                    L10n.t("When Instagram has more than 500 MB of saved photos and videos, deletes the photos as "
                            + "you leave it and the videos the next time it starts. Your sign-in, drafts and "
                            + "settings stay.")));
            Row clearNow = new Row(context);
            clearNow.setKey(CLEAR_MEDIA_CACHE_NOW);
            clearNow.setPersistent(false);
            clearNow.setTitle(L10n.t("Clear saved copies now"));
            clearNow.setSummary(L10n.t("Deletes the saved photos Instagram keeps to show again, whatever their size, "
                    + "and the saved videos the next time it starts."));
            clearNow.setOnPreferenceClickListener(p -> {
                clearMediaCache(p);
                return true;
            });
            storage.addPreference(mark(clearNow, SettingsIcons.DELETE));
        }

        // Any download patch brings this section, so each one that saves joins this condition.
        if (build.contains(PatchFamily.REEL_DOWNLOAD) || build.contains(PatchFamily.STORY_DOWNLOAD)
                || build.contains(PatchFamily.VIDEO_DOWNLOAD) || build.contains(PatchFamily.PROFILE_PICTURE)
                || build.contains(PatchFamily.VOICE_MESSAGE)) {
            PreferenceCategory downloads = category(screen, L10n.t("Downloads"));
            this.downloads = downloads;
            if (build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_VIDEOS, L10n.t("Download feed videos"),
                        L10n.t("Adds Download to the menu of a post in your feed with a video. Uses the quality below. "
                                + "Off or paused, Instagram's own menu returns.")));
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_FEED_COVER, L10n.t("Download video covers"),
                        L10n.t("Adds Download cover to the menu of a feed post with a video, and of a carousel showing one. "
                                + "It saves the still picture shown before the video plays, at its largest size.")));
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_PHOTOS, L10n.t("Download feed photos"),
                        L10n.t("The same Download on a photo post, and on a carousel showing a photo. Saves the largest "
                                + "size Instagram has.")));
                downloads.addPreference(toggle(context, Settings.POST_DETAILS, L10n.t("Details in a post's menu"),
                        L10n.t("Adds Details to a feed post's menu. It shows when the post went up, who posted it, "
                                + "its media ID and the size Download saves, with buttons to copy the file link, "
                                + "username and caption.")));
                downloads.addPreference(toggle(context, Settings.FEED_DOWNLOAD_BUTTON, L10n.t("Download button on feed posts"),
                        L10n.t("Puts a download icon in the row under a post, left of Save, so one tap saves it without "
                                + "opening the menu. On a carousel it asks for this page or all of them.")));
            }
            if (build.contains(PatchFamily.PROFILE_PICTURE)) {
                downloads.addPreference(toggle(context, Settings.SAVE_PROFILE_PICTURES, L10n.t("Save profile picture"),
                        L10n.t("Adds Save profile picture to the menu on someone's profile. Saves their picture at the "
                                + "largest size Instagram has.")));
                downloads.addPreference(toggle(context, Settings.VIEW_PROFILE_PICTURES, L10n.t("View profile picture"),
                        L10n.t("Adds View profile picture to the menu on someone's profile. Opens their picture full "
                                + "screen at the largest size Instagram has, with pinch zoom and a Save button.")));
                downloads.addPreference(toggle(context, Settings.COPY_PROFILE_TEXT, L10n.t("Copy username and bio"),
                        L10n.t("Adds Copy username and Copy bio to the menu on someone's profile. Each copies the "
                                + "text exactly as the account has it.")));
            }
            if (build.contains(PatchFamily.VOICE_MESSAGE)) {
                downloads.addPreference(toggle(context, Settings.DOWNLOAD_VOICE_MESSAGES, L10n.t("Download voice messages"),
                        L10n.t("Adds Save to the menu you get by holding a voice message in a chat. Saves the recording "
                                + "as an audio file. One sent to be played once never gets it.")));
            }
            if (build.contains(PatchFamily.REEL_DOWNLOAD) || build.contains(PatchFamily.VIDEO_DOWNLOAD)) {
                downloads.addPreference(toggle(context, Settings.OPEN_IN_PLAYER, L10n.t("Open in another player"),
                        L10n.t("Adds a row next to Download on a reel and a feed video that plays it in an app you "
                                + "pick, such as VLC. The player streams it from Instagram's servers.")));
            }
            downloads.addPreference(toggle(context, Settings.SEND_DOWNLOADS_TO_APP, L10n.t("Send downloads to another app"),
                    L10n.t("Download on a reel, a feed post or a story opens the share sheet with its link, for a "
                            + "downloader app such as Seal. Save all and the other save rows still save here.")));
            // Every save reads it, whichever download patch started it, so it's here above the
            // quality it keeps within.
            downloads.addPreference(toggle(context, Settings.DOWNLOAD_COMPATIBLE, L10n.t("Save videos other apps can open"),
                    L10n.t("For WhatsApp, video editors such as CapCut and InShot, or a gallery or player that plays saves "
                            + "without sound. May lower quality.")));
            downloads.addPreference(qualityRow(context));
            downloads.addPreference(folderRow(context));
            downloads.addPreference(fileNameRow(context));
            downloads.addPreference(toggle(context, Settings.SAVE_FOLDER_PER_ACCOUNT, L10n.t("Folder per account"),
                    L10n.t("Each save goes into a folder named for the account that posted it, inside the save folder. "
                            + "A save that doesn't know who posted stays in the save folder.")));
            downloads.addPreference(toggle(context, Settings.SAVE_NAME_BY_POST, L10n.t("Name saves by account and post time"),
                    L10n.t("Names each photo and video for the account that posted it and when, like "
                            + "username_20261005_143012, so an account's saves sort by date. A carousel page gets its "
                            + "number on the end. Takes the place of the video file name. A save that doesn't know who "
                            + "posted or when keeps its usual name.")));
        }

        if (build.contains(PatchFamily.STOP_HEARTBEAT)) {
            PreferenceCategory battery = category(screen, L10n.t("Battery"));
            battery.addPreference(toggle(context, Settings.STOP_HEARTBEAT, L10n.t("Stop the background heartbeat"),
                    L10n.t("Instagram wakes your phone every minute or two, screen off included, only to note that it "
                            + "is still running. This stops that. Messages, notifications and everything you see are "
                            + "unaffected.")));
            battery.addPreference(toggle(context, Settings.STOP_UPLOAD_ALARM, L10n.t("Stop the analytics upload alarm"),
                    L10n.t("Instagram sets an alarm to wake your phone and send its usage events five minutes later. "
                            + "This stops that alarm. The events go the next time you use the app, or nowhere with "
                            + "Disable analytics.")));
        }

        if (build.contains(PatchFamily.BUILD_EXPIRED_POPUP)) {
            PreferenceCategory updates = category(screen, L10n.t("Updates"));
            updates.addPreference(toggle(context, Settings.REMOVE_BUILD_EXPIRED_POPUP,
                    L10n.t("Remove build expired popup"),
                    L10n.t("Instagram stops showing the screen that says this version is too old. A patched build "
                            + "doesn't update on its own, so this keeps it usable.")));
        }

        if (build.contains(PatchFamily.DEVELOPER_OPTIONS)) {
            PreferenceCategory developer = category(screen, L10n.t("Developer"));
            developer.addPreference(toggle(context, Settings.OPEN_DEVELOPER_OPTIONS,
                    L10n.t("Developer options on a long press of Home"),
                    L10n.t("Opens Instagram's own developer options, where you can look at and change its hidden "
                            + "settings. A wrong change can break parts of Instagram until you reset it.")));
            developer.addPreference(nativeScreenRow(context, "hushgram_open_overrides",
                    L10n.t("Open MetaConfig overrides"),
                    L10n.t("Opens Instagram's own editor for its hidden settings (MetaConfig). A wrong override, "
                            + "which is a changed setting, can break parts of Instagram."),
                    app.hushgram.extension.instagram.misc.DeveloperOptions::openOverrides,
                    L10n.t("MetaConfig is unavailable on this screen. Open HushGram settings from Home while signed in.")));
            // The names go through the patch's hook on MetaConfig's list, which a build goes without
            // when Instagram moved it.
            if (PatchFamily.flagNamesInBuild()) {
                importFlagNames = new Row(context);
                importFlagNames.setKey("hushgram_import_flag_names");
                importFlagNames.setPersistent(false);
                importFlagNames.setTitle(L10n.t("Import setting names"));
                importFlagNames.setSummary(L10n.t("Pick a list of MetaConfig names, such as an id_name_mapping.json file, "
                        + "and MetaConfig shows those names in place of numbers. Searching by number still works."));
                importFlagNames.setOnPreferenceClickListener(row -> { pickOverrides(IMPORT_FLAG_NAMES); return true; });
                developer.addPreference(importFlagNames);
                removeFlagNames = new Row(context);
                removeFlagNames.setKey("hushgram_remove_flag_names");
                removeFlagNames.setPersistent(false);
                removeFlagNames.setTitle(L10n.t("Remove setting names"));
                removeFlagNames.setSummary(L10n.t("Forget the imported names, so MetaConfig shows Instagram's own labels again."));
                removeFlagNames.setOnPreferenceClickListener(row -> { flagNames(null, REMOVE_FLAG_NAMES); return true; });
                developer.addPreference(removeFlagNames);
            }
            developer.addPreference(nativeScreenRow(context, "hushgram_open_whitehat",
                    L10n.t("Open Whitehat settings"),
                    L10n.t("Opens Instagram's own Whitehat settings. Its switch lets Instagram trust the certificates "
                            + "installed on this phone for 24 hours, so you can check the app's traffic. Restart "
                            + "Instagram after you turn it on."),
                    app.hushgram.extension.instagram.misc.DeveloperOptions::openWhitehat,
                    L10n.t("Whitehat settings are unavailable on this screen. Open HushGram settings from Home while signed in.")));
            // Export, Validate and Import go through the patch's override reader and writer, which a
            // build goes without when Instagram moved them. The rest of Developer stays.
            if (PatchFamily.overrideExchangeInBuild()) {
                exportOverrides = new Row(context);
                exportOverrides.setKey("hushgram_export_overrides");
                exportOverrides.setPersistent(false);
                exportOverrides.setTitle(L10n.t("Export overrides"));
                exportOverrides.setSummary(L10n.t("Saves your overrides (changes to Instagram's hidden settings) for "
                        + "this exact Instagram version."));
                exportOverrides.setOnPreferenceClickListener(row -> { pickOverrides(EXPORT_OVERRIDES); return true; });
                developer.addPreference(exportOverrides);
                validateOverrides = new Row(context);
                validateOverrides.setKey("hushgram_validate_overrides");
                validateOverrides.setPersistent(false);
                validateOverrides.setTitle(L10n.t("Validate an overrides file"));
                validateOverrides.setSummary(L10n.t("Checks a saved file against this Instagram version. Nothing is "
                        + "applied."));
                validateOverrides.setOnPreferenceClickListener(row -> { pickOverrides(VALIDATE_OVERRIDES); return true; });
                developer.addPreference(validateOverrides);
            }
            if (PatchFamily.overrideImportInBuild()) {
                developer.addPreference(toggle(context, Settings.ALLOW_OVERRIDE_IMPORT,
                        L10n.t("Allow importing overrides"),
                        L10n.t("Shows the Import and Restore rows. An import changes Instagram's hidden settings for "
                                + "this signed-in account.")));
                developerSection = developer;
                importOverrides = new Row(context);
                importOverrides.setKey("hushgram_import_overrides");
                importOverrides.setPersistent(false);
                importOverrides.setTitle(L10n.t("Import overrides"));
                importOverrides.setSummary(L10n.t("Applies a HushGram export, or Instagram's own overrides file. "
                        + "Your current overrides are saved first so Restore can bring them back."));
                importOverrides.setOnPreferenceClickListener(row -> { pickOverrides(IMPORT_OVERRIDES); return true; });
                restoreOverrides = new Row(context);
                restoreOverrides.setKey("hushgram_restore_overrides");
                restoreOverrides.setPersistent(false);
                restoreOverrides.setTitle(L10n.t("Restore previous overrides"));
                restoreOverrides.setSummary(L10n.t("Puts back the overrides saved before your last import for this "
                        + "account and Instagram version."));
                restoreOverrides.setOnPreferenceClickListener(row -> { exchangeOverrides(null, RESTORE_OVERRIDES); return true; });
                discardOverrides = new Row(context);
                discardOverrides.setKey("hushgram_discard_overrides");
                discardOverrides.setPersistent(false);
                discardOverrides.setTitle(L10n.t("Discard saved overrides"));
                discardOverrides.setSummary(L10n.t("Forgets the copy saved for Restore so imports can run again. "
                        + "Instagram's own settings don't change."));
                discardOverrides.setOnPreferenceClickListener(row -> { exchangeOverrides(null, DISCARD_OVERRIDES); return true; });
                resetOverrides = new Row(context);
                resetOverrides.setKey("hushgram_reset_overrides");
                resetOverrides.setPersistent(false);
                resetOverrides.setTitle(L10n.t("Reset all overrides"));
                resetOverrides.setSummary(L10n.t("Removes every override from this signed-in account, so Instagram "
                        + "goes back to its own settings. Your current overrides are saved first for Restore."));
                resetOverrides.setOnPreferenceClickListener(row -> { exchangeOverrides(null, RESET_OVERRIDES); return true; });
                if (Settings.ALLOW_OVERRIDE_IMPORT.get()) {
                    developer.addPreference(importOverrides);
                    developer.addPreference(restoreOverrides);
                    developer.addPreference(discardOverrides);
                    developer.addPreference(resetOverrides);
                }
            }
        }

        if (build.contains(PatchFamily.RESTORE_TRUST) || build.contains(PatchFamily.REMOVE_AD_ID)
                || build.contains(PatchFamily.PURE_BLACK) || build.contains(PatchFamily.VERSION_CODE)) {
            PreferenceCategory patched = category(screen, L10n.t("Set when you patched"));
            if (build.contains(PatchFamily.RESTORE_TRUST)) {
                patched.addPreference(mark(info(context, L10n.t("Re-signed build fix"),
                        L10n.t("Instagram's own checks of who signed the app keep passing on this patched build.")), SettingsIcons.BUILD));
            }
            if (build.contains(PatchFamily.REMOVE_AD_ID)) {
                patched.addPreference(mark(info(context, L10n.t("Advertising ID removed"),
                        L10n.t("Instagram can't read your phone's advertising ID or tell Android's ad services "
                                + "which ads you saw or tapped. The permissions for them are gone from this build.")),
                        SettingsIcons.BLOCK));
            }
            if (build.contains(PatchFamily.PURE_BLACK)) {
                patched.addPreference(mark(info(context, L10n.t("Pure black dark mode"),
                        L10n.t("Instagram's dark mode uses pure black instead of its near-black gray. Menus, sheets "
                                + "and buttons keep their own grays.")), SettingsIcons.MOON));
            }
            if (build.contains(PatchFamily.VERSION_CODE)) {
                patched.addPreference(mark(info(context, L10n.t("Version code raised"),
                        L10n.t("Google Play won't offer Meta's updates over this build. To go back to the normal "
                                + "Instagram, uninstall this one first, which deletes Instagram's data on this "
                                + "phone. Later HushGram builds need Change version code too, or they won't install "
                                + "over this one.")), SettingsIcons.UPDATES));
            }
            patched.addPreference(info(context, L10n.t("Changing these"),
                    L10n.t("They're chosen in Morphe Manager when you patch, and Pause doesn't turn them off. "
                            + "Patch again to change them.")));
        }

        PreferenceCategory backup = category(screen, L10n.t("Settings backup"));
        exportConfiguration = new Row(context);
        exportConfiguration.setKey("hushgram_export_configuration");
        exportConfiguration.setPersistent(false);
        exportConfiguration.setTitle(L10n.t("Export HushGram settings"));
        exportConfiguration.setSummary(L10n.t("Choose a file for your installed patches' settings. Accounts and history stay on this device."));
        exportConfiguration.setOnPreferenceClickListener(p -> { pickConfiguration(false); return true; });
        backup.addPreference(mark(exportConfiguration, SettingsIcons.EXPORT));
        importConfiguration = new Row(context);
        importConfiguration.setKey("hushgram_import_configuration");
        importConfiguration.setPersistent(false);
        importConfiguration.setTitle(L10n.t("Import HushGram settings"));
        importConfiguration.setSummary(L10n.t("Choose a settings file. All valid choices apply together, and ones "
                + "this version doesn't know are skipped. The Undo row shows how long you can undo."));
        importConfiguration.setOnPreferenceClickListener(p -> { pickConfiguration(true); return true; });
        backup.addPreference(mark(importConfiguration, SettingsIcons.EXPORT));
        undoConfiguration = new Row(context);
        undoConfiguration.setKey("hushgram_undo_configuration");
        undoConfiguration.setPersistent(false);
        undoConfiguration.setTitle(L10n.t("Undo settings import"));
        backup.addPreference(mark(undoConfiguration, SettingsIcons.DELETE));
        showConfiguration();

        // Named for its rows: the screen's own title already says HushGram.
        PreferenceCategory hushgram = category(screen, L10n.t("Pause and diagnostics"));
        recovery = hushgram;
        hushgram.addPreference(mark(toggle(context, BaseSettings.PAUSED, L10n.t("Pause HushGram"),
                L10n.t("From the next start, every switch but Debug logging acts as if it were off. "
                        + "Changes made when you patched stay in, and your choices stay saved.")), SettingsIcons.PATCHED));
        // Debug logging also fills the exported report and turns on error toasts (Logger).
        hushgram.addPreference(mark(toggle(context, BaseSettings.DEBUG, L10n.t("Debug logging"),
                L10n.t("Record patch activity and show errors for a bug report. Leave off during normal use.")), SettingsIcons.BUG));
        ExportDiagnosticReportPreference export = exportDiagnostics = new ExportRow(context);
        export.setTitle(L10n.t("Export diagnostic report"));
        export.setSummary(L10n.f("Copy a quick report or save the full one to %1$s. Links, IDs, cookies and sign-in "
                + "details are left out. Check it for other private text before you share it.",
                L10n.isolate(LogBufferManager.reportFolder(context))));
        hushgram.addPreference(mark(export, SettingsIcons.LICENSE));
        ClearLogBufferPreference clear = new ClearRow(context);
        clear.setTitle(L10n.t("Clear diagnostic data"));
        clear.setClearAndUndoSummaries(L10n.t("Empties the activity log that a diagnostic report would include."),
                L10n.t("Diagnostic data cleared. Tap again to put it back."));
        hushgram.addPreference(mark(clear, SettingsIcons.DELETE));
        String stays = PatchFamily.staysWhilePausedSummary(build);
        if (stays != null) hushgram.addPreference(info(context, L10n.t(STAYS_WHILE_PAUSED), stays));

        PreferenceCategory about = category(screen, L10n.t("About"));
        about.addPreference(mark(info(context, L10n.t("Version"), L10n.f("HushGram %1$s on Instagram %2$s",
                L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName()))
                + "\n" + L10n.isolate(Utils.getSourceBuildIdentity())), SettingsIcons.ABOUT));

        Preference source = new Row(context);
        source.setTitle(L10n.t("Source code and issues"));
        source.setSummary(SOURCE_ADDRESS);
        source.setPersistent(false);
        source.setOnPreferenceClickListener(p -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
            } catch (ActivityNotFoundException | SecurityException missing) {
                // No browser, or none switched on. Uncaught, Android's exception closed Instagram.
                Logger.printInfo(() -> "No app opened the source code link");
                Utils.showToastLong(L10n.f("No app on this phone can open the link. The address is %1$s.",
                        L10n.isolate(SOURCE_ADDRESS)));
            }
            return true;
        });
        about.addPreference(mark(source, SettingsIcons.OPENING));

        // Section 7b asks that its notice reach the person using the software, and a file in the
        // repository does not reach them.
        Preference licenses = new Row(context);
        licenses.setTitle(L10n.t("Licenses"));
        licenses.setSummary(L10n.t("GPL-3.0, with the notices of the projects this is built on"));
        licenses.setPersistent(false);
        licenses.setOnPreferenceClickListener(p -> {
            showNotice(context);
            return true;
        });
        about.addPreference(mark(licenses, SettingsIcons.LICENSE));

        // Outside every category, so no category page, count or saved setting changes. filterSettings
        // puts it last on the home page and in a search it matches.
        Row donate = support = new Row(context);
        donate.setKey(SUPPORT_ROW_KEY);
        donate.setTitle(L10n.t("Support HushGram"));
        donate.setSummary(L10n.t("Buy me a coffee on Ko-fi"));
        donate.setPersistent(false);
        donate.setOrder(Integer.MAX_VALUE - 1);
        donate.setOnPreferenceClickListener(p -> {
            openSupport(p.getContext());
            return true;
        });
        mark(donate, SettingsIcons.OPENING);
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference section = screen.getPreference(i);
            if (!(section instanceof PreferenceCategory)) continue;
            PreferenceCategory group = (PreferenceCategory) section;
            List<Preference> rows = new ArrayList<>();
            for (int j = 0; j < group.getPreferenceCount(); j++) {
                Preference row = group.getPreference(j);
                rows.add(row);
                String key = row.getKey();
                StringBuilder aliases = new StringBuilder(row == ghostMode ? "ghost mode " : "");
                aliases.append(row == clearPositions
                        ? L10n.t("Clear remembered positions")
                        : row.getTitle() == null ? "" : row.getTitle().toString());
                for (PatchFamily family : build) {
                    boolean belongs = family.switches.stream().anyMatch(setting -> setting.key.equals(key));
                    belongs |= family == PatchFamily.STORY_RING && Settings.STORY_RING_SCALE.key.equals(key);
                    belongs |= family == PatchFamily.REEL_TAP_AND_VOLUME && Settings.REEL_TAP_CHOICE.key.equals(key);
                    belongs |= family == PatchFamily.PLAYBACK_QUALITY && Settings.PLAYBACK_QUALITY.key.equals(key);
                    belongs |= family == PatchFamily.PLAYBACK_QUALITY && Settings.QUALITY_TAB_TARGET.key.equals(key);
                    belongs |= family == PatchFamily.TAP_TO_PLAY && Settings.TAP_TO_PLAY_SCOPE.key.equals(key);
                    belongs |= family == PatchFamily.STORY_TIME && Settings.STORY_TIME_MODE.key.equals(key);
                    belongs |= family == PatchFamily.GLASS_TAB_BAR && Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key.equals(key);
                    belongs |= family == PatchFamily.REELS_TAB
                            && (Settings.START_TAB.key.equals(key) || Settings.TAB_ORDER.key.equals(key));
                    belongs |= family == PatchFamily.LIKE_ANIMATION && Settings.LIKE_ANIMATION.key.equals(key);
                    belongs |= family == PatchFamily.MESSAGES_LOCK
                            && (Settings.LOCK_AGAIN.key.equals(key) || LOCKED_CHATS_ROW.equals(key)
                            || HIDDEN_CHATS_ROW.equals(key));
                    belongs |= family == PatchFamily.RESUME_LONG_VIDEOS && row == clearPositions;
                    belongs |= (family == PatchFamily.REEL_DOWNLOAD || family == PatchFamily.STORY_DOWNLOAD
                            || family == PatchFamily.VIDEO_DOWNLOAD || family == PatchFamily.PROFILE_PICTURE
                            || family == PatchFamily.VOICE_MESSAGE)
                            && (Settings.DOWNLOAD_QUALITY.key.equals(key)
                            || Settings.SAVE_FOLDER.key.equals(key) || Settings.FILENAME_TEMPLATE.key.equals(key)
                            || Settings.DOWNLOAD_COMPATIBLE.key.equals(key));
                    belongs |= family == PatchFamily.RESTORE_TRUST && L10n.t("Re-signed build fix").equals(row.getTitle());
                    belongs |= family == PatchFamily.REMOVE_AD_ID && L10n.t("Advertising ID removed").equals(row.getTitle());
                    belongs |= family == PatchFamily.PURE_BLACK && L10n.t("Pure black dark mode").equals(row.getTitle());
                    belongs |= family == PatchFamily.VERSION_CODE && L10n.t("Version code raised").equals(row.getTitle());
                    if (!belongs) continue;
                    aliases.append(' ').append(family.patchName);
                    if (family == PatchFamily.DISABLE_ANALYTICS) aliases.append(" contacts contact location setup analytics ")
                            .append(L10n.t("Contacts, location setup, analytics"));
                    if (family == PatchFamily.FOLLOWING_FEED) aliases.append(" following home feed");
                    if (family == PatchFamily.REEL_DECLUTTER || family == PatchFamily.REEL_DOWNLOAD
                            || family == PatchFamily.REEL_WATCH_HISTORY || family == PatchFamily.REELS_TAB
                            || family == PatchFamily.KEEP_REEL_SPEED || family == PatchFamily.TAP_TO_PLAY
                            || family == PatchFamily.PLAYBACK_QUALITY || family == PatchFamily.RESUME_LONG_VIDEOS
                            || family == PatchFamily.REEL_TAP_AND_VOLUME
                            || Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.key.equals(key)) aliases.append(" reels");
                }
                searchAliases.put(row, aliases.toString());
            }
            searchableRows.put(group, rows);
            // Pause and diagnostics stays open in every mode, as it does while searching.
            if (group != recovery) categoryRows.put(group, categoryRow(context, group, rows));
        }
        filterSettings();
    }

    /** The row that opens [group]'s page: its name, its first few settings, and where it stood. */
    private Row categoryRow(Context context, PreferenceCategory group, List<Preference> rows) {
        Row row = new Row(context);
        row.setKey(CATEGORY_ROW_KEY + categoryRows.size());
        row.setPersistent(false);
        row.setTitle(group.getTitle());
        List<String> titles = new ArrayList<>();
        for (Preference each : rows) {
            if (titles.size() == 3) break;
            CharSequence title = each.getTitle();
            if (title != null && title.length() > 0) titles.add(title.toString());
        }
        row.setSummary(String.join(", ", titles));
        // The category itself is off the screen while its row is on, so the row takes its place.
        row.setOrder(group.getOrder());
        row.setOnPreferenceClickListener(tapped -> {
            openCategory(group);
            return true;
        });
        return row;
    }

    /** Whether the page lists its categories. A choice about the page, so Pause doesn't turn it off. */
    private static boolean categoryPages() {
        return Settings.CATEGORY_PAGES.savedValue();
    }

    /** Opens [group]'s page, keeping where the list of categories was for Back. */
    private void openCategory(PreferenceCategory group) {
        ListView list = listView();
        if (list != null) {
            categoryListPosition = list.getFirstVisiblePosition();
            View first = list.getChildAt(0);
            categoryListTop = first == null ? 0 : first.getTop() - list.getPaddingTop();
        }
        openCategory = group;
        filterSettings();
        showTitle(group.getTitle());
        if (list != null) {
            // After the adapter has caught up with the screen's new rows.
            list.post(() -> list.setSelection(0));
            list.announceForAccessibility(group.getTitle());
        }
    }

    /**
     * Back on a category's page: the list of categories again, scrolled where it was. Answers
     * whether a page was open, so Back closes settings only from the list.
     */
    boolean closeCategory() {
        if (openCategory == null) return false;
        openCategory = null;
        filterSettings();
        showTitle(null);
        ListView list = listView();
        if (list != null) {
            int position = categoryListPosition, top = categoryListTop;
            list.post(() -> list.setSelectionFromTop(position, top));
        }
        return true;
    }

    @Nullable private ListView listView() {
        View view = getView();
        return view == null ? null : view.findViewById(android.R.id.list);
    }

    private void showTitle(@Nullable CharSequence title) {
        if (getParentFragment() instanceof SettingsDialog) ((SettingsDialog) getParentFragment()).showTitle(title);
    }

    /** Hidden rows still participate in the shared preference synchronization contract. */
    @Override public Preference findPreference(CharSequence key) {
        Preference visible = super.findPreference(key);
        if (visible != null || key == null) return visible;
        for (List<Preference> rows : searchableRows.values()) {
            for (Preference row : rows) if (key.toString().equals(row.getKey())) return row;
        }
        return null;
    }

    private void restoreSearchRows() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || search == null || (!topHidden && screen.findPreference(search.getKey()) != search)) return;
        for (Map.Entry<PreferenceCategory, List<Preference>> section : searchableRows.entrySet()) {
            PreferenceCategory group = section.getKey();
            if (group.getParent() != screen) screen.addPreference(group);
            for (Preference row : section.getValue()) if (row.getParent() != group) group.addPreference(row);
        }
    }

    @Override protected void updateUIToSettingValues() {
        restoreSearchRows();
        try { super.updateUIToSettingValues(); } finally { showOverrideImport(); showGhostMode(); filterSettings(); }
    }

    @Override protected void updateUIAvailability() {
        restoreSearchRows();
        try { super.updateUIAvailability(); } finally { showOverrideImport(); showGhostMode(); filterSettings(); }
    }

    /** Ghost mode shows on only while every switch it turns is on, whichever way they got there. */
    private void showGhostMode() {
        SwitchPreference row = ghostMode;
        if (row != null) row.setChecked(GhostMode.on(ghostSwitches));
    }

    /**
     * Ghost mode's row. It keeps no value: a tap saves the new value to each switch it turns, through
     * that switch's own row when the page has one, so each row shows its new state, and a toast
     * says what happened.
     */
    private SwitchPreference ghostModeRow(Context context) {
        SwitchPreference row = new Toggle(context);
        row.setPersistent(false);
        row.setTitle(L10n.t("Ghost mode"));
        row.setSummary(L10n.t("Turns the switches that keep what you do to yourself on or off in one go, like View "
                + "stories anonymously and Hide that you're typing. Each one keeps its own switch."));
        row.setOnPreferenceChangeListener((preference, value) -> {
            boolean on = Boolean.TRUE.equals(value);
            for (BooleanSetting setting : ghostSwitches) {
                Preference own = findPreference(setting.key);
                if (own instanceof TwoStatePreference && own.isPersistent()) ((TwoStatePreference) own).setChecked(on);
                else setting.save(on);
            }
            showGhostMode();
            Utils.showToastShort(GhostMode.message(ghostSwitches, on,
                    L10n.t("Couldn't change every Ghost mode switch. Check them below.")));
            return false;
        });
        row.setChecked(GhostMode.on(ghostSwitches));
        return row;
    }

    /** Import, Restore and Discard exist on the page, and in search, only while their switch is on. */
    private void showOverrideImport() {
        PreferenceCategory group = developerSection;
        if (group == null || importOverrides == null || restoreOverrides == null || discardOverrides == null
                || resetOverrides == null) return;
        boolean allowed = Settings.ALLOW_OVERRIDE_IMPORT.get();
        List<Preference> rows = searchableRows.get(group);
        for (Row row : new Row[]{importOverrides, restoreOverrides, discardOverrides, resetOverrides}) {
            if (allowed) {
                if (rows != null && !rows.contains(row)) rows.add(row);
                if (row.getParent() != group) group.addPreference(row);
            } else {
                if (rows != null) rows.remove(row);
                if (row.getParent() == group) group.removePreference(row);
            }
        }
    }

    private static String searchText(CharSequence text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKD)
                .toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT).replaceAll("\\p{M}+|\\p{Cf}+", "");
    }

    void searchSettings(String query) {
        searchQuery = query == null ? "" : query;
        filterSettings();
    }

    private void filterSettings() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || search == null || (!topHidden && screen.findPreference(search.getKey()) != search)) return;
        String normalized = searchText(searchQuery).trim();
        String[] terms = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        boolean pages = categoryPages();
        if (!pages && openCategory != null) {
            openCategory = null;
            showTitle(null);
        }
        // A search looks through every category, whichever page is open, and clearing it goes back.
        boolean listing = pages && terms.length == 0;
        // A category's page shows only its own settings: no status card, view button or search.
        topHidden = listing && openCategory != null;
        for (Preference top : new Preference[]{statusCard, viewToggle, search}) {
            if (top == null) continue;
            if (topHidden) {
                if (top.getParent() == screen) screen.removePreference(top);
            } else if (top.getParent() != screen) screen.addPreference(top);
        }
        if (viewToggle != null) {
            CharSequence label = pages ? L10n.t("Show all settings as a list") : L10n.t("Show settings by category");
            if (!label.equals(viewToggle.getTitle())) viewToggle.setTitle(label);
        }
        int matches = 0;
        for (Map.Entry<PreferenceCategory, List<Preference>> section : searchableRows.entrySet()) {
            PreferenceCategory group = section.getKey();
            for (Preference row : section.getValue()) {
                String text = searchText(group.getTitle()) + " " + searchText(row.getTitle()) + " "
                        + searchText(row.getSummary()) + " " + searchText(searchAliases.get(row));
                boolean match = true;
                for (String term : terms) if (!text.contains(term)) { match = false; break; }
                if (match) matches++;
                if (match || group == recovery) {
                    if (row.getParent() != group) group.addPreference(row);
                } else if (row.getParent() == group) group.removePreference(row);
            }
            // Running saves are live rows outside the snapshot, so Cancel survives every query.
            boolean filled = group.getPreferenceCount() > 0;
            boolean asRow = listing && openCategory == null && group != recovery;
            // Pause and diagnostics lives in the list of categories only, not on each category's page.
            boolean elsewhere = listing && openCategory != null && group != openCategory;
            if (filled && !asRow && !elsewhere) {
                if (group.getParent() != screen) screen.addPreference(group);
            } else if (group.getParent() == screen) screen.removePreference(group);
            Row row = categoryRows.get(group);
            if (row != null) {
                if (filled && asRow) {
                    if (row.getParent() != screen) screen.addPreference(row);
                } else if (row.getParent() == screen) screen.removePreference(row);
            }
        }
        Row donate = support;
        if (donate != null) {
            String text = searchText(donate.getTitle()) + " " + searchText(donate.getSummary()) + " ko-fi kofi donate";
            boolean match = true;
            for (String term : terms) if (!text.contains(term)) { match = false; break; }
            // The home page (and a search it matches) shows it, a category's page doesn't.
            boolean shown = match && (terms.length > 0 || !pages || openCategory == null);
            if (terms.length > 0 && match) matches++;
            if (shown) {
                if (donate.getParent() != screen) screen.addPreference(donate);
            } else if (donate.getParent() == screen) screen.removePreference(donate);
        }
        // removePreference notifies even when absent. No-op changes mustn't rebind live Cancel.
        if (terms.length > 0 && matches == 0) {
            if (noSearchResults.getParent() != screen) screen.addPreference(noSearchResults);
        } else if (noSearchResults.getParent() == screen) screen.removePreference(noSearchResults);
    }

    /** The Ko-fi page, for a browser, in a task of its own so it never opens inside Instagram's. */
    static Intent supportIntent() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Opens the Ko-fi page, or says no app here can, as Source code and issues does. */
    static void openSupport(Context context) {
        try {
            context.startActivity(supportIntent());
        } catch (ActivityNotFoundException | SecurityException missing) {
            // No browser, or none switched on. Uncaught, Android's exception closed Instagram.
            Logger.printInfo(() -> "No app opened the support link");
            Utils.showToastLong(L10n.f("No app on this phone can open the link. The address is %1$s.",
                    L10n.isolate(SUPPORT_ADDRESS)));
        }
    }

    private void showConfiguration() {
        boolean busy = changingConfiguration || changingOverrides || documentRequest != 0 || ExportStatus.CONFIGURATION.active();
        if (exportConfiguration != null) {
            exportConfiguration.setEnabled(!busy);
            ExportStatus.State state = ExportStatus.CONFIGURATION.state();
            if (state != null) exportConfiguration.setSummary(state.message);
        }
        if (exportDiagnostics != null) {
            ExportStatus.State state = ExportStatus.DIAGNOSTICS.state();
            exportDiagnostics.setEnabled(state == null || !state.active);
            if (state != null) exportDiagnostics.setSummary(state.message);
        }
        if (importConfiguration != null) importConfiguration.setEnabled(!busy);
        if (importConfiguration != null && importFeedback != null) importConfiguration.setSummary(importFeedback);
        if (undoConfiguration != null) {
            Row row = undoConfiguration;
            long token = ConfigurationBackup.undoToken();
            long deadline = ConfigurationBackup.undoDeadline(token);
            row.setEnabled(!busy && deadline != 0);
            row.setSummary(deadline != 0
                    ? L10n.f("Undo is available until %1$s. Restarting Instagram discards Undo.",
                    DateFormat.getTimeInstance(DateFormat.MEDIUM, L10n.locale(row.getContext())).format(
                            new Date(System.currentTimeMillis() + Math.max(0, deadline - SystemClock.elapsedRealtime()))))
                    : L10n.t("No settings import to undo."));
            row.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override public boolean onPreferenceClick(Preference preference) {
                    if (isResumed() && undoConfiguration == row && row.isEnabled()
                            && row.getOnPreferenceClickListener() == this) changeConfiguration(null, token);
                    return true;
                }
            });
        }
        scheduleUndoExpiry();
        if (exportOverrides != null) {
            exportOverrides.setEnabled(!busy);
            if (overrideExportFeedback != null) exportOverrides.setSummary(overrideExportFeedback);
        }
        if (validateOverrides != null) {
            validateOverrides.setEnabled(!busy);
            if (overrideValidationFeedback != null) validateOverrides.setSummary(overrideValidationFeedback);
        }
        if (importOverrides != null) {
            importOverrides.setEnabled(!busy);
            if (overrideImportFeedback != null) importOverrides.setSummary(overrideImportFeedback);
        }
        if (restoreOverrides != null) {
            restoreOverrides.setEnabled(!busy);
            if (overrideRestoreFeedback != null) restoreOverrides.setSummary(overrideRestoreFeedback);
        }
        if (discardOverrides != null) {
            discardOverrides.setEnabled(!busy);
            if (overrideDiscardFeedback != null) discardOverrides.setSummary(overrideDiscardFeedback);
        }
        if (resetOverrides != null) {
            resetOverrides.setEnabled(!busy);
            if (overrideResetFeedback != null) resetOverrides.setSummary(overrideResetFeedback);
        }
        if (importFlagNames != null) {
            importFlagNames.setEnabled(!busy);
            if (flagNamesFeedback != null) importFlagNames.setSummary(flagNamesFeedback);
        }
        if (removeFlagNames != null) removeFlagNames.setEnabled(!busy);
        filterSettings();
    }

    private void pickConfiguration(boolean importing) {
        if (documentRequest != 0 || changingConfiguration || changingOverrides || ExportStatus.CONFIGURATION.active()) return;
        if (documentSequence == Integer.MAX_VALUE) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
            return;
        }
        if (!importing) {
            configurationExportToken = ExportStatus.CONFIGURATION.begin(L10n.t("Choose a file for the settings export."), true);
            if (configurationExportToken == null) return;
        }
        documentRequest = importing ? IMPORT_CONFIGURATION : EXPORT_CONFIGURATION;
        documentCode = ++documentSequence;
        showConfiguration();
        Intent picker = new Intent(importing ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        if (!importing) picker.putExtra(Intent.EXTRA_TITLE, "HushGram-settings.json");
        try {
            startActivityForResult(picker, documentCode);
        } catch (ActivityNotFoundException | SecurityException failure) {
            documentRequest = 0;
            documentCode = 0;
            if (!importing) ExportStatus.CONFIGURATION.finish(configurationExportToken,
                    L10n.t("This phone has no file picker. Your settings haven't changed."));
            showConfiguration();
            Utils.showToastLong(L10n.t("This phone has no file picker. Your settings haven't changed."));
        }
    }

    private void pickOverrides(int request) {
        if (documentRequest != 0 || changingConfiguration || changingOverrides || ExportStatus.CONFIGURATION.active()) return;
        if (documentSequence == Integer.MAX_VALUE) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
            return;
        }
        documentRequest = request;
        documentCode = ++documentSequence;
        showConfiguration();
        boolean exporting = request == EXPORT_OVERRIDES;
        boolean names = request == IMPORT_FLAG_NAMES;
        // A name list can be HushGram's plain text as well as JSON, and some providers don't type either.
        Intent picker = new Intent(exporting ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(names ? "*/*" : "application/json");
        if (exporting) picker.putExtra(Intent.EXTRA_TITLE, "HushGram-overrides.json");
        try { startActivityForResult(picker, documentCode); }
        catch (ActivityNotFoundException | SecurityException failure) {
            documentRequest = 0;
            documentCode = 0;
            overrideFeedback(request, names ? L10n.t("This phone has no file picker. Your settings haven't changed.")
                    : L10n.t("This phone has no file picker. Overrides haven't changed."));
            showConfiguration();
        }
    }

    @Override
    public void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != documentCode) return;
        int request = documentRequest;
        boolean overrides = request == EXPORT_OVERRIDES || request == VALIDATE_OVERRIDES || request == IMPORT_OVERRIDES;
        boolean names = request == IMPORT_FLAG_NAMES;
        if (request != EXPORT_CONFIGURATION && request != IMPORT_CONFIGURATION && !overrides && !names) return;
        documentRequest = 0;
        documentCode = 0;
        if (request == EXPORT_CONFIGURATION) {
            ExportStatus.State current = ExportStatus.CONFIGURATION.state();
            if (current == null || !current.active || !current.choosing || !current.token.equals(configurationExportToken)) {
                showConfiguration();
                return;
            }
        }
        if (result != Activity.RESULT_OK) {
            if (request == EXPORT_CONFIGURATION) ExportStatus.CONFIGURATION.finish(
                    configurationExportToken, L10n.t("Settings export cancelled."));
            showConfiguration();
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) {
            if (names) {
                overrideFeedback(request, L10n.t("Couldn't read setting names from that file. Nothing changed."));
            } else if (overrides) {
                overrideFeedback(request, L10n.t("Couldn't use that file. Instagram's overrides haven't changed."));
            } else {
                if (request == EXPORT_CONFIGURATION) ExportStatus.CONFIGURATION.finish(configurationExportToken,
                        L10n.t("Couldn't use that settings file. Your settings haven't changed."));
                Utils.showToastLong(L10n.t("Couldn't use that settings file. Your settings haven't changed."));
            }
            showConfiguration();
            return;
        }
        if (overrides) {
            exchangeOverrides(uri, request);
            return;
        }
        if (names) {
            flagNames(uri, request);
            return;
        }
        if (request == IMPORT_CONFIGURATION) changeConfiguration(uri, 0);
        else exportConfiguration(uri);
    }

    /** Restore and Discard read HushGram's saved copy instead of a document. */
    private void exchangeOverrides(@Nullable Uri uri, int request) {
        boolean saved = request == RESTORE_OVERRIDES || request == DISCARD_OVERRIDES || request == RESET_OVERRIDES;
        if (saved && (documentRequest != 0 || changingConfiguration || changingOverrides || ExportStatus.CONFIGURATION.active())) return;
        boolean importing = saved || request == IMPORT_OVERRIDES;
        boolean validating = request == VALIDATE_OVERRIDES;
        Context context = getContext();
        Activity activity = getActivity();
        if (context == null || activity == null) { showConfiguration(); return; }
        changingOverrides = true;
        showConfiguration();
        if (!Utils.runOnBackgroundThread(() -> {
            try {
                // The rows hide while the switch is off or HushGram is paused, but either can change
                // while a picker is open. Then neither the document nor the store is read.
                if (importing && !Settings.ALLOW_OVERRIDE_IMPORT.get()) {
                    Logger.printInfo(() -> "Override import refused while its switch is off");
                    overrideFeedback(request, L10n.t("Allow importing overrides is off or HushGram is paused. Nothing changed."));
                } else if (request == RESTORE_OVERRIDES) {
                    overrideFeedback(request, overrideOutcome(OverrideImport.restore(activity), true));
                } else if (request == RESET_OVERRIDES) {
                    overrideFeedback(request, resetOutcome(OverrideImport.reset(activity)));
                } else if (request == DISCARD_OVERRIDES) {
                    overrideFeedback(request, OverrideImport.discard(activity)
                            ? L10n.t("Discarded the saved copy. Imports can run again, and Instagram's overrides haven't changed.")
                            : L10n.t("There's no saved copy to discard. Nothing changed."));
                } else if (request == IMPORT_OVERRIDES) {
                    byte[] bytes;
                    try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                        bytes = OverrideExchange.read(input);
                    }
                    // The document is closed before the first native call, so a provider failure
                    // can't follow a write.
                    overrideFeedback(request, overrideOutcome(OverrideImport.apply(activity, bytes), false));
                } else if (validating) {
                    byte[] bytes;
                    try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                        bytes = OverrideExchange.read(input);
                    }
                    OverrideExchange.Checked checked = OverrideExchange.validate(bytes, OverrideExchange.capture(activity));
                    String validated = L10n.f("Checked %1$d overrides only. Nothing was applied.", checked.fits);
                    overrideFeedback(request, checked.leftOut == 0 ? validated : validated + " " + L10n.f("%1$d more "
                            + "come from a different Instagram version and don't fit this one, so the import leaves "
                            + "them out.", checked.leftOut));
                } else {
                    OverrideExchange.Snapshot snapshot = OverrideExchange.capture(activity);
                    byte[] bytes = OverrideExchange.export(snapshot);
                    try (java.io.OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new java.io.IOException();
                        output.write(bytes);
                    }
                    String exported = L10n.t("Overrides exported for this Instagram version.");
                    overrideFeedback(request, snapshot.leftOut() == 0 ? exported : exported + " " + L10n.f("%1$d "
                            + "overrides from a different Instagram version don't fit this one and were left out.", snapshot.leftOut()));
                }
            } catch (OverrideImport.RestoreFirst failure) {
                Logger.printInfo(() -> "Override import refused until Restore runs");
                overrideFeedback(request, L10n.t("An earlier import still needs Restore previous overrides, "
                        + "or Discard saved overrides if Restore can't run. Nothing changed."));
            } catch (OverrideImport.NotAllowed failure) {
                Logger.printInfo(() -> "Override import refused while its switch is off");
                overrideFeedback(request, L10n.t("Allow importing overrides is off or HushGram is paused. Nothing changed."));
            } catch (OverrideImport.StoreChanging failure) {
                Logger.printInfo(() -> "Override import refused while the native store was still changing");
                overrideFeedback(request, L10n.t("Instagram is still saving an override change. Wait a moment and try again. Nothing changed."));
            } catch (OverrideImport.NothingSaved failure) {
                overrideFeedback(request, L10n.t("Couldn't restore overrides. There's no saved copy for this account "
                        + "and Instagram version. Nothing changed."));
            } catch (OverrideExchange.NothingFits failure) {
                Logger.printInfo(() -> "Override file holds nothing this build has");
                overrideFeedback(request, L10n.t("None of this file's overrides fit this Instagram version. Nothing "
                        + "changed."));
            } catch (OverrideImport.SavedCopyDoesntFit failure) {
                Logger.printInfo(() -> "Override restore refused a saved copy from another build or schema");
                overrideFeedback(request, L10n.t("Couldn't restore overrides. The saved copy doesn't fit this "
                        + "account and Instagram version. Use Discard saved overrides if you don't need it. Nothing "
                        + "changed."));
            } catch (Exception failure) {
                Logger.printInfo(() -> "Override document operation failed before native mutation");
                overrideFeedback(request, request == RESTORE_OVERRIDES
                        ? L10n.t("Couldn't restore overrides. Open settings from Home while signed in. Nothing changed.")
                        : request == RESET_OVERRIDES
                        ? L10n.t("Couldn't reset overrides. Open settings from Home while signed in. Nothing changed.")
                        : request == DISCARD_OVERRIDES
                        ? L10n.t("Couldn't finish discarding the saved copies. Try Discard saved overrides again. "
                                + "Instagram's overrides haven't changed.")
                        : request == IMPORT_OVERRIDES
                        ? L10n.t("Couldn't import overrides. Check the file and open settings from Home while signed in. Nothing changed.")
                        : validating
                        ? L10n.t("Couldn't validate overrides. Check the file and open settings from Home while signed in. Nothing changed.")
                        : L10n.t("Couldn't export overrides. The selected file may be incomplete. Instagram's "
                                + "overrides haven't changed."));
            } finally { configurationFinished(); }
        })) {
            changingOverrides = false;
            overrideFeedback(request, L10n.t("Couldn't start that. Try again. Nothing changed."));
            showConfiguration();
        }
    }

    /**
     * Import flag names reads the picked list into HushGram's own copy, and Remove forgets that copy.
     * Neither touches Instagram's overrides, its schema or anything it sends. A file that isn't a
     * name list, or names nothing, changes nothing and says so in one line.
     */
    private void flagNames(@Nullable Uri uri, int request) {
        boolean removing = request == REMOVE_FLAG_NAMES;
        if (removing && (documentRequest != 0 || changingConfiguration || changingOverrides || ExportStatus.CONFIGURATION.active())) return;
        Context context = getContext();
        if (context == null || (!removing && uri == null)) { showConfiguration(); return; }
        changingOverrides = true;
        showConfiguration();
        if (!Utils.runOnBackgroundThread(() -> {
            try {
                if (removing) {
                    overrideFeedback(request, FlagNames.clear(context)
                            ? L10n.t("Setting names removed. Open MetaConfig again to see Instagram's own labels.")
                            : L10n.t("There are no imported setting names to remove."));
                } else {
                    byte[] bytes;
                    try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                        bytes = FlagNames.read(input);
                    }
                    FlagNames.Names names = FlagNames.importNames(context, bytes);
                    String imported = L10n.f("Setting names imported: %1$d. Open MetaConfig again to see them.", names.size());
                    overrideFeedback(request, names.leftOut == 0 ? imported : imported + " " + L10n.f(
                            "Entries left out because they repeat or don't fit: %1$d.", names.leftOut));
                }
            } catch (FlagNames.Empty failure) {
                overrideFeedback(request, L10n.t("That file has no setting names in it. Nothing changed."));
            } catch (FlagNames.Unreadable failure) {
                overrideFeedback(request, L10n.t("Couldn't read setting names from that file. Nothing changed."));
            } catch (Exception failure) {
                Logger.printInfo(() -> "Flag names operation failed", failure);
                overrideFeedback(request, removing ? L10n.t("Couldn't remove the setting names. Try again.")
                        : L10n.t("Couldn't save the setting names. Nothing changed."));
            } finally { configurationFinished(); }
        })) {
            changingOverrides = false;
            overrideFeedback(request, L10n.t("Couldn't start that. Try again."));
            showConfiguration();
        }
    }

    private static String resetOutcome(OverrideImport.Result result) {
        if (result.outcome == OverrideImport.Outcome.UNCHANGED) {
            return L10n.t("There are no overrides to reset. Nothing changed.");
        }
        if (result.outcome != OverrideImport.Outcome.APPLIED) return overrideOutcome(result, false);
        String message = L10n.f("Removed %1$d overrides. Restart Instagram to go back to its own settings.", result.changes);
        return result.blocked ? message + " " + L10n.t("Recovery cleanup didn't finish. Use Restore previous overrides or "
                + "Discard saved overrides.") : message;
    }

    private static String overrideOutcome(OverrideImport.Result result, boolean restoring) {
        String message;
        switch (result.outcome) {
            case UNCHANGED: message = restoring
                    ? L10n.t("The current overrides already match the saved copy. Nothing changed.")
                    : L10n.t("This file matches the current overrides. Nothing changed."); break;
            case APPLIED: message = restoring
                    ? L10n.t("Previous overrides restored. Restart Instagram to apply them.")
                    : L10n.f("Imported %1$d override changes. Restart Instagram to apply them.", result.changes); break;
            case ROLLED_BACK: message = L10n.t("Instagram didn't keep the change, so the overrides were put back as they were."); break;
            case PARTIAL: return result.blocked
                    ? L10n.f("Restore put back what it could, except %1$d overrides that were set to nothing, which "
                    + "can't be put back this way. Imports stay blocked until you use Discard saved overrides. "
                    + "Restart Instagram to apply the rest.", result.skipped)
                    : L10n.f("Restore put back what it could, except %1$d overrides that were set to nothing, which "
                    + "can't be put back this way. Restart Instagram to apply the rest.", result.skipped);
            default: return L10n.t("Instagram didn't keep the change and the overrides couldn't be confirmed. "
                    + "Use Restore previous overrides, then restart Instagram.");
        }
        if (result.leftOut > 0) message += " " + L10n.f("%1$d overrides from a different Instagram version don't fit "
                + "this one and were left out.", result.leftOut);
        return result.blocked ? message + " " + L10n.t("Recovery cleanup didn't finish. Use Restore previous overrides or Discard saved overrides.") : message;
    }

    private void overrideFeedback(int request, String message) {
        if (request == VALIDATE_OVERRIDES) overrideValidationFeedback = message;
        else if (request == IMPORT_OVERRIDES) overrideImportFeedback = message;
        else if (request == RESTORE_OVERRIDES) overrideRestoreFeedback = message;
        else if (request == DISCARD_OVERRIDES) overrideDiscardFeedback = message;
        else if (request == RESET_OVERRIDES) overrideResetFeedback = message;
        else if (request == IMPORT_FLAG_NAMES || request == REMOVE_FLAG_NAMES) flagNamesFeedback = message;
        else overrideExportFeedback = message;
        Utils.showToastLong(message);
        Utils.runOnMainThread(this::showConfiguration);
    }

    private void exportConfiguration(Uri uri) {
        String token = configurationExportToken;
        if (!ExportStatus.CONFIGURATION.start(token, L10n.t("Exporting HushGram settings..."))) return;
        Context context = getContext();
        if (context == null) {
            ExportStatus.CONFIGURATION.finish(token, L10n.t("Couldn't export HushGram settings. Try another file."));
            return;
        }
        Context application = context.getApplicationContext();
        final Context app = application == null ? context : application;
        showConfiguration();
        if (!Utils.runOnBackgroundThread(() -> {
            try {
                byte[] bytes = ConfigurationBackup.export();
                try (java.io.OutputStream output = app.getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException();
                    output.write(bytes);
                }
                ExportStatus.CONFIGURATION.finish(token, L10n.t("HushGram settings exported."));
                Utils.showToastLong(L10n.t("HushGram settings exported."));
            } catch (Exception failure) {
                Logger.printInfo(() -> "Configuration export failed");
                ExportStatus.CONFIGURATION.finish(token, L10n.t("Couldn't export HushGram settings. Try another file."));
                Utils.showToastLong(L10n.t("Couldn't export HushGram settings. Try another file."));
            }
        })) {
            ExportStatus.CONFIGURATION.finish(token, L10n.t("Couldn't start that. Try again."));
            configurationQueueFull();
        }
    }

    /** Undo always remains Undo, even if its expiration callback hasn't reached the screen yet. */
    private void changeConfiguration(@Nullable Uri uri, long token) {
        if (changingConfiguration || ExportStatus.CONFIGURATION.active()) return;
        boolean undo = uri == null;
        Context context = getContext();
        if (context == null) return;
        changingConfiguration = true;
        showConfiguration();
        if (!Utils.runOnBackgroundThread(() -> {
            byte[] document = null;
            try {
                ConfigurationBackup.Result result;
                if (undo) result = ConfigurationBackup.undo(token);
                else {
                    byte[] bytes;
                    try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                        bytes = ConfigurationBackup.read(input);
                    }
                    document = bytes;
                    // A provider can fail while closing. Finish all document I/O before values
                    // change, so the failure verdict can truthfully say nothing was applied.
                    result = ConfigurationBackup.restore(bytes);
                }
                if (result == null) showImportFeedback(L10n.t("Undo has expired."));
                else {
                    String message = undo ? result.skipped == 0 ? L10n.t("Settings restored.")
                            : L10n.f("Restored %1$d settings. Kept %2$d newer choices.", result.applied, result.skipped)
                            : L10n.f("Imported %1$d settings. Skipped %2$d that this version doesn't know.", result.applied, result.skipped);
                    if (result.restart) message += " " + L10n.t("Restart Instagram to apply these choices.");
                    showImportFeedback(message);
                }
            } catch (Setting.BatchFailed failure) {
                showImportFeedback(failure.restored
                        ? L10n.t("Couldn't save the settings. The previous values were restored.")
                        : undo ? L10n.t("Undo couldn't fully restore the settings. Check the shown values. Undo has "
                                + "been used up.")
                        : L10n.t("Couldn't save or fully restore the settings. Check the shown values and try Undo."));
            } catch (Exception failure) {
                Logger.printInfo(() -> "Configuration import failed before applying settings");
                // An overrides file handed to the settings import is the likeliest mix-up, so name it.
                showImportFeedback(OverrideExchange.isOverridesFile(document)
                        ? L10n.t("That's an overrides file, not a settings file. Turn on Allow importing overrides "
                                + "under Developer, then use Import overrides. Your settings haven't changed.")
                        : L10n.t("Couldn't use that settings file. Your settings haven't changed."));
            } finally { configurationFinished(); }
        })) configurationQueueFull();
    }

    /** Android limits toasts to two lines. Keep complete import/rollback/restart feedback readable. */
    private void showImportFeedback(String message) {
        importFeedback = message;
        Utils.showToastLong(message);
        Utils.runOnMainThread(() -> {
            showConfiguration();
        });
    }

    private void configurationQueueFull() {
        changingConfiguration = false;
        showConfiguration();
        Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
    }

    private void configurationFinished() {
        Utils.runOnMainThread(() -> {
            changingConfiguration = false;
            changingOverrides = false;
            if (isAdded() && getPreferenceScreen() != null) updateUIToSettingValues();
            showConfiguration();
        });
    }

    /** Clear remains available when playback is off or paused; it never reads media identities. */
    private void showClearPositions() {
        Row row = clearPositions;
        if (row == null) return;
        long token = ResumePlayback.undoHistoryToken();
        long deadline = ResumePlayback.undoHistoryDeadline(token);
        boolean undo = deadline != 0;
        row.setEnabled(!changingPositions);
        row.setTitle(changingPositions ? L10n.t("Updating remembered positions...")
                : undo ? L10n.t("Undo cleared positions") : L10n.t("Clear remembered positions"));
        row.setSummary(undo
                ? L10n.f("Undo is available until %1$s.",
                DateFormat.getTimeInstance(DateFormat.MEDIUM, L10n.locale(row.getContext())).format(
                        new Date(System.currentTimeMillis() + Math.max(0, deadline - SystemClock.elapsedRealtime()))))
                : L10n.t("HushGram remembers where you stopped in up to 200 videos for 30 days. Tap to clear them "
                        + "from this device."));
        row.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference preference) {
                if (isResumed() && clearPositions == row && row.isEnabled()
                        && row.getOnPreferenceClickListener() == this) changePositions(token);
                return true;
            }
        });
        scheduleUndoExpiry();
        filterSettings();
    }

    /** Reopening only schedules the remainder of the original monotonic deadline. */
    private void scheduleUndoExpiry() {
        undoRefresh.removeCallbacks(refreshUndo);
        if (!isResumed()) return;
        long configuration = ConfigurationBackup.undoDeadline(ConfigurationBackup.undoToken());
        long positions = ResumePlayback.undoHistoryDeadline(ResumePlayback.undoHistoryToken());
        long next = configuration == 0 ? positions : positions == 0 ? configuration : Math.min(configuration, positions);
        if (next != 0) undoRefresh.postDelayed(refreshUndo, Math.max(0, next - SystemClock.elapsedRealtime()));
    }

    /** Disk commits run on the existing worker, with immediate feedback and no confirmation. */
    private void changePositions(long token) {
        if (changingPositions) return;
        // Honor the action the row offered. An expired Undo must never become another clear
        // while its delayed refresh is still waiting on the main thread.
        boolean undo = token != 0;
        changingPositions = true;
        showClearPositions();
        if (!Utils.runOnBackgroundThread(() -> {
            try {
                if (undo) {
                    boolean restored = ResumePlayback.undoHistory(token);
                    Utils.showToastShort(restored ? L10n.t("Remembered playback positions restored.")
                            : L10n.t("Undo has expired."));
                } else {
                    ResumePlayback.clearHistory();
                    Utils.showToastShort(L10n.t("You cleared the remembered playback positions."));
                }
            } catch (Exception failure) {
                Logger.printException(() -> "Could not update resume history", failure);
                Utils.showToastLong(L10n.t("Could not update the remembered playback positions."));
            } finally {
                Utils.runOnMainThread(() -> {
                    changingPositions = false;
                    showClearPositions();
                });
            }
        })) {
            changingPositions = false;
            showClearPositions();
            Utils.showToastLong(L10n.t("Could not update the remembered playback positions."));
        }
    }

    /**
     * Lists each save running now at the top of Downloads, and takes a finished one away. The
     * notification was the only way to follow or stop a save, and with Instagram's notifications or
     * the saves channel off there wasn't one. A row that stays is changed in place.
     */
    void showSaves() {
        int stopped = SaveLeftovers.interruptedCount();
        if (stopped > 0 && recovery != null && interruptedSaves == null) {
            interruptedSaves = new Row(recovery.getContext());
            interruptedSaves.setKey("hushgram_interrupted_saves");
            interruptedSaves.setPersistent(false);
            interruptedSaves.setSelectable(false);
            interruptedSaves.setOrder(Integer.MIN_VALUE / 2);
            interruptedSaves.setTitle(L10n.quantity(stopped,
                    "A save was interrupted", "%1$d saves were interrupted"));
            interruptedSaves.setSummary(L10n.t("Reopen the media and save again."));
            recovery.addPreference(mark(interruptedSaves, SettingsIcons.DOWNLOADS));
        }
        PreferenceCategory group = downloads;
        if (group == null) return;
        String outcome = SaveControl.batchOutcome();
        if (outcome != null) {
            if (lastCarouselSave == null) {
                lastCarouselSave = new Row(group.getContext());
                lastCarouselSave.setKey("hushgram_last_carousel_save");
                lastCarouselSave.setPersistent(false);
                lastCarouselSave.setSelectable(false);
                lastCarouselSave.setOrder(Integer.MIN_VALUE / 4);
                lastCarouselSave.setTitle(L10n.t("Last carousel save"));
                mark(lastCarouselSave, SettingsIcons.DOWNLOADS);
            }
            lastCarouselSave.setSummary(outcome);
            if (lastCarouselSave.getParent() != group) group.addPreference(lastCarouselSave);
        } else if (lastCarouselSave != null && lastCarouselSave.getParent() == group) {
            group.removePreference(lastCarouselSave);
        }
        List<SaveControl.Running> running = SaveControl.running();
        Set<Integer> now = new HashSet<>();
        for (SaveControl.Running save : running) now.add(save.id);
        for (java.util.Iterator<Map.Entry<Integer, SaveRow>> rows = saveRows.entrySet().iterator(); rows.hasNext(); ) {
            Map.Entry<Integer, SaveRow> row = rows.next();
            if (now.contains(row.getKey())) continue;
            group.removePreference(row.getValue());
            rows.remove();
        }
        for (SaveControl.Running save : running) {
            SaveRow row = saveRows.get(save.id);
            if (row != null) {
                row.show(save);
                continue;
            }
            row = new SaveRow(group.getContext(), save);
            saveRows.put(save.id, row);
            group.addPreference(row);
        }
        filterSettings();
    }

    /** Inline input. Its query is local to this page and never becomes a stored setting. */
    private final class SearchRow extends Preference {
        SearchRow(Context context) { super(context); }

        @Override protected View onCreateView(android.view.ViewGroup parent) {
            Context context = getContext();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int touch = Math.round(48 * context.getResources().getDisplayMetrics().density);
            int inset = Math.round(16 * context.getResources().getDisplayMetrics().density);
            row.setPaddingRelative(inset, 0, inset, 0);
            row.setMinimumHeight(touch);
            // Legacy ListView refocuses a row during layout. Keep focus on its typing field.
            row.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            EditText field = new EditText(context);
            field.setTag("hushgram-settings-search");
            field.setSaveEnabled(false);
            field.setSingleLine(true);
            field.setTextSize(16);
            field.setTextColor(ScreenColors.DEFAULT.title);
            field.setHintTextColor(ScreenColors.DEFAULT.summary);
            field.setHint(L10n.t("Search settings"));
            field.setContentDescription(L10n.t("Search settings"));
            field.setMinimumHeight(touch);
            field.setText(searchQuery);
            SearchFocus focus = new SearchFocus(this, row);
            field.setAccessibilityDelegate(focus);
            row.addOnAttachStateChangeListener(focus);
            row.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button clear = new Button(context);
            clear.setText("×");
            clear.setAllCaps(false);
            clear.setTextSize(24);
            clear.setTextColor(ScreenColors.DEFAULT.heading);
            clear.setBackgroundColor(Color.TRANSPARENT);
            clear.setContentDescription(L10n.t("Clear search"));
            clear.setPadding(0, 0, 0, 0);
            clear.setMinimumWidth(touch);
            clear.setMinimumHeight(touch);
            clear.setEnabled(!searchQuery.isEmpty());
            clear.setVisibility(searchQuery.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            clear.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    if (!SearchFocus.canAct(SearchRow.this, row, host)) {
                        info.setEnabled(false);
                        info.setClickable(false);
                        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                    }
                }

                @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                    if (action == AccessibilityNodeInfo.ACTION_CLICK
                            && !SearchFocus.canAct(SearchRow.this, row, host)) return false;
                    return super.performAccessibilityAction(host, action, arguments);
                }
            });
            clear.setOnClickListener(view -> {
                if (SearchFocus.canAct(this, row, view)) field.setText("");
            });
            row.addView(clear, new LinearLayout.LayoutParams(touch, LinearLayout.LayoutParams.WRAP_CONTENT));
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable text) {
                    if (SearchFocus.canAct(SearchRow.this, row, field)) searchSettings(text.toString());
                    clear.setEnabled(text.length() > 0);
                    clear.setVisibility(text.length() == 0 ? View.INVISIBLE : View.VISIBLE);
                }
            });
            return row;
        }
    }

    /** Keep ListView's stable row while its search input owns accessibility focus. */
    static final class SearchFocus extends View.AccessibilityDelegate implements View.OnAttachStateChangeListener {
        private final Preference preference;
        private final View row;
        private boolean held;

        SearchFocus(Preference preference, View row) { this.preference = preference; this.row = row; }

        private static boolean canAct(Preference preference, View row, View host) {
            return host.isAttachedToWindow() && host.isShown() && RowSemantics.enabledViewTree(host)
                    && preference.isEnabled()
                    && RowSemantics.positionOf(preference, row, RowSemantics.listOf(row))
                    != AdapterView.INVALID_POSITION;
        }

        @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            if (!canAct(preference, row, host)) {
                info.setEnabled(false);
                info.setClickable(false);
                for (AccessibilityNodeInfo.AccessibilityAction action : new ArrayList<>(info.getActionList())) {
                    if (action.getId() != AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) info.removeAction(action);
                }
            }
        }

        @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                boolean performed = super.performAccessibilityAction(host, action, arguments);
                // Android omits the cleared event when accessibility is disabled.
                hold(false);
                return performed;
            }
            return canAct(preference, row, host) && super.performAccessibilityAction(host, action, arguments);
        }

        @Override public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) hold(true);
            else if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) hold(false);
        }

        private void hold(boolean value) {
            if (value == held) return;
            row.setHasTransientState(value);
            held = value;
        }

        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { hold(false); }
    }

    /**
     * The first row: whether HushGram is on, and why not when it isn't. Paused by safe mode or the
     * marker file, a tap turns it back on from the next start.
     */
    private Preference statusCard(Context context) {
        Row card = new Row(context);
        card.setPersistent(false);
        card.setEnabled(!resumingFromOverview);
        boolean paused = HushgramPause.isPaused();
        ScreenColors palette = ScreenColors.DEFAULT;
        card.setIcon(SettingsIcons.icon(context, paused ? SettingsIcons.PAUSE : SettingsIcons.PATCHED,
                paused ? palette.summary : palette.heading));
        statusCard = card;
        if (!paused) {
            card.setTitle(L10n.t("HushGram is on"));
            card.setSelectable(false);
            showStatus(card, context);
            return card;
        }
        card.setTitle(L10n.t("HushGram is paused"));
        showStatus(card, context);
        // A tap acts at once, taking the pause off the next start, and the card says so in words.
        card.actsAtOnce = true;
        card.setOnPreferenceClickListener(p -> {
            resumeFromOverview();
            return true;
        });
        return card;
    }

    /**
     * What's known about the account and a patched Instagram, under the status card until a tap
     * hides it for good: Google's check fails on a re-signed build whatever the patches do, and
     * what keeps the sign-in. It ranks no install or account as safer, since nobody outside Meta
     * knows what gets an account suspended.
     */
    private static Preference signInNotice(Context context, PreferenceScreen screen) {
        Row notice = new Row(context);
        notice.setPersistent(false);
        notice.setKey(SIGN_IN_NOTICE_KEY);
        notice.setIcon(SettingsIcons.icon(context, SettingsIcons.ABOUT, ScreenColors.DEFAULT.heading));
        notice.setTitle(L10n.t("Before you sign in"));
        notice.setSummary(L10n.t("Nobody outside Meta knows what gets an account suspended. A patched Instagram "
                + "can't pass Google's check that it came from the Play Store, and no patch changes that. If you'd "
                + "rather not risk your account, try a spare one first. Installing updates over the top with the "
                + "same signing key keeps Instagram's data and your sign-in. On a rooted phone, a Root Mount install "
                + "keeps the sign-in you already have.")
                + " " + L10n.t("Tap to hide this."));
        notice.actsAtOnce = true;
        notice.setOnPreferenceClickListener(row -> {
            if (Settings.SIGN_IN_NOTICE_HIDDEN.save(true)) screen.removePreference(row);
            else Utils.showToastLong(L10n.t(context, "Couldn't hide this notice. Try again."));
            return true;
        });
        return notice;
    }

    void resumeFromOverview() {
        Context context = getContext();
        Activity activity = getActivity();
        if (resumingFromOverview || context == null || statusCard == null || getView() == null
                || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        Context application = context.getApplicationContext();
        Context storage = application == null ? context : application;
        resumingFromOverview = true;
        setResumeControlsEnabled(false);
        if (!Utils.runOnBackgroundThread(() -> {
            HushgramPause.Reason kept = null;
            try { kept = HushgramPause.turnBackOn(storage); }
            catch (RuntimeException failure) {
                Logger.printInfo(() -> "HushGram pause recovery failed", failure);
            } finally {
                HushgramPause.Reason still = kept;
                Utils.runOnMainThread(() -> resumeFromOverviewFinished(still));
            }
        })) resumeFromOverviewFinished(null);
    }

    private void setResumeControlsEnabled(boolean enabled) {
        if (statusCard != null) statusCard.setEnabled(enabled);
        Preference pause = findPreference(BaseSettings.PAUSED.key);
        if (pause != null) pause.setEnabled(enabled && BaseSettings.PAUSED.isAvailable());
    }

    private void resumeFromOverviewFinished(@Nullable HushgramPause.Reason still) {
        resumingFromOverview = false;
        Context context = getContext();
        Activity activity = getActivity();
        if (context == null || statusCard == null || getView() == null || activity == null
                || activity.isFinishing() || activity.isDestroyed()) return;
        setResumeControlsEnabled(true);
        // The switch shows what was kept. Setting it to off here would write off through the
        // preference itself when the store had just refused to.
        Preference pause = findPreference(BaseSettings.PAUSED.key);
        if (pause != null) syncSettingWithPreference(pause, BaseSettings.PAUSED, true);
        if (still == HushgramPause.Reason.MARKER_FILE) {
            String file = L10n.isolate(HushgramPause.MARKER_FILE_NAME);
            String folder = L10n.isolate(markerFolder(context.getPackageName()));
            String left = L10n.f("The file %1$s couldn't be removed. Delete it from %2$s to turn HushGram back on.",
                    file, folder);
            statusCard.setSummary(left);
            Utils.showToastLong(left);
            return;
        }
        if (still != HushgramPause.Reason.NONE) {
            Utils.showToastLong(L10n.t("Couldn't turn HushGram back on. Try again."));
        }
        showStatus(statusCard, context);
    }

    /**
     * The card's line under its title: this start's state, and the next start's when a change on
     * this screen makes it differ.
     */
    private void showStatus(Preference card, Context context) {
        boolean pausedNext = HushgramPause.pausesNextStart(context);
        String status;
        if (!HushgramPause.isPaused()) {
            String version = L10n.f("Version %1$s for Instagram %2$s",
                    L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName()));
            status = pausedNext ? version + " " + L10n.t("HushGram pauses when Instagram restarts.") : version;
        } else if (pausedNext) {
            status = pausedSummary(HushgramPause.reason(), context.getPackageName())
                    + " " + L10n.t("Tap to turn it back on.");
        } else {
            status = L10n.t("HushGram turns back on when Instagram restarts.");
        }
        card.setSummary(status);
    }

    @Override
    protected void onRestartPendingChanged() {
        Preference card = statusCard;
        Context context = getContext();
        if (card != null && context != null) showStatus(card, context);
    }

    /**
     * Why this start runs paused, what a pause does and doesn't reach, and that nothing the reader
     * saved has changed.
     */
    static String pausedSummary(HushgramPause.Reason reason, String packageName) {
        String why;
        switch (reason) {
            case CRASH_LOOP:
                why = L10n.t("Instagram crashed or froze within a minute of starting three times in a row, so "
                        + "HushGram paused itself.");
                break;
            case MARKER_FILE:
                why = L10n.f("A file named %1$s in %2$s paused HushGram.",
                        L10n.isolate(HushgramPause.MARKER_FILE_NAME), L10n.isolate(markerFolder(packageName)));
                break;
            default:
                why = L10n.t("You paused HushGram.");
                break;
        }
        return why + " " + L10n.t("Every switch but Debug logging acts as if it were off, and what was set when you "
                + "patched stays in. Your settings stay as they are.");
    }

    /** Where the marker file goes, the way a file manager shows it. */
    static String markerFolder(String packageName) {
        return "Android/data/" + packageName + "/files";
    }

    /** The dark Material theme every row and dialog on this screen is built with. */
    static Context themed(Context base) {
        return new ContextThemeWrapper(base, ScreenColors.themeFor(base));
    }

    /** The recovery page draws on the same page as the rows, so it gets the same theme. */
    @Override
    protected Context pageContext(Activity activity) {
        return themed(activity);
    }

    @Override
    protected ErrorActionStyler errorActionStyler() {
        return ScreenColors::recoveryAction;
    }

    @Override
    protected void styleInitializationMessage(View row) {
        ScreenColors.recoveryMessage(row);
    }

    private static PreferenceCategory category(PreferenceScreen screen, String title) {
        PreferenceCategory category = new Heading(screen.getContext());
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    /**
     * A lock's switch. Turning it off while Instagram is locked asks the phone's lock first, so the
     * switch can't be used to get around it.
     */
    static SwitchPreference lockToggle(Context context, BooleanSetting setting, String title, String summary) {
        SwitchPreference row = toggle(context, setting, title, summary);
        row.setOnPreferenceChangeListener((preference, value) -> {
            Activity activity = activityOf(preference.getContext());
            if (Boolean.TRUE.equals(value)) {
                MessagesLock.openUntilLeft();
                return true;
            }
            if (!MessagesLock.locked() || activity == null) return true;
            MessagesLock.confirmThen(activity, () -> ((SwitchPreference) preference).setChecked(false));
            return false;
        });
        return row;
    }

    /** How long after you leave Instagram the locks lock again; its summary says what the choice does. */
    static LockDelayRow lockDelayRow(Context context) {
        LockDelayRow row = new LockDelayRow(context);
        row.setKey(Settings.LOCK_AGAIN.key);
        row.setTitle(L10n.t("Lock again"));
        row.setDialogTitle(L10n.t("Lock again"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        LockDelay[] delays = LockDelay.values();
        CharSequence[] entries = new CharSequence[delays.length];
        CharSequence[] values = new CharSequence[delays.length];
        for (int i = 0; i < delays.length; i++) {
            entries[i] = lockDelayLabel(delays[i]);
            values[i] = delays[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.LOCK_AGAIN.savedValue().name());
        return row;
    }

    /** What the list calls [delay]. */
    static String lockDelayLabel(LockDelay delay) {
        switch (delay) {
            case ONE_MINUTE: return L10n.t("After 1 minute");
            case FIVE_MINUTES: return L10n.t("After 5 minutes");
            case FIFTEEN_MINUTES: return L10n.t("After 15 minutes");
            case ONE_HOUR: return L10n.t("After 1 hour");
            default: return L10n.t("Right away");
        }
    }

    /** The row that opens the list of chats locked one at a time. */
    private Row lockedChatsRow(Context context) {
        Row row = new Row(context);
        row.setKey(LOCKED_CHATS_ROW);
        row.setPersistent(false);
        row.setTitle(L10n.t("Locked chats"));
        row.setSummary(L10n.t("Lock single chats. A locked chat asks for your fingerprint, face or screen lock each "
                + "time you open it, and its notifications say only that a message came."));
        row.setOnPreferenceClickListener(tapped -> {
            showLockedChats();
            return true;
        });
        return row;
    }

    /**
     * The locked chats, each with a check, and the chat you opened last above them when it isn't on
     * the list. Checking one locks it at once; unchecking one asks the phone's lock first while
     * anything is locked, so the list can't be used to get around it.
     */
    private void showLockedChats() {
        Context context = getActivity();
        if (context == null) return;
        List<ChatLocks.Chat> chats = new ArrayList<>(ChatLocks.chats());
        ChatLocks.Chat last = ChatLocks.lastOpened();
        int offered = last == null ? 0 : 1;
        if (last != null) chats.add(0, last);
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Locked chats"));
        if (chats.isEmpty()) {
            builder.setMessage(L10n.t("Open a chat in Instagram, then come back here to lock it."));
        } else {
            CharSequence[] names = new CharSequence[chats.size()];
            boolean[] locked = new boolean[chats.size()];
            for (int i = 0; i < names.length; i++) {
                boolean offer = i < offered;
                names[i] = offer ? L10n.f("%1$s (opened last)", chats.get(i).name) : chats.get(i).name;
                locked[i] = !offer;
            }
            builder.setMultiChoiceItems(names, locked, (dialog, which, checked) ->
                    lockChat((AlertDialog) dialog, chats.get(which), which, checked));
        }
        show(builder.setPositiveButton(L10n.t("OK"), null));
    }

    private void lockChat(AlertDialog dialog, ChatLocks.Chat chat, int position, boolean lock) {
        if (lock) {
            ChatLocks.add(chat.id, chat.name);
            MessagesLock.chatListed();
            Utils.showToastShort(L10n.f("%1$s is locked", chat.name));
            return;
        }
        Runnable unlock = () -> {
            ChatLocks.remove(chat.id);
            Utils.showToastShort(L10n.f("%1$s is unlocked", chat.name));
            setChecked(dialog, position, false);
        };
        Activity activity = getActivity();
        if (activity == null) {
            setChecked(dialog, position, true);
            return;
        }
        // Stays checked until the phone's lock says it's you, if anything is locked.
        setChecked(dialog, position, true);
        MessagesLock.confirmChatsThen(activity, unlock);
    }

    /** The row that opens the list of chats hidden one at a time. */
    private Row hiddenChatsRow(Context context) {
        Row row = new Row(context);
        row.setKey(HIDDEN_CHATS_ROW);
        row.setPersistent(false);
        row.setTitle(L10n.t("Hidden chats"));
        row.setSummary(L10n.t("Hide single chats. A hidden chat leaves your inbox and its notifications don't post "
                + "until you show it here again."));
        row.setOnPreferenceClickListener(tapped -> {
            Activity activity = getActivity();
            if (activity == null) return true;
            // The list names the chats, so it asks the phone's lock first while Lock your messages is on.
            MessagesLock.confirmHiddenThen(activity, this::showHiddenChats);
            return true;
        });
        return row;
    }

    /**
     * The hidden chats, each with a check, and the chat you opened last above them when it isn't
     * hidden. Checking one hides it at once; unchecking one shows it again. Paused, the list still
     * shows what you chose, though Instagram shows every chat until HushGram is back on.
     */
    private void showHiddenChats() {
        Context context = getActivity();
        if (context == null) return;
        List<ChatLocks.Chat> chats = new ArrayList<>(HiddenChats.saved());
        ChatLocks.Chat last = HiddenChats.lastOpened();
        int offered = last == null ? 0 : 1;
        if (last != null) chats.add(0, last);
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Hidden chats"));
        if (chats.isEmpty()) {
            builder.setMessage(L10n.t("Open a chat in Instagram, then come back here to hide it."));
        } else {
            CharSequence[] names = new CharSequence[chats.size()];
            boolean[] hidden = new boolean[chats.size()];
            for (int i = 0; i < names.length; i++) {
                boolean offer = i < offered;
                names[i] = offer ? L10n.f("%1$s (opened last)", chats.get(i).name) : chats.get(i).name;
                hidden[i] = !offer;
            }
            builder.setMultiChoiceItems(names, hidden, (dialog, which, checked) -> {
                ChatLocks.Chat chat = chats.get(which);
                if (checked) {
                    HiddenChats.add(chat.id, chat.name);
                    Utils.showToastShort(L10n.f("%1$s is hidden", chat.name));
                } else {
                    HiddenChats.remove(chat.id);
                    Utils.showToastShort(L10n.f("%1$s is shown again", chat.name));
                }
            });
        }
        show(builder.setPositiveButton(L10n.t("OK"), null));
    }

    /** The row that opens the list of accounts whose posts Home and Following leave out. */
    private Row hiddenAccountsRow(Context context) {
        Row row = new Row(context);
        row.setKey(HIDDEN_ACCOUNTS_ROW);
        row.setPersistent(false);
        row.setTitle(L10n.t("Hidden accounts"));
        row.setSummary(L10n.t("Takes posts by the accounts you pick out of Home and Following. Each account you "
                + "sign in to keeps its own list."));
        row.setOnPreferenceClickListener(tapped -> {
            showHiddenAccounts();
            return true;
        });
        return row;
    }

    /**
     * The usernames hidden for the signed-in account, each with a check. Unchecking one shows that
     * account's posts again at once, and checking it again hides them. Add a username opens a box to
     * type one. Paused, the list still shows what you chose, though Home shows every post.
     */
    void showHiddenAccounts() {
        Context context = getActivity();
        if (context == null) return;
        List<String> names = HiddenAccounts.saved();
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Hidden accounts"));
        if (names.isEmpty()) {
            builder.setMessage(L10n.t("No accounts are hidden yet. Add a username to take that account's posts out "
                    + "of Home and Following."));
        } else {
            CharSequence[] shown = new CharSequence[names.size()];
            boolean[] hidden = new boolean[names.size()];
            for (int i = 0; i < shown.length; i++) {
                shown[i] = "@" + names.get(i);
                hidden[i] = true;
            }
            builder.setMultiChoiceItems(shown, hidden, (dialog, which, checked) -> {
                String name = names.get(which);
                if (checked) {
                    HiddenAccounts.add(name);
                    Utils.showToastShort(L10n.f("Posts from @%1$s are hidden", name));
                } else {
                    HiddenAccounts.remove(name);
                    Utils.showToastShort(L10n.f("Posts from @%1$s show again", name));
                }
            });
        }
        show(builder.setNeutralButton(L10n.t("Add a username"), (dialog, which) -> askHiddenAccount())
                .setPositiveButton(L10n.t("OK"), null));
    }

    /**
     * A box to type a username in. Hide their posts adds it for the signed-in account and goes back
     * to the list; a name that can't be a username is turned down with a toast saying what one is.
     */
    void askHiddenAccount() {
        Context context = getActivity();
        if (context == null) return;
        EditText field = new EditText(context);
        field.setTag(HIDDEN_ACCOUNTS_ROW);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setHint(L10n.t("Username"));
        field.setContentDescription(L10n.t("Username"));
        int inset = Math.round(20 * context.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(context);
        box.setPaddingRelative(inset, inset / 2, inset, 0);
        box.addView(field);
        AlertDialog dialog = show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Add a username"))
                .setView(box)
                .setPositiveButton(L10n.t("Hide their posts"), (shown, which) -> {
                    String name = HiddenAccounts.add(field.getText().toString());
                    if (name == null) {
                        Utils.showToastShort(L10n.t("A username is up to 30 letters, numbers, dots and underscores"));
                    } else {
                        Utils.showToastShort(L10n.f("Posts from @%1$s are hidden", name));
                    }
                    showHiddenAccounts();
                })
                .setNegativeButton(L10n.t("Cancel"), (shown, which) -> showHiddenAccounts()));
        fitAboveKeyboard(dialog);
    }

    private static void setChecked(AlertDialog dialog, int position, boolean checked) {
        try {
            dialog.getListView().setItemChecked(position, checked);
        } catch (RuntimeException e) {
            // The dialog is gone; the list already holds the answer.
            Logger.printInfo(() -> "Locked chats: dialog closed before the row could change");
        }
    }

    @Nullable
    private static Activity activityOf(Context context) {
        for (Context at = context; at != null; at = at instanceof android.content.ContextWrapper
                ? ((android.content.ContextWrapper) at).getBaseContext() : null) {
            if (at instanceof Activity) return (Activity) at;
        }
        return null;
    }

    /** A privacy row's summary, plus how much of the patch this build has when it's only part. */
    static String withCoverage(String summary, String encodedCoverage) {
        String note = PatchFamily.partialCoverageNote(encodedCoverage);
        return note.isEmpty() ? summary : summary + " " + note;
    }

    static SwitchPreference toggle(Context context, BooleanSetting setting, String title, String summary) {
        SwitchPreference preference = new Toggle(context);
        preference.setKey(setting.key);
        preference.setTitle(title);
        preference.setSummary(summary);
        return preference;
    }

    /**
     * The quality a video save asks for. The list's values are the setting's own names, which is
     * what the shared page syncs a list by, and the summary says what the choice does.
     */
    static NavigationRow navigationRow(Context context) {
        return tabRow(context, false);
    }

    /** The row choosing the tab whose long press opens the playback quality list (#93). */
    static NavigationRow qualityTabRow(Context context) {
        return tabRow(context, true);
    }

    private static NavigationRow tabRow(Context context, boolean forQuality) {
        EnumSetting<NavigationTarget> setting = forQuality ? Settings.QUALITY_TAB_TARGET : Settings.NAVIGATION_SETTINGS_TARGET;
        NavigationRow row = new NavigationRow(context, forQuality);
        row.setKey(setting.key);
        row.setTitle(forQuality ? L10n.t("Pick the playback quality with a tab long press")
                : L10n.t("Open settings with a tab long press"));
        row.setDialogTitle(L10n.t("Choose a tab"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        NavigationTarget[] targets = NavigationTarget.values();
        CharSequence[] labels = new CharSequence[targets.length];
        CharSequence[] values = new CharSequence[targets.length];
        for (int i = 0; i < targets.length; i++) {
            labels[i] = navigationLabel(targets[i]);
            values[i] = targets[i].name();
        }
        row.setEntries(labels);
        row.setEntryValues(values);
        row.setValue(setting.savedValue().name());
        return row;
    }

    static String navigationLabel(NavigationTarget target) {
        switch (target) {
            case FEED: return L10n.t("Home");
            case SEARCH: return L10n.t("Search");
            case CLIPS: return L10n.t("Reels");
            case DIRECT: return L10n.t("Messages");
            case PROFILE: return L10n.t("Profile");
            case SHARE: return L10n.t("Create (+)");
            case CREATION: return L10n.t("Camera");
            case NEWS: return L10n.t("Activity");
            case PRODUCER_PROFILE_PANEL: return L10n.t("Creator tools");
            case FEED_SWITCHER: return L10n.t("Home feed picker");
            case DYNAMIC_TAB: return L10n.t("Custom tab");
            default: return L10n.t("Off");
        }
    }

    static QualityRow qualityRow(Context context) {
        QualityRow row = new QualityRow(context);
        row.setKey(Settings.DOWNLOAD_QUALITY.key);
        row.setTitle(L10n.t("Download quality"));
        row.setDialogTitle(L10n.t("Download quality"));
        // A list's dialog keeps DialogPreference's Cancel, which Android fills in the activity's
        // language unless it's set here, as the folder and file name rows' are.
        row.setNegativeButtonText(L10n.t("Cancel"));
        DownloadQuality[] qualities = DownloadQuality.values();
        CharSequence[] entries = new CharSequence[qualities.length];
        CharSequence[] values = new CharSequence[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            entries[i] = qualityLabel(qualities[i]);
            values[i] = qualities[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.DOWNLOAD_QUALITY.savedValue().name());
        return row;
    }

    /** What the list shows for [quality]: a word for the two ends, the label itself between them. */
    static String qualityLabel(DownloadQuality quality) {
        switch (quality) {
            case BEST:
                return L10n.t("Best");
            case SMALLEST:
                return L10n.t("Smallest");
            default:
                return L10n.isolate(quality.ceilingLabel());
        }
    }

    /**
     * What a save does with [quality], for the row's summary. A cap takes the best rendition at or
     * under it, and only a video with nothing that low goes above it ({@link DownloadQuality}), so
     * the sentence names both directions: "the closest quality" alone read as 1080p beating 240p
     * under a 720p cap, and the save picks 240p.
     */
    static String qualitySummary(DownloadQuality quality) {
        switch (quality) {
            case BEST:
                return L10n.t("Each video saves at the best quality Instagram offers for it.");
            case SMALLEST:
                return L10n.t("Each video saves at its lowest quality, for the smallest file.");
            default:
                return L10n.f("Each video saves at %1$s or the closest quality below it. A video with nothing "
                        + "that low saves at the closest quality above.", L10n.isolate(quality.ceilingLabel()));
        }
    }

    /**
     * The quality videos play at. Like the download quality's row, its values are the setting's own
     * names and its summary says what the choice does.
     */
    static PlaybackQualityRow playbackQualityRow(Context context) {
        PlaybackQualityRow row = new PlaybackQualityRow(context);
        row.setKey(Settings.PLAYBACK_QUALITY.key);
        row.setTitle(L10n.t("Playback quality"));
        row.setDialogTitle(L10n.t("Playback quality"));
        // Android's own Cancel follows the activity's language, as the download quality's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        PlaybackQuality[] qualities = PlaybackQuality.values();
        CharSequence[] entries = new CharSequence[qualities.length];
        CharSequence[] values = new CharSequence[qualities.length];
        for (int i = 0; i < qualities.length; i++) {
            entries[i] = playbackQualityLabel(qualities[i]);
            values[i] = qualities[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.PLAYBACK_QUALITY.savedValue().name());
        return row;
    }

    /** What the list calls [quality]: Auto for Instagram's own choice, and a ceiling by its label. */
    static String playbackQualityLabel(PlaybackQuality quality) {
        switch (quality) {
            case DATA_SAVER:
                return L10n.t("Data saver");
            case P480:
            case P720:
                return L10n.f("Up to %1$s", L10n.isolate(quality.fileValue));
            case HIGHEST:
                return L10n.t("Highest");
            default:
                return L10n.t("Auto");
        }
    }

    /**
     * What a video does with [quality], for the row's summary. The rungs are the ones Instagram
     * offers for each video, so the summary says the video has to offer the quality rather than
     * promise it.
     */
    static String playbackQualitySummary(PlaybackQuality quality) {
        switch (quality) {
            case DATA_SAVER:
                return L10n.t("Videos play at the lowest quality Instagram offers for each.");
            case P480:
            case P720:
                return L10n.f("Videos play at the best quality up to %1$s that Instagram offers for each, or the closest above.",
                        L10n.isolate(quality.fileValue));
            case HIGHEST:
                return L10n.t("Videos play at the highest quality Instagram offers for each.");
            default:
                return L10n.t("Instagram picks the quality as each video plays, from your connection.");
        }
    }

    /**
     * Where Tap to play holds starts. Like the playback quality's row, its values are the setting's
     * own names and its summary says what the choice does.
     */
    /** Clear now: empties the media cache off the main thread, then shows what it freed on the row. */
    private static void clearMediaCache(Preference row) {
        Context context = row.getContext();
        if (!Utils.runOnBackgroundThread(() -> {
            long freed = MediaCache.clearNow(context);
            String size = L10n.isolate(Formatter.formatShortFileSize(context, freed));
            String shown = MediaCache.videosWaiting(context)
                    ? L10n.f("Freed %1$s. The videos go the next time Instagram starts.", size)
                    : L10n.f("Freed %1$s.", size);
            Utils.runOnMainThread(() -> {
                row.setSummary(shown);
                Utils.showToastShort(shown);
            });
        })) {
            Utils.showToastLong(L10n.t("Couldn't clear the saved copies. Try again."));
        }
    }

    static TapToPlayScopeRow tapToPlayScopeRow(Context context) {
        TapToPlayScopeRow row = new TapToPlayScopeRow(context);
        row.setKey(Settings.TAP_TO_PLAY_SCOPE.key);
        row.setTitle(L10n.t("Where videos wait"));
        row.setDialogTitle(L10n.t("Where videos wait"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        TapToPlayScope[] scopes = TapToPlayScope.values();
        CharSequence[] entries = new CharSequence[scopes.length];
        CharSequence[] values = new CharSequence[scopes.length];
        for (int i = 0; i < scopes.length; i++) {
            entries[i] = tapToPlayScopeLabel(scopes[i]);
            values[i] = scopes[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.TAP_TO_PLAY_SCOPE.savedValue().name());
        return row;
    }

    /** What the list calls [scope]. */
    static String tapToPlayScopeLabel(TapToPlayScope scope) {
        switch (scope) {
            case OUTSIDE_REELS:
                return L10n.t("Everywhere but Reels");
            case ONLY_REELS:
                return L10n.t("Only in Reels");
            default:
                return L10n.t("Everywhere");
        }
    }

    /** What waits for a tap with [scope], for the row's summary. Reels in the feed go with the feed. */
    static String tapToPlayScopeSummary(TapToPlayScope scope) {
        switch (scope) {
            case OUTSIDE_REELS:
                return L10n.t("Feed videos and stories wait for your tap. Reels play as you swipe to them.");
            case ONLY_REELS:
                return L10n.t("Reels wait for your tap. Feed videos and stories play as Instagram plays them.");
            default:
                return L10n.t("Feed videos, reels and stories all wait for your tap.");
        }
    }

    /** What a single tap on a reel does. Its summary says what the choice does. */
    static ReelTapChoiceRow reelTapChoiceRow(Context context) {
        ReelTapChoiceRow row = new ReelTapChoiceRow(context);
        row.setKey(Settings.REEL_TAP_CHOICE.key);
        row.setTitle(L10n.t("A tap on a reel"));
        row.setDialogTitle(L10n.t("A tap on a reel"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        ReelTapChoice[] choices = ReelTapChoice.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = reelTapChoiceLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.REEL_TAP_CHOICE.savedValue().name());
        return row;
    }

    /** What the list calls [choice]. */
    static String reelTapChoiceLabel(ReelTapChoice choice) {
        switch (choice) {
            case PAUSE:
                return L10n.t("Pause");
            case MUTE:
                return L10n.t("Mute");
            default:
                return L10n.t("Instagram's default");
        }
    }

    /** What a tap does with [choice], for the row's summary. */
    static String reelTapChoiceSummary(ReelTapChoice choice) {
        switch (choice) {
            case PAUSE:
                return L10n.t("A tap on a reel pauses it, and another tap plays it again.");
            case MUTE:
                return L10n.t("A tap on a playing reel turns its sound off or on and keeps it playing. "
                        + "A reel that's paused still plays on a tap.");
            default:
                return L10n.t("A tap on a reel does what Instagram does.");
        }
    }

    /** The tick the glass tab bar plays. Its summary is the entry chosen, so there are no sentences to keep in step. */
    static HapticStyleRow hapticStyleRow(Context context) {
        HapticStyleRow row = new HapticStyleRow(context);
        row.setKey(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key);
        row.setTitle(L10n.t("Haptic style"));
        row.setDialogTitle(L10n.t("Haptic style"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        HapticStyle[] styles = HapticStyle.values();
        CharSequence[] entries = new CharSequence[styles.length];
        CharSequence[] values = new CharSequence[styles.length];
        for (int i = 0; i < styles.length; i++) {
            entries[i] = hapticStyleLabel(styles[i]);
            values[i] = styles[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.GLASS_TAB_BAR_HAPTIC_STYLE.savedValue().name());
        return row;
    }

    /** The row that opens the tab order list. Its summary names the order chosen. */
    private Row tabOrderRow(Context context) {
        Row row = new Row(context);
        row.setKey(Settings.TAB_ORDER.key);
        row.setPersistent(false);
        row.setTitle(L10n.t("Tab order"));
        row.setSummary(tabOrderSummary(Settings.TAB_ORDER.get()));
        row.setOnPreferenceClickListener(tapped -> {
            showTabOrder(row);
            return true;
        });
        return row;
    }

    /** What the tab order [saved] does, for its row's summary. */
    static String tabOrderSummary(String saved) {
        if (TabOrder.parse(saved).isEmpty()) return L10n.t("The tab bar keeps Instagram's order.");
        List<String> labels = new ArrayList<>();
        for (String name : TabOrder.choices(saved)) labels.add(L10n.isolate(tabLabel(name)));
        String order = String.join(", ", labels);
        return L10n.f("Tabs go in this order: %1$s. Restart Instagram to see the change.", order);
    }

    /** What the tab order list calls the tab named [name] in Instagram's tab enum. */
    static String tabLabel(String name) {
        switch (name) {
            case "SEARCH":
                return L10n.t("Search");
            case "CLIPS":
                return L10n.t("Reels");
            case "DIRECT":
                return L10n.t("Messages");
            case "CREATION":
                return L10n.t("Create");
            case "PROFILE":
                return L10n.t("Profile");
            default:
                return L10n.t("Home");
        }
    }

    /**
     * The tabs on the bar in the order chosen. A tap on one moves it a place up and saves the order
     * at once, keeping the list open, so a tab can be walked to the top. Instagram's order clears
     * the choice. The bar is built as Instagram starts, so it shows after a restart.
     */
    void showTabOrder(Row row) {
        Context context = getActivity();
        if (context == null) return;
        List<String> order = new ArrayList<>(TabOrder.choices(Settings.TAB_ORDER.get()));
        ArrayAdapter<String> labels = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, tabLabels(order));
        AlertDialog dialog = show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Tap a tab to move it up"))
                .setAdapter(labels, null)
                .setNeutralButton(L10n.t("Instagram's order"), (shown, which) -> {
                    Settings.TAB_ORDER.save("");
                    row.setSummary(tabOrderSummary(""));
                })
                .setPositiveButton(L10n.t("OK"), null));
        if (dialog == null) return;
        // A click listener of its own, so a tap moves the tab without closing the list.
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            List<String> moved = TabOrder.movedUp(order, position);
            order.clear();
            order.addAll(moved);
            Settings.TAB_ORDER.save(TabOrder.join(order));
            labels.clear();
            labels.addAll(tabLabels(order));
            row.setSummary(tabOrderSummary(Settings.TAB_ORDER.get()));
        });
    }

    private static List<String> tabLabels(List<String> names) {
        List<String> labels = new ArrayList<>(names.size());
        for (String name : names) labels.add(tabLabel(name));
        return labels;
    }

    /** The tab Instagram opens on when it's started from its icon. Its summary says what the choice does. */
    static StartTabRow startTabRow(Context context) {
        StartTabRow row = new StartTabRow(context);
        row.setKey(Settings.START_TAB.key);
        row.setTitle(L10n.t("Start tab"));
        row.setDialogTitle(L10n.t("Start tab"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        StartTab[] tabs = StartTab.values();
        CharSequence[] entries = new CharSequence[tabs.length];
        CharSequence[] values = new CharSequence[tabs.length];
        for (int i = 0; i < tabs.length; i++) {
            entries[i] = startTabLabel(tabs[i]);
            values[i] = tabs[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.START_TAB.savedValue().name());
        return row;
    }

    /** What the list calls [tab]. */
    static String startTabLabel(StartTab tab) {
        switch (tab) {
            case SEARCH:
                return L10n.t("Search");
            case MESSAGES:
                return L10n.t("Messages");
            case REELS:
                return L10n.t("Reels");
            case PROFILE:
                return L10n.t("Profile");
            default:
                return L10n.t("Home");
        }
    }

    /** What the choice of [tab] does, for the row's summary. */
    static String startTabSummary(StartTab tab) {
        if (tab == StartTab.HOME) return L10n.t("Instagram opens the way it always has.");
        return L10n.f("Instagram opens on %1$s when you start it from its icon. A notification or a link still opens "
                + "what it's for. If that tab isn't on your bar, it opens on Home. Restart Instagram to see the change.",
                L10n.isolate(startTabLabel(tab)));
    }

    /** What the list calls [style]. */
    static String hapticStyleLabel(HapticStyle style) {
        switch (style) {
            case SYSTEM:
                return L10n.t("System tick");
            case SOFT:
                return L10n.t("Soft tick");
            case FULL:
                return L10n.t("Full tick");
            default:
                return L10n.t("Short tick");
        }
    }

    /**
     * How a story's time shows. Like Tap to play's choice of where, its values are the setting's own
     * names and its summary says what the choice does.
     */
    static StoryTimeModeRow storyTimeModeRow(Context context) {
        StoryTimeModeRow row = new StoryTimeModeRow(context);
        row.setKey(Settings.STORY_TIME_MODE.key);
        row.setTitle(L10n.t("How the time shows"));
        row.setDialogTitle(L10n.t("How the time shows"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        StoryTimeMode[] modes = StoryTimeMode.values();
        CharSequence[] entries = new CharSequence[modes.length];
        CharSequence[] values = new CharSequence[modes.length];
        for (int i = 0; i < modes.length; i++) {
            entries[i] = storyTimeModeLabel(modes[i]);
            values[i] = modes[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.STORY_TIME_MODE.savedValue().name());
        return row;
    }

    /** What the list calls [mode]. */
    static String storyTimeModeLabel(StoryTimeMode mode) {
        switch (mode) {
            case TIME_LEFT:
                return L10n.t("Time left");
            case TIME_POSTED:
                return L10n.t("Time posted");
            default:
                return L10n.t("Date and time");
        }
    }

    /** What a story's header says with [mode], for the row's summary. */
    static String storyTimeModeSummary(StoryTimeMode mode) {
        switch (mode) {
            case TIME_LEFT:
                return L10n.t("How long until the story expires, like 18h 14m left. Older stories show the date and time.");
            case TIME_POSTED:
                return L10n.t("Only the time the story was posted, like 3:45 PM. Stories posted before today show the date and time.");
            default:
                return L10n.t("The date and time the story was posted, like Oct 2, 3:45 PM.");
        }
    }

    /**
     * The size the story rings are drawn at. Like the playback quality's row, its values are the
     * setting's own names and its summary says what the choice does.
     */
    static StoryRingRow storyRingRow(Context context) {
        StoryRingRow row = new StoryRingRow(context);
        row.setKey(Settings.STORY_RING_SCALE.key);
        row.setTitle(L10n.t("Ring size"));
        row.setDialogTitle(L10n.t("Ring size"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        StoryRingSize[] sizes = StoryRingSize.values();
        CharSequence[] entries = new CharSequence[sizes.length];
        CharSequence[] values = new CharSequence[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            entries[i] = storyRingLabel(sizes[i]);
            values[i] = sizes[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.STORY_RING_SCALE.savedValue().name());
        return row;
    }

    /** What the list calls [size]. */
    static String storyRingLabel(StoryRingSize size) {
        switch (size) {
            case SMALLEST:
                return L10n.t("Much smaller");
            case SMALLER:
                return L10n.t("Smaller");
            case LARGER:
                return L10n.t("Larger");
            case LARGEST:
                return L10n.t("Much larger");
            default:
                return L10n.t("Instagram's size");
        }
    }

    /** What the rings look like at [size], for the row's summary, with its share in the phone's own percent format. */
    static String storyRingSummary(StoryRingSize size) {
        if (size == StoryRingSize.INSTAGRAM) return L10n.t("The rings are the size Instagram picks for your screen.");
        return L10n.f("The rings are %1$s of the size Instagram picks for your screen.",
                L10n.isolate(NumberFormat.getPercentInstance().format(size.percent() / 100.0)));
    }

    @Override
    protected void updatePreferenceAvailability(Preference preference, Setting<?> setting) {
        super.updatePreferenceAvailability(preference, setting);
        if (resumingFromOverview && setting == BaseSettings.PAUSED) preference.setEnabled(false);
        if (setting == Settings.ONLY_FOLLOWING) {
            preference.setSummary(setting.isAvailable()
                    ? L10n.t("Removes For you from the choices at the top of Home, so it stays on Following or "
                            + "Favorites. Needs Start Home on Following. Restart Instagram to see the change.")
                    : L10n.t("Turn on Start Home on Following to use this choice."));
        } else if (setting == Settings.LOGO_ON_FOLLOWING) {
            preference.setSummary(setting.isAvailable()
                    ? L10n.t("Keeps Instagram's logo at the top of Home in place of the feed's name. Tap it to switch "
                            + "feeds. Needs Start Home on Following. Restart Instagram to see the change.")
                    : L10n.t("Turn on Start Home on Following to use this choice."));
        } else if (preference instanceof PlaybackQualityRow) {
            ((PlaybackQualityRow) preference).showSummary();
        } else if (preference instanceof TapToPlayScopeRow) {
            ((TapToPlayScopeRow) preference).showSummary();
        } else if (preference instanceof ReelTapChoiceRow) {
            ((ReelTapChoiceRow) preference).showSummary();
        } else if (preference instanceof StoryTimeModeRow) {
            ((StoryTimeModeRow) preference).showSummary();
        } else if (preference instanceof StartTabRow) {
            ((StartTabRow) preference).showSummary();
        } else if (preference instanceof LockDelayRow) {
            ((LockDelayRow) preference).showSummary();
        } else if (preference instanceof StoryRingRow) {
            ((StoryRingRow) preference).showSummary();
        } else if (preference instanceof LikeAnimationRow) {
            ((LikeAnimationRow) preference).showSummary();
        }
    }

    /** The quality rows' and the ring size's summaries are sentences of their own rather than the chosen entry. */
    @Override
    protected void updateListPreferenceSummary(ListPreference listPreference, Setting<?> setting) {
        if (listPreference instanceof NavigationRow) {
            ((NavigationRow) listPreference).showSummary();
        } else if (listPreference instanceof QualityRow) {
            ((QualityRow) listPreference).showSummary();
        } else if (listPreference instanceof PlaybackQualityRow) {
            ((PlaybackQualityRow) listPreference).showSummary();
        } else if (listPreference instanceof TapToPlayScopeRow) {
            ((TapToPlayScopeRow) listPreference).showSummary();
        } else if (listPreference instanceof ReelTapChoiceRow) {
            ((ReelTapChoiceRow) listPreference).showSummary();
        } else if (listPreference instanceof StoryTimeModeRow) {
            ((StoryTimeModeRow) listPreference).showSummary();
        } else if (listPreference instanceof StartTabRow) {
            ((StartTabRow) listPreference).showSummary();
        } else if (listPreference instanceof LockDelayRow) {
            ((LockDelayRow) listPreference).showSummary();
        } else if (listPreference instanceof StoryRingRow) {
            ((StoryRingRow) listPreference).showSummary();
        } else if (listPreference instanceof LikeAnimationRow) {
            ((LikeAnimationRow) listPreference).showSummary();
        } else {
            super.updateListPreferenceSummary(listPreference, setting);
        }
    }

    /**
     * The folder every save goes to. What's typed is cleaned before it's kept, so the row, the
     * setting and the next save all show the one folder name the save will use.
     */
    static FolderRow folderRow(Context context) {
        FolderRow row = new FolderRow(context);
        row.setKey(Settings.SAVE_FOLDER.key);
        row.setTitle(L10n.t("Save folder"));
        row.setDialogTitle(L10n.t("Save folder"));
        row.setDialogMessage(L10n.f("Choose a folder name under Movies and Pictures. Invalid characters become "
                + "underscores. Leave it blank to use the default folder, %1$s.", L10n.isolate(SaveFolder.DEFAULT)));
        row.setPositiveButtonText(L10n.t("Save"));
        // Unset, Android fills in its own Cancel in the activity's language, which can differ
        // from Instagram's, and the dialog read "Speichern" next to "Cancel".
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(L10n.t("Folder name"));
        row.setText(Settings.SAVE_FOLDER.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = SaveFolder.sanitize(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean name in place of what was typed. The store changes, and the shared
            // page reads the setting from the row as it does for any change.
            ((FolderRow) preference).setText(clean);
            Utils.showToastShort(L10n.f("Folder set to %1$s.", L10n.isolate(clean)));
            return false;
        });
        return row;
    }

    /**
     * The name every saved video gets, next to the folder it goes to. What's typed is cleaned the
     * way the folder is, and held to the gallery's naming, so the row, the setting and the next
     * save all show the one template the save will use.
     */
    /**
     * The place Spoof location reports, typed as a latitude and a longitude. Anything else is
     * refused with a toast and the place stays as it was.
     */
    static PlaceRow placeRow(Context context) {
        PlaceRow row = new PlaceRow(context);
        row.setKey(Settings.SPOOF_LOCATION_PLACE.key);
        row.setTitle(L10n.t("Place"));
        row.setDialogTitle(L10n.t("Place"));
        row.setDialogMessage(L10n.t("The latitude and the longitude in degrees, with a comma between them, "
                + "like 40.758, -73.9855. North and east are positive, south and west negative. A map app shows "
                + "both when you press and hold a spot."));
        row.setPositiveButtonText(L10n.t("Save"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint("40.758, -73.9855");
        row.setText(Settings.SPOOF_LOCATION_PLACE.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String text = typed == null ? "" : typed.toString().trim();
            if (text.isEmpty() || SpoofLocation.parse(text) != null) return true;
            Utils.showToastShort(L10n.t("That isn't a place. Type a latitude and a longitude with a comma between them."));
            return false;
        });
        return row;
    }

    /**
     * The domain Sanitize sharing links moves links to instagram.com to. What's typed is saved
     * bare (no scheme, no closing slash), and what isn't a domain is refused with a toast.
     */
    static SharingDomainRow sharingDomainRow(Context context) {
        SharingDomainRow row = new SharingDomainRow(context);
        row.setKey(Settings.SHARING_DOMAIN.key);
        row.setTitle(L10n.t("Sharing domain"));
        row.setDialogTitle(L10n.t("Sharing domain"));
        row.setDialogMessage(L10n.t("Links to instagram.com that you copy or share go out on this domain "
                + "instead, for a site that shows Instagram posts and reels in chat apps. Type the domain alone, "
                + "like example.com. Leave it blank to keep instagram.com."));
        row.setPositiveButtonText(L10n.t("Save"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint("example.com");
        row.setText(Settings.SHARING_DOMAIN.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String domain = SharingDomain.normalize(raw);
            if (domain == null) {
                Utils.showToastShort(L10n.t("That isn't a domain. Type one like example.com, or leave it blank."));
                return false;
            }
            if (domain.equals(raw)) return true;
            // Keeps the bare domain in place of what was typed, as the file name row does.
            ((SharingDomainRow) preference).setText(domain);
            return false;
        });
        return row;
    }

    /** What the Sharing domain row says: where links to instagram.com go out. */
    static String sharingDomainSummary(String text) {
        String domain = SharingDomain.normalize(text);
        return domain == null || domain.isEmpty()
                ? L10n.t("Links keep instagram.com.")
                : L10n.f("Links to instagram.com go out on %1$s.", L10n.isolate(domain));
    }

    /**
     * Which of Instagram's like animations the heart plays. Its values are the animations' own names,
     * read from Instagram as the screen is built, and its summary says which one plays.
     */
    static LikeAnimationRow likeAnimationRow(Context context) {
        return likeAnimationRow(context, LikeAnimation.names());
    }

    static LikeAnimationRow likeAnimationRow(Context context, List<String> names) {
        LikeAnimationRow row = new LikeAnimationRow(context);
        row.setKey(Settings.LIKE_ANIMATION.key);
        row.setTitle(L10n.t("Like animation"));
        row.setDialogTitle(L10n.t("Like animation"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        CharSequence[] entries = new CharSequence[names.size()];
        CharSequence[] values = new CharSequence[names.size()];
        for (int i = 0; i < names.size(); i++) {
            entries[i] = LikeAnimation.label(names.get(i));
            values[i] = names.get(i);
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.LIKE_ANIMATION.savedValue());
        return row;
    }

    /** What the like animation's row says: the animation the heart plays, or that none is picked. */
    static String likeAnimationSummary(@Nullable String name, @Nullable CharSequence[] names) {
        boolean known = false;
        if (name != null && names != null) {
            for (CharSequence candidate : names) known |= name.contentEquals(candidate);
        }
        return known
                ? L10n.f("The heart plays %1$s when you double tap a post.", L10n.isolate(LikeAnimation.label(name)))
                : L10n.t("Pick an animation. Until you do, the heart stays Instagram's.");
    }

    /** What the place row says: the place, or that there's none and what a fix answers then. */
    static String placeSummary(String text) {
        double[] place = SpoofLocation.parse(text);
        return place == null
                ? L10n.t("No place set. Until there is one, Instagram is told 0, 0 while Spoof location is on.")
                : L10n.f("Tells Instagram your phone is at %1$s.", L10n.isolate(SpoofLocation.describe(place)));
    }

    static FileNameRow fileNameRow(Context context) {
        FileNameRow row = new FileNameRow(context);
        row.setKey(Settings.FILENAME_TEMPLATE.key);
        row.setTitle(L10n.t("Video file name"));
        row.setDialogTitle(L10n.t("Video file name"));
        row.setDialogMessage(L10n.f("%1$s becomes the date and time of the save, %2$s the video's number on "
                        + "Instagram, %3$s who posted it and %4$s the day it was posted. What a save doesn't know is "
                        + "left out, and a name with none of these gets the date added. When the name is already in "
                        + "the folder, the time of the save goes on the end. Invalid characters become underscores. "
                        + "Leave it blank to use the default, %5$s.",
                L10n.isolate(FileNameTemplate.DATE), L10n.isolate(FileNameTemplate.VIDEO_ID),
                L10n.isolate(FileNameTemplate.OWNER), L10n.isolate(FileNameTemplate.POSTED),
                L10n.isolate(FileNameTemplate.DEFAULT)));
        row.setPositiveButtonText(L10n.t("Save"));
        // Android's own Cancel follows the activity's language, as the folder row's did.
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setHint(L10n.t("File name"));
        row.setText(Settings.FILENAME_TEMPLATE.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String clean = FileNameTemplate.sanitize(raw);
            if (clean.equals(raw)) return true;
            // Keeps the clean template in place of what was typed, as the folder row does.
            ((FileNameRow) preference).setText(clean);
            Utils.showToastShort(L10n.f("File name set to %1$s.", L10n.isolate(clean)));
            return false;
        });
        return row;
    }

    /**
     * "Videos are named IG_VID_{date}. Photos are always named IG_IMG_ followed by the date and
     * time." for [template]. The photo prefix is HushGram's, not Instagram's, so it isn't called
     * Instagram's own naming.
     */
    static String fileNameSummary(String template) {
        return L10n.f("Videos are named %1$s. Photos are always named %2$s followed by the date and time.",
                L10n.isolate(template), L10n.isolate(FileNameTemplate.PHOTO_PREFIX));
    }

    /** "Videos go to Movies/Instagram and photos to Pictures/Instagram." for the folder [leaf]. */
    static String folderSummary(String leaf) {
        String videos = Environment.DIRECTORY_MOVIES + "/" + leaf;
        String photos = Environment.DIRECTORY_PICTURES + "/" + leaf;
        return L10n.f("Videos go to %1$s and photos to %2$s.", L10n.isolate(videos), L10n.isolate(photos));
    }

    private static Preference mark(Preference row, String icon) {
        row.setIcon(SettingsIcons.icon(row.getContext(), icon, ScreenColors.DEFAULT.heading));
        return row;
    }

    private static Preference info(Context context, String title, String summary) {
        Preference preference = new Row(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        preference.setPersistent(false);
        return preference;
    }

    /**
     * A row that opens one of Instagram's own screens over the current host. Settings close only
     * once it opened; otherwise they stay, and the row keeps the whole reason, since Android cuts
     * a toast to two lines.
     */
    private Preference nativeScreenRow(Context context, String key, String title, String summary,
                                       java.util.function.Predicate<Activity> open, String unavailable) {
        Preference preference = new Row(context);
        preference.setKey(key);
        preference.setPersistent(false);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setOnPreferenceClickListener(row -> {
            if (open.test(getActivity())) {
                SettingsEntry.onClosedByUser();
                if (getParentFragment() instanceof DialogFragment) {
                    ((DialogFragment) getParentFragment()).dismissAllowingStateLoss();
                }
            } else {
                row.setSummary(unavailable);
                Utils.showToastShort(unavailable);
            }
            return true;
        });
        return preference;
    }

    /**
     * Shows a dialog over this page and remembers it, so it goes with the page. Nothing shows once
     * the view is gone or the activity is finishing. Answers the dialog, or null when none showed.
     */
    @Nullable
    private AlertDialog show(AlertDialog.Builder builder) {
        Activity activity = getActivity();
        if (getView() == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        AlertDialog dialog = builder.show();
        ScreenColors.dialog(dialog);
        shownDialogs.add(dialog);
        dialog.setOnDismissListener(shownDialogs::remove);
        return dialog;
    }

    /**
     * Lets a row's title and summary wrap to as many lines as they need. Android draws a row's
     * title on one line and cuts its summary at ten.
     *
     * <p>Every line starts at the row's start edge. In a right-to-left layout, English text is a
     * left-to-right paragraph, which the row's start gravity sets flush left inside a text block
     * that sits on the right, so a wrapped summary looked left-aligned beside one-line titles on
     * the right. Left to right, the start edge is the left, as before.
     */
    static void showAllText(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
            title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setMaxLines(Integer.MAX_VALUE);
            summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        }
    }

    /** A section title, which a screen reader announces as a heading. */
    static final class Heading extends PreferenceCategory {
        Heading(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setAccessibilityHeading(true);
            showAllText(view);
            ScreenColors.heading(view);
        }
    }

    /** A row of text, which a screen reader calls a button when a tap does something. */
    static final class Row extends Preference implements ImmediateAction {
        /** Set on a row whose tap does what it says at once, which then goes without a chevron. */
        boolean actsAtOnce;

        Row(Context context) {
            super(context);
        }

        @Override
        public boolean actsOnTap() {
            return actsAtOnce;
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(isSelectable() ? new RowSemantics(this, Button.class) : null);
            view.setAccessibilityLiveRegion("hushgram_export_configuration".equals(getKey())
                    ? View.ACCESSIBILITY_LIVE_REGION_POLITE : View.ACCESSIBILITY_LIVE_REGION_NONE);
        }
    }

    /** A switch row, which a screen reader calls a switch and reads as on or off. */
    static final class Toggle extends SwitchPreference {
        Toggle(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Switch.class));
        }
    }

    /**
     * A running save: what it is, what it's doing, how far it has got, and a Cancel button. Its text
     * changes in place: a row rebuilt under a finger loses the tap on its button.
     */
    static final class SaveRow extends Preference {
        /** Before every setting of the section, oldest save first. */
        private static final int FIRST = Integer.MIN_VALUE / 2;

        final int id;
        private final String cancelDescription;
        private String status;
        @Nullable
        private View bound;

        SaveRow(Context context, SaveControl.Running save) {
            super(context);
            id = save.id;
            cancelDescription = SaveControl.cancelDescription(save);
            status = SaveControl.status(save);
            setKey("running_save_" + save.id);
            setPersistent(false);
            setSelectable(false);
            setOrder(FIRST + save.id);
            setTitle(SaveControl.title(save));
        }

        @Override
        public CharSequence getSummary() {
            return status;
        }

        void show(SaveControl.Running save) {
            String next = SaveControl.status(save);
            if (next.equals(status)) return;
            status = next;
            TextView summary = bound == null ? null : bound.findViewById(android.R.id.summary);
            if (summary != null) summary.setText(next);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            bound = view;
            showAllText(view);
            ScreenColors.row(view, this);
            android.view.ViewGroup frame = view.findViewById(android.R.id.widget_frame);
            if (frame == null) return;
            frame.removeAllViews();
            Button cancel = new Button(getContext());
            cancel.setText(L10n.t("Cancel"));
            // Two saves can be listed at once, so the button says whose it is.
            cancel.setContentDescription(cancelDescription);
            cancel.setAllCaps(false);
            cancel.setTextSize(14);
            ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
            cancel.setTextColor(colors.heading);
            cancel.setBackgroundColor(Color.TRANSPARENT);
            int touch = Math.round(48 * view.getResources().getDisplayMetrics().density);
            cancel.setMinWidth(touch);
            cancel.setMinimumWidth(touch);
            cancel.setMinHeight(touch);
            cancel.setMinimumHeight(touch);
            cancel.setPadding(touch / 4, 0, touch / 4, 0);
            cancel.setOnClickListener(ignored -> {
                cancel.setEnabled(false);
                SaveControl.cancel(id);
            });
            frame.addView(cancel, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            frame.setVisibility(View.VISIBLE);
        }
    }

    /**
     * An edit dialog shrinks to fit above the keyboard instead of going under it. Android's own
     * EditTextPreference only asks for the keyboard, so at a large font size a long message pushed
     * the dialog's Save and Cancel behind the keyboard, where nobody could reach them. The dialog's
     * keyboard state is kept; only how it adjusts changes.
     */
    static void fitAboveKeyboard(Dialog dialog) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        int mode = window.getAttributes().softInputMode;
        window.setSoftInputMode((mode & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    /**
     * The save folder's row. Its summary follows its text, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class FolderRow extends EditTextPreference {
        FolderRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(folderSummary(SaveFolder.sanitize(text)));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * The video file name's row. Its summary follows its text, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import. Its dialog shows what the typed
     * template names a video, as it's typed.
     */
    static final class SharingDomainRow extends EditTextPreference {
        SharingDomainRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(sharingDomainSummary(text));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colors, as the place's does. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /** The like animation's row. Its summary follows its value, and it answers it itself, as the ring size's does. */
    static final class LikeAnimationRow extends ListPreference {
        @Nullable
        private String summary;

        LikeAnimationRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            summary = Settings.LIKE_ANIMATION.isAvailable()
                    ? likeAnimationSummary(getValue(), getEntryValues())
                    : L10n.t("Turn on Change the like animation to use this choice.");
            setSummary(summary);
        }

        @Override
        public CharSequence getSummary() {
            return summary != null ? summary : super.getSummary();
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    static final class PlaceRow extends EditTextPreference {
        PlaceRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(placeSummary(text));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colors, as the file name's does. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    static final class FileNameRow extends EditTextPreference {
        @Nullable private TextView preview;
        private java.util.Date previewDate;

        FileNameRow(Context context) {
            super(context);
            getEditText().addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (preview != null) preview.setText(previewName(text.toString(), previewDate));
                }
                @Override public void afterTextChanged(android.text.Editable text) { }
            });
        }

        static String previewName(String text, java.util.Date when) {
            return FileNameTemplate.videoName(FileNameTemplate.sanitize(text), when, "123456") + ".mp4";
        }

        @Override protected View onCreateDialogView() {
            Context context = getContext();
            ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
            int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
            android.widget.LinearLayout content = new android.widget.LinearLayout(context);
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            content.setPadding(pad, pad / 2, pad, pad);
            EditText field = getEditText();
            if (field.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) field.getParent()).removeView(field);
            content.addView(field, new android.widget.LinearLayout.LayoutParams(-1, -2));
            TextView label = new TextView(context);
            label.setText(L10n.t("Example without post details"));
            label.setTextSize(12);
            label.setTextColor(colors.summary);
            label.setPadding(0, pad, 0, pad / 4);
            content.addView(label);
            previewDate = new java.util.Date();
            preview = new TextView(context);
            preview.setTextSize(14);
            preview.setTextColor(colors.title);
            preview.setText(previewName(getText(), previewDate));
            content.addView(preview);
            TextView help = new TextView(context);
            help.setId(android.R.id.message);
            help.setText(getDialogMessage());
            help.setTextSize(14);
            help.setTextColor(colors.summary);
            help.setPadding(0, pad, 0, 0);
            content.addView(help);
            ScrollView scroll = new ScrollView(context);
            scroll.addView(content);
            return scroll;
        }

        @Override protected void onBindDialogView(View view) {
            // The input is already in the custom scroll container, before its explanatory text.
            getEditText().setText(getText());
        }

        @Override protected void onDialogClosed(boolean positive) {
            super.onDialogClosed(positive);
            preview = null;
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(fileNameSummary(FileNameTemplate.sanitize(text)));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its edit dialog takes the screen's colours, as the folder's does. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
            fitAboveKeyboard(getDialog());
        }
    }

    /**
     * The download quality's row. Its summary follows its value, whoever sets it: the person, the
     * shared page syncing it from the setting, or an import.
     */
    static final class NavigationRow extends ListPreference {
        private String summary;
        /** The row for the quality picker's tab (#93) rather than the one that opens HushGram. */
        private final boolean forQuality;
        NavigationRow(Context context) { this(context, false); }
        NavigationRow(Context context, boolean forQuality) {
            super(context);
            this.forQuality = forQuality;
        }
        /** The tabs take the choice once the setting has it, after this change's own sync (#82). */
        @Override public void setValue(String value) {
            super.setValue(value);
            showSummary();
            Utils.runOnMainThread(NavigationSettings::applyChoice);
        }
        void showSummary() {
            NavigationTarget target = NavigationTarget.OFF;
            for (NavigationTarget candidate : NavigationTarget.values()) {
                if (candidate.name().equals(getValue())) target = candidate;
            }
            if (forQuality) {
                summary = target == NavigationTarget.OFF
                        ? L10n.t("Tab long presses keep Instagram's own action. Choose one to pick the playback quality instead.")
                        : L10n.f("Long-press %1$s to pick the playback quality instead of that tab's usual action. "
                                + "Normal taps and other tabs stay the same. Needs Default playback quality on. "
                                + "If the same tab opens HushGram, that comes first.", navigationLabel(target));
                setSummary(summary);
                return;
            }
            summary = target == NavigationTarget.OFF
                    ? L10n.t("Tab long presses keep Instagram's own action. Choose one to open HushGram instead.")
                    : L10n.f("Long-press %1$s to open HushGram instead of that tab's usual action. "
                            + "Normal taps and other tabs stay the same. Only tabs your account shows can be used.",
                            navigationLabel(target));
            setSummary(summary);
        }
        @Override public CharSequence getSummary() { return summary != null ? summary : super.getSummary(); }
        @Override protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
        @Override protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    static final class QualityRow extends ListPreference {
        QualityRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            DownloadQuality quality = DownloadQuality.BEST;
            for (DownloadQuality candidate : DownloadQuality.values()) {
                if (candidate.name().equals(getValue())) quality = candidate;
            }
            setSummary(qualitySummary(quality));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The playback quality's row. Its summary follows its value, whoever sets it: the person or the
     * shared page syncing it from the setting.
     */
    static final class PlaybackQualityRow extends ListPreference {
        PlaybackQualityRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            PlaybackQuality quality = PlaybackQuality.AUTO;
            for (PlaybackQuality candidate : PlaybackQuality.values()) {
                if (candidate.name().equals(getValue())) quality = candidate;
            }
            setSummary(Settings.PLAYBACK_QUALITY.isAvailable()
                    ? playbackQualitySummary(quality)
                    : L10n.t("Turn on Default playback quality to use this choice."));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /** Tap to play's choice of where. Its summary follows its value, as the playback quality's does. */
    static final class TapToPlayScopeRow extends ListPreference {
        TapToPlayScopeRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            TapToPlayScope scope = TapToPlayScope.EVERYWHERE;
            for (TapToPlayScope candidate : TapToPlayScope.values()) {
                if (candidate.name().equals(getValue())) scope = candidate;
            }
            setSummary(Settings.TAP_TO_PLAY_SCOPE.isAvailable()
                    ? tapToPlayScopeSummary(scope)
                    : L10n.t("Turn on Tap to play to use this choice."));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /** The choice of what a tap on a reel does. Its summary follows its value, as Tap to play's choice does. */
    static final class ReelTapChoiceRow extends ListPreference {
        ReelTapChoiceRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            ReelTapChoice choice = ReelTapChoice.DEFAULT;
            for (ReelTapChoice candidate : ReelTapChoice.values()) {
                if (candidate.name().equals(getValue())) choice = candidate;
            }
            setSummary(reelTapChoiceSummary(choice));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /** Show a story's exact time's choice of how. Its summary follows its value, as Tap to play's choice does. */
    static final class LockDelayRow extends ListPreference {
        LockDelayRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            LockDelay delay = LockDelay.RIGHT_AWAY;
            for (LockDelay candidate : LockDelay.values()) {
                if (candidate.name().equals(getValue())) delay = candidate;
            }
            setSummary(delay == LockDelay.RIGHT_AWAY
                    ? L10n.t("Instagram locks as soon as you leave it or the screen turns off.")
                    : L10n.f("Instagram locks once you've been away from it for %1$s.", lockDelayAway(delay)));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /** How long away [delay] waits, for the row's summary. */
    static String lockDelayAway(LockDelay delay) {
        switch (delay) {
            case ONE_MINUTE: return L10n.t("1 minute");
            case FIVE_MINUTES: return L10n.t("5 minutes");
            case FIFTEEN_MINUTES: return L10n.t("15 minutes");
            default: return L10n.t("1 hour");
        }
    }

    static final class HapticStyleRow extends ListPreference {
        HapticStyleRow(Context context) {
            super(context);
        }

        /** The summary is the tick chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            HapticStyle style = HapticStyle.SYSTEM;
            for (HapticStyle candidate : HapticStyle.values()) {
                if (candidate.name().equals(value)) style = candidate;
            }
            setSummary(hapticStyleLabel(style));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    static final class StartTabRow extends ListPreference {
        StartTabRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        /** The summary is the sentence for the tab chosen, not the entry. */
        void showSummary() {
            StartTab tab = StartTab.HOME;
            for (StartTab candidate : StartTab.values()) {
                if (candidate.name().equals(getValue())) tab = candidate;
            }
            setSummary(startTabSummary(tab));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    static final class StoryTimeModeRow extends ListPreference {
        StoryTimeModeRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            StoryTimeMode mode = StoryTimeMode.DATE_AND_TIME;
            for (StoryTimeMode candidate : StoryTimeMode.values()) {
                if (candidate.name().equals(getValue())) mode = candidate;
            }
            setSummary(Settings.STORY_TIME_MODE.isAvailable()
                    ? storyTimeModeSummary(mode)
                    : L10n.t("Turn on Show a story's exact time to use this choice."));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    /**
     * The story ring size's row. Its summary follows its value, as the playback quality's does, and
     * it answers that summary itself: ListPreference runs its summary through String.format, and a
     * share such as "130%" is no format.
     */
    static final class StoryRingRow extends ListPreference {
        @Nullable
        private String summary;

        StoryRingRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            StoryRingSize size = StoryRingSize.INSTAGRAM;
            for (StoryRingSize candidate : StoryRingSize.values()) {
                if (candidate.name().equals(getValue())) size = candidate;
            }
            summary = Settings.STORY_RING_SCALE.isAvailable()
                    ? storyRingSummary(size)
                    : L10n.t("Turn on Story ring size to use this choice.");
            setSummary(summary);
        }

        @Override
        public CharSequence getSummary() {
            return summary != null ? summary : super.getSummary();
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        /** Its list takes the screen's colours, as the other rows' dialogs do. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) ScreenColors.dialog((AlertDialog) getDialog());
        }
    }

    final class ExportRow extends ExportDiagnosticReportPreference {
        ExportRow(Context context) {
            super(context);
            Preference.OnPreferenceClickListener open = getOnPreferenceClickListener();
            setOnPreferenceClickListener(row -> {
                Activity activity = getActivity();
                if (row.isEnabled() && HushgramPreferenceFragment.this.getView() != null && activity != null
                        && !activity.isFinishing() && !activity.isDestroyed()) {
                    open.onPreferenceClick(row);
                }
                return true;
            });
        }

        /** The two choices as cards, each saying what it does under its name. */
        @Override
        protected ListAdapter choices(Context dialogContext) {
            return new ChoiceCards(ScreenColors.DEFAULT,
                    new CharSequence[]{L10n.t(getContext(), "Copy quick report"),
                            L10n.t(getContext(), "Save full report")},
                    new CharSequence[]{L10n.t(getContext(), "Copy a short report to the clipboard."),
                            L10n.f(getContext(), "Save the full report in %1$s.",
                                    L10n.isolate(LogBufferManager.reportFolder(getContext())))});
        }

        /** The cards bring their own spacing and ripple, so the list draws no divider of its own. */
        @Override
        protected void onDialogCreated(AlertDialog dialog) {
            ListView list = dialog.getListView();
            if (list != null) {
                list.setDivider(null);
                list.setSelector(new ColorDrawable(Color.TRANSPARENT));
            }
            ScreenColors.dialog(dialog);
            shownDialogs.add(dialog);
            dialog.setOnDismissListener(shownDialogs::remove);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
            view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
    }

    static final class ClearRow extends ClearLogBufferPreference {
        ClearRow(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }
    }

    /**
     * What a screen reader hears about a row a tap acts on. The list gives such a row its place and
     * a click action and no role, and the switch drawn in a switch row can't take focus, so on its
     * own a switch row said neither that it was a switch nor whether it was on.
     */
    static final class RowSemantics extends View.AccessibilityDelegate {
        private final Preference preference;
        private final Class<? extends View> role;

        RowSemantics(Preference preference, Class<? extends View> role) {
            this.preference = preference;
            this.role = role;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            AbsListView list = listOf(host);
            int position = positionOf(preference, host, list);
            if (position != AdapterView.INVALID_POSITION) {
                list.onInitializeAccessibilityNodeInfoForItem(host, position, info);
            }
            info.setClassName(role.getName());
            // A Switch role reads its own label instead of aggregating descendant TextViews.
            CharSequence title = preference.getTitle(), summary = preference.getSummary();
            info.setContentDescription(TextUtils.isEmpty(summary) ? title : TextUtils.isEmpty(title)
                    ? summary : TextUtils.concat(title, "\n", summary));
            if (preference instanceof TwoStatePreference) {
                info.setCheckable(true);
                info.setChecked(((TwoStatePreference) preference).isChecked());
            }
            boolean enabled = position != AdapterView.INVALID_POSITION
                    && enabledViewTree(host) && preference.isEnabled();
            boolean clickable = enabled && preference.isSelectable();
            info.setEnabled(enabled);
            info.setClickable(clickable);
            // AbsListView may have added a click for a disabled or no longer bound item.
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            if (clickable) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            }
        }

        @Override
        public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
            super.onInitializeAccessibilityEvent(host, event);
            event.setClassName(role.getName());
            if (preference instanceof TwoStatePreference) {
                event.setChecked(((TwoStatePreference) preference).isChecked());
            }
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                AbsListView list = listOf(host);
                int position = positionOf(preference, host, list);
                if (position == AdapterView.INVALID_POSITION || !enabledViewTree(host)
                        || !preference.isEnabled() || !preference.isSelectable()) return false;
                return list.performItemClick(host, position, list.getItemIdAtPosition(position));
            }
            return super.performAccessibilityAction(host, action, arguments);
        }

        /** A delayed service action must not target the replacement at this row's old position. */
        private static int positionOf(Preference preference, View host, @Nullable AbsListView list) {
            if (list == null || preference.getParent() == null
                    || !host.isAttachedToWindow() || !host.isShown()) {
                return AdapterView.INVALID_POSITION;
            }
            Preference root = preference;
            while (root.getParent() != null) root = root.getParent();
            if (!(root instanceof PreferenceScreen) || root.getPreferenceManager() == null
                    || root.getPreferenceManager().findPreference(SCREEN_KEY) != root
                    || ((PreferenceScreen) root).getRootAdapter() != list.getAdapter()) {
                return AdapterView.INVALID_POSITION;
            }
            int position = list.getPositionForView(host);
            return position >= 0 && position < list.getCount()
                    && list.getItemAtPosition(position) == preference
                    ? position : AdapterView.INVALID_POSITION;
        }

        private static boolean enabledViewTree(View host) {
            for (View view = host; view != null;
                    view = view.getParent() instanceof View ? (View) view.getParent() : null) {
                if (!view.isEnabled()) return false;
            }
            return true;
        }

        @Nullable
        private static AbsListView listOf(View row) {
            return row.getParent() instanceof AbsListView ? (AbsListView) row.getParent() : null;
        }
    }

    /**
     * The notice itself stays in English, as the licence texts it carries are: a translation of
     * the GPL is not the licence.
     */
    private void showNotice(Context context) {
        TextView text = new TextView(context);
        // NOTICE is hard-wrapped for a source file. Reflow prose on a narrow screen while keeping
        // blank lines, headings, lists and the generated notice itself intact.
        String notice = LicenseNotice.TEXT.replaceAll("(?<=[\\p{L}.,;])\\n(?=\\p{L})", " ")
                .replaceAll("(?m)^(  .+?) {2,}(https?://[^\\n]+)$", "$1\n$2\n");
        text.setText(notice);
        text.setTextIsSelectable(true);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        text.setLineSpacing(Math.round(2 * context.getResources().getDisplayMetrics().density), 1.04f);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(text);
        text.setTextColor(ScreenColors.DEFAULT.summary);
        text.setLinkTextColor(ScreenColors.DEFAULT.heading);
        Linkify.addLinks(text, Linkify.WEB_URLS);
        show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Licenses"))
                .setView(scroll)
                .setPositiveButton(L10n.t("OK"), null));
    }

    @Override
    protected CharSequence initializationErrorTitle(@Nullable Context context) {
        return L10n.t(context, "HushGram settings couldn't open");
    }
}
