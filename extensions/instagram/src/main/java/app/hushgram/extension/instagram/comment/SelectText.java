/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.app.AlertDialog;
import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import kotlin.jvm.functions.Function0;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Select text to copy (#492): a caption, a bio or a comment opens in a small window whose text can be
 * selected by touch, so part of it can be copied. Links in it work. It adds a row beside the Copy rows
 * only when its switch is on, and reads nothing but the text the person already has on screen.
 */
public final class SelectText {
    private SelectText() {}

    interface NativeRows {
        String text(Object comment);
        Object row(Object callback);
        Object callback(Object row);
    }

    private static final NativeRows NATIVE = new NativeRows() {
        public String text(Object comment) { return CommentMenuNative.originalText(comment); }
        public Object row(Object callback) { return CommentAuthorNative.newRow(callback); }
        public Object callback(Object row) { return CommentAuthorNative.callback(row); }
    };

    /** Whether the switch is on and HushGram isn't paused. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.SELECT_TEXT.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_COPY, "select text switch", failure);
            return false;
        }
    }

    /** Opens [text] in a window with selectable, linked text and a Copy all button. Never throws. */
    public static void show(Context context, String title, String text) {
        try {
            if (context == null || text == null || text.isEmpty()) return;
            TextView body = new TextView(context);
            body.setText(text);
            body.setTextIsSelectable(true);
            body.setAutoLinkMask(Linkify.ALL);
            body.setLinksClickable(true);
            body.setMovementMethod(LinkMovementMethod.getInstance());
            body.setTextSize(16);
            int pad = Math.round(context.getResources().getDisplayMetrics().density * 20);
            body.setPadding(pad, pad / 2, pad, pad / 2);
            // In HushGram's own dialog colours, black with Pure black dark mode, rather than the
            // phone's grey dialog (#525).
            ScreenColors colors = ScreenColors.current();
            body.setTextColor(colors.title);
            body.setLinkTextColor(colors.heading);
            body.setHighlightColor(ScreenColors.half(colors.accent));
            ScrollView scroll = new ScrollView(context);
            scroll.addView(body);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(scroll)
                    .setPositiveButton(L10n.t(context, "Copy all"), (shown, which) -> {
                        Utils.setClipboard(context, title, text);
                        Utils.showToastShort(L10n.t(context, "Copied"));
                    })
                    .setNegativeButton(L10n.t(context, "Close"), null)
                    .create();
            dialog.show();
            ScreenColors.dialog(dialog);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_COPY, "select text", failure);
            try {
                Utils.showToastShort(L10n.t(context, "Couldn't open the text"));
            } catch (Throwable feedback) {
                HookStatus.threw(FamilyNames.COMMENT_COPY, "select text feedback", feedback);
            }
        }
    }

    /** A tap that opens [text] under [title]; for rows HushGram draws itself. */
    public static View.OnClickListener opener(Context context, String title, String text) {
        return view -> {
            if (on()) show(context, title, text);
        };
    }

    /** Adds Select text to a selected comment's menu rows, or takes it off when it no longer applies. */
    public static List<?> rows(List<?> rows, Object comment, Context context) {
        return rows(rows, comment, context, NATIVE);
    }

    static List<?> rows(List<?> rows, Object comment, Context context, NativeRows nativeRows) {
        try {
            if (rows == null || context == null || comment == null) return rows;
            boolean wanted = on();
            boolean has = false;
            for (Object row : rows) {
                if (nativeRows.callback(row) instanceof SelectAction) has = true;
            }
            if (!wanted && !has) return rows;
            String text = wanted ? nativeRows.text(comment) : null;
            List<Object> result = new ArrayList<>(rows.size() + 1);
            for (Object row : rows) {
                if (!(nativeRows.callback(row) instanceof SelectAction)) result.add(row);
            }
            if (text != null && !text.isEmpty()) {
                Object select = nativeRows.row(new SelectAction(text, context));
                if (select != null) result.add(select);
            }
            return result;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_COPY, "comment select", failure);
            return rows;
        }
    }

    /** Whether [callback] belongs to a Select text row, for the menu's label hook. */
    static boolean isSelect(Object callback) {
        return callback instanceof SelectAction;
    }

    /** Holds the comment's text and the short-lived menu's context. */
    public static final class SelectAction implements Function0<Object> {
        final String text;
        final Context context;

        SelectAction(String text, Context context) {
            this.text = text;
            this.context = context;
        }

        @Override public Object invoke() {
            if (on()) show(context, L10n.t(context, "Comment"), text);
            return null;
        }
    }
}
