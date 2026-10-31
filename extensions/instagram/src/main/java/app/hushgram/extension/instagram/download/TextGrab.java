/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /**
     * Its Lens share target, named so Android doesn't ask between Lens and the Google app's Save, or
     * between a phone's personal and work profiles, before Lens opens.
     */
    static final String LENS_ENTRY = "com.google.android.apps.search.lens.LensShareEntryPointActivity";
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
        /**
         * Copies [area] of the window [view] is in into [into], telling [done] on the main thread whether
         * it worked. [window] is that window when it's [view]'s activity's own, or null when it isn't.
         */
        void window(View view, @Nullable Window window, Rect area, Bitmap into, Consumer<Boolean> done);

        /** Copies [view]'s current frame into [into], telling [done] on the main thread whether it worked. */
        void surface(SurfaceView view, Bitmap into, Consumer<Boolean> done);
    }

    static volatile Copier copier = new Copier() {
        @Override public void window(View view, @Nullable Window window, Rect area, Bitmap into, Consumer<Boolean> done) {
            if (Build.VERSION.SDK_INT >= 34) {
                // By the view, so a story Instagram opens in a window over its main screen is what's read.
                Handler main = new Handler(Looper.getMainLooper());
                PixelCopy.request(PixelCopy.Request.Builder.ofWindow(view).setSourceRect(area).setDestinationBitmap(into).build(),
                        main::post, result -> done.accept(result.getStatus() == PixelCopy.SUCCESS));
                return;
            }
            if (window == null) {
                done.accept(false);
                return;
            }
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

    /** Starts [intent] from [context]. Tests stand in to see what went. */
    interface Starter {
        void start(Context context, Intent intent);
    }

    static volatile Starter starter = Context::startActivity;

    /**
     * Where the picture is written: a thread of its own, so a tap never waits in line behind HushGram's
     * other background work. Tests stand in.
     */
    static volatile Executor writer = task -> {
        Thread thread = new Thread(task, "HushGram copy text");
        thread.setDaemon(true);
        thread.start();
    };

    /**
     * How long a copy may go unanswered before the tap is told it failed. Nothing promises PixelCopy
     * answers a window that loses its surface partway.
     */
    static final long COPY_WAIT_MS = 3000;

    /**
     * Sends what [page] shows to Google Lens: a reel's page, a story's view, or null for the whole
     * window. [activity] is the one [page] was drawn for, when known. [family] is the patch the counts
     * and failures are reported under. Main thread. Never throws.
     */
    public static void grab(@Nullable View page, @Nullable Activity activity, String family) {
        try {
            Activity owner = ownerOf(page, activity);
            Window window = owner == null ? null : owner.getWindow();
            View shown = page != null && page.isAttachedToWindow() ? page : window == null ? null : window.peekDecorView();
            // With no activity open, the app sends it, as long as the page is on screen.
            Context from = owner != null ? owner : shown != null ? shown.getContext().getApplicationContext() : null;
            if (from == null) {
                Logger.printDebug(() -> "copy text: no open activity and nothing on screen");
                Context context = page != null ? page.getContext() : activity != null ? activity : Utils.getContext();
                if (context != null) fail(context, family, COUNT_NO_COPY);
                else HookStatus.counted(family, COUNT_NO_COPY);
                return;
            }
            View root = shown == null ? null : shown.getRootView();
            Rect area = root == null ? new Rect() : areaOf(shown, root);
            if (area.isEmpty()) {
                Logger.printDebug(() -> "copy text: nothing on screen to copy");
                fail(from, family, COUNT_NO_COPY);
                return;
            }
            boolean ownWindow = owner != null && holds(owner, root);
            Logger.printDebug(() -> "copy text: copying " + area.width() + "x" + area.height() + " of "
                    + (owner == null ? "a window with no activity" : (ownWindow ? "" : "a window over ")
                    + owner.getClass().getSimpleName()));
            Bitmap top = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
            List<SurfaceView> surfaces = new ArrayList<>();
            surfacesUnder(shown, surfaces);
            copier.window(shown, ownWindow ? window : null, area, top, answered("the screen", worked -> {
                if (!Boolean.TRUE.equals(worked)) {
                    // A copy that never answered may still be writing into it, so only an answered one is let go.
                    if (worked != null) top.recycle();
                    fail(from, family, COUNT_NO_COPY);
                    return;
                }
                underlay(from, area, top, surfaces, family);
            }));
        } catch (Throwable failure) {
            HookStatus.threw(family, "copy text", failure);
        }
    }

    /**
     * The open activity whose window holds [view]: [given], the one [view] or a parent of it was made
     * for, or the one HushGram last saw. When none holds it, the first of them still open. Null when
     * all are gone.
     *
     * <p>A story's views carry no activity of their own: Instagram makes them with a plain context, in
     * an activity over its main one, so the parents are asked too.
     */
    @Nullable
    static Activity ownerOf(@Nullable View view, @Nullable Activity given) {
        View root = view == null || !view.isAttachedToWindow() ? null : view.getRootView();
        Activity[] candidates = {given, madeFor(view), Utils.getActivity()};
        for (Activity candidate : candidates) {
            if (open(candidate) && holds(candidate, root)) return candidate;
        }
        for (Activity candidate : candidates) {
            if (open(candidate)) return candidate;
        }
        return null;
    }

    /** The activity behind the context of [view] or the nearest parent of it that has one, or null. */
    @Nullable
    static Activity madeFor(@Nullable View view) {
        for (View at = view; at != null; at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            for (Context context = at.getContext(); context instanceof ContextWrapper;
                    context = ((ContextWrapper) context).getBaseContext()) {
                if (context instanceof Activity) return (Activity) context;
            }
        }
        return null;
    }

    private static boolean open(@Nullable Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    /** Whether [root] is the top of [activity]'s own window. */
    private static boolean holds(Activity activity, @Nullable View root) {
        Window window = activity.getWindow();
        return root != null && window != null && window.peekDecorView() == root;
    }

    /**
     * [done], told at most once: with the copy's answer, or with null when none came in
     * {@link #COPY_WAIT_MS}.
     */
    private static Consumer<Boolean> answered(String what, Consumer<Boolean> done) {
        AtomicBoolean told = new AtomicBoolean();
        Utils.runOnMainThreadDelayed(() -> {
            if (!told.compareAndSet(false, true)) return;
            Logger.printDebug(() -> "copy text: copying " + what + " never answered");
            done.accept(null);
        }, COPY_WAIT_MS);
        return worked -> {
            if (told.compareAndSet(false, true)) done.accept(Boolean.TRUE.equals(worked));
        };
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
    private static void underlay(Context from, Rect area, Bitmap top, List<SurfaceView> surfaces, String family) {
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
                save(from, compose(top, frames, places), family);
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
            copier.surface(surface, frame, answered("a video", worked -> {
                if (Boolean.TRUE.equals(worked)) frames[which] = frame;
                else if (worked != null) frame.recycle();
                if (--left[0] == 0) finish.run();
            }));
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
    private static void save(Context from, Bitmap picture, String family) {
        Context app = from.getApplicationContext();
        try {
            writer.execute(() -> {
                File file = null;
                try {
                    file = write(app, picture);
                } catch (Throwable failure) {
                    Logger.printException(() -> "copy text: the picture wouldn't write", failure);
                }
                File written = file;
                Logger.printDebug(() -> "copy text: " + (written == null ? "the picture didn't write" : "wrote " + written.getName()));
                Utils.runOnMainThread(() -> {
                    if (written == null) fail(from, family, COUNT_NO_COPY);
                    else send(from, written, family);
                });
            });
        } catch (RuntimeException | OutOfMemoryError unstarted) {
            Logger.printException(() -> "copy text: the picture couldn't be written", unstarted);
            picture.recycle();
            fail(from, family, COUNT_NO_COPY);
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

    /**
     * Offers [file] to Google Lens, or to the share sheet when Lens isn't there. [from] is the activity
     * showing the page, or the app when there's none, which then starts Lens in a task of its own.
     */
    static void send(Context from, File file, String family) {
        try {
            if (from instanceof Activity && (((Activity) from).isFinishing() || ((Activity) from).isDestroyed())) {
                Logger.printDebug(() -> "copy text: " + from.getClass().getSimpleName() + " closed before sending");
                return;
            }
            String provider = authority.of(from);
            if (provider == null) {
                fail(from, family, COUNT_NO_PROVIDER);
                return;
            }
            Uri uri = new Uri.Builder().scheme("content").authority(provider)
                    .appendPath(CACHE_ROOT).appendPath(FOLDER).appendPath(file.getName()).build();
            Intent share = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("", uri));
            int task = from instanceof Activity ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK;
            try {
                starter.start(from, new Intent(share).setClassName(LENS_PACKAGE, LENS_ENTRY).addFlags(task));
            } catch (ActivityNotFoundException noEntry) {
                // A Google app that moved its Lens target still takes the picture by package.
                try {
                    starter.start(from, new Intent(share).setPackage(LENS_PACKAGE).addFlags(task));
                } catch (ActivityNotFoundException noLens) {
                    Intent chooser = Intent.createChooser(share, L10n.t(from, "Copy text with"));
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | task);
                    starter.start(from, chooser);
                }
            }
            HookStatus.counted(family, COUNT_SENT);
            Logger.printDebug(() -> "copy text: sent " + file.getName());
            if (!logged) {
                logged = true;
                Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, SOURCE, () -> "copy text sent a picture to read");
            }
        } catch (ActivityNotFoundException none) {
            fail(from, family, COUNT_NO_APP);
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
