/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.settings.SettingsStatus;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Record calls (#732): a Record button on Instagram's call screen that saves the call's sound, both
 * sides, as an audio file where song saves go.
 *
 * <p>Where the sound comes from: Instagram's calls run in its native call engine, which plays and
 * records through AAudio below Java, so Android's AudioRecord and AudioTrack never see it. The
 * engine's audio API takes sinks instead: {@code addSink(person's id, stream type, sink)} gives the
 * sink each decoded frame of that person's stream. The patch hands the extension the audio API as
 * the engine makes it ({@link #audioApi}) and each state of the call ({@link #callModel}), whose
 * participants name the streams, and makes {@link CallAudioSink} an engine sink. A recording adds
 * one sink for you and one for each other person, adds anyone who joins later, mixes the frames at
 * {@link #RATE} mono and encodes AAC into an .m4a.
 *
 * <p>Time in the file is the engine's: a frame is 10 ms of sound, so the file grows as frames come
 * and holds no stretch where none did. Once no frame has come for {@link #QUIET_MS}, the call is
 * over (or the engine never took the sinks), and the recording saves what it has, or says nothing
 * was recorded.
 *
 * <p>The button is a pill over the call screen's own views: tap to start or stop, drag to move it.
 * It goes while the screen is paused, as it is in picture in picture, and comes back with it; the
 * recording goes on either way.
 */
public final class CallRecorder {
    private static final String SOURCE = "CallRecorder";
    /** Instagram's call screen, which the pill goes on. */
    static final String CALL_SCREEN = "com.instagram.rtc.activity.RtcCallActivity";
    /** The engine's stream type for a person's ordinary sound: its StreamType counts Unknown 0, Default 1, ScreenShare 2. */
    static final int DEFAULT_STREAM = 1;
    /** The file's sample rate, mono. */
    static final int RATE = 48_000;
    /** What a person's queue holds before it's mixed: 60 ms, so a frame a little late doesn't gap. */
    static final int PRIME = RATE * 60 / 1000;
    /** A queue past half a second is running behind the mix, and is cut back to {@link #KEEP}. */
    static final int BACKLOG = RATE / 2;
    static final int KEEP = RATE / 10;
    /** How long without a frame from anyone ends the recording. */
    static final long QUIET_MS = 5_000L;
    private static final long MIX_MS = 20L;
    private static final long LOOK_MS = 1_000L;
    private static final int BIT_RATE = 64_000;
    private static final long ENCODER_WAIT_US = 10_000L;
    /** A scratch file older than this is a recording the app didn't live to save. */
    private static final long LEFTOVER_MS = 6L * 60L * 60L * 1000L;
    private static final char DOT = (char) 0x25CF;
    private static final int IDLE_COLOR = 0xE61C1C1E;
    private static final int RECORDING_COLOR = 0xE6C62828;
    private static final int DOT_COLOR = 0xFFFF5449;
    /** The pill's tag in the decor, so a resume finds the one it put there. */
    private static final Object PILL = new Object();

    private static final Call METADATA = new Call("getMetadata");
    private static final Call AUDIO_DATA = new Call("getAudioData");
    private static final Call SAMPLE_RATE = new Call("getSampleRate");
    private static final Call CHANNELS = new Call("getNumberOfChannels");
    private static final Call SAMPLES = new Call("getNumberOfSamples");
    private static final Call MUTED = new Call("getIsMuted");
    private static final Call SHORTS = new Call("getAudioData", int.class);

    /** The engine's audio API, the latest made. Weak, so a call's engine goes when Instagram lets it go. */
    private static volatile WeakReference<Object> engineAudio = new WeakReference<>(null);
    /** The latest state of a call the engine made. */
    private static volatile Object latestModel;
    private static boolean registered;
    /** The recording going on, on the main thread only. */
    private static Recording recording;
    private static WeakReference<TextView> shown = new WeakReference<>(null);
    /** Where the pill was dragged to, kept across the call screen's instances. */
    private static float movedX, movedY;
    private static Handler main;

    private CallRecorder() {
    }

    /** Called as the call engine makes its audio API. Never throws. */
    public static void audioApi(Object api) {
        HookStatus.invoked(FamilyNames.CALL_RECORDER);
        if (api != null) engineAudio = new WeakReference<>(api);
    }

    /** Called as the call engine makes each state of a call. Never throws. */
    public static void callModel(Object model) {
        HookStatus.invoked(FamilyNames.CALL_RECORDER);
        if (model != null) latestModel = model;
    }

    /** Called as the application starts. Does nothing in a build without Record calls. */
    public static void install(Context context) {
        try {
            if (registered || !(context instanceof Application) || !SettingsStatus.callRecorder()) return;
            registered = true;
            offAfterACrash(context);
            ((Application) context).registerActivityLifecycleCallbacks(new Watcher());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CALL_RECORDER, "start", failure);
        }
    }

    // The crash guard (#831).

    /**
     * Left in the app's own files while the call engine takes a recording's sinks, and taken away
     * once {@link #MARK_MS} have passed with Instagram still running, or the recording ends. On some
     * phones the engine closes Instagram as it takes them, and then this is still there when the app
     * starts again.
     */
    static final String STARTING_MARK = "hushgram-call-recorder-starting";
    /** How long after the engine takes a sink a crash still counts as the sink's. */
    static final long MARK_MS = 3_000L;
    /** How long after the app starts the note about it waits, so the first screen is up. */
    private static final long NOTE_MS = 2_500L;

    /**
     * Turns Record calls off when the last recording's start closed Instagram, so the next tap on
     * Record doesn't close it again, and says so. Answers whether it turned the switch off. Leaves
     * the mark for the next start while the settings aren't read yet. Never throws.
     */
    static boolean offAfterACrash(Context context) {
        try {
            File mark = startingMark(context);
            if (mark == null || !mark.exists() || !Utils.settingsReady()) return false;
            if (!mark.delete()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the start mark stayed behind");
            }
            HookStatus.counted(FamilyNames.CALL_RECORDER, "closed Instagram");
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "Instagram closed while the call engine took the recording's sinks", null);
            if (!Settings.CALL_RECORDER.savedValue()) return false;
            Settings.CALL_RECORDER.save(false);
            HookStatus.counted(FamilyNames.CALL_RECORDER, "turned off after a crash");
            main().postDelayed(() -> Utils.showToastLong(L10n.t("Record calls closed Instagram, so it's off now.")), NOTE_MS);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CALL_RECORDER, "crash guard", failure);
            return false;
        }
    }

    @Nullable
    static File startingMark(Context context) {
        File folder = context == null ? null : context.getNoBackupFilesDir();
        return folder == null ? null : new File(folder, STARTING_MARK);
    }

    /** Puts the mark down while [starting], and takes it away otherwise. Never throws. */
    static void markStarting(Context context, boolean starting) {
        try {
            File mark = startingMark(context);
            if (mark == null) return;
            if (starting ? !mark.exists() && !mark.createNewFile() : mark.exists() && !mark.delete()) {
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the start mark couldn't change");
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CALL_RECORDER, "start mark", failure);
        }
    }

    /** The switch, off until the settings are read and while HushGram is paused. */
    static boolean on() {
        return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.CALL_RECORDER.get();
    }

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    // The pill.

    private static boolean callScreen(Activity activity) {
        return activity != null && CALL_SCREEN.equals(activity.getClass().getName());
    }

    private static void resumed(Activity activity) {
        boolean wanted = recording != null || on();
        TextView pill = pill(activity, wanted);
        if (pill == null) return;
        if (!wanted) {
            ((ViewGroup) pill.getParent()).removeView(pill);
            return;
        }
        pill.setVisibility(View.VISIBLE);
        shown = new WeakReference<>(pill);
        paint(pill);
        place(pill, true);
    }

    private static void paused(Activity activity) {
        TextView pill = pill(activity, false);
        if (pill != null) pill.setVisibility(View.GONE);
    }

    private static void destroyed(Activity activity) {
        TextView pill = pill(activity, false);
        if (pill != null && pill.getParent() instanceof ViewGroup) ((ViewGroup) pill.getParent()).removeView(pill);
        if (shown.get() == pill) shown = new WeakReference<>(null);
    }

    /** The pill on [activity]'s decor, made there when [make] and it isn't yet. */
    @Nullable
    private static TextView pill(Activity activity, boolean make) {
        Window window = activity.getWindow();
        View decor = window == null ? null : window.peekDecorView();
        if (!(decor instanceof FrameLayout)) return null;
        FrameLayout frame = (FrameLayout) decor;
        for (int index = 0; index < frame.getChildCount(); index++) {
            View child = frame.getChildAt(index);
            if (child instanceof TextView && child.getTag() == PILL) return (TextView) child;
        }
        if (!make) return null;
        TextView pill = new TextView(activity);
        pill.setTag(PILL);
        pill.setTextColor(0xFFFFFFFF);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        int across = dp(pill, 16);
        int down = dp(pill, 10);
        pill.setPadding(across, down, across, down);
        pill.setMinHeight(dp(pill, 44));
        pill.setElevation(dp(pill, 8));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(pill, 22));
        background.setColor(IDLE_COLOR);
        pill.setBackground(background);
        pill.setOnClickListener(view -> toggle(view.getContext()));
        pill.setOnTouchListener(new Drag());
        frame.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        return pill;
    }

    /** Below the status bar, where it was dragged to. Waits a frame for the insets when it isn't laid out yet. */
    private static void place(TextView pill, boolean again) {
        WindowInsets insets = pill.getRootWindowInsets();
        if (insets == null && again) {
            pill.post(() -> place(pill, false));
            return;
        }
        int bar = insets == null ? 0 : insets.getSystemWindowInsetTop();
        if (pill.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) pill.getLayoutParams();
            int top = bar + dp(pill, 72);
            if (params.topMargin != top) {
                params.topMargin = top;
                pill.setLayoutParams(params);
            }
        }
        pill.setTranslationX(movedX);
        pill.setTranslationY(movedY);
    }

    private static void paint(TextView pill) {
        Recording now = recording;
        if (pill.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) pill.getBackground()).setColor(now == null ? IDLE_COLOR : RECORDING_COLOR);
        }
        if (now == null) {
            SpannableString label = new SpannableString(DOT + " " + L10n.t("Record"));
            label.setSpan(new ForegroundColorSpan(DOT_COLOR), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pill.setText(label);
            pill.setContentDescription(L10n.t("Record this call"));
        } else {
            pill.setText(DOT + " " + clock(now.recordedMs()));
            pill.setContentDescription(L10n.t("Stop recording"));
        }
    }

    private static void refresh() {
        TextView pill = shown.get();
        if (pill != null) paint(pill);
    }

    private static final Runnable CLOCK = new Runnable() {
        @Override
        public void run() {
            if (recording == null) return;
            refresh();
            main().postDelayed(this, LOOK_MS);
        }
    };

    /** A length as the call screen shows one: 1:05, or 1:02:05 past an hour. */
    static String clock(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        long hours = seconds / 3600L;
        long minutes = seconds / 60L % 60L;
        long rest = seconds % 60L;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    /** A drag moves the pill; a touch that didn't move is a tap. */
    private static final class Drag implements View.OnTouchListener {
        private float downX, downY, startX, startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = view.getTranslationX();
                    startY = view.getTranslationY();
                    dragging = false;
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(view.getContext()).getScaledTouchSlop()) {
                        dragging = true;
                        view.setPressed(false);
                    }
                    if (dragging) {
                        movedX = within(startX + dx, view, true);
                        movedY = within(startY + dy, view, false);
                        view.setTranslationX(movedX);
                        view.setTranslationY(movedY);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    if (!dragging) view.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    return true;
                default:
                    return false;
            }
        }

        /** [moved] kept to where the whole pill stays on the screen. */
        private static float within(float moved, View view, boolean across) {
            if (!(view.getParent() instanceof View)) return moved;
            View parent = (View) view.getParent();
            if (across) {
                float room = Math.max(0f, (parent.getWidth() - view.getWidth()) / 2f);
                return Math.max(-room, Math.min(room, moved));
            }
            float top = -view.getTop();
            float bottom = Math.max(top, parent.getHeight() - view.getBottom());
            return Math.max(top, Math.min(bottom, moved));
        }
    }

    // Starting and stopping.

    private static void toggle(Context context) {
        try {
            if (recording != null) stop();
            else start(context);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CALL_RECORDER, "record button", failure);
        }
    }

    private static void start(Context context) {
        Object api = engineAudio.get();
        Object model = latestModel;
        List<Person> people = new ArrayList<>();
        String why = null;
        if (api == null) why = "the call engine's audio isn't known";
        else if (model == null) why = "no call is known";
        else {
            try {
                people = people(model);
                if (people.isEmpty()) why = "the call names nobody";
            } catch (Throwable failure) {
                why = "the call's people couldn't be read: " + failure;
            }
        }
        if (why != null) {
            String reason = why;
            HookStatus.counted(FamilyNames.CALL_RECORDER, "start refused");
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "not recording: " + reason);
            Utils.showToastLong(L10n.t("Couldn't start recording this call"));
            return;
        }
        Recording started = new Recording(context.getApplicationContext(), api, people);
        recording = started;
        started.begin();
        refresh();
        main().removeCallbacks(CLOCK);
        main().postDelayed(CLOCK, LOOK_MS);
    }

    private static void stop() {
        Recording ending = recording;
        recording = null;
        main().removeCallbacks(CLOCK);
        refresh();
        if (ending != null) ending.end(true);
    }

    /** From the recorder's thread: [ending] is over, so the pill goes back to Record. */
    private static void over(Recording ending) {
        main().post(() -> {
            if (recording != ending) return;
            recording = null;
            main().removeCallbacks(CLOCK);
            refresh();
        });
    }

    // The engine's frames.

    /** A frame for [track], from its sink on the engine's audio thread. Never throws. */
    static void frame(Track track, Object frame) {
        if (track == null || frame == null || track.closed) return;
        try {
            Object metadata = METADATA.on(frame);
            int rate = ((Number) SAMPLE_RATE.on(metadata)).intValue();
            int channels = ((Number) CHANNELS.on(metadata)).intValue();
            int samples = ((Number) SAMPLES.on(metadata)).intValue();
            int total = shortsToRead(rate, channels, samples);
            if (total <= 0) {
                track.odd(rate, channels, samples);
                return;
            }
            short[] pcm = null;
            if (!Boolean.TRUE.equals(MUTED.on(metadata))) {
                Object data = AUDIO_DATA.on(frame);
                if (data != null) pcm = (short[]) SHORTS.on(data, total);
            }
            track.push(rate, channels, pcm, total);
        } catch (Throwable failure) {
            track.failed(failure);
        }
    }

    /**
     * How many samples a frame holds, all channels together, or 0 for one that's no 10 ms frame of
     * sound. The engine's count is per channel for a 10 ms frame of more than one, and the whole
     * buffer otherwise; the buffer is read with that count and nothing checks it, so never more.
     */
    static int shortsToRead(int rate, int channels, int samples) {
        if (rate < 8_000 || rate > 96_000 || channels < 1 || channels > 8 || samples < 1) return 0;
        int total = channels > 1 && samples == rate / 100 ? samples * channels : samples;
        return total > rate / 10 * channels ? 0 : total;
    }

    /** [length] mono samples from interleaved [pcm] of [channels], as -1 to 1; silence where there's none. */
    static void downmix(@Nullable short[] pcm, int length, int channels, float[] into) {
        if (pcm == null) {
            Arrays.fill(into, 0, length, 0f);
            return;
        }
        int have = pcm.length / channels;
        float scale = 32768f * channels;
        for (int index = 0; index < length; index++) {
            if (index >= have) {
                into[index] = 0f;
                continue;
            }
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) sum += pcm[index * channels + channel];
            into[index] = sum / scale;
        }
    }

    /** A mixed sample as 16-bit, held to full scale. */
    static short clip(float value) {
        if (value >= 1f) return Short.MAX_VALUE;
        if (value <= -1f) return -Short.MAX_VALUE;
        return (short) Math.round(value * Short.MAX_VALUE);
    }

    /** One of the engine's frame methods, found once on the class that declares it. */
    private static final class Call {
        private final String name;
        private final Class<?>[] parameters;
        private volatile Method method;

        Call(String name, Class<?>... parameters) {
            this.name = name;
            this.parameters = parameters;
        }

        Object on(Object target, Object... arguments) throws ReflectiveOperationException {
            Method known = method;
            if (known == null || !known.getDeclaringClass().isInstance(target)) {
                method = known = topmost(target.getClass(), name, parameters);
            }
            return known.invoke(target, arguments);
        }
    }

    /**
     * The public [name] declared highest above [type], so one lookup serves every subclass: the
     * engine hands frames of its own proxy classes, each overriding its abstract parent.
     */
    static Method topmost(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method found = null;
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            try {
                Method declared = at.getDeclaredMethod(name, parameters);
                if (Modifier.isPublic(declared.getModifiers())) found = declared;
            } catch (NoSuchMethodException notHere) {
                // Declared further up, or not at all.
            }
        }
        if (found == null) throw new NoSuchMethodException(type.getName() + "." + name);
        return found;
    }

    // The call's people.

    static final class Person {
        final String id;
        final String username;
        final boolean self;

        Person(String id, @Nullable String username, boolean self) {
            this.id = id;
            this.username = username;
            this.self = self;
        }
    }

    /** You, then everyone else on the call, each once; the engine's service participant isn't a person. */
    static List<Person> people(Object model) throws ReflectiveOperationException {
        List<Person> people = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        add(people, seen, field(model, "selfParticipant"), true);
        Object others = field(model, "remoteParticipants");
        if (others instanceof List) {
            for (Object participant : new ArrayList<>((List<?>) others)) add(people, seen, participant, false);
        }
        return people;
    }

    private static void add(List<Person> people, Set<String> seen, @Nullable Object participant, boolean self)
            throws ReflectiveOperationException {
        if (participant == null || Boolean.TRUE.equals(field(participant, "isService"))) return;
        Object id = field(participant, "userId");
        if (!(id instanceof String) || ((String) id).isEmpty() || !seen.add((String) id)) return;
        Object name = field(participant, "username");
        people.add(new Person((String) id, name instanceof String && !((String) name).isEmpty() ? (String) name : null, self));
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getField(name).get(owner);
    }

    /** The audio API's addSink or removeSink: (String id, int stream type, the engine's sink). */
    static Method sinkMethod(Object api, String name, Object sink) throws NoSuchMethodException {
        for (Method method : api.getClass().getMethods()) {
            Class<?>[] takes = method.getParameterTypes();
            if (method.getName().equals(name) && takes.length == 3 && takes[0] == String.class
                    && takes[1] == int.class && takes[2].isInstance(sink)) {
                return method;
            }
        }
        throw new NoSuchMethodException(api.getClass().getName() + "." + name);
    }

    // One person's sound.

    /**
     * One person's frames, at {@link #RATE} mono, waiting for the mix. The engine's thread pushes
     * them and the recorder's takes them, so both go through this lock.
     */
    static final class Track {
        final String id;
        final CallAudioSink sink;
        private final Resampler resampler = new Resampler();
        private final float[] queue = new float[RATE * 2];
        private int head, size;
        private boolean primed;
        private float[] mono = new float[0];
        private float[] made = new float[0];
        volatile long frames;
        volatile long heardAt;
        volatile boolean closed;
        private volatile boolean reportedOdd, reportedFailure;

        Track(String id) {
            this.id = id;
            this.sink = new CallAudioSink(this);
        }

        synchronized void push(int rate, int channels, @Nullable short[] pcm, int total) {
            int length = total / channels;
            if (length <= 0) return;
            if (mono.length < length) mono = new float[length];
            downmix(pcm, length, channels, mono);
            int most = Resampler.most(length, rate);
            if (made.length < most) made = new float[most];
            int count = resampler.process(mono, length, rate, made);
            for (int index = 0; index < count; index++) {
                if (size == queue.length) {
                    head = (head + 1) % queue.length;
                    size--;
                }
                queue[(head + size) % queue.length] = made[index];
                size++;
            }
            frames++;
            heardAt = SystemClock.elapsedRealtime();
        }

        /** Whether this person has sound ready to mix. */
        synchronized boolean ready() {
            return primed || size >= PRIME;
        }

        /**
         * Adds up to [length] of this person's samples into [mix]. A queue fills to {@link #PRIME}
         * before it's mixed, and one that runs dry leaves the rest silent and fills again.
         */
        synchronized void take(float[] mix, int length) {
            if (!primed) {
                if (size < PRIME) return;
                primed = true;
            }
            if (size > BACKLOG) {
                int drop = size - KEEP;
                head = (head + drop) % queue.length;
                size -= drop;
            }
            int count = Math.min(length, size);
            for (int index = 0; index < count; index++) mix[index] += queue[(head + index) % queue.length];
            head = (head + count) % queue.length;
            size -= count;
            if (count < length) primed = false;
        }

        synchronized int queuedForTests() {
            return size;
        }

        void odd(int rate, int channels, int samples) {
            if (reportedOdd) return;
            reportedOdd = true;
            HookStatus.counted(FamilyNames.CALL_RECORDER, "odd frame");
            Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                    () -> "skipped a frame of " + samples + " samples, " + channels + " channels at " + rate + " Hz");
        }

        void failed(Throwable failure) {
            if (reportedFailure) return;
            reportedFailure = true;
            HookStatus.counted(FamilyNames.CALL_RECORDER, "frame failed");
            Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "couldn't read a call frame", failure);
        }
    }

    /**
     * Linear resampling to {@link #RATE}, carried across frames: the position runs in steps of the
     * input rate over a 48 kHz grid, so no frame boundary drifts, and the last sample of a frame
     * leads into the next.
     */
    static final class Resampler {
        private int rate;
        private long at;
        private float last;
        private boolean started;

        /** The most samples [length] at [rate] makes. */
        static int most(int length, int rate) {
            return (int) ((long) length * RATE / rate) + 2;
        }

        int process(float[] in, int length, int rate, float[] out) {
            if (length <= 0 || rate <= 0) return 0;
            if (rate != this.rate) {
                this.rate = rate;
                at = 0L;
                started = false;
            }
            int count = 0;
            long end = (long) (length - 1) * RATE;
            while (at <= end) {
                long whole = Math.floorDiv(at, (long) RATE);
                int index = (int) whole;
                float part = (float) (at - whole * RATE) / RATE;
                float from = index < 0 ? (started ? last : in[0]) : in[index];
                float to = index + 1 < length ? in[index + 1] : in[length - 1];
                out[count++] = from + (to - from) * part;
                at += rate;
            }
            at -= (long) length * RATE;
            last = in[length - 1];
            started = true;
            return count;
        }
    }

    // A recording.

    /** One recording: its people's sinks, the mix and the encoder, all on its own thread. */
    static final class Recording {
        final Context app;
        final Object api;
        final Date started = new Date();
        final long since = SystemClock.elapsedRealtime();
        private final List<Person> people;
        private final HandlerThread thread;
        private final Handler handler;
        private final List<Track> tracks = new ArrayList<>();
        private final float[] mix = new float[RATE / 10];
        private final short[] pcm = new short[RATE / 10];
        private volatile long written;
        private Encoder encoder;
        private File file;
        private boolean ended;
        private String username;
        /** When the engine last took one of this recording's sinks, while its mark is down; -1 once it's up. */
        private long markedAt = -1L;

        Recording(Context app, Object api, List<Person> people) {
            this.app = app;
            this.api = api;
            this.people = people;
            thread = new HandlerThread("HushGram call recorder", Process.THREAD_PRIORITY_AUDIO);
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        long recordedMs() {
            return written * 1000L / RATE;
        }

        void begin() {
            handler.post(this::open);
        }

        void end(boolean save) {
            handler.post(() -> close(save));
        }

        private void open() {
            try {
                file = scratch(app);
                encoder = new Encoder(file);
                for (Person person : people) join(person);
                if (tracks.isEmpty()) throw new IllegalStateException("the engine took no sink");
                HookStatus.counted(FamilyNames.CALL_RECORDER, "recording started");
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "recording " + tracks.size() + " of " + people.size() + " people");
                handler.postDelayed(mixing, MIX_MS);
                handler.postDelayed(looking, LOOK_MS);
            } catch (Throwable failure) {
                HookStatus.counted(FamilyNames.CALL_RECORDER, "start failed");
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "couldn't start recording", failure);
                Utils.showToastLong(L10n.t("Couldn't start recording this call"));
                close(false);
            }
        }

        /** A sink for [person], unless they have one. One the engine refuses is logged and skipped. */
        private void join(Person person) {
            for (Track track : tracks) if (track.id.equals(person.id)) return;
            if (username == null && !person.self) username = person.username;
            Track track = new Track(person.id);
            try {
                markStarting(app, true);
                markedAt = SystemClock.elapsedRealtime();
                sinkMethod(api, "addSink", track.sink).invoke(api, person.id, DEFAULT_STREAM, track.sink);
                tracks.add(track);
            } catch (Throwable failure) {
                track.closed = true;
                HookStatus.counted(FamilyNames.CALL_RECORDER, "sink refused");
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE,
                        () -> "the engine refused a sink for " + (person.self ? "you" : "a person on the call"), failure);
            }
        }

        private final Runnable mixing = new Runnable() {
            @Override
            public void run() {
                if (ended) return;
                if (markedAt >= 0L && SystemClock.elapsedRealtime() - markedAt > MARK_MS) {
                    markedAt = -1L;
                    markStarting(app, false);
                }
                try {
                    mixDue();
                } catch (Throwable failure) {
                    HookStatus.counted(FamilyNames.CALL_RECORDER, "mix failed");
                    Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the mix stopped", failure);
                    close(true);
                    return;
                }
                handler.postDelayed(this, MIX_MS);
            }
        };

        /** Each person's new frames, mixed and encoded, while anyone has some. */
        private void mixDue() {
            for (int blocks = 0; blocks < 10; blocks++) {
                boolean anyone = false;
                for (Track track : tracks) anyone |= track.ready();
                if (!anyone) return;
                int length = (int) Math.min(mix.length, RATE * MIX_MS / 1000L);
                Arrays.fill(mix, 0, length, 0f);
                for (Track track : tracks) track.take(mix, length);
                for (int index = 0; index < length; index++) pcm[index] = clip(mix[index]);
                encoder.write(pcm, length);
                written += length;
            }
        }

        private final Runnable looking = new Runnable() {
            @Override
            public void run() {
                if (ended) return;
                long heard = since;
                for (Track track : tracks) heard = Math.max(heard, track.heardAt);
                if (SystemClock.elapsedRealtime() - heard > QUIET_MS) {
                    HookStatus.counted(FamilyNames.CALL_RECORDER, "went quiet");
                    close(true);
                    return;
                }
                try {
                    Object model = latestModel;
                    if (model != null) for (Person person : people(model)) join(person);
                } catch (Throwable failure) {
                    HookStatus.counted(FamilyNames.CALL_RECORDER, "people unreadable");
                }
                handler.postDelayed(this, LOOK_MS);
            }
        };

        /** Ends the recording: the sinks come off, the file's finished and, when [save], goes to the phone. */
        private void close(boolean save) {
            if (ended) return;
            ended = true;
            if (markedAt >= 0L) {
                markedAt = -1L;
                markStarting(app, false);
            }
            handler.removeCallbacks(mixing);
            handler.removeCallbacks(looking);
            over(this);
            long frames = 0L;
            for (Track track : tracks) {
                frames += track.frames;
                track.closed = true;
                try {
                    sinkMethod(api, "removeSink", track.sink).invoke(api, track.id, DEFAULT_STREAM, track.sink);
                } catch (Throwable failure) {
                    Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "couldn't take a sink off", failure);
                }
            }
            try {
                boolean playable = encoder != null && encoder.finish();
                encoder = null;
                if (!save) return;
                if (frames == 0L || !playable) {
                    long heard = frames;
                    HookStatus.counted(FamilyNames.CALL_RECORDER, "nothing recorded");
                    Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE,
                            () -> "nothing to save: " + heard + " frames, file " + (playable ? "finished" : "empty"));
                    Utils.showToastLong(L10n.t("Nothing was recorded"));
                    return;
                }
                MediaStoreWriter writer = MediaStoreWriter.forAudio(app,
                        username == null ? PostDetails.NONE : new PostDetails(null, username, started));
                Downloader.Result result = Downloader.publish(file, MediaStoreWriter.AUDIO_MP4, writer, Downloader.SILENT);
                if (result.ok()) {
                    HookStatus.counted(FamilyNames.CALL_RECORDER, "recording saved");
                    long length = recordedMs();
                    Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "saved " + clock(length) + " of the call");
                    Utils.showToastLong(L10n.f("Saved to %1$s", L10n.isolate(writer.savedLocation())));
                } else {
                    HookStatus.counted(FamilyNames.CALL_RECORDER, "save failed");
                    Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "couldn't save: " + result, null);
                    Utils.showToastLong(L10n.t("Couldn't save the call recording"));
                }
            } catch (Throwable failure) {
                HookStatus.counted(FamilyNames.CALL_RECORDER, "save failed");
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "couldn't finish the recording", failure);
                if (save) Utils.showToastLong(L10n.t("Couldn't save the call recording"));
            } finally {
                if (encoder != null) encoder.abandon();
                if (file != null && file.exists() && !file.delete()) {
                    Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the scratch recording stayed behind");
                }
                thread.quitSafely();
            }
        }
    }

    /** A new scratch file for a recording, clearing ones older recordings left. */
    private static File scratch(Context app) throws IOException {
        File folder = new File(app.getCacheDir(), "hushgram-calls");
        if (!folder.mkdirs() && !folder.isDirectory()) throw new IOException("no scratch folder");
        File[] old = folder.listFiles();
        long now = System.currentTimeMillis();
        if (old != null) {
            for (File leftover : old) {
                if (now - leftover.lastModified() > LEFTOVER_MS && !leftover.delete()) {
                    Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "an old scratch recording stayed behind");
                }
            }
        }
        return new File(folder, "call-" + now + MediaStoreWriter.AUDIO_SUFFIX);
    }

    /** AAC-LC at {@link #RATE} mono into an MPEG-4 file. */
    private static final class Encoder {
        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private int track = -1;
        private boolean muxing, wrote;
        private long samples;

        Encoder(File file) throws IOException {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, RATE / 10 * 2);
            MediaCodec made = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            MediaMuxer into;
            try {
                made.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                made.start();
                into = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException | RuntimeException failure) {
                made.release();
                throw failure;
            }
            codec = made;
            muxer = into;
        }

        void write(short[] pcm, int length) {
            int offset = 0;
            int waits = 0;
            while (offset < length) {
                int index = codec.dequeueInputBuffer(ENCODER_WAIT_US);
                if (index < 0) {
                    drain(false);
                    if (++waits > 50) throw new IllegalStateException("the encoder took no sound for half a second");
                    continue;
                }
                ByteBuffer buffer = codec.getInputBuffer(index);
                if (buffer == null) throw new IllegalStateException("the encoder gave no input buffer");
                buffer.clear();
                buffer.order(ByteOrder.nativeOrder());
                int count = Math.min(length - offset, buffer.remaining() / 2);
                if (count <= 0) throw new IllegalStateException("the encoder's input buffer holds no sample");
                buffer.asShortBuffer().put(pcm, offset, count);
                codec.queueInputBuffer(index, 0, count * 2, samples * 1_000_000L / RATE, 0);
                samples += count;
                offset += count;
                drain(false);
            }
        }

        private void drain(boolean end) {
            int idle = 0;
            while (true) {
                int index = codec.dequeueOutputBuffer(info, end ? ENCODER_WAIT_US : 0L);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!end || ++idle > 100) return;
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxing) throw new IllegalStateException("the encoder changed its format twice");
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxing = true;
                    continue;
                }
                if (index < 0) {
                    if (++idle > 100) return;
                    continue;
                }
                ByteBuffer out = codec.getOutputBuffer(index);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && info.size > 0 && muxing && out != null) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    muxer.writeSampleData(track, out, info);
                    wrote = true;
                }
                codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        /** Ends the stream and the file. Whether the file holds any sound to play. */
        boolean finish() {
            try {
                int index = -1;
                for (int tries = 0; index < 0 && tries < 50; tries++) {
                    index = codec.dequeueInputBuffer(ENCODER_WAIT_US);
                    if (index < 0) drain(false);
                }
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, samples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    drain(true);
                }
            } catch (IllegalStateException failure) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the encoder didn't finish", failure);
            }
            stopCodec();
            boolean finished = false;
            try {
                if (muxing && wrote) {
                    muxer.stop();
                    finished = true;
                }
            } catch (IllegalStateException failure) {
                Logger.diagnosticError(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "the file didn't finish", failure);
            } finally {
                muxer.release();
            }
            return finished;
        }

        void abandon() {
            stopCodec();
            muxer.release();
        }

        private void stopCodec() {
            try {
                codec.stop();
            } catch (IllegalStateException alreadyStopped) {
                // Released or failed already; release() below is what frees it.
            }
            codec.release();
        }
    }

    private static final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            if (!callScreen(activity)) return;
            try {
                resumed(activity);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CALL_RECORDER, "call screen", failure);
            }
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            if (!callScreen(activity)) return;
            try {
                paused(activity);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CALL_RECORDER, "call screen", failure);
            }
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            if (!callScreen(activity)) return;
            try {
                destroyed(activity);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CALL_RECORDER, "call screen", failure);
            }
        }

        @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
        @Override public void onActivityStarted(@NonNull Activity activity) { }
        @Override public void onActivityStopped(@NonNull Activity activity) { }
        @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
    }

    static void resetForTests() {
        recording = null;
        latestModel = null;
        engineAudio = new WeakReference<>(null);
        shown = new WeakReference<>(null);
        movedX = 0f;
        movedY = 0f;
    }
}
