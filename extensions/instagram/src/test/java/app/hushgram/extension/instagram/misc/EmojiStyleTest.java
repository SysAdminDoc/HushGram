/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

/** When Emoji style has EmojiCompat draw every emoji from Google's font, and when it leaves Instagram's strategy alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class EmojiStyleTest {
    /** EmojiCompat's REPLACE_STRATEGY_DEFAULT and REPLACE_STRATEGY_NON_EXISTENT. */
    private static final int DEFAULT = 0;
    private static final int NON_EXISTENT = 2;

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void prepare() {
        Settings.NOTO_EMOJI.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After
    public void restore() {
        Settings.NOTO_EMOJI.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** Off to start, so a build with the patch asks for what Instagram asks for until the switch goes on. */
    @Test
    public void offToStartInstagramsStrategyGoesThrough() {
        assertEquals(Boolean.FALSE, Settings.NOTO_EMOJI.defaultValue);
        assertTrue("text already drawn keeps its style until a restart", Settings.NOTO_EMOJI.rebootApp);
        assertEquals(DEFAULT, EmojiStyle.replaceStrategy(DEFAULT));
        assertEquals(NON_EXISTENT, EmojiStyle.replaceStrategy(NON_EXISTENT));
        assertEquals(List.of(FamilyNames.EMOJI_STYLE + ": invoked 2, 0 found, 0 missing"), HookStatus.report());
    }

    /** With the switch on, every strategy Instagram asks for becomes replace-all, and each is counted. */
    @Test
    public void withTheSwitchOnEveryEmojiIsReplaced() {
        Settings.NOTO_EMOJI.save(true);
        assertEquals(1, EmojiStyle.REPLACE_ALL);
        assertEquals(EmojiStyle.REPLACE_ALL, EmojiStyle.replaceStrategy(DEFAULT));
        assertEquals(EmojiStyle.REPLACE_ALL, EmojiStyle.replaceStrategy(NON_EXISTENT));
        assertEquals(EmojiStyle.REPLACE_ALL, EmojiStyle.replaceStrategy(EmojiStyle.REPLACE_ALL));
        assertEquals(List.of(FamilyNames.EMOJI_STYLE + ": invoked 3, 0 found, 0 missing. Counted: " + EmojiStyle.GOOGLE + " 3"),
                HookStatus.report());
    }

    @Test
    public void pausedAndUnreadyLeaveInstagramsStrategy() {
        Settings.NOTO_EMOJI.save(true);
        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertEquals(DEFAULT, EmojiStyle.replaceStrategy(DEFAULT));
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertEquals(DEFAULT, EmojiStyle.replaceStrategy(DEFAULT)));

        assertEquals(EmojiStyle.REPLACE_ALL, EmojiStyle.replaceStrategy(DEFAULT));
    }
}
