/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.direct.SeenChoices.Choice;

/** The chats' own seen choices (#13): kept per account and chat, on file, newest 500 only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class SeenChoicesTest {
    private SharedPreferences file;

    @Before
    public void prepare() {
        file = RuntimeEnvironment.getApplication().getSharedPreferences("seen_choices_test", Context.MODE_PRIVATE);
        file.edit().clear().commit();
    }

    @After
    public void restore() {
        file.edit().clear().commit();
    }

    @Test
    public void eachChatOnEachAccountKeepsItsOwnChoiceOnFile() {
        SeenChoices choices = new SeenChoices(file);
        assertTrue(choices.isEmpty());
        assertFalse(choices.anyHeld());
        assertEquals(Choice.SWITCH, choices.of("a1", "t1"));

        choices.choose("a1", "t1", Choice.HOLD, 10);
        choices.choose("a1", "t2", Choice.SEND, 11);
        choices.choose("a1/t", "1", Choice.SEND, 12);
        assertEquals(Choice.HOLD, choices.of("a1", "t1"));
        assertEquals(Choice.SEND, choices.of("a1", "t2"));
        assertEquals(Choice.SWITCH, choices.of("a2", "t1"));
        assertEquals(Choice.SWITCH, choices.of("a1", "t3"));
        assertEquals("an id holding a slash is still its own chat", Choice.SEND, choices.of("a1/t", "1"));
        assertTrue(choices.anyHeld());
        assertEquals(3, choices.size());
        assertEquals("10/HOLD", file.getString(ReadHere.key("a1", "t1"), null));
        assertEquals("11/SEND", file.getString(ReadHere.key("a1", "t2"), null));

        SeenChoices again = new SeenChoices(file);
        assertEquals(Choice.HOLD, again.of("a1", "t1"));
        assertEquals(Choice.SEND, again.of("a1", "t2"));
        again.choose("a1", "t1", Choice.SWITCH, 13);
        assertEquals(Choice.SWITCH, again.of("a1", "t1"));
        assertFalse(again.anyHeld());
        assertFalse("a chat back on the switch isn't kept", file.contains(ReadHere.key("a1", "t1")));
        assertEquals(2, again.size());
    }

    @Test
    public void onlyTheNewestChoicesAreKeptAndChoosingAgainMakesAChatNewest() {
        SeenChoices choices = new SeenChoices(file);
        for (int thread = 0; thread <= SeenChoices.MAX_CHATS; thread++) {
            choices.choose("a1", "t" + thread, Choice.HOLD, 100 + thread);
        }
        assertEquals(SeenChoices.MAX_CHATS, choices.size());
        assertEquals("the chat chosen longest ago goes first", Choice.SWITCH, choices.of("a1", "t0"));
        assertEquals(Choice.HOLD, choices.of("a1", "t1"));
        assertEquals(SeenChoices.MAX_CHATS, file.getAll().size());

        choices.choose("a1", "t1", Choice.SEND, 1_000);
        choices.choose("a1", "new", Choice.HOLD, 1_001);
        assertEquals(Choice.SEND, choices.of("a1", "t1"));
        assertEquals(Choice.SWITCH, choices.of("a1", "t2"));
        assertEquals(SeenChoices.MAX_CHATS, file.getAll().size());

        SeenChoices again = new SeenChoices(file);
        assertEquals(Choice.SEND, again.of("a1", "t1"));
        assertEquals(Choice.SWITCH, again.of("a1", "t2"));
        assertEquals(Choice.HOLD, again.of("a1", "new"));
        assertEquals(SeenChoices.MAX_CHATS, again.size());
    }

    @Test
    public void brokenValuesAndTheSurplusAreCleanedWhenTheFileIsRead() {
        SharedPreferences.Editor edit = file.edit();
        edit.putString(ReadHere.key("a1", "bare"), "SEND");
        edit.putString(ReadHere.key("a1", "switch"), "5/SWITCH");
        edit.putString(ReadHere.key("a1", "words"), "x/HOLD");
        edit.putString(ReadHere.key("a1", "nothing"), "/HOLD");
        edit.putString(ReadHere.key("a1", "lower"), "6/hold");
        edit.putInt(ReadHere.key("a1", "number"), 7);
        for (int thread = 0; thread <= SeenChoices.MAX_CHATS; thread++) {
            edit.putString(ReadHere.key("a1", "t" + thread), (100 + thread) + "/HOLD");
        }
        edit.commit();

        SeenChoices choices = new SeenChoices(file);
        assertEquals(SeenChoices.MAX_CHATS, choices.size());
        assertEquals(Choice.SWITCH, choices.of("a1", "t0"));
        assertEquals(Choice.HOLD, choices.of("a1", "t" + SeenChoices.MAX_CHATS));
        for (String broken : new String[] {"bare", "switch", "words", "nothing", "lower", "number"}) {
            assertEquals(broken, Choice.SWITCH, choices.of("a1", broken));
            assertFalse(broken, file.contains(ReadHere.key("a1", broken)));
        }
        assertFalse(file.contains(ReadHere.key("a1", "t0")));
        assertEquals(SeenChoices.MAX_CHATS, file.getAll().size());
    }

    @Test
    public void withoutAFileTheChoicesLastOnlyInMemory() {
        SeenChoices choices = new SeenChoices(null);
        choices.choose("a1", "t1", Choice.HOLD, 1);
        assertEquals(Choice.HOLD, choices.of("a1", "t1"));
        assertTrue(choices.anyHeld());
        choices.choose("a1", "t1", Choice.SWITCH, 2);
        assertTrue(choices.isEmpty());

        for (int thread = 0; thread <= SeenChoices.MAX_CHATS; thread++) {
            choices.choose("a1", "t" + thread, Choice.SEND, thread);
        }
        assertEquals(SeenChoices.MAX_CHATS, choices.size());
        assertEquals(Choice.SWITCH, choices.of("a1", "t0"));
        assertFalse(choices.anyHeld());
        assertTrue(file.getAll().isEmpty());
    }

    /** Instagram's other processes: a chat held on file stays held there, and nothing there changes the file. */
    @Test
    public void anotherProcessReadsTheFileButNeverWritesIt() {
        SharedPreferences.Editor edit = file.edit();
        edit.putString(ReadHere.key("a1", "held"), "10/HOLD");
        edit.putString(ReadHere.key("a1", "sent"), "11/SEND");
        edit.putString(ReadHere.key("a1", "broken"), "SEND");
        edit.commit();

        SeenChoices other = new SeenChoices(file, false);
        assertEquals(Choice.HOLD, other.of("a1", "held"));
        assertEquals(Choice.SEND, other.of("a1", "sent"));
        assertTrue(other.anyHeld());
        assertEquals("a broken value isn't used", Choice.SWITCH, other.of("a1", "broken"));
        assertTrue("nor removed from another process", file.contains(ReadHere.key("a1", "broken")));

        other.choose("a1", "held", Choice.SWITCH, 12);
        other.choose("a1", "new", Choice.HOLD, 13);
        assertEquals(Choice.SWITCH, other.of("a1", "held"));
        assertEquals(Choice.HOLD, other.of("a1", "new"));
        other.clear();
        assertEquals("10/HOLD", file.getString(ReadHere.key("a1", "held"), null));
        assertFalse(file.contains(ReadHere.key("a1", "new")));
        assertEquals(3, file.getAll().size());
    }

    @Test
    public void clearForgetsTheFileToo() {
        SeenChoices choices = new SeenChoices(file);
        choices.choose("a1", "t1", Choice.HOLD, 1);
        choices.clear();
        assertTrue(choices.isEmpty());
        assertTrue(file.getAll().isEmpty());
        assertTrue(new SeenChoices(file).isEmpty());
    }
}
