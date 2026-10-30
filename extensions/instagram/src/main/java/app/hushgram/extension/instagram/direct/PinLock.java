/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.ScreenColors;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.Setting;

/**
 * A HushGram PIN for {@link MessagesLock}, for people whose phone's lock is known to others. It
 * answers the same ask the phone's lock does, so every lock path gets it from one place.
 *
 * <p>The PIN is 4 to 8 digits and is kept as a salted PBKDF2 hash in HushGram's own preferences,
 * never the digits. A backup leaves it, the choice of PIN and the miss count out. Five wrong tries
 * in a row make it wait 30 seconds before another, and the count and the wait outlast a restart.
 * The PIN can't be recovered, and nothing that knows the phone's lock can reset it: Forgot PIN
 * only says how to clear it (Instagram's storage in Android's app settings, which also resets
 * HushGram's settings). Every setting that would weaken a lock asks the PIN first.
 */
public final class PinLock {
    public static final int MIN_DIGITS = 4;
    public static final int MAX_DIGITS = 8;
    static final int MAX_MISSES = 5;
    static final long WAIT_MS = 30_000;
    static final int ITERATIONS = 120_000;
    static final String PREFIX = "pbkdf2-sha256";
    static final String FIELD_TAG = "hushgram_pin_field";
    static final String CONFIRM_TAG = "hushgram_pin_confirm";
    static final String MESSAGE_TAG = "hushgram_pin_message";
    static final String SCROLL_TAG = "hushgram_pin_scroll";
    private static final int ERROR_COLOR = ScreenColors.DEFAULT.error;

    static final String PIN_STEP = "pin";

    /** The clock the wait runs on, wall time so it holds across a restart. Tests move it. */
    static volatile LongSupplier clock = System::currentTimeMillis;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** The current process still rate-limits guesses when its preference write fails. */
    private static String missPin;
    private static long liveMisses;
    private static long liveWaitUntil;

    private PinLock() {
    }

    /** Messages are unlocked with the PIN: it's the choice and a PIN is set. */
    public static boolean active() {
        try {
            return Utils.settingsReady() && Settings.UNLOCK_WITH.get() == UnlockWith.PIN && isSet();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, PIN_STEP, t);
            return false;
        }
    }

    /**
     * A PIN is what stands behind the locks, whether or not settings are ready to be read. Anything
     * that can't tell takes the answer yes, so it fails closed.
     */
    public static boolean protects() {
        try {
            return Settings.UNLOCK_WITH.savedValue() == UnlockWith.PIN && isSet();
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isSet() {
        return parse(Settings.LOCK_PIN.get()) != null;
    }

    static boolean validFormat(CharSequence pin) {
        if (pin == null || pin.length() < MIN_DIGITS || pin.length() > MAX_DIGITS) return false;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Stores [pin] as a salted hash and forgets earlier misses. False when it isn't 4 to 8 digits. */
    public static boolean set(CharSequence pin) {
        return set(pin, () -> true);
    }

    private static boolean set(CharSequence pin, BooleanSupplier maySave) {
        if (!validFormat(pin)) return false;
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] hash = hash(pin, salt, ITERATIONS);
            String stored = PREFIX + "$" + ITERATIONS + "$" + hex(salt) + "$" + hex(hash);
            synchronized (PinLock.class) {
                synchronized (Setting.class) {
                    if (!maySave.getAsBoolean()) return false;
                    Map<Setting<?>, Object> updates = clearedMisses();
                    updates.put(Settings.LOCK_PIN, stored);
                    Setting.saveAll(updates);
                    resetLiveMisses(stored);
                }
            }
            return true;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, PIN_STEP, t);
            Logger.printException(() -> "Lock your messages: could not store the PIN", t);
            return false;
        }
    }

    static synchronized void clear() {
        Map<Setting<?>, Object> updates = clearedMisses();
        updates.put(Settings.LOCK_PIN, Settings.LOCK_PIN.defaultValue);
        if (saveMisses(updates)) resetLiveMisses(Settings.LOCK_PIN.savedValue());
    }

    /** True when [pin] is the PIN. Doesn't count a miss; {@link #attempt} does. */
    public static boolean matches(CharSequence pin) {
        String[] parts = parse(Settings.LOCK_PIN.get());
        if (parts == null || !validFormat(pin)) return false;
        try {
            byte[] expected = unhex(parts[3]);
            byte[] actual = hash(pin, unhex(parts[2]), Integer.parseInt(parts[1]));
            return MessageDigest.isEqual(expected, actual);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.MESSAGES_LOCK, PIN_STEP, t);
            return false;
        }
    }

    enum Result { RIGHT, WRONG, WAIT }

    /** Checks [pin] against the PIN, counting a miss and starting the wait after too many. */
    static synchronized Result attempt(CharSequence pin) {
        if (waitLeftMs() > 0) return Result.WAIT;
        if (matches(pin)) {
            resetMisses();
            return Result.RIGHT;
        }
        syncMisses();
        long misses = Math.min(MAX_MISSES, Math.max(liveMisses, Settings.LOCK_PIN_MISSES.savedValue())) + 1;
        liveMisses = misses;
        if (misses >= MAX_MISSES) liveWaitUntil = clock.getAsLong() + WAIT_MS;
        Map<Setting<?>, Object> updates = new LinkedHashMap<>();
        updates.put(Settings.LOCK_PIN_MISSES, misses);
        if (misses >= MAX_MISSES) updates.put(Settings.LOCK_PIN_WAIT_UNTIL, liveWaitUntil);
        saveMisses(updates);
        return Result.WRONG;
    }

    /** How long until another try is allowed, in milliseconds; 0 when one is. */
    static synchronized long waitLeftMs() {
        syncMisses();
        long until = Math.max(Settings.LOCK_PIN_WAIT_UNTIL.savedValue(), liveWaitUntil);
        if (until <= 0) return 0;
        long now = clock.getAsLong();
        // A clock set back can't stretch the wait past what one wait is.
        long left = Math.min(until - now, WAIT_MS);
        return Math.max(left, 0);
    }

    private static void resetMisses() {
        resetLiveMisses(Settings.LOCK_PIN.savedValue());
        saveMisses(clearedMisses());
    }

    private static Map<Setting<?>, Object> clearedMisses() {
        Map<Setting<?>, Object> updates = new LinkedHashMap<>();
        updates.put(Settings.LOCK_PIN_MISSES, 0L);
        updates.put(Settings.LOCK_PIN_WAIT_UNTIL, 0L);
        return updates;
    }

    private static boolean saveMisses(Map<Setting<?>, Object> updates) {
        try {
            Setting.saveAll(updates);
            return true;
        } catch (java.io.IOException failure) {
            Logger.printException(() -> "Could not save PIN protection", failure);
            return false;
        }
    }

    private static void syncMisses() {
        String stored = Settings.LOCK_PIN.savedValue();
        if (!stored.equals(missPin)) resetLiveMisses(stored);
    }

    private static void resetLiveMisses(String stored) {
        missPin = stored;
        liveMisses = 0;
        liveWaitUntil = 0;
    }

    // ---------------------------------------------------------------- storage

    private static byte[] hash(CharSequence pin, byte[] salt, int iterations) throws Exception {
        char[] digits = new char[pin.length()];
        for (int i = 0; i < digits.length; i++) digits[i] = pin.charAt(i);
        PBEKeySpec spec = new PBEKeySpec(digits, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(digits, '\0');
        }
    }

    /** The stored value's four parts, or null when nothing is stored or it isn't ours. */
    private static String[] parse(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return null;
        try {
            if (Integer.parseInt(parts[1]) < 1 || parts[2].isEmpty() || parts[3].isEmpty()) return null;
        } catch (NumberFormatException bad) {
            return null;
        }
        return parts;
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(digits[(b >> 4) & 15]).append(digits[b & 15]);
        return out.toString();
    }

    private static byte[] unhex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ---------------------------------------------------------------- the dialogs

    /** Asks for the PIN in a HushGram dialog; the same shape as the phone's ask. */
    static void ask(Activity activity, Runnable confirmed, Runnable notConfirmed) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            notConfirmed.run();
            return;
        }
        EditText field = digitsField(activity, FIELD_TAG, EditorInfo.IME_ACTION_DONE);
        TextView message = message(activity);
        LinearLayout body = body(activity);
        addField(body, field, L10n.t("PIN"));
        body.addView(message);
        clearErrorOnEdit(field, message, "");
        boolean[] done = {false};
        AlertDialog dialog = builder(activity)
                .setTitle(L10n.t("Enter your PIN"))
                .setView(scroll(body))
                .setPositiveButton(L10n.t("Unlock"), null)
                .setNeutralButton(L10n.t("Forgot PIN?"), null)
                .setNegativeButton(L10n.t("Cancel"), null)
                .create();
        secure(dialog, true);
        dialog.setCanceledOnTouchOutside(false);
        // Only this dialog owns the countdown. Dismissal, including a turn or activity teardown,
        // drops its callback and clears the digits before reporting a cancelled ask.
        Runnable retry = new Runnable() {
            @Override
            public void run() {
                message.removeCallbacks(this);
                if (!dialog.isShowing()) return;
                boolean waiting = waitLeftMs() > 0;
                // Announce the wait once, rather than reading a fresh number every second.
                message.setAccessibilityLiveRegion(waiting && !field.isEnabled()
                        ? View.ACCESSIBILITY_LIVE_REGION_NONE : View.ACCESSIBILITY_LIVE_REGION_POLITE);
                showWait(message);
                field.setEnabled(!waiting);
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!waiting);
                if (waiting) message.postDelayed(this, 1000);
                else field.requestFocus();
            }
        };
        dialog.setOnCancelListener(d -> {
            if (!done[0]) {
                done[0] = true;
                notConfirmed.run();
            }
        });
        // Closed some other way, by Instagram or Android rather than a button: the ask is over all
        // the same, or the cover's Unlock would do nothing until the ask timed out.
        dialog.setOnDismissListener(d -> {
            message.removeCallbacks(retry);
            field.setText("");
            if (!done[0]) {
                done[0] = true;
                notConfirmed.run();
            }
        });
        dialog.setOnShowListener(d -> {
            Runnable unlock = () -> {
                // An unfinished entry isn't a guess. Keep it for correction without spending a try.
                if (!validFormat(field.getText())) {
                    error(message, L10n.t("Use 4 to 8 digits."));
                    field.requestFocus();
                    return;
                }
                Result result = attempt(field.getText());
                if (result == Result.RIGHT) {
                    done[0] = true;
                    field.setText("");
                    dialog.dismiss();
                    confirmed.run();
                    return;
                }
                field.setText("");
                if (result == Result.WAIT || waitLeftMs() > 0) retry.run();
                else {
                    error(message, L10n.t("Wrong PIN. Try again."));
                    field.requestFocus();
                }
            };
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> unlock.run());
            field.setOnEditorActionListener((v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                if (dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled()) unlock.run();
                return true;
            });
            // Forgot PIN only explains; the PIN dialog stays under it and nothing is reset.
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> forgot(activity));
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
                done[0] = true;
                field.setText("");
                dialog.dismiss();
                notConfirmed.run();
            });
            retry.run();
        });
        dialog.show();
        ScreenColors.dialog(dialog);
        // Keep recovery beside the field, with the answer buttons left together below it.
        Button forgot = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        ((ViewGroup) forgot.getParent()).removeView(forgot);
        ScreenColors.styleAction(forgot, false);
        LinearLayout.LayoutParams recovery = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        recovery.topMargin = dp(activity, 8);
        body.addView(forgot, recovery);
    }

    private static void showWait(TextView message) {
        long left = waitLeftMs();
        message.setTextColor(left > 0 ? ERROR_COLOR : ScreenColors.DEFAULT.summary);
        message.setVisibility(left > 0 ? View.VISIBLE : View.GONE);
        message.setText(left > 0
                ? L10n.f("Too many tries. Wait %1$d seconds.", (left + 999) / 1000)
                : "");
    }

    /**
     * Forgot PIN: the PIN can't be recovered and the phone's lock can't reset it, since people
     * around you know that. Clearing Instagram's storage is the way out, and it's offered here.
     */
    private static void forgot(Activity activity) {
        AlertDialog dialog = builder(activity)
                .setTitle(L10n.t("Forgot PIN?"))
                .setMessage(L10n.t("The PIN can't be recovered. To remove it, clear Instagram's storage in Android's "
                        + "app settings. That signs you out and resets HushGram's settings. A saved settings backup "
                        + "brings the rest back, but not the PIN."))
                .setPositiveButton(L10n.t("Open app info"), null)
                .setNegativeButton(L10n.t("Cancel"), null)
                .create();
        secure(dialog, false);
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.getPackageName(), null)));
            } catch (Throwable t) {
                HookStatus.threw(FamilyNames.MESSAGES_LOCK, PIN_STEP, t);
            }
            dialog.dismiss();
        }));
        dialog.show();
        ScreenColors.dialog(dialog);
    }

    /** Set a PIN while it's up, so settings the lock covers can close it. */
    private static WeakReference<AlertDialog> newPin = new WeakReference<>(null);

    /**
     * Closes Set a PIN if it's up, as a cancel would: nothing is saved. Settings the lock has just
     * covered call this, since the dialog is a window of its own above their cover, and whoever has
     * the phone could otherwise choose the PIN that unlocks the messages.
     */
    public static void closeNew() {
        AlertDialog dialog = newPin.get();
        newPin = new WeakReference<>(null);
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    /**
     * Asks twice for a new 4 to 8 digit PIN and stores it. [done] hears whether it was saved; a
     * cancel is a false. Nothing changes until both entries match.
     */
    public static void promptNew(Activity activity, Consumer<Boolean> done) {
        promptNew(activity, () -> true, done);
    }

    /** A delayed form rechecks permission at the PIN transaction, after hashing. */
    public static void promptNew(Activity activity, BooleanSupplier maySave, Consumer<Boolean> done) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            done.accept(false);
            return;
        }
        EditText first = digitsField(activity, FIELD_TAG, EditorInfo.IME_ACTION_NEXT);
        EditText again = digitsField(activity, CONFIRM_TAG, EditorInfo.IME_ACTION_DONE);
        first.setNextFocusForwardId(again.getId());
        TextView message = message(activity);
        message.setText(L10n.t("Use 4 to 8 digits."));
        LinearLayout body = body(activity);
        addField(body, first, L10n.t("New PIN"));
        addField(body, again, L10n.t("Confirm PIN"));
        body.addView(message);
        clearErrorOnEdit(first, message, L10n.t("Use 4 to 8 digits."));
        clearErrorOnEdit(again, message, L10n.t("Use 4 to 8 digits."));
        boolean[] finished = {false};
        AlertDialog dialog = builder(activity)
                .setTitle(L10n.t("Set a PIN"))
                .setView(scroll(body))
                .setPositiveButton(L10n.t("Save"), null)
                .setNegativeButton(L10n.t("Cancel"), null)
                .create();
        secure(dialog, true);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> {
            if (!finished[0]) {
                finished[0] = true;
                done.accept(false);
            }
        });
        dialog.setOnDismissListener(d -> {
            if (newPin.get() == dialog) newPin = new WeakReference<>(null);
            first.setText("");
            again.setText("");
            if (!finished[0]) {
                finished[0] = true;
                done.accept(false);
            }
        });
        dialog.setOnShowListener(d -> {
            Runnable save = () -> {
                String a = first.getText().toString();
                String b = again.getText().toString();
                if (!validFormat(a)) {
                    error(message, L10n.t("Use 4 to 8 digits."));
                    first.requestFocus();
                    return;
                }
                if (!validFormat(b)) {
                    error(message, L10n.t("Use 4 to 8 digits."));
                    again.requestFocus();
                    return;
                }
                if (!a.equals(b)) {
                    again.setText("");
                    error(message, L10n.t("The PINs don't match."));
                    again.requestFocus();
                    return;
                }
                boolean[] refused = {false};
                if (!set(a, () -> {
                    refused[0] = !maySave.getAsBoolean();
                    return !refused[0];
                })) {
                    if (refused[0]) {
                        finished[0] = true;
                        dialog.dismiss();
                        done.accept(false);
                        return;
                    }
                    error(message, L10n.t("Couldn't save the PIN."));
                    return;
                }
                finished[0] = true;
                first.setText("");
                again.setText("");
                dialog.dismiss();
                done.accept(true);
            };
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> save.run());
            first.setOnEditorActionListener((v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_NEXT) return false;
                if (validFormat(first.getText())) again.requestFocus();
                else {
                    error(message, L10n.t("Use 4 to 8 digits."));
                    first.requestFocus();
                }
                return true;
            });
            again.setOnEditorActionListener((v, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                save.run();
                return true;
            });
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
                finished[0] = true;
                dialog.dismiss();
                done.accept(false);
            });
            first.requestFocus();
        });
        newPin = new WeakReference<>(dialog);
        dialog.show();
        ScreenColors.dialog(dialog);
    }

    private static AlertDialog.Builder builder(Context context) {
        return new AlertDialog.Builder(context, Utils.isDarkModeEnabled()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    /** The window can't be captured or shown in the recent apps picture while the digits are typed. */
    private static void secure(AlertDialog dialog, boolean input) {
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | (input ? WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN));
    }

    /** A masked number field: no autofill, at most 8 digits. */
    private static EditText digitsField(Context context, String tag, int action) {
        EditText field = new EditText(context);
        field.setId(View.generateViewId());
        field.setTag(tag);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_DIGITS)});
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setSaveEnabled(false);
        field.setImeOptions(action | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        ScreenColors.styleField(field);
        return field;
    }

    private static TextView message(Context context) {
        TextView message = new TextView(context);
        message.setTag(MESSAGE_TAG);
        message.setTextSize(14);
        message.setTextColor(ScreenColors.DEFAULT.summary);
        message.setMinHeight(dp(context, 24));
        message.setPadding(0, dp(context, 12), 0, 0);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return message;
    }

    private static void error(TextView message, String text) {
        message.setVisibility(View.VISIBLE);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setTextColor(ERROR_COLOR);
        message.setText(text);
    }

    /** A correction removes the old error, while a lockout keeps its own countdown message. */
    private static void clearErrorOnEdit(EditText field, TextView message, String help) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                if (!field.isEnabled() || value.length() == 0 || message.getCurrentTextColor() != ERROR_COLOR) return;
                message.setTextColor(ScreenColors.DEFAULT.summary);
                message.setText(help);
                message.setVisibility(help.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static LinearLayout body(Context context) {
        int pad = dp(context, 20);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPaddingRelative(pad, pad / 2, pad, dp(context, 12));
        return body;
    }

    /** Labels stay readable after typing, and identify each masked field to a screen reader. */
    private static void addField(LinearLayout body, EditText field, String name) {
        TextView label = new TextView(body.getContext());
        label.setText(name);
        label.setTextSize(14);
        label.setTextColor(ScreenColors.DEFAULT.summary);
        label.setLabelFor(field.getId());
        LinearLayout.LayoutParams labelSize = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (body.getChildCount() > 0) labelSize.topMargin = dp(body.getContext(), 12);
        body.addView(label, labelSize);
        body.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** The framework keeps the answer buttons in view while the form scrolls above the keyboard. */
    private static ScrollView scroll(LinearLayout body) {
        ScrollView scroll = new ScrollView(body.getContext());
        scroll.setTag(SCROLL_TAG);
        scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        clock = System::currentTimeMillis;
        newPin = new WeakReference<>(null);
    }
}
