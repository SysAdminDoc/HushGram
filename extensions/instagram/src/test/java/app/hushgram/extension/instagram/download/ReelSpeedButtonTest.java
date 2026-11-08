/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPopupMenu;
import org.robolectric.shadows.ShadowToast;

import kotlin.jvm.functions.Function1;

import app.hushgram.extension.instagram.reels.HiddenSounds;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * The speed icon beside a reel, for Speed button on reels (#480): it joins the column only with its
 * switch on and only on an organic reel, makes up More's margin only as the last icon there, and a tap
 * offers 0.5x to 3x with the one playing checked and puts the pick on the reel.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class ReelSpeedButtonTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ReelDownloadButton.Bridge originalBridge = ReelDownloadButton.bridge;
    private final ReelNotInterestedButton.Bridge originalNotInterested = ReelNotInterestedButton.bridge;
    private final ReelBlockButton.Bridge originalBlock = ReelBlockButton.bridge;
    private final ReelSoundButton.Bridge originalSound = ReelSoundButton.bridge;
    private final ReelSpeedButton.Menus originalMenus = ReelSpeedButton.menus;
    private final Supplier<String> originalAccounts = DownloadHistory.accounts;
    private final FakeBridge bridge = new FakeBridge();
    private final FakeSpeed speed = new FakeSpeed();
    private ActivityController<Activity> controller;
    private Activity activity;

    private enum Kind { ORGANIC, AD }

    /** A reel: what kind it is. */
    private static final class Item {
        final Kind kind;

        Item(Kind kind) {
            this.kind = kind;
        }
    }

    /** The column beside a reel: the reel it shows and the viewer's config its state holds. */
    private static final class Column {
        final Item item;
        final Object config = new Object();

        Column(Item item) {
            this.item = item;
        }
    }

    /** Reads the fake column and keeps what the icon was made with. */
    private static class FakeBridge implements ReelDownloadButton.Bridge {
        final Object made = new Object();
        int icons;
        Drawable drawable;
        CharSequence description;
        Function1<Object, Object> click;
        long margin;
        int moreTop;

        @Override public Object item(Object column) {
            return ((Column) column).item;
        }

        @Override public Object media(Object item) {
            return "media";
        }

        @Override public String kind(Object item) {
            return ((Item) item).kind.name();
        }

        @Override public int moreTop(Object column, Context context) {
            return moreTop;
        }

        @Override public Object icon(Drawable drawable, CharSequence description, Function1<Object, Object> click, long size,
                                     long margin) {
            icons++;
            this.drawable = drawable;
            this.description = description;
            this.click = click;
            this.margin = margin;
            return made;
        }
    }

    /** The player on screen: what it plays at and what was picked. */
    private static final class FakeSpeed implements ReelSpeedButton.Speed {
        float playing = 1f;
        boolean takes = true;
        final List<Float> chosen = new ArrayList<>();

        @Override public float playing() {
            return playing;
        }

        @Override public boolean choose(float speed) {
            chosen.add(speed);
            if (takes) playing = speed;
            return takes;
        }
    }

    /** The column's config, so the Not interested icon can tell whether it shows. */
    private static final class FakeNotInterested implements ReelNotInterestedButton.Bridge {
        @Override public Object config(Object column) {
            return ((Column) column).config;
        }

        @Override public Object state(Object column) {
            return new Object();
        }

        @Override public void run(Object delegate, Object item, Object state) {
        }
    }

    /** Someone else's reel, for the Block icon. */
    private static final class FakeBlock implements ReelBlockButton.Bridge {
        @Override public Object owner(Object item) {
            return "user 2";
        }

        @Override public String ownerId(Object item) {
            return "2";
        }

        @Override public void block(Object delegate, Object owner, String module, String ownerId) {
        }
    }

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        ReelDownloadButton.bridge = bridge;
        ReelNotInterestedButton.bridge = new FakeNotInterested();
        ReelBlockButton.bridge = new FakeBlock();
        ReelSoundButton.bridge = item -> new HiddenSounds.Sound("111", "Espresso", "Sabrina Carpenter");
        DownloadHistory.accounts = () -> "1";
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        ReelSoundButton.resetForTests();
        ReelTextButton.resetForTests();
        ReelSpeedButton.resetForTests();
        ReelSpeedButton.speed = speed;
        // No glyph of Instagram's here, so one of the framework's stands in.
        ReelSpeedButton.glyph = android.R.drawable.ic_media_ff;
        ReelTextButton.glyph = android.R.drawable.ic_menu_search;
        ReelSoundButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelBlockButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        ReelNotInterestedButton.glyph = android.R.drawable.ic_menu_close_clear_cancel;
        Settings.SPEED_BUTTON_ON_REELS.save(true);
    }

    @After
    public void tearDown() {
        ReelDownloadButton.bridge = originalBridge;
        ReelNotInterestedButton.bridge = originalNotInterested;
        ReelBlockButton.bridge = originalBlock;
        ReelSoundButton.bridge = originalSound;
        ReelSpeedButton.menus = originalMenus;
        DownloadHistory.accounts = originalAccounts;
        ReelNotInterestedButton.resetForTests();
        ReelBlockButton.resetForTests();
        ReelDownloadButton.resetForTests();
        ReelSoundButton.resetForTests();
        ReelTextButton.resetForTests();
        ReelSpeedButton.resetForTests();
        Settings.SPEED_BUTTON_ON_REELS.resetToDefault();
        Settings.TEXT_BUTTON_ON_REELS.resetToDefault();
        Settings.HIDE_SOUND_REELS.resetToDefault();
        Settings.REEL_BLOCK_BUTTON.resetToDefault();
        Settings.REEL_NOT_INTERESTED_BUTTON.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        controller.pause().stop().destroy();
    }

    private static Column reel() {
        return new Column(new Item(Kind.ORGANIC));
    }

    /** The icon, sitting on a reel's page in the activity, as a tap finds it. */
    private View iconOnPage() {
        FrameLayout page = new FrameLayout(activity);
        View icon = new View(activity);
        page.addView(icon, new FrameLayout.LayoutParams(40, 40));
        activity.setContentView(page);
        ShadowLooper.idleMainLooper();
        return icon;
    }

    @Test
    public void theSwitchStartsOffAndNeedsNoRestart() {
        assertFalse(Settings.SPEED_BUTTON_ON_REELS.defaultValue);
        assertFalse(Settings.SPEED_BUTTON_ON_REELS.rebootApp);
        assertTrue("it has its own switch", Settings.SPEED_BUTTON_ON_REELS.getParentSettings().isEmpty());
    }

    @Test
    public void offOrPausedLeavesTheColumnAsItWas() {
        Column column = reel();
        Settings.SPEED_BUTTON_ON_REELS.save(false);
        assertFalse(ReelSpeedButton.on());
        assertNull(ReelSpeedButton.litho(column, null, 120, 30));
        assertNull(ReelSpeedButton.lithoAboveMore(column, null, 120, 30));

        Settings.SPEED_BUTTON_ON_REELS.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(ReelSpeedButton.on());
        assertNull(ReelSpeedButton.litho(column, null, 120, 30));
        assertEquals(0, bridge.icons);

        List<Float> shown = new ArrayList<>();
        ReelSpeedButton.menus = (anchor, playing, chosen) -> shown.add(playing);
        new ReelSpeedButton.Click().invoke(iconOnPage());
        assertTrue("a tap after it went off shows nothing", shown.isEmpty());
    }

    @Test
    public void anAdGetsNoIcon() {
        assertNull(ReelSpeedButton.litho(new Column(new Item(Kind.AD)), null, 120, 30));
        assertEquals(0, bridge.icons);
    }

    @Test
    public void aBuildWithoutTheGlyphGetsNoIcon() {
        ReelSpeedButton.glyph = -1;
        assertNull(ReelSpeedButton.litho(reel(), null, 120, 30));
        assertEquals(0, bridge.icons);
        assertTrue(String.join("\n", HookStatus.report()).contains(ReelSpeedButton.COUNT_REFUSED));
    }

    @Test
    public void theMenuOffersTheSevenSpeedsAsked() {
        StringBuilder labels = new StringBuilder();
        for (float each : ReelSpeedButton.SPEEDS) labels.append(ReelSpeedButton.label(each)).append(' ');
        assertEquals("2.5x between 2x and 3x (#551)", "0.5x 1x 1.5x 1.75x 2x 2.5x 3x", labels.toString().trim());
    }

    @Test
    public void aTapOffersTheSpeedsWithTheOnePlayingCheckedAndAPickPlays() {
        assertTrue(ReelSpeedButton.on());
        assertSame(bridge.made, ReelSpeedButton.litho(reel(), null, 120, 30));
        assertEquals("Playback speed", String.valueOf(bridge.description));
        assertEquals(30, bridge.margin);
        assertEquals(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN), bridge.drawable.getColorFilter());
        assertNotNull(bridge.click);

        speed.playing = 1.5f;
        View icon = iconOnPage();
        assertNull(bridge.click.invoke(icon));
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull("Instagram's own kind of menu opens by the icon", popup);
        Menu items = popup.getMenu();
        assertEquals(7, items.size());
        assertEquals("1.75x", String.valueOf(items.getItem(3).getTitle()));
        for (int i = 0; i < items.size(); i++) {
            assertEquals("only what plays now is checked: " + items.getItem(i).getTitle(), i == 2, items.getItem(i).isChecked());
        }

        assertEquals("2.5x", String.valueOf(items.getItem(5).getTitle()));
        Shadows.shadowOf(popup).getOnMenuItemClickListener().onMenuItemClick(items.getItem(5));
        assertEquals(List.of(2.5f), speed.chosen);
        assertNull("a pick that took shows no toast", ShadowToast.getTextOfLatestToast());

        speed.takes = false;
        assertNull(bridge.click.invoke(icon));
        PopupMenu again = ShadowPopupMenu.getLatestPopupMenu();
        assertTrue("now 2.5x is checked", again.getMenu().getItem(5).isChecked());
        Shadows.shadowOf(again).getOnMenuItemClickListener().onMenuItemClick(again.getMenu().getItem(0));
        ShadowLooper.idleMainLooper();
        assertEquals(List.of(2.5f, 0.5f), speed.chosen);
        assertEquals("with no reel playing it says so", "Speed didn't change", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aPickAfterTheSwitchWentOffChangesNothing() {
        ReelSpeedButton.litho(reel(), null, 120, 30);
        List<ReelSpeedButton.Chosen> held = new ArrayList<>();
        ReelSpeedButton.menus = (anchor, playing, chosen) -> held.add(chosen);
        bridge.click.invoke(iconOnPage());
        assertEquals(1, held.size());
        Settings.SPEED_BUTTON_ON_REELS.save(false);
        held.get(0).chose(2f);
        assertTrue(speed.chosen.isEmpty());
    }

    @Test
    public void overMoreItMakesUpTheMarginOnlyWhenItIsTheLastIcon() {
        Column column = reel();
        ReelNotInterestedButton.remember(column.config, new Object());
        long eightDp = ReelDownloadButton.PX_BITS | 23;
        bridge.moreTop = -23;

        assertSame(bridge.made, ReelSpeedButton.lithoAboveMore(column, null, 124, eightDp));
        assertEquals("alone over More it makes up Save's 23 pixels and the 23 More pulls up",
                ReelDownloadButton.PX_BITS | 46, bridge.margin);

        Settings.TEXT_BUTTON_ON_REELS.save(true);
        ReelSpeedButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the Lens icon after it, that one does and this keeps Save's", eightDp, bridge.margin);

        Settings.TEXT_BUTTON_ON_REELS.save(false);
        Settings.HIDE_SOUND_REELS.save(true);
        ReelSpeedButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("with the sound icon after it, the same", eightDp, bridge.margin);

        Settings.HIDE_SOUND_REELS.save(false);
        ReelTextButton.glyph = -1;
        Settings.TEXT_BUTTON_ON_REELS.save(true);
        ReelSpeedButton.lithoAboveMore(column, null, 124, eightDp);
        assertEquals("a Lens icon this build can't draw leaves it last", ReelDownloadButton.PX_BITS | 46, bridge.margin);
    }

    @Test
    public void nothingThrowsIntoInstagram() {
        ReelSpeedButton.menus = (anchor, playing, chosen) -> {
            throw new NoSuchMethodError("BV2");
        };
        assertNull(new ReelSpeedButton.Click().invoke(iconOnPage()));
        assertNull(new ReelSpeedButton.Click().invoke(null));
        ReelSpeedButton.menus = (anchor, playing, chosen) -> chosen.chose(2f);
        ReelSpeedButton.speed = new ReelSpeedButton.Speed() {
            @Override public float playing() {
                return 1f;
            }

            @Override public boolean choose(float picked) {
                throw new NoSuchMethodError("AZ9");
            }
        };
        assertNull(new ReelSpeedButton.Click().invoke(iconOnPage()));
        ReelDownloadButton.bridge = new FakeBridge() {
            @Override public Object item(Object column) {
                throw new NoSuchMethodError("AZ1");
            }
        };
        assertNull(ReelSpeedButton.litho(reel(), null, 120, 30));
        assertNull(ReelSpeedButton.litho(null, null, 120, 30));
        assertFalse("the Errors were reported, not thrown", HookStatus.report().isEmpty());
    }
}
