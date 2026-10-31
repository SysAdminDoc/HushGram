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
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.biometrics.BiometricManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.DialogPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.DocumentsContract;
import android.preference.TwoStatePreference;
import android.text.Layout;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.text.NumberFormat;
import java.lang.ref.WeakReference;
import java.text.Normalizer;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.direct.ChatAccounts;
import app.hushgram.extension.instagram.direct.ChatLocks;
import app.hushgram.extension.instagram.direct.HiddenChats;
import app.hushgram.extension.instagram.direct.PinnedChats;
import app.hushgram.extension.instagram.direct.LockDelay;
import app.hushgram.extension.instagram.direct.MessageBox;
import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.instagram.direct.PinLock;
import app.hushgram.extension.instagram.direct.UnlockWith;
import app.hushgram.extension.instagram.download.DownloadQuality;
import app.hushgram.extension.instagram.media.PlaybackQuality;
import app.hushgram.extension.instagram.media.TapToPlayScope;
import app.hushgram.extension.instagram.media.ResumePlayback;
import app.hushgram.extension.instagram.feed.HiddenAccounts;
import app.hushgram.extension.instagram.feed.AmbientSpeed;
import app.hushgram.extension.instagram.feed.AmbientSpread;
import app.hushgram.extension.instagram.feed.CornerSize;
import app.hushgram.extension.instagram.feed.LikeAnimation;
import app.hushgram.extension.instagram.misc.MediaCache;
import app.hushgram.extension.instagram.misc.NotificationGroups;
import app.hushgram.extension.instagram.misc.SettingsRows;
import app.hushgram.extension.instagram.misc.SpoofLocation;
import app.hushgram.extension.instagram.share.ShareSheetActions;
import app.hushgram.extension.instagram.share.SharingDomain;
import app.hushgram.extension.instagram.stories.StoryBarThickness;
import app.hushgram.extension.instagram.stories.StoryEmoji;
import app.hushgram.extension.instagram.stories.StoryFonts;
import app.hushgram.extension.instagram.stories.StoryRingColor;
import app.hushgram.extension.instagram.stories.StoryRingSize;
import app.hushgram.extension.instagram.stories.StoryTimeMode;
import app.hushgram.extension.instagram.stories.TrayBadge;
import app.hushgram.extension.instagram.profile.FollowLabelPlace;
import app.hushgram.extension.instagram.profile.ProfileTabs;
import app.hushgram.extension.instagram.misc.GlassCorners;
import app.hushgram.extension.instagram.misc.GlassHeight;
import app.hushgram.extension.instagram.misc.GlassLift;
import app.hushgram.extension.instagram.misc.GlassReelsHide;
import app.hushgram.extension.instagram.misc.AccentHue;
import app.hushgram.extension.instagram.misc.AppProxy;
import app.hushgram.extension.instagram.misc.MaterialYou;
import app.hushgram.extension.instagram.misc.AppIconChoice;
import app.hushgram.extension.instagram.misc.EmojiChoice;
import app.hushgram.extension.instagram.misc.EmojiFonts;
import app.hushgram.extension.instagram.misc.FontChoice;
import app.hushgram.extension.instagram.misc.Fonts;
import app.hushgram.extension.instagram.misc.HapticStyle;
import app.hushgram.extension.instagram.reels.BurnInMode;
import app.hushgram.extension.instagram.reels.BurnInWait;
import app.hushgram.extension.instagram.reels.CaptionWordReels;
import app.hushgram.extension.instagram.reels.HiddenBars;
import app.hushgram.extension.instagram.reels.HiddenSounds;
import app.hushgram.extension.instagram.reels.ReelTapChoice;
import app.hushgram.extension.instagram.reels.ScrollBreakTime;
import app.hushgram.extension.instagram.reels.StartTab;
import app.hushgram.extension.instagram.reels.TabBarLayoutChoice;
import app.hushgram.extension.instagram.reels.TabOrder;
import app.hushgram.extension.instagram.download.DownloadHistory;
import app.hushgram.extension.instagram.profile.Unfollowers;
import app.hushgram.extension.instagram.download.FileNameTemplate;
import app.hushgram.extension.instagram.download.PostDetails;
import app.hushgram.extension.instagram.download.HoldFingers;
import app.hushgram.extension.instagram.download.HoldTime;
import app.hushgram.extension.instagram.download.ReelSoundButton;
import app.hushgram.extension.instagram.download.SaveControl;
import app.hushgram.extension.instagram.download.SaveLeftovers;
import app.hushgram.extension.instagram.download.SaveFolder;
import app.hushgram.extension.instagram.download.SaveTree;
import app.hushgram.extension.instagram.download.ViewingHistory;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.BooleanSetting;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.IntegerSetting;
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
    /** The row right above Support HushGram that opens a filled-in bug report on GitHub. */
    static final String REPORT_ROW_KEY = "action_report_problem";
    /** Lock these settings (#301), right under Pause HushGram. */
    static final String LOCK_ROW_KEY = "hushgram_settings_lock_row";
    /** The English of the row listing what Pause can't reach, and its key in {@link L10n}. */
    static final String STAYS_WHILE_PAUSED = "Stays in while paused";
    /** The Before you sign in notice's key, which finds it on the screen. */
    static final String SIGN_IN_NOTICE_KEY = "hushgram_sign_in_notice";
    static final String CLEAR_MEDIA_CACHE_NOW = "hushgram_clear_media_cache_now";
    static final String CLEAR_DOWNLOAD_HISTORY = "hushgram_clear_download_history";
    /** The row listing who left your Followers list, and the row that forgets them. */
    static final String UNFOLLOWERS_ROW = "hushgram_unfollowers_row";
    static final String VIEWING_HISTORY_ROW = "hushgram_viewing_history_row";
    static final String CLEAR_VIEWING_HISTORY = "hushgram_clear_viewing_history";
    static final String CLEAR_UNFOLLOWERS = "hushgram_clear_unfollowers";
    /** The row that lists the chats locked one at a time. */
    static final String LOCKED_CHATS_ROW = "hushgram_locked_chats_row";
    /** The row that lists the chats hidden one at a time. */
    static final String HIDDEN_CHATS_ROW = "hushgram_hidden_chats_row";
    /** The row that lists the chats pinned to the top of the inbox. */
    static final String PINNED_CHATS_ROW = "hushgram_pinned_chats_row";
    /** The row that sets or changes the HushGram PIN. */
    static final String CHANGE_PIN_ROW = "hushgram_change_pin_row";
    static final String HIDDEN_ACCOUNTS_ROW = "hushgram_hidden_accounts_row";
    static final String HIDDEN_SOUNDS_ROW = "hushgram_hidden_sounds_row";
    /** The row that lists the font files added for story text. */
    static final String STORY_FONTS_ROW = "hushgram_story_fonts_row";
    static final String GHOST_PICKS_ROW = "hushgram_ghost_picks_row";
    private static final String SCREEN_KEY = "hushgram_settings_root";
    /** Stable category keys. Keep the old prefix for saved restart and recreation state. */
    static final String CATEGORY_ROW_KEY = "hushgram_category_page_";

    /** The first row, which says whether HushGram runs now and whether the next start changes that. */
    @Nullable
    private Preference statusCard;
    private boolean resumingFromOverview;

    @Nullable Row clearPositions;
    private boolean changingPositions;
    private final Handler undoRefresh = new Handler(Looper.getMainLooper());
    private final Runnable refreshUndo = () -> {
        showClearPositions();
        showConfiguration();
    };
    private static final int EXPORT_CONFIGURATION = 0x4847;
    private static final int IMPORT_CONFIGURATION = 0x4848;
    private static final int PICK_FONT = 0x484d;
    private static final int PICK_STORY_FONT = 0x484e;
    private static final int PICK_STORY_EMOJI = 0x484f;
    private static final int PICK_SAVE_FOLDER = 0x4850;
    private static final int PICK_EMOJI = 0x4851;
    /** Framework fragment callbacks run on the main thread. Never reuse a code across pages. */
    private static int documentSequence = PICK_FONT;
    private int documentRequest;
    private int documentCode;
    private long fontRequestRevision = -1;
    @Nullable private String configurationExportToken;
    private boolean changingConfiguration;
    /** Last operation's receipt lasts for this process, including closing/reopening settings. */
    @Nullable static volatile String importFeedback;
    @Nullable Row exportConfiguration;
    @Nullable ExportRow exportDiagnostics;
    private final Runnable exportChanges = this::showConfiguration;
    private final Runnable fontChanges = () -> {
        if (getView() == null || !isAdded()) return;
        updateUIToSettingValues();
        showConfiguration();
        showRestart();
    };
    @Nullable Row importConfiguration;
    @Nullable Row undoConfiguration;
    /** Lock these settings (#301), and the rows it greyed out while the settings are locked. */
    @Nullable Row settingsLock;
    private final Set<Preference> lockedRows = new HashSet<>();
    private boolean lockHolds, showingLock;
    private final Handler lockRefresh = new Handler(Looper.getMainLooper());
    private final Runnable lockTick = this::showLock;
    /** The About section, whose rows stay open while the settings are locked. */
    @Nullable PreferenceCategory about;
    /** Ghost mode's switch, and the switches it turns, both set once the page is built. */
    @Nullable SwitchPreference ghostMode;
    List<BooleanSetting> ghostSwitches = new ArrayList<>();
    /** The row under Ghost mode that picks which switches it turns (#251), and each switch's title by key. */
    @Nullable Row ghostPicks;
    private final Map<String, CharSequence> ghostNames = new HashMap<>();
    /** The row choosing the tab whose long press turns Ghost mode (#255). */
    @Nullable NavigationRow ghostTab;

    private String searchQuery = "";
    @Nullable private SearchRow search;
    @Nullable private Row noSearchResults;
    /** The home page's last row, which opens the Ko-fi page in a browser. */
    @Nullable private Row support;
    /** Report a problem, right above Support HushGram. */
    @Nullable private Row report;
    /** Keep the actual row objects, including their values and listeners, while filtering. */
    private final Map<PreferenceCategory, List<Preference>> searchableRows = new LinkedHashMap<>();
    private final Map<Preference, String> searchAliases = new HashMap<>();
    /** The order each section and row was built with, kept while a search has it further down. */
    private final Map<Preference, Integer> builtOrders = new HashMap<>();
    /** Past every section's and row's own order, and far short of the rows that close the page. */
    private static final int RANKED_LATER = 100_000;
    /** Stable category keys, retained while search temporarily reveals other settings. */
    private final Set<String> expandedCategories = new java.util.LinkedHashSet<>();
    /** The last expanded section, used to return to it after a restart. */
    @Nullable private String lastCategory;
    /** The button at the top that switches between the list of categories and one long list. */
    @Nullable private Row viewToggle;
    /** Before you sign in, while no tap has hidden it. The home page's, like the status card. */
    @Nullable private Preference beforeSignIn;
    @Nullable private String restoredCategory;
    private int restoredPosition = -1;
    private int restoredTop;

    /** The page's dialogs that may still be on screen, which would outlive it. */
    private final List<Dialog> shownDialogs = new ArrayList<>();
    private final Set<Dialog> mutatingDialogs = new HashSet<>();
    private static final Map<Preference, WeakReference<HushgramPreferenceFragment>> mutationOwners = new WeakHashMap<>();
    private static WeakReference<HushgramPreferenceFragment> newPinOwner = new WeakReference<>(null);

    /** The Downloads section, where the running saves are listed, or null when no download patch is in. */
    @Nullable
    PreferenceCategory downloads;
    /** Choose a folder (#333) and Use Movies and Pictures, under Save folder. */
    @Nullable Row saveTree;
    @Nullable Row galleryFolders;
    @Nullable PreferenceCategory recovery;
    @Nullable private Row interruptedSaves;
    @Nullable private Row lastCarouselSave;

    /** The rows of the saves running now, by save number. */
    private final Map<Integer, SaveRow> saveRows = new HashMap<>();

    /** Keeps the rows in step with the saves while the page is showing. Saves tell it from their own thread. */
    private final SaveControl.Watcher saves = () -> Utils.runOnMainThread(this::showSaves);

    @Override
    public void onCreate(Bundle state) {
        if (state != null) {
            restoredCategory = state.getString("hushgram_open_category");
            List<String> expanded = state.getStringArrayList("hushgram_expanded_categories");
            if (expanded != null) expandedCategories.addAll(expanded);
            restoredPosition = state.getInt("hushgram_category_position", -1);
            restoredTop = state.getInt("hushgram_category_top", 0);
            documentRequest = state.getInt("hushgram_document_request", 0);
            // Older saved pages used the operation itself as their Android request code.
            documentCode = state.getInt("hushgram_document_code", documentRequest);
            fontRequestRevision = state.getLong("hushgram_font_revision", -1);
            if (isFontRequest(documentRequest) && !Fonts.importProcess().equals(state.getString("hushgram_font_process"))) {
                fontRequestRevision = fontSetting(documentRequest).savedRevision();
            }
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
        } else if (getParentFragment() instanceof SettingsDialog) {
            // Keep the old restart marker compatible by expanding its section on the main page.
            restoredCategory = ((SettingsDialog) getParentFragment()).startPage();
        }
        super.onCreate(state);
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("hushgram_document_request", documentRequest);
        state.putInt("hushgram_document_code", documentCode);
        state.putLong("hushgram_font_revision", fontRequestRevision);
        state.putString("hushgram_font_process", Fonts.importProcess());
        state.putInt("hushgram_document_sequence", documentSequence);
        state.putString("hushgram_configuration_export", configurationExportToken);
        state.putStringArrayList("hushgram_expanded_categories", new ArrayList<>(expandedCategories));
        if (lastCategory != null) state.putString("hushgram_open_category", lastCategory);
        ListView list = listView();
        boolean searching = !searchQuery.trim().isEmpty();
        View first = list == null ? null : list.getChildAt(0);
        state.putInt("hushgram_category_position", searching || list == null ? 0 : list.getFirstVisiblePosition());
        state.putInt("hushgram_category_top", searching || first == null ? 0 : first.getTop() - list.getPaddingTop());
    }

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container,
                             Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, container, savedInstanceState);
        ListView list = root == null ? null : root.findViewById(android.R.id.list);
        // The search field keeps the focus while its typing filters the rows (TypingFocusHold).
        if (list != null) TypingFocusHold.around(list);
        return root;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ListView list = view.findViewById(android.R.id.list);
        if (list != null) {
            // Our saved position belongs to the restored page, not the previous search results.
            list.setSaveEnabled(false);
            list.setItemsCanFocus(true);
            list.setDivider(null);
            list.setDividerHeight(0);
            list.setBackgroundColor(Color.BLACK);
        }
        showTitle(null);
    }

    @Override public void onViewStateRestored(Bundle state) {
        super.onViewStateRestored(state);
        focusPage();
        ListView list = listView();
        if (list != null && restoredPosition >= 0) {
            int position = restoredPosition, top = restoredTop;
            restoredPosition = -1;
            list.post(() -> {
                if (list == listView()) list.setSelectionFromTop(position, top);
            });
        } else if (list != null && categoryPages() && lastCategory != null) {
            String category = lastCategory;
            list.post(() -> {
                if (list != listView()) return;
                for (int i = 0; i < list.getCount(); i++) {
                    Object item = list.getItemAtPosition(i);
                    if (item instanceof Heading && category.equals(((Heading) item).getKey())) {
                        list.setSelectionFromTop(i, 0);
                        break;
                    }
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        AbstractPreferenceFragment.setRestartFeedbackPresenter(restartFeedback);
        showRestart();
        ExportStatus.CONFIGURATION.watch(exportChanges);
        ExportStatus.DIAGNOSTICS.watch(exportChanges);
        Fonts.watchImports(fontChanges);
        SaveControl.watch(saves);
        SaveLeftovers.showInterrupted(getContext());
        showSaves();
        showClearPositions();
        showConfiguration();
        showLock();
    }

    @Override
    public void onPause() {
        ExportStatus.CONFIGURATION.unwatch(exportChanges);
        ExportStatus.DIAGNOSTICS.unwatch(exportChanges);
        Fonts.unwatchImports(fontChanges);
        SaveControl.unwatch(saves);
        undoRefresh.removeCallbacks(refreshUndo);
        lockRefresh.removeCallbacks(lockTick);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        AbstractPreferenceFragment.clearRestartFeedbackPresenter(restartFeedback);
        undoRefresh.removeCallbacks(refreshUndo);
        lockRefresh.removeCallbacks(lockTick);
        for (Dialog dialog : new ArrayList<>(shownDialogs)) dialog.dismiss();
        shownDialogs.clear();
        mutatingDialogs.clear();
        Fonts.unwatchImports(fontChanges);
        if (newPinOwner.get() == this) PinLock.closeNew();
        clearPositions = null;
        exportDiagnostics = null;
        exportConfiguration = importConfiguration = undoConfiguration = null;
        recovery = null;
        interruptedSaves = null;
        // The rows outlive this view, and a search left on them would be read as how they were built.
        restoreOrders();
        if (!categoryPages()) NewRowDots.leftEverything();
        else for (PreferenceCategory group : searchableRows.keySet()) {
            if (expandedCategories.contains(group.getKey())) NewRowDots.left(group);
        }
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        Activity activity = getActivity();
        boolean removed = isRemoving() || (getParentFragment() != null && getParentFragment().isRemoving());
        if (documentRequest == EXPORT_CONFIGURATION && (removed || (activity != null && activity.isFinishing()))) {
            ExportStatus.CONFIGURATION.finish(configurationExportToken, L10n.t("Settings export canceled."));
        }
        NewRowDots.stop();
        DescriptionInfo.searching = false;
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
        // The rows are built again, so the old ones' greying is no longer anyone's to undo.
        lockedRows.clear();

        viewToggle = new Row(context);
        viewToggle.setKey("hushgram_view_toggle");
        viewToggle.setPersistent(false);
        viewToggle.setOnPreferenceClickListener(tapped -> {
            Preference own = findPreference(Settings.CATEGORY_PAGES.key);
            if (own == null ? !allowMutation() : !allowMutation(own, true)) return true;
            if (own instanceof TwoStatePreference) {
                ((TwoStatePreference) own).setChecked(!Settings.CATEGORY_PAGES.savedValue());
            } else {
                Settings.CATEGORY_PAGES.save(!Settings.CATEGORY_PAGES.savedValue());
                filterSettings();
            }
            return true;
        });
        Preference status = statusCard(context);
        status.setOrder(-40);
        screen.addPreference(status);
        // Search and the list switch lead the page; notices and recovery follow them.
        beforeSignIn = null;
        if (!Settings.SIGN_IN_NOTICE_HIDDEN.savedValue()) {
            Preference notice = beforeSignIn = signInNotice(context, screen);
            notice.setOrder(-30);
            screen.addPreference(notice);
        }
        viewToggle.setOrder(-45);
        screen.addPreference(viewToggle);
        search = new SearchRow(context);
        search.setOrder(-50);
        search.setKey("hushgram_settings_search");
        search.setTitle(L10n.t("Search settings"));
        search.setPersistent(false);
        // ListView skips non-selectable rows when restoring or resetting its position outside
        // touch mode. Search contains real controls, so it must remain a selectable row.
        screen.addPreference(search);
        noSearchResults = new Row(context);
        noSearchResults.setPersistent(false);
        noSearchResults.setSelectable(false);
        noSearchResults.setKey("hushgram_settings_search_empty");
        noSearchResults.setOrder(-1);
        noSearchResults.setTitle(L10n.t("No matching settings"));
        noSearchResults.setSummary(L10n.t("Try another word. Settings only appear for patches included in this install."));
        mark(noSearchResults, SettingsIcons.SEARCH);
        // The export row below reads this; registering twice keeps one.
        PatchFamily.registerDiagnostics();
        Set<PatchFamily> build = PatchFamily.inThisBuild();
        // Everyday controls first; settings access and upkeep follow them.
        PrivacyCategory.add(this, screen, context, build);
        FeedCategory.add(this, screen, context, build);
        MetaAiCategory.add(this, screen, context, build);
        ExploreCategory.add(this, screen, context, build);
        MessagesCategory.add(this, screen, context, build);
        ReelsCategory.add(this, screen, context, build);
        StoriesCategory.add(this, screen, context, build);
        PlaybackCategory.add(this, screen, context, build);
        SharingCategory.add(this, screen, context, build);
        CommentsCategory.add(this, screen, context, build);
        ProfilesCategory.add(this, screen, context, build);
        LayoutCategory.add(this, screen, context, build);
        TabBarCategory.add(this, screen, context, build);
        NotificationsCategory.add(this, screen, context, build);
        StorageCategory.add(this, screen, context, build);
        DownloadsCategory.add(this, screen, context, build);
        BatteryCategory.add(this, screen, context, build);
        UpdatesCategory.add(this, screen, context, build);
        EntryCategory.add(this, screen, context, build);
        PatchTimeCategory.add(this, screen, context, build);
        BackupCategory.add(this, screen, context, build);
        PauseCategory.add(this, screen, context, build);
        AboutCategory.add(this, screen, context, build);

        // Outside every category, like Support HushGram below it. Every row counts toward the
        // switches it lists, the ones a folded category or a search keeps off the screen too.
        Row bug = report = new Row(context);
        bug.setKey(REPORT_ROW_KEY);
        bug.setTitle(L10n.t("Report a problem"));
        bug.setSummary(L10n.t("Opens a GitHub report with your versions and settings filled in. Add what happened."));
        bug.setPersistent(false);
        bug.setOrder(Integer.MAX_VALUE / 2 - 1);
        bug.setOnPreferenceClickListener(p -> {
            ReportProblemForm.open(p.getContext(), everyRow());
            return true;
        });
        mark(bug, SettingsIcons.BUG);

        // Outside every category, so no category section, count or saved setting changes. filterSettings
        // puts it last on the home page and in a search it matches.
        Row donate = support = new Row(context);
        donate.setKey(SUPPORT_ROW_KEY);
        // Its name alone, with no line under it.
        donate.setTitle(L10n.t("Support HushGram"));
        donate.setPersistent(false);
        // Native Preference.compareTo subtracts orders, so leave room for negative header orders.
        donate.setOrder(Integer.MAX_VALUE / 2);
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
                mutationOwners.put(row, new WeakReference<>(this));
                Preference.OnPreferenceChangeListener change = row.getOnPreferenceChangeListener();
                row.setOnPreferenceChangeListener((preference, value) -> allowMutation(preference, false)
                        && (change == null || change.onPreferenceChange(preference, value)));
                StringBuilder aliases = new StringBuilder(
                        row == ghostMode || row == ghostPicks || row == ghostTab ? "ghost mode " : "");
                for (BooleanSetting setting : ghostSwitches) {
                    if (setting.key.equals(key) && row.getTitle() != null) ghostNames.put(key, row.getTitle());
                }
                aliases.append(row == clearPositions
                        ? L10n.t("Clear remembered positions")
                        : row.getTitle() == null ? "" : row.getTitle().toString());
                // Its title follows the lock's state, so the words for every state find it.
                if (row == settingsLock) aliases.append(' ').append(L10n.t("Lock these settings"))
                        .append(' ').append(L10n.t("Ask to unlock")).append(" lock focus cooldown wait timer");
                if (L10n.t("Pure black dark mode").equals(row.getTitle())
                        || L10n.t("Pure black dark mode isn't in this build").equals(row.getTitle())) {
                    aliases.append(" amoled oled black theme");
                }
                if (NotificationsCategory.movedFromProfiles(key)) aliases.append(' ').append(L10n.t("Profiles"));
                if (Settings.HIDE_REELS_TAB.key.equals(key) || Settings.SHOW_REELS_TAB.key.equals(key)) {
                    aliases.append(' ').append(L10n.t("Reels"));
                }
                if (Settings.VIEW_DM_MEDIA_ANONYMOUSLY.key.equals(key)) aliases.append(' ').append(L10n.t("Ads and privacy"));
                if (Settings.REMOVE_BUILD_EXPIRED_POPUP.key.equals(key)) aliases.append(' ').append(L10n.t("Updates"));
                for (PatchFamily family : build) {
                    boolean belongs = family.switches.stream().anyMatch(setting -> setting.key.equals(key));
                    belongs |= family == PatchFamily.STORY_RING && (Settings.STORY_RING_SCALE.key.equals(key)
                            || Settings.STORY_RING_COLOR.key.equals(key));
                    belongs |= family == PatchFamily.ROUNDED_POST_CORNERS && Settings.ROUNDED_CORNER_SIZE.key.equals(key);
                    belongs |= family == PatchFamily.AMBIENT_LIGHT && (Settings.AMBIENT_LIGHT_SPREAD.key.equals(key)
                            || Settings.AMBIENT_LIGHT_SPEED.key.equals(key));
                    belongs |= family == PatchFamily.STORY_PROGRESS_BAR && Settings.STORY_BAR_THICKNESS.key.equals(key);
                    belongs |= family == PatchFamily.STORIES_TRAY && Settings.TRAY_BADGE.key.equals(key);
                    belongs |= family == PatchFamily.REEL_TAP_AND_VOLUME && Settings.REEL_TAP_CHOICE.key.equals(key);
                    belongs |= family == PatchFamily.REEL_DECLUTTER && (Settings.BURN_IN_GUARD.key.equals(key)
                            || Settings.BURN_IN_WAIT.key.equals(key) || Settings.BURN_IN_STAY_DIM.key.equals(key)
                            || Settings.START_REEL_CLEAR_MODE.key.equals(key));
                    belongs |= family == PatchFamily.REEL_SCROLLING && (Settings.SCROLL_BREAK.key.equals(key)
                            || Settings.REEL_CAP_COUNT.key.equals(key) || Settings.REEL_CAP_BREAK.key.equals(key));
                    belongs |= family == PatchFamily.PLAYBACK_QUALITY && Settings.PLAYBACK_QUALITY.key.equals(key);
                    belongs |= family == PatchFamily.PLAYBACK_QUALITY && Settings.QUALITY_TAB_TARGET.key.equals(key);
                    belongs |= family == PatchFamily.TAP_TO_PLAY && (Settings.TAP_TO_PLAY_SCOPE.key.equals(key)
                            || Settings.TAP_TO_PLAY_AUTO_SCROLL_WAITS.key.equals(key));
                    belongs |= family == PatchFamily.STORY_TIME && Settings.STORY_TIME_MODE.key.equals(key);
                    belongs |= family == PatchFamily.REEL_POST_TIME && (Settings.REEL_POST_TIME_SECONDS.key.equals(key)
                            || Settings.REEL_POST_TIME_AGO.key.equals(key));
                    belongs |= family == PatchFamily.FRIENDSHIP_STATUS && Settings.FOLLOW_LABEL_PLACE.key.equals(key);
                    belongs |= family == PatchFamily.FULL_SCREEN_BARS && Settings.FULL_SCREEN_HIDES.key.equals(key);
                    belongs |= family == PatchFamily.GLASS_TAB_BAR && Settings.GLASS_TAB_BAR_HAPTIC_STYLE.key.equals(key);
                    belongs |= family == PatchFamily.GLASS_TAB_BAR && (Settings.GLASS_TAB_BAR_OPACITY.key.equals(key)
                            || Settings.GLASS_TAB_BAR_BLUR_RADIUS.key.equals(key)
                            || Settings.GLASS_TAB_BAR_HEIGHT.key.equals(key) || Settings.GLASS_TAB_BAR_LIFT.key.equals(key)
                            || Settings.GLASS_TAB_BAR_CORNERS.key.equals(key) || Settings.GLASS_TAB_BAR_REELS_HIDE.key.equals(key));
                    belongs |= family == PatchFamily.VIDEO_DOWNLOAD && (Settings.HOLD_TO_DOWNLOAD.key.equals(key)
                            || Settings.HOLD_FINGERS.key.equals(key) || Settings.HOLD_TIME.key.equals(key)
                            || Settings.FEED_DOWNLOAD_BUTTON.key.equals(key) || Settings.FEED_DOWNLOAD_ALL_PAGES.key.equals(key)
                            || Settings.DOWNLOAD_CHOOSE_PAGES.key.equals(key));
                    belongs |= family == PatchFamily.REEL_DOWNLOAD && Settings.REEL_DOWNLOAD_BUTTON.key.equals(key);
                    belongs |= family == PatchFamily.REEL_DOWNLOAD && Settings.REEL_NOT_INTERESTED_BUTTON.key.equals(key);
                    belongs |= family == PatchFamily.REEL_DOWNLOAD && Settings.REEL_BLOCK_BUTTON.key.equals(key);
                    belongs |= family == PatchFamily.REEL_DOWNLOAD && Settings.TEXT_BUTTON_ON_REELS.key.equals(key);
                    belongs |= family == PatchFamily.REEL_DOWNLOAD && (Settings.HIDE_SOUND_REELS.key.equals(key)
                            || HIDDEN_SOUNDS_ROW.equals(key));
                    belongs |= family == PatchFamily.ACCENT_COLOR && Settings.ACCENT_HUE.key.equals(key);
                    belongs |= family == PatchFamily.FONT && Settings.FONT.key.equals(key);
                    belongs |= family == PatchFamily.EMOJI_STYLE && Settings.EMOJI_FONT.key.equals(key);
                    belongs |= family == PatchFamily.STORY_FONTS && STORY_FONTS_ROW.equals(key);
                    belongs |= family == PatchFamily.APP_ICON && Settings.APP_ICON.key.equals(key);
                    belongs |= family == PatchFamily.SETTINGS_ROWS && Settings.HIDDEN_SETTINGS_ROWS.key.equals(key);
                    belongs |= family == PatchFamily.NOTIFICATION_GROUPS && Settings.MUTED_NOTIFICATION_CHANNELS.key.equals(key);
                    belongs |= family == PatchFamily.SHARE_SHEET && Settings.HIDDEN_SHARE_SHEET_BUTTONS.key.equals(key);
                    belongs |= family == PatchFamily.PROFILE_TABS && (Settings.HIDDEN_PROFILE_TABS.key.equals(key)
                            || Settings.HIDDEN_OWN_PROFILE_TABS.key.equals(key));
                    belongs |= family == PatchFamily.MESSAGE_BOX && Settings.HIDDEN_MESSAGE_BOX_BUTTONS.key.equals(key);
                    belongs |= family == PatchFamily.REELS_TAB
                            && (Settings.START_TAB.key.equals(key) || Settings.TAB_ORDER.key.equals(key));
                    belongs |= family == PatchFamily.TAB_BAR_LAYOUT && Settings.TAB_BAR_LAYOUT.key.equals(key);
                    belongs |= family == PatchFamily.LIKE_ANIMATION && Settings.LIKE_ANIMATION.key.equals(key);
                    belongs |= family == PatchFamily.CAPTION_WORD_REELS && (Settings.REELS_BLOCKED_WORDS.key.equals(key)
                            || Settings.HIDE_CAPTION_WORD_HOME.key.equals(key));
                    belongs |= family == PatchFamily.MESSAGES_LOCK
                            && (Settings.LOCK_AGAIN.key.equals(key) || LOCKED_CHATS_ROW.equals(key)
                            || HIDDEN_CHATS_ROW.equals(key) || PINNED_CHATS_ROW.equals(key) || Settings.UNLOCK_WITH.key.equals(key)
                            || CHANGE_PIN_ROW.equals(key));
                    belongs |= family == PatchFamily.RESUME_LONG_VIDEOS && row == clearPositions;
                    belongs |= (family == PatchFamily.REEL_DOWNLOAD || family == PatchFamily.STORY_DOWNLOAD
                            || family == PatchFamily.VIDEO_DOWNLOAD || family == PatchFamily.PROFILE_PICTURE
                            || family == PatchFamily.VOICE_MESSAGE)
                            && (Settings.DOWNLOAD_QUALITY.key.equals(key)
                            || Settings.SAVE_FOLDER.key.equals(key) || Settings.FILENAME_TEMPLATE.key.equals(key)
                            || Settings.DOWNLOAD_COMPATIBLE.key.equals(key) || Settings.DOWNLOAD_CONVERT.key.equals(key));
                    belongs |= (family == PatchFamily.REEL_DOWNLOAD || family == PatchFamily.STORY_DOWNLOAD)
                            && (Settings.DOWNLOAD_SKIP_SAVED.key.equals(key) || CLEAR_DOWNLOAD_HISTORY.equals(key));
                    belongs |= family == PatchFamily.VIDEO_DOWNLOAD && CLEAR_DOWNLOAD_HISTORY.equals(key);
                    belongs |= family == PatchFamily.VIDEO_DOWNLOAD && (Settings.VIEWING_HISTORY.key.equals(key)
                            || VIEWING_HISTORY_ROW.equals(key) || CLEAR_VIEWING_HISTORY.equals(key));
                    belongs |= family == PatchFamily.RESTORE_TRUST && L10n.t("Instagram accepts this build").equals(row.getTitle());
                    belongs |= family == PatchFamily.REMOVE_AD_ID && L10n.t("Advertising ID removed").equals(row.getTitle());
                    belongs |= family == PatchFamily.PURE_BLACK && L10n.t("Pure black dark mode").equals(row.getTitle());
                    belongs |= family == PatchFamily.VERSION_CODE && L10n.t("No Play Store updates").equals(row.getTitle());
                    if (!belongs) continue;
                    aliases.append(' ').append(family.patchName);
                    if (family == PatchFamily.FOLLOWING_FEED) aliases.append(" following home feed");
                    if (Settings.REEL_AUTO_ADVANCE.key.equals(key)) aliases.append(" auto advance autoloop loop next reels");
                    if (Settings.STOP_VIDEO_LOOPING.key.equals(key)) aliases.append(" autoloop loop replay repeat reels");
                    if (family == PatchFamily.REEL_DECLUTTER || family == PatchFamily.REEL_DOWNLOAD
                            || family == PatchFamily.REEL_WATCH_HISTORY || family == PatchFamily.REELS_TAB
                            || family == PatchFamily.KEEP_REEL_SPEED || family == PatchFamily.TAP_TO_PLAY
                            || family == PatchFamily.PLAYBACK_QUALITY || family == PatchFamily.RESUME_LONG_VIDEOS
                            || family == PatchFamily.REEL_TAP_AND_VOLUME
                            || Settings.TURN_OFF_DOUBLE_TAP_LIKE_ON_REELS.key.equals(key)) aliases.append(" reels");
                }
                searchAliases.put(row, aliases.toString());
                if (row.getIcon() == null) mark(row, SettingsIcons.forKey(key, iconOf(group)));
            }
            searchableRows.put(group, rows);
            group.setKey(CATEGORY_ROW_KEY + iconOf(group));
            mark(group, iconOf(group));
            group.setOnPreferenceClickListener(tapped -> {
                toggleCategory(group);
                return true;
            });
        }
        // After every row is known: a first open counts them all as seen, so a fresh install has no dots.
        NewRowDots.start(context, searchableRows);
        Set<String> available = new HashSet<>();
        for (PreferenceCategory group : searchableRows.keySet()) {
            available.add(group.getKey());
            NewRowDots.opens(group, group);
        }
        expandedCategories.retainAll(available);
        if (restoredCategory != null && available.contains(restoredCategory)) {
            expandedCategories.add(restoredCategory);
            lastCategory = restoredCategory;
        } else if (restoredCategory != null) restoredPosition = -1;
        restoredCategory = null;
        filterSettings();
    }

    private static String iconOf(PreferenceCategory group) {
        return group instanceof Heading ? ((Heading) group).icon : SettingsIcons.SETTINGS;
    }

    /** Whether the page lists its categories. A choice about the page, so Pause doesn't turn it off. */
    private static boolean categoryPages() {
        return Settings.CATEGORY_PAGES.savedValue();
    }

    /** Expand in place. Keep the visible anchor instead of scrolling to the top of settings. */
    private void toggleCategory(PreferenceCategory group) {
        if (!categoryPages() || !searchQuery.trim().isEmpty()) return;
        ListView list = listView();
        int position = list == null ? -1 : list.getFirstVisiblePosition();
        Object anchor = list == null || position < 0 || position >= list.getCount()
                ? null : list.getItemAtPosition(position);
        View first = list == null ? null : list.getChildAt(0);
        int top = first == null ? 0 : first.getTop() - list.getPaddingTop();
        if (expandedCategories.remove(group.getKey())) {
            NewRowDots.left(group);
            if (group.getKey().equals(lastCategory)) lastCategory = null;
        } else {
            expandedCategories.add(group.getKey());
            lastCategory = group.getKey();
        }
        focusPage();
        filterSettings();
        if (list != null && anchor != null) {
            list.post(() -> {
                if (list != listView()) return;
                for (int i = 0; i < list.getCount(); i++) {
                    if (list.getItemAtPosition(i) == anchor) {
                        list.setSelectionFromTop(i, top);
                        break;
                    }
                }
            });
        }
    }

    /** Back clears a search first. Expanded sections do not add a navigation level. */
    boolean closeCategory() {
        return endSearch();
    }

    /**
     * Back during a search ends it on the page it started from, as the field's own clear does.
     * From a category's page Back used to drop the category under the search instead: the matches
     * stayed, and the status card and the list switch came in above the field (S25, 2026-10-13).
     */
    private boolean endSearch() {
        if (searchText(searchQuery).trim().isEmpty()) return false;
        View view = getView();
        EditText field = view == null ? null : view.findViewWithTag("hushgram-settings-search");
        // On the screen the field's watcher filters and puts its clear button away. Scrolled off
        // it there is no field, and the next one is built from the query.
        if (field != null) field.setText("");
        if (!searchQuery.isEmpty()) searchSettings("");
        ListView list = listView();
        if (list != null) list.post(() -> list.setSelection(0));
        return true;
    }

    @Nullable private ListView listView() {
        View view = getView();
        return view == null ? null : view.findViewById(android.R.id.list);
    }

    /** Binds the rows on show again, for a choice about how every row looks. */
    void rebindRows() {
        ListView list = listView();
        if (list != null) list.invalidateViews();
    }

    /** Page entry shouldn't focus the search field or move a restored viewport to show it. */
    private void focusPage() {
        View view = getView();
        if (!(view instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup body = (android.view.ViewGroup) view;
        FocusRest.on(body);
        body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // PreferenceFragment posts focusableViewAvailable(list) after binding. Keep initial
        // focus here; explicit focus on Search and its controls still works normally. Under a
        // keyboard the rest steps aside and this lands on the rows.
        body.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        body.requestFocus();
    }

    private void showTitle(@Nullable CharSequence title) {
        if (getParentFragment() instanceof SettingsDialog) ((SettingsDialog) getParentFragment()).showTitle(title);
    }

    /** Keeps the screen's Restart now bar in step with what waits on a restart. */
    private void showRestart() {
        if (getParentFragment() instanceof SettingsDialog) {
            ((SettingsDialog) getParentFragment()).showRestart(restartActionAvailable());
        }
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

    /**
     * A plain row tied to a setting (Tab order) shows its own summary and saves its own value, so
     * there's nothing to sync. The shared sync would log every one as a patch error each time
     * settings opens and on every tap.
     */
    @Override
    protected void syncSettingWithPreference(Preference pref, Setting<?> setting, boolean applySettingToPreference) {
        if (pref instanceof Row) return;
        super.syncSettingWithPreference(pref, setting, applySettingToPreference);
    }

    private void restoreSearchRows() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || search == null || screen.findPreference(search.getKey()) != search) return;
        for (Map.Entry<PreferenceCategory, List<Preference>> section : searchableRows.entrySet()) {
            PreferenceCategory group = section.getKey();
            if (group.getParent() != screen) screen.addPreference(group);
            for (Preference row : section.getValue()) if (row.getParent() != group) group.addPreference(row);
        }
    }

    @Override protected void updateUIToSettingValues() {
        restoreSearchRows();
        try { super.updateUIToSettingValues(); } finally { showGhostMode(); filterSettings(); }
    }

    @Override protected void updateUIAvailability() {
        restoreSearchRows();
        try { super.updateUIAvailability(); } finally { showGhostMode(); filterSettings(); }
    }

    /**
     * Ghost mode shows on only while every switch it turns is on, whichever way they got there, and
     * the row under it names the ones it turns.
     */
    private void showGhostMode() {
        List<BooleanSetting> picked = GhostMode.picked(ghostSwitches);
        SwitchPreference row = ghostMode;
        if (row != null) row.setChecked(GhostMode.on(picked));
        Row picks = ghostPicks;
        if (picks == null) return;
        if (picked.size() == ghostSwitches.size()) {
            picks.setSummary(L10n.t("All of them. Tap to leave some out, and Ghost mode leaves those as you set them."));
            return;
        }
        StringBuilder names = new StringBuilder();
        for (BooleanSetting setting : picked) {
            if (names.length() > 0) names.append(", ");
            names.append(ghostName(setting));
        }
        picks.setSummary(L10n.f("%1$s. The others stay as you set them.", names.toString()));
    }

    /** [setting]'s title as its own row shows it. */
    private CharSequence ghostName(BooleanSetting setting) {
        CharSequence name = ghostNames.get(setting.key);
        if (name != null) return name;
        Preference own = findPreference(setting.key);
        return own == null || own.getTitle() == null ? setting.key : own.getTitle();
    }

    /** The row under Messages that opens the list of message box buttons to show (#217). */
    Row messageBoxRow(Context context) {
        Row row = new Row(context);
        row.setKey(Settings.HIDDEN_MESSAGE_BOX_BUTTONS.key);
        row.setPersistent(false);
        row.setTitle(L10n.t("Buttons in the message box"));
        row.setSummary(messageBoxSummary(Settings.HIDDEN_MESSAGE_BOX_BUTTONS.savedValue()));
        row.setOnPreferenceClickListener(tapped -> {
            pickMessageBoxButtons(row);
            return true;
        });
        return row;
    }

    /** What the buttons left off in [saved] do, for the row's summary. */
    static String messageBoxSummary(String saved) {
        Set<String> hidden = MessageBox.parse(saved);
        if (hidden.isEmpty()) {
            return L10n.t("Every button in the message box shows. Untick the ones you don't use. The text box and Send always stay.");
        }
        List<String> labels = new ArrayList<>();
        for (String name : MessageBox.OFFERED) {
            if (hidden.contains(name)) labels.add(L10n.isolate(messageBoxLabel(name)));
        }
        String left = String.join(", ", labels);
        return L10n.f("Left off the message box: %1$s. The text box and Send stay.", left);
    }

    /** What the message box list calls the button Instagram names [name]. */
    static String messageBoxLabel(String name) {
        switch (name) {
            case "GALLERY":
                return L10n.t("Gallery");
            case "STICKER":
                return L10n.t("Stickers");
            default:
                return L10n.t("Voice message");
        }
    }

    /**
     * Each button Instagram can show in a chat's message box, ticked while it shows. A change saves
     * at once and shows the next time a chat opens. The text box and Send aren't listed.
     */
    void pickMessageBoxButtons(Row row) {
        Context context = getActivity();
        if (context == null) return;
        Set<String> hidden = new LinkedHashSet<>(MessageBox.parse(Settings.HIDDEN_MESSAGE_BOX_BUTTONS.savedValue()));
        List<String> names = MessageBox.OFFERED;
        CharSequence[] labels = new CharSequence[names.size()];
        boolean[] shown = new boolean[names.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = messageBoxLabel(names.get(i));
            shown[i] = !hidden.contains(names.get(i));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Buttons in the message box"));
        builder.setMultiChoiceItems(labels, shown, (dialog, which, on) -> {
            if (!allowMutation(row, true)) {
                setChecked((AlertDialog) dialog, which, !hidden.contains(names.get(which)));
                return;
            }
            if (on) hidden.remove(names.get(which));
            else hidden.add(names.get(which));
            Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save(MessageBox.join(hidden));
            row.setSummary(messageBoxSummary(Settings.HIDDEN_MESSAGE_BOX_BUTTONS.savedValue()));
        });
        AlertDialog dialog = show(builder.setNeutralButton(L10n.t("Show all"), null)
                .setPositiveButton(L10n.t("OK"), null));
        if (dialog == null) return;
        // A click listener of its own, so Show all ticks every button without closing the list.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(tapped -> {
            if (!allowMutation(row, true)) return;
            hidden.clear();
            Settings.HIDDEN_MESSAGE_BOX_BUTTONS.save("");
            row.setSummary(messageBoxSummary(""));
            for (int i = 0; i < names.size(); i++) dialog.getListView().setItemChecked(i, true);
        });
    }

    /** The row under Profiles that opens the list of tabs to show on your own profile (#296). */
    Row ownProfileTabsRow(Context context) {
        return profileTabsRow(context, true);
    }

    /** The row under Profiles that opens the list of tabs to show on other people's profiles (#254, #296). */
    Row otherProfileTabsRow(Context context) {
        return profileTabsRow(context, false);
    }

    private Row profileTabsRow(Context context, boolean own) {
        Row row = new Row(context);
        row.setKey(own ? Settings.HIDDEN_OWN_PROFILE_TABS.key : Settings.HIDDEN_PROFILE_TABS.key);
        row.setPersistent(false);
        row.setTitle(profileTabsTitle(own));
        row.setSummary(profileTabsSummary(own, savedProfileTabs(own)));
        row.setOnPreferenceClickListener(tapped -> {
            pickProfileTabs(row, own);
            return true;
        });
        return row;
    }

    /** The row's and the list's title, for your own profile when [own], else other people's. */
    static String profileTabsTitle(boolean own) {
        return own ? L10n.t("Tabs on your profile") : L10n.t("Tabs on other profiles");
    }

    /** What the user picked to leave off your own profile when [own], else off other people's. */
    static String savedProfileTabs(boolean own) {
        return own ? ProfileTabs.savedForOwn() : Settings.HIDDEN_PROFILE_TABS.savedValue();
    }

    /** What the tabs left off in [saved] do, for the row's summary, on your own profile when [own]. */
    static String profileTabsSummary(boolean own, String saved) {
        Set<String> hidden = ProfileTabs.parse(saved);
        if (hidden.isEmpty()) {
            return own
                    ? L10n.t("Every tab shows on your profile. Untick the ones you don't want. The grid of posts always stays.")
                    : L10n.t("Every tab shows on other people's profiles. Untick the ones you don't want. The grid of posts always stays.");
        }
        List<String> labels = new ArrayList<>();
        for (String key : ProfileTabs.OFFERED) {
            if (hidden.contains(key)) labels.add(L10n.isolate(profileTabLabel(key)));
        }
        String left = String.join(", ", labels);
        return own
                ? L10n.f("Left off your profile: %1$s. The grid of posts stays.", left)
                : L10n.f("Left off other profiles: %1$s. The grid of posts stays.", left);
    }

    /** What the profile tabs list calls the tab Instagram keys [key]. */
    static String profileTabLabel(String key) {
        switch (key) {
            case "profile_clips":
                return L10n.t("Reels");
            case "profile_reposts":
                return L10n.t("Reposts");
            default:
                return L10n.t("Tagged");
        }
    }

    /**
     * Each tab Instagram can show on a profile, ticked while it shows, for your own profile when
     * [own], else other people's. A change saves at once and shows the next time a profile opens.
     * The grid of posts isn't listed, since it's what stays.
     */
    void pickProfileTabs(Row row, boolean own) {
        Context context = getActivity();
        if (context == null) return;
        Set<String> hidden = new LinkedHashSet<>(ProfileTabs.parse(savedProfileTabs(own)));
        List<String> keys = ProfileTabs.OFFERED;
        CharSequence[] names = new CharSequence[keys.size()];
        boolean[] checked = new boolean[keys.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = profileTabLabel(keys.get(i));
            checked[i] = !hidden.contains(keys.get(i));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(profileTabsTitle(own));
        builder.setMultiChoiceItems(names, checked, (dialog, which, on) -> {
            if (!allowMutation(row, true)) {
                setChecked((AlertDialog) dialog, which, !hidden.contains(keys.get(which)));
                return;
            }
            if (on) hidden.remove(keys.get(which));
            else hidden.add(keys.get(which));
            if (own) ProfileTabs.saveOwn(hidden);
            else ProfileTabs.saveOthers(hidden);
            row.setSummary(profileTabsSummary(own, savedProfileTabs(own)));
        });
        show(builder.setPositiveButton(L10n.t("OK"), null));
    }

    /** The row under Ghost mode that opens the list of switches it turns (#251). */
    Row ghostPicksRow(Context context) {
        Row row = new Row(context);
        row.setKey(GHOST_PICKS_ROW);
        row.setPersistent(false);
        row.setTitle(L10n.t("Switches Ghost mode turns"));
        row.setOnPreferenceClickListener(tapped -> {
            pickGhostMode();
            return true;
        });
        return row;
    }

    /**
     * Each ghost switch with a check on the ones Ghost mode turns. A change saves at once, and the
     * Ghost mode row, the Home button and the inbox long press all follow it. The last check can't
     * come off, since Ghost mode would have nothing to turn.
     */
    void pickGhostMode() {
        Context context = getActivity();
        List<BooleanSetting> switches = ghostSwitches;
        if (context == null || switches.isEmpty()) return;
        Set<String> picked = new LinkedHashSet<>();
        for (BooleanSetting setting : GhostMode.picked(switches)) picked.add(setting.key);
        CharSequence[] names = new CharSequence[switches.size()];
        boolean[] checked = new boolean[switches.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = ghostName(switches.get(i));
            checked[i] = picked.contains(switches.get(i).key);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Switches Ghost mode turns"));
        builder.setMultiChoiceItems(names, checked, (dialog, which, on) -> {
            String key = switches.get(which).key;
            if (!allowMutation()) {
                setChecked((AlertDialog) dialog, which, picked.contains(key));
                return;
            }
            if (!on && picked.size() == 1 && picked.contains(key)) {
                checked[which] = true;
                setChecked((AlertDialog) dialog, which, true);
                Utils.showToastShort(L10n.t("Ghost mode needs at least one switch to turn."));
                return;
            }
            if (on) picked.add(key);
            else picked.remove(key);
            GhostMode.pick(switches, picked);
            showGhostMode();
        });
        show(builder.setPositiveButton(L10n.t("OK"), null));
    }

    /**
     * Ghost mode's row. It keeps no value: a tap saves the new value to each switch it turns, through
     * that switch's own row when the page has one, so each row shows its new state, and a toast
     * says what happened.
     */
    SwitchPreference ghostModeRow(Context context) {
        SwitchPreference row = new Toggle(context);
        row.setPersistent(false);
        row.setTitle(L10n.t("Ghost mode"));
        row.setSummary(L10n.t("Turns the switches that keep what you do to yourself on or off in one go, like View "
                + "stories anonymously and Hide that you're typing. Each one keeps its own switch."));
        row.setOnPreferenceChangeListener((preference, value) -> {
            boolean on = Boolean.TRUE.equals(value);
            List<BooleanSetting> picked = GhostMode.picked(ghostSwitches);
            for (BooleanSetting setting : picked) {
                Preference own = findPreference(setting.key);
                if (own instanceof TwoStatePreference && own.isPersistent()) ((TwoStatePreference) own).setChecked(on);
                else setting.save(on);
            }
            showGhostMode();
            Utils.showToastShort(GhostMode.message(picked, on,
                    L10n.t("Couldn't change every Ghost mode switch. Check them below.")));
            return false;
        });
        row.setChecked(GhostMode.on(GhostMode.picked(ghostSwitches)));
        mark(row, SettingsIcons.HIDDEN);
        return row;
    }

    /**
     * [text] as search compares it: no accents or invisible marks, one case. Every row's title and
     * summary go through here on each keystroke and resume, so the pattern is compiled once and each
     * string worked out once: done afresh every time, typing a query held the main thread past an
     * ANR on the emulator.
     */
    static String searchText(CharSequence text) {
        if (text == null) return "";
        String key = text.toString();
        synchronized (SEARCH_TEXT) {
            String found = SEARCH_TEXT.get(key);
            if (found != null) return found;
        }
        String normalized = SEARCH_MARKS.matcher(Normalizer.normalize(key, Normalizer.Form.NFKD)
                .toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)).replaceAll("");
        synchronized (SEARCH_TEXT) {
            SEARCH_TEXT.put(key, normalized);
        }
        return normalized;
    }

    private static final Pattern SEARCH_MARKS = Pattern.compile("\\p{M}+|\\p{Cf}+");
    /** searchText's answers, the least recently used dropped past a few thousand. */
    private static final Map<String, String> SEARCH_TEXT = new LinkedHashMap<String, String>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 4096;
        }
    };

    void searchSettings(String query) {
        boolean starting = searchText(searchQuery).trim().isEmpty();
        searchQuery = query == null ? "" : query;
        filterSettings();
        if (starting && !searchText(searchQuery).trim().isEmpty()) searchToTop();
    }

    /**
     * A search that starts puts its field at the top of the list, so what it finds comes up right
     * under it. Below the status card and the sign-in notice the field sat two thirds of the way
     * down the screen: the matches came up behind the keyboard, and with it closed one and a half
     * of them showed (S25, 2026-10-13). Later keystrokes leave the list where the reader put it.
     */
    private void searchToTop() {
        ListView list = listView();
        Preference field = search;
        if (list == null || field == null) return;
        // After the adapter has caught up with the rows the search left on the screen.
        list.post(() -> {
            android.widget.ListAdapter rows = list.getAdapter();
            if (rows == null) return;
            for (int at = 0; at < rows.getCount(); at++) {
                if (rows.getItem(at) == field) {
                    list.setSelectionFromTop(at, 0);
                    return;
                }
            }
        });
    }

    void filterSettings() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || search == null || screen.findPreference(search.getKey()) != search) return;
        String normalized = searchText(searchQuery).trim();
        String[] terms = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        boolean pages = categoryPages();
        NewRowDots.searching(terms.length > 0);
        if (DescriptionInfo.searching != terms.length > 0) {
            DescriptionInfo.searching = terms.length > 0;
            rebindRows();
        }
        rankForSearch(terms);
        // Search reveals matching rows without changing the sections the person expanded.
        boolean listing = pages && terms.length == 0;
        showTitle(terms.length > 0 ? L10n.t("Search settings") : null);
        for (Preference top : new Preference[]{statusCard, beforeSignIn, viewToggle, search}) {
            if (top == null) continue;
            // A tap hid the notice for good and took it off the screen. It stays off.
            if (top == beforeSignIn && Settings.SIGN_IN_NOTICE_HIDDEN.savedValue()) continue;
            boolean hidden = top == statusCard && !HushgramPause.isPaused();
            if (hidden) {
                if (top.getParent() == screen) screen.removePreference(top);
            } else if (top.getParent() != screen) screen.addPreference(top);
        }
        if (viewToggle != null) {
            CharSequence label = pages ? L10n.t("Show all settings as a list") : L10n.t("Show settings by category");
            if (!label.equals(viewToggle.getTitle())) {
                viewToggle.setTitle(label);
                // A list for the long list, stacked cards for the categories: what a tap shows.
                mark(viewToggle, pages ? SettingsIcons.MENU : SettingsIcons.FEED);
            }
        }
        int matches = 0;
        for (Map.Entry<PreferenceCategory, List<Preference>> section : searchableRows.entrySet()) {
            PreferenceCategory group = section.getKey();
            // Pause, the lock and an interrupted save stay in reach: their section opens and can't fold,
            // and stays open after, so unlocking doesn't fold the row just tapped.
            boolean held = listing && group == recovery && recoveryHeldOpen();
            if (held) expandedCategories.add(group.getKey());
            boolean expanded = !listing || expandedCategories.contains(group.getKey());
            boolean filled = false;
            for (Preference row : section.getValue()) {
                String text = searchText(group.getTitle()) + " " + searchText(row.getTitle()) + " "
                        + searchText(row.getSummary()) + " " + searchText(searchAliases.get(row));
                boolean match = true;
                for (String term : terms) if (!text.contains(term)) { match = false; break; }
                if (match) matches++;
                filled |= match || group == recovery;
                if (expanded && (match || group == recovery)) {
                    if (row.getParent() != group) group.addPreference(row);
                } else if (row.getParent() == group) group.removePreference(row);
            }
            // Keep live row objects. A query never hides a running save's Cancel button.
            if (group == downloads) {
                for (SaveRow save : saveRows.values()) {
                    filled = true;
                    if (expanded && save.getParent() != group) group.addPreference(save);
                    else if (!expanded && save.getParent() == group) group.removePreference(save);
                }
                if (lastCarouselSave != null && SaveControl.batchOutcome() != null) {
                    filled = true;
                    if (expanded && lastCarouselSave.getParent() != group) group.addPreference(lastCarouselSave);
                    else if (!expanded && lastCarouselSave.getParent() == group) group.removePreference(lastCarouselSave);
                }
            }
            if (group instanceof Heading) ((Heading) group).display(listing && !held, expanded);
            if (filled) {
                if (group.getParent() != screen) screen.addPreference(group);
            } else if (group.getParent() == screen) screen.removePreference(group);
        }
        // Actions below the categories remain on the same page.
        Row[] loose = {report, support};
        String[] looseAliases = {" github bug issue", " ko-fi kofi donate coffee"};
        for (int i = 0; i < loose.length; i++) {
            Row row = loose[i];
            if (row == null) continue;
            String text = searchText(row.getTitle()) + " " + searchText(row.getSummary()) + looseAliases[i];
            boolean match = true;
            for (String term : terms) if (!text.contains(term)) { match = false; break; }
            boolean shown = match;
            if (terms.length > 0 && match) matches++;
            if (shown) {
                if (row.getParent() != screen) screen.addPreference(row);
            } else if (row.getParent() == screen) screen.removePreference(row);
        }
        // removePreference notifies even when absent. No-op changes mustn't rebind live Cancel.
        if (terms.length > 0 && matches == 0) {
            if (noSearchResults.getParent() != screen) screen.addPreference(noSearchResults);
        } else if (noSearchResults.getParent() == screen) screen.removePreference(noSearchResults);
        // Last, after every row that sets its own state has: a locked page greys them all again.
        showLock();
    }

    /**
     * Lock these settings (#301): what the lock's row says now, and while the settings are locked,
     * every row that changes something greyed out. When they open, the rows it greyed come back and
     * each row's own rules run again. While a wait or the open time runs, it runs again within a
     * minute, so the page opens and locks on time.
     */
    void showLock() {
        if (showingLock) return;
        showingLock = true;
        try {
            lockRefresh.removeCallbacks(lockTick);
            SettingsLock.forgetSpentAsk();
            SettingsLock.State state = SettingsLock.state();
            if (settingsLock != null) showLockRow(settingsLock, state);
            lockHolds = state == SettingsLock.State.LOCKED || state == SettingsLock.State.WAITING;
            if (lockHolds) {
                for (Preference row : everyRow()) holdLocked(row);
                closeMutatingDialogs();
            } else if (!lockedRows.isEmpty()) {
                for (Preference row : lockedRows) row.setEnabled(true);
                lockedRows.clear();
                if (getPreferenceScreen() != null) updateUIAvailability();
                setResumeControlsEnabled(!resumingFromOverview);
                showClearPositions();
                showConfiguration();
            }
            long left = SettingsLock.left();
            if (left > 0 && isResumed()) lockRefresh.postDelayed(lockTick, Math.min(left, 60_000L));
        } finally {
            showingLock = false;
        }
        // The lock can close on its own clock, and a save can stop while the page is away: refold.
        if (recovery instanceof Heading && recovery.getParent() != null && categoryPages()
                && searchQuery.trim().isEmpty() && ((Heading) recovery).collapsible == recoveryHeldOpen()) {
            filterSettings();
        }
    }

    /** Whether Pause and diagnostics must stay open: HushGram is paused, the lock holds, or a save was interrupted. */
    private boolean recoveryHeldOpen() {
        return HushgramPause.isPaused() || BaseSettings.PAUSED.savedValue() || SettingsLock.locked()
                || interruptedSaves != null;
    }

    /** Greys out [row] while the settings are locked, unless it changes nothing or it's the lock itself. */
    private void holdLocked(@Nullable Preference row) {
        if (!lockHolds || row == null || !row.isEnabled() || !lockable(row)) return;
        row.setEnabled(false);
        lockedRows.add(row);
    }

    /**
     * Whether the lock greys [row] out. The lock's own row, both exports, Debug logging, clearing
     * the diagnostic data and the About rows stay open: none of them changes how Instagram acts.
     */
    private boolean lockable(Preference row) {
        if (row == settingsLock || row == exportConfiguration || row == exportDiagnostics
                || row instanceof ClearRow || !row.isSelectable()) return false;
        String key = row.getKey();
        if (BaseSettings.DEBUG.key.equals(key)) return false;
        // Switching Pause off only brings every switch back, so it stays open while paused.
        if (BaseSettings.PAUSED.key.equals(key) && BaseSettings.PAUSED.savedValue()) return false;
        List<Preference> aboutRows = about == null ? null : searchableRows.get(about);
        return aboutRows == null || !aboutRows.contains(row);
    }

    @Nullable
    private static HushgramPreferenceFragment mutationOwner(Preference row) {
        WeakReference<HushgramPreferenceFragment> reference = mutationOwners.get(row);
        return reference == null ? null : reference.get();
    }

    /** Native Save may already be posted when its page closes. An approval received later may not. */
    private static boolean allowMutation(Preference row, boolean needsLivePage) {
        HushgramPreferenceFragment owner = mutationOwner(row);
        if (needsLivePage && mutationOwners.containsKey(row)
                && (owner == null || !owner.isAdded() || owner.getView() == null
                || owner.getActivity() == null || owner.getActivity().isFinishing()
                || owner.getActivity().isDestroyed() || !owner.everyRow().contains(row))) return false;
        boolean exempt = owner != null ? !owner.lockable(row)
                : BaseSettings.DEBUG.key.equals(row.getKey())
                || (BaseSettings.PAUSED.key.equals(row.getKey()) && BaseSettings.PAUSED.savedValue());
        if (!SettingsLock.locked() || exempt) return true;
        sayLocked();
        if (owner != null) Utils.runOnMainThread(() -> {
            if (owner.getView() != null) owner.showLock();
        });
        return false;
    }

    private boolean allowMutation() {
        if (!SettingsLock.locked()) return true;
        sayLocked();
        Utils.runOnMainThread(() -> { if (getView() != null) showLock(); });
        return false;
    }

    /** Read-only policy for the final worker transaction, after every provider stream closed. */
    private static boolean asyncChangesAllowed() {
        return !SettingsLock.locked() && !MessagesLock.locked();
    }

    private static String heldMessage() {
        return SettingsLock.locked() ? L10n.t("These settings are locked. Ask to unlock first.")
                : L10n.t("HushGram settings are locked");
    }

    /** A deferred callback must save successfully before it presents a new value. */
    private static <T> boolean saveDeferred(Preference row, Setting<T> setting, T value) {
        if (!allowMutation(row, true)) return false;
        Object before = setting.savedValue();
        AbstractPreferenceFragment.noteRunningValue(setting, before);
        boolean saved = setting.save(value);
        boolean persistent = row.isPersistent();
        row.setPersistent(false);
        try {
            if (row instanceof TwoStatePreference) ((TwoStatePreference) row).setChecked(Boolean.TRUE.equals(setting.savedValue()));
            else if (row instanceof ListPreference) ((ListPreference) row).setValue(setting.savedValue().toString());
        } finally {
            row.setPersistent(persistent);
        }
        if (!saved) Utils.showToastShort(L10n.t("The setting couldn't finish updating. Its saved value is shown."));
        else {
            HushgramPreferenceFragment owner = mutationOwner(row);
            if (owner != null) owner.noteRestartPending(setting, before);
        }
        return saved;
    }

    private static void promptNewPin(Preference row, Activity activity, Consumer<Boolean> done) {
        HushgramPreferenceFragment owner = mutationOwner(row);
        if (!allowMutation(row, true)) {
            done.accept(false);
            return;
        }
        newPinOwner = new WeakReference<>(owner);
        PinLock.promptNew(activity, () -> allowMutation(row, true), saved -> {
            if (newPinOwner.get() == owner) newPinOwner = new WeakReference<>(null);
            done.accept(saved);
        });
    }

    /** What Lock these settings says in each of the lock's states. */
    static void showLockRow(Row row, SettingsLock.State state) {
        switch (state) {
            case LOCKED:
                row.setTitle(L10n.t("Ask to unlock"));
                row.setSummary(L10n.f("These settings are locked. Tap to start the %1$s wait, then they open for 10 minutes.",
                        waitName(SettingsLock.hours())));
                break;
            case WAITING:
                row.setTitle(L10n.t("Unlock asked"));
                row.setSummary(L10n.f("These settings open at %1$s, for 10 minutes. Tap to stop the wait.",
                        lockTime(row.getContext())));
                break;
            case OPEN:
                row.setTitle(L10n.t("Settings are open"));
                row.setSummary(L10n.f("They lock again at %1$s. Tap to lock them now or turn the lock off.",
                        lockTime(row.getContext())));
                break;
            default:
                row.setTitle(L10n.t("Lock these settings"));
                row.setSummary(L10n.t("Pick a wait from 1 to 72 hours. Locked, nothing here changes until you "
                        + "ask to unlock and the wait is over."));
        }
        // Ask to unlock acts at once. Otherwise a tap opens a choice: the waits, or stopping one.
        row.actsAtOnce = state == SettingsLock.State.LOCKED;
    }

    /** A lock's wait in words: "1 hour", "6 hours". */
    static String waitName(int hours) {
        return L10n.quantity(hours, "%1$d hour", "%1$d hours", hours);
    }

    /**
     * When the wait or the open time ends, as the time of day, with the date too when that isn't
     * today.
     */
    static String lockTime(Context context) {
        long now = SettingsLock.clocks.wall();
        long at = now + SettingsLock.left();
        Locale locale = L10n.locale(context);
        Calendar today = Calendar.getInstance(locale);
        today.setTimeInMillis(now);
        Calendar then = Calendar.getInstance(locale);
        then.setTimeInMillis(at);
        boolean sameDay = today.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && today.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        DateFormat format = sameDay ? DateFormat.getTimeInstance(DateFormat.SHORT, locale)
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale);
        return L10n.isolate(format.format(new Date(at)));
    }

    /**
     * A tap on the lock's row. Off or open, it offers the waits (and while open, turning the lock
     * off). Locked, it starts the wait. Waiting, it says when the settings open and offers to stop
     * the wait, which keeps them locked.
     */
    void tapLock() {
        Context context = getContext();
        if (context == null) return;
        SettingsLock.State state = SettingsLock.state();
        if (state == SettingsLock.State.LOCKED) {
            if (SettingsLock.ask()) Utils.showToastLong(L10n.f("These settings open at %1$s, for 10 minutes.", lockTime(context)));
            else changeFailed();
            showLock();
            return;
        }
        if (state == SettingsLock.State.WAITING) {
            show(new AlertDialog.Builder(themed(context))
                    .setTitle(L10n.t("Unlock asked"))
                    .setMessage(L10n.f("These settings open at %1$s, for 10 minutes.", lockTime(context)))
                    .setPositiveButton(L10n.t("Stop the wait"), (shown, which) -> {
                        if (SettingsLock.stopWait()) Utils.showToastLong(L10n.t("Wait stopped. These settings stay locked."));
                        else changeFailed();
                        showLock();
                    })
                    .setNegativeButton(L10n.t("Keep waiting"), null), false);
            return;
        }
        boolean open = state == SettingsLock.State.OPEN;
        int[] waits = SettingsLock.WAITS;
        CharSequence[] names = new CharSequence[waits.length + (open ? 1 : 0)];
        for (int i = 0; i < waits.length; i++) names[i] = waitName(waits[i]);
        if (open) names[waits.length] = L10n.t("Turn the lock off");
        show(new AlertDialog.Builder(themed(context))
                .setTitle(L10n.t("Lock these settings"))
                .setItems(names, (shown, which) -> {
                    if (which < waits.length) {
                        if (SettingsLock.lock(waits[which])) Utils.showToastLong(L10n.t("Settings locked"));
                        else if (SettingsLock.locked()) sayLocked();
                        else changeFailed();
                    } else if (SettingsLock.off()) {
                        Utils.showToastLong(L10n.t("Lock turned off"));
                    } else changeFailed();
                    showLock();
                })
                .setNegativeButton(L10n.t("Cancel"), null), false);
    }

    /** Says why a change didn't happen while the settings are locked. */
    private static void sayLocked() {
        Utils.showToastLong(L10n.t("These settings are locked. Ask to unlock first."));
    }

    private static void changeFailed() {
        Utils.showToastLong(L10n.t("The setting couldn't finish updating. Its saved value is shown."));
    }

    /**
     * Puts what a search is most likely after first: a row whose title holds every term comes
     * before the rows that only mention it, and a section with such a row before the others.
     * "Start tab" used to list most of the Tab bar page ahead of Start tab itself, since each
     * summary there says "Restart Instagram" (S25, 2026-10-13). What matches stays as it was,
     * anywhere in a word, because German and Korean write compounds as one word. Rows only ever
     * move down, so Search stays first, and with no terms everything is back where it was built.
     */
    private void rankForSearch(String[] terms) {
        if (terms.length == 0) {
            restoreOrders();
            return;
        }
        for (Map.Entry<PreferenceCategory, List<Preference>> section : searchableRows.entrySet()) {
            boolean named = holdsAll(searchText(section.getKey().getTitle()), terms);
            for (Preference row : section.getValue()) {
                boolean inTitle = holdsAll(searchText(row.getTitle()), terms);
                named |= inTitle;
                rank(row, !inTitle);
            }
            rank(section.getKey(), !named);
        }
    }

    /**
     * Puts back every order a search moved. It goes by what was moved, not by what a search can
     * still find, so a row that left the search doesn't keep its moved place.
     */
    private void restoreOrders() {
        for (Map.Entry<Preference, Integer> moved : builtOrders.entrySet()) {
            if (moved.getKey().getOrder() != moved.getValue()) moved.getKey().setOrder(moved.getValue());
        }
        builtOrders.clear();
    }

    private void rank(Preference item, boolean later) {
        Integer built = builtOrders.get(item);
        if (built == null) {
            if (!later) return;
            built = item.getOrder();
            builtOrders.put(item, built);
        }
        int order = later ? built + RANKED_LATER : built;
        if (item.getOrder() != order) item.setOrder(order);
    }

    private static boolean holdsAll(String text, String[] terms) {
        for (String term : terms) if (!text.contains(term)) return false;
        return true;
    }

    /** Every row in the categories, on the screen or kept off it by a folded category or a search. */
    private List<Preference> everyRow() {
        List<Preference> rows = new ArrayList<>();
        for (List<Preference> section : searchableRows.values()) rows.addAll(section);
        return rows;
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

    void showConfiguration() {
        refreshFontRows();
        boolean busy = changingConfiguration || documentRequest != 0 || ExportStatus.CONFIGURATION.active() || Fonts.importActive();
        showSaveTree(busy);
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
        filterSettings();
    }

    private void refreshFontRows() {
        boolean busy = Fonts.importActive() || documentRequest != 0;
        for (String key : new String[]{Settings.FONT.key, Settings.STORY_FONTS.key, Settings.STORY_EMOJI.key, STORY_FONTS_ROW,
                Settings.EMOJI_FONT.key}) {
            Preference row = findPreference(key);
            if (row == null) continue;
            Setting<?> setting = Setting.getSettingFromPath(key);
            if (setting == null) row.setEnabled(!busy);
            else updatePreferenceAvailability(row, setting);
            if (busy) row.setEnabled(false);
            holdLocked(row);
        }
    }

    void pickConfiguration(boolean importing) {
        if (documentRequest != 0 || changingConfiguration || ExportStatus.CONFIGURATION.active()) return;
        if (importing && SettingsLock.locked()) {
            sayLocked();
            return;
        }
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
        if (!importing) picker.putExtra(Intent.EXTRA_TITLE,
                ConfigurationBackup.fileName(new Date(), Settings.DATE_SETTINGS_EXPORT.savedValue()));
        try {
            startActivityForResult(picker, documentCode);
        } catch (ActivityNotFoundException | SecurityException failure) {
            documentRequest = 0;
            documentCode = 0;
            fontRequestRevision = -1;
            if (!importing) ExportStatus.CONFIGURATION.finish(configurationExportToken,
                    L10n.t("This phone has no file picker. Your settings haven't changed."));
            showConfiguration();
            Utils.showToastLong(L10n.t("This phone has no file picker. Your settings haven't changed."));
        }
    }

    @Override
    public void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != documentCode) return;
        int request = documentRequest;
        if (isFontRequest(request)) {
            long revision = fontRequestRevision;
            documentRequest = 0;
            documentCode = 0;
            fontRequestRevision = -1;
            showConfiguration();
            if (result == Activity.RESULT_OK) keepPickedFont(request, data == null ? null : data.getData(), revision);
            return;
        }
        if (request == PICK_SAVE_FOLDER) {
            documentRequest = 0;
            documentCode = 0;
            if (result == Activity.RESULT_OK) keepSaveFolder(data == null ? null : data.getData());
            showConfiguration();
            return;
        }
        if (request != EXPORT_CONFIGURATION && request != IMPORT_CONFIGURATION) return;
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
                    configurationExportToken, L10n.t("Settings export canceled."));
            showConfiguration();
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) {
            if (request == EXPORT_CONFIGURATION) ExportStatus.CONFIGURATION.finish(configurationExportToken,
                    L10n.t("Couldn't use that settings file. Your settings haven't changed."));
            Utils.showToastLong(L10n.t("Couldn't use that settings file. Your settings haven't changed."));
            showConfiguration();
            return;
        }
        if (request == IMPORT_CONFIGURATION) changeConfiguration(uri, 0);
        else exportConfiguration(uri);
    }

    /**
     * Opens Android's file picker for a font file, for [request]: the Font row's or Your story fonts'.
     * Nothing changes until a file is picked and loads as a font.
     */
    private void pickFont(int request) {
        if (documentRequest != 0 || changingConfiguration || ExportStatus.CONFIGURATION.active() || Fonts.importActive()) return;
        if (!allowMutation()) return;
        if (documentSequence == Integer.MAX_VALUE) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
            return;
        }
        fontRequestRevision = fontSetting(request).savedRevision();
        documentRequest = request;
        documentCode = ++documentSequence;
        showConfiguration();
        // Few file providers give .ttf and .otf a type, so the file itself is checked instead.
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(picker, documentCode);
        } catch (ActivityNotFoundException | SecurityException failure) {
            documentRequest = 0;
            documentCode = 0;
            fontRequestRevision = -1;
            showConfiguration();
            Utils.showToastLong(L10n.t("This phone has no file picker. Your settings haven't changed."));
        }
    }

    /** Choose a folder (#333): Android's folder picker, starting in the folder picked before. */
    Row saveTreeRow(Context context) {
        Row row = new Row(context);
        row.setKey(Settings.SAVE_TREE.key);
        row.setPersistent(false);
        row.actsAtOnce = true;
        row.setTitle(L10n.t("Choose a folder"));
        row.setOnPreferenceClickListener(p -> {
            pickSaveFolder();
            return true;
        });
        saveTree = row;
        return row;
    }

    /** Use Movies and Pictures: forgets the picked folder, there only while one is picked. */
    Row galleryFoldersRow(Context context) {
        Row row = new Row(context);
        row.setKey(GALLERY_FOLDERS);
        row.setPersistent(false);
        row.actsAtOnce = true;
        row.setTitle(L10n.t("Use Movies and Pictures"));
        row.setSummary(L10n.t("Stop using the folder you picked. Saves go back to Movies and Pictures."));
        row.setOnPreferenceClickListener(p -> {
            if (!allowMutation() || !SaveTree.picked()) return true;
            SaveTree.forget(p.getContext());
            showConfiguration();
            Utils.showToastShort(L10n.t("Saves go to Movies and Pictures again."));
            return true;
        });
        galleryFolders = row;
        return row;
    }

    static final String GALLERY_FOLDERS = "hushgram_gallery_folders";

    private void pickSaveFolder() {
        if (documentRequest != 0 || changingConfiguration || ExportStatus.CONFIGURATION.active() || Fonts.importActive()) return;
        if (!allowMutation()) return;
        if (documentSequence == Integer.MAX_VALUE) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
            return;
        }
        documentRequest = PICK_SAVE_FOLDER;
        documentCode = ++documentSequence;
        showConfiguration();
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        Uri current = SaveTree.saved();
        if (current != null) picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, SaveTree.root(current));
        try {
            startActivityForResult(picker, documentCode);
        } catch (ActivityNotFoundException | SecurityException failure) {
            documentRequest = 0;
            documentCode = 0;
            showConfiguration();
            Utils.showToastLong(L10n.t("This phone has no file picker. Your settings haven't changed."));
        }
    }

    /** The picker's answer [tree]: kept, and said where saves go now, or said it couldn't be. */
    private void keepSaveFolder(@Nullable Uri tree) {
        Context context = getContext();
        if (context == null || !allowMutation()) return;
        if (!SaveTree.keep(context, tree)) {
            Utils.showToastLong(L10n.t("Couldn't use that folder. Saves still go where they did."));
            return;
        }
        Utils.showToastShort(L10n.f("Folder set to %1$s.", L10n.isolate(SaveTree.label(context, tree))));
    }

    /** What the two folder rows say and allow now, for the picked folder or none. */
    private void showSaveTree(boolean busy) {
        Row row = saveTree;
        if (row != null) {
            Context context = row.getContext();
            Uri picked = SaveTree.saved();
            row.setEnabled(!busy);
            if (!SaveTree.picked()) {
                row.setSummary(L10n.t("Saves go to Movies and Pictures. Pick any folder instead, a hidden one too, "
                        + "and every photo and video goes there."));
            } else if (SaveTree.reachable(context)) {
                row.setSummary(L10n.f("Photos and videos go to %1$s. Tap to pick another folder.",
                        L10n.isolate(SaveTree.label(context, picked))));
            } else {
                row.setSummary(SaveTree.lostMessage(context, picked));
            }
        }
        if (galleryFolders != null) galleryFolders.setEnabled(!busy && SaveTree.picked());
    }

    private static boolean isFontRequest(int request) {
        return request == PICK_FONT || request == PICK_STORY_FONT || request == PICK_STORY_EMOJI || request == PICK_EMOJI;
    }

    private static Setting<?> fontSetting(int request) {
        return request == PICK_FONT ? Settings.FONT : request == PICK_STORY_FONT ? Settings.STORY_FONTS
                : request == PICK_EMOJI ? Settings.EMOJI_FONT : Settings.STORY_EMOJI;
    }

    /** UNREACHED: an emoji pack that couldn't be downloaded, or wasn't the exact file it names. */
    private enum FontVerdict { SAVED, INVALID, UNREACHED, HELD, STALE, SAVE_FAILED }

    private static final class FontResult {
        final int request;
        final FontVerdict verdict;
        @Nullable final String name;

        FontResult(int request, FontVerdict verdict, @Nullable String name) {
            this.request = request;
            this.verdict = verdict;
            this.name = name;
        }
    }

    /** The provider owns no live file. Its successful close precedes every installation. */
    private void keepPickedFont(int request, @Nullable Uri uri, long revision) {
        Context context = getContext();
        if (context == null || !allowMutation()) return;
        if (uri == null || !"content".equals(uri.getScheme())) {
            fontFinished(new FontResult(request, FontVerdict.INVALID, null));
            return;
        }
        if (MessagesLock.locked()) {
            Activity activity = getActivity();
            if (activity != null) MessagesLock.confirmThen(activity, () -> keepPickedFont(request, uri, revision));
            return;
        }
        long token = Fonts.beginImport();
        if (token == 0) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
            return;
        }
        Context application = context.getApplicationContext() == null ? context : context.getApplicationContext();
        long expectedRevision = revision < 0 ? fontSetting(request).savedRevision() : revision;
        showConfiguration();
        if (!Utils.runOnBackgroundThread(() -> {
            Fonts.Prepared prepared = null;
            FontResult result;
            try {
                String shown = null;
                try (java.io.InputStream input = application.getContentResolver().openInputStream(uri)) {
                    if (input != null) {
                        prepared = request == PICK_FONT ? Fonts.prepare(application, input)
                                : request == PICK_STORY_FONT ? StoryFonts.prepare(application, input)
                                : request == PICK_EMOJI ? EmojiFonts.prepare(application, input)
                                : StoryEmoji.prepare(application, input);
                        if (request == PICK_STORY_FONT) shown = shownName(application, uri);
                    }
                }
                result = prepared == null ? new FontResult(request, FontVerdict.INVALID, null)
                        : installPickedFont(application, request, EmojiChoice.FILE, token, expectedRevision, prepared, shown);
            } catch (Exception failure) {
                Logger.printInfo(() -> "Font: couldn't finish reading the picked file", failure);
                result = new FontResult(request, FontVerdict.INVALID, null);
            } finally {
                if (prepared != null) prepared.close();
                Fonts.finishImport(token);
            }
            FontResult finished = result;
            Utils.runOnMainThread(() -> fontFinished(finished));
        })) {
            Fonts.finishImport(token);
            showConfiguration();
            Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
        }
    }

    /** [emoji] is what the Emoji pack row saves for PICK_EMOJI: the pack downloaded, or FILE for a picked file. */
    private static FontResult installPickedFont(Context context, int request, EmojiChoice emoji, long token, long revision,
                                                Fonts.Prepared prepared, @Nullable String shown) {
        if (!asyncChangesAllowed()) return new FontResult(request, FontVerdict.HELD, null);
        if (request == PICK_STORY_FONT) {
            synchronized (StoryFonts.class) {
                String name = StoryFonts.prepareInstall(context, prepared, shown);
                return name == null ? new FontResult(request, FontVerdict.INVALID, null)
                        : commitPickedFont(request, emoji, token, revision, prepared, name);
            }
        }
        boolean ready = request == PICK_FONT ? Fonts.prepareInstall(context, prepared)
                : request == PICK_EMOJI ? EmojiFonts.prepareInstall(context, prepared)
                : StoryEmoji.prepareInstall(context, prepared);
        return ready ? commitPickedFont(request, emoji, token, revision, prepared, null)
                : new FontResult(request, FontVerdict.INVALID, null);
    }

    /** Authorization and the setting revision are read again at the actual transaction boundary. */
    private static FontResult commitPickedFont(int request, EmojiChoice emoji, long token, long revision,
                                               Fonts.Prepared prepared, @Nullable String name) {
        synchronized (Setting.class) {
            if (!asyncChangesAllowed()) return new FontResult(request, FontVerdict.HELD, null);
            Setting<?> setting = fontSetting(request);
            if (!Fonts.ownsImport(token) || setting.savedRevision() != revision) {
                return new FontResult(request, FontVerdict.STALE, null);
            }
            if (!prepared.recordSetting(setting, request == PICK_FONT ? FontChoice.FILE : request == PICK_EMOJI ? emoji : true)) {
                return new FontResult(request, FontVerdict.SAVE_FAILED, null);
            }
            if (!prepared.install()) {
                prepared.rollback();
                return new FontResult(request, FontVerdict.SAVE_FAILED, null);
            }
            Object before = setting.savedValue();
            AbstractPreferenceFragment.noteRunningValue(setting, before);
            boolean saved = request == PICK_FONT ? Settings.FONT.save(FontChoice.FILE)
                    : request == PICK_STORY_FONT ? Settings.STORY_FONTS.save(true)
                    : request == PICK_EMOJI ? Settings.EMOJI_FONT.save(emoji) : Settings.STORY_EMOJI.save(true);
            if (!saved) {
                prepared.rollback();
                return new FontResult(request, FontVerdict.SAVE_FAILED, null);
            }
            if (!prepared.accept()) {
                if (prepared.rollback()) {
                    // This live transaction still owns Setting.class; no later choice can be lost.
                    boolean restored = request == PICK_FONT ? Settings.FONT.save((FontChoice) before)
                            : request == PICK_STORY_FONT ? Settings.STORY_FONTS.save((Boolean) before)
                            : request == PICK_EMOJI ? Settings.EMOJI_FONT.save((EmojiChoice) before)
                            : Settings.STORY_EMOJI.save((Boolean) before);
                    if (!restored) Logger.printException(() -> "Font: couldn't restore the previous choice");
                }
                return new FontResult(request, FontVerdict.SAVE_FAILED, null);
            }
            if (request == PICK_FONT) Fonts.fileInstalled();
            else if (request == PICK_STORY_FONT) StoryFonts.installed();
            else if (request == PICK_EMOJI) EmojiFonts.installed();
            return new FontResult(request, FontVerdict.SAVED, name);
        }
    }

    private void fontFinished(FontResult result) {
        if (result.verdict == FontVerdict.HELD) Utils.showToastLong(heldMessage());
        else if (result.verdict == FontVerdict.STALE || result.verdict == FontVerdict.SAVE_FAILED) {
            Utils.showToastShort(L10n.t("The setting couldn't finish updating. Its saved value is shown."));
        } else if (result.verdict == FontVerdict.UNREACHED) {
            Utils.showToastLong(L10n.t("Couldn't download the emoji. Try again."));
        } else if (result.verdict == FontVerdict.INVALID) {
            Utils.showToastLong(result.request == PICK_FONT
                    ? L10n.t("That file isn't a font HushGram can use. Your font hasn't changed.")
                    : result.request == PICK_STORY_FONT
                    ? L10n.t("That file isn't a font HushGram can use. Nothing was added.")
                    : result.request == PICK_EMOJI
                    ? L10n.t("No color emoji in that file. Nothing changed.")
                    : L10n.t("That file isn't a font HushGram can use. Story emoji haven't changed."));
        } else if (result.request == PICK_EMOJI) {
            Utils.showToastLong(L10n.t("Emoji saved. Restart Instagram to see them."));
        } else if (result.request == PICK_FONT) {
            Utils.showToastLong(L10n.t("Font saved. Restart Instagram to see it."));
        } else if (result.request == PICK_STORY_FONT) {
            Utils.showToastLong(L10n.f("Added %1$s. Pick it in the story text editor alongside Instagram's fonts.",
                    L10n.isolate(result.name)));
            if (isAdded() && getView() != null && !Fonts.importActive() && documentRequest == 0 && asyncChangesAllowed()) showStoryFonts();
        } else Utils.showToastLong(L10n.t("Emoji font saved. Emoji in story text draw in it."));
        if (getView() != null && isAdded()) {
            updateUIToSettingValues();
            showConfiguration();
            showRestart();
        }
    }

    void exportConfiguration(Uri uri) {
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
            } finally {
                // An Error past the catch still ends the export, or every settings action stays busy until a restart.
                if (ExportStatus.CONFIGURATION.finish(token, L10n.t("Couldn't export HushGram settings. Try another file."))) {
                    Utils.showToastLong(L10n.t("Couldn't export HushGram settings. Try another file."));
                }
            }
        })) {
            ExportStatus.CONFIGURATION.finish(token, L10n.t("Couldn't start that. Try again."));
            configurationQueueFull();
        }
    }

    /** Undo always remains Undo, even if its expiration callback hasn't reached the screen yet. */
    private void changeConfiguration(@Nullable Uri uri, long token) {
        if (changingConfiguration || ExportStatus.CONFIGURATION.active()) return;
        // A file or an Undo arriving after the settings locked again (#301) changes nothing.
        if (SettingsLock.locked()) {
            showConfiguration();
            sayLocked();
            return;
        }
        // These settings can outlast the unlock that opened them, and the file picker's own screen
        // is a leave that can lock again. A file or an Undo arriving while locked waits for a new
        // unlock; a cancel changes nothing.
        if (MessagesLock.locked()) {
            // The picker's request is spent, so the rows are offered again whatever the answer.
            showConfiguration();
            Activity activity = getActivity();
            if (activity != null) MessagesLock.confirmThen(activity, () -> changeConfiguration(uri, token));
            return;
        }
        boolean undo = uri == null;
        Context context = getContext();
        if (context == null) return;
        changingConfiguration = true;
        showConfiguration();
        if (!Utils.runOnBackgroundThread(() -> {
            try {
                ConfigurationBackup.Result result;
                if (undo) result = ConfigurationBackup.undo(token, HushgramPreferenceFragment::asyncChangesAllowed);
                else {
                    byte[] bytes;
                    try (java.io.InputStream input = context.getContentResolver().openInputStream(uri)) {
                        bytes = ConfigurationBackup.read(input);
                    }
                    // A provider can fail while closing. Finish all document I/O before values
                    // change, so the failure verdict can truthfully say nothing was applied.
                    result = ConfigurationBackup.restore(bytes, HushgramPreferenceFragment::asyncChangesAllowed);
                }
                if (result == null) showImportFeedback(L10n.t("Undo has expired."));
                else {
                    String message = configurationReceipt(undo, result);
                    // A file or an Undo owes the same restart as flipping each row by hand, so Restart now
                    // shows for it too, and an Undo back to what's running takes it away again. The
                    // receipt asks for a restart only while one is owed, not for every restart choice
                    // the file touched.
                    Utils.runOnMainThread(() -> {
                        boolean owed = false;
                        for (var changed : result.before.entrySet()) owed |= noteRestartPending(changed.getKey(), changed.getValue());
                        showImportFeedback(owed ? message + " " + L10n.t("Restart Instagram to apply these choices.") : message);
                    });
                }
            } catch (ConfigurationBackup.Held held) {
                showImportFeedback(heldMessage());
            } catch (Setting.BatchFailed failure) {
                showImportFeedback(failure.restored
                        ? L10n.t("Couldn't save the settings. The previous values were restored.")
                        : undo ? L10n.t("Undo couldn't fully restore the settings. Check the shown values. Undo has "
                                + "been used up.")
                        : L10n.t("Couldn't save or fully restore the settings. Check the shown values and try Undo."));
            } catch (Exception failure) {
                Logger.printInfo(() -> "Configuration import failed before applying settings");
                showImportFeedback(L10n.t("Couldn't use that settings file. Your settings haven't changed."));
            } finally { configurationFinished(); }
        })) configurationQueueFull();
    }

    /**
     * What an import or an Undo reports, before any restart note. Lock switches a HushGram PIN (or a
     * lock that came back) kept as they are get their own sentence: they're known settings, and
     * counting them as ones this version doesn't know hid why they didn't change. Skipped settings
     * are only mentioned when there are some.
     */
    static String configurationReceipt(boolean undo, ConfigurationBackup.Result result) {
        String message = undo ? result.skipped == 0 ? L10n.t("Settings restored.")
                : L10n.f("Restored %1$d settings. Kept %2$d newer choices.", result.applied, result.skipped)
                : result.skipped == 0 ? L10n.f("Imported %1$d settings.", result.applied)
                : L10n.f("Imported %1$d settings. Skipped %2$d that this version doesn't know.", result.applied, result.skipped);
        return result.held == 0 ? message
                : message + " " + L10n.t("Lock switches stay as they are until you change them here.");
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
            if (isAdded() && getPreferenceScreen() != null) updateUIToSettingValues();
            showConfiguration();
        });
    }

    /** Clear remains available when playback is off or paused; it never reads media identities. */
    void showClearPositions() {
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
        if (changingPositions || !allowMutation()) return;
        // Honor the action the row offered. An expired Undo must never become another clear
        // while its delayed refresh is still waiting on the main thread.
        boolean undo = token != 0;
        changingPositions = true;
        showClearPositions();
        if (!Utils.runOnBackgroundThread(() -> {
            try {
                if (!asyncChangesAllowed()) {
                    Utils.showToastLong(heldMessage());
                    return;
                }
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
                Utils.showToastLong(L10n.t("Couldn't update the remembered playback positions."));
            } finally {
                Utils.runOnMainThread(() -> {
                    changingPositions = false;
                    showClearPositions();
                });
            }
        })) {
            changingPositions = false;
            showClearPositions();
            Utils.showToastLong(L10n.t("Couldn't update the remembered playback positions."));
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
            float density = context.getResources().getDisplayMetrics().density;
            int inset = Math.round(18 * density);
            int gap = Math.round(4 * density);
            android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
            for (boolean focused : new boolean[]{true, false}) {
                android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
                surface.setColor(ScreenColors.DEFAULT.card);
                surface.setCornerRadius(Math.round(10 * density));
                surface.setStroke(Math.round(density), focused ? ScreenColors.DEFAULT.heading : ScreenColors.DEFAULT.outline);
                states.addState(focused ? new int[]{android.R.attr.state_focused} : new int[]{},
                        new android.graphics.drawable.InsetDrawable(surface, inset, gap, inset, gap));
            }
            row.setBackground(states);
            row.setAddStatesFromChildren(true);
            row.setPaddingRelative(inset + Math.round(14 * density), gap, inset + Math.round(4 * density), gap);
            row.setMinimumHeight(touch + 2 * gap);
            // Its children own the accessible actions; the row only anchors ListView selection.
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Legacy ListView refocuses a row during layout. Keep focus on its typing field.
            row.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            EditText field = new EditText(context);
            field.setTag("hushgram-settings-search");
            field.setSaveEnabled(false);
            field.setSingleLine(true);
            field.setTextSize(16);
            ScreenColors.styleField(field);
            // The whole search row owns the surface, including its clear action.
            field.setBackground(null);
            field.setPaddingRelative(0, Math.round(10 * density), 0, Math.round(10 * density));
            field.setTextColor(ScreenColors.DEFAULT.title);
            field.setHintTextColor(ScreenColors.DEFAULT.summary);
            field.setHint(L10n.t("Search settings"));
            field.setContentDescription(L10n.t("Search settings"));
            // A magnifier at the field's start, as every row starts with an icon. A screen reader
            // already hears the field's name, and a compound drawable isn't read.
            android.graphics.drawable.Drawable glass = SettingsIcons.icon(context, SettingsIcons.SEARCH,
                    ScreenColors.DEFAULT.summary);
            int glassSize = Math.round(20 * context.getResources().getDisplayMetrics().density);
            glass.setBounds(0, 0, glassSize, glassSize);
            field.setCompoundDrawablesRelative(glass, null, null, null);
            field.setCompoundDrawablePadding(Math.round(12 * context.getResources().getDisplayMetrics().density));
            field.setMinimumHeight(touch);
            field.setText(searchQuery);
            SearchFocus focus = new SearchFocus(this, row);
            field.setAccessibilityDelegate(focus);
            row.addOnAttachStateChangeListener(focus);
            row.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button clear = new Button(context);
            clear.setText("");
            clear.setAllCaps(false);
            clear.setTextSize(24);
            clear.setTextColor(ScreenColors.DEFAULT.heading);
            android.graphics.drawable.Drawable cross = SettingsIcons.icon(context, SettingsIcons.CLOSE,
                    ScreenColors.DEFAULT.heading);
            int crossSize = Math.round(20 * density);
            cross.setBounds(0, 0, crossSize, crossSize);
            clear.setCompoundDrawablesRelative(cross, null, null, null);
            ScreenColors.styleIconAction(clear);
            clear.setContentDescription(L10n.t("Clear search"));
            clear.setTooltipText(L10n.t("Clear search"));
            int crossPadding = (touch - crossSize) / 2;
            clear.setPaddingRelative(crossPadding, 0, crossPadding, 0);
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
        card.setKey("hushgram_status");
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
                // Its own line: two translations joined by a space can read as one broken sentence.
                + "\n\n" + L10n.t("Tap to hide this."));
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
        holdLocked(pause);
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
        showRestart();
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

    /**
     * The screen's Restart now. A save or an export still running keeps Instagram up and says so.
     * Instagram comes back with the screen open on this page.
     */
    void restartNow() {
        if (Fonts.importActive()) {
            Utils.showToastLong(L10n.t("A save or an export is still running. Restart once it's done."));
            return;
        }
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        switch (RestartNow.restart(activity, currentPage())) {
            case BUSY:
                Utils.showToastLong(L10n.t(activity, "A save or an export is still running. Restart once it's done."));
                break;
            case FAILED:
                Utils.showToastLong(L10n.t(activity, "Couldn't restart Instagram. Close it and open it again."));
                break;
            default:
                break;
        }
    }

    /** The last expanded section to reveal after restarting, or the top of the main list. */
    String currentPage() {
        return categoryPages() && expandedCategories.contains(lastCategory) ? lastCategory : "";
    }

    /**
     * The shared fragment's "restart to apply" feedback while this page is up. When the screen shows
     * its Restart now bar (#146), the bar is what to tap, so a screen reader hears the message and
     * no toast covers the page. Otherwise (HushGram paused now and at the next start too, so a
     * restart would apply nothing) the toast still says it, since nothing on the screen does.
     */
    private final AbstractPreferenceFragment.RestartFeedbackPresenter restartFeedback = (shown, message) -> {
        ListView list = listView();
        if (restartActionAvailable() && list != null) {
            list.announceForAccessibility(message);
        } else {
            Utils.showToastLong(message.toString());
        }
    };

    @Override
    protected void onRestartPendingChanged() {
        Preference card = statusCard;
        Context context = getContext();
        if (card != null && context != null) showStatus(card, context);
        showRestart();
    }

    /**
     * Whether Restart now is offered. Pausing HushGram or turning it back on changes what the next
     * start runs, so that alone offers it (#146: flipping Pause offered nothing to tap). While the
     * pause holds through the next start too, a restart applies nothing and the bar stays off.
     */
    boolean restartActionAvailable() {
        Context context = getContext();
        if (context == null) return false;
        boolean pausedNext = HushgramPause.pausesNextStart(context);
        if (pausedNext != HushgramPause.isPaused()) return true;
        return !pausedNext && (RestartNow.owed() || Fonts.restartPending() || EmojiFonts.restartPending());
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

    /**
     * A section of the page; the icon its row on the list of categories, and its rows without one
     * of their own, lead with; and the line under its expandable heading, or null for a section
     * that never folds.
     */
    static PreferenceCategory category(PreferenceScreen screen, String title, String icon,
                                               @Nullable String intro) {
        Heading category = new Heading(screen.getContext());
        category.setTitle(title);
        category.icon = icon;
        category.intro = intro;
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
            if (PinLock.protects()) {
                // With a PIN, turning a lock off always asks it, and with nothing to ask it stays on.
                if (activity != null) {
                    MessagesLock.confirmPinThen(activity, () -> saveDeferred(preference, setting, false));
                }
                return false;
            }
            if (!MessagesLock.locked() || activity == null) return true;
            MessagesLock.confirmThen(activity, () -> saveDeferred(preference, setting, false));
            return false;
        });
        return row;
    }

    /**
     * How long after you leave Instagram the locks lock again; its summary says what the choice does.
     * With a PIN behind the locks, a longer wait weakens them, so it asks the PIN every time, as
     * switching a lock off does, even in a session already unlocked (audit 2026-10-12). Nothing is
     * saved until the PIN is right, and a shorter wait takes effect at once.
     */
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
        row.setOnPreferenceChangeListener((preference, value) -> {
            LockDelay target = LockDelayRow.delayOf(value);
            LockDelayRow delayRow = (LockDelayRow) preference;
            if (target.millis <= LockDelayRow.delayOf(delayRow.getValue()).millis || !PinLock.protects()) return true;
            Activity activity = activityOf(preference.getContext());
            if (activity != null) MessagesLock.confirmPinThen(activity, () -> saveDeferred(delayRow, Settings.LOCK_AGAIN, target));
            return false;
        });
        return row;
    }

    /**
     * What unlocks the messages: the phone's lock, a HushGram PIN, or on Android 11 and up the
     * fingerprint or face alone. Choosing the PIN asks for one first when none is set, choosing the
     * fingerprint or face is refused while the phone has none, and a change while the messages are
     * locked asks the current lock, so the choice can't be used to get around it.
     */
    static UnlockWithRow unlockWithRow(Context context) {
        UnlockWithRow row = new UnlockWithRow(context);
        row.setKey(Settings.UNLOCK_WITH.key);
        row.setTitle(L10n.t("Unlock with"));
        row.setDialogTitle(L10n.t("Unlock with"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        if (Build.VERSION.SDK_INT >= 30) {
            row.setEntries(new CharSequence[]{L10n.t("Phone lock"), L10n.t("HushGram PIN"), L10n.t("Fingerprint or face only")});
            row.setEntryValues(new CharSequence[]{UnlockWith.PHONE_LOCK.name(), UnlockWith.PIN.name(), UnlockWith.BIOMETRICS.name()});
        } else {
            row.setEntries(new CharSequence[]{L10n.t("Phone lock"), L10n.t("HushGram PIN")});
            row.setEntryValues(new CharSequence[]{UnlockWith.PHONE_LOCK.name(), UnlockWith.PIN.name()});
        }
        row.setValue(Settings.UNLOCK_WITH.savedValue().name());
        row.setOnPreferenceChangeListener((preference, value) -> {
            UnlockWith target = UnlockWith.PIN.name().equals(value) ? UnlockWith.PIN
                    : UnlockWith.BIOMETRICS.name().equals(value) ? UnlockWith.BIOMETRICS : UnlockWith.PHONE_LOCK;
            if (target == UnlockWith.BIOMETRICS && !MessagesLock.biometricsReady(preference.getContext())) {
                Utils.showToastLong(noBiometrics(MessagesLock.biometricsState(preference.getContext())));
                return false;
            }
            Activity activity = activityOf(preference.getContext());
            if (activity == null) return !PinLock.protects();
            UnlockWithRow unlockRow = (UnlockWithRow) preference;
            Runnable change = () -> {
                if (!allowMutation(unlockRow, true)) return;
                if (target == UnlockWith.PIN && !PinLock.isSet()) {
                    promptNewPin(unlockRow, activity, saved -> {
                        if (saved) saveDeferred(unlockRow, Settings.UNLOCK_WITH, target);
                    });
                } else {
                    saveDeferred(unlockRow, Settings.UNLOCK_WITH, target);
                }
            };
            MessagesLock.confirmPinThen(activity, change);
            return false;
        });
        return row;
    }

    /** Why Fingerprint or face only can't be chosen, from BiometricManager's [state]. */
    static String noBiometrics(int state) {
        if (state == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            return L10n.t("Add a fingerprint or face in your phone's settings first.");
        }
        if (state == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
            return L10n.t("This phone has no fingerprint or face unlock to use.");
        }
        return L10n.t("Your phone won't let HushGram use your fingerprint or face right now. Check its fingerprint and face settings.");
    }

    /** The row that sets or changes the HushGram PIN; changing it while locked asks the current lock first. */
    static Row changePinRow(Context context) {
        Row row = new Row(context);
        row.setKey(CHANGE_PIN_ROW);
        row.setPersistent(false);
        row.setTitle(L10n.t("HushGram PIN"));
        row.setSummary(L10n.t("Set or change the 4 to 8 digit PIN. It stays on this phone and isn't in a backup."));
        row.setOnPreferenceClickListener(tapped -> {
            Activity activity = activityOf(tapped.getContext());
            if (activity == null) return true;
            Runnable prompt = () -> promptNewPin(row, activity, saved -> {
                if (saved) Utils.showToastShort(L10n.t("PIN saved."));
            });
            // Changing the PIN asks the current one; with no PIN yet there's nothing to ask.
            MessagesLock.confirmPinThen(activity, prompt);
            return true;
        });
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
    Row lockedChatsRow(Context context) {
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
     * The locked chats of the account you're signed in to, each with a check, and the chat you
     * opened last on it above them when it isn't on the list. Checking one locks it at once;
     * unchecking one asks the phone's lock first while anything is locked, so the list can't be used
     * to get around it. Every change is for the account the list was opened on, and none is offered
     * while that account isn't known.
     */
    private void showLockedChats() {
        Context context = getActivity();
        if (context == null) return;
        String account = ChatAccounts.active();
        List<ChatLocks.Chat> chats = new ArrayList<>(ChatLocks.chats(account));
        ChatLocks.Chat last = ChatLocks.lastOpened(account);
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
                    lockChat((AlertDialog) dialog, account, chats.get(which), which, checked));
        }
        show(builder.setPositiveButton(L10n.t("OK"), null));
    }

    private void lockChat(AlertDialog dialog, String account, ChatLocks.Chat chat, int position, boolean lock) {
        if (!allowMutation()) {
            setChecked(dialog, position, !lock);
            return;
        }
        if (lock) {
            if (!ChatLocks.add(account, chat.id, chat.name)) {
                notSaved(dialog, position, false);
                return;
            }
            MessagesLock.chatListed();
            Utils.showToastShort(L10n.f("%1$s is locked", L10n.isolate(chat.name)));
            return;
        }
        Runnable unlock = () -> {
            if (!allowMutation()) {
                setChecked(dialog, position, true);
                return;
            }
            if (!ChatLocks.remove(account, chat.id)) {
                notSaved(dialog, position, true);
                return;
            }
            Utils.showToastShort(L10n.f("%1$s is unlocked", L10n.isolate(chat.name)));
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
    Row hiddenChatsRow(Context context) {
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
     * The hidden chats of the account you're signed in to, each with a check, and the chat you
     * opened last on it above them when it isn't hidden. Checking one hides it at once; unchecking
     * one shows it again, on that account only. Paused, the list still shows what you chose, though
     * Instagram shows every chat until HushGram is back on. None is offered while the account isn't
     * known.
     */
    private void showHiddenChats() {
        Context context = getActivity();
        if (context == null) return;
        show(hiddenChatsList(context, this::allowMutation));
    }

    /**
     * The same list over whatever Instagram screen [activity] shows, for two quick taps on Messages
     * (#358). Nothing on the list changes while these settings are locked. The caller has already
     * asked the phone's lock when a messages lock is on.
     */
    public static void showHiddenChatsOver(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog dialog = hiddenChatsList(activity, () -> {
            if (!SettingsLock.locked()) return true;
            sayLocked();
            return false;
        }).show();
        ScreenColors.dialog(dialog);
    }

    /** Builds the hidden chats list, asking [allowMutation] before each check changes. */
    private static AlertDialog.Builder hiddenChatsList(Context context, BooleanSupplier allowMutation) {
        String account = ChatAccounts.active();
        List<ChatLocks.Chat> chats = new ArrayList<>(HiddenChats.saved(account));
        ChatLocks.Chat last = HiddenChats.lastOpened(account);
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
                if (!allowMutation.getAsBoolean()) {
                    setChecked((AlertDialog) dialog, which, !checked);
                    return;
                }
                ChatLocks.Chat chat = chats.get(which);
                if (checked) {
                    if (!HiddenChats.add(account, chat.id, chat.name)) {
                        notSaved((AlertDialog) dialog, which, false);
                        return;
                    }
                    Utils.showToastShort(L10n.f("%1$s is hidden", L10n.isolate(chat.name)));
                } else {
                    if (!HiddenChats.remove(account, chat.id)) {
                        notSaved((AlertDialog) dialog, which, true);
                        return;
                    }
                    Utils.showToastShort(L10n.f("%1$s is shown again", L10n.isolate(chat.name)));
                }
            });
        }
        return builder.setPositiveButton(L10n.t("OK"), null);
    }

    /** The row that opens the list of chats pinned to the top of your inbox (#345). */
    Row pinnedChatsRow(Context context) {
        Row row = new Row(context);
        row.setKey(PINNED_CHATS_ROW);
        row.setPersistent(false);
        row.setTitle(L10n.t("Pinned chats"));
        row.setSummary(L10n.t("Keeps the chats you pick at the top of your inbox. The pins stay on this phone, "
                + "and Instagram's own order holds for every other chat."));
        row.setOnPreferenceClickListener(tapped -> {
            Context host = getActivity();
            if (host == null) return true;
            show(pinnedChatsList(host, this::allowMutation));
            return true;
        });
        return row;
    }

    /**
     * The chats pinned on the account you're signed in to, each with a check, and the chat you opened
     * last above them when it isn't pinned. Checking one pins it at once, below the ones pinned
     * before it; unchecking one puts it back where Instagram has it. Each account keeps its own pins.
     */
    private static AlertDialog.Builder pinnedChatsList(Context context, BooleanSupplier allowMutation) {
        String account = ChatAccounts.active();
        List<ChatLocks.Chat> chats = new ArrayList<>(PinnedChats.saved(account));
        ChatLocks.Chat last = PinnedChats.lastOpened(account);
        int offered = last == null ? 0 : 1;
        if (last != null) chats.add(0, last);
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Pinned chats"));
        if (chats.isEmpty()) {
            builder.setMessage(L10n.t("Open a chat in Instagram, then come back here to pin it."));
        } else {
            CharSequence[] names = new CharSequence[chats.size()];
            boolean[] pinned = new boolean[chats.size()];
            for (int i = 0; i < names.length; i++) {
                boolean offer = i < offered;
                names[i] = offer ? L10n.f("%1$s (opened last)", chats.get(i).name) : chats.get(i).name;
                pinned[i] = !offer;
            }
            builder.setMultiChoiceItems(names, pinned, (dialog, which, checked) -> {
                if (!allowMutation.getAsBoolean()) {
                    setChecked((AlertDialog) dialog, which, !checked);
                    return;
                }
                ChatLocks.Chat chat = chats.get(which);
                if (checked) {
                    if (!PinnedChats.add(account, chat.id, chat.name)) {
                        notSaved((AlertDialog) dialog, which, false);
                        return;
                    }
                    Utils.showToastShort(L10n.f("%1$s is pinned", L10n.isolate(chat.name)));
                } else {
                    if (!PinnedChats.remove(account, chat.id)) {
                        notSaved((AlertDialog) dialog, which, true);
                        return;
                    }
                    Utils.showToastShort(L10n.f("%1$s is unpinned", L10n.isolate(chat.name)));
                }
            });
        }
        return builder.setPositiveButton(L10n.t("OK"), null);
    }

    /** The row that opens the list of accounts whose posts Home and Following leave out. */
    Row hiddenAccountsRow(Context context) {
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
                if (!allowMutation()) {
                    setChecked((AlertDialog) dialog, which, !checked);
                    return;
                }
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

    /** The row that opens the list of sounds whose reels the Reels tab leaves out. */
    Row hiddenSoundsRow(Context context) {
        Row row = new Row(context);
        row.setKey(HIDDEN_SOUNDS_ROW);
        row.setPersistent(false);
        row.setTitle(L10n.t("Hidden sounds"));
        row.setSummary(L10n.t("The sounds you hid with the music icon. Uncheck one to see its reels again."));
        row.setOnPreferenceClickListener(tapped -> {
            showHiddenSounds();
            return true;
        });
        return row;
    }

    /**
     * The hidden sounds, each with a check. Unchecking one brings its reels back to the Reels tab at
     * once, and checking it again hides them. Paused, the list still shows what you chose, though the
     * tab shows every reel.
     */
    void showHiddenSounds() {
        Context context = getActivity();
        if (context == null) return;
        List<HiddenSounds.Sound> sounds = HiddenSounds.saved();
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Hidden sounds"));
        if (sounds.isEmpty()) {
            builder.setMessage(L10n.t("No sounds are hidden yet. Turn on Hide reels by sound, then tap the music "
                    + "icon beside a reel."));
        } else {
            CharSequence[] shown = new CharSequence[sounds.size()];
            boolean[] hidden = new boolean[sounds.size()];
            for (int i = 0; i < shown.length; i++) {
                shown[i] = L10n.isolate(ReelSoundButton.label(context, sounds.get(i)));
                hidden[i] = true;
            }
            builder.setMultiChoiceItems(shown, hidden, (dialog, which, checked) -> {
                if (!allowMutation()) {
                    setChecked((AlertDialog) dialog, which, !checked);
                    return;
                }
                HiddenSounds.Sound sound = sounds.get(which);
                String name = L10n.isolate(ReelSoundButton.label(context, sound));
                if (checked) {
                    HiddenSounds.add(sound);
                    Utils.showToastShort(L10n.f("Hiding reels with %1$s", name));
                } else {
                    HiddenSounds.remove(sound.id);
                    Utils.showToastShort(L10n.f("Showing reels with %1$s again", name));
                }
            });
        }
        show(builder.setPositiveButton(L10n.t("OK"), null));
    }

    /** The row that opens the list of font files the story text editor shows alongside Instagram's own. */
    Row storyFontsRow(Context context) {
        Row row = new Row(context);
        row.setKey(STORY_FONTS_ROW);
        row.setPersistent(false);
        row.setTitle(L10n.t("Your story fonts"));
        row.setSummary(L10n.t("Add .ttf or .otf files from your phone. Uncheck one to take it out."));
        row.setOnPreferenceClickListener(tapped -> {
            showStoryFonts();
            return true;
        });
        return row;
    }

    /**
     * The font files added for story text, each with a check. The unchecked ones are taken out when
     * OK is tapped, and Cancel keeps them all. Add a font file opens the file picker.
     */
    void showStoryFonts() {
        Context context = getActivity();
        if (context == null) return;
        List<String> names = StoryFonts.names(context);
        boolean[] keep = new boolean[names.size()];
        Arrays.fill(keep, true);
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Your story fonts"));
        if (names.isEmpty()) {
            builder.setMessage(L10n.t("No fonts added yet. Add a .ttf or .otf file and it shows up in the story "
                    + "text editor alongside Instagram's fonts."));
        } else {
            CharSequence[] shown = new CharSequence[names.size()];
            for (int i = 0; i < shown.length; i++) shown[i] = L10n.isolate(names.get(i));
            builder.setMultiChoiceItems(shown, keep, (dialog, which, checked) -> keep[which] = checked);
        }
        show(builder.setNeutralButton(L10n.t("Add a font file"), (dialog, which) -> pickFont(PICK_STORY_FONT))
                .setNegativeButton(L10n.t("Cancel"), null)
                .setPositiveButton(L10n.t("OK"), (dialog, which) -> {
                    if (!allowMutation()) return;
                    boolean removed = false;
                    for (int i = 0; i < keep.length; i++) {
                        if (!keep[i]) removed |= StoryFonts.remove(context, names.get(i));
                    }
                    if (removed) Utils.showToastShort(L10n.t("Removed from your story fonts"));
                }));
    }

    /** The name the file picker shows for [uri], or null when it gives none. */
    @Nullable
    static String shownName(Context context, Uri uri) {
        try (android.database.Cursor row = context.getContentResolver().query(uri,
                new String[] {android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (row != null && row.moveToFirst() && !row.isNull(0)) return row.getString(0);
        } catch (Exception failure) {
            Logger.printInfo(() -> "Story fonts: the picker gave no file name", failure);
        }
        return null;
    }

    /**
     * The switch for emoji in story text drawn from a font file (#275). Turning it on opens the file
     * picker, and it shows on only once a picked file loads as a font. Off, Instagram's emoji return.
     */
    SwitchPreference storyEmojiRow(Context context) {
        SwitchPreference row = toggle(context, Settings.STORY_EMOJI, L10n.t("Emoji font in story text"),
                L10n.t("Emoji you type in story text draw from an emoji font file you pick. Turning it on asks for "
                        + "the file. Off, Instagram's emoji come back."));
        row.setOnPreferenceChangeListener((preference, value) -> {
            if (!Boolean.TRUE.equals(value)) return true;
            pickFont(PICK_STORY_EMOJI);
            return false;
        });
        return row;
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
                    if (!allowMutation()) return;
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

    /** A chat's change couldn't be saved: its check goes back to [saved], what the list still holds, and a toast says so. */
    private static void notSaved(AlertDialog dialog, int position, boolean saved) {
        setChecked(dialog, position, saved);
        Utils.showToastShort(L10n.t("The setting couldn't finish updating. Its saved value is shown."));
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
     * Hands [changed] the activity under HushGram settings each time [row] is flipped. Settings are a
     * dialog over Instagram's screen, which never resumes when they close, so a helper that decides as
     * an activity resumes hears of the change here.
     */
    static SwitchPreference onFlip(SwitchPreference row, java.util.function.Consumer<Activity> changed) {
        row.setOnPreferenceChangeListener((preference, value) -> {
            changed.accept(activityOf(preference.getContext()));
            return true;
        });
        return row;
    }

    /**
     * The quality a video save asks for. The list's values are the setting's own names, which is
     * what the shared page syncs a list by, and the summary says what the choice does.
     */
    static NavigationRow navigationRow(Context context) {
        return tabRow(context, NavigationRow.Use.SETTINGS);
    }

    /**
     * What a way-in switch does, for the shortcut, the logo press and the menu row's hide switch.
     * The hide switch says when it can't hide the row because nothing else opens HushGram.
     */
    static String wayInSummary(Setting<?> setting) {
        if (setting == Settings.SETTINGS_SHORTCUT) {
            return L10n.t("Puts HushGram settings in the menu you get by holding Instagram's icon. Off, it leaves that menu.");
        }
        if (setting == Settings.SETTINGS_LOGO_PRESS) {
            return L10n.t("A long press on the Instagram logo at the top of Home opens these settings. Off, the long "
                    + "press goes back to Instagram.");
        }
        if (Settings.HIDE_MENU_ROW.savedValue() && SettingsEntry.waysInOn() == 0) {
            return L10n.t("No other way into HushGram is on, so the row stays in Instagram's menu.");
        }
        return L10n.t("Instagram's Settings and activity screen leaves out the HushGram row while another way here "
                + "opens HushGram. With every other way off, the row comes back.");
    }

    /**
     * The switch for opening HushGram with a long press on your profile's menu button (#84). The
     * button already on screen takes the change once the setting has it, after this change's own
     * sync, so no restart.
     */
    static SwitchPreference profileMenuRow(Context context) {
        SwitchPreference row = toggle(context, Settings.PROFILE_MENU_LONG_PRESS,
                L10n.t("Open settings with a long press on your profile's menu"),
                L10n.t("A long press on the menu button, the three lines at the top right of your own profile, "
                        + "opens HushGram. A tap still opens Instagram's menu, and other profiles stay the same."));
        row.setOnPreferenceChangeListener((preference, value) -> {
            Utils.runOnMainThread(ProfileMenuPress::applyChoice);
            return true;
        });
        return row;
    }

    /** The row choosing the tab whose long press opens the playback quality list (#93). */
    static NavigationRow qualityTabRow(Context context) {
        return tabRow(context, NavigationRow.Use.QUALITY);
    }

    /** The row choosing the tab whose long press turns Ghost mode on or off (#255). */
    static NavigationRow ghostTabRow(Context context) {
        return tabRow(context, NavigationRow.Use.GHOST);
    }

    private static NavigationRow tabRow(Context context, NavigationRow.Use use) {
        EnumSetting<NavigationTarget> setting = use == NavigationRow.Use.QUALITY ? Settings.QUALITY_TAB_TARGET
                : use == NavigationRow.Use.GHOST ? Settings.GHOST_TAB_TARGET : Settings.NAVIGATION_SETTINGS_TARGET;
        NavigationRow row = new NavigationRow(context, use);
        row.setKey(setting.key);
        row.setTitle(use == NavigationRow.Use.QUALITY ? L10n.t("Pick the playback quality with a tab long press")
                : use == NavigationRow.Use.GHOST ? L10n.t("Turn Ghost mode on or off with a tab long press")
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
    static void clearMediaCache(Preference row) {
        if (!allowMutation(row, true)) return;
        Context context = row.getContext();
        if (!Utils.runOnBackgroundThread(() -> {
            if (!asyncChangesAllowed()) {
                Utils.showToastLong(heldMessage());
                return;
            }
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

    /** How many of the newest unfollowers the list row shows. */
    static final int UNFOLLOWERS_SHOWN = 50;

    /**
     * The row listing who left your Followers list, newest first with the day each was seen gone, and
     * when the list was last seen in full. Read off the main thread, so it says so until it's read.
     */
    static Preference unfollowersRow(Context context) {
        Row row = new Row(context);
        row.setKey(UNFOLLOWERS_ROW);
        row.setPersistent(false);
        row.setSelectable(false);
        row.setTitle(L10n.t("Who unfollowed you"));
        row.setSummary(L10n.t("Reading the list…"));
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!Utils.runOnBackgroundThread(() -> {
            String summary = unfollowersSummary(Unfollowers.shown(application));
            Utils.runOnMainThread(() -> row.setSummary(summary));
        })) {
            row.setSummary(unfollowersSummary(Unfollowers.shown(application)));
        }
        return mark(row, SettingsIcons.PERSON);
    }

    /** What the unfollowers row says for [record]. */
    static String unfollowersSummary(Unfollowers.Record record) {
        if (record.checked <= 0) {
            return L10n.t("Nothing to compare yet. With Recent unfollowers on, open your own Followers list and "
                    + "scroll to the end. The next time you do, anyone missing shows here.");
        }
        java.text.DateFormat day = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        String last = L10n.f("Last seen in full on %1$s.", day.format(new java.util.Date(record.checked)));
        if (record.left.isEmpty()) return L10n.t("No one has left since then.") + "\n" + last;
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (Unfollowers.Left left : record.left) {
            if (shown == UNFOLLOWERS_SHOWN) break;
            String who = left.username.isEmpty() ? L10n.f("Account %1$s", left.id) : "@" + left.username;
            text.append(L10n.f("%1$s, gone by %2$s", who, day.format(new java.util.Date(left.when)))).append('\n');
            shown++;
        }
        if (record.left.size() > UNFOLLOWERS_SHOWN) text.append(L10n.t("Only the newest 50 are shown.")).append('\n');
        return text.append(last).toString();
    }

    /**
     * Forgets every account's saved Followers list and unfollowers, off the main thread, says so, and
     * empties [list]. It's handed over rather than looked up, since a search that hides the list row
     * takes it off the screen, and it came back still naming everyone (S22, 2026-10-15).
     */
    static void clearUnfollowers(Preference row, Preference list) {
        if (!allowMutation(row, true)) return;
        Context context = row.getContext();
        if (!Utils.runOnBackgroundThread(() -> {
            if (!asyncChangesAllowed()) {
                Utils.showToastLong(heldMessage());
                return;
            }
            boolean cleared = Unfollowers.clear(context);
            Utils.runOnMainThread(() -> {
                if (cleared) {
                    Utils.showToastShort(L10n.t("Unfollower list cleared."));
                    list.setSummary(unfollowersSummary(Unfollowers.Record.NONE));
                } else {
                    Utils.showToastLong(L10n.t("Couldn't clear the unfollower list. Try again."));
                }
            });
        })) {
            Utils.showToastLong(L10n.t("Couldn't clear the unfollower list. Try again."));
        }
    }

    /** Forgets every save Skip what's already saved remembers, off the main thread, and says so. */
    static void clearDownloadHistory(Preference row) {
        if (!allowMutation(row, true)) return;
        Context context = row.getContext();
        if (!Utils.runOnBackgroundThread(() -> {
            if (!asyncChangesAllowed()) {
                Utils.showToastLong(heldMessage());
                return;
            }
            boolean cleared = DownloadHistory.clear(context);
            Utils.runOnMainThread(() -> {
                if (cleared) Utils.showToastShort(L10n.t("Download history cleared."));
                else Utils.showToastLong(L10n.t("Couldn't clear the download history. Try again."));
            });
        })) {
            Utils.showToastLong(L10n.t("Couldn't clear the download history. Try again."));
        }
    }

    /** How many of the newest viewing history rows the list row shows. */
    static final int VIEWING_HISTORY_SHOWN = 50;

    /**
     * The row listing what the viewing history kept, newest first. Read off the main thread, so it
     * says so until it's read. It isn't tappable: the summary is the list.
     */
    static Preference viewingHistoryRow(Context context) {
        Row row = new Row(context);
        row.setKey(VIEWING_HISTORY_ROW);
        row.setPersistent(false);
        row.setSelectable(false);
        row.setTitle(L10n.t("Viewing history"));
        row.setSummary(L10n.t("Reading the list…"));
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!Utils.runOnBackgroundThread(() -> {
            String summary = viewingHistorySummary(ViewingHistory.entries(application), System.currentTimeMillis());
            Utils.runOnMainThread(() -> row.setSummary(summary));
        })) {
            row.setSummary(viewingHistorySummary(ViewingHistory.entries(application), System.currentTimeMillis()));
        }
        return mark(row, SettingsIcons.PERSON);
    }

    /** What the viewing history row says for [rows], newest first, at [now]. */
    static String viewingHistorySummary(List<ViewingHistory.Row> rows, long now) {
        if (rows.isEmpty()) {
            return L10n.t("Nothing here yet. With Keep a viewing history on, what you look at shows up here.");
        }
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (ViewingHistory.Row seen : rows) {
            if (shown == VIEWING_HISTORY_SHOWN) break;
            String who = seen.owner.isEmpty() ? "" : "@" + seen.owner + ", ";
            String ago = android.text.format.DateUtils.getRelativeTimeSpanString(seen.time, now,
                    android.text.format.DateUtils.MINUTE_IN_MILLIS).toString();
            text.append(who).append(viewingKindLabel(seen.kind)).append(", ").append(ago).append('\n');
            shown++;
        }
        if (rows.size() > VIEWING_HISTORY_SHOWN) text.append(L10n.t("Only the newest 50 are shown."));
        return text.toString().trim();
    }

    /** What the list calls [kind]. */
    static String viewingKindLabel(ViewingHistory.Kind kind) {
        switch (kind) {
            case POST: return L10n.t("Post");
            case REEL: return L10n.t("Reel");
            case STORY: return L10n.t("Story");
            default: return L10n.t("Profile");
        }
    }

    /** Forgets the viewing history off the main thread, says so, and empties [list]. */
    static void clearViewingHistory(Preference row, Preference list) {
        if (!allowMutation(row, true)) return;
        Context context = row.getContext();
        if (!Utils.runOnBackgroundThread(() -> {
            if (!asyncChangesAllowed()) {
                Utils.showToastLong(heldMessage());
                return;
            }
            boolean cleared = ViewingHistory.clear(context);
            Utils.runOnMainThread(() -> {
                if (cleared) {
                    Utils.showToastShort(L10n.t("Viewing history cleared."));
                    list.setSummary(viewingHistorySummary(java.util.Collections.emptyList(), System.currentTimeMillis()));
                } else {
                    Utils.showToastLong(L10n.t("Couldn't clear the viewing history. Try again."));
                }
            });
        })) {
            Utils.showToastLong(L10n.t("Couldn't clear the viewing history. Try again."));
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

    /** Burn-in guard on the Reels screen. Its summary says what the choice does. */
    static BurnInGuardRow burnInGuardRow(Context context) {
        BurnInGuardRow row = new BurnInGuardRow(context);
        row.setKey(Settings.BURN_IN_GUARD.key);
        row.setTitle(L10n.t("Burn-in guard"));
        row.setDialogTitle(L10n.t("Burn-in guard"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        BurnInMode[] modes = BurnInMode.values();
        CharSequence[] entries = new CharSequence[modes.length];
        CharSequence[] values = new CharSequence[modes.length];
        for (int i = 0; i < modes.length; i++) {
            entries[i] = burnInLabel(modes[i]);
            values[i] = modes[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.BURN_IN_GUARD.savedValue().name());
        return row;
    }

    /** What the list calls [mode]. */
    static String burnInLabel(BurnInMode mode) {
        switch (mode) {
            case DIM:
                return L10n.t("Dim when idle");
            case DIM_AND_SHIFT:
                return L10n.t("Dim when idle and shift");
            default:
                return L10n.t("Off");
        }
    }

    /** What [mode] does, for the row's summary. */
    static String burnInSummary(BurnInMode mode) {
        switch (mode) {
            case DIM:
                return L10n.t("On the Reels screen, the buttons, caption and bars fade when you leave them alone. "
                        + "Touching one of them brings them back.");
            case DIM_AND_SHIFT:
                return L10n.t("On the Reels screen, the buttons, caption and bars fade when you leave them alone, "
                        + "and the screen moves a step every 2 minutes. Touching one of them brings them back.");
            default:
                return L10n.t("Off. It can fade a reel's buttons while the screen sits untouched, so they don't burn into "
                        + "an OLED screen.");
        }
    }

    /** How long Burn-in guard waits before it dims (#162). Its summary says the choice and what counts as a touch. */
    static BurnInWaitRow burnInWaitRow(Context context) {
        BurnInWaitRow row = new BurnInWaitRow(context);
        row.setKey(Settings.BURN_IN_WAIT.key);
        row.setTitle(L10n.t("Dim after"));
        row.setDialogTitle(L10n.t("Dim after"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        BurnInWait[] waits = BurnInWait.values();
        CharSequence[] entries = new CharSequence[waits.length];
        CharSequence[] values = new CharSequence[waits.length];
        for (int i = 0; i < waits.length; i++) {
            entries[i] = burnInWaitLabel(waits[i]);
            values[i] = waits[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.BURN_IN_WAIT.savedValue().name());
        return row;
    }

    /** What the list calls [wait]. */
    static String burnInWaitLabel(BurnInWait wait) {
        return wait == BurnInWait.AT_ONCE ? L10n.t("At once") : L10n.f("After %1$d seconds", wait.millis / 1000);
    }

    /** [wait], then what doesn't count as a touch, for the row's summary. */
    static String burnInWaitSummary(BurnInWait wait) {
        return burnInWaitLabel(wait) + ". " + L10n.t("Tapping the video, double-tapping it to like, and Save and "
                + "Download don't count as a touch, so the buttons stay faded.");
    }

    /** Scroll break (#297): how long the Home, Reels and Explore tabs stay open before a break. Its summary says the choice. */
    static ScrollBreakRow scrollBreakRow(Context context) {
        ScrollBreakRow row = new ScrollBreakRow(context);
        row.setKey(Settings.SCROLL_BREAK.key);
        row.setTitle(L10n.t("Take a scroll break"));
        row.setDialogTitle(L10n.t("Take a scroll break"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        ScrollBreakTime[] times = ScrollBreakTime.values();
        CharSequence[] entries = new CharSequence[times.length];
        CharSequence[] values = new CharSequence[times.length];
        for (int i = 0; i < times.length; i++) {
            entries[i] = scrollBreakLabel(times[i]);
            values[i] = times[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.SCROLL_BREAK.savedValue().name());
        return row;
    }

    /** What the list calls [time]. */
    static String scrollBreakLabel(ScrollBreakTime time) {
        return time == ScrollBreakTime.OFF ? L10n.t("Off") : L10n.f("After %1$d minutes", time.minutes);
    }

    /** [time], then what it does, for the row's summary. */
    static String scrollBreakSummary(ScrollBreakTime time) {
        if (time == ScrollBreakTime.OFF) {
            return L10n.t("Off. It can cover the Home, Reels and Explore tabs for 5 minutes after you've spent a "
                    + "while on them.");
        }
        return L10n.f("After %1$d minutes on the Home, Reels and Explore tabs, a break screen covers them for 5 "
                + "minutes. Messages, your profile and the tab bar still work.", time.minutes);
    }

    /** The home screen icon (#284). Its summary says the choice. */
    static AppIconRow appIconRow(Context context) {
        AppIconRow row = new AppIconRow(context);
        row.setKey(Settings.APP_ICON.key);
        row.setTitle(L10n.t("App icon"));
        row.setDialogTitle(L10n.t("App icon"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        AppIconChoice[] icons = AppIconChoice.values();
        CharSequence[] entries = new CharSequence[icons.length];
        CharSequence[] values = new CharSequence[icons.length];
        for (int i = 0; i < icons.length; i++) {
            entries[i] = appIconLabel(icons[i]);
            values[i] = icons[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.APP_ICON.savedValue().name());
        return row;
    }

    /** What the list calls [icon]. HushGram's own name isn't translated. */
    static String appIconLabel(AppIconChoice icon) {
        switch (icon) {
            case HUSHGRAM: return "HushGram";
            case DARK: return L10n.t("Instagram on black");
            case PLUM: return L10n.t("Instagram on plum");
            case LIGHT: return L10n.t("Instagram in black on white");
            case SKETCH: return L10n.t("Hand-drawn Instagram");
            default: return L10n.t("Instagram's own");
        }
    }

    /** [icon], then when it shows, for the row's summary. */
    static String appIconSummary(AppIconChoice icon) {
        if (icon == AppIconChoice.INSTAGRAM) {
            return L10n.t("Instagram's own. Pick another to change the icon on your home screen and in the app drawer.");
        }
        return L10n.f("%1$s. It shows once you leave Instagram. If the icon is gone from your home screen, add it "
                + "again from the app drawer.", appIconLabel(icon));
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

    /** How far the glass tab bar blurs what's behind it, from a slider in dp (#289, #391). */
    static SliderRow glassBlurRow(Context context) {
        SliderRow row = new SliderRow(context, Settings.GLASS_TAB_BAR_BLUR_RADIUS, 1, L10n.t("%1$d dp"));
        row.setTitle(L10n.t("Blur strength"));
        row.setText(String.valueOf(Settings.GLASS_TAB_BAR_BLUR_RADIUS.savedValue()));
        return row;
    }

    /** How strong the glass tab bar's tint is, from a slider in percent of the bar's own look (#391). */
    static SliderRow glassOpacityRow(Context context) {
        SliderRow row = new SliderRow(context, Settings.GLASS_TAB_BAR_OPACITY, 5, L10n.t("%1$d%%"));
        row.setTitle(L10n.t("Tab bar opacity"));
        row.setText(String.valueOf(Settings.GLASS_TAB_BAR_OPACITY.savedValue()));
        return row;
    }

    /** How many reels play before Stop after a set number of reels stops the swiping, from a slider (#405). */
    static SliderRow reelCapCountRow(Context context) {
        SliderRow row = new SliderRow(context, Settings.REEL_CAP_COUNT, 5, L10n.t("%1$d reels"));
        row.setTitle(L10n.t("Reels before swiping stops"));
        row.setText(String.valueOf(Settings.REEL_CAP_COUNT.savedValue()));
        return row;
    }

    /** How long Instagram stays in the background before the swiping comes back, from a slider (#405). */
    static SliderRow reelCapBreakRow(Context context) {
        SliderRow row = new SliderRow(context, Settings.REEL_CAP_BREAK, 5, L10n.t("%1$d minutes in the background"));
        row.setTitle(L10n.t("Time away before swiping comes back"));
        row.setText(String.valueOf(Settings.REEL_CAP_BREAK.savedValue()));
        return row;
    }

    /**
     * Instagram's font, the phone's or a font file (#166, #5). Choosing A font file opens the file
     * picker, and the choice is kept only once a picked file loads as a font.
     */
    FontRow fontRow(Context context) {
        FontRow row = new FontRow(context);
        row.setKey(Settings.FONT.key);
        row.setTitle(L10n.t("Font"));
        row.setDialogTitle(L10n.t("Font"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        FontChoice[] choices = FontChoice.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = fontLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.FONT.savedValue().name());
        row.setOnPreferenceChangeListener((preference, value) -> {
            if (!FontChoice.FILE.name().equals(value)) return true;
            pickFont(PICK_FONT);
            return false;
        });
        return row;
    }

    /**
     * Where Instagram's emoji draw from (#166): the phone's, a free pack or an emoji font file.
     * Choosing a pack downloads it, and choosing a file opens the file picker. Either is kept only
     * once the font has color emoji, and until then the row keeps what it had.
     */
    EmojiRow emojiRow(Context context) {
        EmojiRow row = new EmojiRow(context);
        row.setKey(Settings.EMOJI_FONT.key);
        row.setTitle(L10n.t("Emoji pack"));
        row.setDialogTitle(L10n.t("Emoji pack"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        EmojiChoice[] choices = EmojiChoice.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = emojiLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.EMOJI_FONT.savedValue().name());
        row.setOnPreferenceChangeListener((preference, value) -> {
            EmojiChoice choice = emojiChoice(String.valueOf(value));
            if (choice == EmojiChoice.FILE) {
                pickFont(PICK_EMOJI);
                return false;
            }
            if (!choice.pack()) return true;
            downloadEmoji(choice);
            return false;
        });
        return row;
    }

    static EmojiChoice emojiChoice(@Nullable String value) {
        for (EmojiChoice candidate : EmojiChoice.values()) if (candidate.name().equals(value)) return candidate;
        return EmojiChoice.PHONE;
    }

    /** What the emoji list calls [choice]. The packs keep their makers' names. */
    static String emojiLabel(EmojiChoice choice) {
        switch (choice) {
            case TWEMOJI:
                return "Twemoji";
            case OPENMOJI:
                return "OpenMoji";
            case BLOBMOJI:
                return "Blobmoji";
            case FILE:
                return L10n.t("An emoji font file");
            default:
                return L10n.t("Your phone's emoji");
        }
    }

    /** The sentence under the emoji row for [choice]. */
    static String emojiSummary(EmojiChoice choice) {
        switch (choice) {
            case PHONE:
                return L10n.t("Your phone's emoji, or Google's with the switch above on. You can also download a free "
                        + "emoji pack or pick a color emoji font file.");
            case FILE:
                return L10n.t("Emoji draw from the emoji font file you picked. Pick it again to change the file. "
                        + "Restart Instagram to see a change.");
            default:
                return L10n.f("Emoji draw as %1$s, downloaded from its maker. Restart Instagram to see a change.",
                        L10n.isolate(emojiLabel(choice)));
        }
    }

    /**
     * Downloads [choice]'s pack off the main thread and keeps it the way a picked file is kept, then
     * saves the choice. The row keeps what it had until then. Choosing the pack already kept does nothing.
     */
    private void downloadEmoji(EmojiChoice choice) {
        Context context = getContext();
        if (context == null || documentRequest != 0 || changingConfiguration || ExportStatus.CONFIGURATION.active()
                || Fonts.importActive()) return;
        if (!allowMutation() || EmojiFonts.keeps(context, choice)) return;
        if (MessagesLock.locked()) {
            Activity activity = getActivity();
            if (activity != null) MessagesLock.confirmThen(activity, () -> downloadEmoji(choice));
            return;
        }
        long token = Fonts.beginImport();
        if (token == 0) {
            Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
            return;
        }
        Context application = context.getApplicationContext() == null ? context : context.getApplicationContext();
        long revision = Settings.EMOJI_FONT.savedRevision();
        Utils.showToastShort(L10n.t("Downloading the emoji..."));
        showConfiguration();
        if (!Utils.runOnBackgroundThread(() -> {
            Fonts.Prepared prepared = null;
            FontResult result;
            try {
                prepared = EmojiFonts.download(application, choice);
                result = prepared == null ? new FontResult(PICK_EMOJI, FontVerdict.UNREACHED, null)
                        : installPickedFont(application, PICK_EMOJI, choice, token, revision, prepared, null);
            } catch (Exception failure) {
                Logger.printInfo(() -> "Emoji: couldn't keep the " + choice + " pack", failure);
                result = new FontResult(PICK_EMOJI, FontVerdict.UNREACHED, null);
            } finally {
                if (prepared != null) prepared.close();
                Fonts.finishImport(token);
            }
            FontResult finished = result;
            Utils.runOnMainThread(() -> fontFinished(finished));
        })) {
            Fonts.finishImport(token);
            showConfiguration();
            Utils.showToastLong(L10n.t("Couldn't start that. Try again."));
        }
    }

    /** What the list calls [choice]. */
    static String fontLabel(FontChoice choice) {
        switch (choice) {
            case SYSTEM:
                return L10n.t("Your phone's font");
            case FILE:
                return L10n.t("A font file");
            default:
                return L10n.t("Instagram's font");
        }
    }

    /** The sentence under the row for [choice]. */
    static String fontSummary(FontChoice choice) {
        switch (choice) {
            case SYSTEM:
                return L10n.t("Instagram's text uses your phone's font. Restart Instagram to see a change.");
            case FILE:
                return L10n.t("Instagram's text uses the font file you picked. Pick it again to change the file. "
                        + "Restart Instagram to see a change.");
            default:
                return L10n.t("Instagram's own font. You can also pick your phone's font or a .ttf or .otf file. "
                        + "Restart Instagram to see a change.");
        }
    }

    /**
     * The color Instagram's blue turns into. Its summary is the color chosen. Material You is offered
     * on Android 12 and newer, which have a wallpaper palette.
     */
    static AccentHueRow accentHueRow(Context context) {
        AccentHueRow row = new AccentHueRow(context);
        row.setKey(Settings.ACCENT_HUE.key);
        row.setTitle(L10n.t("Color"));
        row.setDialogTitle(L10n.t("Accent color"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        List<AccentHue> hues = new ArrayList<>(Arrays.asList(AccentHue.values()));
        CharSequence[] entries = new CharSequence[hues.size()];
        CharSequence[] values = new CharSequence[hues.size()];
        for (int i = 0; i < hues.size(); i++) {
            // Material You stays in the list on Android 11 and older, greyed out with its reason.
            entries[i] = hues.get(i) == AccentHue.WALLPAPER && !MaterialYou.available()
                    ? L10n.t("Material You needs Android 12 or newer, so Instagram's blue stays.")
                    : accentHueLabel(hues.get(i));
            values[i] = hues.get(i).name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.ACCENT_HUE.savedValue().name());
        return row;
    }

    /** How tall the glass tab bar's pill is. Its summary is the step chosen. */
    static GlassHeightRow glassHeightRow(Context context) {
        GlassHeightRow row = new GlassHeightRow(context);
        row.setKey(Settings.GLASS_TAB_BAR_HEIGHT.key);
        row.setTitle(L10n.t("Tab bar height"));
        row.setDialogTitle(L10n.t("Tab bar height"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        GlassHeight[] steps = GlassHeight.values();
        CharSequence[] entries = new CharSequence[steps.length];
        CharSequence[] values = new CharSequence[steps.length];
        for (int i = 0; i < steps.length; i++) {
            entries[i] = glassHeightLabel(steps[i]);
            values[i] = steps[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.GLASS_TAB_BAR_HEIGHT.savedValue().name());
        return row;
    }

    /** How round the glass tab bar's pill is (#144). Its summary is the step chosen. */
    static GlassCornersRow glassCornersRow(Context context) {
        GlassCornersRow row = new GlassCornersRow(context);
        row.setKey(Settings.GLASS_TAB_BAR_CORNERS.key);
        row.setTitle(L10n.t("Tab bar corners"));
        row.setDialogTitle(L10n.t("Tab bar corners"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        GlassCorners[] steps = GlassCorners.values();
        CharSequence[] entries = new CharSequence[steps.length];
        CharSequence[] values = new CharSequence[steps.length];
        for (int i = 0; i < steps.length; i++) {
            entries[i] = glassCornersLabel(steps[i]);
            values[i] = steps[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.GLASS_TAB_BAR_CORNERS.savedValue().name());
        return row;
    }

    /** How much room a floating glass pill leaves under itself. Its summary is the step chosen. */
    static GlassLiftRow glassLiftRow(Context context) {
        GlassLiftRow row = new GlassLiftRow(context);
        row.setKey(Settings.GLASS_TAB_BAR_LIFT.key);
        row.setTitle(L10n.t("Space under the tab bar"));
        row.setDialogTitle(L10n.t("Space under the tab bar"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        GlassLift[] steps = GlassLift.values();
        CharSequence[] entries = new CharSequence[steps.length];
        CharSequence[] values = new CharSequence[steps.length];
        for (int i = 0; i < steps.length; i++) {
            entries[i] = glassLiftLabel(steps[i]);
            values[i] = steps[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.GLASS_TAB_BAR_LIFT.savedValue().name());
        return row;
    }

    /** How long a reel plays untouched before the glass tab bar slides away (#170). Its summary says what the choice does. */
    static GlassReelsHideRow glassReelsHideRow(Context context) {
        GlassReelsHideRow row = new GlassReelsHideRow(context);
        row.setKey(Settings.GLASS_TAB_BAR_REELS_HIDE.key);
        row.setTitle(L10n.t("Hide the tab bar on Reels"));
        row.setDialogTitle(L10n.t("Hide the tab bar on Reels"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        GlassReelsHide[] delays = GlassReelsHide.values();
        CharSequence[] entries = new CharSequence[delays.length];
        CharSequence[] values = new CharSequence[delays.length];
        for (int i = 0; i < delays.length; i++) {
            entries[i] = glassReelsHideLabel(delays[i]);
            values[i] = delays[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.GLASS_TAB_BAR_REELS_HIDE.savedValue().name());
        return row;
    }

    /** What the list calls [delay]. */
    static String glassReelsHideLabel(GlassReelsHide delay) {
        return delay == GlassReelsHide.NEVER ? L10n.t("Never") : L10n.f("After %1$d seconds", delay.millis / 1000);
    }

    /** What [delay] does and how the bar comes back, for the row's summary. */
    static String glassReelsHideSummary(GlassReelsHide delay) {
        if (delay == GlassReelsHide.NEVER) {
            return L10n.t("Never. Pick a time and the pill slides away once a reel has played that long without a "
                    + "touch. Restart Instagram after changing it.");
        }
        return L10n.f("On Reels, the pill slides away after %1$d seconds without a touch. Tap the bottom edge of the "
                + "screen, change tab or press Back to bring it back. Restart Instagram after changing it.",
                delay.millis / 1000);
    }

    /** The Download icon beside a reel (#178), under Downloads next to the feed post one. */
    static SwitchPreference reelDownloadButtonRow(Context context) {
        return toggle(context, Settings.REEL_DOWNLOAD_BUTTON, L10n.t("Download button on reels"),
                L10n.t("Puts a download icon under Save beside a reel, so one tap saves it the way Download in its "
                        + "menu does. Needs Download on reels."));
    }

    /** How many fingers Hold to download waits for. Its summary is the count chosen. */
    static HoldFingersRow holdFingersRow(Context context) {
        HoldFingersRow row = new HoldFingersRow(context);
        row.setKey(Settings.HOLD_FINGERS.key);
        row.setTitle(L10n.t("Fingers"));
        row.setDialogTitle(L10n.t("Fingers"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        HoldFingers[] counts = HoldFingers.values();
        CharSequence[] entries = new CharSequence[counts.length];
        CharSequence[] values = new CharSequence[counts.length];
        for (int i = 0; i < counts.length; i++) {
            entries[i] = holdFingersLabel(counts[i]);
            values[i] = counts[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.HOLD_FINGERS.savedValue().name());
        return row;
    }

    /** How round Rounded corners on posts and on Reels cut their corners. Its summary is the size chosen. */
    static CornerSizeRow cornerSizeRow(Context context) {
        CornerSizeRow row = new CornerSizeRow(context);
        row.setKey(Settings.ROUNDED_CORNER_SIZE.key);
        row.setTitle(L10n.t("Corner size"));
        row.setDialogTitle(L10n.t("Corner size"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        CornerSize[] sizes = CornerSize.values();
        CharSequence[] entries = new CharSequence[sizes.length];
        CharSequence[] values = new CharSequence[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            entries[i] = cornerSizeLabel(sizes[i]);
            values[i] = sizes[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.ROUNDED_CORNER_SIZE.savedValue().name());
        return row;
    }

    /** What the list calls [size]. */
    static String cornerSizeLabel(CornerSize size) {
        return size == CornerSize.LARGE ? L10n.t("Large") : L10n.t("Small");
    }

    /** How far Ambient light's glow reaches. Its summary is the choice. */
    static AmbientSpreadRow ambientSpreadRow(Context context) {
        AmbientSpreadRow row = new AmbientSpreadRow(context);
        row.setKey(Settings.AMBIENT_LIGHT_SPREAD.key);
        row.setTitle(L10n.t("Glow spread"));
        row.setDialogTitle(L10n.t("Glow spread"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        AmbientSpread[] choices = AmbientSpread.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = ambientSpreadLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.AMBIENT_LIGHT_SPREAD.savedValue().name());
        return row;
    }

    /** What the list calls [spread]. */
    static String ambientSpreadLabel(AmbientSpread spread) {
        if (spread == AmbientSpread.NARROW) return L10n.t("Narrow");
        return spread == AmbientSpread.WIDE ? L10n.t("Wide") : L10n.t("Medium");
    }

    /** How quickly Ambient light's glow follows a video. Its summary is the choice. */
    static AmbientSpeedRow ambientSpeedRow(Context context) {
        AmbientSpeedRow row = new AmbientSpeedRow(context);
        row.setKey(Settings.AMBIENT_LIGHT_SPEED.key);
        row.setTitle(L10n.t("Glow speed"));
        row.setDialogTitle(L10n.t("Glow speed"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        AmbientSpeed[] choices = AmbientSpeed.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = ambientSpeedLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.AMBIENT_LIGHT_SPEED.savedValue().name());
        return row;
    }

    /** What the list calls [speed]. */
    static String ambientSpeedLabel(AmbientSpeed speed) {
        if (speed == AmbientSpeed.SLOW) return L10n.t("Slow");
        return speed == AmbientSpeed.FAST ? L10n.t("Fast") : L10n.t("Medium");
    }

    /** How much thicker Thicker story progress bar draws the bar. Its summary is the choice. */
    static StoryBarRow storyBarRow(Context context) {
        StoryBarRow row = new StoryBarRow(context);
        row.setKey(Settings.STORY_BAR_THICKNESS.key);
        row.setTitle(L10n.t("Thickness"));
        row.setDialogTitle(L10n.t("Thickness"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        StoryBarThickness[] choices = StoryBarThickness.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = storyBarLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.STORY_BAR_THICKNESS.savedValue().name());
        return row;
    }

    /** What the list calls [thickness]. */
    static String storyBarLabel(StoryBarThickness thickness) {
        return thickness == StoryBarThickness.THREE_TIMES ? L10n.t("Three times as thick") : L10n.t("Twice as thick");
    }

    /** Which stories Filter the Stories tray by verified accounts keeps (#340). Its summary is the choice. */
    static TrayBadgeRow trayBadgeRow(Context context) {
        TrayBadgeRow row = new TrayBadgeRow(context);
        row.setKey(Settings.TRAY_BADGE.key);
        row.setTitle(L10n.t("Stories to keep"));
        row.setDialogTitle(L10n.t("Stories to keep"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        TrayBadge[] choices = TrayBadge.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = trayBadgeLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.TRAY_BADGE.savedValue().name());
        return row;
    }

    /** What the list calls [badge]. */
    static String trayBadgeLabel(TrayBadge badge) {
        return badge == TrayBadge.VERIFIED ? L10n.t("Only verified accounts") : L10n.t("Only accounts that aren't verified");
    }

    /** How long Hold to download's fingers stay down. Its summary is the time chosen. */
    static HoldTimeRow holdTimeRow(Context context) {
        HoldTimeRow row = new HoldTimeRow(context);
        row.setKey(Settings.HOLD_TIME.key);
        row.setTitle(L10n.t("Hold time"));
        row.setDialogTitle(L10n.t("Hold time"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        HoldTime[] times = HoldTime.values();
        CharSequence[] entries = new CharSequence[times.length];
        CharSequence[] values = new CharSequence[times.length];
        for (int i = 0; i < times.length; i++) {
            entries[i] = holdTimeLabel(times[i]);
            values[i] = times[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.HOLD_TIME.savedValue().name());
        return row;
    }

    /** The row that opens the list of the share sheet's round buttons. Its summary names the ones left off. */
    Row shareSheetButtonsRow(Context context) {
        Row row = new Row(context);
        row.setKey(Settings.HIDDEN_SHARE_SHEET_BUTTONS.key);
        row.setPersistent(false);
        row.setTitle(L10n.t("Buttons on the share sheet"));
        row.setSummary(shareSheetButtonsSummary(Settings.HIDDEN_SHARE_SHEET_BUTTONS.savedValue()));
        row.setOnPreferenceClickListener(tapped -> {
            showShareSheetButtons(row);
            return true;
        });
        return row;
    }

    /** What the buttons left off in [saved] mean, for the row's summary. */
    static String shareSheetButtonsSummary(String saved) {
        Set<String> hidden = ShareSheetActions.hidden(saved);
        if (hidden.isEmpty()) return L10n.t("Every button under the people on the share sheet shows. Untick the ones you don't use.");
        List<String> labels = new ArrayList<>();
        for (String action : ShareSheetActions.ACTIONS) {
            if (hidden.contains(action)) labels.add(L10n.isolate(shareSheetButtonLabel(action)));
        }
        String left = String.join(", ", labels);
        return L10n.f("Left off the share sheet: %1$s.", left);
    }

    /** What the list calls the share sheet button Instagram names [action]. */
    static String shareSheetButtonLabel(String action) {
        switch (action) {
            case "add_to_your_story":
                return L10n.t("Add to story");
            case "system_share_sheet":
                return L10n.t("Share to");
            case "whatsapp":
                return L10n.t("WhatsApp");
            case "copy_link":
                return L10n.t("Copy link");
            case "download_media":
                return L10n.t("Download");
            case "user_sms":
                return L10n.t("SMS");
            case "messenger":
                return L10n.t("Messenger");
            case "facebook":
                return L10n.t("Facebook");
            case "snapchat":
                return L10n.t("Snapchat");
            case "twitter":
                return L10n.t("X");
            case "barcelona":
                return L10n.t("Threads");
            case "add_to_channel":
                return L10n.t("Add to channel");
            case "save":
                return L10n.t("Save");
            case "qr_code":
                return L10n.t("QR code");
            case "whatsapp_status":
                return L10n.t("WhatsApp status");
            case "reply_to_author":
                return L10n.t("Reply to author");
            case "watch_on_tv":
                return L10n.t("Watch on TV");
            case "profile_card_download":
                return L10n.t("Download profile card");
            case "line":
                return L10n.t("LINE");
            case "kakaotalk":
                return L10n.t("KakaoTalk");
            case "discord":
                return L10n.t("Discord");
            default:
                return L10n.t("Band");
        }
    }

    /**
     * Every share sheet button with a check, ticked while it shows. A change saves at once, so the
     * next share sheet Instagram opens has the row without the buttons left unticked. A button an
     * Instagram update adds to the row isn't listed, and shows.
     */
    void showShareSheetButtons(Row row) {
        Context context = getActivity();
        if (context == null) return;
        Set<String> hidden = new LinkedHashSet<>(ShareSheetActions.hidden(Settings.HIDDEN_SHARE_SHEET_BUTTONS.savedValue()));
        List<String> actions = ShareSheetActions.ACTIONS;
        CharSequence[] names = new CharSequence[actions.size()];
        boolean[] shown = new boolean[actions.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = shareSheetButtonLabel(actions.get(i));
            shown[i] = !hidden.contains(actions.get(i));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Buttons on the share sheet"));
        builder.setMultiChoiceItems(names, shown, (dialog, which, on) -> {
            if (!allowMutation(row, true)) {
                setChecked((AlertDialog) dialog, which, !hidden.contains(actions.get(which)));
                return;
            }
            if (on) hidden.remove(actions.get(which));
            else hidden.add(actions.get(which));
            Settings.HIDDEN_SHARE_SHEET_BUTTONS.save(ShareSheetActions.join(hidden));
            row.setSummary(shareSheetButtonsSummary(Settings.HIDDEN_SHARE_SHEET_BUTTONS.savedValue()));
        });
        AlertDialog dialog = show(builder.setNeutralButton(L10n.t("Show all"), null)
                .setPositiveButton(L10n.t("OK"), null));
        if (dialog == null) return;
        // A click listener of its own, so Show all ticks every button without closing the list.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(tapped -> {
            if (!allowMutation(row, true)) return;
            hidden.clear();
            Settings.HIDDEN_SHARE_SHEET_BUTTONS.save("");
            row.setSummary(shareSheetButtonsSummary(""));
            for (int i = 0; i < actions.size(); i++) dialog.getListView().setItemChecked(i, true);
        });
    }

    /** The row that opens the list of rows on Instagram's own settings screen. Its summary says how many are left off. */
    Row settingsRowsRow(Context context) {
        Row row = new Row(context);
        row.setKey(Settings.HIDDEN_SETTINGS_ROWS.key);
        row.setPersistent(false);
        row.setTitle(L10n.t("Rows in Instagram's settings"));
        row.setSummary(settingsRowsSummary(Settings.HIDDEN_SETTINGS_ROWS.savedValue()));
        row.setOnPreferenceClickListener(tapped -> {
            showSettingsRows(row);
            return true;
        });
        return row;
    }

    /** What the rows left off in [saved] mean, for the row's summary. */
    static String settingsRowsSummary(String saved) {
        int hidden = SettingsRows.parse(saved).size();
        if (hidden == 0) return L10n.t("Every row of Instagram's Settings and activity shows. Untick the ones you don't use.");
        return L10n.quantity(hidden, "%1$d row is left off Instagram's Settings and activity.",
                "%1$d rows are left off Instagram's Settings and activity.", hidden);
    }

    /**
     * The rows Instagram's Settings and activity has shown on this phone, each with a check, ticked
     * while it shows, in Instagram's own words. A change saves at once and shows the next time that
     * screen opens. Until the screen has opened once there's nothing to list, and the list says so.
     */
    void showSettingsRows(Row row) {
        Context context = getActivity();
        if (context == null) return;
        List<String[]> rows = SettingsRows.offered();
        Set<String> hidden = SettingsRows.parse(Settings.HIDDEN_SETTINGS_ROWS.savedValue());
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Rows in Instagram's settings"));
        if (rows.isEmpty()) {
            show(builder.setMessage(L10n.t("Open Settings and activity in Instagram once, then come back here. Its rows "
                    + "show up in this list to untick.")).setPositiveButton(L10n.t("OK"), null));
            return;
        }
        CharSequence[] names = new CharSequence[rows.size()];
        boolean[] shown = new boolean[rows.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = rows.get(i)[1];
            shown[i] = !hidden.contains(rows.get(i)[0]);
        }
        builder.setMultiChoiceItems(names, shown, (dialog, which, on) -> {
            if (!allowMutation(row, true)) {
                setChecked((AlertDialog) dialog, which, !hidden.contains(rows.get(which)[0]));
                return;
            }
            if (on) hidden.remove(rows.get(which)[0]);
            else hidden.add(rows.get(which)[0]);
            Settings.HIDDEN_SETTINGS_ROWS.save(SettingsRows.join(hidden));
            row.setSummary(settingsRowsSummary(Settings.HIDDEN_SETTINGS_ROWS.savedValue()));
        });
        AlertDialog dialog = show(builder.setNeutralButton(L10n.t("Show all"), null)
                .setPositiveButton(L10n.t("OK"), null));
        if (dialog == null) return;
        // A click listener of its own, so Show all ticks every row without closing the list.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(tapped -> {
            if (!allowMutation(row, true)) return;
            hidden.clear();
            Settings.HIDDEN_SETTINGS_ROWS.save("");
            row.setSummary(settingsRowsSummary(""));
            for (int i = 0; i < rows.size(); i++) dialog.getListView().setItemChecked(i, true);
        });
    }

    /** The row that opens the list of Instagram's notification types to mute. Its summary says how many are. */
    Row mutedChannelsRow(Context context) {
        Row row = new Row(context);
        row.setKey(Settings.MUTED_NOTIFICATION_CHANNELS.key);
        row.setPersistent(false);
        row.setTitle(L10n.t("Mute notification types"));
        row.setSummary(mutedChannelsSummary(Settings.MUTED_NOTIFICATION_CHANNELS.savedValue()));
        row.setOnPreferenceClickListener(tapped -> {
            showMutedChannels(row);
            return true;
        });
        return row;
    }

    /** What the notification types muted in [saved] mean, for the row's summary. */
    static String mutedChannelsSummary(String saved) {
        int muted = NotificationGroups.mutedChannels(saved).size();
        if (muted == 0) {
            return L10n.t("Stops the notification types you tick, even ones Instagram's own notification settings can't turn off.");
        }
        return L10n.quantity(muted, "%1$d notification type is muted.", "%1$d notification types are muted.", muted);
    }

    /**
     * Instagram's notification channels on this phone, each with a check, ticked while muted, in
     * Instagram's own words. A change saves at once and holds back the next notification on that
     * channel. Until Instagram has set its channels up there's nothing to list, and the list says so.
     */
    void showMutedChannels(Row row) {
        Context context = getActivity();
        if (context == null) return;
        Set<String> muted = NotificationGroups.mutedChannels(Settings.MUTED_NOTIFICATION_CHANNELS.savedValue());
        List<String[]> channels = NotificationGroups.offeredChannels(context, muted);
        AlertDialog.Builder builder = new AlertDialog.Builder(context).setTitle(L10n.t("Mute notification types"));
        if (channels.isEmpty()) {
            show(builder.setMessage(L10n.t("Instagram hasn't set up its notification types on this phone yet. Open "
                    + "Instagram once, then come back here.")).setPositiveButton(L10n.t("OK"), null));
            return;
        }
        CharSequence[] names = new CharSequence[channels.size()];
        boolean[] ticked = new boolean[channels.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = channels.get(i)[1];
            ticked[i] = muted.contains(channels.get(i)[0]);
        }
        // The dialog keeps [ticked] and re-applies its checks at each layout, so every change to a check changes it too.
        builder.setMultiChoiceItems(names, ticked, (dialog, which, on) -> {
            if (!allowMutation(row, true)) {
                ticked[which] = muted.contains(channels.get(which)[0]);
                setChecked((AlertDialog) dialog, which, ticked[which]);
                return;
            }
            if (on) muted.add(channels.get(which)[0]);
            else muted.remove(channels.get(which)[0]);
            Settings.MUTED_NOTIFICATION_CHANNELS.save(NotificationGroups.joinChannels(muted));
            row.setSummary(mutedChannelsSummary(Settings.MUTED_NOTIFICATION_CHANNELS.savedValue()));
        });
        AlertDialog dialog = show(builder.setNeutralButton(L10n.t("Unmute all"), null)
                .setPositiveButton(L10n.t("OK"), null));
        if (dialog == null) return;
        // A click listener of its own, so Unmute all clears every check without closing the list.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(tapped -> {
            if (!allowMutation(row, true)) return;
            muted.clear();
            Settings.MUTED_NOTIFICATION_CHANNELS.save("");
            row.setSummary(mutedChannelsSummary(""));
            for (int i = 0; i < channels.size(); i++) {
                ticked[i] = false;
                dialog.getListView().setItemChecked(i, false);
            }
        });
    }

    /** The row that opens the tab order list. Its summary names the order chosen. */
    Row tabOrderRow(Context context) {
        Row row = new Row(context);
        row.setKey(Settings.TAB_ORDER.key);
        row.setPersistent(false);
        row.setTitle(L10n.t("Tab order"));
        row.setSummary(tabOrderSummary(Settings.TAB_ORDER.savedValue()));
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
            case "NEWS":
                return L10n.t("Activity");
            case "CREATION":
                return L10n.t("Create");
            case "PROFILE":
                return L10n.t("Profile");
            default:
                return L10n.t("Home");
        }
    }

    /**
     * Every tab with a box for whether it's on the bar and a handle to drag it up or down, like
     * Piko's (#207, {@link TabPicker}). OK saves what changed, Reset puts the list back to
     * Instagram's order and tabs without saving, and Cancel leaves everything as it was. The bar is
     * built as Instagram starts, so it shows after a restart.
     */
    void showTabOrder(Row row) {
        Context context = getActivity();
        if (context == null) return;
        List<String> order = TabPicker.rows(Settings.TAB_ORDER.savedValue());
        Set<String> ticked = new LinkedHashSet<>();
        for (String tab : order) {
            if (TabPicker.shown(tab)) ticked.add(tab);
        }
        LinearLayout list = TabPicker.list(context, order, ticked);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        boolean[] reset = {false};
        AlertDialog dialog = show(new AlertDialog.Builder(context)
                .setTitle(L10n.t("Show and reorder tabs"))
                .setView(scroll)
                .setNeutralButton(L10n.t("Reset"), null)
                .setNegativeButton(L10n.t("Cancel"), null)
                .setPositiveButton(L10n.t("OK"), (shown, which) -> {
                    if (!allowMutation(row, true)) return;
                    AbstractPreferenceFragment.restartNoteOnce(context,
                            () -> TabPicker.save(TabPicker.orderOf(list), TabPicker.tickedOf(list), reset[0]));
                    row.setSummary(tabOrderSummary(Settings.TAB_ORDER.savedValue()));
                }));
        if (dialog == null) return;
        // A click listener of its own, so Reset changes the list without closing it.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(tapped -> {
            List<String> instagrams = TabPicker.rows("");
            Set<String> defaults = new LinkedHashSet<>();
            for (String tab : instagrams) {
                if (TabPicker.shownByDefault(tab)) defaults.add(tab);
            }
            TabPicker.fill(list, instagrams, defaults);
            reset[0] = true;
        });
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
            case ALWAYS_HOME:
                return L10n.t("Always Home");
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
        if (tab == StartTab.HOME) {
            return L10n.t("Instagram opens the way it always has, which can be Reels if you left it there.");
        }
        if (tab == StartTab.ALWAYS_HOME) {
            return L10n.t("Instagram opens on Home when you start it from its icon, even if you left it on Reels. "
                    + "A notification or a link still opens what it's for. Restart Instagram to see the change.");
        }
        return L10n.f("Instagram opens on %1$s when you start it from its icon. A notification or a link still opens "
                + "what it's for. If that tab isn't on your bar, it opens on Home. Restart Instagram to see the change.",
                L10n.isolate(startTabLabel(tab)));
    }

    /** Which tab bar Instagram draws. Its summary says what the choice does. */
    static TabBarLayoutRow tabBarLayoutRow(Context context) {
        TabBarLayoutRow row = new TabBarLayoutRow(context);
        row.setKey(Settings.TAB_BAR_LAYOUT.key);
        row.setTitle(L10n.t("Tab bar layout"));
        row.setDialogTitle(L10n.t("Tab bar layout"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        TabBarLayoutChoice[] choices = TabBarLayoutChoice.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = tabBarLayoutLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.TAB_BAR_LAYOUT.savedValue().name());
        return row;
    }

    /** What the list calls [choice]. */
    static String tabBarLayoutLabel(TabBarLayoutChoice choice) {
        switch (choice) {
            case SWIPE:
                return L10n.t("Swipe between tabs");
            case CLASSIC:
                return L10n.t("Classic");
            default:
                return L10n.t("Instagram's choice");
        }
    }

    /** What the bar looks like with [choice], for the row's summary. */
    static String tabBarLayoutSummary(TabBarLayoutChoice choice) {
        switch (choice) {
            case SWIPE:
                return L10n.t("Keeps the newer bar, with Messages on it, and you can swipe sideways between every tab, even "
                        + "if Instagram moves your account back to the older one. Restart Instagram to see the change.");
            case CLASSIC:
                return L10n.t("Keeps the older bar, with Create on it and Messages at the top of Home. Swiping only moves "
                        + "between Home and the screens beside it. Restart Instagram to see the change.");
            default:
                return L10n.t("Instagram picks the tab bar for your account, and it can switch between the two on its own.");
        }
    }

    /** What the list calls [hue]. */
    static String accentHueLabel(AccentHue hue) {
        switch (hue) {
            case PURPLE:
                return L10n.t("Purple");
            case PINK:
                return L10n.t("Pink");
            case RED:
                return L10n.t("Red");
            case ORANGE:
                return L10n.t("Orange");
            case GREEN:
                return L10n.t("Green");
            case TEAL:
                return L10n.t("Teal");
            case WALLPAPER:
                return L10n.t("Material You");
            default:
                return L10n.t("Instagram blue");
        }
    }

    /** What the list calls [step]. */
    static String glassLiftLabel(GlassLift step) {
        switch (step) {
            case MORE:
                return L10n.t("A little more");
            case MOST:
                return L10n.t("More");
            default:
                return L10n.t("Standard");
        }
    }

    /** What the list calls [step]. */
    static String glassCornersLabel(GlassCorners step) {
        switch (step) {
            case ROUNDER:
                return L10n.t("Rounder");
            case ROUND:
                return L10n.t("Fully round");
            default:
                return L10n.t("Standard");
        }
    }

    /** What the list calls [step]. */
    static String glassHeightLabel(GlassHeight step) {
        switch (step) {
            case COMPACT:
                return L10n.t("Compact");
            case TALL:
                return L10n.t("Tall");
            default:
                return L10n.t("Standard");
        }
    }

    /** What the list calls [count]. */
    static String holdFingersLabel(HoldFingers count) {
        return count == HoldFingers.THREE ? L10n.t("Three fingers") : L10n.t("Two fingers");
    }

    /** What the list calls [time]. */
    static String holdTimeLabel(HoldTime time) {
        switch (time) {
            case HALF_SECOND:
                return L10n.t("Half a second");
            case ONE_AND_A_HALF_SECONDS:
                return L10n.t("1.5 seconds");
            default:
                return L10n.t("1 second");
        }
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

    /** Where the follow chip goes and how big it is (#157). Its values are the setting's own names. */
    static FollowLabelPlaceRow followLabelPlaceRow(Context context) {
        FollowLabelPlaceRow row = new FollowLabelPlaceRow(context);
        row.setKey(Settings.FOLLOW_LABEL_PLACE.key);
        row.setTitle(L10n.t("Follow label"));
        row.setDialogTitle(L10n.t("Follow label"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        FollowLabelPlace[] places = FollowLabelPlace.values();
        CharSequence[] entries = new CharSequence[places.length];
        CharSequence[] values = new CharSequence[places.length];
        for (int i = 0; i < places.length; i++) {
            entries[i] = followLabelPlaceLabel(places[i]);
            values[i] = places[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.FOLLOW_LABEL_PLACE.savedValue().name());
        return row;
    }

    /** What the list calls [place]. */
    static String followLabelPlaceLabel(FollowLabelPlace place) {
        return place == FollowLabelPlace.UNDER_PICTURE ? L10n.t("Small, under the picture") : L10n.t("Under the counts");
    }

    /** Where the chip goes with [place], for the row's summary. */
    static String followLabelPlaceSummary(FollowLabelPlace place) {
        return place == FollowLabelPlace.UNDER_PICTURE
                ? L10n.t("A smaller chip, centered under the profile picture. A profile with no room there gets the small chip under the counts.")
                : L10n.t("The chip sits under the posts, followers and following counts.");
    }

    /** Which bars Full screen Reels and Home hide (#231). Its values are the setting's own names. */
    static HiddenBarsRow hiddenBarsRow(Context context) {
        HiddenBarsRow row = new HiddenBarsRow(context);
        row.setKey(Settings.FULL_SCREEN_HIDES.key);
        row.setTitle(L10n.t("Bars to hide"));
        row.setDialogTitle(L10n.t("Bars to hide"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        HiddenBars[] choices = HiddenBars.values();
        CharSequence[] entries = new CharSequence[choices.length];
        CharSequence[] values = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            entries[i] = hiddenBarsLabel(choices[i]);
            values[i] = choices[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.FULL_SCREEN_HIDES.savedValue().name());
        return row;
    }

    /** What the list calls [bars]. */
    static String hiddenBarsLabel(HiddenBars bars) {
        if (bars == HiddenBars.STATUS_BAR) return L10n.t("The status bar only");
        if (bars == HiddenBars.NAVIGATION_BAR) return L10n.t("The navigation bar only");
        return L10n.t("Both bars");
    }

    /** What full screen hides with [bars], for the row's summary. */
    static String hiddenBarsSummary(HiddenBars bars) {
        if (bars == HiddenBars.STATUS_BAR) {
            return L10n.t("Full screen Reels and Home hide only the status bar at the top. The navigation bar stays.");
        }
        if (bars == HiddenBars.NAVIGATION_BAR) {
            return L10n.t("Full screen Reels and Home hide only the navigation bar at the bottom. The status bar stays.");
        }
        return L10n.t("Full screen Reels and Home hide the status bar and the navigation bar.");
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

    /** What the list calls [size]: its share in the phone's own percent format, or Instagram's size. */
    static String storyRingLabel(StoryRingSize size) {
        if (size == StoryRingSize.INSTAGRAM) return L10n.t("Instagram's size");
        return NumberFormat.getPercentInstance().format(size.percent() / 100.0);
    }

    /** What the rings look like at [size], for the row's summary, with its share in the phone's own percent format. */
    static String storyRingSummary(StoryRingSize size) {
        if (size == StoryRingSize.INSTAGRAM) return L10n.t("The rings are the size Instagram picks for your screen.");
        return L10n.f("The rings are %1$s of the size Instagram picks for your screen.",
                L10n.isolate(NumberFormat.getPercentInstance().format(size.percent() / 100.0)));
    }

    /**
     * The color the rings of stories that haven't been watched are drawn in. Its values are the
     * setting's own names, and its summary is the color chosen.
     */
    static StoryRingColorRow storyRingColorRow(Context context) {
        StoryRingColorRow row = new StoryRingColorRow(context);
        row.setKey(Settings.STORY_RING_COLOR.key);
        row.setTitle(L10n.t("Ring color"));
        row.setDialogTitle(L10n.t("Ring color"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        StoryRingColor[] colors = StoryRingColor.values();
        CharSequence[] entries = new CharSequence[colors.length];
        CharSequence[] values = new CharSequence[colors.length];
        for (int i = 0; i < colors.length; i++) {
            entries[i] = storyRingColorLabel(colors[i]);
            values[i] = colors[i].name();
        }
        row.setEntries(entries);
        row.setEntryValues(values);
        row.setValue(Settings.STORY_RING_COLOR.savedValue().name());
        return row;
    }

    /** What the list calls [color]. */
    static String storyRingColorLabel(StoryRingColor color) {
        switch (color) {
            case BLUE:
                return L10n.t("Blue");
            case TEAL:
                return L10n.t("Teal");
            case PURPLE:
                return L10n.t("Purple");
            case RED:
                return L10n.t("Red");
            case ORANGE:
                return L10n.t("Orange");
            case GOLD:
                return L10n.t("Gold");
            default:
                return L10n.t("Instagram's colors");
        }
    }

    @Override
    protected void updatePreferenceAvailability(Preference preference, Setting<?> setting) {
        super.updatePreferenceAvailability(preference, setting);
        if ((setting == Settings.FONT || setting == Settings.STORY_FONTS || setting == Settings.STORY_EMOJI
                || setting == Settings.EMOJI_FONT) && (Fonts.importActive() || documentRequest != 0)) {
            preference.setEnabled(false);
        }
        if (resumingFromOverview && setting == BaseSettings.PAUSED) preference.setEnabled(false);
        if (setting == Settings.SETTINGS_SHORTCUT || setting == Settings.SETTINGS_LOGO_PRESS
                || setting == Settings.HIDE_MENU_ROW) {
            // The last way into HushGram still on stays on, greyed out under a line saying why.
            boolean last = SettingsEntry.isLastWayIn(setting);
            if (last) preference.setEnabled(false);
            preference.setSummary(last ? L10n.t("The last way into these settings stays on.") : wayInSummary(setting));
        } else if (setting == Settings.ONLY_FOLLOWING) {
            preference.setSummary(setting.isAvailable()
                    ? L10n.t("Removes For you from the choices at the top of Home, so it stays on Following or "
                            + "Favorites. Needs Start Home on Following. Restart Instagram to see the change.")
                    : L10n.t("Turn on Start Home on Following to use this choice."));
        } else if (preference instanceof PlaybackQualityRow) {
            ((PlaybackQualityRow) preference).showSummary();
        } else if (preference instanceof TapToPlayScopeRow) {
            ((TapToPlayScopeRow) preference).showSummary();
        } else if (preference instanceof ReelTapChoiceRow) {
            ((ReelTapChoiceRow) preference).showSummary();
        } else if (preference instanceof BurnInGuardRow) {
            ((BurnInGuardRow) preference).showSummary();
        } else if (preference instanceof BurnInWaitRow) {
            ((BurnInWaitRow) preference).showSummary();
        } else if (preference instanceof ScrollBreakRow) {
            ((ScrollBreakRow) preference).showSummary();
        } else if (preference instanceof StoryTimeModeRow) {
            ((StoryTimeModeRow) preference).showSummary();
        } else if (preference instanceof FollowLabelPlaceRow) {
            ((FollowLabelPlaceRow) preference).showSummary();
        } else if (preference instanceof HiddenBarsRow) {
            ((HiddenBarsRow) preference).showSummary();
        } else if (preference instanceof StartTabRow) {
            ((StartTabRow) preference).showSummary();
        } else if (preference instanceof TabBarLayoutRow) {
            ((TabBarLayoutRow) preference).showSummary();
        } else if (preference instanceof FontRow) {
            ((FontRow) preference).showSummary();
        } else if (preference instanceof EmojiRow) {
            ((EmojiRow) preference).showSummary();
        } else if (preference instanceof LockDelayRow) {
            ((LockDelayRow) preference).showSummary();
        } else if (preference instanceof UnlockWithRow) {
            ((UnlockWithRow) preference).showSummary();
        } else if (preference instanceof StoryRingRow) {
            ((StoryRingRow) preference).showSummary();
        } else if (preference instanceof StoryRingColorRow) {
            ((StoryRingColorRow) preference).showSummary();
        } else if (preference instanceof LikeAnimationRow) {
            ((LikeAnimationRow) preference).showSummary();
        } else if (preference instanceof GlassReelsHideRow) {
            ((GlassReelsHideRow) preference).showSummary();
        } else if (preference instanceof AppIconRow) {
            ((AppIconRow) preference).showSummary();
        }
        holdLocked(preference);
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
        } else if (listPreference instanceof FollowLabelPlaceRow) {
            ((FollowLabelPlaceRow) listPreference).showSummary();
        } else if (listPreference instanceof HiddenBarsRow) {
            ((HiddenBarsRow) listPreference).showSummary();
        } else if (listPreference instanceof StartTabRow) {
            ((StartTabRow) listPreference).showSummary();
        } else if (listPreference instanceof TabBarLayoutRow) {
            ((TabBarLayoutRow) listPreference).showSummary();
        } else if (listPreference instanceof FontRow) {
            ((FontRow) listPreference).showSummary();
        } else if (listPreference instanceof EmojiRow) {
            ((EmojiRow) listPreference).showSummary();
        } else if (listPreference instanceof LockDelayRow) {
            ((LockDelayRow) listPreference).showSummary();
        } else if (listPreference instanceof UnlockWithRow) {
            ((UnlockWithRow) listPreference).showSummary();
        } else if (listPreference instanceof StoryRingRow) {
            ((StoryRingRow) listPreference).showSummary();
        } else if (listPreference instanceof StoryRingColorRow) {
            ((StoryRingColorRow) listPreference).showSummary();
        } else if (listPreference instanceof LikeAnimationRow) {
            ((LikeAnimationRow) listPreference).showSummary();
        } else if (listPreference instanceof GlassReelsHideRow) {
            ((GlassReelsHideRow) listPreference).showSummary();
        } else if (listPreference instanceof AppIconRow) {
            ((AppIconRow) listPreference).showSummary();
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
        field.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        field.setTextDirection(View.TEXT_DIRECTION_LTR);
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
     * The server App-only proxy sends Instagram through. What's typed is saved as host:port (no
     * scheme, no closing slash), and what isn't a server is refused with a toast.
     */
    static ProxyServerRow proxyServerRow(Context context) {
        ProxyServerRow row = new ProxyServerRow(context);
        row.setKey(Settings.APP_PROXY_SERVER.key);
        row.setTitle(L10n.t("Proxy server"));
        row.setDialogTitle(L10n.t("Proxy server"));
        row.setDialogMessage(L10n.t("An HTTP proxy's address and port, with a colon between them, like "
                + "192.168.1.10:8080. Proxies that need a password, and SOCKS proxies, don't work here. "
                + "Instagram needs a restart to use it."));
        row.setPositiveButtonText(L10n.t("Save"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setSingleLine(true);
        field.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        field.setTextDirection(View.TEXT_DIRECTION_LTR);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint("192.168.1.10:8080");
        row.setText(Settings.APP_PROXY_SERVER.savedValue());
        row.setOnPreferenceChangeListener((preference, typed) -> {
            String raw = typed == null ? "" : typed.toString();
            String server = AppProxy.normalize(raw);
            if (server == null) {
                Utils.showToastShort(L10n.t("That isn't a proxy server. Try 10.0.0.2:8080."));
                return false;
            }
            if (server.equals(raw)) return true;
            // Keeps the plain host:port in place of what was typed, as the sharing domain row does.
            ((ProxyServerRow) preference).setText(server);
            return false;
        });
        return row;
    }

    /** What the proxy server row says: where Instagram goes out, or that it goes out directly. */
    static String proxyServerSummary(String text) {
        String server = AppProxy.normalize(text);
        return server == null || server.isEmpty()
                ? L10n.t("No server set. Until there is one, Instagram goes out directly.")
                : L10n.f("Instagram goes through %1$s.", L10n.isolate(server));
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
        row.setTitle(L10n.t("Download file name"));
        row.setDialogTitle(L10n.t("Download file name"));
        row.setDialogMessage(L10n.f("%1$s becomes the date and time of the save, %2$s the video's number on "
                        + "Instagram, %3$s who posted it and %4$s the day it was posted. What a save doesn't know is "
                        + "left out, and a name with none of these gets the date added. When the name is already in "
                        + "the folder, the time of the save goes on the end. Invalid characters become underscores. "
                        + "Leave it blank to use the default, %5$s.",
                L10n.isolate(FileNameTemplate.DATE), L10n.isolate(FileNameTemplate.VIDEO_ID),
                L10n.isolate(FileNameTemplate.OWNER), L10n.isolate(FileNameTemplate.POSTED),
                L10n.isolate(FileNameTemplate.DEFAULT)) + " "
                + L10n.f("%1$s is the post's shortcode. Stories and unknown posts leave it out. Using it also names photos.",
                L10n.isolate(FileNameTemplate.SHORTCODE)));
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

    /** The current template's media types, with the default photo name when it doesn't opt photos in. */
    static String fileNameSummary(String template) {
        if (FileNameTemplate.usesShortcode(template)) {
            return L10n.f("Videos and photos use %1$s. Unknown codes are left out.", L10n.isolate(template));
        }
        return L10n.f("Videos are named %1$s. Photos are always named %2$s followed by the date and time.",
                L10n.isolate(template), L10n.isolate(FileNameTemplate.PHOTO_PREFIX));
    }

    /** "Videos go to Movies/Instagram and photos to Pictures/Instagram." for the folder [leaf]. */
    static String folderSummary(String leaf) {
        String videos = Environment.DIRECTORY_MOVIES + "/" + leaf;
        String photos = Environment.DIRECTORY_PICTURES + "/" + leaf;
        return L10n.f("Videos go to %1$s and photos to %2$s.", L10n.isolate(videos), L10n.isolate(photos));
    }

    static Preference mark(Preference row, String icon) {
        row.setIcon(SettingsIcons.icon(row.getContext(), icon, ScreenColors.DEFAULT.heading));
        return row;
    }

    static Preference info(Context context, String title, String summary) {
        Preference preference = new Row(context);
        preference.setTitle(title);
        preference.setSummary(summary);
        preference.setSelectable(false);
        preference.setPersistent(false);
        return preference;
    }

    /**
     * Closes every dialog this page has open over itself: its lists and notes, a row's choices or
     * text box, and Set a PIN. The lock's cover over the settings goes in the settings' own window,
     * under these, so once the lock is back they'd still show what they show (the hidden chats, by
     * name) and still take a tap. Closed like a tap outside them, nothing in them is saved.
     */
    void closeDialogs() {
        for (Dialog dialog : new ArrayList<>(shownDialogs)) dialog.dismiss();
        shownDialogs.clear();
        mutatingDialogs.clear();
        PinLock.closeNew();
        PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) closeRowDialogs(screen);
    }

    private void closeMutatingDialogs() {
        for (Dialog dialog : new ArrayList<>(mutatingDialogs)) dialog.dismiss();
        mutatingDialogs.clear();
        PinLock.closeNew();
        for (Preference row : everyRow()) {
            if (!lockable(row) || !(row instanceof DialogPreference)) continue;
            Dialog dialog = ((DialogPreference) row).getDialog();
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }
    }

    private static void closeRowDialogs(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row instanceof PreferenceGroup) closeRowDialogs((PreferenceGroup) row);
            if (!(row instanceof DialogPreference)) continue;
            Dialog dialog = ((DialogPreference) row).getDialog();
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }
    }

    /**
     * Shows a dialog over this page and remembers it, so it goes with the page. Nothing shows once
     * the view is gone or the activity is finishing. Answers the dialog, or null when none showed.
     */
    @Nullable
    private AlertDialog show(AlertDialog.Builder builder) {
        return show(builder, true);
    }

    @Nullable
    private AlertDialog show(AlertDialog.Builder builder, boolean mutating) {
        Activity activity = getActivity();
        if (getView() == null || activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        AlertDialog dialog = builder.show();
        ScreenColors.dialog(dialog);
        shownDialogs.add(dialog);
        if (mutating) mutatingDialogs.add(dialog);
        dialog.setOnDismissListener(shown -> {
            shownDialogs.remove(dialog);
            mutatingDialogs.remove(dialog);
        });
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
        /** The section's icon, one of {@link SettingsIcons}'. */
        String icon = SettingsIcons.SETTINGS;
        /** One short sentence on what the section is for, shown under its expandable heading. */
        @Nullable String intro;
        private final int headingLayout;
        private final int buttonLayout;
        boolean collapsible;
        boolean expanded;

        Heading(Context context) {
            super(context);
            headingLayout = getLayoutResource();
            buttonLayout = new Preference(context).getLayoutResource();
            setSelectable(false);
            setPersistent(false);
        }

        void display(boolean collapsible, boolean expanded) {
            if (this.collapsible == collapsible && this.expanded == expanded) return;
            this.collapsible = collapsible;
            this.expanded = expanded;
            setLayoutResource(collapsible ? buttonLayout : headingLayout);
            setSelectable(collapsible);
            setSummary(collapsible ? intro : null);
            notifyChanged();
        }

        @Override public boolean isEnabled() {
            // PreferenceCategory normally disables its heading, independently of its children.
            return collapsible && !super.shouldDisableDependents();
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setAccessibilityHeading(true);
            showAllText(view);
            if (collapsible) {
                ScreenColors.row(view, this);
                view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
            } else {
                view.setAccessibilityDelegate(null);
                ScreenColors.heading(view);
            }
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
            view.setAccessibilityLiveRegion(("hushgram_export_configuration".equals(getKey())
                    || "hushgram_settings_search_empty".equals(getKey()))
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
            // A greyed-out row's line says why, so it stays in sight.
            DescriptionInfo.bind(view, isEnabled() ? getSummary() : null);
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
            mark(this, SettingsIcons.DOWNLOADS);
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
            cancel.setTextColor(ScreenColors.dimmedWhenDisabled(colors.heading));
            ScreenColors.styleIconAction(cancel);
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

    /** Compact edit forms keep the input first and let their help scroll above the actions. */
    private static View editDialogBody(EditTextPreference preference) {
        Context context = preference.getContext();
        ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad / 2, pad, pad);
        EditText field = preference.getEditText();
        if (field.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) field.getParent()).removeView(field);
        }
        content.addView(field, new LinearLayout.LayoutParams(-1, -2));
        if (!TextUtils.isEmpty(preference.getDialogMessage())) {
            TextView help = new TextView(context);
            help.setId(android.R.id.message);
            help.setText(preference.getDialogMessage());
            help.setTextSize(14);
            help.setTextColor(colors.summary);
            help.setPadding(0, pad, 0, 0);
            content.addView(help, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        return scroll;
    }

    /**
     * The words Hide reels by caption words looks for, typed with commas or new lines between them,
     * as Hushfeed's Blocked caption words are, so a list from there pastes in as it is.
     */
    static WordsRow blockedWordsRow(Context context) {
        WordsRow row = new WordsRow(context);
        row.setKey(Settings.REELS_BLOCKED_WORDS.key);
        row.setTitle(L10n.t("Blocked words"));
        row.setDialogTitle(L10n.t("Blocked words"));
        row.setDialogMessage(L10n.t("Words or phrases, with a comma or a new line between them. Capital letters don't "
                + "matter, and a hashtag works too."));
        row.setPositiveButtonText(L10n.t("Save"));
        row.setNegativeButtonText(L10n.t("Cancel"));
        EditText field = row.getEditText();
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMaxLines(6);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(ConfigurationBackup.MAX_WORDS_TEXT)});
        row.setText(Settings.REELS_BLOCKED_WORDS.savedValue());
        return row;
    }

    /** What the blocked words row says under its title: how many words it holds, or that it has none. */
    static String blockedWordsSummary(String stored) {
        int count = CaptionWordReels.words(stored).size();
        return count == 0 ? L10n.t("No words yet")
                : L10n.quantity(count, "%1$d word", "%1$d words", count);
    }

    /**
     * The blocked words' row. Its summary follows its text, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class WordsRow extends EditTextPreference {
        WordsRow(Context context) {
            super(context);
        }

        @Override protected View onCreateDialogView() { return editDialogBody(this); }

        @Override protected void onBindDialogView(View view) {
            getEditText().setText(getText());
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(blockedWordsSummary(text));
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
     * The save folder's row. Its summary follows its text, whoever sets it: the person, the shared
     * page syncing it from the setting, or an import.
     */
    static final class FolderRow extends EditTextPreference {
        FolderRow(Context context) {
            super(context);
        }

        @Override protected View onCreateDialogView() { return editDialogBody(this); }

        @Override protected void onBindDialogView(View view) {
            // EditTextPreference's default binder would move the field out of our scroll body.
            getEditText().setText(getText());
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
    static final class SharingDomainRow extends ValidatedEditRow {
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

        @Override String validatedValue(String typed) {
            return SharingDomain.normalize(typed);
        }

        @Override String validationError() {
            return L10n.t("That isn't a domain. Type one like example.com, or leave it blank.");
        }
    }

    /**
     * The like animation's row. Its summary follows its value, and it answers it itself, as the ring
     * size's does. With Instagram's hearts to hand, its list shows and plays the one tapped under it,
     * and OK saves the choice, so each can be seen before it's picked (#299).
     */
    static final class LikeAnimationRow extends ListPreference {
        /** How tall the heart under the list is, in dp. */
        static final int PREVIEW_DP = 160;

        @Nullable
        private String summary;
        /** The heart for an animation's name and what plays one: Instagram's, unless a test swaps them. */
        Function<String, Drawable> hearts = name -> LikeAnimation.preview(getContext(), name);
        Consumer<Drawable> player = LikeAnimation::playPreview;
        /** The choice the list shows checked, which OK saves. */
        private int picked = -1;
        /** The heart under the list while it's open with one, else null. */
        @Nullable
        ImageView preview;

        LikeAnimationRow(Context context) {
            super(context);
        }

        @Override
        protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
            super.onPrepareDialogBuilder(builder);
            preview = null;
            CharSequence[] entries = getEntries();
            CharSequence[] values = getEntryValues();
            if (entries == null || values == null || values.length == 0) return;
            picked = findIndexOfValue(getValue());
            Drawable heart = hearts.apply(values[Math.max(picked, 0)].toString());
            if (heart == null) return;
            int height = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, PREVIEW_DP,
                    getContext().getResources().getDisplayMetrics()));
            ImageView image = new ImageView(getContext());
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setAdjustViewBounds(true);
            image.setMinimumHeight(height);
            image.setMaxHeight(height);
            image.setPadding(height / 12, height / 12, height / 12, height / 12);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (picked >= 0) image.setImageDrawable(heart);
            preview = image;
            builder.setView(image);
            builder.setSingleChoiceItems(entries, picked, (dialog, which) -> show(which));
            builder.setPositiveButton(android.R.string.ok, this);
            builder.setNegativeButton(getNegativeButtonText(), this);
        }

        /** A tap on a choice: it's checked, and its heart shows and plays under the list. */
        private void show(int which) {
            picked = which;
            CharSequence[] values = getEntryValues();
            ImageView image = preview;
            if (image == null || values == null || which < 0 || which >= values.length) return;
            Drawable heart = hearts.apply(values[which].toString());
            image.setImageDrawable(heart);
            if (heart != null) player.accept(heart);
        }

        @Override
        protected void onDialogClosed(boolean positiveResult) {
            if (preview == null) {
                super.onDialogClosed(positiveResult);
                return;
            }
            preview = null;
            CharSequence[] values = getEntryValues();
            if (!positiveResult || values == null || picked < 0 || picked >= values.length) return;
            String value = values[picked].toString();
            if (callChangeListener(value)) setValue(value);
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

        /** Its list takes the screen's colours, as the other rows' dialogs do, and the picked heart plays as it opens. */
        @Override
        protected void showDialog(Bundle state) {
            super.showDialog(state);
            if (getDialog() instanceof AlertDialog) {
                AlertDialog dialog = (AlertDialog) getDialog();
                ScreenColors.dialog(dialog);
                if (preview != null) letListGiveWay(dialog);
            }
            Drawable heart = preview == null ? null : preview.getDrawable();
            if (heart != null) player.accept(heart);
        }

        /**
         * With a list and a view under it, the dialog lays its parts out top to bottom, and dozens of
         * animations took all the height: the heart and the buttons got none. The list's panel takes
         * only what's left after them.
         */
        private static void letListGiveWay(AlertDialog dialog) {
            ListView list = dialog.getListView();
            if (list == null || !(list.getParent() instanceof View)) return;
            View panel = (View) list.getParent();
            if (!(panel.getParent() instanceof LinearLayout)) return;
            panel.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    /** Save and the keyboard's Done share validation, leaving a rejected value ready to correct. */
    static abstract class ValidatedEditRow extends EditTextPreference {
        ValidatedEditRow(Context context) {
            super(context);
            getEditText().setImeOptions(EditorInfo.IME_ACTION_DONE);
            getEditText().addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    getEditText().setError(null);
                }
                @Override public void afterTextChanged(Editable text) { }
            });
        }

        @Nullable abstract String validatedValue(String typed);
        abstract String validationError();

        @Override protected View onCreateDialogView() { return editDialogBody(this); }

        @Override protected void onBindDialogView(View view) {
            getEditText().setText(getText());
        }

        @Override protected void showDialog(Bundle state) {
            super.showDialog(state);
            Dialog shown = getDialog();
            if (!(shown instanceof AlertDialog)) return;
            AlertDialog dialog = (AlertDialog) shown;
            ScreenColors.dialog(dialog);
            fitAboveKeyboard(dialog);
            getEditText().setError(null);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> saveEditor(dialog));
            getEditText().setOnEditorActionListener((view, action, key) -> {
                boolean done = action == EditorInfo.IME_ACTION_DONE;
                boolean enter = key != null && key.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (!done && !enter) return false;
                if (done || key.getAction() == KeyEvent.ACTION_UP) saveEditor(dialog);
                return true;
            });
        }

        private void saveEditor(AlertDialog dialog) {
            if (dialog != getDialog() || !dialog.isShowing() || !isEnabled()) return;
            EditText field = getEditText();
            String value = validatedValue(field.getText().toString());
            if (value == null) {
                field.setError(validationError());
                field.requestFocus();
                return;
            }
            if (!callChangeListener(value)) return;
            setText(value);
            dialog.dismiss();
        }
    }

    static final class ProxyServerRow extends ValidatedEditRow {
        ProxyServerRow(Context context) {
            super(context);
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(proxyServerSummary(text));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            view.setAccessibilityDelegate(new RowSemantics(this, Button.class));
        }

        @Override String validatedValue(String typed) {
            return AppProxy.normalize(typed);
        }

        @Override String validationError() {
            return L10n.t("That isn't a proxy server. Try 10.0.0.2:8080.");
        }
    }

    static final class PlaceRow extends ValidatedEditRow {
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

        @Override String validatedValue(String typed) {
            return typed.trim().isEmpty() || SpoofLocation.parse(typed) != null ? typed : null;
        }

        @Override String validationError() {
            return L10n.t("That isn't a place. Type a latitude and a longitude with a comma between them.");
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
            return FileNameTemplate.videoName(FileNameTemplate.sanitize(text), when,
                    PostDetails.of("123456").withPostId("123456")) + ".mp4";
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
            label.setText(L10n.t("Example file name"));
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
        /** What the chosen tab's long press does: open HushGram, the quality picker (#93) or Ghost mode (#255). */
        enum Use { SETTINGS, QUALITY, GHOST }
        private String summary;
        private final Use use;
        NavigationRow(Context context) { this(context, Use.SETTINGS); }
        NavigationRow(Context context, Use use) {
            super(context);
            this.use = use;
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
            if (use == Use.GHOST) {
                summary = target == NavigationTarget.OFF
                        ? L10n.t("Tab long presses keep Instagram's own action. Choose one to turn Ghost mode on or off instead.")
                        : L10n.f("Long press %1$s to turn Ghost mode on or off instead of that tab's usual action, and a "
                                + "toast says which way it went. Normal taps and other tabs stay the same. If the same tab "
                                + "opens HushGram or picks the playback quality, that comes first.", navigationLabel(target));
                setSummary(summary);
                return;
            }
            if (use == Use.QUALITY) {
                summary = target == NavigationTarget.OFF
                        ? L10n.t("Tab long presses keep Instagram's own action. Choose one to pick the playback quality instead.")
                        : L10n.f("Long press %1$s to pick the playback quality instead of that tab's usual action. "
                                + "Normal taps and other tabs stay the same. Needs Default playback quality on. "
                                + "If the same tab opens HushGram, that comes first.", navigationLabel(target));
                setSummary(summary);
                return;
            }
            summary = target == NavigationTarget.OFF
                    ? L10n.t("Tab long presses keep Instagram's own action. Choose one to open HushGram instead.")
                    : L10n.f("Long press %1$s to open HushGram instead of that tab's usual action. "
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

    /** The Burn-in guard choice (#162). Its summary says what the choice does, as the reel tap choice's does. */
    static final class BurnInGuardRow extends ListPreference {
        BurnInGuardRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            BurnInMode mode = BurnInMode.OFF;
            for (BurnInMode candidate : BurnInMode.values()) {
                if (candidate.name().equals(getValue())) mode = candidate;
            }
            setSummary(burnInSummary(mode));
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

    /** The Burn-in guard wait (#162). Its summary says the choice, as the Burn-in guard row's does. */
    static final class BurnInWaitRow extends ListPreference {
        BurnInWaitRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            BurnInWait wait = BurnInWait.FIVE_SECONDS;
            for (BurnInWait candidate : BurnInWait.values()) {
                if (candidate.name().equals(getValue())) wait = candidate;
            }
            setSummary(burnInWaitSummary(wait));
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

    /** Scroll break's time (#297). Its summary says the choice, as Dim after's does. */
    static final class ScrollBreakRow extends ListPreference {
        ScrollBreakRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            ScrollBreakTime time = ScrollBreakTime.OFF;
            for (ScrollBreakTime candidate : ScrollBreakTime.values()) {
                if (candidate.name().equals(getValue())) time = candidate;
            }
            setSummary(scrollBreakSummary(time));
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

    /** The home screen icon (#284). Its summary says the choice, as Scroll break's does. */
    static final class AppIconRow extends ListPreference {
        AppIconRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            AppIconChoice icon = AppIconChoice.INSTAGRAM;
            for (AppIconChoice candidate : AppIconChoice.values()) {
                if (candidate.name().equals(getValue())) icon = candidate;
            }
            setSummary(appIconSummary(icon));
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
            if (getDialog() instanceof AlertDialog) {
                AlertDialog dialog = (AlertDialog) getDialog();
                ListView choices = dialog.getListView();
                if (choices != null && choices.getAdapter() != null) {
                    // Each choice shows its actual icon before its name (#321, #327).
                    DialogChoices.swap(choices, new IconChoices(getContext(), choices.getAdapter(), AppIconChoice.values()));
                }
                ScreenColors.dialog(dialog);
            }
        }
    }

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

        /** The delay a value of the list names, Right away for one it doesn't. */
        static LockDelay delayOf(@Nullable Object value) {
            for (LockDelay candidate : LockDelay.values()) {
                if (candidate.name().equals(value)) return candidate;
            }
            return LockDelay.RIGHT_AWAY;
        }

        void showSummary() {
            LockDelay delay = delayOf(getValue());
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

    /** What unlocks the messages; its summary says what the choice does. */
    static final class UnlockWithRow extends ListPreference {
        UnlockWithRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            // Before Android 11 the phone's lock asks whatever was saved, so the row says so.
            super.setValue(Build.VERSION.SDK_INT < 30 && UnlockWith.BIOMETRICS.name().equals(value) ? UnlockWith.PHONE_LOCK.name() : value);
            showSummary();
        }

        void showSummary() {
            setSummary(UnlockWith.PIN.name().equals(getValue())
                    ? L10n.t("A PIN you set here unlocks your messages, in place of your screen lock. It can't be recovered, only cleared by clearing Instagram's storage.")
                    : UnlockWith.BIOMETRICS.name().equals(getValue())
                    ? L10n.t("Only your fingerprint or face unlocks your messages. If the phone has none set up, your screen lock does.")
                    : L10n.t("Your fingerprint, face or screen lock unlocks your messages."));
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

    static final class AccentHueRow extends ListPreference {
        AccentHueRow(Context context) {
            super(context);
        }

        /** The summary is the color chosen, or why Material You can't be, on Android 11 and older. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            AccentHue hue = Settings.ACCENT_HUE.defaultValue;
            for (AccentHue candidate : AccentHue.values()) {
                if (candidate.name().equals(value)) hue = candidate;
            }
            setSummary(hue == AccentHue.WALLPAPER && !MaterialYou.available()
                    ? L10n.t("Material You needs Android 12 or newer, so Instagram's blue stays.")
                    : accentHueLabel(hue));
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
            if (getDialog() instanceof AlertDialog) {
                AlertDialog dialog = (AlertDialog) getDialog();
                ListView choices = dialog.getListView();
                CharSequence[] values = getEntryValues();
                if (!MaterialYou.available() && choices != null && choices.getAdapter() != null && values != null) {
                    boolean[] off = new boolean[values.length];
                    for (int i = 0; i < values.length; i++) off[i] = AccentHue.WALLPAPER.name().contentEquals(values[i]);
                    DialogChoices.swap(choices, new DisabledChoices(choices.getAdapter(), off));
                }
                ScreenColors.dialog(dialog);
            }
        }
    }

    /**
     * A number setting's row with a slider under its summary (#391). The slider saves as it's let go, as the
     * row's text, so the page syncs, restores and backs it up as it does a text row, and the summary follows
     * the slider while it moves. The row itself opens nothing: the slider is the control.
     */
    static final class SliderRow extends EditTextPreference {
        private static final String SLIDER = "hushgram_slider";
        final IntegerSetting setting;
        /** How far one notch of the slider moves the value. */
        final int step;
        private final String format;

        SliderRow(Context context, IntegerSetting setting, int step, String format) {
            super(context);
            this.setting = setting;
            this.step = step;
            this.format = format;
            setKey(setting.key);
            setSelectable(false);
        }

        /** [text] as a value inside the setting's range, or its default when it isn't a number. */
        int valueOf(@Nullable String text) {
            try {
                int value = Integer.parseInt(text == null ? "" : text.trim());
                return Math.max(setting.minimum(), Math.min(setting.maximum(), value));
            } catch (NumberFormatException notANumber) {
                return setting.defaultValue;
            }
        }

        /** The value at the slider's [progress], a {@link #step} a notch from the start of the range. */
        int valueAt(int progress) {
            return Math.max(setting.minimum(), Math.min(setting.maximum(), setting.minimum() + progress * step));
        }

        int progressOf(int value) {
            return Math.round((value - setting.minimum()) / (float) step);
        }

        /** What the summary says for [value], marking the one the bar has always had. */
        String label(int value) {
            String amount = String.format(format, value);
            return value == setting.defaultValue ? String.format(L10n.t("%1$s (standard)"), amount) : amount;
        }

        @Override
        public void setText(String text) {
            super.setText(text);
            setSummary(label(valueOf(text)));
        }

        /** Saves [value] as the row's text, through the page's listener as any other row's change goes. */
        void choose(int value) {
            String text = String.valueOf(value);
            if (text.equals(getText())) return;
            if (callChangeListener(text)) setText(text);
        }

        @Override
        protected void onClick() {
            // The slider is the control, so there's no dialog to open.
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            showAllText(view);
            ScreenColors.row(view, this);
            SeekBar slider = slider(view);
            if (slider == null) return;
            TextView summary = view.findViewById(android.R.id.summary);
            ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
            slider.setProgressTintList(ScreenColors.dimmedWhenDisabled(colors.accent));
            slider.setThumbTintList(ScreenColors.dimmedWhenDisabled(colors.accent));
            slider.setOnSeekBarChangeListener(null);
            slider.setMax(progressOf(setting.maximum()));
            slider.setProgress(progressOf(valueOf(getText())));
            slider.setEnabled(isEnabled());
            slider.setContentDescription(getTitle());
            describe(slider, label(valueOf(getText())));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                private boolean dragging;

                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    String shown = label(valueAt(progress));
                    if (summary != null) summary.setText(shown);
                    describe(bar, shown);
                    // A key or a screen reader moves it a notch at a time without a drag, so each notch saves.
                    if (!dragging) choose(valueAt(progress));
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    dragging = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    dragging = false;
                    choose(valueAt(bar.getProgress()));
                }
            });
        }

        /** A screen reader reads the value as the summary shows it, not as a share of the slider. */
        private static void describe(SeekBar slider, String shown) {
            if (Build.VERSION.SDK_INT >= 30) slider.setStateDescription(shown);
        }

        /** The row's slider, made under its summary the first time the row's view is bound. */
        @Nullable
        private SeekBar slider(View view) {
            View made = view.findViewWithTag(SLIDER);
            if (made instanceof SeekBar) return (SeekBar) made;
            TextView summary = view.findViewById(android.R.id.summary);
            if (summary == null || !(summary.getParent() instanceof android.view.ViewGroup)) return null;
            android.view.ViewGroup parent = (android.view.ViewGroup) summary.getParent();
            SeekBar slider = new SeekBar(getContext());
            slider.setTag(SLIDER);
            if (parent instanceof RelativeLayout) {
                RelativeLayout.LayoutParams below = new RelativeLayout.LayoutParams(-1, -2);
                below.addRule(RelativeLayout.BELOW, android.R.id.summary);
                parent.addView(slider, below);
            } else {
                parent.addView(slider, parent.indexOfChild(summary) + 1, new android.view.ViewGroup.LayoutParams(-1, -2));
            }
            return slider;
        }
    }

    static final class GlassLiftRow extends ListPreference {
        GlassLiftRow(Context context) {
            super(context);
        }

        /** The summary is the step chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            GlassLift step = GlassLift.STANDARD;
            for (GlassLift candidate : GlassLift.values()) {
                if (candidate.name().equals(value)) step = candidate;
            }
            setSummary(glassLiftLabel(step));
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

    /** Hide the tab bar on Reels' choice of how long (#170). Its summary says what the choice does, as Burn-in guard's does. */
    static final class GlassReelsHideRow extends ListPreference {
        GlassReelsHideRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            GlassReelsHide delay = GlassReelsHide.NEVER;
            for (GlassReelsHide candidate : GlassReelsHide.values()) {
                if (candidate.name().equals(getValue())) delay = candidate;
            }
            setSummary(glassReelsHideSummary(delay));
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

    static final class GlassCornersRow extends ListPreference {
        GlassCornersRow(Context context) {
            super(context);
        }

        /** The summary is the step chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            GlassCorners step = GlassCorners.STANDARD;
            for (GlassCorners candidate : GlassCorners.values()) {
                if (candidate.name().equals(value)) step = candidate;
            }
            setSummary(glassCornersLabel(step));
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

    static final class GlassHeightRow extends ListPreference {
        GlassHeightRow(Context context) {
            super(context);
        }

        /** The summary is the step chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            GlassHeight step = GlassHeight.STANDARD;
            for (GlassHeight candidate : GlassHeight.values()) {
                if (candidate.name().equals(value)) step = candidate;
            }
            setSummary(glassHeightLabel(step));
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

    static final class HoldFingersRow extends ListPreference {
        HoldFingersRow(Context context) {
            super(context);
        }

        /** The summary is the count chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            HoldFingers count = HoldFingers.TWO;
            for (HoldFingers candidate : HoldFingers.values()) {
                if (candidate.name().equals(value)) count = candidate;
            }
            setSummary(holdFingersLabel(count));
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

    static final class CornerSizeRow extends ListPreference {
        CornerSizeRow(Context context) {
            super(context);
        }

        /** The summary is the size chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            CornerSize size = CornerSize.SMALL;
            for (CornerSize candidate : CornerSize.values()) {
                if (candidate.name().equals(value)) size = candidate;
            }
            setSummary(cornerSizeLabel(size));
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

    static final class AmbientSpreadRow extends ListPreference {
        AmbientSpreadRow(Context context) {
            super(context);
        }

        /** The summary is the spread chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            AmbientSpread spread = AmbientSpread.MEDIUM;
            for (AmbientSpread candidate : AmbientSpread.values()) {
                if (candidate.name().equals(value)) spread = candidate;
            }
            setSummary(ambientSpreadLabel(spread));
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

    static final class AmbientSpeedRow extends ListPreference {
        AmbientSpeedRow(Context context) {
            super(context);
        }

        /** The summary is the speed chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            AmbientSpeed speed = AmbientSpeed.MEDIUM;
            for (AmbientSpeed candidate : AmbientSpeed.values()) {
                if (candidate.name().equals(value)) speed = candidate;
            }
            setSummary(ambientSpeedLabel(speed));
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

    static final class StoryBarRow extends ListPreference {
        StoryBarRow(Context context) {
            super(context);
        }

        /** The summary is the thickness chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            StoryBarThickness thickness = StoryBarThickness.TWICE;
            for (StoryBarThickness candidate : StoryBarThickness.values()) {
                if (candidate.name().equals(value)) thickness = candidate;
            }
            setSummary(storyBarLabel(thickness));
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

    static final class TrayBadgeRow extends ListPreference {
        TrayBadgeRow(Context context) {
            super(context);
        }

        /** The summary is the choice. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            TrayBadge badge = TrayBadge.UNVERIFIED;
            for (TrayBadge candidate : TrayBadge.values()) {
                if (candidate.name().equals(value)) badge = candidate;
            }
            setSummary(trayBadgeLabel(badge));
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

    static final class HoldTimeRow extends ListPreference {
        HoldTimeRow(Context context) {
            super(context);
        }

        /** The summary is the time chosen. */
        @Override
        public void setValue(String value) {
            super.setValue(value);
            HoldTime time = HoldTime.ONE_SECOND;
            for (HoldTime candidate : HoldTime.values()) {
                if (candidate.name().equals(value)) time = candidate;
            }
            setSummary(holdTimeLabel(time));
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

    static final class FontRow extends ListPreference {
        FontRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        /** The summary is the sentence for the font chosen, not the entry. */
        void showSummary() {
            FontChoice choice = FontChoice.INSTAGRAM;
            for (FontChoice candidate : FontChoice.values()) {
                if (candidate.name().equals(getValue())) choice = candidate;
            }
            setSummary(fontSummary(choice));
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

    static final class EmojiRow extends ListPreference {
        EmojiRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        /** The summary is the sentence for the emoji chosen, not the entry. */
        void showSummary() {
            setSummary(emojiSummary(emojiChoice(getValue())));
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

    static final class TabBarLayoutRow extends ListPreference {
        TabBarLayoutRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        /** The summary is the sentence for the bar chosen, not the entry. */
        void showSummary() {
            TabBarLayoutChoice choice = TabBarLayoutChoice.INSTAGRAM;
            for (TabBarLayoutChoice candidate : TabBarLayoutChoice.values()) {
                if (candidate.name().equals(getValue())) choice = candidate;
            }
            setSummary(tabBarLayoutSummary(choice));
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

    static final class FollowLabelPlaceRow extends ListPreference {
        FollowLabelPlaceRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            FollowLabelPlace place = FollowLabelPlace.UNDER_COUNTS;
            for (FollowLabelPlace candidate : FollowLabelPlace.values()) {
                if (candidate.name().equals(getValue())) place = candidate;
            }
            setSummary(Settings.FOLLOW_LABEL_PLACE.isAvailable()
                    ? followLabelPlaceSummary(place)
                    : L10n.t("Turn on Show it as a chip to use this choice."));
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

    static final class HiddenBarsRow extends ListPreference {
        HiddenBarsRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            HiddenBars bars = HiddenBars.BOTH;
            for (HiddenBars candidate : HiddenBars.values()) {
                if (candidate.name().equals(getValue())) bars = candidate;
            }
            setSummary(Settings.FULL_SCREEN_HIDES.isAvailable()
                    ? hiddenBarsSummary(bars)
                    : L10n.t("Turn on Full screen Reels, Full screen Home or Full screen Messages to use this choice."));
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

    /**
     * The story ring color's row. Its summary is the color chosen, or what to turn on while the
     * switch above it is off, and it answers that summary itself as the ring size's row does.
     */
    static final class StoryRingColorRow extends ListPreference {
        @Nullable
        private String summary;

        StoryRingColorRow(Context context) {
            super(context);
        }

        @Override
        public void setValue(String value) {
            super.setValue(value);
            showSummary();
        }

        void showSummary() {
            StoryRingColor color = StoryRingColor.INSTAGRAM;
            for (StoryRingColor candidate : StoryRingColor.values()) {
                if (candidate.name().equals(getValue())) color = candidate;
            }
            summary = Settings.STORY_RING_COLOR.isAvailable()
                    ? storyRingColorLabel(color)
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
                if (preference instanceof Heading && ((Heading) preference).collapsible) {
                    boolean expanded = ((Heading) preference).expanded;
                    info.addAction(expanded ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                            : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
                    CharSequence state = expanded ? L10n.t("Expanded") : L10n.t("Collapsed");
                    if (Build.VERSION.SDK_INT >= 30) info.setStateDescription(state);
                    else info.setContentDescription(TextUtils.concat(info.getContentDescription(), ", ", state));
                }
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
            boolean categoryAction = preference instanceof Heading && ((Heading) preference).collapsible
                    && (action == AccessibilityNodeInfo.ACTION_EXPAND && !((Heading) preference).expanded
                    || action == AccessibilityNodeInfo.ACTION_COLLAPSE && ((Heading) preference).expanded);
            if (action == AccessibilityNodeInfo.ACTION_CLICK || categoryAction) {
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
    void showNotice(Context context) {
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
                .setPositiveButton(L10n.t("OK"), null), false);
    }

    @Override
    protected CharSequence initializationErrorTitle(@Nullable Context context) {
        return L10n.t(context, "HushGram settings couldn't open");
    }
}
