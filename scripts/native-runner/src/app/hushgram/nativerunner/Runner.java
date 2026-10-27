/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.nativerunner;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Process;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
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
    // Each declared 453.0.0.55.79 build's view-once receipt names, found with DexProbe from the handler's
    // endpoint and raven_media strings: the build, the visual and voice handlers, their shared
    // method, the visual and voice mutations, the mutation base, its analytics extras and the
    // completion interface. A set is used only once Instagram's own code agrees with it.
    private static final String[][] VISUAL_NAMES = {
            {"385914283", "X.04wm", "X.04wp", "Gxe", "X.0KN3", "X.0KMW", "X.0999", "X.08hM", "X.0Wom"},
            {"385914282", "X.05eb", "X.05ef", "Gxn", "X.0J1i", "X.0J1O", "X.0682", "X.08wE", "X.0VAA"},
            {"385914284", "X.04xq", "X.04xt", "Gxt", "X.0KP3", "X.0KOW", "X.0974", "X.08iU", "X.0Wa2"},
            {"385914285", "X.04wm", "X.04wp", "Gxd", "X.0KM6", "X.0KM1", "X.0997", "X.08hM", "X.0Wnn"},
    };
    private static final String VISUAL_MUTATION = "send_visual_item_seen_marker";
    private static final String VOICE_MUTATION = "send_voice_item_seen_marker";
    // The handler's own guard on a mutation without its message, thrown before any request exists.
    private static final String NATIVE_GUARD = "Required value was null.";

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

    private static void save(Object setting, boolean value) throws Exception {
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
