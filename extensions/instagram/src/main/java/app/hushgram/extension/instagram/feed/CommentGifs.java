/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Hide GIFs in comments patch (#511): takes GIFs and stickers out of a post's comments
 * and off the comment box.
 *
 * <p>The comment sheet builds one row object for each comment, through one builder. While the switch
 * is on {@link #dropsComment} answers yes for a comment that's only a GIF or a sticker, a mention at
 * most, and the builder answers null, which every one of its callers already takes as no row. A comment
 * with words keeps them and loses its picture, through {@link #keptGif} for a GIF and
 * {@link #hidesMedia} for a sticker, which reaches the row through its media.
 *
 * <p>The box offers GIFs through a set of picker kinds. {@link #composerTypes} takes the GIF kind out,
 * so with nothing else in the set Instagram draws no button at all, and with stickers in it the button
 * stays for those. The older thread layout's button has its own id, which {@link ViewHider} keeps gone.
 *
 * <p>Comments already on screen keep their rows until the sheet loads them again. Off, paused, before
 * the settings are read or on any failure, everything stays as Instagram has it. No hook here throws.
 */
public final class CommentGifs {
    /** The GIF button of the older thread layout. */
    static final String BUTTON = "layout_comment_thread_animated_image_picker_button";

    static final String HOOK = "comment gifs";
    static final String COUNT_HIDDEN = "comment gifs hidden";

    /** The name of the picker kind that offers GIFs, on an enum whose names survive its build. */
    private static final String GIF_KIND = "GIF";

    /** A comment's words when it has none of its own: nothing, or a GIF reply's "@name". */
    private static final Pattern ONLY_MENTIONS = Pattern.compile("\\s*(@[\\w.]+\\s*)*");

    private static final ViewHider HIDER = new ViewHider(FamilyNames.COMMENT_GIFS, HOOK, COUNT_HIDDEN,
            () -> Settings.HIDE_COMMENT_GIFS.get(), null, BUTTON);

    private CommentGifs() {
    }

    /** Called as the application starts. Does nothing in a build without the patch. */
    public static void install(Context context) {
        HIDER.install(context, SettingsStatus.commentGifs());
    }

    /** The switch was flipped in HushGram settings, a dialog over [activity]: the screen under it decides now. */
    public static void switchChanged(@Nullable Activity activity) {
        HIDER.switchChanged(activity);
    }

    /** Whether the builder has anything to ask, read first so a row costs nothing with the switch off. */
    public static boolean hiding() {
        try {
            HookStatus.invoked(FamilyNames.COMMENT_GIFS);
            return HIDER.wanted();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_GIFS, HOOK, failure);
            return false;
        }
    }

    /**
     * Whether a comment gets no row at all: it has a GIF ([gif]) or a sticker ([mediaGif], the one on its
     * media) and [text] holds no words of its own. No with the switch off and on any failure.
     */
    public static boolean dropsComment(@Nullable Object gif, @Nullable Object mediaGif, @Nullable String text) {
        try {
            if (gif == null && mediaGif == null) return false;
            if (!HIDER.wanted()) return false;
            if (text != null && !ONLY_MENTIONS.matcher(text).matches()) return false;
            HookStatus.counted(FamilyNames.COMMENT_GIFS, COUNT_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_GIFS, HOOK, failure);
            return false;
        }
    }

    /** The GIF a comment's row gets: none while the switch is on, otherwise [gif] itself. */
    @Nullable
    public static Object keptGif(@Nullable Object gif) {
        try {
            if (gif == null || !HIDER.wanted()) return gif;
            HookStatus.counted(FamilyNames.COMMENT_GIFS, COUNT_HIDDEN);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_GIFS, HOOK, failure);
            return gif;
        }
    }

    /** Whether a row's media is a sticker to leave out, [mediaGif] being the GIF on that media. */
    public static boolean hidesMedia(@Nullable Object mediaGif) {
        try {
            if (mediaGif == null || !HIDER.wanted()) return false;
            HookStatus.counted(FamilyNames.COMMENT_GIFS, COUNT_HIDDEN);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_GIFS, HOOK, failure);
            return false;
        }
    }

    /** The picker kinds the comment box offers: [kinds] without the GIF one while the switch is on. */
    @Nullable
    public static Set<?> composerTypes(@Nullable Set<?> kinds) {
        try {
            if (kinds == null || kinds.isEmpty() || !HIDER.wanted()) return kinds;
            Set<Object> kept = new LinkedHashSet<>();
            for (Object kind : kinds) {
                if (!(kind instanceof Enum) || !GIF_KIND.equals(((Enum<?>) kind).name())) kept.add(kind);
            }
            return kept.size() == kinds.size() ? kinds : kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.COMMENT_GIFS, HOOK, failure);
            return kinds;
        }
    }

    static ViewHider hider() {
        return HIDER;
    }

    public static boolean listeningForTests(Activity activity) {
        return HIDER.listeningForTests(activity);
    }

    public static void resetForTests() {
        HIDER.resetForTests();
    }
}
