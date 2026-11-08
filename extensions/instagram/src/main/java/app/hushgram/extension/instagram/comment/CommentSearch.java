/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.comment;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Search comments (#560): a search box above a post's comments. Enter or the down arrow scrolls to
 * the next comment holding the words, or the name, typed in, and the up arrow to the one before.
 * {@link CommentFinder} does the looking, loading more comments as it scrolls the way a person
 * scrolling would, and the comment it lands on flashes. Only comments Instagram has put in the list
 * are searched, so replies still folded under View replies aren't.
 *
 * <p>The patch calls {@link #contentShown} as each comments sheet screen finishes making its view.
 * With the switch off, or paused, nothing is added. No hook here throws.
 */
public final class CommentSearch {
    static final String HOOK = "comment search";
    static final String COUNT_BAR = "comment search box added";
    static final String COUNT_NO_LIST = "comment search found no comments list";

    /** Marks the box, so a screen whose view is made again gets one box. */
    static final String TAG = "hushgram_comment_search";
    /** Ids looked up by name, since they change with every build. */
    static final String LIST_CONTAINER = "list_view_container";
    static final String LIST = "sticky_header_list";
    private static final String RECYCLER = "RecyclerView";

    static final long FLASH_MILLIS = 1200;

    private CommentSearch() {
    }

    /** Whether the switch is on and HushGram isn't paused. Never throws. */
    public static boolean on() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.COMMENT_SEARCH.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_SEARCH, HOOK, failure);
            return false;
        }
    }

    /**
     * Injected before each return of the comments sheet screens' onViewCreated. Puts the search box
     * right above the comments list. Never throws.
     *
     * @param screen the comments fragment
     * @param view the screen's root view
     */
    public static void contentShown(Object screen, Object view) {
        try {
            HookStatus.invoked(FamilyNames.COMMENT_SEARCH);
            if (!(view instanceof ViewGroup) || !on()) return;
            add((ViewGroup) view);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_SEARCH, HOOK, failure);
        }
    }

    /** Puts the box above [root]'s comments list; false when there's no list it can go above. */
    static boolean add(ViewGroup root) {
        View container = byName(root, LIST_CONTAINER);
        return container != null && place(container);
    }

    /**
     * Puts the box over the column holding [container]. A feed post's sheet slides the Meta AI row
     * above the list away as the list scrolls, pulling the list up behind it with a negative top
     * margin as deep as an action bar, which is past the row's own height. A box between the two
     * had the list drawn over it. So the box goes in the column's frame, above the column, and the
     * column keeps a top padding as tall as the box, clipped, so what slides up goes under the box.
     * With no frame around the column, the box goes in the column right above the list.
     */
    static boolean place(View container) {
        if (!(container.getParent() instanceof LinearLayout)
                || ((LinearLayout) container.getParent()).getOrientation() != LinearLayout.VERTICAL) {
            HookStatus.counted(FamilyNames.COMMENT_SEARCH, COUNT_NO_LIST);
            return false;
        }
        LinearLayout column = (LinearLayout) container.getParent();
        ViewGroup frame = column.getParent() instanceof FrameLayout ? (ViewGroup) column.getParent() : column;
        if (frame.findViewWithTag(TAG) != null) return true;
        View box = box(column.getContext(), container);
        if (frame == column) {
            column.addView(box, column.indexOfChild(container),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            FrameLayout.LayoutParams where = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            if (column.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) column.getLayoutParams();
                where.setMargins(margins.leftMargin, margins.topMargin, margins.rightMargin, 0);
            }
            frame.addView(box, frame.indexOfChild(column) + 1, where);
            keepRoom(column, box);
        }
        HookStatus.counted(FamilyNames.COMMENT_SEARCH, COUNT_BAR);
        return true;
    }

    /** Keeps [column]'s top padding [box]'s height more than it was, from the first frame on. */
    private static void keepRoom(LinearLayout column, View box) {
        int base = column.getPaddingTop();
        column.setClipToPadding(true);
        int width = column.getResources().getDisplayMetrics().widthPixels;
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        pad(column, base + box.getMeasuredHeight());
        View.OnLayoutChangeListener fit = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            try {
                if (box.getParent() != null && box.getHeight() > 0 && column.getPaddingTop() != base + box.getHeight()) {
                    column.post(() -> pad(column, base + box.getHeight()));
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_SEARCH, HOOK, failure);
            }
        };
        box.addOnLayoutChangeListener(fit);
        column.addOnLayoutChangeListener(fit);
    }

    private static void pad(LinearLayout column, int top) {
        if (column.getPaddingTop() != top) {
            column.setPadding(column.getPaddingLeft(), top, column.getPaddingRight(), column.getPaddingBottom());
        }
    }

    /** The bar: the field, then the status, then the up and down arrows. */
    static View box(Context context, View container) {
        int ink = ink(context);
        LinearLayout bar = new LinearLayout(context);
        bar.setTag(TAG);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(context, 16), dp(context, 4), dp(context, 8), dp(context, 4));

        EditText field = new EditText(context);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(L10n.t("Search comments"));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setTextColor(ink);
        field.setHintTextColor(withAlpha(ink, 0x8C));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(withAlpha(ink, 0x17));
        fill.setCornerRadius(dp(context, 10));
        field.setBackground(fill);
        field.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        bar.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        status.setTextColor(withAlpha(ink, 0xA6));
        status.setPadding(dp(context, 8), 0, 0, 0);
        status.setVisibility(View.GONE);
        bar.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton up = arrow(context, android.R.drawable.arrow_up_float, L10n.t("Previous match"), ink);
        ImageButton down = arrow(context, android.R.drawable.arrow_down_float, L10n.t("Next match"), ink);
        bar.addView(up);
        bar.addView(down);

        CommentFinder finder = new CommentFinder(new ListHost(container, status, ink));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                try {
                    finder.search(text);
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.COMMENT_SEARCH, HOOK, failure);
                }
            }
        });
        field.setOnEditorActionListener((view, action, event) -> {
            try {
                if (!finder.searching()) return false;
                // A hardware Enter comes here twice, pressed and let go: only the press looks, or
                // one Enter would skip a match. The keyboard's own search key comes with no event.
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                find(finder, field, 1);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.COMMENT_SEARCH, HOOK, failure);
            }
            return true;
        });
        up.setOnClickListener(view -> find(finder, field, -1));
        down.setOnClickListener(view -> find(finder, field, 1));
        bar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attached) {
            }

            @Override
            public void onViewDetachedFromWindow(View detached) {
                finder.stop();
            }
        });
        return bar;
    }

    /**
     * Closes the keyboard and looks: with it open the list shows only a sliver, which the walk would
     * crawl through a line at a time and the match would barely show in.
     */
    private static void find(CommentFinder finder, EditText field, int direction) {
        try {
            InputMethodManager keyboard = (InputMethodManager) field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
            finder.find(direction);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_SEARCH, HOOK, failure);
        }
    }

    private static ImageButton arrow(Context context, int icon, String label, int ink) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(ink));
        button.setContentDescription(label);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true) && ripple.resourceId != 0) {
            button.setBackgroundResource(ripple.resourceId);
        } else {
            button.setBackground(null);
        }
        int size = dp(context, 40);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        return button;
    }

    /** The list's operations, for the finder. The list is found when first needed: the screen may make it late. */
    static final class ListHost implements CommentFinder.Host {
        private final View container;
        private final TextView status;
        private final int ink;
        @Nullable
        private ViewGroup list;

        ListHost(View container, TextView status, int ink) {
            this.container = container;
            this.status = status;
            this.ink = ink;
        }

        @Nullable
        private ViewGroup list() {
            ViewGroup found = list;
            if (found != null && found.getParent() != null) return found;
            View byId = container instanceof ViewGroup ? byName((ViewGroup) container, LIST) : null;
            found = byId instanceof ViewGroup ? (ViewGroup) byId : recycler(container);
            list = found;
            return found;
        }

        @Override
        public int rows() {
            ViewGroup now = list();
            return now == null ? 0 : now.getChildCount();
        }

        @Override
        public View row(int index) {
            ViewGroup now = list();
            return now == null ? null : now.getChildAt(index);
        }

        /** Where rows start showing: below the list's padding, and below the box once the list slides up under it. */
        @Override
        public int top() {
            ViewGroup now = list();
            return now == null ? 0 : Math.max(now.getPaddingTop(), underTheBox(now));
        }

        /** How far down [list] the column's top padding, the box's room, reaches. */
        private int underTheBox(View list) {
            if (!(container.getParent() instanceof ViewGroup)) return 0;
            int listTop = 0;
            View view = list;
            while (view != null && view != container) {
                listTop += view.getTop();
                view = view.getParent() instanceof View ? (View) view.getParent() : null;
            }
            if (view == null) return 0;
            return ((ViewGroup) container.getParent()).getPaddingTop() - (container.getTop() + listTop);
        }

        @Override
        public int height() {
            ViewGroup now = list();
            return now == null ? 0 : now.getHeight();
        }

        @Override
        public boolean canScroll(int direction) {
            ViewGroup now = list();
            return now != null && now.canScrollVertically(direction);
        }

        @Override
        public void scrollBy(int dy) {
            ViewGroup now = list();
            if (now != null && dy != 0) now.scrollBy(0, dy);
        }

        @Override
        public void later(Runnable step, long delayMillis) {
            container.postDelayed(() -> {
                try {
                    step.run();
                } catch (Throwable failure) {
                    HookStatus.threw(FamilyNames.COMMENT_SEARCH, HOOK, failure);
                }
            }, delayMillis);
        }

        @Override
        public boolean alive() {
            return container.isAttachedToWindow() && list() != null;
        }

        @Override
        public void found(View row) {
            flash(row, withAlpha(ink, 0x26));
        }

        @Override
        public void status(int state) {
            String text = state == CommentFinder.SEARCHING ? L10n.t("Searching...")
                    : state == CommentFinder.NO_MORE ? L10n.t("No more matches") : "";
            status.setText(text);
            status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /** Lays a see-through tint over [row] for a moment, then puts back what was there. */
    static void flash(View row, int color) {
        Drawable before = row.getForeground();
        ColorDrawable tint = new ColorDrawable(color);
        row.setForeground(tint);
        row.postDelayed(() -> {
            if (row.getForeground() == tint) row.setForeground(before);
        }, FLASH_MILLIS);
    }

    @Nullable
    static View byName(ViewGroup root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    /** The first view under [view] that tells accessibility it's a RecyclerView. */
    @Nullable
    private static ViewGroup recycler(View view) {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        CharSequence kind = group.getAccessibilityClassName();
        if (kind != null && kind.toString().endsWith(RECYCLER)) return group;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup found = recycler(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /** The theme's main text color, or white or black for the night mode in use. */
    static int ink(Context context) {
        TypedArray read = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            ColorStateList colors = read.getColorStateList(0);
            if (colors != null) return colors.getDefaultColor();
        } finally {
            read.recycle();
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES ? Color.WHITE : Color.BLACK;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
