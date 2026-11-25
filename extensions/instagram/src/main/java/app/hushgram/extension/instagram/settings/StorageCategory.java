/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.CLEAR_MEDIA_CACHE_NOW;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.category;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.clearMediaCache;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.clearMediaCacheSummary;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mark;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.mediaCacheLimitRow;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.showMediaCacheSize;
import static app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.toggle;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.format.Formatter;

import androidx.annotation.Nullable;

import java.util.Set;

import app.hushgram.extension.instagram.misc.DataUse;
import app.hushgram.extension.instagram.settings.HushgramPreferenceFragment.Row;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;

/** Builds the Storage category of HushGram settings. */
final class StorageCategory {
    private StorageCategory() { }

    static void add(HushgramPreferenceFragment page, PreferenceScreen screen, Context context, Set<PatchFamily> build) {
        if (build.contains(PatchFamily.MEDIA_CACHE)) {
            PreferenceCategory storage = category(screen, L10n.t("Storage"), SettingsIcons.STORAGE,
                    L10n.t("Keep Instagram's saved photos and videos from filling your phone."));
            storage.addPreference(toggle(context, Settings.CLEAR_MEDIA_CACHE, L10n.t("Clear the media cache"),
                    L10n.t("When Instagram's saved photos and videos pass the limit below, deletes the photos as "
                            + "you leave it and the videos the next time it starts. Your sign-in, drafts and "
                            + "settings stay.")));
            storage.addPreference(mediaCacheLimitRow(context));
            Row clearNow = new Row(context);
            clearNow.setKey(CLEAR_MEDIA_CACHE_NOW);
            clearNow.setPersistent(false);
            clearNow.setTitle(L10n.t("Clear saved copies now"));
            clearNow.setSummary(clearMediaCacheSummary());
            clearNow.setOnPreferenceClickListener(p -> {
                clearMediaCache(p);
                return true;
            });
            storage.addPreference(mark(clearNow, SettingsIcons.DELETE));
            showMediaCacheSize(clearNow);
            storage.addPreference(mark(spaceUsedRow(context), SettingsIcons.STORAGE));
        }
    }

    /** The Space used row's key. */
    static final String SPACE_USED = "hushgram_space_used";

    /**
     * Says how much Instagram's data takes up outside its cache, its biggest folders and HushGram's
     * own share (#683), read off the main thread since it walks the folders, so someone whose data
     * keeps growing can see which folder it is.
     */
    static Row spaceUsedRow(Context context) {
        Row row = new Row(context);
        row.setKey(SPACE_USED);
        row.setPersistent(false);
        row.setSelectable(false);
        row.setTitle(L10n.t("Space used"));
        row.setSummary(L10n.t("Adding it up…"));
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> {
            String shown = spaceUsedSummary(application, DataUse.read(application));
            Utils.runOnMainThread(() -> row.setSummary(shown));
        });
        return row;
    }

    /** What the Space used row says about [use], one line each for the total, the biggest folders and HushGram. */
    static String spaceUsedSummary(Context context, @Nullable DataUse use) {
        if (use == null) return L10n.t("Couldn't add up the space used.");
        StringBuilder shown = new StringBuilder(L10n.f("Instagram's data takes up %1$s, its cache aside.", size(context, use.total)));
        if (!use.biggest.isEmpty()) {
            StringBuilder parts = new StringBuilder();
            for (DataUse.Part part : use.biggest) {
                if (parts.length() > 0) parts.append(", ");
                parts.append(L10n.isolate(part.name)).append(' ').append(size(context, part.bytes));
            }
            shown.append('\n').append(L10n.f("Biggest: %1$s.", parts.toString()));
        }
        shown.append('\n').append(L10n.f("HushGram's own files take up %1$s.", size(context, use.hushgram)));
        return shown.toString();
    }

    private static String size(Context context, long bytes) {
        return L10n.isolate(Formatter.formatShortFileSize(context, bytes));
    }
}
