/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** A greyed-out choice (Material You before Android 12) can't be tapped and its row is drawn disabled. */
@RunWith(RobolectricTestRunner.class)
public class DisabledChoicesTest {
    @Test public void aDisabledRowIsNotEnabledAndIsDrawnDisabled() {
        Context context = RuntimeEnvironment.getApplication();
        ArrayAdapter<String> source = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1,
                new String[]{"Teal", "Material You"});
        DisabledChoices choices = new DisabledChoices(source, new boolean[]{false, true});
        assertEquals(2, choices.getCount());
        assertFalse(choices.areAllItemsEnabled());
        assertTrue(choices.isEnabled(0));
        assertFalse(choices.isEnabled(1));
        assertTrue(choices.getView(0, null, null).isEnabled());
        TextView off = (TextView) choices.getView(1, null, null);
        assertFalse(off.isEnabled());
        assertEquals("Material You", off.getText().toString());
    }
}
