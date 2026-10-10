/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** The comments sheet hook: dark mode paints the screen's root, light mode and bad input leave things alone. */
@RunWith(RobolectricTestRunner.class)
public class CommentsSheetTest {
    private static final int GRAY = Color.rgb(25, 28, 31);

    private static LinearLayout root(boolean night) {
        Application app = RuntimeEnvironment.getApplication();
        Configuration configuration = new Configuration(app.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        LinearLayout root = new LinearLayout(app.createConfigurationContext(configuration));
        root.setBackground(new ColorDrawable(GRAY));
        return root;
    }

    @Test
    public void darkModePaintsTheScreensRootBlackKeepingItsAlpha() {
        LinearLayout root = root(true);
        CommentsSheet.contentShown(new Object(), root);
        assertNotNull(root.getBackground().getColorFilter());
        assertTrue(root.getBackground() instanceof ColorDrawable);
    }

    @Test
    public void lightModeLeavesTheBackgroundAlone() {
        LinearLayout root = root(false);
        CommentsSheet.contentShown(new Object(), root);
        assertNull(root.getBackground().getColorFilter());
    }

    @Test
    public void badInputNeverThrows() {
        CommentsSheet.contentShown(null, null);
        CommentsSheet.contentShown(new Object(), "not a view");
        CommentsSheet.repaint(null);
        CommentsSheet.paint(null, null);
    }

    @Test
    public void theSheetIsTheNearestAncestorWithTheGivenId() {
        Application app = RuntimeEnvironment.getApplication();
        FrameLayout outer = new FrameLayout(app);
        outer.setId(7);
        FrameLayout sheet = new FrameLayout(app);
        sheet.setId(7);
        FrameLayout slot = new FrameLayout(app);
        slot.setId(8);
        LinearLayout root = new LinearLayout(app);
        outer.addView(sheet);
        sheet.addView(slot);
        slot.addView(root);
        assertSame(sheet, CommentsSheet.findSheet(root, 7));
        assertNull(CommentsSheet.findSheet(root, 9));
        assertFalse(CommentsSheet.findSheet(root, 7) == outer);
    }
}
