/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.diagnostics.DiagnosticCategory;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Copy text off a reel or a story (#303): what the screen shows of the reel or story goes to Google
 * Lens, where its words can be selected and copied.
 *
 * <p>The picture is the window's own pixels over the reel or story, read with {@link PixelCopy}, so it
 * holds what the person sees: the video frame, a photo, the text and stickers Instagram draws over them,
 * and the caption. A video playing in a SurfaceView sits under the window rather than in it, so each one
 * on the page is copied as well and drawn under the window's pixels, which are clear over it.
 *
 * <p>It goes out as a JPEG in Instagram's cache, offered through Instagram's own FileProvider, which
 * shares its cache folder, so nothing lands in the gallery and no permission is asked. Only the last one
 * is kept. Google Lens takes it when the Google app is there; otherwise the share sheet offers whatever
 * reads pictures. Nothing here throws into Instagram.
 */
public final class TextGrab {
    private static final String SOURCE = "TextGrab";
    /** The Google app, whose share target for a picture is Google Lens. */
    static final String LENS_PACKAGE = "com.google.android.googlequicksearchbox";
    /** Instagram's FileProvider, found by class since a second install renames its authority. */
    static final String FILE_PROVIDER = "androidx.core.content.FileProvider";
    /** The root Instagram's provider gives its cache folder, and the folder under it these go in. */
    static final String CACHE_ROOT = "cache";
    static final String FOLDER = "hushgram-text";
    static final String COUNT_SENT = "text grab sent";
    static final String COUNT_NO_COPY = "text grab couldn't copy the screen";
    static final String COUNT_NO_PROVIDER = "text grab found no FileProvider";
    static final String COUNT_NO_APP = "text grab found no app to send to";
    /** How much of the window a parent must span, across and down, to be the page a reel plays on. */
    static final float PAGE_WIDTH = 0.9f;
    static final float PAGE_HEIGHT = 0.5f;
    /** JPEG quality: text stays sharp and the file stays small. */
    static final int QUALITY = 92;

    private static volatile boolean logged;

    private TextGrab() {
    }

    /** How the pixels are read. Tests stand in, since only a phone has the surfaces to read. */
    interface Copier {
        /** Copies [area] of [window] into [into], telling [done] on the main thread whether it worked. */
        void window(Window window, Rect area, Bitmap into, Consumer<Boolean> done);

        /** Copies [view]'s current frame into [into], telling [done] on the main thread whether it worked. */
        void surface(SurfaceView view, Bitmap into, Consumer<Boolean> done);
    }

    static volatile Copier copier = new Copier() {
        @Override public void window(Window window, Rect area, Bitmap into, Consumer<Boolean> done) {
            PixelCopy.request(window, area, into, result -> done.accept(result == PixelCopy.SUCCESS),
                    new Handler(Looper.getMainLooper()));
        }

        @Override public void surface(SurfaceView view, Bitmap into, Consumer<Boolean> done) {
            if (view.getHolder().getSurface() == null || !view.getHolder().getSurface().isValid()) {
                done.accept(false);
                return;
            }
            PixelCopy.request(view, into, result -> done.accept(result == PixelCopy.SUCCESS),
                    new Handler(Looper.getMainLooper()));
        }
    };

    /** Where the file is offered from: the authority of Instagram's FileProvider, or null. Tests stand in. */
    interface Authority {
        @Nullable
        String of(Context context);
    }

    static volatile Authority authority = TextGrab::fileProvider;

    /** Starts [intent] from [activity]. Tests stand in to see what went. */
    interface Starter {
        void start(Activity activity, Intent intent);
    }

    static volatile Starter starter = Activity::startActivity;

    /**
     * Sends what [page] shows to Google Lens: a reel's page, a story's view, or null for the whole
     * window. [family] is the patch the counts and failures are reported under. Main thread. Never
     * throws.
     */
    public static void grab(@Nullable View page, @Nullable Activity activity, String family) {
        try {
            if (activity == null || activity.isFinishing()) return;
            Window window = activity.getWindow();
            View decor = window == null ? null : window.getDecorView();
            if (decor == null) return;
            View shown = page == null || !page.isAttachedToWindow() ? decor : page;
            Rect area = areaOf(shown, decor);
            if (area.isEmpty()) {
                fail(activity, family, COUNT_NO_COPY);
                return;
            }
            Bitmap top = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
            List<SurfaceView> surfaces = new ArrayList<>();
            surfacesUnder(shown, surfaces);
            copier.window(window, area, top, worked -> {
                if (!worked) {
                    top.recycle();
                    fail(activity, family, COUNT_NO_COPY);
                    return;
                }
                underlay(activity, area, top, surfaces, family);
            });
        } catch (Throwable failure) {
            HookStatus.threw(family, "copy text", failure);
        }
    }

    /**
     * The page a reel's column sits on: the nearest parent of [anchor] spanning most of the window
     * across and half of it down, or null when there's none and the whole window will do.
     */
    @Nullable
    public static View pageOf(@Nullable View anchor) {
        if (anchor == null) return null;
        View root = anchor.getRootView();
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        for (View at = anchor; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            if (at.getWidth() >= root.getWidth() * PAGE_WIDTH && at.getHeight() >= root.getHeight() * PAGE_HEIGHT) return at;
        }
        return null;
    }

    /** [view]'s rectangle in the window, cut to what of the window there is. */
    static Rect areaOf(View view, View decor) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        Rect area = new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
        if (!area.intersect(0, 0, decor.getWidth(), decor.getHeight())) area.setEmpty();
        return area;
    }

    /** Every SurfaceView under [view] that shows, in drawing order. */
    static void surfacesUnder(View view, List<SurfaceView> into) {
        if (!view.isShown()) return;
        if (view instanceof SurfaceView) {
            into.add((SurfaceView) view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) surfacesUnder(group.getChildAt(i), into);
    }

    /**
     * Copies each of [surfaces] that crosses [area] and draws them under [top] on black, then writes the
     * picture. A surface that won't copy, a protected video say, stays black under the rest.
     */
    private static void underlay(Activity activity, Rect area, Bitmap top, List<SurfaceView> surfaces, String family) {
        List<SurfaceView> crossing = new ArrayList<>();
        List<Rect> places = new ArrayList<>();
        for (SurfaceView surface : surfaces) {
            if (surface.getWidth() <= 0 || surface.getHeight() <= 0) continue;
            Rect place = new Rect();
            int[] at = new int[2];
            surface.getLocationInWindow(at);
            place.set(at[0], at[1], at[0] + surface.getWidth(), at[1] + surface.getHeight());
            if (!Rect.intersects(place, area)) continue;
            place.offset(-area.left, -area.top);
            crossing.add(surface);
            places.add(place);
        }
        Bitmap[] frames = new Bitmap[crossing.size()];
        int[] left = {crossing.size()};
        Runnable finish = () -> {
            try {
                save(activity, compose(top, frames, places), family);
            } catch (Throwable failure) {
                HookStatus.threw(family, "copy text", failure);
            }
        };
        if (crossing.isEmpty()) {
            finish.run();
            return;
        }
        for (int i = 0; i < crossing.size(); i++) {
            int which = i;
            SurfaceView surface = crossing.get(i);
            Bitmap frame = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888);
            copier.surface(surface, frame, worked -> {
                if (worked) frames[which] = frame;
                else frame.recycle();
                if (--left[0] == 0) finish.run();
            });
        }
    }

    /**
     * [top] over black with each of [frames] drawn under it at its place in [places], a null frame
     * left out. Recycles everything it was handed.
     */
    static Bitmap compose(Bitmap top, Bitmap[] frames, List<Rect> places) {
        Bitmap picture = Bitmap.createBitmap(top.getWidth(), top.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(picture);
        canvas.drawColor(Color.BLACK);
        for (int i = 0; i < frames.length; i++) {
            if (frames[i] == null) continue;
            canvas.drawBitmap(frames[i], null, places.get(i), null);
            frames[i].recycle();
        }
        canvas.drawBitmap(top, 0, 0, null);
        top.recycle();
        return picture;
    }

    /** Writes [picture] off the main thread, then sends it from the main thread. */
    private static void save(Activity activity, Bitmap picture, String family) {
        Context app = activity.getApplicationContext();
        boolean queued = Utils.runOnBackgroundThread(() -> {
            File file = write(app, picture);
            Utils.runOnMainThread(() -> {
                if (file == null) fail(activity, family, COUNT_NO_COPY);
                else send(activity, file, family);
            });
        });
        if (!queued) {
            picture.recycle();
            fail(activity, family, COUNT_NO_COPY);
        }
    }

    /** [picture] as a JPEG in the cache folder, the one there from last time deleted, or null when it won't write. */
    @Nullable
    static File write(Context context, Bitmap picture) {
        File folder = new File(context.getCacheDir(), FOLDER);
        try {
            if (!folder.mkdirs() && !folder.isDirectory()) return null;
            File[] old = folder.listFiles();
            if (old != null) {
                for (File file : old) {
                    if (!file.delete()) Logger.printDebug(() -> "copy text: an old picture wouldn't delete");
                }
            }
            File file = new File(folder, "screen-" + System.currentTimeMillis() + ".jpg");
            try (OutputStream out = new FileOutputStream(file)) {
                if (!picture.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null;
            }
            return file;
        } catch (IOException | RuntimeException unwritable) {
            Logger.printException(() -> "copy text: the picture wouldn't write", unwritable);
            return null;
        } finally {
            picture.recycle();
        }
    }

    /** Offers [file] to Google Lens, or to the share sheet when Lens isn't there. */
    static void send(Activity activity, File file, String family) {
        try {
            if (activity.isFinishing()) return;
            String provider = authority.of(activity);
            if (provider == null) {
                fail(activity, family, COUNT_NO_PROVIDER);
                return;
            }
            Uri uri = new Uri.Builder().scheme("content").authority(provider)
                    .appendPath(CACHE_ROOT).appendPath(FOLDER).appendPath(file.getName()).build();
            Intent share = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("", uri));
            try {
                starter.start(activity, new Intent(share).setPackage(LENS_PACKAGE));
            } catch (ActivityNotFoundException noLens) {
                Intent chooser = Intent.createChooser(share, L10n.t(activity, "Copy text with"));
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                starter.start(activity, chooser);
            }
            HookStatus.counted(family, COUNT_SENT);
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "copy text sent a picture to read");
            }
        } catch (ActivityNotFoundException none) {
            fail(activity, family, COUNT_NO_APP);
        } catch (Throwable failure) {
            HookStatus.threw(family, "copy text", failure);
        }
    }

    /** The authority of [context]'s package's FileProvider, the one sharing its cache folder, or null. */
    @Nullable
    static String fileProvider(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PROVIDERS);
            if (info.providers == null) return null;
            for (ProviderInfo provider : info.providers) {
                if (FILE_PROVIDER.equals(provider.name) && provider.authority != null) {
                    return provider.authority.split(";")[0];
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unreadable) {
            Logger.printException(() -> "copy text: the package's providers couldn't be read", unreadable);
        }
        return null;
    }

    /** Counts [what] and says the screen couldn't go out. */
    private static void fail(Context context, String family, String what) {
        HookStatus.counted(family, what);
        Utils.showToastShort(L10n.t(context, what.equals(COUNT_NO_APP) ? "No app here can read text" : "Couldn't copy this screen"));
    }

    /** Back to how a fresh start finds it. */
    static void resetForTests() {
        logged = false;
    }
}
