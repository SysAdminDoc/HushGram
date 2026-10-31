/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.Drawable;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import app.hushgram.extension.instagram.misc.AppIconChoice;

/** The App icon list draws each choice's own icon, read from its launcher alias (#321, #327). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class IconChoicesTest {
    private static final String INSTAGRAM_ALIAS = "com.instagram.android.activity.MainTabActivity";

    private static ArrayAdapter<String> names(Context context) {
        return new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, new String[]{"Instagram's own", "Dark"});
    }

    @Test public void aChoiceWithAnAliasShowsItsIconAndOneWithoutShowsNone() {
        Context context = RuntimeEnvironment.getApplication();
        String owner = context.getPackageName();
        ActivityInfo dark = new ActivityInfo();
        dark.packageName = owner;
        dark.name = INSTAGRAM_ALIAS + ".hushgram_dark";
        dark.icon = android.R.drawable.ic_menu_add;
        dark.applicationInfo = context.getApplicationInfo();
        Shadows.shadowOf(context.getPackageManager()).addOrUpdateActivity(dark);

        AppIconChoice[] choices = {AppIconChoice.INSTAGRAM, AppIconChoice.DARK};
        IconChoices list = new IconChoices(context, names(context), choices);
        TextView first = (TextView) list.getView(0, null, null);
        TextView second = (TextView) list.getView(1, null, null);
        assertNull(first.getCompoundDrawablesRelative()[0]);
        Drawable shown = second.getCompoundDrawablesRelative()[0];
        assertNotNull(shown);
        int size = Math.round(40 * context.getResources().getDisplayMetrics().density);
        assertEquals(size, shown.getBounds().width());
        assertEquals(size, shown.getBounds().height());
        assertEquals("Dark", second.getText().toString());
    }
}
