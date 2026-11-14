/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
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
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.nio.file.Files;

import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/**
 * Home header text (#454): saved words take the logo's place at its height and in the color
 * Instagram gives the logo, come back when Instagram loads its logo again, and change or go on the
 * next draw with no restart. Blank, Pause, a build without the patch and a view with no logo keep
 * Instagram's header.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeaderTextTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void inTheBuild() {
        HeaderText.inBuildForTests = true;
    }

    @After
    public void restore() {
        HeaderText.inBuildForTests = null;
        PauseForTests.resume();
        Settings.HEADER_TEXT.resetToDefault();
        Settings.HEADER_PICTURE.resetToDefault();
        Settings.HEADER_TEXT_FONT.resetToDefault();
        Settings.HEADER_TEXT_CENTER.resetToDefault();
        Settings.FONT.resetToDefault();
        FontsForTests.reset();
        HeaderPicture.forget();
        File picture = HeaderPicture.file(RuntimeEnvironment.getApplication());
        if (picture.exists()) assertTrue(picture.delete());
    }

    /** Instagram's header logo after its own load: a bitmap 24 pixels high. */
    private static ImageView header(Drawable[] stock) {
        Context context = RuntimeEnvironment.getApplication();
        ImageView logo = new ImageView(context);
        stock[0] = new BitmapDrawable(context.getResources(), Bitmap.createBitmap(120, 24, Bitmap.Config.ARGB_8888));
        logo.setImageDrawable(stock[0]);
        logo.setScaleType(ImageView.ScaleType.MATRIX);
        return logo;
    }

    /** Whether the header's next draw was skipped for one showing a change. */
    private static boolean redraw(ImageView logo) {
        return logo.getViewTreeObserver().dispatchOnPreDraw();
    }

    private static int center(Drawable mark) {
        Bitmap drawn = Bitmap.createBitmap(mark.getIntrinsicWidth(), mark.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        mark.setBounds(0, 0, drawn.getWidth(), drawn.getHeight());
        mark.draw(new Canvas(drawn));
        return drawn.getPixel(drawn.getWidth() / 2, drawn.getHeight() / 2);
    }

    @Test
    public void savedWordsTakeTheLogosPlaceAtItsHeight() {
        assertEquals("the text starts blank", "", Settings.HEADER_TEXT.defaultValue);
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("Matt's feed");

        HeaderText.dress(logo);

        assertNotSame("Instagram's logo was replaced", stock[0], logo.getDrawable());
        assertTrue(logo.getDrawable() instanceof HeaderText.TextMark);
        assertEquals("the words", "Matt's feed", ((HeaderText.TextMark) logo.getDrawable()).text);
        assertEquals("the text keeps the logo's height", 24, logo.getDrawable().getIntrinsicHeight());
        assertTrue("the text is wider than tall", logo.getDrawable().getIntrinsicWidth() > 24);
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());
    }

    @Test
    public void theTextTakesTheColorInstagramGivesItsLogo() {
        HeaderText.TextMark mark = new HeaderText.TextMark("H", 24, 1000, Color.BLUE);
        assertEquals("before the header colors it, the theme's text color", Color.BLUE, center(mark));

        // Instagram colors its logo with an ImageView color filter, white in dark mode.
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("H");
        HeaderText.dress(logo);
        logo.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        assertEquals("the header's color filter", Color.WHITE, center(logo.getDrawable()));

        HeaderText.TextMark tinted = new HeaderText.TextMark("H", 24, 1000, Color.BLUE);
        ImageView other = header(stock);
        other.setImageDrawable(tinted);
        other.setImageTintList(ColorStateList.valueOf(Color.GREEN));
        assertEquals("or its tint", Color.GREEN, center(tinted));
    }

    @Test
    public void longTextShrinksToFitAndALongerOneIsCut() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW");
        HeaderText.dress(logo);
        HeaderText.TextMark mark = (HeaderText.TextMark) logo.getDrawable();
        assertEquals("cut at the most characters", HeaderText.MAX_LENGTH, mark.text.length());
        int widest = Math.round(logo.getResources().getDisplayMetrics().widthPixels * 0.55f);
        assertTrue("narrow enough for the header: " + mark.getIntrinsicWidth(), mark.getIntrinsicWidth() <= widest + 1);
        assertEquals("still the logo's height", 24, mark.getIntrinsicHeight());
    }

    @Test
    public void instagramLoadingItsLogoAgainGetsTheTextBack() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("Home");
        HeaderText.dress(logo);

        // A theme change runs Instagram's own load again.
        logo.setImageDrawable(stock[0]);
        logo.setScaleType(ImageView.ScaleType.MATRIX);
        assertTrue("that draw is skipped for one with the text", redraw(logo));
        assertTrue("the text came back", logo.getDrawable() instanceof HeaderText.TextMark);
        assertEquals("the words", "Home", ((HeaderText.TextMark) logo.getDrawable()).text);
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());
        assertEquals("the same height", 24, logo.getDrawable().getIntrinsicHeight());

        Drawable kept = logo.getDrawable();
        assertFalse("with the text in place, drawing goes on", redraw(logo));
        assertSame("and nothing changes", kept, logo.getDrawable());
    }

    @Test
    public void aChangeShowsOnTheNextDrawAndBlankBringsTheLogoBack() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        HeaderText.dress(logo);
        assertSame("nothing saved", stock[0], logo.getDrawable());
        assertFalse("nothing to change", redraw(logo));

        Settings.HEADER_TEXT.save("Home");
        assertTrue("words saved later show on the next draw", redraw(logo));
        assertEquals("Home", ((HeaderText.TextMark) logo.getDrawable()).text);

        Settings.HEADER_TEXT.save("Feed");
        assertTrue(redraw(logo));
        assertEquals("new words", "Feed", ((HeaderText.TextMark) logo.getDrawable()).text);

        Settings.HEADER_TEXT.save("");
        assertTrue(redraw(logo));
        assertSame("blank puts Instagram's logo back", stock[0], logo.getDrawable());
        assertEquals("with its own scale", ImageView.ScaleType.MATRIX, logo.getScaleType());
        assertFalse(redraw(logo));

        // A second dress, as the header drawing from a new state, adds no second keeper.
        HeaderText.dress(logo);
        Settings.HEADER_TEXT.save("Home");
        assertTrue(redraw(logo));
        assertEquals("Home", ((HeaderText.TextMark) logo.getDrawable()).text);
    }

    @Test
    public void blankPausedOrAnotherViewKeepInstagramsLogo() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("   \n ");
        HeaderText.dress(logo);
        assertSame("only spaces", stock[0], logo.getDrawable());

        Settings.HEADER_TEXT.save("Home");
        redraw(logo);
        assertTrue(logo.getDrawable() instanceof HeaderText.TextMark);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        assertTrue(redraw(logo));
        assertSame("paused", stock[0], logo.getDrawable());
        PauseForTests.resume();

        assertNull("a view with no logo in it", HeaderText.logoIn(new FrameLayout(RuntimeEnvironment.getApplication())));
        HeaderText.dress(new View(RuntimeEnvironment.getApplication()));
        HeaderText.dress(null);
    }

    @Test
    public void aBuildWithoutThePatchKeepsInstagramsLogo() {
        HeaderText.inBuildForTests = false;
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("Home");
        HeaderText.dress(logo);
        assertSame(stock[0], logo.getDrawable());
        assertFalse("and nothing watches it", redraw(logo));
    }

    @Test
    public void theTextIsOneTrimmedLineOfAtMostThirtyCharacters() {
        assertEquals("", HeaderText.clean(null));
        assertEquals("", HeaderText.clean(" \t\n "));
        assertEquals("Matt's feed", HeaderText.clean("  Matt's \n\t feed  "));
        assertEquals("no invisible characters", "ab", HeaderText.clean("a" + (char) 0x200B + (char) 0x202E + "b" + (char) 7));
        String emoji = new String(Character.toChars(0x1F600));
        assertEquals("an emoji is one character", emoji + " hi", HeaderText.clean(emoji + "  hi"));
        StringBuilder long31 = new StringBuilder();
        for (int i = 0; i < 31; i++) long31.append(emoji);
        String cut = HeaderText.clean(long31.toString());
        assertEquals(HeaderText.MAX_LENGTH, cut.codePointCount(0, cut.length()));
        assertEquals("the cut never ends on a space", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                HeaderText.clean("aaaaaaaaaaaaaaaaaaaaaaaaaaaaa bbbb"));
    }

    /** A picture picked in the settings, its copy in Instagram's files: [width] by [height], all [color]. */
    private static void pictureSaved(int width, int height, int color) throws Exception {
        Files.write(HeaderPicture.file(RuntimeEnvironment.getApplication()).toPath(),
                HeaderPictureTest.picture(width, height, color, Bitmap.CompressFormat.PNG));
        HeaderPicture.forget();
        Settings.HEADER_PICTURE.save("logo.png");
    }

    @Test
    public void aSavedPictureTakesTheLogosPlaceInItsOwnColors() throws Exception {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        pictureSaved(200, 100, Color.RED);
        Settings.HEADER_TEXT.save("Home");

        HeaderText.dress(logo);

        assertTrue("the picture wins over the words", logo.getDrawable() instanceof HeaderText.PictureMark);
        Drawable mark = logo.getDrawable();
        assertEquals("the logo's height", 24, mark.getIntrinsicHeight());
        assertEquals("its shape kept", 48, mark.getIntrinsicWidth());
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());

        // Dark mode colors the logo through the header's color filter, which would paint the
        // picture one color.
        logo.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        assertEquals("its own color", Color.RED, center(mark));
    }

    @Test
    public void aWidePictureGetsSmallerAndComesBackAfterAReload() throws Exception {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        pictureSaved(2000, 100, Color.BLUE);
        HeaderText.dress(logo);
        Drawable mark = logo.getDrawable();
        int widest = Math.round(logo.getResources().getDisplayMetrics().widthPixels * 0.55f);
        assertTrue("narrow enough for the header: " + mark.getIntrinsicWidth(), mark.getIntrinsicWidth() <= widest);
        assertTrue("and lower to keep its shape", mark.getIntrinsicHeight() < 24);

        logo.setImageDrawable(stock[0]);
        logo.setScaleType(ImageView.ScaleType.MATRIX);
        assertTrue("that draw is skipped", redraw(logo));
        assertTrue("the picture came back", logo.getDrawable() instanceof HeaderText.PictureMark);
        assertEquals("the same size", mark.getIntrinsicWidth(), logo.getDrawable().getIntrinsicWidth());
        assertEquals(ImageView.ScaleType.CENTER_INSIDE, logo.getScaleType());
    }

    @Test
    public void aNewPictureUnderTheSameNameShowsOnTheNextDraw() throws Exception {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        pictureSaved(60, 20, Color.RED);
        HeaderText.dress(logo);
        assertEquals(Color.RED, center(logo.getDrawable()));

        pictureSaved(60, 20, Color.BLUE);
        assertTrue(redraw(logo));
        assertEquals("the new picture", Color.BLUE, center(logo.getDrawable()));
    }

    @Test
    public void pauseOrARemovedPictureKeepsTheWordsOrTheLogo() throws Exception {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        pictureSaved(60, 20, Color.RED);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        HeaderText.dress(logo);
        assertSame("paused", stock[0], logo.getDrawable());
        PauseForTests.resume();
        assertTrue(redraw(logo));
        assertTrue("resumed", logo.getDrawable() instanceof HeaderText.PictureMark);

        // What the settings' Remove row does: the copy goes, and the keepers are told.
        assertTrue(HeaderPicture.file(RuntimeEnvironment.getApplication()).delete());
        HeaderPicture.forget();
        assertTrue(redraw(logo));
        assertSame("no copy and no words", stock[0], logo.getDrawable());

        Settings.HEADER_TEXT.save("Home");
        assertTrue(redraw(logo));
        assertTrue("no copy, so the words", logo.getDrawable() instanceof HeaderText.TextMark);
    }

    /** #688: a font of your own for the words, on the next draw. */
    @Test
    public void thePickedFontShowsOnTheNextDraw() {
        assertEquals("the words start in the bold they always had", HeaderText.Font.BOLD, Settings.HEADER_TEXT_FONT.defaultValue);
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        Settings.HEADER_TEXT.save("Hello world");
        HeaderText.dress(logo);
        HeaderText.TextMark bold = (HeaderText.TextMark) logo.getDrawable();
        assertEquals(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), bold.typeface());

        Settings.HEADER_TEXT_FONT.save(HeaderText.Font.MONOSPACE);
        assertTrue("that draw is skipped for one in the new font", redraw(logo));
        HeaderText.TextMark mono = (HeaderText.TextMark) logo.getDrawable();
        assertNotSame(bold, mono);
        assertEquals("Hello world", mono.text);
        assertEquals(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), mono.typeface());
        assertEquals("still the logo's height", 24, mono.getIntrinsicHeight());
        assertNotEquals("the letters really are another font", bold.getIntrinsicWidth(), mono.getIntrinsicWidth());
        assertFalse("then drawing goes on", redraw(logo));

        assertEquals(Typeface.create(Typeface.SERIF, Typeface.BOLD), HeaderText.typeface(HeaderText.Font.SERIF));
        assertEquals(Typeface.create("cursive", Typeface.BOLD), HeaderText.typeface(HeaderText.Font.SCRIPT));
        assertEquals(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL), HeaderText.typeface(HeaderText.Font.REGULAR));
    }

    @Test
    public void yourFontIsTheOnePickedUnderFontInBold() {
        FontsForTests.reset();
        assertEquals("Instagram's own picked there, so the bold",
                HeaderText.typeface(HeaderText.Font.BOLD), HeaderText.typeface(HeaderText.Font.YOUR_FONT));
        FontsForTests.reset();
        Settings.FONT.save(FontChoice.SYSTEM);
        assertEquals("the phone's font, in bold", Typeface.create(Typeface.DEFAULT, 700, false),
                HeaderText.typeface(HeaderText.Font.YOUR_FONT));
    }

    /** Home's bar as wide as the window, holding a title view at its start with the logo in it, laid out. */
    private static FrameLayout bar(ImageView logo, FrameLayout title, int width) {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout window = new FrameLayout(context);
        LinearLayout row = new LinearLayout(context);
        title.addView(logo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        row.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        window.addView(row, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        layOut(window, width);
        return window;
    }

    private static void layOut(View window, int width) {
        window.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, width, 200);
    }

    private static float middle(ImageView logo) {
        return HeaderText.leftInRoot(logo) + logo.getWidth() / 2f;
    }

    /** #688: the words sit in the middle of the window, and go back where Instagram put them when that's off. */
    @Test
    public void centeringMovesTheMarkToTheMiddleAndBack() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        FrameLayout title = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout window = bar(logo, title, 1080);
        assertFalse("off to start", Settings.HEADER_TEXT_CENTER.defaultValue);
        Settings.HEADER_TEXT.save("Home");
        HeaderText.dress(logo);
        layOut(window, 1080);
        assertFalse(redraw(logo));
        assertEquals("off, it stays where Instagram put its logo", 0f, title.getTranslationX(), 0f);
        assertTrue("at the start of the bar", middle(logo) < 300);

        Settings.HEADER_TEXT_CENTER.save(true);
        assertFalse("a move needs no skipped draw", redraw(logo));
        assertEquals("in the middle of the window", 540f, middle(logo), 1f);
        assertEquals("the title view moved, inside the full width bar", 0f, logo.getTranslationX(), 0f);
        float moved = title.getTranslationX();
        assertTrue(moved > 0);
        assertFalse(redraw(logo));
        assertEquals("a second draw doesn't move it again", moved, title.getTranslationX(), 0f);

        // Instagram moving its title view on its own is kept for when centering goes off.
        title.setTranslationX(moved + 20);
        redraw(logo);
        assertEquals("back in the middle", 540f, middle(logo), 1f);
        Settings.HEADER_TEXT_CENTER.save(false);
        redraw(logo);
        assertEquals("only the move made here is taken back", 20f, title.getTranslationX(), 0.5f);
    }

    @Test
    public void blankWordsOrAButtonBesideTheLogoLeaveItWhereItIs() {
        Drawable[] stock = new Drawable[1];
        ImageView logo = header(stock);
        FrameLayout title = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout window = bar(logo, title, 1080);
        Settings.HEADER_TEXT_CENTER.save(true);
        HeaderText.dress(logo);
        redraw(logo);
        assertEquals("Instagram's own logo isn't moved", 0f, title.getTranslationX(), 0f);

        Settings.HEADER_TEXT.save("Home");
        assertTrue(redraw(logo));
        layOut(window, 1080);
        redraw(logo);
        assertEquals(540f, middle(logo), 1f);
        Settings.HEADER_TEXT.save("");
        assertTrue(redraw(logo));
        layOut(window, 1080);
        redraw(logo);
        assertSame(stock[0], logo.getDrawable());
        assertEquals("the logo back, and back where it was", 0f, title.getTranslationX(), 0f);

        // A holder that also shows a button would move the button too, so nothing moves.
        ImageView other = header(stock);
        FrameLayout shared = new FrameLayout(RuntimeEnvironment.getApplication());
        View button = new View(RuntimeEnvironment.getApplication());
        shared.addView(button, new FrameLayout.LayoutParams(60, 60));
        FrameLayout second = bar(other, shared, 1080);
        Settings.HEADER_TEXT.save("Home");
        HeaderText.dress(other);
        layOut(second, 1080);
        redraw(other);
        assertNull(HeaderText.mover(other));
        assertEquals(0f, shared.getTranslationX(), 0f);
        assertEquals(0f, other.getTranslationX(), 0f);
    }
}
