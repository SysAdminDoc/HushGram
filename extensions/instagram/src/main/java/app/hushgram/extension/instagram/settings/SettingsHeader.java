/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.hushgram.extension.instagram.misc.AppIcon;
import app.hushgram.extension.instagram.misc.AppIconChoice;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;

/**
 * The top of the page with Black settings page on (#455): HushGram's icon, its name in Instagram's
 * gradient and the version line, centred over the rows. Nothing on it can be tapped.
 */
final class SettingsHeader extends Preference {
    static final String KEY = "hushgram_settings_header";
    /** Tags of its parts, for the tests. */
    static final String ICON = "hushgram-header-icon";
    static final String NAME = "hushgram-header-name";
    static final String VERSION = "hushgram-header-version";

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
        icon.setImageDrawable(icon(context, colors));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // The name under it says the same.
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(64 * density);
        column.addView(icon, new LinearLayout.LayoutParams(size, size));

        TextView name = new TextView(context);
        name.setTag(NAME);
        name.setText("HushGram");
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
     * HushGram's own launcher icon when this build has the app icon patch, or the patched mark the
     * status card wears.
     */
    static Drawable icon(Context context, ScreenColors colors) {
        Drawable own = AppIcon.preview(context, AppIconChoice.HUSHGRAM);
        return own != null ? own : SettingsIcons.icon(context, SettingsIcons.PATCHED, colors.heading);
    }
}
