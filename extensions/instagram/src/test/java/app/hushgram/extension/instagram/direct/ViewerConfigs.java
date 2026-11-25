/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

/**
 * Stand-ins for a Reels viewer's ClipsViewerConfig, which the patch hands the chat reels hooks, read
 * through {@link ChatReels#readConfigsWith} in place of the patch's bridges. Install them in a test's
 * {@code @Before} and reset them in its {@code @After}.
 */
final class ViewerConfigs {
    /** Stand-ins for Instagram's viewer sources, named as its enum names them. */
    enum Source { DIRECT, DIRECT_SEARCH, CLIPS_TAB, BLEND }

    /** A chat's id as Instagram's threads carry it. */
    static final String THREAD = "340282366841710300949128";

    /** A reel tapped in a chat: opened as the chat's, holding the chat. */
    static final Config CHAT = new Config(Source.DIRECT, THREAD);
    /**
     * A reel tapped in a chat that has a Blend, once Instagram has loaded the Blend: opened as the
     * Blend's, still holding the chat (#804).
     */
    static final Config CHATS_BLEND = new Config(Source.BLEND, THREAD);
    /** A Blend opened outside a chat, from the inbox: the Blend's, holding no chat. */
    static final Config BLEND = new Config(Source.BLEND, null);
    /** A viewer opened as a chat's that holds no chat. */
    static final Config NO_CHAT = new Config(Source.DIRECT, null);
    /** The Reels tab. */
    static final Config CLIPS_TAB = new Config(Source.CLIPS_TAB, null);
    /** A reel found by searching a chat, which isn't the chat's viewer. */
    static final Config SEARCH = new Config(Source.DIRECT_SEARCH, THREAD);

    private ViewerConfigs() {
    }

    /** Has the extension read [Config]s, and nothing else, as viewer configs. */
    static void install() {
        ChatReels.readConfigsWith(
            config -> config instanceof Config ? ((Config) config).source : null,
            config -> config instanceof Config ? ((Config) config).thread : null);
    }

    /** Puts the patch's bridges back. */
    static void reset() {
        ChatReels.readConfigsWith(null, null);
    }

    /** A viewer config opened from [source], holding the chat with the id [thread], or none. */
    static final class Config {
        final Object source;
        /** The chat's id, or null for none. */
        final String thread;

        Config(Object source, String thread) {
            this.source = source;
            this.thread = thread;
        }

        @Override public String toString() {
            return source + (thread == null ? " without a chat" : " holding a chat");
        }
    }
}
