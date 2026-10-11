/*
 * The config numbers, parameter indexes and on values come from docs/mappings/piko_recommended_flags_v3.json
 * in https://github.com/crimera/piko (GPL-3.0), commit ea752648f5688a50a96df21430020e98bf9ece00.
 * Modified for HushGram, 2026. https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import app.hushgram.extension.shared.L10n;

/**
 * Named on/off switches for a short list of Instagram's hidden settings (MetaConfig flags).
 *
 * <p>Each switch is one or more boolean parameters, listed by config and parameter index. The list
 * follows the community-kept recommended flags of Piko (crimera/piko, GPL-3.0), with our own names
 * and descriptions. A switch has three states: Instagram's own value (no override), On and Off.
 * Choosing one builds the current overrides with just that switch's parameters changed and applies
 * that through {@link OverrideImport}, the path Import overrides uses, so the previous overrides are
 * saved for Restore first and Instagram's own typed writer does the writing. Nothing here touches the
 * native store directly. A switch whose first parameter isn't a boolean in this Instagram build isn't
 * offered, and any other of its parameters that this build lacks are left alone.
 */
public final class RecommendedFlags {
    private RecommendedFlags() {}

    public enum Choice {
        /** No override: Instagram decides. */
        DEFAULT,
        ON,
        OFF,
        /** Overrides that don't amount to On or Off, such as one parameter set by hand. */
        OTHER
    }

    /** One switch: a name, one line saying what it does, and the parameters it sets. */
    public static final class Flag {
        public final String title, description;
        final int[] config, index;
        /** For each parameter, the value that means the switch is On. */
        final boolean[] on;

        Flag(String title, String description, int[] config, int[] index, boolean[] on) {
            if (config.length == 0 || config.length != index.length || config.length != on.length) {
                throw new IllegalArgumentException("parameter lists differ in length");
            }
            this.title = title; this.description = description;
            this.config = config; this.index = index; this.on = on;
        }

        /** Number of parameters the switch sets when every one exists. */
        public int size() { return config.length; }
    }

    /** What a page shows: the switches this build offers and where each stands. */
    public static final class Page {
        public final List<Flag> flags = new ArrayList<>();
        public final List<Choice> choices = new ArrayList<>();
    }

    private static final class Builder {
        final String title, description;
        final List<int[]> parameters = new ArrayList<>();
        final List<Boolean> on = new ArrayList<>();
        Builder(String title, String description) { this.title = title; this.description = description; }
        /** Turning the switch on sets this parameter true. */
        Builder p(int config, int index) { parameters.add(new int[]{config, index}); on.add(true); return this; }
        /** Turning the switch on sets this parameter false. */
        Builder q(int config, int index) { parameters.add(new int[]{config, index}); on.add(false); return this; }
        Flag build() {
            int n = parameters.size();
            int[] configs = new int[n], indexes = new int[n];
            boolean[] values = new boolean[n];
            for (int i = 0; i < n; i++) {
                configs[i] = parameters.get(i)[0];
                indexes[i] = parameters.get(i)[1];
                values[i] = on.get(i);
            }
            return new Flag(title, description, configs, indexes, values);
        }
    }

    /**
     * The switches, in the order the page shows them. The story style ones come first. Each string
     * is its own literal so the translation tables can find it.
     */
    public static List<Flag> catalogue() {
        List<Flag> flags = new ArrayList<>();
        flags.add(new Builder(L10n.t("New story fonts and animations"),
                L10n.t("The newer text fonts, animations and effects in the story editor."))
                .p(85030, 1).p(85030, 2).p(85030, 5).p(85030, 6).build());
        flags.add(new Builder(L10n.t("New story timestamp style"),
                L10n.t("The newer look for the \"posted ... ago\" label on stories."))
                .p(126445, 0).p(126445, 1).p(126445, 2).p(126445, 7).build());
        flags.add(new Builder(L10n.t("Redesigned story viewer list"),
                L10n.t("The newer list of who viewed and replied to your story."))
                .p(119728, 0).p(119728, 1).p(119728, 2).p(119728, 3).p(119728, 4).p(119728, 6).build());
        flags.add(new Builder(L10n.t("Edit a story after posting"),
                L10n.t("Lets you change a story for a short while after you post it."))
                .p(118413, 0).build());
        flags.add(new Builder(L10n.t("Post a story straight to Highlights"),
                L10n.t("Adds the option to put a new story in a Highlight as you post it."))
                .p(93316, 1).p(93316, 5).p(93316, 6).build());
        flags.add(new Builder(L10n.t("Leave a Close Friends list"),
                L10n.t("Tap the Close Friends icon on someone's story to remove yourself from their list."))
                .p(105130, 0).build());
        flags.add(new Builder(L10n.t("Send stories with a long press"),
                L10n.t("Press and hold the paper plane on a story to send it."))
                .p(119043, 0).build());
        flags.add(new Builder(L10n.t("Quick emoji reactions on stories"),
                L10n.t("Press and hold the like button on a story to pick an emoji reaction."))
                .p(127260, 0).build());
        flags.add(new Builder(L10n.t("Blurred background on story reposts"),
                L10n.t("Fills the space behind a reposted reel, post or story with a blur."))
                .p(110215, 0).p(110215, 1).p(110215, 2).build());
        flags.add(new Builder(L10n.t("Upload stories at higher quality"),
                L10n.t("Asks Instagram to keep more detail when it prepares a story. Instagram's servers still "
                        + "compress what they receive."))
                .p(91245, 0).p(91245, 1).p(91245, 2).p(111955, 0).q(98437, 0).build());
        flags.add(new Builder(L10n.t("New like animations"),
                L10n.t("The newer animations when you like a post, and the emoji ones on stories."))
                .p(122670, 0).p(121368, 0).build());
        flags.add(new Builder(L10n.t("2x speed for videos in the feed"),
                L10n.t("Press and hold the edge of a video in your feed to watch it at double speed."))
                .p(103957, 1).p(103957, 2).p(103957, 3).p(103957, 8).build());
        flags.add(new Builder(L10n.t("Playback speed in the Reels menu"),
                L10n.t("Adds a speed choice to the menu on a reel."))
                .p(109947, 0).build());
        flags.add(new Builder(L10n.t("Quick speed menu in Reels"),
                L10n.t("Press and hold a reel to open a menu with playback speed."))
                .p(121815, 0).build());
        // piko names 83371::10 "Per-session auto-scroll": true makes Instagram reset auto scroll at
        // every start, so On here, keeping it between sessions, sets the parameter false.
        flags.add(new Builder(L10n.t("Keep Reels auto scroll between sessions"),
                L10n.t("Instagram turns auto scroll off each time it starts. On keeps it the way you left it."))
                .q(83371, 10).build());
        flags.add(new Builder(L10n.t("Preview of disappearing photos in Messages"),
                L10n.t("Shows a blurred preview of a disappearing photo or video in a chat."))
                .p(98405, 1).build());
        flags.add(new Builder(L10n.t("Enlarge profile photos"),
                L10n.t("Lets you tap a profile photo to see it bigger, with a setting in Account Privacy."))
                .p(46551, 1).p(46551, 6).build());
        flags.add(new Builder(L10n.t("Redesigned Explore tab"),
                L10n.t("The newer layout of the Explore tab."))
                .p(124823, 0).build());
        flags.add(new Builder(L10n.t("Saved button in Explore"),
                L10n.t("Adds a shortcut to your saved posts in the Explore tab."))
                .p(126311, 1).p(133789, 0).build());
        flags.add(new Builder(L10n.t("Usernames in Messages and notifications"),
                L10n.t("Shows usernames in place of display names in chats and notifications."))
                .p(77064, 0).p(77064, 1).p(77064, 2).build());
        flags.add(new Builder(L10n.t("Redesigned notification settings"),
                L10n.t("The newer layout of the notification settings."))
                .p(91066, 0).build());
        flags.add(new Builder(L10n.t("Suggested accounts in search"),
                L10n.t("Choose Off to hide the suggested accounts that show before you type a search."))
                .p(130063, 0).p(130063, 4).p(130063, 3).p(130063, 2).build());
        flags.add(new Builder(L10n.t("Meta AI suggestions in search"),
                L10n.t("Choose Off to show your recent searches instead of AI suggestions."))
                .p(111509, 3).build());
        flags.add(new Builder(L10n.t("Ultra HDR photos"),
                L10n.t("Choose Off to stop photos from being shown at full HDR brightness."))
                .p(109125, 0).build());
        flags.add(new Builder(L10n.t("Extra-bright HDR video"),
                L10n.t("Choose Off to remove the brightness boost on reels and videos on HDR screens."))
                .p(68885, 0).build());
        return flags;
    }

    /** Reads the store and lists the switches this Instagram build offers, each with its current state. */
    public static Page load(Activity activity) throws IOException {
        return load(OverrideExchange.capture(activity), catalogue());
    }

    static Page load(OverrideExchange.Snapshot snapshot, List<Flag> candidates) throws IOException {
        Map<Long, String> current = OverrideExchange.values(raw(snapshot), snapshot);
        Page page = new Page();
        for (Flag flag : candidates) {
            if (!offered(snapshot, flag)) continue;
            page.flags.add(flag);
            page.choices.add(choice(snapshot, flag, current));
        }
        return page;
    }

    /** Where the first parameter is a boolean in this build, the switch is offered. */
    private static boolean offered(OverrideExchange.Snapshot snapshot, Flag flag) {
        return OverrideExchange.typeOf(snapshot, flag.config[0], flag.index[0]) == 1;
    }

    private static boolean applies(OverrideExchange.Snapshot snapshot, Flag flag, int at) {
        return OverrideExchange.typeOf(snapshot, flag.config[at], flag.index[at]) == 1;
    }

    private static byte[] raw(OverrideExchange.Snapshot snapshot) {
        return snapshot.raw == null ? "{}".getBytes(StandardCharsets.UTF_8) : snapshot.raw;
    }

    private static long key(Flag flag, int at) { return OverrideExchange.key(flag.config[at], flag.index[at]); }

    private static Choice choice(OverrideExchange.Snapshot snapshot, Flag flag, Map<Long, String> current) {
        int set = 0, on = 0, off = 0, count = 0;
        for (int i = 0; i < flag.size(); i++) {
            if (!applies(snapshot, flag, i)) continue;
            count++;
            String value = current.get(key(flag, i));
            if (value == null) continue;
            set++;
            if (value.equals(Boolean.toString(flag.on[i]))) on++;
            else if (value.equals(Boolean.toString(!flag.on[i]))) off++;
        }
        if (set == 0) return Choice.DEFAULT;
        if (on == count) return Choice.ON;
        if (off == count) return Choice.OFF;
        return Choice.OTHER;
    }

    /**
     * Sets one switch through Import overrides' path: the current overrides plus this change, with
     * the previous ones saved for Restore first. Restart Instagram afterwards. Never called with
     * {@link Choice#OTHER}.
     */
    public static OverrideImport.Result set(Activity activity, Flag flag, Choice wanted) throws IOException {
        if (activity == null || flag == null || wanted == null || wanted == Choice.OTHER) throw OverrideImport.invalid();
        OverrideExchange.Snapshot snapshot = OverrideExchange.capture(activity);
        if (!offered(snapshot, flag)) throw OverrideImport.invalid();
        Map<Long, String> changes = new TreeMap<>();
        for (int i = 0; i < flag.size(); i++) {
            if (!applies(snapshot, flag, i)) continue;
            changes.put(key(flag, i), wanted == Choice.DEFAULT ? null
                    : Boolean.toString(wanted == Choice.ON ? flag.on[i] : !flag.on[i]));
        }
        return OverrideImport.apply(activity, OverrideExchange.withOverrides(snapshot, changes));
    }

    /** The codes of the switches, as `config` or `config::index`, for tests and docs. */
    static List<String> codes(Flag flag) {
        String[] codes = new String[flag.size()];
        for (int i = 0; i < codes.length; i++) codes[i] = flag.config[i] + "::" + flag.index[i];
        return Arrays.asList(codes);
    }
}
