/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.view.View;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the call buttons switch of the "Ask before a call" patch (#217): takes the voice and
 * video call buttons off the top of a chat.
 *
 * <p>Instagram 452 draws the buttons at the right of a chat's header from a list of button models,
 * each with a kind (an enum: {@code VIDEO_CALL}, {@code AUDIO_CALL}, the {@code COMBINED_CALL_*}
 * pills, {@code COLLAPSED_CALL} and the two AI call kinds). Two methods of the header's binder fill
 * a view from its model, one for the pill buttons and one for the icon buttons, and the patch hands
 * each of them the view and the model through {@link #bound} first thing. A view whose model is a
 * call kind goes GONE with the switch on, so the buttons give their room to the name. A button
 * that's on a call still in progress is another kind and stays.
 *
 * <p>The Blend buttons of that row ({@code BLEND} and {@code BLEND_ADD}) go GONE the same way with
 * Hide Blend in chats on (#688).
 *
 * <p>The back arrow at the left of the header isn't one of those buttons: Instagram binds it from its
 * own model, the header model's left button, and hides the arrow and gives its room to the picture
 * and the name when that is null. The patch hands the left button to {@link #leftButton} as the
 * header model is made, and with Hide the back button in chats on a {@code BACK} one answers null
 * (#688). Any other left button, Cancel included, stays.
 *
 * <p>A view hidden here is remembered, weakly, so that the next bind with the switch off (or HushGram
 * paused) gives it back as Instagram had it: visible. A kind Instagram adds later that isn't named
 * here is never touched.
 *
 * <p>Fails open: with the switch off, HushGram paused, the settings not read yet or anything thrown,
 * the view is left as Instagram binds it.
 */
public final class CallButtons {
    /** The model kinds that start a call, as the names of Instagram's button enum. */
    public static final Set<String> CALL_KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "VIDEO_CALL", "AUDIO_CALL",
            "COMBINED_CALL_V1", "COMBINED_CALL_V2", "COMBINED_CALL_V3", "COMBINED_CALL_V4",
            "COLLAPSED_CALL", "AI_VIDEO_CALL", "AI_AUDIO_CALL")));

    /** The model kinds of the Blend buttons, as the names of Instagram's button enum. */
    public static final Set<String> BLEND_KINDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "BLEND", "BLEND_ADD")));

    /** The kind of the header's back arrow. */
    public static final String BACK = "BACK";

    /** What's counted for each call button taken off a header. */
    static final String HIDDEN = "call buttons hidden";
    /** What's counted for each Blend button taken off a header, and each back arrow left out. */
    static final String BLEND_HIDDEN = "Blend buttons hidden";
    static final String BACK_HIDDEN = "back arrows left out";

    /** The one stub, which tests swap for a stand-in. */
    interface Kinds {
        String kindOf(Object model);
    }

    static final Kinds PATCHED = CallButtons::kindOf;
    static volatile Kinds access = PATCHED;

    /** The views this hid, so a later bind with the switch off can show them again. Main thread only. */
    private static final WeakHashMap<View, Boolean> hidden = new WeakHashMap<>();

    private CallButtons() {
    }

    /**
     * Rewritten by the patch to answer the name of the kind of Instagram's button model [model].
     * Never called as written.
     */
    public static String kindOf(Object model) {
        return null;
    }

    /**
     * Injected at the start of both methods of the header's binder that fill a button from its
     * model. Never throws, and never waits for the settings.
     */
    public static void bound(View view, Object model) {
        try {
            HookStatus.invoked(FamilyNames.ASK_BEFORE_CALL);
            if (view == null) return;
            String kind = model == null ? null : access.kindOf(model);
            // A recycled view this hid can come back bound to another kind of button, or none, which shows again.
            boolean call = kind != null && CALL_KINDS.contains(kind);
            boolean blend = kind != null && BLEND_KINDS.contains(kind);
            boolean hide = (call || blend) && Utils.settingsReady() && !HushgramPause.isPaused() &&
                    (call ? Settings.HIDE_CALL_BUTTONS.get() : Settings.HIDE_CHAT_BLEND.get());
            if (hide) {
                view.setVisibility(View.GONE);
                hidden.put(view, Boolean.TRUE);
                HookStatus.counted(FamilyNames.ASK_BEFORE_CALL, call ? HIDDEN : BLEND_HIDDEN);
            } else if (hidden.remove(view) != null) {
                view.setVisibility(View.VISIBLE);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_CALL, "call buttons", failure);
        }
    }

    /**
     * Injected first thing where Instagram makes a chat header's model, with its left button [model].
     * Answers null, no left button, for the back arrow with Hide the back button in chats on, and
     * [model] for anything else. Never throws, and never waits for the settings.
     */
    @Nullable
    public static Object leftButton(@Nullable Object model) {
        try {
            HookStatus.invoked(FamilyNames.ASK_BEFORE_CALL);
            if (model == null || !Utils.settingsReady() || HushgramPause.isPaused() ||
                    !Settings.HIDE_CHAT_BACK_BUTTON.get()) return model;
            if (!BACK.equals(access.kindOf(model))) return model;
            HookStatus.counted(FamilyNames.ASK_BEFORE_CALL, BACK_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.ASK_BEFORE_CALL, "back button", failure);
            return model;
        }
    }
}
