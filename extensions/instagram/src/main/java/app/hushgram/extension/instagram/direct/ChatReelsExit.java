/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.Logger;
import app.hushgram.extension.shared.diagnostics.HookStatus;

/**
 * Takes a swipe up past a chat's last reel back to the chat, while "Stop after a chat's reels" is on
 * (#390). With nothing after that reel the viewer only bounces, and Back was the one way out.
 *
 * <p>The patch hands {@link #viewer(Object, Object)} each Reels viewer's pager as the viewer sets it
 * up, with the viewer's ClipsViewerSource. For a chat's viewer this wraps its window's callback, as
 * Hold to download does, to see touches. A finger that goes down on the pager, travels up at least
 * {@link #EXIT_SHARE} of its height, mostly straight, while the pager's list is being dragged and
 * can't scroll any further down, goes on to Instagram first, then Back.
 */
public final class ChatReelsExit {
    /** How far up a swipe past the last reel travels to leave, as a share of the pager's height. */
    static final float EXIT_SHARE = 0.25f;
    /** AndroidX's RecyclerView, by the name Instagram keeps, which a ViewPager2 holds as its list. */
    static final String LIST_TYPE = "androidx.recyclerview.widget.RecyclerView";
    /** RecyclerView's SCROLL_STATE_DRAGGING. */
    static final int DRAGGING = 1;

    /** The pagers of the chats' viewers, all touched on the main thread. */
    private static final Map<View, Boolean> PAGERS = new WeakHashMap<>();
    private static final Map<Window, Boolean> WRAPPED = new WeakHashMap<>();
    @Nullable private static Handler handler;

    private ChatReelsExit() {
    }

    /**
     * A Reels viewer set up [pager], its ViewPager2, for [source], its ClipsViewerSource: a chat's
     * pager gets watched once its window shows. Never throws.
     */
    public static void viewer(Object pager, Object source) {
        try {
            if (!(pager instanceof View)) return;
            View view = (View) pager;
            if (!ChatReels.fromChat(source)) {
                if (PAGERS.remove(view) != null) {
                    Logger.printDebug(() -> "Chat reels: a chat's pager set up again for " + source + ", no longer watched");
                }
                return;
            }
            PAGERS.put(view, Boolean.TRUE);
            Logger.printDebug(() -> "Chat reels: watching a chat's pager, attached " + view.isAttachedToWindow());
            if (view.isAttachedToWindow()) {
                watch(view);
            } else {
                view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(@NonNull View v) {
                        v.removeOnAttachStateChangeListener(this);
                        try {
                            watch(v);
                        } catch (Throwable failure) {
                            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels pager", failure);
                        }
                    }

                    @Override public void onViewDetachedFromWindow(@NonNull View v) { }
                });
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels pager", failure);
        }
    }

    /** Wraps the callback of the activity window showing [pager] once. */
    private static void watch(View pager) {
        Activity activity = ownerOf(pager);
        Window window = activity == null ? null : activity.getWindow();
        if (window == null || WRAPPED.containsKey(window)) {
            Logger.printDebug(() -> "Chat reels: window not wrapped for the pager, "
                    + (window == null ? "no activity window holds it" : "already wrapped"));
            return;
        }
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        window.setCallback((Window.Callback) Proxy.newProxyInstance(ChatReelsExit.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, new Watcher(inner, activity)));
        WRAPPED.put(window, Boolean.TRUE);
    }

    /**
     * The activity whose window shows [pager]. A pager Instagram keeps and sets up again for a
     * later viewer can carry a context that isn't that viewer's activity (the second chat reels
     * viewer of a process, #390), so this looks up from [pager] through its parents to the first
     * view whose context's activity holds the window [pager] is in, the content view at the latest.
     */
    @Nullable
    static Activity ownerOf(View pager) {
        View root = pager.getRootView();
        for (Object at = pager; at instanceof View; at = ((View) at).getParent()) {
            Activity activity = activityOf(((View) at).getContext());
            Window window = activity == null ? null : activity.getWindow();
            if (window != null && window.peekDecorView() == root) return activity;
        }
        return null;
    }

    @Nullable
    static Activity activityOf(@Nullable Context context) {
        Context at = context;
        for (int depth = 0; depth < 10 && at instanceof ContextWrapper; depth++) {
            if (at instanceof Activity) return (Activity) at;
            at = ((ContextWrapper) at).getBaseContext();
        }
        return null;
    }

    /** The callback [callback] passes calls on to when it's this watcher, else [callback]. */
    public static Window.Callback innerOf(Window.Callback callback) {
        if (callback != null && Proxy.isProxyClass(callback.getClass())
                && Proxy.getInvocationHandler(callback) instanceof Watcher) {
            return ((Watcher) Proxy.getInvocationHandler(callback)).inner;
        }
        return callback;
    }

    /** The chat pager shown in [decor] under the screen point [x], [y], or null. */
    @Nullable
    private static View pagerAt(View decor, float x, float y) {
        int[] at = new int[2];
        for (View pager : new ArrayList<>(PAGERS.keySet())) {
            if (pager == null || !pager.isShown() || pager.getRootView() != decor) continue;
            pager.getLocationOnScreen(at);
            if (x >= at[0] && x < at[0] + pager.getWidth() && y >= at[1] && y < at[1] + pager.getHeight()) {
                return pager;
            }
        }
        return null;
    }

    /** The chat pager on screen, the one in the focused window first, or null. */
    @Nullable
    static View shownPager() {
        View shown = null;
        for (View pager : new ArrayList<>(PAGERS.keySet())) {
            if (pager == null || !pager.isShown()) continue;
            if (pager.hasWindowFocus()) return pager;
            if (shown == null) shown = pager;
        }
        return shown;
    }

    /** Whether [view] is a chat's Reels pager. */
    static boolean isChatPager(View view) {
        return PAGERS.containsKey(view);
    }

    /** [pager]'s list, the RecyclerView a ViewPager2 pages with, or null. */
    @Nullable
    static View listOf(View pager) {
        if (!(pager instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) pager;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            for (Class<?> type = child.getClass(); type != null; type = type.getSuperclass()) {
                if (LIST_TYPE.equals(type.getName())) return child;
            }
        }
        return null;
    }

    /** Whether [list] is following a finger right now. */
    private static boolean dragging(View list) throws ReflectiveOperationException {
        Method state = list.getClass().getMethod("getScrollState");
        return ((Integer) state.invoke(list)) == DRAGGING;
    }

    private static Handler handler() {
        Looper main = Looper.getMainLooper();
        if (handler == null || handler.getLooper() != main) handler = new Handler(main);
        return handler;
    }

    /** Passes every call on to the callback it wraps, and follows a finger on a chat's pager. */
    private static final class Watcher implements InvocationHandler {
        final Window.Callback inner;
        private final WeakReference<Activity> activity;
        @Nullable private View tracking;
        private float downX;
        private float downY;

        Watcher(Window.Callback inner, Activity activity) {
            this.inner = inner;
            this.activity = new WeakReference<>(activity);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean leave = false;
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName())) {
                try {
                    leave = seen((MotionEvent) args[0]);
                } catch (Throwable failure) {
                    tracking = null;
                    HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels swipe", failure);
                }
            }
            Object result;
            try {
                result = method.invoke(inner, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
            if (leave) {
                HookStatus.counted(FamilyNames.CHAT_REELS, "swiped past a chat's last reel");
                handler().post(this::back);
            }
            return result;
        }

        /** Follows [event], and whether it ends a swipe that leaves. */
        private boolean seen(MotionEvent event) throws ReflectiveOperationException {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tracking = null;
                    Activity owner = activity.get();
                    Window window = owner == null ? null : owner.getWindow();
                    View decor = window == null ? null : window.peekDecorView();
                    if (decor == null || PAGERS.isEmpty() || !ChatReels.stop()) return false;
                    tracking = pagerAt(decor, event.getRawX(), event.getRawY());
                    downX = event.getRawX();
                    downY = event.getRawY();
                    if (tracking == null) Logger.printDebug(() -> "Chat reels: no chat pager under the finger");
                    return false;
                case MotionEvent.ACTION_UP:
                    View pager = tracking;
                    tracking = null;
                    if (pager == null) return false;
                    float dy = downY - event.getRawY();
                    float dx = Math.abs(event.getRawX() - downX);
                    if (dy < EXIT_SHARE * pager.getHeight() || dy <= 2 * dx) return false;
                    View list = listOf(pager);
                    boolean more = list != null && list.canScrollVertically(1);
                    boolean held = list != null && dragging(list);
                    Logger.printDebug(() -> "Chat reels: swipe up at the pager, list " + (list != null)
                            + ", more below " + more + ", dragging " + held);
                    return list != null && !more && held;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    tracking = null;
                    return false;
                default:
                    return false;
            }
        }

        private void back() {
            try {
                Activity owner = activity.get();
                if (owner != null && !owner.isFinishing() && !owner.isDestroyed()) owner.onBackPressed();
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.CHAT_REELS, "chat reels swipe back", failure);
            }
        }
    }

    /** Lets a test start with no pagers or wrapped windows from an earlier one. */
    static void resetForTests() {
        PAGERS.clear();
        WRAPPED.clear();
    }
}
