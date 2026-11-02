/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.HushgramPause;

/**
 * Helper for the "Chat plus menu" patch (#448): takes Saved, AI images and Draw off the menu the plus
 * button of a chat's message bar opens, each by its own switch, and can move the bar's gallery and
 * microphone buttons into that menu.
 *
 * <p>Instagram builds a map from each button's enum constant to the item that draws it, then fills
 * the plus menu's list with the items of the constants the menu shows. The patch hands this the
 * finished menu list and that map. A row is told apart by its enum constant's name, read from the
 * map's keys, so no obfuscated name is written here. A moved button is the bar's own item, so a tap
 * runs exactly what the bar's button runs.
 *
 * <p>Fails open: paused, settings not read, a list that can't change or anything thrown leaves the
 * menu as Instagram built it.
 */
public final class PlusMenu {
    /** Instagram's enum names of the menu rows the switches take off. */
    static final String SAVED = "CROSS_APP_SAVES";
    static final String AI_IMAGES = "IMAGINE";
    static final String DRAW = "DOODLES";
    static final String GALLERY = "GALLERY";
    static final String VOICE = "VOICE_MESSAGE";

    /** What's counted for each row taken out of, or put into, a menu. */
    static final String REMOVED = "plus menu rows removed";
    static final String MOVED = "plus menu buttons moved";

    private PlusMenu() {
    }

    /**
     * Injected where the loop that fills the plus menu's list exits, handed that list and the map from
     * button to item. Changes the list in place. Never throws, and never waits for the settings.
     */
    public static void menu(@Nullable List<?> menu, @Nullable Map<?, ?> byButton) {
        if (menu == null || byButton == null) return;
        try {
            if (!Utils.settingsReady() || HushgramPause.isPaused()) return;
            Set<String> out = new HashSet<>();
            if (Settings.HIDE_PLUS_SAVED.get()) out.add(SAVED);
            if (Settings.HIDE_PLUS_AI_IMAGES.get()) out.add(AI_IMAGES);
            if (Settings.HIDE_PLUS_DRAW.get()) out.add(DRAW);
            boolean move = Settings.MOVE_TO_PLUS_MENU.get();
            if (out.isEmpty() && !move) return;
            HookStatus.invoked(FamilyNames.PLUS_MENU);
            @SuppressWarnings("unchecked")
            List<Object> rows = (List<Object>) menu;
            Object gallery = null;
            Object voice = null;
            for (Map.Entry<?, ?> entry : byButton.entrySet()) {
                if (!(entry.getKey() instanceof Enum<?>)) continue;
                String name = ((Enum<?>) entry.getKey()).name();
                if (out.contains(name)) {
                    takeOut(rows, entry.getValue());
                } else if (GALLERY.equals(name)) {
                    gallery = entry.getValue();
                } else if (VOICE.equals(name)) {
                    voice = entry.getValue();
                }
            }
            if (move) {
                putIn(rows, gallery);
                putIn(rows, voice);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PLUS_MENU, "plus menu", failure);
        }
    }

    /** Whether the gallery and microphone buttons are meant to be in the menu, so the bar leaves them out. */
    public static boolean moved() {
        try {
            return Utils.settingsReady() && !HushgramPause.isPaused() && Settings.MOVE_TO_PLUS_MENU.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    private static void takeOut(List<Object> rows, @Nullable Object item) {
        if (item == null) return;
        for (Iterator<Object> it = rows.iterator(); it.hasNext(); ) {
            if (it.next() == item) {
                it.remove();
                HookStatus.counted(FamilyNames.PLUS_MENU, REMOVED);
            }
        }
    }

    private static void putIn(List<Object> rows, @Nullable Object item) {
        if (item == null) return;
        for (Object row : rows) {
            if (row == item) return;
        }
        rows.add(item);
        HookStatus.counted(FamilyNames.PLUS_MENU, MOVED);
    }
}
