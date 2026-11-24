/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.Utils;

/** How many people the row a long press on Share opens shows (discussion #811). */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w411dp-h891dp")
public class QuickSendTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity before;

    @Before
    public void remember() {
        before = Utils.getActivity();
        Utils.setActivity(Robolectric.buildActivity(Activity.class).setup().get());
    }

    @After
    public void putBack() {
        Settings.MORE_QUICK_SEND_PEOPLE.resetToDefault();
        QuickSend.forget();
        Utils.setActivity(before);
    }

    /**
     * The crash on the S25 (discussion #811): the row asked for more faces than the popup had slots
     * for. A popup where Instagram's slots can't be found gets none added, answers four, and from then
     * on the row asks for four even with the switch on.
     */
    @Test
    public void aPopupWithoutRoomKeepsTheRowAtFour() {
        Settings.MORE_QUICK_SEND_PEOPLE.save(true);
        assertEquals(5, QuickSend.people(4));
        Activity activity = Utils.getActivity();
        PopupWindow popup = new PopupWindow(activity);
        FrameLayout content = new FrameLayout(activity);
        content.addView(new View(activity));
        popup.setContentView(content);
        assertEquals(4, QuickSend.addSlots(popup, 5));
        assertEquals(1, content.getChildCount());
        assertEquals(4, QuickSend.people(4));
        for (int rank = 5; rank <= QuickSend.MOST; rank++) {
            assertEquals(0, QuickSend.avatarId(rank));
            assertEquals(0, QuickSend.labelId(rank));
        }
    }

    /** With the switch off the popup asks for four, and nothing is looked at or added. */
    @Test
    public void fourFacesNeedNoSlots() {
        PopupWindow popup = new PopupWindow(Utils.getActivity());
        assertEquals(4, QuickSend.addSlots(popup, 4));
        assertEquals(3, QuickSend.addSlots(popup, 3));
        assertEquals(0, QuickSend.avatarId(0));
        assertEquals(0, QuickSend.avatarId(QuickSend.MOST + 1));
    }

    /** Each face sits a face and a gap past the one before, from the bar's start plus its padding, and its name over it. */
    @Test
    public void theFacesLineUpAlongTheBar() {
        Activity activity = Utils.getActivity();
        View bar = new View(activity);
        bar.layout(100, 0, 700, 50);
        View[] avatars = new View[5];
        View[] labels = new View[5];
        FrameLayout row = new FrameLayout(activity);
        for (int i = 0; i < 5; i++) {
            avatars[i] = new View(activity);
            labels[i] = new View(activity);
            row.addView(avatars[i]);
            row.addView(labels[i]);
            // Laid out on top of each other, as copies of one slot are.
            avatars[i].layout(130, 10, 170, 50);
            labels[i].layout(120, 0, 160, 8);
        }
        QuickSend.place(bar, avatars, labels, 60f, 12f);
        for (int i = 0; i < 5; i++) {
            float left = 100 + 12 + 60 * i;
            assertEquals(left, avatars[i].getLeft() + avatars[i].getTranslationX(), 0.01f);
            assertEquals(left + 20, labels[i].getLeft() + labels[i].getTranslationX() + 20, 0.01f);
        }

        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        // A detached view settles its direction when measured, as the popup's bar is before it's laid out.
        bar.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(50, View.MeasureSpec.EXACTLY));
        bar.layout(100, 0, 700, 50);
        assertEquals(View.LAYOUT_DIRECTION_RTL, bar.getLayoutDirection());
        QuickSend.place(bar, avatars, labels, 60f, 12f);
        for (int i = 0; i < 5; i++) {
            float right = 700 - 12 - 60 * i;
            assertEquals(right, avatars[i].getRight() + avatars[i].getTranslationX(), 0.01f);
        }
    }

    @Test
    public void theSwitchStartsOffSoTheRowKeepsInstagramsFour() {
        assertFalse(Settings.MORE_QUICK_SEND_PEOPLE.defaultValue);
        assertEquals(4, QuickSend.people(4));
    }

    /**
     * A 411 dp window, the S22's, takes five faces, not six: the popup's box is 345 dp wide whatever
     * the window, and six faces with their gaps and padding need 368, which squashed the row.
     */
    @Test
    public void withTheSwitchOnTheRowFillsThePopup() {
        Settings.MORE_QUICK_SEND_PEOPLE.save(true);
        assertEquals(5, QuickSend.people(4));
        Settings.MORE_QUICK_SEND_PEOPLE.save(false);
        assertEquals(4, QuickSend.people(4));
    }

    /** An app without Instagram's dimen, or no window yet, reads the box as 456.1's 345 dp. */
    @Test
    public void theBoxIsInstagramsWidthWhenItsDimenIsMissing() {
        assertEquals(QuickSend.BOX_DP, QuickSend.boxWidthDp(Utils.getActivity()), 0f);
        assertEquals(QuickSend.BOX_DP, QuickSend.boxWidthDp(null), 0f);
    }

    /** However narrow the window, the row never shows fewer than Instagram does, and however wide its box, no more than ten. */
    @Test
    public void theRowStaysBetweenInstagramsFourAndTen() {
        float box = QuickSend.BOX_DP;
        assertEquals(4, QuickSend.fit(4, 0, box));
        assertEquals(4, QuickSend.fit(4, -20, box));
        assertEquals(4, QuickSend.fit(4, 280, box));
        assertEquals(4, QuickSend.fit(4, 360, 0));
        assertEquals(5, QuickSend.fit(4, 360, box));
        // The S25's 384 dp and the S22's 411 dp: six would be 368 dp wide, more than the 345 dp box.
        assertEquals(5, QuickSend.fit(4, 384, box));
        assertEquals(5, QuickSend.fit(4, 411, box));
        assertEquals(5, QuickSend.fit(4, 1280, box));
        // A box Instagram makes wider takes more, up to what the window leaves it.
        assertEquals(6, QuickSend.fit(4, 411, 400));
        assertEquals(QuickSend.MOST, QuickSend.fit(4, 1280, 1280));
    }

    /** Every count past four fits in the box and the window less its edges, and one more face wouldn't. */
    @Test
    public void whatTheRowTakesFitsInTheBox() {
        for (int box = 200; box <= 800; box += 7) {
            for (int width = 200; width <= 800; width++) {
                int people = QuickSend.fit(4, width, box);
                if (people == 4 || people == QuickSend.MOST) continue;
                float room = Math.min(box, width - 2 * QuickSend.EDGE_DP);
                String at = width + " dp in a " + box + " dp box: ";
                assertTrue(at + people, rowWidth(people) <= room);
                assertTrue(at + (people + 1), rowWidth(people + 1) > room);
            }
        }
    }

    /** The popup's width for [people] faces, as Instagram's clamp works it out. */
    private static float rowWidth(int people) {
        return people * QuickSend.FACE_DP + (people - 1) * QuickSend.GAP_DP + 2 * QuickSend.PADDING_DP;
    }
}
