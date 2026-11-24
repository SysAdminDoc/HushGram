/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import java.util.Arrays;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * More people in the row a long press on a post's Share button opens (discussion #811). Instagram
 * puts four faces there: it takes four people off the top of the share sheet's list, draws no more
 * than four faces from whichever list it has, and makes the popup four faces wide. The patch asks
 * {@link #people(int)} for each of those fours instead, and with the switch on the answer is as many
 * faces as fit across the window, so the row fills it.
 *
 * <p>The popup's layout only holds four slots, an avatar and its name each, laid over the bar by the
 * layout's constraints, and the face for each rank finds its slot by id. So {@link #addSlots} gives
 * each face past four a slot of its own, taken from a second copy of the same layout so it's drawn
 * just like Instagram's, and lines every slot up along the widened bar. The fifth takes the ids
 * Instagram already gives a fifth face; the rest get new ones, which {@link #avatarId} and
 * {@link #labelId} hand the rank lookup.
 */
public final class QuickSend {
    /** The popup's sizes on 456.1, in dp: each face, the gap between two, and the padding at each end. */
    static final float FACE_DP = 44f;
    static final float GAP_DP = 16f;
    static final float PADDING_DP = 12f;
    /**
     * Room left clear at each side of the window, so the row never runs off it: the popup's own box
     * sits about 20 dp in from each side (55 px on the S25), and the bar can't be wider than the box.
     */
    static final float EDGE_DP = 20f;
    /** The most faces the row takes, however wide the window. */
    static final int MOST = 10;
    /** The faces Instagram's layout has slots for. */
    static final int STOCK = 4;
    /** The names of a slot's avatar and name, each followed by the slot's number from 0. */
    static final String AVATAR = "avatar_image_view_";
    static final String LABEL = "avatar_label_text_";
    /** The bar the faces sit on. */
    static final String BAR = "quick_send_popup_window";

    private static boolean logged;
    /** The layout the newest popup was made from, for the copies its extra slots come from. */
    private static int layout;
    /** How many faces the newest popup had slots for, {@link #MOST} until one is made. */
    private static int room = MOST;
    /** The avatar's and the name's ids for each slot, by slot, zero for a slot there isn't. */
    private static final int[] avatarIds = new int[MOST];
    private static final int[] labelIds = new int[MOST];

    private QuickSend() { }

    /**
     * How many people the row shows where Instagram shows [stock]: [stock] while the switch is off,
     * before the settings are ready or when anything goes wrong, and otherwise as many as fit across
     * the window, never fewer than [stock]. Never throws, and never waits for the settings.
     */
    public static int people(int stock) {
        try {
            HookStatus.invoked(FamilyNames.SHARE_SHEET);
            if (!Utils.settingsReady() || !Settings.MORE_QUICK_SEND_PEOPLE.get()) return stock;
            int answer = Math.min(fit(stock, windowWidthDp()), Math.max(stock, room));
            if (!logged && answer != stock) {
                logged = true;
                Logger.printDebug(() -> "Quick send: room for " + answer + " people");
            }
            return answer;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, "quick send", failure);
            return stock;
        }
    }

    /** Inflates the popup's layout as Instagram does, and remembers which layout it was. */
    public static View inflate(LayoutInflater inflater, int resource, ViewGroup root) {
        layout = resource;
        return inflater.inflate(resource, root);
    }

    /**
     * Makes room in a new popup for [faces] faces, the most its constructor kept, and answers how
     * many it has room for: [faces] when that's no more than Instagram's four, and otherwise four
     * plus each slot it could add. Answers four, adding nothing, when Instagram's slots or the bar
     * can't be found, so the row is never asked for a face it has no slot for. Never throws.
     */
    public static int addSlots(PopupWindow popup, int faces) {
        if (faces <= STOCK) return faces;
        int made = STOCK;
        try {
            View content = popup.getContentView();
            Context context = content.getContext();
            Resources res = context.getResources();
            String pkg = context.getPackageName();
            View bar = content.findViewById(res.getIdentifier(BAR, "id", pkg));
            View[] avatars = new View[Math.min(faces, MOST)];
            View[] labels = new View[avatars.length];
            Arrays.fill(avatarIds, 0);
            Arrays.fill(labelIds, 0);
            for (int i = 0; i < STOCK; i++) {
                avatars[i] = content.findViewById(avatarIds[i] = res.getIdentifier(AVATAR + i, "id", pkg));
                labels[i] = content.findViewById(labelIds[i] = res.getIdentifier(LABEL + i, "id", pkg));
                if (avatars[i] == null || labels[i] == null) return give(STOCK);
            }
            ViewGroup avatarRow = parentOf(avatars[STOCK - 1]);
            ViewGroup labelRow = parentOf(labels[STOCK - 1]);
            if (bar == null || layout == 0 || avatarRow == null || labelRow == null || bar.getParent() != avatarRow) {
                return give(STOCK);
            }
            LayoutInflater inflater = LayoutInflater.from(context);
            View copy = null;
            int taken = STOCK;
            try {
                while (made < avatars.length) {
                    if (taken == STOCK) {
                        copy = inflater.inflate(layout, null, false);
                        taken = 0;
                    }
                    View avatar = copy.findViewById(res.getIdentifier(AVATAR + taken, "id", pkg));
                    View label = copy.findViewById(res.getIdentifier(LABEL + taken, "id", pkg));
                    taken++;
                    ViewGroup from = parentOf(avatar);
                    ViewGroup labelFrom = parentOf(label);
                    if (from == null || labelFrom == null) break;
                    int avatarId = idFor(res, pkg, AVATAR + made);
                    int labelId = idFor(res, pkg, LABEL + made);
                    from.removeView(avatar);
                    labelFrom.removeView(label);
                    avatar.setId(avatarId);
                    label.setId(labelId);
                    avatarRow.addView(avatar, avatar.getLayoutParams());
                    labelRow.addView(label, label.getLayoutParams());
                    avatars[made] = avatar;
                    labels[made] = label;
                    avatarIds[made] = avatarId;
                    labelIds[made] = labelId;
                    made++;
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.SHARE_SHEET, "quick send slots", failure);
            }
            if (made > STOCK) {
                View[] placed = Arrays.copyOf(avatars, made);
                View[] named = Arrays.copyOf(labels, made);
                float density = res.getDisplayMetrics().density;
                avatarRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                        place(bar, placed, named, (FACE_DP + GAP_DP) * density, PADDING_DP * density));
            }
            return give(made);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHARE_SHEET, "quick send slots", failure);
            return give(made);
        }
    }

    /** Back to how a fresh process starts: no popup made yet. For tests. */
    static void forget() {
        layout = 0;
        room = MOST;
        logged = false;
        Arrays.fill(avatarIds, 0);
        Arrays.fill(labelIds, 0);
    }

    /** Keeps [count] as the newest popup's room and answers it. */
    private static int give(int count) {
        room = count;
        if (count < MOST) {
            Arrays.fill(avatarIds, count, MOST, 0);
            Arrays.fill(labelIds, count, MOST, 0);
        }
        return count;
    }

    /**
     * Lines the faces up along [bar], [step] apart from the bar's start plus [padding], the way
     * Instagram's width works out, whatever the layout's own constraints made of them, and puts each
     * name over its face. Goes from the right in a right to left layout.
     */
    static void place(View bar, View[] avatars, View[] labels, float step, float padding) {
        boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < avatars.length; i++) {
            View avatar = avatars[i];
            float left = rtl ? bar.getRight() - padding - i * step - avatar.getWidth() : bar.getLeft() + padding + i * step;
            avatar.setTranslationX(left - avatar.getLeft());
            View label = labels[i];
            if (label != null && label.getParent() == avatar.getParent()) {
                float middle = left + avatar.getWidth() / 2f;
                label.setTranslationX(middle - (label.getLeft() + label.getWidth() / 2f));
            }
        }
    }

    /** The avatar's id for a face's [rank], counted from 1, or zero when the popup has no slot for it. */
    public static int avatarId(int rank) {
        return rank >= 1 && rank <= MOST ? avatarIds[rank - 1] : 0;
    }

    /** The name's id for a face's [rank], counted from 1, or zero when the popup has no slot for it. */
    public static int labelId(int rank) {
        return rank >= 1 && rank <= MOST ? labelIds[rank - 1] : 0;
    }

    /** Instagram's own id by [name] when it has one, as it has for a fifth face, otherwise a new one. */
    private static int idFor(Resources res, String pkg, String name) {
        int id = res.getIdentifier(name, "id", pkg);
        return id != 0 ? id : View.generateViewId();
    }

    private static ViewGroup parentOf(View view) {
        return view != null && view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
    }

    /** How many faces fit across [widthDp], between [stock] and {@link #MOST}. */
    static int fit(int stock, float widthDp) {
        float room = widthDp - 2 * PADDING_DP - 2 * EDGE_DP + GAP_DP;
        int fits = room <= 0 ? 0 : (int) (room / (FACE_DP + GAP_DP));
        return Math.max(stock, Math.min(MOST, fits));
    }

    /** The width of Instagram's window, which split screen narrows, or the screen's when there's no window yet. */
    private static float windowWidthDp() {
        Activity activity = Utils.getActivity();
        if (activity != null) {
            int width = activity.getResources().getConfiguration().screenWidthDp;
            if (width > 0) return width;
        }
        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        return metrics.widthPixels / metrics.density;
    }
}
