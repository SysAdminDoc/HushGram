/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.shared;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;

import org.robolectric.RuntimeEnvironment;

import java.util.Map;

/**
 * Instagram as Install next to Instagram leaves it: the app is renamed com.instagram.android.hush,
 * and its theme and every other resource are still named under com.instagram.android, where a
 * lookup by name has to go (#431).
 */
public final class RenamedCopyForTests {
    public static final String INSTAGRAM = "com.instagram.android";
    public static final String COPY = INSTAGRAM + ".hush";
    /** The copy's theme, one of Instagram's own resources. */
    private static final int THEME = 0x7f150001;

    private RenamedCopyForTests() {
    }

    /** The copy's context, whose resources answer [ids] ("type/name" to number) under Instagram's name only. */
    public static Context context(Map<String, Integer> ids) {
        Context app = RuntimeEnvironment.getApplication();
        Resources table = new Resources(app.getAssets(), app.getResources().getDisplayMetrics(), app.getResources().getConfiguration()) {
            @Override
            public int getIdentifier(String name, String type, String defPackage) {
                Integer id = INSTAGRAM.equals(defPackage) ? ids.get(type + "/" + name) : null;
                return id == null ? 0 : id;
            }

            @Override
            public String getResourcePackageName(int id) {
                return id >>> 24 == 0x7f ? INSTAGRAM : super.getResourcePackageName(id);
            }
        };
        ApplicationInfo info = new ApplicationInfo(app.getApplicationInfo());
        info.packageName = COPY;
        info.theme = THEME;
        return new ContextWrapper(app) {
            @Override
            public Resources getResources() {
                return table;
            }

            @Override
            public String getPackageName() {
                return COPY;
            }

            @Override
            public ApplicationInfo getApplicationInfo() {
                return info;
            }
        };
    }
}
