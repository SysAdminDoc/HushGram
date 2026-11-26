/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;

import app.hushgram.extension.instagram.misc.AppIcon;
import app.hushgram.extension.instagram.misc.AppIconChoice;
import app.hushgram.extension.instagram.misc.HeaderPicture;
import app.hushgram.extension.instagram.misc.HeaderText;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;

/**
 * The top of the page with Black settings page on (#455): HushGram's icon, its name in Instagram's
 * gradient and the version line, centred over the rows. Nothing on it can be tapped.
 *
 * <p>The icon and the name can be your own (#872): Instagram's icon or a picture you pick in place
 * of HushGram's, and a name you type in place of HushGram, which the bar over the page shows too.
 * The version line stays. A picture that's gone or won't decode shows HushGram's icon, and a blank
 * name shows HushGram.
 */
final class SettingsHeader extends Preference {
    static final String KEY = "hushgram_settings_header";
    /** Tags of its parts, for the tests. */
    static final String ICON = "hushgram-header-icon";
    static final String NAME = "hushgram-header-name";
    static final String VERSION = "hushgram-header-version";

    /** What the header shows when no name of your own is saved. */
    static final String DEFAULT_NAME = "HushGram";

    /** The most characters a name of your own takes. More wraps the 32 sp name past two lines. */
    static final int MAX_NAME = 24;

    /** The picture's copy, in Instagram's files folder, and the most pixels it keeps each way. */
    static final String PICTURE_FILE = "hushgram-settings-logo.png";
    static final int PICTURE_SIZE = 256;

    /** What sits over the name. */
    public enum Logo {
        /** HushGram's own icon, as it has always been. */
        HUSHGRAM,
        /** Instagram's own launcher icon. */
        INSTAGRAM,
        /** A picture picked in settings, kept as {@link #PICTURE_FILE}. */
        PICTURE
    }

    /** The picture last read, with the file's time and size when it was read. */
    private static final class Read {
        final long modified;
        final long length;
        final Bitmap picture;

        Read(long modified, long length, Bitmap picture) {
            this.modified = modified;
            this.length = length;
            this.picture = picture;
        }
    }

    @Nullable
    private static volatile Read last;

    SettingsHeader(Context context) {
        super(context);
        setKey(KEY);
        // What it shows, for anything that reads rows by their titles.
        setTitle("HushGram");
        setPersistent(false);
        setSelectable(false);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        float density = context.getResources().getDisplayMetrics().density;
        ScreenColors colors = ScreenColors.current();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(Math.round(24 * density), Math.round(28 * density), Math.round(24 * density),
                Math.round(20 * density));

        ImageView icon = new ImageView(context);
        icon.setTag(ICON);
        icon.setImageDrawable(logo(context, colors));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // The name under it says the same.
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(64 * density);
        column.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView name = new TextView(context);
        name.setTag(NAME);
        name.setText(name());
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setGravity(Gravity.CENTER);
        name.setAccessibilityHeading(true);
        ShimmerTitle.wear(name);
        LinearLayout.LayoutParams nameSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameSpace.topMargin = Math.round(12 * density);
        column.addView(name, nameSpace);

        TextView version = new TextView(context);
        version.setTag(VERSION);
        version.setText(L10n.f("Version %1$s for Instagram %2$s",
                L10n.isolate(Utils.getPatchesReleaseVersion()), L10n.isolate(Utils.getAppVersionName())));
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        version.setTextColor(colors.summary);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionSpace.topMargin = Math.round(4 * density);
        column.addView(version, versionSpace);
        return column;
    }

    /**
     * Sets the logo and the name again each time the list draws this row. After a change the list
     * draws it into the view it drew before, without onCreateView, so a name or logo set only there
     * stayed as it was until the page opened again.
     */
    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        Context context = getContext();
        View icon = view.findViewWithTag(ICON);
        if (icon instanceof ImageView) ((ImageView) icon).setImageDrawable(logo(context, ScreenColors.current()));
        View name = view.findViewWithTag(NAME);
        if (name instanceof TextView) ((TextView) name).setText(name());
    }

    /** Draws the header again with the name and logo saved now. */
    void refresh() {
        notifyChanged();
    }

    /**
     * HushGram's own launcher icon when this build has the app icon patch, or the patched mark the
     * status card wears.
     */
    static Drawable icon(Context context, ScreenColors colors) {
        Drawable own = AppIcon.preview(context, AppIconChoice.HUSHGRAM);
        return own != null ? own : SettingsIcons.icon(context, SettingsIcons.PATCHED, colors.heading);
    }

    /** The name the header and the bar show: the one saved, cleaned, or HushGram when that's blank. */
    static String name() {
        String own = cleanName(Settings.SETTINGS_HEADER_NAME.savedValue());
        return own.isEmpty() ? DEFAULT_NAME : own;
    }

    /**
     * [typed] as the header shows it: one line, spaces run together and trimmed, invisible
     * characters dropped, at most {@link #MAX_NAME} characters. Blank stays blank.
     */
    static String cleanName(@Nullable String typed) {
        String line = HeaderText.clean(typed);
        if (line.codePointCount(0, line.length()) <= MAX_NAME) return line;
        return line.substring(0, line.offsetByCodePoints(0, MAX_NAME)).trim();
    }

    /** The logo the header draws, after the fallbacks: a picture that won't load shows HushGram's. */
    static Logo shownLogo(Context context) {
        Logo chosen = Settings.SETTINGS_HEADER_LOGO.savedValue();
        if (chosen == Logo.INSTAGRAM && instagramIcon(context) == null) return Logo.HUSHGRAM;
        if (chosen == Logo.PICTURE && picture(context) == null) return Logo.HUSHGRAM;
        return chosen;
    }

    /** The drawable for {@link #shownLogo}. */
    static Drawable logo(Context context, ScreenColors colors) {
        switch (shownLogo(context)) {
            case INSTAGRAM: {
                Drawable instagram = instagramIcon(context);
                if (instagram != null) return instagram;
                break;
            }
            case PICTURE: {
                Bitmap picture = picture(context);
                if (picture != null) return new BitmapDrawable(context.getResources(), picture);
                break;
            }
            default:
                break;
        }
        return icon(context, colors);
    }

    /** Instagram's own launcher icon, else the app's icon, else null. */
    @Nullable
    static Drawable instagramIcon(Context context) {
        Drawable launcher = AppIcon.preview(context, AppIconChoice.INSTAGRAM);
        if (launcher != null) return launcher;
        try {
            return context.getApplicationInfo().loadIcon(context.getPackageManager());
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** The picked picture's copy. */
    static File pictureFile(Context context) {
        return new File(context.getFilesDir(), PICTURE_FILE);
    }

    /**
     * The picked picture, or null when its copy is missing or won't decode. The bitmap read last is
     * handed back while the file hasn't changed, so a scroll doesn't read it again. Never throws.
     */
    @Nullable
    static Bitmap picture(Context context) {
        try {
            File copy = pictureFile(context);
            if (!copy.isFile()) return null;
            long modified = copy.lastModified();
            long length = copy.length();
            Read held = last;
            if (held != null && held.modified == modified && held.length == length) return held.picture;
            Bitmap picture = BitmapFactory.decodeFile(copy.getPath());
            if (picture == null || picture.getWidth() <= 0 || picture.getHeight() <= 0) {
                Logger.printInfo(() -> "Settings logo: the saved picture won't decode, so HushGram's shows");
                return null;
            }
            last = new Read(modified, length, picture);
            return picture;
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings logo: could not read the saved picture", failure);
            return null;
        }
    }

    /** The picture changed or went, so the next {@link #picture} reads the file again. */
    static void forgetPicture() {
        last = null;
    }

    /**
     * Keeps the picture in [input] as the logo: shrunk to at most {@link #PICTURE_SIZE} each way,
     * written to Instagram's files, and the choice saved as {@link Logo#PICTURE} before it moves in.
     * A file turned down leaves the choice and the copy before it as they were. Off the main thread.
     */
    static void keepPicture(Context context, @Nullable InputStream input) throws HeaderPicture.Refused {
        Logo before = Settings.SETTINGS_HEADER_LOGO.savedValue();
        HeaderPicture.copyWithin(input, pictureFile(context), PICTURE_SIZE, PICTURE_SIZE, new HeaderPicture.Choice() {
            @Override
            public boolean save() {
                return Settings.SETTINGS_HEADER_LOGO.save(Logo.PICTURE);
            }

            @Override
            public void undo() {
                Settings.SETTINGS_HEADER_LOGO.save(before);
            }
        });
        forgetPicture();
    }

    /**
     * Puts HushGram's icon and name back and takes the picture's copy away. Answers whether both
     * choices saved. Off the main thread.
     */
    static boolean reset(Context context) {
        boolean saved = Settings.SETTINGS_HEADER_NAME.save("") & Settings.SETTINGS_HEADER_LOGO.save(Logo.HUSHGRAM);
        File copy = pictureFile(context);
        if (copy.exists() && !copy.delete()) Logger.printInfo(() -> "The settings logo's copy couldn't be removed");
        forgetPicture();
        return saved;
    }
}
