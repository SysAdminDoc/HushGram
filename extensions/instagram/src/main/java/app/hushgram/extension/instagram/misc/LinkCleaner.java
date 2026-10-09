/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.misc;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.instagram.share.SharingDomain;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Takes Instagram's tracking keys out of its own links, offline.
 *
 * <p>Only the query changes, and only by losing whole {@code key=value} pairs whose key is on the
 * list below, on an Instagram host. Everything else stays as it was written: the other pairs in
 * their order, how each value is encoded, the fragment, and links to any other site. So a link
 * that carries no tracking key comes back as the same string, and cleaning a clean link changes
 * nothing.
 *
 * <p>Three ways in: the two share-link parsers hand their link here as Instagram reads it from
 * the server, and every clipboard copy, share sheet and share Instagram sends straight to one app
 * goes through the stand-ins below, which clean the Instagram links in the text on its way out,
 * and move the links to instagram.com to the Sharing domain when one is set ({@link SharingDomain}).
 * The same activity-start stand-ins send a link opened from a bio straight to its page, not
 * through Instagram's click tracker. Instagram's in-app browser hands the address of the page
 * you're on to {@link #browserLink} when you tap Share or Copy link in its menu, and that's the one
 * place a link to another site loses keys too: Meta's click id and the utm_ keys an ad's page opens
 * with. Nothing here goes online.
 */
public final class LinkCleaner {

    private LinkCleaner() {}

    /*
     * igsh, igshid and igsi are the keys Instagram 449 itself lists as its share-tracking keys
     * (the "igsh,igshid,igsi" default it strips from links it opens). igsh is a per-share id that
     * ties a link back to the account that shared it. The utm_ keys only label where a click came
     * from (ig_web_copy_link, ig_story_item_share, qr), and fbclid is Meta's click id. stkn is
     * the newer per-share id: on 449, Copy link and Share both gave /p/<code>/?stkn=<base64 id>,
     * a different id each time, and no igsh. The server adds it, so the app's own code never
     * names it. None of them picks what a link opens: the path does (/p/, /reel/, /stories/, a
     * username).
     */
    private static final Set<String> TRACKING = keys("igsh", "igshid", "igsi", "stkn", "fbclid",
            "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term", "utm_id");

    /*
     * The per-share id again, moved. On Instagram 450 (builds 385611438 and 385611440, 2026-10-08)
     * Copy link on a post or reel, and a profile's Copy profile URL, end in one pair like
     * ?obrf=a2k3NmE5MnUzcGFi, ?dlrf=..., ?exln=... or ?mdxt=...: the key is four random lowercase
     * letters that change on every copy, the value is base64 of a short lowercase id (a 12 to 14
     * character [a-z0-9] string). The server adds it and the app's code names none of the keys,
     * so no fixed list can hold them. {@link #isRotatingShareId} matches the shape instead.
     */

    /**
     * Real four-letter keys Instagram or its pages use. The value test below is the main guard,
     * so this only covers a key that could ever carry a base64 id by chance.
     */
    private static final Set<String> REAL_FOUR_LETTER_KEYS = keys("next", "lang", "page", "from", "type",
            "mode", "text", "code", "name", "user", "view");

    /** Meta's click id alone, which a link to another site loses on its way out of the in-app browser. */
    private static final Set<String> CLICK_ID = keys("fbclid");

    /**
     * What the in-app browser's Share and Copy link take off the page's address, on any site: Meta's
     * click id and every utm_ key. An ad's page opens with them, naming its campaign, ad set and
     * placement. Keys the advertiser named itself stay, since a page may need them.
     */
    private static boolean isAdKey(String key) {
        return key.equals("fbclid") || key.startsWith("utm_");
    }

    /**
     * The link shims Instagram 449 itself reads a destination out of, by exact host: a link in a
     * bio opens as {@code https://l.instagram.com/?u=<the page>&e=<a click token>},
     * which records the tap and forwards to the page.
     */
    private static final Set<String> SHIM_HOSTS = keys("l.instagram.com", "l.facebook.com", "l.alpha.facebook.com",
            "lm.alpha.facebook.com");

    /** The schemes a text message opens with, and the extra its text travels in. */
    private static final Set<String> MESSAGE_SCHEMES = keys("sms", "smsto", "mms", "mmsto");
    private static final String SMS_BODY = "sms_body";

    /** How many shims deep a link is followed before it's opened as it is. */
    private static final int MAX_SHIMS = 4;

    /** Instagram's hosts, each with its subdomains. */
    private static final String[] INSTAGRAM_HOSTS = {"instagram.com", "instagr.am", "ig.me"};

    /**
     * A web link in running text. It stops before trailing punctuation, so the full stop after a
     * link in a sentence isn't taken for part of its last value.
     */
    private static final Pattern WEB_LINK = Pattern.compile("(?i)https?://[^\\s<>\"']*[^\\s<>\"'.,!?;:]");

    /**
     * Injected where Instagram reads a share link from the server. Answers the link without its
     * tracking keys, or as it came while the switch is off, HushGram is paused or the settings
     * aren't ready yet. Never throws.
     */
    public static String sanitizeShared(String url) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        return enabled() ? clean(url) : url;
    }

    /**
     * Injected where Instagram's handler for its in-app browser's menu takes the page's address for
     * Share or Copy link, before it goes into the share sheet's text or to the clipboard. Answers the
     * address without Meta's click id and the utm_ keys, on any site, and an Instagram link without
     * Instagram's tracking keys as well, or as it came while the switch is off, HushGram is paused or
     * the settings aren't ready yet. The page itself still loads with them. Never throws.
     */
    public static String browserLink(String url) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        if (url == null || !enabled()) return url;
        try {
            return withoutKeys(cleaned(url), pair -> isAdKey(keyOf(pair)), false);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "in-app browser menu", t);
            return url;
        }
    }

    /** Stands in for {@link ClipboardManager#setPrimaryClip}: the same clip with its Instagram links cleaned. */
    public static void setPrimaryClip(ClipboardManager manager, ClipData clip) {
        manager.setPrimaryClip(sanitizedClip(clip));
    }

    /** Stands in for {@link Intent#createChooser(Intent, CharSequence)}, cleaning the shared text first. */
    public static Intent createChooser(Intent target, CharSequence title) {
        return Intent.createChooser(sanitizedShare(target), title);
    }

    /** Stands in for {@link Intent#createChooser(Intent, CharSequence, IntentSender)}, cleaning the shared text first. */
    public static Intent createChooser(Intent target, CharSequence title, IntentSender sender) {
        return Intent.createChooser(sanitizedShare(target), title, sender);
    }

    /**
     * Stands in for {@link Context#startActivity(Intent)}. A share Instagram sends straight to one
     * app, with no share sheet in between, and a text message it opens go with their Instagram
     * links cleaned, and a page opened through a link shim opens directly. Every other intent goes
     * as it came.
     */
    public static void startActivity(Context context, Intent intent) {
        context.startActivity(sanitizedStart(intent));
    }

    /** Stands in for {@link Context#startActivity(Intent, Bundle)}, the same way. */
    public static void startActivity(Context context, Intent intent, Bundle options) {
        context.startActivity(sanitizedStart(intent), options);
    }

    /** [intent], cleaned in place when it's a share, a text message or opens a link shim. */
    static Intent sanitizedStart(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) return sanitizedShare(intent);
        if (isMessage(intent)) return sanitizedMessage(intent);
        return unwrappedShim(intent);
    }

    /**
     * Whether [intent] opens a text message: the SMS button in Instagram's share sheet starts
     * {@code sms:} with the link in {@link #SMS_BODY}.
     */
    private static boolean isMessage(Intent intent) {
        Uri data = intent.getData();
        String scheme = data == null ? null : data.getScheme();
        return scheme != null && MESSAGE_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }

    /** [intent] with the Instagram links in its message text cleaned. */
    static Intent sanitizedMessage(Intent intent) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        if (!enabled()) return intent;
        try {
            CharSequence text = intent.getCharSequenceExtra(SMS_BODY);
            if (text == null) return intent;
            String cleaned = cleanText(text.toString());
            if (!cleaned.contentEquals(text)) intent.putExtra(SMS_BODY, cleaned);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "text message", t);
        }
        return intent;
    }

    /**
     * [intent] with the shim address it opens swapped for the page the shim forwards to, keeping
     * its type, component and extras, or as it came when it opens no shim, the switch is off, or
     * anything goes wrong.
     */
    static Intent unwrappedShim(Intent intent) {
        try {
            Uri data = intent.getData();
            if (data == null || shimDestination(data) == null) return intent;
            HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
            if (!enabled()) return intent;
            intent.setDataAndType(unwrapShims(data), intent.getType());
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "link shim", t);
        }
        return intent;
    }

    /**
     * The page {@code uri} forwards to through every link shim it's wrapped in, or {@code uri}
     * itself when it's no shim. Whatever the switch says: {@link ExternalBrowser} asks too.
     */
    static Uri unwrapShims(Uri uri) {
        Uri target = uri;
        for (int depth = 0; depth < MAX_SHIMS; depth++) {
            Uri inner = shimDestination(target);
            if (inner == null) break;
            target = inner;
        }
        return target;
    }

    /**
     * The page {@code uri} forwards to when it's one of {@link #SHIM_HOSTS}, read from its "u" the
     * way Instagram reads it, or null when it's no shim or forwards to anything but a web page.
     */
    private static Uri shimDestination(Uri uri) {
        if (uri.isOpaque() || !isWebScheme(uri.getScheme())) return null;
        String host = uri.getHost();
        if (host == null || !SHIM_HOSTS.contains(host.toLowerCase(Locale.ROOT))) return null;
        String wrapped = uri.getQueryParameter("u");
        if (wrapped == null) return null;
        Uri target = Uri.parse(wrapped);
        String targetHost = target.getHost();
        return isWebScheme(target.getScheme()) && targetHost != null && !targetHost.isEmpty() ? target : null;
    }

    private static boolean isWebScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * [clip] with the Instagram links in its plain-text and URI-only items cleaned, or [clip]
     * itself when there's nothing to clean, the switch is off, or anything goes wrong. A clip with
     * an intent, HTML, or mixed fields in one item is left as it came.
     */
    static ClipData sanitizedClip(ClipData clip) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        if (clip == null || !enabled()) return clip;
        try {
            int count = clip.getItemCount();
            String domain = SharingDomain.chosen();
            List<ClipData.Item> items = new ArrayList<>(count);
            boolean changed = false;
            for (int i = 0; i < count; i++) {
                ClipData.Item item = clip.getItemAt(i);
                CharSequence text = item.getText();
                Uri uri = item.getUri();
                if (item.getIntent() != null || item.getHtmlText() != null) {
                    items.add(item);
                    continue;
                }
                if (text != null && uri == null) {
                    String cleaned = cleanText(text.toString());
                    if (cleaned.contentEquals(text)) {
                        items.add(item);
                    } else {
                        items.add(new ClipData.Item(cleaned));
                        changed = true;
                    }
                    continue;
                }
                if (uri != null && text == null) {
                    String original = uri.toString();
                    String cleaned = SharingDomain.moved(clean(original), domain);
                    if (cleaned.equals(original)) {
                        items.add(item);
                    } else {
                        items.add(new ClipData.Item(Uri.parse(cleaned)));
                        changed = true;
                    }
                    continue;
                }
                items.add(item);
            }
            if (!changed || items.isEmpty()) return clip;
            ClipData copy = new ClipData(clip.getDescription(), items.get(0));
            for (int i = 1; i < items.size(); i++) copy.addItem(items.get(i));
            return copy;
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "clipboard", t);
            return clip;
        }
    }

    /**
     * [target] with the Instagram links in its shared text cleaned, in place, or as it came when
     * there's nothing to clean, the switch is off, or anything goes wrong.
     */
    static Intent sanitizedShare(Intent target) {
        HookStatus.invoked(FamilyNames.SANITIZE_SHARING_LINKS);
        if (target == null || !enabled()) return target;
        try {
            CharSequence text = target.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text == null) return target;
            String cleaned = cleanText(text.toString());
            if (!cleaned.contentEquals(text)) target.putExtra(Intent.EXTRA_TEXT, cleaned);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "share sheet", t);
        }
        return target;
    }

    private static boolean enabled() {
        try {
            return Utils.settingsReady() && Settings.SANITIZE_SHARING_LINKS.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.SANITIZE_SHARING_LINKS, "switch read", t);
            return false;
        }
    }

    /**
     * [text] with every Instagram link in it cleaned, and each link to instagram.com on the Sharing
     * domain when one is set. The rest of the text stays as it was.
     */
    static String cleanText(String text) {
        if (text.indexOf("://") < 0) return text;
        String domain = SharingDomain.chosen();
        Matcher links = WEB_LINK.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (links.find()) {
            String link = links.group();
            String cleaned = SharingDomain.moved(clean(link), domain);
            if (cleaned.equals(link)) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, last, links.start()).append(cleaned);
            last = links.end();
        }
        if (out == null) return text;
        return out.append(text, last, text.length()).toString();
    }

    /**
     * {@code url} without the tracking keys, or {@code url} itself when it has none, isn't an
     * Instagram web link, or can't be read. Never throws.
     */
    public static String clean(String url) {
        if (url == null) return null;
        try {
            return cleaned(url);
        } catch (Throwable t) {
            // A link this can't take apart goes out as it came.
            return url;
        }
    }

    /**
     * {@code url} without Meta's click id, fbclid, on any host, or {@code url} itself when it has
     * none or can't be read. The one key {@link ExternalBrowser} takes off a link to another site.
     * Never throws.
     */
    static String withoutClickId(String url) {
        if (url == null) return null;
        try {
            return withoutKeys(url, pair -> CLICK_ID.contains(keyOf(pair)), false);
        } catch (Throwable t) {
            return url;
        }
    }

    private static String cleaned(String url) {
        return withoutKeys(url, pair -> TRACKING.contains(keyOf(pair)) || isRotatingShareId(pair), true);
    }

    /**
     * Whether {@code pair} is the per-share id Instagram 450 adds under a rotating key: a key of
     * exactly four lowercase ASCII letters that isn't a real key, and a value that is base64 of
     * 8 to 24 characters of [a-z0-9]. Both halves have to fit, and the pair is taken off an
     * Instagram link only. A value that isn't base64, or decodes to anything else, stays.
     */
    static boolean isRotatingShareId(String pair) {
        int equals = pair.indexOf('=');
        if (equals != 4) return false;
        for (int i = 0; i < 4; i++) {
            char c = pair.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        if (REAL_FOUR_LETTER_KEYS.contains(pair.substring(0, 4))) return false;
        String value = percentDecoded(pair.substring(5));
        if (!value.matches("[A-Za-z0-9+/]{8,40}={0,2}")) return false;
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length < 8 || decoded.length > 24) return false;
            for (byte b : decoded) {
                if (!((b >= 'a' && b <= 'z') || (b >= '0' && b <= '9'))) return false;
            }
            return true;
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    /**
     * {@code value} with each valid %XX turned into its character, so the padding and the + and /
     * of a base64 id read the same whether the link escaped them or not. A % that doesn't start
     * two hex digits stays as it is, which keeps the value from passing as base64. Only the
     * shape test reads this; the link itself is rewritten from the pairs as written.
     */
    private static String percentDecoded(String value) {
        if (value.indexOf('%') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && Character.digit(value.charAt(i + 1), 16) >= 0
                    && Character.digit(value.charAt(i + 2), 16) >= 0) {
                out.append((char) Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * {@code url} without the pairs {@code drop} picks (given a pair as written), on an Instagram
     * host alone when {@code instagramOnly}.
     */
    private static String withoutKeys(String url, Predicate<String> drop, boolean instagramOnly) {
        int colon = url.indexOf(':');
        if (colon <= 0) return url;
        String scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return url;

        // The fragment starts at the first '#', and a '?' after it is part of the fragment.
        int fragment = url.indexOf('#');
        int end = fragment < 0 ? url.length() : fragment;
        int query = url.indexOf('?');
        if (query < 0 || query > end) return url;
        if (instagramOnly && !isInstagramHost(host(url, colon + 1, query))) return url;

        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String pair : url.substring(query + 1, end).split("&", -1)) {
            if (drop.test(pair)) {
                removed = true;
            } else {
                kept.add(pair);
            }
        }
        if (!removed) return url;

        String rest = String.join("&", kept);
        return url.substring(0, rest.isEmpty() ? query : query + 1) + rest + url.substring(end);
    }

    /** The key of a {@code key=value} pair, decoded and lower-cased, or as written when it doesn't decode. */
    private static String keyOf(String pair) {
        int equals = pair.indexOf('=');
        String key = equals < 0 ? pair : pair.substring(0, equals);
        if (key.indexOf('%') >= 0) {
            try {
                key = URLDecoder.decode(key.replace("+", "%2B"), "UTF-8");
            } catch (Exception malformed) {
                // Kept as written.
            }
        }
        return key.toLowerCase(Locale.ROOT);
    }

    /**
     * The host between {@code //} and the path, the query or the port, lower-cased, or
     * {@code null} when the link has no authority.
     */
    private static String host(String url, int from, int query) {
        if (!url.startsWith("//", from)) return null;
        int start = from + 2;
        int stop = query;
        int slash = url.indexOf('/', start);
        if (slash >= 0 && slash < stop) stop = slash;
        String authority = url.substring(start, stop);
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close < 0 ? host : host.substring(0, close + 1);
        } else {
            int port = host.indexOf(':');
            if (port >= 0) host = host.substring(0, port);
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.toLowerCase(Locale.ROOT);
    }

    private static boolean isInstagramHost(String host) {
        if (host == null) return false;
        for (String domain : INSTAGRAM_HOSTS) {
            // The dot keeps "notinstagram.com" from matching "instagram.com".
            if (host.equals(domain) || host.endsWith("." + domain)) return true;
        }
        return false;
    }

    private static Set<String> keys(String... keys) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(keys)));
    }
}
