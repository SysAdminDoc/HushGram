/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** The like button's line heart, left out of a liked heart in the accent color (#519). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LikedHeartTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private final Canvas canvas = new Canvas(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888));
    private final Counting outline = new Counting();

    @Before
    public void prepare() {
        Settings.ACCENT_COLOR.resetToDefault();
        Settings.ACCENT_HUE.resetToDefault();
        MaterialYou.reset();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        prepare();
    }

    @Test
    public void accentColorOffDrawsTheLineHeartAsInstagramDoes() {
        LikedHeart.outline(outline, canvas, 1f);
        LikedHeart.outline(outline, canvas, 0f);

        assertEquals(2, outline.draws);
    }

    @Test
    public void aLikedHeartInTheAccentColorHasNoLineHeartUnderIt() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.TEAL);

        LikedHeart.outline(outline, canvas, 1f);
        assertEquals("liked", 0, outline.draws);
        assertEquals(List.of(FamilyNames.ACCENT_COLOR + ": invoked 0, 0 found, 0 missing. Counted: "
                + LikedHeart.COUNT_DROPPED + " 1"), HookStatus.report());

        LikedHeart.outline(outline, canvas, 0f);
        LikedHeart.outline(outline, canvas, 0.5f);
        assertEquals("not liked, or still filling in", 2, outline.draws);
    }

    @Test
    public void instagramBlueOrAPauseKeepsTheLineHeart() {
        Settings.ACCENT_COLOR.save(true);
        Settings.ACCENT_HUE.save(AccentHue.BLUE);
        LikedHeart.outline(outline, canvas, 1f);
        assertEquals("Instagram's blue picked", 1, outline.draws);

        Settings.ACCENT_HUE.save(AccentHue.TEAL);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        LikedHeart.outline(outline, canvas, 1f);
        assertEquals("paused", 2, outline.draws);
    }

    @Test
    public void noLineHeartIsNothingToDraw() {
        LikedHeart.outline(null, canvas, 1f);
        assertTrue(HookStatus.report().isEmpty());
    }

    /** A drawable that counts its draws. */
    private static final class Counting extends Drawable {
        int draws;

        @Override
        public void draw(Canvas canvas) {
            draws++;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
