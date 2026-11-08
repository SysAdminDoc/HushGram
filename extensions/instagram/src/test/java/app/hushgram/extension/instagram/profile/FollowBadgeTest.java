/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.profile;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The Following list badge (#572) as drawn: a white check on green or cross on red, in a ring, nothing past it. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = {28, 37}, qualifiers = "xxhdpi")
public class FollowBadgeTest {
    @Test
    public void aCheckOnGreenOrACrossOnRedInsideARing() {
        Context context = RuntimeEnvironment.getApplication();
        float density = context.getResources().getDisplayMetrics().density;
        for (boolean followsYou : new boolean[] {true, false}) {
            FollowBadge badge = new FollowBadge(context, followsYou);
            int size = badge.getIntrinsicWidth();
            assertEquals(Math.round(FollowBadge.SIZE_DP * density), size);
            assertEquals(size, badge.getIntrinsicHeight());
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            badge.setBounds(0, 0, size, size);
            badge.draw(new Canvas(bitmap));

            int middle = size / 2;
            float ring = FollowBadge.RING_DP * density;
            float inner = size / 2f - ring;
            String which = followsYou ? "check" : "cross";
            assertEquals(which + " disc", followsYou ? FollowBadge.GREEN : FollowBadge.RED,
                    bitmap.getPixel(middle, Math.round(middle - inner * 0.8f)));
            assertEquals(which + " ring", Color.WHITE, bitmap.getPixel(middle, Math.round(ring / 2)));
            assertEquals(which + " corner", 0, Color.alpha(bitmap.getPixel(0, 0)));
            // The check's bend, or where the cross's arms meet.
            int glyphX = followsYou ? Math.round(middle - inner * 0.12f) : middle;
            int glyphY = followsYou ? Math.round(middle + inner * 0.34f) : middle;
            assertEquals(which + " glyph", Color.WHITE, bitmap.getPixel(glyphX, glyphY));
        }
    }

    /** In dark mode the ring takes the page's black, so the badge doesn't sit in a white halo. */
    @Test
    @Config(qualifiers = "night-xxhdpi")
    public void darkModeGivesABlackRing() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(Color.BLACK, new FollowBadge(context, true).ringColor);
        assertEquals(Color.WHITE, new FollowBadge(context.createConfigurationContext(dayOf(context)), false).ringColor);
    }

    private static android.content.res.Configuration dayOf(Context context) {
        android.content.res.Configuration day = new android.content.res.Configuration(context.getResources().getConfiguration());
        day.uiMode = (day.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_NO;
        return day;
    }
}
