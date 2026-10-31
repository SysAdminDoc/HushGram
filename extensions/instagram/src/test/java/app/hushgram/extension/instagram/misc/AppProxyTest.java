/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.SettingsContextRule;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.BaseSettings;
import app.hushgram.extension.shared.settings.HushgramPause;
import app.hushgram.extension.shared.settings.PauseForTests;

/** App-only proxy: while it's on with a server set, Instagram's proxy properties name that server, and otherwise they stay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 37})
public class AppProxyTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    /** What the phone's Wi-Fi proxy left in the properties, standing in for Android's own. */
    private static final String PHONE_HOST = "10.0.0.1";
    private static final String PHONE_PORT = "3128";

    private final Map<String, String> before = new HashMap<>();

    private static String[] properties() {
        String[] all = new String[AppProxy.HOST_PROPERTIES.length + AppProxy.PORT_PROPERTIES.length];
        System.arraycopy(AppProxy.HOST_PROPERTIES, 0, all, 0, AppProxy.HOST_PROPERTIES.length);
        System.arraycopy(AppProxy.PORT_PROPERTIES, 0, all, AppProxy.HOST_PROPERTIES.length, AppProxy.PORT_PROPERTIES.length);
        return all;
    }

    @Before
    public void enable() {
        for (String property : properties()) before.put(property, System.getProperty(property));
        for (String property : AppProxy.HOST_PROPERTIES) System.setProperty(property, PHONE_HOST);
        for (String property : AppProxy.PORT_PROPERTIES) System.setProperty(property, PHONE_PORT);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        Settings.APP_PROXY.save(true);
        Settings.APP_PROXY_SERVER.save("Proxy.Example.com:8080");
        HookStatus.clear();
    }

    @After
    public void restore() {
        for (Map.Entry<String, String> entry : before.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
        Settings.APP_PROXY.resetToDefault();
        Settings.APP_PROXY_SERVER.resetToDefault();
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static void assertRoutedTo(String host, String port) {
        for (String property : AppProxy.HOST_PROPERTIES) assertEquals(property, host, System.getProperty(property));
        for (String property : AppProxy.PORT_PROPERTIES) assertEquals(property, port, System.getProperty(property));
    }

    @Test
    public void onWithAServerPointsInstagramAtIt() {
        AppProxy.route();
        assertRoutedTo("proxy.example.com", "8080");

        Settings.APP_PROXY_SERVER.save("http://192.168.1.20:3128/");
        AppProxy.route();
        assertRoutedTo("192.168.1.20", "3128");

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(AppProxy.ROUTED + " 2"));
        assertTrue(HookStatus.missing(FamilyNames.APP_PROXY).isEmpty());
    }

    @Test
    public void offPausedUnreadyOrWithNoServerLeavesThePhonesProxy() {
        Settings.APP_PROXY.save(false);
        AppProxy.route();
        assertRoutedTo(PHONE_HOST, PHONE_PORT);
        Settings.APP_PROXY.save(true);

        BaseSettings.PAUSED.save(true);
        PauseForTests.pause(HushgramPause.Reason.SWITCH);
        AppProxy.route();
        assertRoutedTo(PHONE_HOST, PHONE_PORT);
        BaseSettings.PAUSED.save(false);
        PauseForTests.resume();

        SettingsContextRule.withoutContext(AppProxy::route);
        assertRoutedTo(PHONE_HOST, PHONE_PORT);

        for (String server : new String[]{"", "   ", "socks5://proxy.example.com:1080", "user:pass@proxy.example.com:8080"}) {
            Settings.APP_PROXY_SERVER.save(server);
            AppProxy.route();
            assertRoutedTo(PHONE_HOST, PHONE_PORT);
        }
        String report = String.join("\n", HookStatus.report());
        assertFalse(report, report.contains(AppProxy.ROUTED));
    }

    @Test
    public void aFailureLeavesThePhonesProxyAndIsReported() {
        AppProxy.route(() -> {
            throw new IllegalStateException("settings went away");
        });
        assertRoutedTo(PHONE_HOST, PHONE_PORT);
        assertFalse(HookStatus.missing(FamilyNames.APP_PROXY).isEmpty());
    }

    @Test
    public void aServerIsAHostAndAPort() {
        assertEquals("proxy.example.com:8080", AppProxy.normalize("  Proxy.Example.COM:8080 "));
        assertEquals("proxy.example.com:8080", AppProxy.normalize("HTTP://proxy.example.com:8080/"));
        assertEquals("localhost:1", AppProxy.normalize("localhost:1"));
        assertEquals("127.0.0.1:65535", AppProxy.normalize("127.0.0.1:65535"));
        assertEquals("my-proxy.lan:80", AppProxy.normalize("my-proxy.lan:080"));
        assertEquals("", AppProxy.normalize(""));
        assertEquals("", AppProxy.normalize("  "));
        assertNull(AppProxy.normalize(null));
        for (String text : new String[]{
            "proxy.example.com", ":8080", "proxy.example.com:", "proxy.example.com:0", "proxy.example.com:65536",
            "proxy.example.com:80a", "proxy.example.com:123456", "https://proxy.example.com:8080",
            "socks5://proxy.example.com:1080", "user:pass@proxy.example.com:8080", "-proxy.example.com:8080",
            "proxy-.example.com:8080", "proxy..example.com:8080", "proxy example.com:8080", "256.1.1.1:8080",
            "1.2.3:8080", "1.2.3.4.5:8080", "[::1]:8080", "proxy.example.com:8080//",
        }) {
            assertNull(text, AppProxy.normalize(text));
        }
    }
}
