/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import kotlin.jvm.functions.Function0;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * A comment GIF's or sticker's name in its menu (#502): the title Instagram already holds for it,
 * shown as the row's own label, and copied when the row is tapped. Reads nothing but the comment the
 * menu was opened on, and has its own switch.
 */
public final class CommentGifName {
    private CommentGifName() {}

    interface NativeRows {
        /** The name of the GIF or sticker [comment] carries of its own, or null. */
        String name(Object comment);
        Object row(Object callback);
        Object callback(Object row);
    }

    private static final NativeRows NATIVE = new NativeRows() {
        public String name(Object comment) {
            Object gif = CommentPhoto.gifOrSticker(comment);
            Object title = gif == null ? null : CommentPhotoNative.gifTitle(gif);
            return title instanceof String ? (String) title : null;
        }
        public Object row(Object callback) { return CommentAuthorNative.newRow(callback); }
        public Object callback(Object row) { return CommentAuthorNative.callback(row); }
    };

    /** Whether the switch is on and HushGram isn't paused. Never throws. */
    private static boolean on() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SHOW_COMMENT_GIF_NAMES.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "gif name switch", failure);
            return false;
        }
    }

    /** Adds the name's row to a selected comment's menu rows, or takes it off when it no longer applies. */
    public static List<?> rows(List<?> rows, Object comment, Context context) {
        return rows(rows, comment, context, NATIVE);
    }

    static List<?> rows(List<?> rows, Object comment, Context context, NativeRows nativeRows) {
        try {
            if (rows == null || context == null || comment == null) return rows;
            boolean wanted = on();
            boolean has = false;
            for (Object row : rows) {
                if (nativeRows.callback(row) instanceof NameAction) has = true;
            }
            if (!wanted && !has) return rows;
            String name = wanted ? nativeRows.name(comment) : null;
            if (name != null) name = name.trim();
            List<Object> result = new ArrayList<>(rows.size() + 1);
            for (Object row : rows) {
                if (!(nativeRows.callback(row) instanceof NameAction)) result.add(row);
            }
            if (name != null && !name.isEmpty()) {
                Object row = nativeRows.row(new NameAction(name, context));
                if (row != null) result.add(row);
            }
            return result;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_PHOTO, "comment gif name", failure);
            return rows;
        }
    }

    /** The name [callback]'s row shows, or null when it isn't a name's row, for the menu's label hook. */
    static String nameOf(Object callback) {
        return callback instanceof NameAction ? ((NameAction) callback).name : null;
    }

    /** Holds the name and the short-lived menu's context. Instagram ignores the result, then dismisses its menu. */
    public static final class NameAction implements Function0<Object> {
        final String name;
        final Context context;

        NameAction(String name, Context context) {
            this.name = name;
            this.context = context;
        }

        @Override public Object invoke() {
            try {
                if (!on()) return null;
                Utils.setClipboard(context, L10n.t(context, "GIF name"), name);
                Utils.showToastShort(L10n.t(context, "GIF name copied"));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_PHOTO, "copy gif name", failure);
                try {
                    Utils.showToastShort(L10n.t(context, "Couldn't copy the GIF name"));
                } catch (Throwable feedback) {
                    HookStatus.threw(FamilyNames.COMMENT_PHOTO, "copy gif name feedback", feedback);
                }
            }
            return null;
        }
    }
}
