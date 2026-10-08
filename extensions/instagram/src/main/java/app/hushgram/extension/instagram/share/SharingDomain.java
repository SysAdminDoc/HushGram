/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.share;

import java.util.Locale;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Sanitize sharing links' Sharing domain: links to instagram.com that you copy or share go out on
 * a domain you pick, such as a site that shows Instagram posts and reels in chat apps.
 *
 * <p>Only the host changes, and only on {@code instagram.com} and {@code www.instagram.com}. The
 * scheme, the path, what's left of the query once the tracking keys are gone, and the fragment stay
 * as they were. Instagram's other hosts (its link shim, {@code ig.me}, {@code instagr.am}), any
 * other site, and a link with a port or a user name in it go out as they came. Nothing here goes
 * online, and nothing changes while the row is blank.
 */
public final class SharingDomain {
    /** The longest a domain may be. */
    private static final int MAX_LENGTH = 253;

    /** One label of a domain: letters, digits and hyphens, not starting or ending with a hyphen. */
    private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    /** The hosts whose links move, each written exactly. */
    private static final String[] MOVED = {"instagram.com", "www.instagram.com"};

    private SharingDomain() {
    }

    /**
     * [typed] as a bare domain: trimmed, lower-cased, without an http or https scheme in front or a
     * slash or dot at the end. Blank answers "", and anything that isn't a domain of two or more
     * labels answers null. A top-level label of digits alone is refused, which keeps an IP address
     * out.
     */
    public static String normalize(String typed) {
        if (typed == null) return "";
        String text = typed.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("https://")) {
            text = text.substring("https://".length());
        } else if (text.startsWith("http://")) {
            text = text.substring("http://".length());
        }
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        if (text.isEmpty()) return "";
        if (text.length() > MAX_LENGTH) return null;
        String[] labels = text.split("\\.", -1);
        if (labels.length < 2) return null;
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) return null;
        }
        String top = labels[labels.length - 1];
        for (int i = 0; i < top.length(); i++) {
            if (!Character.isDigit(top.charAt(i))) return text;
        }
        return null;
    }

    /**
     * The domain links go out on, as saved and normalized, or "" when there's none, what's saved
     * isn't a domain, or the settings can't be read. Never throws.
     */
    public static String chosen() {
        try {
            if (!Utils.settingsReady()) return "";
            String domain = normalize(Settings.SHARING_DOMAIN.get());
            return domain == null ? "" : domain;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "sharing domain", failure);
            return "";
        }
    }

    /**
     * [link] with its host swapped for [domain] when it's an http or https link to one of the
     * moved hosts, or [link] as it came otherwise, and always when [domain] is blank.
     */
    public static String moved(String link, String domain) {
        if (link == null || domain == null || domain.isEmpty()) return link;
        int colon = link.indexOf(':');
        if (colon <= 0) return link;
        String scheme = link.substring(0, colon);
        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return link;
        if (!link.startsWith("//", colon + 1)) return link;
        int start = colon + 3;
        int stop = link.length();
        for (char end : new char[]{'/', '?', '#'}) {
            int at = link.indexOf(end, start);
            if (at >= 0 && at < stop) stop = at;
        }
        String host = link.substring(start, stop).toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        for (String moved : MOVED) {
            if (moved.equals(host)) return scheme + "://" + domain + link.substring(stop);
        }
        return link;
    }
}
