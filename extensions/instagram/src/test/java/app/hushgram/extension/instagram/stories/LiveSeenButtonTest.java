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

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** #412: the Mark as seen button in a live's header: where it goes, what a tap does, and when it's hidden. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class LiveSeenButtonTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    private static final String LIVE = "17841400000000001";

    private final AtomicBoolean on = new AtomicBoolean(true);
    private final BooleanSupplier switches = on::get;
    private Context context;
    private int menuId;

    /**
     * Instagram 453's live header, as layouts.bin builds it: the row at the top end holds the viewer
     * count, the three-dot menu and the close button, in that order.
     */
    private final class Header {
        final FrameLayout root = new FrameLayout(context);
        final LinearLayout row = new LinearLayout(context);
        final View count = new View(context);
        final ImageView menu = new ImageView(context);
        final ImageView close = new ImageView(context);

        Header(boolean menuInRow) {
            menu.setId(menuId);
            row.addView(count);
            if (menuInRow) {
                row.addView(menu);
                root.addView(row);
            } else {
                root.addView(row);
                root.addView(menu);
            }
            row.addView(close);
        }

        Header() {
            this(true);
        }

        ImageView button() {
            ImageView found = null;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child instanceof ImageView && ((ImageView) child).getDrawable() instanceof StorySeenButton.Eye) {
                    assertNull("one button per header", found);
                    found = (ImageView) child;
                }
            }
            return found;
        }
    }

    @Before
    public void prepare() {
        context = RuntimeEnvironment.getApplication();
        LiveSeenButton.resetForTests();
        LiveSeen.resetForTests();
        menuId = View.generateViewId();
        LiveSeenButton.putIdForTests(context, LiveSeenButton.MENU, menuId);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
        Settings.VIEW_LIVE_ANONYMOUSLY.save(true);
    }

    @After
    public void restore() {
        LiveSeenButton.resetForTests();
        LiveSeen.resetForTests();
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        Settings.MARK_LIVE_SEEN.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static StoryMarks.State drawn(ImageView button) {
        return ((StorySeenButton.Eye) button.getDrawable()).state();
    }

    @Test
    public void theButtonGoesBeforeTheMenuOnce() {
        Header header = new Header();
        LiveSeenButton.bind(header.root, switches);
        ImageView button = header.button();
        assertNotNull(button);
        assertEquals(1, header.row.indexOfChild(button));
        assertEquals(2, header.row.indexOfChild(header.menu));
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals(StoryMarks.State.UNMARKED, drawn(button));
        assertEquals(StorySeenButton.describe(StoryMarks.State.UNMARKED), button.getContentDescription());
        float density = context.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(48 * density), button.getLayoutParams().width);

        LiveSeenButton.bind(header.root, switches);
        assertEquals("bound again, still one button", 4, header.row.getChildCount());
        assertTrue(HookStatus.missing(FamilyNames.LIVE_SEEN).toString(), HookStatus.missing(FamilyNames.LIVE_SEEN).isEmpty());
    }

    @Test
    public void aTapMarksTheLiveOnScreen() {
        Header header = new Header();
        LiveSeenButton.bind(header.root, switches);
        assertTrue(LiveSeen.hold(LIVE));
        ImageView button = header.button();
        button.performClick();
        assertEquals(StoryMarks.State.SENT, drawn(button));
        assertFalse("marked once, there's nothing to undo", button.isEnabled());
        assertFalse(LiveSeen.hold(LIVE));

        Header next = new Header();
        LiveSeenButton.bind(next.root, switches);
        assertEquals("the next live opens unmarked", StoryMarks.State.UNMARKED, drawn(next.button()));
        assertTrue(next.button().isEnabled());
    }

    @Test
    public void offOrPausedTheButtonHides() {
        Header header = new Header();
        LiveSeenButton.bind(header.root, switches);
        ImageView button = header.button();

        on.set(false);
        LiveSeenButton.bind(header.root, switches);
        assertEquals(View.GONE, button.getVisibility());

        on.set(true);
        LiveSeenButton.bind(header.root, switches);
        assertEquals(View.VISIBLE, button.getVisibility());
        on.set(false);
        button.performClick();
        assertEquals("a tap after the switch went off hides it", View.GONE, button.getVisibility());
        assertTrue(LiveSeen.hold(LIVE));

        Header fresh = new Header();
        LiveSeenButton.bind(fresh.root, switches);
        assertNull("never added while off", fresh.button());
    }

    @Test
    public void theRealSwitchesNeedBothOnAndHushGramRunning() {
        assertFalse(LiveSeenButton.switchedOn());
        Settings.MARK_LIVE_SEEN.save(true);
        assertTrue(LiveSeenButton.switchedOn());
        Settings.VIEW_LIVE_ANONYMOUSLY.resetToDefault();
        assertFalse(LiveSeenButton.switchedOn());
        Settings.VIEW_LIVE_ANONYMOUSLY.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertFalse(LiveSeenButton.switchedOn());
    }

    @Test
    public void aMenuOutsideARowIsReportedAndNothingIsAdded() {
        Header header = new Header(false);
        LiveSeenButton.bind(header.root, switches);
        assertNull(header.button());
        String missing = HookStatus.missing(FamilyNames.LIVE_SEEN).toString();
        assertTrue(missing, missing.contains(LiveSeenButton.MENU));

        HookStatus.clear();
        on.set(false);
        LiveSeenButton.bind(new Header(false).root, switches);
        assertTrue("not wanted, not reported", HookStatus.missing(FamilyNames.LIVE_SEEN).isEmpty());
    }

    @Test
    public void aThrowingSwitchIsReportedNotThrown() {
        Header header = new Header();
        LiveSeenButton.bind(header.root, () -> {
            throw new IllegalStateException("settings went away");
        });
        assertNull(header.button());
        String missing = HookStatus.missing(FamilyNames.LIVE_SEEN).toString();
        assertTrue(missing, missing.contains("'" + LiveSeenButton.HOOK + "'"));
        LiveSeenButton.bind(null, switches);
    }
}
