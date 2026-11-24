/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Copy link in the menu you get when you hold a chat message with link buttons (#852), the kind
 * automated accounts and creator bots send under a text or a card, like "Website" or "Shop now".
 * A tap on such a button opens the page straight away, so there's no other way to see or copy
 * where it goes first.
 *
 * <p>Instagram builds the held message's menu from a model it makes for the message, and that
 * model already carries a list of extra rows some cards ask for. The patch hands that list and the
 * message to {@link #rows} as the model is made. With the switch on and the message holding at
 * least one button that goes to a web page, a Copy link row is added through Instagram's own row
 * model, so it looks and sits like the card rows. Its tap comes back to Instagram's dispatcher for
 * a card row with the text the row carries, and the patch asks {@link #picked} first there: the
 * row's text starts with {@link #PREFIX} and holds the buttons, so one link is copied at once and
 * several give a list to pick from. Instagram never opens the page.
 *
 * <p>Both hooks fail open: off, paused, before the settings are read or on anything thrown, the
 * menu is Instagram's, and any other row is left to Instagram.
 */
public final class ChatButtonLinks {
    /** What a Copy link row's text starts with, so its tap is told from Instagram's card rows. */
    static final String PREFIX = "hushgram:copy-link\n";

    /** The link icon of the row, and the copy icon when a build has no link icon. */
    static final String ICON = "instagram_link_pano_outline_24";
    static final String ICON_FALLBACK = "instagram_copy_pano_outline_24";

    /** The steps a throw is reported under. */
    static final String ROWS = "copy link row";
    static final String PICKED = "copy link tap";

    /** What Hook status counts. */
    static final String COUNT_ADDED = "Copy link added";
    static final String COUNT_COPIED = "link copied";

    /** Instagram's and Facebook's link redirects, whose u parameter is where a button really goes. */
    private static final String[] REDIRECTS = {"l.instagram.com", "l.facebook.com", "lm.facebook.com"};

    /** One button: its words and where it goes. */
    static final class Link {
        final String title;
        final String url;

        Link(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    /** Where {@link #rows} reads a message's buttons and makes a row. Tests hand in their own. */
    interface Parts {
        /** The buttons under a text message, or null. */
        @Nullable
        List<?> textButtons(Object message);

        /** The message's cards, or null. */
        @Nullable
        List<?> cards(Object message);

        /** A card's buttons, or null. */
        @Nullable
        List<?> cardButtons(Object card);

        @Nullable
        String title(Object button);

        @Nullable
        String url(Object button);

        /** Instagram's model of a card row with these words, icon and text, or null. */
        @Nullable
        Object row(String title, @Nullable Integer icon, String payload);

        /** The row icon's resource id, or null. */
        @Nullable
        Integer icon();
    }

    static final Parts PATCHED = new Parts() {
        @Override
        public List<?> textButtons(Object message) {
            return ChatButtonLinks.textButtons(message);
        }

        @Override
        public List<?> cards(Object message) {
            return ChatButtonLinks.cards(message);
        }

        @Override
        public List<?> cardButtons(Object card) {
            return ChatButtonLinks.cardButtons(card);
        }

        @Override
        public String title(Object button) {
            return ChatButtonLinks.buttonTitle(button);
        }

        @Override
        public String url(Object button) {
            return ChatButtonLinks.buttonUrl(button);
        }

        @Override
        public Object row(String title, Integer icon, String payload) {
            return ChatButtonLinks.row(title, icon, payload);
        }

        @Override
        public Integer icon() {
            return linkIcon(Utils.getContext());
        }
    };

    /** Copies a link and says so. Tests hand in their own. */
    interface Copier {
        void copy(Context context, String url);
    }

    static final Copier CLIPBOARD = (context, url) -> {
        Utils.setClipboard(context, L10n.t(context, "Copy link"), url);
        Utils.showToastShort(L10n.t(context, "Link copied"));
    };

    private ChatButtonLinks() {
    }

    /**
     * Injected where Instagram makes the model of a held message's menu, with the list of extra card
     * rows it made (null when there are none) and the message. Answers the list to use: a copy with
     * a Copy link row at the end when the switch is on and the message has a web link button, else
     * the list it was handed. Never throws.
     */
    @Nullable
    public static List<?> rows(@Nullable List<?> rows, Object message) {
        return rows(rows, message, PATCHED, ChatButtonLinks::switchedOn);
    }

    @Nullable
    static List<?> rows(@Nullable List<?> rows, Object message, Parts parts, BooleanSupplier on) {
        try {
            if (message == null || !on.getAsBoolean()) return rows;
            HookStatus.invoked(FamilyNames.CHAT_BUTTON_LINKS);
            List<Link> links = links(message, parts);
            if (links.isEmpty()) return rows;
            String title = L10n.t("Copy link");
            Object row = parts.row(title, parts.icon(), payload(links));
            if (row == null) return rows;
            List<Object> out = rows == null ? new ArrayList<>() : new ArrayList<>(rows);
            out.add(row);
            HookStatus.counted(FamilyNames.CHAT_BUTTON_LINKS, COUNT_ADDED);
            return out;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_BUTTON_LINKS, ROWS, failure);
            return rows;
        }
    }

    /**
     * Injected first in the dispatcher every row of a held message's menu reaches, the bottom row's
     * and the ones in the list More opens, and first in each row of a popup list More opens (those
     * hand the dispatcher More's text, not their own), with the chat's activity, the row's action and
     * the text the row carries. True when the row is a Copy link row, which this then handles: the
     * caller returns at once. Never throws.
     */
    public static boolean picked(@Nullable Activity activity, Object action, @Nullable String payload) {
        return picked(activity, payload, CLIPBOARD);
    }

    static boolean picked(@Nullable Activity activity, @Nullable String payload, Copier copier) {
        if (payload == null || !payload.startsWith(PREFIX)) return false;
        try {
            List<Link> links = decode(payload);
            Context context = activity != null ? activity : Utils.getContext();
            if (links.isEmpty() || context == null) return true;
            if (links.size() == 1 || activity == null) {
                copy(context, links.get(0).url, copier);
                return true;
            }
            String[] labels = new String[links.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = label(links.get(i));
            new AlertDialog.Builder(activity)
                    .setTitle(L10n.t(activity, "Copy link"))
                    .setItems(labels, (dialog, which) -> {
                        if (which >= 0 && which < links.size()) copy(activity, links.get(which).url, copier);
                    })
                    .show();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_BUTTON_LINKS, PICKED, failure);
        }
        return true;
    }

    private static void copy(Context context, String url, Copier copier) {
        copier.copy(context, url);
        HookStatus.counted(FamilyNames.CHAT_BUTTON_LINKS, COUNT_COPIED);
    }

    /** A list line: the button's words over where it goes, or just where it goes. */
    static String label(Link link) {
        return link.title.isEmpty() ? link.url : link.title + "\n" + link.url;
    }

    /** The message's buttons that go to a web page, text buttons first, each link once. */
    static List<Link> links(Object message, Parts parts) {
        List<Link> links = new ArrayList<>();
        add(links, parts.textButtons(message), parts);
        List<?> cards = parts.cards(message);
        if (cards != null) {
            for (Object card : cards) {
                if (card != null) add(links, parts.cardButtons(card), parts);
            }
        }
        return links;
    }

    private static void add(List<Link> links, @Nullable List<?> buttons, Parts parts) {
        if (buttons == null) return;
        for (Object button : buttons) {
            if (button == null) continue;
            String url = webLink(parts.url(button));
            if (url == null) continue;
            boolean seen = false;
            for (Link link : links) seen |= link.url.equals(url);
            if (seen) continue;
            String title = parts.title(button);
            links.add(new Link(oneLine(title), url));
        }
    }

    /**
     * Where [url] goes when it's a web page: the page a link redirect of Instagram's or Facebook's
     * sends you to, or the link itself. Null for anything else, like a link into the app.
     */
    @Nullable
    static String webLink(@Nullable String url) {
        if (url == null) return null;
        String link = url.trim();
        Uri uri = Uri.parse(link);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        for (String redirect : REDIRECTS) {
            if (!host.toLowerCase(Locale.ROOT).equals(redirect)) continue;
            String target = uri.getQueryParameter("u");
            String inner = target == null ? null : webLink(target);
            if (inner != null) return inner;
        }
        return link;
    }

    /** The row's text: {@link #PREFIX}, then a line per link, its words and the link apart by a tab. */
    static String payload(List<Link> links) {
        StringBuilder text = new StringBuilder(PREFIX);
        for (Link link : links) text.append(link.title).append('\t').append(link.url).append('\n');
        return text.toString();
    }

    /** The links in a row's text. */
    static List<Link> decode(String payload) {
        if (!payload.startsWith(PREFIX)) return Collections.emptyList();
        List<Link> links = new ArrayList<>();
        for (String line : payload.substring(PREFIX.length()).split("\n")) {
            int tab = line.lastIndexOf('\t');
            if (tab < 0) continue;
            String url = webLink(line.substring(tab + 1));
            if (url != null) links.add(new Link(line.substring(0, tab), url));
        }
        return links;
    }

    private static String oneLine(@Nullable String text) {
        if (text == null) return "";
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    @Nullable
    static Integer linkIcon(@Nullable Context context) {
        if (context == null) return null;
        try {
            Resources resources = context.getResources();
            String owner = Utils.resourcePackage(context);
            for (String name : new String[]{ICON, ICON_FALLBACK}) {
                int id = resources.getIdentifier(name, "drawable", owner);
                if (id != 0) return id;
            }
        } catch (Throwable ignored) {
            // No icon: the row shows without one.
        }
        return null;
    }

    /** Whether the switch is on: off while paused and before the settings are read. */
    static boolean switchedOn() {
        return Utils.settingsReady() && Settings.COPY_CHAT_BUTTON_LINKS.get();
    }

    // ------------------------------------------------------------------ what the patch fills in

    /** Filled in by the patch: the buttons under a text message, or null. */
    @Nullable
    public static List<?> textButtons(Object message) {
        return null;
    }

    /** Filled in by the patch: a message's cards, or null. */
    @Nullable
    public static List<?> cards(Object message) {
        return null;
    }

    /** Filled in by the patch: a card's buttons, or null. */
    @Nullable
    public static List<?> cardButtons(Object card) {
        return null;
    }

    /** Filled in by the patch: a button's words, or null. */
    @Nullable
    public static String buttonTitle(Object button) {
        return null;
    }

    /** Filled in by the patch: where a button goes, or null. */
    @Nullable
    public static String buttonUrl(Object button) {
        return null;
    }

    /** Filled in by the patch: Instagram's model of a card row, or null. */
    @Nullable
    public static Object row(String title, @Nullable Integer icon, String payload) {
        return null;
    }
}
