/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import app.hushgram.extension.instagram.misc.AppIconChoice;

/** The App icon list draws each choice's own icon, read from its launcher alias (#321, #327). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IconChoicesTest {
    private static final String INSTAGRAM_ALIAS = "com.instagram.android.activity.MainTabActivity";

    private static ArrayAdapter<String> names(Context context) {
        return new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, new String[]{"Instagram's own", "Dark"});
    }

    private static void addDarkAlias(Context context) {
        ActivityInfo dark = new ActivityInfo();
        dark.packageName = context.getPackageName();
        dark.name = INSTAGRAM_ALIAS + ".hushgram_dark";
        dark.icon = android.R.drawable.ic_menu_add;
        dark.applicationInfo = context.getApplicationInfo();
        Shadows.shadowOf(context.getPackageManager()).addOrUpdateActivity(dark);
    }

    /** Rows as Material's single choice layout makes them: a CheckedTextView with its radio at the start. */
    private static ArrayAdapter<String> radioRows(Context context) {
        return new ArrayAdapter<String>(context, 0, new String[]{"Instagram's own", "Dark"}) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                CheckedTextView row = recycled instanceof CheckedTextView ? (CheckedTextView) recycled : new CheckedTextView(context);
                row.setText(getItem(position));
                if (recycled == null) {
                    Drawable radio = new ColorDrawable(Color.BLUE);
                    radio.setBounds(0, 0, 10, 10);
                    row.setCompoundDrawablesRelative(radio, null, null, null);
                }
                return row;
            }
        };
    }

    private static Bitmap render(Drawable drawable) {
        Rect bounds = drawable.getBounds();
        Bitmap drawn = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
        drawable.draw(new Canvas(drawn));
        return drawn;
    }

    @Test public void aTintNeverReachesTheIcon() {
        Drawable shown = new IconChoices.Preview(new ColorDrawable(Color.RED));
        shown.setBounds(0, 0, 4, 4);
        shown.mutate().setTintList(ColorStateList.valueOf(Color.GRAY));
        shown.setTint(Color.GRAY);
        assertEquals(Color.RED, render(shown).getPixel(2, 2));
    }

    /** The dialog's painter tints every drawable on a row in the radio's colours, after this list has set them (#321). */
    @Test public void underTheDialogsPainterTheIconLeadsInItsOwnColoursAndTheRadioStays() {
        Context context = RuntimeEnvironment.getApplication();
        addDarkAlias(context);
        AppIconChoice[] choices = {AppIconChoice.INSTAGRAM, AppIconChoice.DARK};
        DialogChoices painted = new DialogChoices(new IconChoices(context, radioRows(context), choices), ScreenColors.DEFAULT);
        FrameLayout parent = new FrameLayout(context);
        CheckedTextView second = (CheckedTextView) painted.getView(1, null, parent);
        Drawable[] drawn = second.getCompoundDrawablesRelative();
        assertTrue(drawn[0] instanceof IconChoices.Preview);
        Drawable radio = drawn[2];
        assertNotNull("the radio still shows which icon is on", radio);
        assertFalse(radio instanceof IconChoices.Preview);
        int size = Math.round(40 * context.getResources().getDisplayMetrics().density);
        assertTrue("the icon isn't tinted", render(drawn[0]).sameAs(render(IconChoices.preview(context, AppIconChoice.DARK, size))));

        Drawable[] again = ((CheckedTextView) painted.getView(1, second, parent)).getCompoundDrawablesRelative();
        assertTrue(again[0] instanceof IconChoices.Preview);
        assertSame("a recycled row keeps its radio", radio, again[2]);
    }

    @Test public void aChoiceWithAnAliasShowsItsIconAndOneWithoutShowsNone() {
        Context context = RuntimeEnvironment.getApplication();
        addDarkAlias(context);

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
