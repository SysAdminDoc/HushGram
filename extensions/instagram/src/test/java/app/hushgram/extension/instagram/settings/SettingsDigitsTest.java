/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.*;
import android.content.Context;
import java.text.NumberFormat;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.instagram.stories.StoryRingSize;

/**
 * Numbers on settings rows are written in Instagram's language, the rule every L10n row formats
 * by, and never in the phone's. The two differ when Instagram has a language of its own picked
 * for it, and the slider labels, the reel thresholds and the bitrate choices used to follow the
 * phone, so an Arabic phone showed Arabic digits in those rows only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@SuppressWarnings("deprecation")
public class SettingsDigitsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Locale phone;

    @Before public void remember() {
        phone = Locale.getDefault();
    }

    @After public void restore() {
        RuntimeEnvironment.setQualifiers("en-rUS");
        Locale.setDefault(phone);
    }

    @Test public void instagramInEnglishOnAnArabicPhoneWritesWesternDigits() {
        RuntimeEnvironment.setQualifiers("en-rUS");
        Locale.setDefault(Locale.forLanguageTag("ar-EG"));
        assertNotEquals("the phone writes its own digits", "20", String.format("%d", 20));
        assertEquals("20 reels (standard)", reelCount().label(20));
        assertEquals("35 reels", reelCount().label(35));
        assertEquals("Fewer than 12,000 views", HushgramPreferenceFragment.minCountSummary(Settings.MIN_REEL_VIEWS, "12000"));
        assertEquals("Fewer than 3,500 likes", HushgramPreferenceFragment.minCountSummary(Settings.MIN_REEL_LIKES, "3500"));
        assertEquals("12 Mbps", bitrate(1));
        assertEquals("50%", HushgramPreferenceFragment.storyRingLabel(StoryRingSize.PERCENT_50));
        assertEquals("The rings are " + L10n.isolate("80%") + " of the size Instagram picks for your screen.",
                HushgramPreferenceFragment.storyRingSummary(StoryRingSize.PERCENT_80));
    }

    /** Instagram in Arabic, which has no HushGram table: English words, Instagram's digits, on every row alike. */
    @Test public void instagramInArabicWritesItsDigitsOnEveryRow() {
        RuntimeEnvironment.setQualifiers("ar-rEG");
        Locale.setDefault(Locale.US);
        Locale instagram = L10n.locale();
        assertEquals("ar", instagram.getLanguage());
        NumberFormat digits = NumberFormat.getIntegerInstance(instagram);
        assertNotEquals("Arabic writes its own digits", "20", digits.format(20));
        assertEquals(digits.format(20) + " reels (standard)", reelCount().label(20));
        assertEquals("Fewer than " + digits.format(12_000) + " views",
                HushgramPreferenceFragment.minCountSummary(Settings.MIN_REEL_VIEWS, "12000"));
        assertEquals(digits.format(12) + " Mbps", bitrate(1));
        String half = NumberFormat.getPercentInstance(instagram).format(0.5);
        assertNotEquals("50%", half);
        assertEquals(half, HushgramPreferenceFragment.storyRingLabel(StoryRingSize.PERCENT_50));
    }

    private static HushgramPreferenceFragment.SliderRow reelCount() {
        return new HushgramPreferenceFragment.SliderRow(context(), Settings.REEL_CAP_COUNT, 5, L10n.t("%1$d reels"));
    }

    private static String bitrate(int index) {
        return String.valueOf(HushgramPreferenceFragment.uploadBitrateRow(context()).getEntries()[index]);
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }
}
