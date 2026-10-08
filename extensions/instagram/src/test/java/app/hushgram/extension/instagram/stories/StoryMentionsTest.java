/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.stories;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.PatchFamily;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** See who a story mentions: the pill under the name, the list it opens, and a row opening a profile. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class StoryMentionsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** An account as a stand-in mention holds it. */
    static final class Account {
        final String username;
        final String fullName;
        final String picture;

        Account(String username, String fullName, String picture) {
            this.username = username;
            this.fullName = fullName;
            this.picture = picture;
        }
    }

    /** The page is its media view, the story is its list of accounts, and each mention is its account. */
    static final class Reads implements StoryMentions.Reads {
        boolean throwing;

        @Override public View itemView(Object page) {
            return (View) page;
        }

        @Override public Object media(Object item) {
            if (throwing) throw new IllegalStateException("the story went away");
            return item;
        }

        @Override public List<?> mentions(Object media) {
            return (List<?>) media;
        }

        @Override public Object mentionUser(Object mention) {
            return mention;
        }

        @Override public String username(Object user) {
            return ((Account) user).username;
        }

        @Override public String fullName(Object user) {
            return ((Account) user).fullName;
        }

        @Override public String picture(Object user) {
            return ((Account) user).picture;
        }
    }

    private static final Account ANA = new Account("ana", "Ana Lima", "https://scontent.cdninstagram.com/ana.jpg");
    private static final Account BO = new Account("bo.k", null, null);

    private final Reads reads = new Reads();
    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;
    private LinearLayout header;
    private FrameLayout itemView;

    @Before
    public void open() {
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.SHOW_STORY_MENTIONS.resetToDefault();
        StoryMentions.resetForTests();
        StoryMentions.readsForTests = reads;
        HookStatus.clear();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setId(View.generateViewId());
        StoryMentions.headerIdForTests = header.getId();
        TextView name = new TextView(activity);
        name.setText("someone");
        header.addView(name);
        itemView = new FrameLayout(activity);
        root.addView(itemView);
        root.addView(header);
        activity.setContentView(root);
    }

    @After
    public void close() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        controller.close();
        StoryMentions.readsForTests = null;
        StoryMentions.picturesForTests = null;
        StoryMentions.headerIdForTests = 0;
        StoryMentions.resetForTests();
        Settings.SHOW_STORY_MENTIONS.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOnAndIsTheFamilysOnly() {
        assertTrue(Settings.SHOW_STORY_MENTIONS.get());
        assertEquals(Collections.singletonList(Settings.SHOW_STORY_MENTIONS), PatchFamily.STORY_MENTIONS.switches);
        assertEquals(FamilyNames.STORY_MENTIONS, PatchFamily.STORY_MENTIONS.patchName);
    }

    @Test
    public void twoMentionsPutAPillUnderTheName() {
        bind(ANA, BO);
        StoryMentions.Pill pill = pill();
        assertNotNull(pill);
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals("2 mentions", pill.getText().toString());
        assertEquals("2 mentions, see who this story mentions", pill.getContentDescription().toString());
        assertEquals("the pill goes after the name", header.getChildCount() - 1, header.indexOfChild(pill));
        assertTrue(HookStatus.missing(FamilyNames.STORY_MENTIONS).toString(), HookStatus.missing(FamilyNames.STORY_MENTIONS).isEmpty());
    }

    @Test
    public void oneMentionIsSingular() {
        bind(ANA);
        assertEquals("1 mention", pill().getText().toString());
    }

    /** The same account twice, or one with no username, counts once or not at all. */
    @Test
    public void repeatsAndBlankUsernamesCountOnce() {
        bind(ANA, ANA, new Account("  ", "No one", null), new Account(null, null, null));
        assertEquals("1 mention", pill().getText().toString());
    }

    @Test
    public void aStoryWithNoMentionsGetsNoPill() {
        bind();
        assertNull(pill());
        StoryMentions.bind(itemView, null);
        idle();
        assertNull(pill());
    }

    /** The page shows a story with mentions and then one without, and the pill goes away. */
    @Test
    public void aLaterStoryWithNoneHidesThePill() {
        bind(ANA, BO);
        StoryMentions.Pill pill = pill();
        bind();
        assertEquals(View.GONE, pill.getVisibility());
        bind(BO);
        assertEquals("the same pill comes back", pill, pill());
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals("1 mention", pill.getText().toString());
    }

    @Test
    public void switchedOffOrPausedThePillHides() {
        bind(ANA);
        StoryMentions.Pill pill = pill();
        Settings.SHOW_STORY_MENTIONS.save(false);
        bind(ANA);
        assertEquals(View.GONE, pill.getVisibility());

        Settings.SHOW_STORY_MENTIONS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        bind(ANA);
        assertEquals(View.GONE, pill.getVisibility());
    }

    /** Instagram builds the header after the bind; the pill goes in once it's there. */
    @Test
    public void aHeaderBuiltLateStillGetsThePill() {
        root.removeView(header);
        StoryMentions.bind(itemView, Arrays.asList(ANA, BO));
        idle();
        assertNull(pill());
        root.addView(header);
        shadowOf(Looper.getMainLooper()).idleFor(StoryMentions.TRIES[1], TimeUnit.MILLISECONDS);
        assertNotNull(pill());
        assertEquals("2 mentions", pill().getText().toString());
    }

    /** A page bound again before its header showed gets the newer story's pill, not the older one's. */
    @Test
    public void aNewerBindWins() {
        StoryMentions.bind(itemView, Arrays.asList(ANA, BO));
        StoryMentions.bind(itemView, Collections.singletonList(BO));
        idle();
        assertEquals("1 mention", pill().getText().toString());
    }

    @Test
    public void tappingThePillListsTheAccountsAndARowOpensTheProfile() {
        bind(ANA, BO);
        assertTrue(pill().performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        View content = dialog.getWindow().getDecorView();
        assertNotNull("Ana's full name", find(content, view -> view instanceof TextView && "Ana Lima".contentEquals(((TextView) view).getText())));
        assertNotNull("Ana's username", find(content, view -> view instanceof TextView && "@ana".contentEquals(((TextView) view).getText())));
        assertNotNull("Bo's username alone", find(content, view -> view instanceof TextView && "@bo.k".contentEquals(((TextView) view).getText())));

        View row = find(content, view -> "Ana Lima, @ana".contentEquals(String.valueOf(view.getContentDescription())));
        assertNotNull(row);
        assertTrue(row.performClick());
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://www.instagram.com/ana/", started.getDataString());
        assertEquals("opens inside Instagram, with no chooser", activity.getPackageName(), started.getPackage());
        assertFalse("the list closes", dialog.isShowing());
    }

    @Test
    public void aRowShowsTheAccountsPicture() throws Exception {
        List<String> asked = new ArrayList<>();
        StoryMentions.picturesForTests = (context, url, size) -> {
            asked.add(url);
            return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        };
        bind(ANA, BO);
        pill().performClick();
        Utils.awaitBackgroundTasksForTests();
        idle();
        assertEquals("only Ana has a picture", Collections.singletonList(ANA.picture), asked);
        View content = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNotNull(find(content, view -> view instanceof ImageView && ((ImageView) view).getDrawable() instanceof BitmapDrawable));
    }

    @Test
    public void aThrowingReadKeepsInstagramsHeaderAndIsReported() {
        reads.throwing = true;
        bind(ANA);
        assertNull(pill());
        String missing = HookStatus.missing(FamilyNames.STORY_MENTIONS).toString();
        assertTrue(missing, missing.contains("'story header'"));
        assertTrue(missing, missing.contains(IllegalStateException.class.getName()));
    }

    @Test
    public void aProfileLinkKeepsTheUsernameWhole() {
        assertEquals("https://www.instagram.com/a.b_c/", StoryMentions.profileLink("a.b_c").toString());
        assertEquals("https://www.instagram.com/a%2Fb/", StoryMentions.profileLink("a/b").toString());
    }

    private void bind(Account... mentioned) {
        StoryMentions.bind(itemView, Arrays.asList(mentioned));
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private StoryMentions.Pill pill() {
        return StoryMentions.pillIn(header);
    }

    private static View find(View at, Predicate<View> wanted) {
        if (wanted.test(at)) return at;
        if (at instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) at;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), wanted);
                if (found != null) return found;
            }
        }
        return null;
    }
}
