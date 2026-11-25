/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import androidx.annotation.Nullable;

import java.util.function.Function;

import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Whether a Reels viewer is a chat's, read off its ClipsViewerConfig, for every patch keyed on a
 * chat's viewer (#804).
 *
 * <p>Only a tap on a reel in a chat hands the viewer the chat. Until Instagram has loaded a chat's
 * Blend that tap opens the viewer as the chat's (DIRECT), and once it has, as the Blend's (BLEND),
 * still holding the chat, so where it was opened alone took it for a Blend's. A Blend opened anywhere
 * else holds no chat. The patch writes {@link #viewerSource(Object)} and {@link #chatThread(Object)}
 * to read a config.
 */
public final class ViewerChat {
    /** The name of Instagram's viewer source for a reel opened from a chat. */
    static final String CHAT_SOURCE = "DIRECT";

    /**
     * The name of Instagram's viewer source for a Blend. A reel tapped in a chat that has a Blend opens
     * with it once Instagram has loaded the chat's Blend, and as a chat's before that (#804).
     */
    static final String BLEND_SOURCE = "BLEND";

    /** How a viewer's config is read, its ClipsViewerSource and its chat's id: the patch's bridges, or a test's. */
    private static volatile Function<Object, Object> sources = ViewerChat::viewerSource;
    private static volatile Function<Object, String> threads = ViewerChat::chatThread;

    private ViewerChat() {
    }

    /**
     * Whether [config], a Reels viewer's ClipsViewerConfig, is a chat's: it holds a chat with an id and
     * was opened as the chat's or as its Blend's ({@link #isChat(Object, String)}). No for a null
     * config and on a failed read, which is counted against [family], the asking switch's. Never throws.
     */
    public static boolean holdsChat(@Nullable Object config, String family) {
        if (config == null) return false;
        try {
            return isChat(sources.apply(config), threads.apply(config));
        } catch (Throwable failure) {
            HookStatus.threw(family, "chat reels viewer", failure);
            return false;
        }
    }

    /**
     * Where the viewer whose ClipsViewerConfig is [config] was opened from, its ClipsViewerSource, or
     * null for a null config and on a failed read, which is counted against [family]. Never throws.
     */
    @Nullable
    public static Object sourceOf(@Nullable Object config, String family) {
        if (config == null) return null;
        try {
            return sources.apply(config);
        } catch (Throwable failure) {
            HookStatus.threw(family, "viewer source", failure);
            return null;
        }
    }

    /**
     * Whether a viewer opened from [source], its ClipsViewerSource, holding the chat with the id
     * [thread] is that chat's: the id is there and the source is a chat's or a Blend's.
     */
    public static boolean isChat(@Nullable Object source, @Nullable String thread) {
        return thread != null && !thread.isEmpty() && (named(source, CHAT_SOURCE) || named(source, BLEND_SOURCE));
    }

    /** Whether [source], a viewer's ClipsViewerSource, is the one Instagram names [name]. */
    static boolean named(@Nullable Object source, String name) {
        return source instanceof Enum && name.equals(((Enum<?>) source).name());
    }

    /** Stub: the patch writes this, answering the ClipsViewerSource of [config], a viewer's ClipsViewerConfig. */
    @Nullable
    public static Object viewerSource(Object config) {
        return null;
    }

    /** Stub: the patch writes this, answering the id of the chat [config], a viewer's ClipsViewerConfig, holds, or null. */
    @Nullable
    public static String chatThread(Object config) {
        return null;
    }

    /** Reads viewer configs with [sources] and [threads] in place of the patch's bridges, for tests; nulls put the bridges back. */
    static void readConfigsWith(@Nullable Function<Object, Object> sources, @Nullable Function<Object, String> threads) {
        ViewerChat.sources = sources == null ? ViewerChat::viewerSource : sources;
        ViewerChat.threads = threads == null ? ViewerChat::chatThread : threads;
    }
}
