/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import app.hushgram.extension.instagram.settings.GhostHeaderButton;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Count on the heart (#541): the heart on Home's header is switched to its number style, and its
 * after-toast styles with it, only while the switch is on and HushGram isn't paused.
 */
@RunWith(RobolectricTestRunner.class)
public class HeartCountTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** Instagram's badge display styles, as the 454 build names them. */
    public enum Style { NUMBERED, DOT, SMALL_DOT, TOAST, LIST_ITEM, OS_SYSTEM_CAPPED, PILL, DESCRIPTION }

    /** A style enum without a number style. */
    public enum DotsOnly { DOT, TOAST }

    /** Stands in for Instagram's ToastingBadge: the setters keep Instagram's names. */
    public static class ToastingBadge extends FrameLayout {
        Style shown = Style.DOT;
        Style fallback = Style.DOT;
        Style cappedFallback = Style.DOT;

        public ToastingBadge(Context context) {
            super(context);
        }

        public void setBadgeDisplayStyle(Style style) {
            shown = style;
        }

        public void setToastFallbackDisplayStyle(Style style) {
            fallback = style;
        }

        public void setToastCappedFallbackDisplayStyle(Style style) {
            cappedFallback = style;
        }
    }

    /** A badge class from a build whose styles have no number. */
    public static final class OlderBadges {
        private OlderBadges() { }

        public static class ToastingBadge extends FrameLayout {
            DotsOnly shown = DotsOnly.DOT;

            public ToastingBadge(Context context) {
                super(context);
            }

            public void setBadgeDisplayStyle(DotsOnly style) {
                shown = style;
            }
        }
    }

    private ActivityController<Activity> screen;
    private FrameLayout header;
    private ToastingBadge heart;
    private ImageView inbox;

    @Before public void prepare() {
        HookStatus.clear();
        HeartCount.forgetForTests();
        screen = Robolectric.buildActivity(Activity.class).setup();
        Context context = screen.get();
        header = new FrameLayout(context);
        LinearLayout end = new LinearLayout(context);
        heart = new ToastingBadge(context);
        heart.addView(new ImageView(context));
        end.addView(heart);
        inbox = new ImageView(context);
        end.addView(inbox);
        header.addView(end);
        // On screen, so the draw hook's posted work runs.
        screen.get().setContentView(header);
    }

    @After public void restore() {
        HeartCount.forgetForTests();
        Settings.HEART_COUNT.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        screen.close();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test public void offLeavesTheDot() {
        assertFalse("off to start", Settings.HEART_COUNT.get());
        HeartCount.show(header);
        assertEquals(Style.DOT, heart.shown);
        assertEquals(Style.DOT, heart.fallback);
        assertFalse(report(), report().contains(HeartCount.SHOWN));
    }

    @Test public void onShowsTheNumberAndKeepsItAfterAToast() {
        Settings.HEART_COUNT.save(true);
        HeartCount.show(header);
        assertEquals(Style.NUMBERED, heart.shown);
        assertEquals(Style.NUMBERED, heart.fallback);
        assertEquals(Style.NUMBERED, heart.cappedFallback);
        assertTrue(report(), report().contains(HeartCount.SHOWN + " 1"));
        // Once per heart: Instagram's later redraws don't ask again.
        heart.shown = Style.DOT;
        HeartCount.show(header);
        assertEquals(Style.DOT, heart.shown);
        assertTrue(report(), report().contains(HeartCount.SHOWN + " 1"));
    }

    @Test public void theHeaderDrawGivesTheCount() {
        Settings.HEART_COUNT.save(true);
        GhostHeaderButton.drew(header);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Style.NUMBERED, heart.shown);
    }

    @Test public void pausedLeavesTheDot() {
        Settings.HEART_COUNT.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        HeartCount.show(header);
        assertEquals(Style.DOT, heart.shown);
    }

    @Test public void onlyTheHeartIsFound() {
        assertEquals(1, HeartCount.hearts(header).size());
        assertEquals(heart, HeartCount.hearts(header).get(0));
        assertTrue(HeartCount.hearts(inbox).isEmpty());
    }

    @Test public void aBadgeWithoutANumberStyleIsLeftAndReported() {
        Settings.HEART_COUNT.save(true);
        OlderBadges.ToastingBadge older = new OlderBadges.ToastingBadge(header.getContext());
        header.removeAllViews();
        header.addView(older);
        HeartCount.show(header);
        assertEquals(DotsOnly.DOT, older.shown);
        assertTrue(report(), report().contains(HeartCount.NO_NUMBER_STYLE));
    }

    @Test public void nothingHappensWithoutAHeader() {
        Settings.HEART_COUNT.save(true);
        HeartCount.show(null);
        assertFalse(report(), report().contains(HeartCount.SHOWN));
    }
}
