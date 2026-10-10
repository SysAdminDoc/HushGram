/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.instagram.settings.Settings;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Save profile picture: a row at the end of the menu on someone's profile that saves their picture
 * at the largest size Instagram has, through the same save as a post's photo. View profile picture,
 * with its own switch, is a row after it that opens that picture full screen
 * ({@link ProfilePictureViewer}), with a Save button that goes through the same save. Copy username
 * and Copy bio, with a switch of their own, put the account's username or bio on the clipboard
 * exactly as Instagram has it (#29).
 *
 * <p>Instagram builds that menu's sheet and shows it in one method. Right before it shows, the
 * patch hands {@link #offer} the sheet, the profile's account and the menu's context. The sizes are
 * read then, so each row works on the picture the menu was opened on, and each goes in through
 * {@link #addRow}, whose body the patch writes as a call to the sheet's own adder of a plain row.
 * Instagram draws it like its own rows and closes the sheet when it's tapped.
 */
public final class ProfilePicture {
    private ProfilePicture() {
    }

    /** Instagram's reads and the sheet's row adder, each one native call. */
    interface Native {
        boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label);
        /** The account's full size picture, its {@code hd_profile_pic_url_info}, or null. */
        Object fullSize(Object user);
        String fullSizeUrl(Object info);
        int fullSizeWidth(Object info);
        int fullSizeHeight(Object info);
        /** The picture Instagram shows on the profile, its {@code profile_pic_url}, or null. */
        Object shown(Object user);
        String shownUrl(Object image);
        int shownWidth(Object image);
        int shownHeight(Object image);
        String username(Object user);
        /** The account's {@code biography}, as its owner wrote it, or null. */
        String biography(Object user);
    }

    interface Save {
        boolean photo(Context context, List<MediaSave.Rendition> sizes, PostDetails details);
    }

    private static final Native NATIVE = new Native() {
        public boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label) {
            return ProfilePicture.addRow(sheet, context, listener, label);
        }
        public Object fullSize(Object user) { return InstagramMedia.fullSizeProfilePicture(user); }
        public String fullSizeUrl(Object info) { return InstagramMedia.profilePictureUrl(info); }
        public int fullSizeWidth(Object info) { return InstagramMedia.profilePictureWidth(info); }
        public int fullSizeHeight(Object info) { return InstagramMedia.profilePictureHeight(info); }
        public Object shown(Object user) { return InstagramMedia.profilePicture(user); }
        public String shownUrl(Object image) { return InstagramMedia.candidateUrl(image); }
        public int shownWidth(Object image) { return InstagramMedia.candidateWidth(image); }
        public int shownHeight(Object image) { return InstagramMedia.candidateHeight(image); }
        public String username(Object user) { return InstagramMedia.username(user); }
        public String biography(Object user) { return InstagramMedia.biography(user); }
    };

    /** Opens the full screen viewer. Answers whether it opened. */
    interface Viewer {
        boolean open(Context context, List<MediaSave.Rendition> sizes, String owner, Save save);
    }

    private static final Save SAVE = MediaSave::savePictureBySize;
    private static final Viewer VIEWER = ProfilePictureViewer::open;

    // What the diagnostic report counts when the menu opens. Fixed text: nothing read from the
    // account goes in.
    static final String FULL_SIZE = "full size picture";
    static final String SHOWN_ONLY = "shown size only";
    static final String NO_PICTURE = "no profile picture";
    static final String NOT_ADDED = "row not added";
    static final String VIEW_NOT_ADDED = "view row not added";
    static final String NO_BIO = "no bio";
    static final String COPY_NOT_ADDED = "copy row not added";

    /**
     * Adds Save profile picture and View profile picture to [sheet], the menu on [user]'s profile,
     * each when its switch is on and the account has a picture, then Copy username and Copy bio
     * when their switch is on, each when the account has one. [context] is the menu's. Never
     * throws.
     */
    public static void offer(Object sheet, Object user, Context context) {
        offer(sheet, user, context, NATIVE, SAVE, VIEWER);
    }

    /**
     * The same rows for the newer pop-up list next to the profile's three dots, which the patch hands
     * its list of items right before the popup takes it. Each row goes in through {@link PopupRows},
     * as a copy of a plain row the list already has. Never throws.
     */
    public static void offerPopup(List<?> items, Object user, Context context) {
        offer(items, user, context, popupReads(NATIVE), SAVE, VIEWER);
    }

    /** [reads] with the row adder swapped for one that appends to a pop-up list's items. */
    static Native popupReads(final Native reads) {
        return new Native() {
            public boolean addRow(Object items, Context context, View.OnClickListener listener, String label) {
                return items instanceof List && PopupRows.add((List<?>) items, listener, label);
            }
            public Object fullSize(Object user) { return reads.fullSize(user); }
            public String fullSizeUrl(Object info) { return reads.fullSizeUrl(info); }
            public int fullSizeWidth(Object info) { return reads.fullSizeWidth(info); }
            public int fullSizeHeight(Object info) { return reads.fullSizeHeight(info); }
            public Object shown(Object user) { return reads.shown(user); }
            public String shownUrl(Object image) { return reads.shownUrl(image); }
            public int shownWidth(Object image) { return reads.shownWidth(image); }
            public int shownHeight(Object image) { return reads.shownHeight(image); }
            public String username(Object user) { return reads.username(user); }
            public String biography(Object user) { return reads.biography(user); }
        };
    }

    static void offer(Object sheet, Object user, Context context, Native reads, Save save) {
        offer(sheet, user, context, reads, save, VIEWER);
    }

    static void offer(Object sheet, Object user, Context context, Native reads, Save save, Viewer viewer) {
        try {
            HookStatus.invoked(FamilyNames.PROFILE_PICTURE);
            if (sheet == null || user == null || context == null) return;
            boolean saving = on(), viewing = viewing(), copying = copying();
            if (!saving && !viewing && !copying) return;
            String owner = reads.username(user);
            List<MediaSave.Rendition> sizes = saving || viewing ? sizes(user, reads) : Collections.emptyList();
            if (saving && !sizes.isEmpty() && !reads.addRow(sheet, context, new Row(context, sizes, owner, save),
                    L10n.t(context, "Save profile picture"))) {
                HookStatus.counted(FamilyNames.PROFILE_PICTURE, NOT_ADDED);
            }
            if (viewing && !sizes.isEmpty() && !reads.addRow(sheet, context, new ViewRow(context, sizes, owner, save, viewer),
                    L10n.t(context, "View profile picture"))) {
                HookStatus.counted(FamilyNames.PROFILE_PICTURE, VIEW_NOT_ADDED);
            }
            if (!copying) return;
            if (owner != null && !owner.isEmpty() && !reads.addRow(sheet, context, new CopyRow(context, owner, false),
                    L10n.t(context, "Copy username"))) {
                HookStatus.counted(FamilyNames.PROFILE_PICTURE, COPY_NOT_ADDED);
            }
            String bio = reads.biography(user);
            if (bio == null || bio.isEmpty()) {
                HookStatus.counted(FamilyNames.PROFILE_PICTURE, NO_BIO);
            } else if (!reads.addRow(sheet, context, new CopyRow(context, bio, true), L10n.t(context, "Copy bio"))) {
                HookStatus.counted(FamilyNames.PROFILE_PICTURE, COPY_NOT_ADDED);
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURE, "profile menu", failure);
        }
    }

    /**
     * The sizes of [user]'s picture with an address on Meta's media servers: the full size one when
     * Instagram has it, and the one the profile shows, so the save keeps the larger. Unmodifiable,
     * empty when there's neither.
     */
    static List<MediaSave.Rendition> sizes(Object user, Native reads) {
        List<MediaSave.Rendition> sizes = new ArrayList<>(2);
        Object full = reads.fullSize(user);
        if (full != null) add(sizes, reads.fullSizeUrl(full), reads.fullSizeWidth(full), reads.fullSizeHeight(full));
        boolean fullSize = !sizes.isEmpty();
        Object shown = reads.shown(user);
        if (shown != null) add(sizes, reads.shownUrl(shown), reads.shownWidth(shown), reads.shownHeight(shown));
        HookStatus.counted(FamilyNames.PROFILE_PICTURE, fullSize ? FULL_SIZE : sizes.isEmpty() ? NO_PICTURE : SHOWN_ONLY);
        return Collections.unmodifiableList(sizes);
    }

    private static void add(List<MediaSave.Rendition> sizes, String url, int width, int height) {
        if (url == null || MediaUrlPolicy.shapeRefusal(url) != null) return;
        for (MediaSave.Rendition kept : sizes) {
            if (kept.url.equals(url)) return;
        }
        sizes.add(new MediaSave.Rendition(url, Math.max(width, 0), Math.max(height, 0), 0));
    }

    static boolean on() {
        try {
            return Utils.settingsReady() && Settings.SAVE_PROFILE_PICTURES.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURE, "profile picture switch", t);
            return false;
        }
    }

    /** Whether View profile picture's switch is on, which a pause answers off. */
    static boolean viewing() {
        try {
            return Utils.settingsReady() && Settings.VIEW_PROFILE_PICTURES.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURE, "view profile picture switch", t);
            return false;
        }
    }

    /** Whether Copy username and bio's switch is on, which a pause answers off. */
    static boolean copying() {
        try {
            return Utils.settingsReady() && Settings.COPY_PROFILE_TEXT.get();
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURE, "copy username and bio switch", t);
            return false;
        }
    }

    /**
     * Adds a plain row labeled [label] to [sheet], one of Instagram's menu sheets, that runs
     * [listener] when tapped. The patch writes the body as the sheet's own call. Answers whether the
     * row went in, which as built it never does.
     */
    @SuppressWarnings("unused")
    public static boolean addRow(Object sheet, Context context, View.OnClickListener listener, String label) {
        return false;
    }

    /**
     * The newer pop-up list's item class, then the field its constructor stores each argument in,
     * in argument order: {@code X.0juk|A00,A01,...}. The patch writes the body from the constructor
     * itself. As built it answers null, and {@link PopupRows} adds no row.
     */
    public static String popupItemFields() {
        return null;
    }

    /** The row: the sizes read when the menu opened, and the menu's context, held as long as the sheet is. */
    static final class Row implements View.OnClickListener {
        final Context context;
        final List<MediaSave.Rendition> sizes;
        final String owner;
        final Save save;

        Row(Context context, List<MediaSave.Rendition> sizes, String owner, Save save) {
            this.context = context;
            this.sizes = sizes;
            this.owner = owner;
            this.save = save;
        }

        /** Instagram may tap it with no view, from a sheet drawn without one. */
        @Override public void onClick(View view) {
            try {
                if (!on()) return;
                if (!save.photo(context, sizes, PostDetails.profilePicture(owner))) failed(context);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PROFILE_PICTURE, "save profile picture", failure);
                failed(context);
            }
        }
    }

    /** The View row: the same sizes, opened full screen on a tap. */
    static final class ViewRow implements View.OnClickListener {
        final Context context;
        final List<MediaSave.Rendition> sizes;
        final String owner;
        final Save save;
        final Viewer viewer;

        ViewRow(Context context, List<MediaSave.Rendition> sizes, String owner, Save save, Viewer viewer) {
            this.context = context;
            this.sizes = sizes;
            this.owner = owner;
            this.save = save;
            this.viewer = viewer;
        }

        @Override public void onClick(View view) {
            try {
                if (!viewing()) return;
                if (!viewer.open(context, sizes, owner, save)) ProfilePictureViewer.notOpened(context);
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PROFILE_PICTURE, "view profile picture", failure);
                ProfilePictureViewer.notOpened(context);
            }
        }
    }

    /**
     * A Copy row: the username, or with [bio] the bio, read when the menu opened, put on the
     * clipboard exactly as it is, never trimmed, and marked sensitive as every copy is.
     */
    static final class CopyRow implements View.OnClickListener {
        final Context context;
        final String text;
        final boolean bio;

        CopyRow(Context context, String text, boolean bio) {
            this.context = context;
            this.text = text;
            this.bio = bio;
        }

        @Override public void onClick(View view) {
            try {
                if (!copying()) return;
                Utils.setClipboard(context, bio ? L10n.t(context, "Bio") : L10n.t(context, "Username"), text);
                Utils.showToastShort(bio ? L10n.t(context, "Bio copied") : L10n.t(context, "Username copied"));
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.PROFILE_PICTURE, bio ? "copy bio" : "copy username", failure);
                try {
                    Utils.showToastShort(bio ? L10n.t(context, "Couldn't copy the bio")
                            : L10n.t(context, "Couldn't copy the username"));
                } catch (Throwable feedback) {
                    HookStatus.threw(FamilyNames.PROFILE_PICTURE, "copy feedback", feedback);
                }
            }
        }
    }

    /** Download failed, in the phone's language. Never throws. */
    static void failed(Context context) {
        try {
            Context application = context == null ? null : context.getApplicationContext();
            if (application != null) Feedback.show(application, L10n.t(application, "Download failed"), true);
        } catch (Throwable t) {
            HookStatus.threw(FamilyNames.PROFILE_PICTURE, "save feedback", t);
        }
    }
}
