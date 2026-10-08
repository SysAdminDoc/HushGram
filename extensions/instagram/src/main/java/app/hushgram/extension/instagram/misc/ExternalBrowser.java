/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/extensions/extension/src/main/java/app/andrewliang/extension/ExternalBrowser.java
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026, and for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.util.Locale;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the Instagram "Open links in external browser" patch.
 *
 * <p>Instagram opens each tapped web link in its own browser. The patch calls this class from the
 * {@code onCreate} and {@code onNewIntent} of that browser, after the superclass call and before
 * the browser is built. This class then builds an {@link Intent#ACTION_VIEW} on the URL and gives
 * it to the system.
 *
 * <p>The URL that arrives is often not the one the user tapped: a link in a bio or a caption comes
 * wrapped in Instagram's click tracker, {@code https://l.instagram.com/?u=<the page>&e=<token>}.
 * The page goes out, and the tracker doesn't, so the browser makes one request and not two.
 *
 * <p>An {@code ACTION_VIEW} on an ordinary URL can't come back into Instagram and make a loop.
 * Each {@code http} and {@code https} intent filter of Instagram 449 is limited to an Instagram or
 * Facebook host, and those stay here.
 *
 * <p>{@link #redirect} returns {@code false} when the link must stay in the in-app browser. The
 * patch then continues into Instagram's original code. That covers Meta's own sites, ads, the
 * schemes that are not web schemes, and a phone with no browser. A link no other app can open
 * still opens in the app.
 */
public final class ExternalBrowser {

    private ExternalBrowser() {}

    /** The source every event of this hook carries in the diagnostic report. */
    private static final String SOURCE = "ExternalBrowser";

    /**
     * The hosts that stay in the in-app browser, each with its subdomains. Login, account linking,
     * checkout and Meta's own pages need the in-app browser's JavaScript bridges, which no other
     * browser has. Instagram 449's own lists of its family's sites name instagram.com, facebook.com
     * and both Threads domains.
     */
    private static final String[] INTERNAL_HOSTS = {
        "instagram.com",
        "instagr.am",
        "ig.me",
        "facebook.com",
        "fb.com",
        "fb.me",
        "fb.watch",
        "messenger.com",
        "meta.com",
        "threads.net",
        "threads.com",
    };

    /**
     * The extra Instagram's browser launchers put the link's context in. The ad launcher's is an
     * IABAdsContext, a kept name, and a tapped link's an IABOrganicContext.
     */
    private static final String IAB_CONTEXT = "EXTRA_IAB_CONTEXT";
    private static final String ADS_CONTEXT = "com.facebook.browser.iabcontext.IABAdsContext";

    /**
     * Gives the URL of {@code intent} to the system browser and closes the in-app browser.
     *
     * @param activity the in-app browser activity. It closes only when the link goes out.
     * @param intent   the intent that started the activity. The URL is the data of that intent,
     *                 and not an extra.
     * @return {@code true} when the link went out and the caller must stop. {@code false} when the
     *         caller must continue and open the in-app browser as before.
     */
    public static boolean redirect(Activity activity, Intent intent) {
        HookStatus.invoked(FamilyNames.EXTERNAL_BROWSER);
        if (activity == null || intent == null) return false;
        if (!switchedOn()) return false;

        Uri uri = intent.getData();
        if (uri == null || !Utils.isWebLink(uri) || isAd(activity, intent)) return false;

        Uri target = LinkCleaner.unwrapShims(uri);
        if (isOn(target.getHost(), INTERNAL_HOSTS)) return false;
        // fbclid is Meta's click id, so the site it opens can tell Meta the link was followed. It
        // goes, and nothing else in the link changes.
        target = Uri.parse(LinkCleaner.withoutClickId(target.toString()));
        // What leaves is checked as it's sent, not only as it came: a web address with a host, or
        // the link stays where Instagram put it.
        if (!Utils.isWebLink(target)) return false;

        try {
            Intent view = new Intent(Intent.ACTION_VIEW, target);
            // Only an app that declares it opens web links takes it, as for any link from an app.
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            // The in-app browser closes, thus the link needs a task of its own to live in.
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(view);
        } catch (Throwable t) {
            // ActivityNotFoundException: the phone has no browser, or the browser is off.
            // SecurityException: the target that answered refused the launch. In both cases, leave
            // the link to Instagram and do not lose it. Only the class is reported: the exception's
            // own text quotes the intent, and the intent carries the link.
            final String kind = t.getClass().getSimpleName();
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "No external browser took the link (" + kind + "). It stays in the in-app browser.", null);
            return false;
        }

        activity.finish();
        return true;
    }

    /**
     * Whether Instagram opened the link for an ad, which stays in the app. A link followed inside a
     * page the browser already shows comes to {@code onNewIntent} with no context of its own:
     * Instagram copies the page's context onto it only after the hook has run, and the browser's
     * own intent is still the one it was opened with. So a link with no context takes the context
     * of the page it was followed from, and an ad's follow-up links stay with the ad. Extras that
     * can't be read count as an ad, so the link stays where Instagram put it.
     */
    @SuppressWarnings("deprecation")
    private static boolean isAd(Activity activity, Intent intent) {
        try {
            Object context = intent.getParcelableExtra(IAB_CONTEXT);
            Intent showing = context == null ? activity.getIntent() : null;
            if (showing != null && showing != intent) context = showing.getParcelableExtra(IAB_CONTEXT);
            for (Class<?> type = context == null ? null : context.getClass(); type != null; type = type.getSuperclass()) {
                if (ADS_CONTEXT.equals(type.getName())) return true;
            }
            return false;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.EXTERNAL_BROWSER, "link context", t);
            return true;
        }
    }

    /**
     * The HushGram switch. Off, unreadable, or asked before the settings are ready, the link
     * stays in the app.
     */
    private static boolean switchedOn() {
        try {
            return Utils.settingsReady() && Settings.OPEN_LINKS_EXTERNALLY.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.EXTERNAL_BROWSER, "switch read", t);
            Logger.diagnosticError(DiagnosticCategory.FEED_AND_NAVIGATION, SOURCE,
                    () -> "Could not read the external browser switch", t);
            return false;
        }
    }

    /** Whether {@code host} is one of {@code domains}, or a subdomain of one. */
    private static boolean isOn(String host, String[] domains) {
        if (host == null) return false;

        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) lower = lower.substring(0, lower.length() - 1);
        for (String domain : domains) {
            // The dot keeps "notinstagram.com" from a match with "instagram.com".
            if (lower.equals(domain) || lower.endsWith("." + domain)) return true;
        }

        return false;
    }
}
