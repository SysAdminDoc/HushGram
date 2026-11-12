/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.view.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.hushgram.extension.shared.L10n;

/**
 * The five groups the categories fall into (#642). The list of categories puts a group's name over
 * its first one, and a chip for each group above the list shows that group alone. A category keeps
 * the place it was built in among its group's.
 */
final class CategoryGroups {
    static final int PRIVACY = 0;
    static final int CONTENT = 1;
    static final int MEDIA = 2;
    static final int LOOK = 3;
    static final int HUSHGRAM = 4;
    static final int COUNT = 5;
    /** Each group's orders: its name's, then its categories', clear of the next group's. */
    private static final int SPAN = 1000;
    private static final String LABEL_KEY = "hushgram_category_group_";

    private CategoryGroups() { }

    static CharSequence title(int group) {
        switch (group) {
            case PRIVACY: return L10n.t("Privacy");
            case CONTENT: return L10n.t("Content");
            case MEDIA: return L10n.t("Media and storage");
            case LOOK: return L10n.t("Look");
            // Not HushGram, which the page's header already says.
            default: return L10n.t("App");
        }
    }

    /** Puts every category on [screen] that has no group yet in [group]. Called after each category is built. */
    static void claim(PreferenceScreen screen, int group) {
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading && ((HushgramPreferenceFragment.Heading) item).group < 0) {
                ((HushgramPreferenceFragment.Heading) item).group = group;
            }
        }
    }

    /**
     * Gives the categories on [screen] their group's orders and puts them back on it in that order,
     * so whatever walks the screen meets them grouped: the list, the grid's tiles and the chips.
     */
    static void arrange(PreferenceScreen screen) {
        List<HushgramPreferenceFragment.Heading> headings = new ArrayList<>();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference item = screen.getPreference(i);
            if (item instanceof HushgramPreferenceFragment.Heading) headings.add((HushgramPreferenceFragment.Heading) item);
        }
        for (Preference heading : headings) screen.removePreference(heading);
        // A stable sort, so each group's categories stay in the order they were built.
        headings.sort(Comparator.comparingInt(heading -> of(heading)));
        int[] placed = new int[COUNT];
        for (HushgramPreferenceFragment.Heading heading : headings) {
            int group = of(heading);
            heading.setOrder(group * SPAN + 1 + placed[group]++);
            screen.addPreference(heading);
        }
    }

    /** [heading]'s group, HushGram's for one that never got one. */
    static int of(PreferenceCategory heading) {
        int group = heading instanceof HushgramPreferenceFragment.Heading ? ((HushgramPreferenceFragment.Heading) heading).group : -1;
        return group >= 0 && group < COUNT ? group : HUSHGRAM;
    }

    /** A group's name over its first category on the list of them. */
    static Label label(Context context, int group) {
        Label label = new Label(context, group);
        label.setKey(LABEL_KEY + group);
        label.setTitle(title(group));
        label.setOrder(group * SPAN);
        return label;
    }

    /** A group's name: not a button, read as a heading, and not part of any card. */
    static final class Label extends Preference {
        final int group;

        Label(Context context, int group) {
            super(context);
            this.group = group;
            // A section title's look, without being a section.
            setLayoutResource(new PreferenceCategory(context).getLayoutResource());
            setPersistent(false);
            setSelectable(false);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            view.setAccessibilityHeading(true);
            ScreenColors.current().paintGroupLabel(view);
        }
    }
}
