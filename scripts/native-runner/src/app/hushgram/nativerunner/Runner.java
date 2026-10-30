/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.nativerunner;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one named case inside the installed target's own process and reports what it found.
 *
 * <p>Every result carries the facts the case ran against, measured here rather than taken on
 * trust: package, version, signer, the SHA-256 of the base APK this process loaded, the Android
 * user, the process name and whether this process has a network. scripts/native-runner.ps1
 * compares them with the local APK it was given and fails the run on any difference.
 *
 * <p>Instagram's Application.onCreate is never called, so the startup work it would start
 * (account restore, schedulers) doesn't run. Android still runs the target's attachBaseContext and
 * its content providers before this class starts, which is why the host turns the network off
 * first and every result reports the network this process sees.
 */
public final class Runner extends Instrumentation {
    static final String PREFIX = "hushgram.";
    private static final String HOST_CLASS = "com.instagram.mainactivity.InstagramMainActivity";
    private static final String EXTENSION_CLASS = "app.hushgram.extension.shared.Utils";
    // A name no patch defines. The negative control asks for it the way a real case asks for a
    // hook, so a lookup that can't fail closed shows up before any feature case trusts it.
    private static final String ABSENT_CLASS = "app.hushgram.extension.nativerunner.AbsentHook";

    private static final String SETTINGS_CLASS = "app.hushgram.extension.instagram.settings.Settings";
    private static final String BASE_SETTINGS_CLASS = "app.hushgram.extension.shared.settings.BaseSettings";
    private static final String PAUSE_CLASS = "app.hushgram.extension.shared.settings.HushgramPause";
    private static final String LAST_ACTIVE_CLASS = "app.hushgram.extension.instagram.direct.LastActive";
    // Meta's presence model keeps these names in every build. Hide your active status asks the
    // extension first in the write request's six-argument constructor.
    private static final String PRESENCE_MODEL = "com.facebook.presence.model.upi.";
    private static final String[] PRESENCE_STATUSES = {"ACTIVE", "IDLE", "OFFLINE", "DISABLED"};
    // Each 453.0.0.57.79 build's view-once receipt names, found with DexProbe from the handler's
    // endpoint and raven_media strings: the build, the visual and voice handlers, their shared
    // method, the visual and voice mutations, the mutation base, its analytics extras and the
    // completion interface. A set is used only once Instagram's own code agrees with it. The three
    // declared builds come first; 385914503 and 385914518 carry 385914556's and 385914558's names.
    private static final String[][] VISUAL_NAMES = {
            {"385914557", "X.05ek", "X.05en", "H2J", "X.0K8M", "X.0K8L", "X.0As6", "X.02nc", "X.0Vae"},
            {"385914556", "X.05ob", "X.05oe", "H2W", "X.0GFt", "X.0GFs", "X.0BHF", "X.02su", "X.0Zhp"},
            {"385914558", "X.05fr", "X.05fw", "H2a", "X.0L6u", "X.0L6s", "X.0AtC", "X.02oh", "X.0Uhp"},
            {"385914517", "X.05ek", "X.05en", "H2J", "X.0K8L", "X.0K8A", "X.0As6", "X.02nc", "X.0VbG"},
            {"385914546", "X.05ef", "X.05ej", "H28", "X.0L8b", "X.0L8N", "X.089W", "X.02mz", "X.0XAz"},
            {"385914510", "X.05ph", "X.05pl", "H2b", "X.0GHA", "X.0GH2", "X.0B69", "X.02tt", "X.0a21"},
    };
    private static final String VISUAL_MUTATION = "send_visual_item_seen_marker";
    private static final String VOICE_MUTATION = "send_voice_item_seen_marker";
    // The handler's own guard on a mutation without its message, thrown before any request exists.
    private static final String NATIVE_GUARD = "Required value was null.";
    private static final String NOTIFICATION_GROUPS_CLASS = "app.hushgram.extension.instagram.misc.NotificationGroups";
    // Two of the channel ids Instagram sets up (GroupNotificationsHookTest reads them from each
    // build): the promo pushes' and the likes'. The ids the case posts with stay clear of Instagram's.
    private static final String MUTED_CHANNEL = "ig_product_announcements";
    private static final String KEPT_CHANNEL = "ig_likes";
    private static final int MUTED_ID = 0x4847_0001;
    private static final int KEPT_ID = 0x4847_0003;
    private static final int UNMUTED_ID = 0x4847_0004;
    private static final int GROUPED_ID = 0x4847_0010;
    private static final int GROUPED_MUTED_ID = 0x4847_0012;
    private static final int GROUPED_UNMUTED_ID = 0x4847_0013;
    // NotificationGroups' one group and the tag its summaries are posted under.
    private static final String ONE_GROUP = "hushgram_notifications";
    private static final String SUMMARY_TAG = "hushgram_notification_group";
    private static final String LINK_CLEANER_CLASS = "app.hushgram.extension.instagram.misc.LinkCleaner";
    // A reel link as Copy link gives it, with the per-share id and a label, next to a link to another
    // site. Only Instagram's own link loses its keys.
    private static final String SHARED_TEXT = "Look https://www.instagram.com/reel/DHushRunner1/?igsh=MTc4MmM1YmI2Ng==&utm_source=ig_web_copy_link"
            + " and https://example.com/a?utm_source=x";
    private static final String CLEANED_TEXT = "Look https://www.instagram.com/reel/DHushRunner1/ and https://example.com/a?utm_source=x";
    // Where the share sheet would say which app was picked: a broadcast to the target itself, never sent.
    private static final String PICKED_ACTION = "app.hushgram.nativerunner.SHARE_PICKED";

    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    // Left empty on purpose: the target's Application.onCreate stays out of every case. Its
    // providers have already run by now.
    @Override
    public void callApplicationOnCreate(Application app) {
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        String name = arguments == null ? "" : arguments.getString("case", "");
        result.putString(PREFIX + "case", name);
        try {
            Context target = getTargetContext();
            bind(target, result);
            boolean passed = runCase(name, target, result);
            result.putString(PREFIX + "verdict", passed ? "pass" : "fail");
            if (passed) code = Activity.RESULT_OK;
        } catch (NeedsSession needed) {
            result.putString(PREFIX + "verdict", "needs-session");
            result.putString(PREFIX + "prerequisite", needed.getMessage());
        } catch (Throwable error) {
            result.putString(PREFIX + "verdict", "error");
            result.putString(PREFIX + "error", error.getClass().getName() + ": " + error.getMessage());
        }
        finish(code, result);
    }

    static boolean runCase(String name, Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        switch (name) {
            case "bind":
                return true;
            case "control-positive": {
                Class<?> host = requireClass(loader, HOST_CLASS, out);
                Class<?> extension = requireClass(loader, EXTENSION_CLASS, out);
                if (host == null || extension == null) return false;
                Method identity = extension.getMethod("getSourceBuildIdentity");
                Object answer = identity.invoke(null);
                out.putString(PREFIX + "buildIdentity", String.valueOf(answer));
                // An unpatched build, or a patch run that skipped the shared extension, answers
                // the stub's "Unknown".
                return answer instanceof String && ((String) answer).matches("hg1:[0-9a-f]{64}");
            }
            case "control-negative":
                return requireClass(loader, ABSENT_CLASS, out) != null;
            case "presence":
                return presence(target, out);
            case "presence-paused":
                return presencePaused(target, out);
            case "visual":
                return visual(target, out);
            case "visual-paused":
                return visualPaused(target, out);
            case "notifications":
                return notifications(target, out);
            case "share":
                return share(target, out);
            default:
                throw new IllegalArgumentException("Unknown case '" + name + "'");
        }
    }

    /**
     * Hide your active status on real write requests, built and read back, never sent: before the
     * settings are ready, with the switch off, then on. Only Active may become Idle, of the same
     * enum, and only while the switch is on. Kotlin's serializer constructor, which the hook leaves
     * out, keeps Active, and the incoming last-active text stays Instagram's own. Ends by saving
     * Pause and the switch on for presence-paused, which needs a new process to start paused.
     */
    private static boolean presence(Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Presence presence = Presence.find(loader, out);
        Class<?> utils = requireClass(loader, EXTENSION_CLASS, out);
        Class<?> settings = requireClass(loader, SETTINGS_CLASS, out);
        Class<?> base = requireClass(loader, BASE_SETTINGS_CLASS, out);
        Class<?> pause = requireClass(loader, PAUSE_CLASS, out);
        Class<?> lastActive = requireClass(loader, LAST_ACTIVE_CLASS, out);
        if (presence == null || utils == null || settings == null || base == null || pause == null || lastActive == null) return false;
        StringBuilder wrong = new StringBuilder();

        // Instagram's Application.onCreate gives the extension its context, and the runner skips it.
        boolean readyAtStart = (Boolean) utils.getMethod("settingsReady").invoke(null);
        out.putString(PREFIX + "settingsReadyAtStart", Boolean.toString(readyAtStart));
        if (readyAtStart) note(wrong, "the settings were ready before the case gave the extension its context");
        else out.putString(PREFIX + "presence.notReady", expect(wrong, "not ready", presence, presence.write("ACTIVE"), "ACTIVE", "ACTIVE"));

        utils.getMethod("setContext", Context.class).invoke(null, target);
        String reason = String.valueOf(pause.getMethod("reason").invoke(null));
        out.putString(PREFIX + "pause", reason);
        if (!"NONE".equals(reason)) note(wrong, "this start runs paused (" + reason + ")");
        Object hide = settings.getField("HIDE_ACTIVE_STATUS").get(null);
        Object showLastActive = settings.getField("SHOW_LAST_ACTIVE").get(null);
        Object paused = base.getField("PAUSED").get(null);

        save(hide, false);
        StringBuilder off = new StringBuilder();
        for (String status : PRESENCE_STATUSES) off.append(',').append(expect(wrong, "off", presence, presence.write(status), status, status));
        out.putString(PREFIX + "presence.off", off.substring(1));

        save(hide, true);
        StringBuilder on = new StringBuilder();
        for (String status : PRESENCE_STATUSES) {
            on.append(',').append(expect(wrong, "on", presence, presence.write(status), status, "ACTIVE".equals(status) ? "IDLE" : status));
        }
        out.putString(PREFIX + "presence.on", on.substring(1));
        out.putString(PREFIX + "presence.serializer", expect(wrong, "on, serializer", presence, presence.serialized("ACTIVE"), "ACTIVE", "ACTIVE"));

        // Someone last active an hour ago, with the switch still on. The presence formatter's hook
        // answers its own text only with Show when someone was last active on, and null, for
        // Instagram's own "Active 1h ago", with it off. The answer while on shows the null isn't a
        // broken path. Neither text is reported, only which one came back.
        Method exact = lastActive.getMethod("exact", long.class);
        long hourAgo = System.currentTimeMillis() - 60L * 60 * 1000;
        Object lastShown = showLastActive.getClass().getMethod("savedValue").invoke(showLastActive);
        save(showLastActive, true);
        Object replaced = exact.invoke(null, hourAgo);
        save(showLastActive, false);
        Object incoming = exact.invoke(null, hourAgo);
        save(showLastActive, (Boolean) lastShown);
        out.putString(PREFIX + "presence.incomingShown", replaced == null ? "native" : "replaced");
        out.putString(PREFIX + "presence.incoming", incoming == null ? "native" : "replaced");
        if (replaced == null) note(wrong, "on: the last-active hook gave no text with its own switch on, so its answer off proves nothing");
        if (incoming != null) note(wrong, "on: the last-active text was replaced with Show when someone was last active off");

        // The next process reads these at its start and runs paused with the switch saved on. A
        // failed case arms nothing: presence-paused is skipped then, and a later case must not
        // start paused.
        if (wrong.length() > 0) {
            save(hide, false);
            out.putString(PREFIX + "detail", wrong.toString());
            return false;
        }
        save(paused, true);
        out.putString(PREFIX + "armed", "Pause and Hide your active status saved on for presence-paused");
        return true;
    }

    /**
     * A start paused by the switch, with Hide your active status saved on: Active stays Active.
     * presence leaves both saved on, and without that this case can't be judged and says so. Puts
     * both back off at the end.
     */
    private static boolean presencePaused(Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Presence presence = Presence.find(loader, out);
        Class<?> utils = requireClass(loader, EXTENSION_CLASS, out);
        Class<?> settings = requireClass(loader, SETTINGS_CLASS, out);
        Class<?> base = requireClass(loader, BASE_SETTINGS_CLASS, out);
        Class<?> pause = requireClass(loader, PAUSE_CLASS, out);
        if (presence == null || utils == null || settings == null || base == null || pause == null) return false;
        utils.getMethod("setContext", Context.class).invoke(null, target);
        Object hide = settings.getField("HIDE_ACTIVE_STATUS").get(null);
        Object paused = base.getField("PAUSED").get(null);
        try {
            String reason = String.valueOf(pause.getMethod("reason").invoke(null));
            Object saved = hide.getClass().getMethod("savedValue").invoke(hide);
            out.putString(PREFIX + "pause", reason);
            if (!"SWITCH".equals(reason) || !Boolean.TRUE.equals(saved)) {
                throw new IllegalStateException("presence-paused needs presence to run first and save Pause and the switch on (pause "
                        + reason + ", switch saved " + saved + ")");
            }
            StringBuilder wrong = new StringBuilder();
            out.putString(PREFIX + "presence.paused", expect(wrong, "paused", presence, presence.write("ACTIVE"), "ACTIVE", "ACTIVE"));
            if (wrong.length() > 0) out.putString(PREFIX + "detail", wrong.toString());
            return wrong.length() == 0;
        } finally {
            save(paused, false);
            save(hide, false);
        }
    }

    /**
     * View DM photos and videos anonymously on Instagram's own receipt handler, called with a new
     * mutation that carries no message and a stand-in for the completion callback, so no request
     * can be built: before the settings are ready, with the switch off, then on. Off, each handler
     * takes its own mutation as far as Instagram's guard and casts the other one away, which ties
     * the handler to the mutation Instagram names. On, only the visual receipt completes, once,
     * with nulls, and the voice receipt that shares its endpoint stays Instagram's. Ends by saving
     * Pause and the switch on for visual-paused.
     */
    private static boolean visual(Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Visual visual = Visual.find(loader, out);
        Class<?> utils = requireClass(loader, EXTENSION_CLASS, out);
        Class<?> settings = requireClass(loader, SETTINGS_CLASS, out);
        Class<?> base = requireClass(loader, BASE_SETTINGS_CLASS, out);
        Class<?> pause = requireClass(loader, PAUSE_CLASS, out);
        if (visual == null || utils == null || settings == null || base == null || pause == null) return false;
        StringBuilder wrong = new StringBuilder();

        boolean readyAtStart = (Boolean) utils.getMethod("settingsReady").invoke(null);
        out.putString(PREFIX + "settingsReadyAtStart", Boolean.toString(readyAtStart));
        if (readyAtStart) note(wrong, "the settings were ready before the case gave the extension its context");
        else out.putString(PREFIX + "visual.notReady", outcome(wrong, "not ready", visual.visual(visual.mutation), "native"));

        utils.getMethod("setContext", Context.class).invoke(null, target);
        String reason = String.valueOf(pause.getMethod("reason").invoke(null));
        out.putString(PREFIX + "pause", reason);
        if (!"NONE".equals(reason)) note(wrong, "this start runs paused (" + reason + ")");
        Object view = settings.getField("VIEW_DM_MEDIA_ANONYMOUSLY").get(null);
        Object paused = base.getField("PAUSED").get(null);

        // As in presence, only a pass arms the paused start. Anything else, a throw included, puts
        // both back off.
        boolean armed = false;
        try {
            save(view, false);
            out.putString(PREFIX + "visual.off", outcome(wrong, "off", visual.visual(visual.mutation), "native"));
            out.putString(PREFIX + "visual.cast", outcome(wrong, "off, visual handler given the voice mutation", visual.visual(visual.voiceMutation), "rejected")
                    + "," + outcome(wrong, "off, voice handler given the visual mutation", visual.voice(visual.mutation), "rejected"));

            save(view, true);
            out.putString(PREFIX + "visual.on", outcome(wrong, "on", visual.visual(visual.mutation), "held"));
            out.putString(PREFIX + "visual.voice", outcome(wrong, "on, voice", visual.voice(visual.voiceMutation), "native"));

            if (wrong.length() > 0) {
                out.putString(PREFIX + "detail", wrong.toString());
                return false;
            }
            save(paused, true);
            armed = true;
            out.putString(PREFIX + "armed", "Pause and View DM photos and videos anonymously saved on for visual-paused");
            return true;
        } finally {
            if (!armed) {
                save(paused, false);
                save(view, false);
            }
        }
    }

    /**
     * A start paused by the switch, with View DM photos and videos anonymously saved on: the visual
     * receipt stays Instagram's. visual leaves both saved on. Puts both back off at the end.
     */
    private static boolean visualPaused(Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Class<?> utils = requireClass(loader, EXTENSION_CLASS, out);
        Class<?> settings = requireClass(loader, SETTINGS_CLASS, out);
        Class<?> base = requireClass(loader, BASE_SETTINGS_CLASS, out);
        Class<?> pause = requireClass(loader, PAUSE_CLASS, out);
        if (utils == null || settings == null || base == null || pause == null) return false;
        utils.getMethod("setContext", Context.class).invoke(null, target);
        Object view = settings.getField("VIEW_DM_MEDIA_ANONYMOUSLY").get(null);
        Object paused = base.getField("PAUSED").get(null);
        // From here on both go back off, whatever happens.
        try {
            Visual visual = Visual.find(loader, out);
            if (visual == null) return false;
            String reason = String.valueOf(pause.getMethod("reason").invoke(null));
            Object saved = view.getClass().getMethod("savedValue").invoke(view);
            out.putString(PREFIX + "pause", reason);
            if (!"SWITCH".equals(reason) || !Boolean.TRUE.equals(saved)) {
                throw new IllegalStateException("visual-paused needs visual to run first and save Pause and the switch on (pause "
                        + reason + ", switch saved " + saved + ")");
            }
            StringBuilder wrong = new StringBuilder();
            out.putString(PREFIX + "visual.paused", outcome(wrong, "paused", visual.visual(visual.mutation), "native"));
            if (wrong.length() > 0) out.putString(PREFIX + "detail", wrong.toString());
            return wrong.length() == 0;
        } finally {
            save(paused, false);
            save(view, false);
        }
    }

    /**
     * Mute notification types on the path every Instagram notify call is sent to, NotificationGroups'
     * stand-in, with test notifications on two of Instagram's own channel ids. With the promo
     * channel muted, its two posts never reach the manager and are logged once, while a post on the
     * other channel arrives as built. With the list emptied, the next promo post arrives in the same
     * process. Then the same with Group notifications on: two kept posts and a muted one give one
     * summary counting 2, each kept post shows as it was built, and the next promo post after the
     * list is emptied joins the group and the count goes to 3, still in the same process. The user
     * is new, so the case makes both channels itself, and it takes its notifications down and the
     * settings back as they were saved at the end.
     */
    private static boolean notifications(Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Class<?> utils = requireClass(loader, EXTENSION_CLASS, out);
        Class<?> settings = requireClass(loader, SETTINGS_CLASS, out);
        Class<?> pause = requireClass(loader, PAUSE_CLASS, out);
        Class<?> groups = requireClass(loader, NOTIFICATION_GROUPS_CLASS, out);
        if (utils == null || settings == null || pause == null || groups == null) return false;
        Method standIn;
        try {
            standIn = groups.getMethod("notify", NotificationManager.class, int.class, Notification.class);
        } catch (NoSuchMethodException absent) {
            missing(out, NOTIFICATION_GROUPS_CLASS + ".notify");
            return false;
        }
        NotificationManager manager = target.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) throw new IllegalStateException("the target can't post notifications in user " + Process.myUid() / 100000);
        utils.getMethod("setContext", Context.class).invoke(null, target);
        StringBuilder wrong = new StringBuilder();
        String reason = String.valueOf(pause.getMethod("reason").invoke(null));
        out.putString(PREFIX + "pause", reason);
        if (!"NONE".equals(reason)) note(wrong, "this start runs paused (" + reason + ")");
        Object muted = settings.getField("MUTED_NOTIFICATION_CHANNELS").get(null);
        Object grouping = settings.getField("GROUP_NOTIFICATIONS").get(null);
        Object byType = settings.getField("GROUP_NOTIFICATIONS_BY_TYPE").get(null);
        Object groupingSaved = grouping.getClass().getMethod("savedValue").invoke(grouping);
        Object byTypeSaved = byType.getClass().getMethod("savedValue").invoke(byType);
        manager.createNotificationChannel(new NotificationChannel(MUTED_CHANNEL, "Product announcements", NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(new NotificationChannel(KEPT_CHANNEL, "Likes", NotificationManager.IMPORTANCE_DEFAULT));
        try {
            save(grouping, false);
            save(muted, MUTED_CHANNEL);
            standIn.invoke(null, manager, MUTED_ID, test(target, MUTED_CHANNEL));
            standIn.invoke(null, manager, MUTED_ID + 1, test(target, MUTED_CHANNEL));
            standIn.invoke(null, manager, KEPT_ID, test(target, KEPT_CHANNEL));
            // The manager takes posts in order, so once the kept one shows, the muted ones would too.
            boolean kept = shows(manager, KEPT_ID);
            out.putString(PREFIX + "notifications.kept", kept ? "shown" : "missing");
            if (!kept) note(wrong, "muted: the post on " + KEPT_CHANNEL + " never showed, so the muted ones' absence proves nothing");
            String mutedSeen = (active(manager, MUTED_ID) ? "shown" : "held") + "," + (active(manager, MUTED_ID + 1) ? "shown" : "held");
            out.putString(PREFIX + "notifications.muted", mutedSeen);
            if (!"held,held".equals(mutedSeen)) note(wrong, "muted: the posts on " + MUTED_CHANNEL + " were " + mutedSeen + ", expected held,held");
            int logged = mutedLines();
            out.putString(PREFIX + "notifications.logged", Integer.toString(logged));
            if (logged != 1) note(wrong, "muted: logged " + logged + " times, expected once");

            save(muted, "");
            standIn.invoke(null, manager, UNMUTED_ID, test(target, MUTED_CHANNEL));
            boolean back = shows(manager, UNMUTED_ID);
            out.putString(PREFIX + "notifications.unmuted", back ? "shown" : "missing");
            if (!back) note(wrong, "unmuted: the next post on " + MUTED_CHANNEL + " never showed");

            manager.cancelAll();
            save(byType, false);
            save(grouping, true);
            save(muted, MUTED_CHANNEL);
            Notification first = test(target, KEPT_CHANNEL, "First like", "Someone liked your photo.");
            Notification second = test(target, KEPT_CHANNEL, "Second like", "Someone else liked your photo.");
            standIn.invoke(null, manager, GROUPED_ID, first);
            standIn.invoke(null, manager, GROUPED_MUTED_ID, test(target, MUTED_CHANNEL, "Promo", "Try something new."));
            standIn.invoke(null, manager, GROUPED_ID + 1, second);
            List<Notification> summaries = summaries(manager, 2);
            String counted = counted(summaries);
            out.putString(PREFIX + "notifications.grouped.summary", counted);
            if (!"2".equals(counted)) note(wrong, "grouped: the summaries counted " + counted + ", expected one counting 2");
            String keptGrouped = asPosted(manager, GROUPED_ID, first) + "," + asPosted(manager, GROUPED_ID + 1, second);
            out.putString(PREFIX + "notifications.grouped.kept", keptGrouped);
            if (!"same,same".equals(keptGrouped)) note(wrong, "grouped: the kept posts showed as " + keptGrouped + ", expected same,same");
            String heldGrouped = active(manager, GROUPED_MUTED_ID) ? "shown" : "held";
            out.putString(PREFIX + "notifications.grouped.muted", heldGrouped);
            if (!"held".equals(heldGrouped)) note(wrong, "grouped: the post on " + MUTED_CHANNEL + " was shown, expected held");

            save(muted, "");
            Notification third = test(target, MUTED_CHANNEL, "Promo", "Try something else.");
            standIn.invoke(null, manager, GROUPED_UNMUTED_ID, third);
            String joined = shows(manager, GROUPED_UNMUTED_ID) ? asPosted(manager, GROUPED_UNMUTED_ID, third) : "missing";
            out.putString(PREFIX + "notifications.grouped.unmuted", joined);
            if (!"same".equals(joined)) note(wrong, "grouped unmuted: the next post on " + MUTED_CHANNEL + " showed as " + joined + ", expected same");
            String recounted = counted(summaries(manager, 3));
            out.putString(PREFIX + "notifications.grouped.after", recounted);
            if (!"3".equals(recounted)) note(wrong, "grouped unmuted: the summaries counted " + recounted + ", expected one counting 3");
        } finally {
            // Each step on its own, so one that fails neither hides the case's own error nor skips the rest.
            try { save(muted, ""); } catch (Exception e) { note(wrong, "cleanup: " + e); }
            try { save(grouping, groupingSaved); } catch (Exception e) { note(wrong, "cleanup: " + e); }
            try { save(byType, byTypeSaved); } catch (Exception e) { note(wrong, "cleanup: " + e); }
            try { manager.cancelAll(); } catch (RuntimeException e) { note(wrong, "cleanup: " + e); }
            try { manager.deleteNotificationChannel(MUTED_CHANNEL); } catch (RuntimeException e) { note(wrong, "cleanup: " + e); }
            try { manager.deleteNotificationChannel(KEPT_CHANNEL); } catch (RuntimeException e) { note(wrong, "cleanup: " + e); }
        }
        if (wrong.length() > 0) out.putString(PREFIX + "detail", wrong.toString());
        return wrong.length() == 0;
    }

    /**
     * Sanitize sharing links on the stand-in for Intent.createChooser with the IntentSender the
     * share sheet tells Instagram the picked app through, sharing SHARED_TEXT: before the settings
     * are ready, with the switch off, then on. Only on does the reel link lose its keys, and the
     * other site's link keeps its own. Each time the chooser has to carry the share and the very
     * sender it was given. Nothing is started or sent, and the switch goes back as it was saved.
     */
    private static boolean share(Context target, Bundle out) throws Exception {
        ClassLoader loader = target.getClassLoader();
        Class<?> utils = requireClass(loader, EXTENSION_CLASS, out);
        Class<?> settings = requireClass(loader, SETTINGS_CLASS, out);
        Class<?> pause = requireClass(loader, PAUSE_CLASS, out);
        Class<?> cleaner = requireClass(loader, LINK_CLEANER_CLASS, out);
        if (utils == null || settings == null || pause == null || cleaner == null) return false;
        Method standIn;
        try {
            standIn = cleaner.getMethod("createChooser", Intent.class, CharSequence.class, IntentSender.class);
        } catch (NoSuchMethodException absent) {
            missing(out, LINK_CLEANER_CLASS + ".createChooser");
            return false;
        }
        StringBuilder wrong = new StringBuilder();
        PendingIntent picked = PendingIntent.getBroadcast(target, 0, new Intent(PICKED_ACTION).setPackage(target.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        try {
            IntentSender sender = picked.getIntentSender();
            String senderKey = senderKey(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain"), "Share", sender), sender);
            out.putString(PREFIX + "share.senderKey", senderKey == null ? "none" : senderKey);
            if (senderKey == null) {
                out.putString(PREFIX + "detail", "Android's own chooser keeps no sender, so the stand-in's can't be judged");
                return false;
            }
            boolean readyAtStart = (Boolean) utils.getMethod("settingsReady").invoke(null);
            out.putString(PREFIX + "settingsReadyAtStart", Boolean.toString(readyAtStart));
            if (readyAtStart) note(wrong, "the settings were ready before the case gave the extension its context");
            else out.putString(PREFIX + "share.notReady", outcome(wrong, "not ready", chosen(standIn, sender, senderKey), "kept"));

            utils.getMethod("setContext", Context.class).invoke(null, target);
            String reason = String.valueOf(pause.getMethod("reason").invoke(null));
            out.putString(PREFIX + "pause", reason);
            if (!"NONE".equals(reason)) note(wrong, "this start runs paused (" + reason + ")");
            Object domain = settings.getField("SHARING_DOMAIN").get(null);
            Object domainSaved = domain.getClass().getMethod("savedValue").invoke(domain);
            // A Sharing domain would move the reel link too, and the user is new, so it's blank.
            if (!"".equals(domainSaved)) note(wrong, "the Sharing domain is set to " + domainSaved);
            Object sanitize = settings.getField("SANITIZE_SHARING_LINKS").get(null);
            Object saved = sanitize.getClass().getMethod("savedValue").invoke(sanitize);
            try {
                save(sanitize, false);
                out.putString(PREFIX + "share.off", outcome(wrong, "off", chosen(standIn, sender, senderKey), "kept"));
                save(sanitize, true);
                out.putString(PREFIX + "share.on", outcome(wrong, "on", chosen(standIn, sender, senderKey), "cleaned"));
            } finally {
                try { save(sanitize, saved); } catch (Exception e) { note(wrong, "cleanup: " + e); }
            }
        } finally {
            picked.cancel();
        }
        if (wrong.length() > 0) out.putString(PREFIX + "detail", wrong.toString());
        return wrong.length() == 0;
    }

    /**
     * The extra Android's own chooser keeps the sender in, or null when it keeps none. Android 17
     * (API 37) keeps it in EXTRA_CHOOSER_RESULT_INTENT_SENDER, not EXTRA_CHOSEN_COMPONENT_INTENT_SENDER,
     * so the key is read off the platform's chooser rather than named here.
     */
    private static String senderKey(Intent own, IntentSender sender) {
        Bundle extras = own.getExtras();
        if (extras == null) return null;
        for (String key : extras.keySet()) {
            if (sender.equals(extras.get(key))) return key;
        }
        return null;
    }

    /**
     * What the stand-in made of a new share of SHARED_TEXT: "kept" or "cleaned" when it answered a
     * chooser holding that share, with the text as given or as cleaned, and the sender it was given
     * where Android's own chooser keeps it. Anything else is named.
     */
    private static String chosen(Method standIn, IntentSender sender, String senderKey) throws Exception {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SHARED_TEXT);
        Object answer = standIn.invoke(null, share, "Share", sender);
        if (!(answer instanceof Intent) || !Intent.ACTION_CHOOSER.equals(((Intent) answer).getAction())) return "no chooser";
        Intent chooser = (Intent) answer;
        Parcelable inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        Parcelable told = chooser.getParcelableExtra(senderKey);
        if (!(inner instanceof Intent)) return "no share in the chooser";
        if (!sender.equals(told)) {
            Bundle extras = chooser.getExtras();
            return "the sender was lost (" + (told == null ? "none" : told.getClass().getName()) + "; extras "
                    + (extras == null ? "none" : String.join(",", extras.keySet())) + ")";
        }
        String text = String.valueOf(((Intent) inner).getCharSequenceExtra(Intent.EXTRA_TEXT));
        if (SHARED_TEXT.equals(text)) return "kept";
        if (CLEANED_TEXT.equals(text)) return "cleaned";
        return "changed to " + text;
    }

    private static Notification test(Context target, String channel) {
        return new Notification.Builder(target, channel).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("HushGram native runner").build();
    }

    private static Notification test(Context target, String channel, String title, String text) {
        return new Notification.Builder(target, channel).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(text).build();
    }

    /** HushGram's summaries in the shade once one counts [number], or what shows after five seconds. */
    private static List<Notification> summaries(NotificationManager manager, int number) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            List<Notification> found = new ArrayList<>();
            boolean counts = false;
            for (StatusBarNotification shown : manager.getActiveNotifications()) {
                if (!SUMMARY_TAG.equals(shown.getTag())) continue;
                found.add(shown.getNotification());
                counts |= shown.getNotification().number == number;
            }
            if (counts || System.currentTimeMillis() >= deadline) return found;
            Thread.sleep(100);
        }
    }

    /** Each summary's count, with a comma between, and its group when that isn't the one group. "none" for no summary. */
    private static String counted(List<Notification> summaries) {
        if (summaries.isEmpty()) return "none";
        List<String> each = new ArrayList<>();
        for (Notification summary : summaries) {
            each.add(summary.number + (ONE_GROUP.equals(summary.getGroup()) ? "" : " in " + summary.getGroup()));
        }
        return String.join(",", each);
    }

    /**
     * "same" when the notification with this id shows in the one group with the title and text it
     * was built with, otherwise what differs.
     */
    private static String asPosted(NotificationManager manager, int id, Notification posted) {
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            if (shown.getId() != id || shown.getTag() != null) continue;
            Notification active = shown.getNotification();
            if (!ONE_GROUP.equals(active.getGroup())) return "outside the group (" + active.getGroup() + ")";
            String title = String.valueOf(active.extras.getCharSequence(Notification.EXTRA_TITLE));
            String text = String.valueOf(active.extras.getCharSequence(Notification.EXTRA_TEXT));
            String postedTitle = String.valueOf(posted.extras.getCharSequence(Notification.EXTRA_TITLE));
            String postedText = String.valueOf(posted.extras.getCharSequence(Notification.EXTRA_TEXT));
            if (postedTitle.equals(title) && postedText.equals(text)) return "same";
            return "changed to " + title + " / " + text;
        }
        return "missing";
    }

    /** Whether the notification with this id shows within five seconds. The manager posts later than notify returns. */
    private static boolean shows(NotificationManager manager, int id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!active(manager, id)) {
            if (System.currentTimeMillis() >= deadline) return false;
            Thread.sleep(100);
        }
        return true;
    }

    private static boolean active(NotificationManager manager, int id) {
        for (StatusBarNotification shown : manager.getActiveNotifications()) {
            if (shown.getId() == id) return true;
        }
        return false;
    }

    /**
     * How many of this process's log lines say the muted channel kept a notification out, read
     * from logcat, which lets an app read its own lines. logd writes a little after Log returns.
     */
    private static int mutedLines() throws Exception {
        String marker = "Notification groups: muted " + MUTED_CHANNEL + ",";
        long deadline = System.currentTimeMillis() + 2_000;
        while (true) {
            int count = 0;
            java.lang.Process logcat = new ProcessBuilder("logcat", "-d", "-v", "raw", "--pid=" + Process.myPid())
                    .redirectErrorStream(true).start();
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = lines.readLine()) != null; ) {
                    if (line.contains(marker)) count++;
                }
            }
            logcat.waitFor();
            if (count > 0 || System.currentTimeMillis() >= deadline) return count;
            Thread.sleep(200);
        }
    }

    /**
     * One declared build's receipt handlers, mutations and completion interface. The handlers have
     * no constructor of their own: Instagram's provider allocates one and stores the account's
     * session in it, which a signed-out user doesn't have. The runner allocates them bare instead,
     * and every call here ends before the handler reads that field.
     */
    static final class Visual {
        Object visualHandler;
        Object voiceHandler;
        Method handle;
        Method voiceHandle;
        Constructor<?> mutation;
        Constructor<?> voiceMutation;
        Constructor<?> extras;
        Class<?> callback;
        String complete;

        /**
         * The first name set Instagram's own code agrees with, or null with what was tried. A
         * handler that can't be allocated is the runner's failure, not a missing hook, so it throws.
         */
        static Visual find(ClassLoader loader, Bundle out) throws ReflectiveOperationException {
            StringBuilder tried = new StringBuilder();
            for (String[] names : VISUAL_NAMES) {
                Visual found;
                try {
                    found = resolve(loader, names);
                } catch (ClassNotFoundException | NoSuchMethodException | IllegalStateException mismatch) {
                    if (tried.length() > 0) tried.append("; ");
                    tried.append(names[0]).append(": ").append(mismatch.getClass().getSimpleName()).append(' ').append(mismatch.getMessage());
                    continue;
                }
                found.visualHandler = allocate(found.handle.getDeclaringClass());
                found.voiceHandler = allocate(found.voiceHandle.getDeclaringClass());
                out.putString(PREFIX + "visual.build", names[0]);
                return found;
            }
            missing(out, "view-once receipt handler (" + tried + ")");
            return null;
        }

        /**
         * Shapes first, without running any of the classes' code: both handlers take (extras,
         * callback, base), each mutation is a base, the callback is an interface with one void
         * (error, String) completion. Only then are the mutations built, and Instagram's own name
         * selector has to name them as the visual and voice receipts.
         */
        private static Visual resolve(ClassLoader loader, String[] names) throws ReflectiveOperationException {
            Class<?> handler = Class.forName(names[1], false, loader);
            Class<?> voiceHandler = Class.forName(names[2], false, loader);
            Class<?> mutation = Class.forName(names[4], false, loader);
            Class<?> voiceMutation = Class.forName(names[5], false, loader);
            Class<?> base = Class.forName(names[6], false, loader);
            Class<?> extras = Class.forName(names[7], false, loader);
            Class<?> callback = Class.forName(names[8], false, loader);
            Visual found = new Visual();
            found.handle = handler.getMethod(names[3], extras, callback, base);
            found.voiceHandle = voiceHandler.getMethod(names[3], extras, callback, base);
            if (found.handle.getReturnType() != void.class || found.voiceHandle.getReturnType() != void.class) {
                throw new IllegalStateException("a handler method returns a value");
            }
            if (!base.isAssignableFrom(mutation) || !base.isAssignableFrom(voiceMutation)) throw new IllegalStateException("a mutation is not a mutation");
            if (!callback.isInterface()) throw new IllegalStateException(callback.getName() + " is not an interface");
            for (Method method : callback.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!Modifier.isAbstract(method.getModifiers()) || method.getReturnType() != void.class ||
                        parameters.length != 2 || parameters[1] != String.class) continue;
                if (found.complete != null) throw new IllegalStateException("two completions on " + callback.getName());
                found.complete = method.getName();
            }
            if (found.complete == null) throw new IllegalStateException("no completion on " + callback.getName());
            found.mutation = mutation.getConstructor();
            found.voiceMutation = voiceMutation.getConstructor();
            found.extras = extras.getConstructor();
            Method name = base.getMethod("A04");
            Object visualName = name.invoke(found.mutation.newInstance());
            Object voiceName = name.invoke(found.voiceMutation.newInstance());
            if (!VISUAL_MUTATION.equals(visualName) || !VOICE_MUTATION.equals(voiceName)) {
                throw new IllegalStateException("Instagram names the mutations " + visualName + " and " + voiceName);
            }
            found.callback = callback;
            return found;
        }

        String visual(Constructor<?> given) throws Exception {
            return call(handle, visualHandler, given);
        }

        String voice(Constructor<?> given) throws Exception {
            return call(voiceHandle, voiceHandler, given);
        }

        /**
         * What one call with a new mutation and new extras did: "held" when it completed once with
         * nulls and returned, "native" when the handler's own guard threw before any request was
         * built, "rejected" when the handler's cast refused the mutation. Anything else is named.
         */
        private String call(Method method, Object handler, Constructor<?> given) throws Exception {
            final List<String> completions = new ArrayList<>();
            // An anonymous class: javac builds against android.jar, which has no lambda factory.
            InvocationHandler recorder = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method called, Object[] arguments) {
                    switch (called.getName()) {
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == arguments[0];
                        case "toString":
                            return "native runner callback";
                        default:
                            completions.add(called.getName() + (arguments == null ? "()" : Arrays.toString(arguments)));
                            return null;
                    }
                }
            };
            Object stand = Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[]{callback}, recorder);
            Throwable thrown = null;
            try {
                method.invoke(handler, extras.newInstance(), stand, given.newInstance());
            } catch (InvocationTargetException failure) {
                thrown = failure.getCause();
            }
            String calls = String.join(";", completions);
            if (thrown == null) {
                boolean once = completions.size() == 1 && completions.get(0).equals(complete + "[null, null]");
                return once ? "held" : "returned after " + (calls.isEmpty() ? "no completion" : calls);
            }
            StackTraceElement[] frames = thrown.getStackTrace();
            boolean own = frames.length > 0 && frames[0].getClassName().equals(handler.getClass().getName());
            if (own && completions.isEmpty()) {
                if (thrown instanceof IllegalStateException && NATIVE_GUARD.equals(thrown.getMessage())) return "native";
                if (thrown instanceof ClassCastException) return "rejected";
            }
            return "threw " + thrown.getClass().getName() + (own ? "" : " outside the handler") + (calls.isEmpty() ? "" : " after " + calls);
        }
    }

    /** An object of the class with no constructor run, as Instagram's provider makes the handler. */
    private static Object allocate(Class<?> type) throws ReflectiveOperationException {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field instance = unsafeType.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        return unsafeType.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type);
    }

    /** The outcome, with a mismatch noted in wrong. */
    private static String outcome(StringBuilder wrong, String step, String seen, String expected) {
        if (!expected.equals(seen)) note(wrong, step + ": " + seen + ", expected " + expected);
        return seen;
    }

    /** The write request's two constructors and status field, and the status constants. */
    static final class Presence {
        final Map<String, Object> statuses = new LinkedHashMap<>();
        Constructor<?> writer;
        Constructor<?> serializer;
        Field status;
        Object appState;
        Object pollingMode;
        Object requestType;

        static Presence find(ClassLoader loader, Bundle out) throws Exception {
            Class<?> request = requireClass(loader, PRESENCE_MODEL + "PresenceWriteRequest", out);
            Class<?> status = requireClass(loader, PRESENCE_MODEL + "PresenceStatus", out);
            Class<?> appState = requireClass(loader, PRESENCE_MODEL + "AppState", out);
            Class<?> polling = requireClass(loader, PRESENCE_MODEL + "PresencePollingMode", out);
            Class<?> type = requireClass(loader, PRESENCE_MODEL + "PresenceWriteRequestType", out);
            if (request == null || status == null || appState == null || polling == null || type == null) return null;
            Presence found = new Presence();
            Map<String, Object> constants = constants(status);
            for (String name : PRESENCE_STATUSES) {
                if (!constants.containsKey(name)) {
                    missing(out, status.getName() + "." + name);
                    return null;
                }
                found.statuses.put(name, constants.get(name));
            }
            Class<?>[] parameters = {appState, polling, status, type, Long.class, String.class};
            Class<?>[] masked = Arrays.copyOf(parameters, parameters.length + 1);
            masked[parameters.length] = int.class;
            try {
                found.writer = request.getDeclaredConstructor(parameters);
                found.serializer = request.getDeclaredConstructor(masked);
            } catch (NoSuchMethodException absent) {
                missing(out, request.getName() + ".<init>");
                return null;
            }
            for (Field field : request.getDeclaredFields()) {
                if (field.getType() == status && !Modifier.isStatic(field.getModifiers())) {
                    if (found.status != null) {
                        missing(out, request.getName() + " one status field");
                        return null;
                    }
                    found.status = field;
                }
            }
            if (found.status == null) {
                missing(out, request.getName() + " status field");
                return null;
            }
            found.writer.setAccessible(true);
            found.serializer.setAccessible(true);
            found.status.setAccessible(true);
            found.appState = first(appState);
            found.pollingMode = first(polling);
            found.requestType = first(type);
            return found;
        }

        /** The status a request built by Instagram's writer path holds. */
        Object write(String status) throws Exception {
            Object request = writer.newInstance(appState, pollingMode, statuses.get(status), requestType, 0L, "hushgram-native-runner");
            return this.status.get(request);
        }

        /**
         * The status a request built by Kotlin's serializer constructor holds. Its mask has a bit
         * for each of the six fields, set for a value given and clear for null, so 63 gives all.
         */
        Object serialized(String status) throws Exception {
            Object request = serializer.newInstance(appState, pollingMode, statuses.get(status), requestType, 0L, "hushgram-native-runner", 63);
            return this.status.get(request);
        }
    }

    /**
     * "GIVEN>STORED" for one request. A stored status that isn't the very constant expected, the
     * same object of the same enum, goes in wrong.
     */
    private static String expect(StringBuilder wrong, String step, Presence presence, Object stored, String given, String expected) {
        String storedName = stored instanceof Enum ? ((Enum<?>) stored).name() : String.valueOf(stored);
        if (stored != presence.statuses.get(expected)) note(wrong, step + ": " + given + " was stored as " + storedName + ", expected " + expected);
        return given + ">" + storedName;
    }

    private static void note(StringBuilder wrong, String problem) {
        if (wrong.length() > 0) wrong.append("; ");
        wrong.append(problem);
    }

    private static void save(Object setting, Object value) throws Exception {
        Object saved = setting.getClass().getMethod("save", Object.class).invoke(setting, value);
        if (!Boolean.TRUE.equals(saved)) throw new IllegalStateException("Could not save " + setting + " as " + value);
    }

    /** Every constant of an enum by name, read from its static fields, which works without values(). */
    private static Map<String, Object> constants(Class<?> type) throws IllegalAccessException {
        Map<String, Object> found = new LinkedHashMap<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != type || !Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof Enum) found.put(((Enum<?>) value).name(), value);
        }
        return found;
    }

    /** The enum's constant with ordinal 0. */
    private static Object first(Class<?> type) throws IllegalAccessException {
        for (Object value : constants(type).values()) {
            if (((Enum<?>) value).ordinal() == 0) return value;
        }
        throw new IllegalStateException(type.getName() + " has no constant");
    }

    private static void missing(Bundle out, String name) {
        String key = PREFIX + "missingHook";
        String earlier = out.getString(key);
        out.putString(key, earlier == null ? name : earlier + "," + name);
    }

    /**
     * Thrown by a case that can only be judged against a signed-in session. The runner's user is
     * always signed out, so such a case reports what it needs instead of passing or failing.
     */
    static final class NeedsSession extends Exception {
        NeedsSession(String prerequisite) {
            super(prerequisite);
        }
    }

    /** The class from the target's loader, or null with its name added to the missing hooks. */
    static Class<?> requireClass(ClassLoader loader, String name, Bundle out) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException absent) {
            missing(out, name);
            return null;
        }
    }

    private static void bind(Context target, Bundle out) throws Exception {
        String packageName = target.getPackageName();
        PackageInfo info = target.getPackageManager()
                .getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
        out.putString(PREFIX + "package", packageName);
        out.putString(PREFIX + "versionName", info.versionName);
        out.putString(PREFIX + "versionCode", Long.toString(info.getLongVersionCode()));
        Signature[] signers = info.signingInfo == null ? new Signature[0]
                : info.signingInfo.getApkContentsSigners();
        String[] digests = new String[signers.length];
        for (int i = 0; i < signers.length; i++) {
            digests[i] = hex(MessageDigest.getInstance("SHA-256").digest(signers[i].toByteArray()));
        }
        Arrays.sort(digests);
        out.putString(PREFIX + "signerSha256", String.join(",", digests));
        out.putString(PREFIX + "baseApkSha256", sha256(target.getApplicationInfo().sourceDir));
        out.putString(PREFIX + "user", Integer.toString(Process.myUid() / 100000));
        out.putString(PREFIX + "process", Application.getProcessName());
        ConnectivityManager connectivity = target.getSystemService(ConnectivityManager.class);
        Network active = connectivity == null ? null : connectivity.getActiveNetwork();
        out.putString(PREFIX + "network", active == null ? "none" : "up");
    }

    private static String sha256(String path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = new FileInputStream(path)) {
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }
}
