/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.misc.GlassPreview;

/**
 * The glass tab bar's sample on the settings page (#391). It sits above the blur, tint and bend sliders,
 * which feed it their value while dragged ({@link #follow}), so a change shows as the thumb moves.
 */
final class GlassPreviewRow extends Preference {
    @Nullable private GlassPreview preview;

    GlassPreviewRow(Context context) {
        super(context);
        setKey("hushgram_glass_preview");
        setSelectable(false);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        GlassPreview made = new GlassPreview(getContext());
        preview = made;
        return made;
    }

    @Override
    protected void onBindView(View view) {
        if (!(view instanceof GlassPreview)) return;
        ScreenColors colors = ScreenColors.shown == null ? ScreenColors.DEFAULT : ScreenColors.shown;
        ((GlassPreview) view).setBase(colors.background);
    }

    /** The sample, once the list has made it. */
    @Nullable
    GlassPreview view() {
        return preview;
    }

    /** Makes [slider]'s row feed this sample the value it holds while it's dragged, and -1 once let go. */
    void follow(HushgramPreferenceFragment.SliderRow slider, int which) {
        slider.live = value -> {
            GlassPreview shown = preview;
            if (shown != null) shown.live(which, value);
        };
    }
}
