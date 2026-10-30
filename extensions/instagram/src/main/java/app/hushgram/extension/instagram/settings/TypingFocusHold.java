/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

/**
 * Keeps the focus on a field inside a list while the list lays its rows out again. Legacy ListView
 * takes the focus itself for that layout and hands it back after, and each hop restarted the
 * keyboard's input: keys typed meanwhile were lost or typed twice ("Fo" or "FFont" for "Font",
 * 453 emulator, 2026-10-29). Held here, nothing in the list can take the focus during the layout.
 * The list's own layouts outside a layout pass aren't held: entering touch mode (a tap after key
 * navigation) or a fling over rows that changed still move the focus once.
 */
final class TypingFocusHold extends FrameLayout {
    private TypingFocusHold(Context context) {
        super(context);
    }

    /** Puts [list] inside a hold, in its place and with its layout. Call before it's attached. */
    static void around(ListView list) {
        if (!(list.getParent() instanceof ViewGroup) || list.getParent() instanceof TypingFocusHold) return;
        ViewGroup parent = (ViewGroup) list.getParent();
        int at = parent.indexOfChild(list);
        ViewGroup.LayoutParams params = list.getLayoutParams();
        parent.removeViewAt(at);
        TypingFocusHold hold = new TypingFocusHold(list.getContext());
        hold.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        parent.addView(hold, at, params);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View typing = findFocus();
        if (typing == null || !typing.onCheckIsTextEditor()) {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }
        int start = typing instanceof TextView ? ((TextView) typing).getSelectionStart() : -1;
        int end = typing instanceof TextView ? ((TextView) typing).getSelectionEnd() : -1;
        int focusability = getDescendantFocusability();
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        try {
            super.onLayout(changed, left, top, right, bottom);
        } finally {
            setDescendantFocusability(focusability);
        }
        if ((typing.isFocused() && holds(typing)) || !isAttachedToWindow()) return;
        // The list built the field's row again, or cleared its focus when it couldn't move it to
        // a selected row. The field takes the focus back with its caret, or the list takes it, so
        // nothing on the screen points at a removed view.
        Object tag = typing.getTag();
        View field = tag == null ? null : findViewWithTag(tag);
        if (field == null || !field.requestFocus()) {
            requestFocus();
        } else if (field instanceof EditText) {
            EditText edit = (EditText) field;
            int length = edit.length();
            edit.setSelection(start < 0 ? length : Math.min(start, length), end < 0 ? length : Math.min(end, length));
        }
    }

    private boolean holds(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == this) return true;
        }
        return false;
    }
}
