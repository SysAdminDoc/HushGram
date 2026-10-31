/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Helper for the "App-only proxy" patch.
 *
 * <p>Instagram's network stack asks {@code TigonServiceLayer.getProxyHostAndPort()} where to send
 * its requests, and that reads the standard Java proxy properties, the ones Android sets from a
 * Wi-Fi network's proxy. The patch calls {@link #route()} first thing there, so while the switch is
 * on and a server is set those properties name that server, inside Instagram only: other apps and
 * the phone's own network settings never see it. Java's own proxy selector reads the same
 * properties, so HushGram's downloads go the same way.
 *
 * <p>With the switch off, HushGram paused, the settings not read yet, no server set or anything
 * thrown, the properties stay as Android set them.
 */
public final class AppProxy {
    /** What's counted each time Instagram is pointed at the set server. */
    static final String ROUTED = "proxy set";

    /** The step a failure is reported under. */
    static final String ROUTE = "proxy route";

    /** The properties Instagram reads, the HTTPS pair first, as Android writes them. */
    static final String[] HOST_PROPERTIES = {"https.proxyHost", "http.proxyHost"};
    static final String[] PORT_PROPERTIES = {"https.proxyPort", "http.proxyPort"};

    /** One part of a host name: letters, digits and hyphens, not starting or ending with a hyphen. */
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern HOST = Pattern.compile(LABEL + "(?:\\." + LABEL + ")*");
    private static final Pattern NUMBERS = Pattern.compile("[0-9.]+");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    private AppProxy() {
    }

    /** Called first thing where Instagram reads its proxy. */
    public static void route() {
        route(AppProxy::server);
    }

    static void route(Supplier<String> server) {
        try {
            HookStatus.invoked(FamilyNames.APP_PROXY);
            String set = server.get();
            if (set == null) return;
            int colon = set.lastIndexOf(':');
            String host = set.substring(0, colon);
            String port = set.substring(colon + 1);
            for (String property : HOST_PROPERTIES) System.setProperty(property, host);
            for (String property : PORT_PROPERTIES) System.setProperty(property, port);
            HookStatus.counted(FamilyNames.APP_PROXY, ROUTED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.APP_PROXY, ROUTE, failure);
        }
    }

    /** The server Instagram goes through, as host:port, or null while the switch is off or none is set. */
    @Nullable
    private static String server() {
        if (!Utils.settingsReady() || !Settings.APP_PROXY.get()) return null;
        String server = normalize(Settings.APP_PROXY_SERVER.get());
        return server == null || server.isEmpty() ? null : server;
    }

    /**
     * The server in [text] as host:port, lower case, with an http:// in front or a / after taken
     * off, "" when [text] is blank, or null when it isn't a server. The host is a name or an IPv4
     * address and the port a number from 1 to 65535. A user name, a password or another scheme
     * (https, socks) isn't taken, since Instagram can't use them.
     */
    @Nullable
    public static String normalize(@Nullable String text) {
        if (text == null) return null;
        String server = text.trim();
        if (server.isEmpty()) return "";
        if (server.regionMatches(true, 0, "http://", 0, 7)) server = server.substring(7);
        if (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        int colon = server.indexOf(':');
        if (colon <= 0 || colon != server.lastIndexOf(':')) return null;
        String host = server.substring(0, colon).toLowerCase(Locale.ROOT);
        String port = server.substring(colon + 1);
        if (host.length() > 253 || !HOST.matcher(host).matches() || !PORT.matcher(port).matches()) return null;
        if (NUMBERS.matcher(host).matches() && !ipv4(host)) return null;
        int number = Integer.parseInt(port);
        if (number < 1 || number > 65535) return null;
        return host + ":" + number;
    }

    /** Whether [host], all digits and dots, is four numbers from 0 to 255. */
    private static boolean ipv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
}
