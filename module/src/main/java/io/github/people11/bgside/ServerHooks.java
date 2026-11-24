package io.github.people11.bgside;

import static io.github.people11.bgside.H.TAG;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;

import com.v7878.unsafe.Reflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** system_server side: every minimize to the bubble moves the task to its own Mirage keep-alive display. */
final class ServerHooks {
    private static final int INVALID_DISPLAY = -1;
    private static final float HANG_FPS = 10f;
    private static final long PENDING_TIMEOUT_MS = 10_000;
    private static final long RELEASE_DELAY_MS = 1_000;
    private static final int SF_SET_FRAME_RATE_OVERRIDE = 1039;

    private static final class Hang {
        volatile String pkg;
        volatile int uid = -1;
        volatile int display = INVALID_DISPLAY;
        volatile boolean ready;
        volatile boolean moved;
    }

    private static final Map<Integer, Hang> hung = new ConcurrentHashMap<>();
    /** Inside the stock bubble restore. */
    private static final Set<Integer> restoring = ConcurrentHashMap.newKeySet();
    /** uid -> hung tasks holding its frame rate override. */
    private static final Map<Integer, Integer> fpsPins = new ConcurrentHashMap<>();
    /** uid of the top resumed activity when it is outside our displays (its override is lifted meanwhile). */
    private static int frontUid = -1;

    private static Handler handler;
    private static ClassLoader loader;

    static void install(ClassLoader cl) {
        loader = cl;
        var thread = new HandlerThread("BgSide");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            Class<?> toFloat = cl.loadClass("com.android.server.wm.ExitFlexibleTaskToFloatStrategy");
            Class<?> ftc = cl.loadClass("com.android.server.wm.FlexibleTaskController");
            Class<?> tfExt = cl.loadClass("com.android.server.wm.TaskFragmentExtImpl");
            Class<?> fhc = cl.loadClass("com.android.server.wm.FloatHandleController");
            Class<?> infoManager = cl.loadClass("com.android.server.wm.OplusFlexibleInfoManager");
            Class<?> openAnimSpec = cl.loadClass("com.android.server.wm.FlexibleFloatHandleAnimationSpec");
            Class<?> starter = cl.loadClass("com.android.server.wm.ActivityStarter");
            Class<?> atms = cl.loadClass("com.android.server.wm.ActivityTaskManagerService");
            Class<?> supervisor = cl.loadClass("com.android.server.wm.ActivityTaskSupervisor");
            Class<?> tcn = cl.loadClass("com.android.server.wm.TaskChangeNotificationController");
            Class<?> keepAlive = cl.loadClass("com.android.server.wm.OplusMirageKeepAlivePolicy");
            Class<?> displayContent = cl.loadClass("com.android.server.wm.DisplayContent");

            // Every minimize path (menu, caption, gesture, auto-collapse) starts here.
            H.hook(toFloat, "handleEvent", a -> {
                int id = H.get(H.get(a.getReference(0), "mTask"), "mTaskId");
                Hang h = new Hang();
                Hang prev = hung.putIfAbsent(id, h);
                if (prev != null) {
                    if (!prev.moved) return false;
                    // A moved task can't be minimized again unless it left our display by a path we missed.
                    unhang(id, "stale");
                    if (hung.putIfAbsent(id, h) != null) return false;
                }
                Log.d(TAG, "minimizing task " + id);
                handler.post(() -> prepare(id, h, 0));
                handler.postDelayed(() -> {
                    if (hung.get(id) == h && !h.moved) unhang(id, "minimize never finished");
                }, PENDING_TIMEOUT_MS);
                return false;
            }, null);

            // Don't pause during the minimize animation.
            H.hook(tfExt, "shouldSkipPauseInRecentsAnim", a -> {
                if (hung.isEmpty()) return false;
                Object tf = H.get(a.getReference(0), "mTaskFragment");
                Hang h = tf != null && tf.getClass().getSimpleName().equals("Task")
                        ? hung.get((int) H.get(tf, "mTaskId")) : null;
                if (h == null || h.moved) return false;
                a.setBoolean(H.RET, true);
                return true;
            }, null);

            // The stock exit hides the task behind home; move it to our display before visibility is recomputed.
            // Plain WM move: Mirage's would reset the flexible-window state (size, hidden caption).
            H.around(ftc, "exitFlexibleTask", (a, original) -> {
                Object task = a.getReference(1);
                int id = H.get(task, "mTaskId");
                Hang h = hung.get(id);
                if (h == null || h.moved) {
                    original.call();
                    return;
                }
                if (!h.ready) {
                    // Minimize without animation: the display (~30 ms) isn't ready yet.
                    unhang(id, "display not ready in time");
                    original.call();
                    return;
                }
                h.pkg = H.call(task, "getBasePackageName");
                h.uid = H.get(task, "effectiveUid");
                h.moved = true;
                Log.i(TAG, "hanging task " + id + " (" + h.pkg + ") on display " + h.display);
                pinFps(h.uid);
                withDeferredVisibility(() -> {
                    original.call();
                    Object root = rootWindowContainer();
                    H.method(root.getClass(), "moveRootTaskToDisplay", 3).invoke(root, id, h.display, false);
                });
            });

            // Keep running with the screen off.
            H.hook(keepAlive, "shouldSleep", a -> {
                Object info = H.get(a.getReference(0), "mDisplayInfo");
                if (!isOurDisplay(H.get(info, "displayId"))) return false;
                a.setBoolean(H.RET, false);
                return true;
            }, null);

            // Match the real screen's touch / color mode: Chrome recreates (and stops media) on that config change.
            H.hook(displayContent, "computeScreenConfiguration", 1, null, a -> {
                if (!isOurDisplay(H.get(a.getReference(0), "mDisplayId"))) return;
                Configuration config = a.getReference(1);
                Configuration main = H.call(H.call(rootWindowContainer(), "getDefaultDisplay"), "getConfiguration");
                config.touchscreen = main.touchscreen;
                config.colorMode = main.colorMode;
            });

            // Bubble tap: the stock restore moves tasks off VIRTUAL displays itself, then uses startActivityFromRecents.
            H.hook(fhc, "startActivityByFloatInfo", a -> {
                restoring.add(H.get(a.getReference(1), "taskId"));
                return false;
            }, a -> {
                int id = H.get(a.getReference(1), "taskId");
                restoring.remove(id);
                unhang(id, "restored from bubble");
            });

            // The restore would cover the live app with its stale minimize-time snapshot until ~1 s after opening.
            H.hook(infoManager, "isSkipRecordSnapshot", a -> {
                if (hung.isEmpty()) return false;
                Hang h = hung.get((int) H.get(H.get(a.getReference(0), "mFlexibleTask"), "mTaskId"));
                if (h == null || !h.moved) return false;
                a.setBoolean(H.RET, true);
                return true;
            }, null);

            // The open animation's first frame leaves the leash uncropped, so the live app peeks out below the bubble.
            H.hook(openAnimSpec, "defaultCallAnimationStart", a -> {
                Object spec = a.getReference(0);
                if (!restoring.contains((int) H.get(H.get(spec, "mAnimTask"), "mTaskId"))) return false;
                SurfaceControl.Transaction t = a.getReference(1);
                t.setCrop(H.<SurfaceControl>get(spec, "mTaskAnimLeash"), new Rect(0, 0, 1, 1));
                return false;
            }, null);

            // Launch from home / recents: move back first, else the transition sees a display change and won't animate.
            H.hook(starter, "execute", 0, a -> {
                if (hung.isEmpty()) return false;
                Intent intent = H.get(H.get(a.getReference(0), "mRequest"), "intent");
                if (intent == null || intent.getComponent() == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                        || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return false;
                String pkg = intent.getComponent().getPackageName();
                // shortcut: matches by package only, so a clone app's launch also ends the hang.
                for (var e : hung.entrySet()) {
                    if (e.getValue().moved && pkg.equals(e.getValue().pkg)) leaveHang(e.getKey(), "launched from home");
                }
                return false;
            }, null);

            H.hook(supervisor, "startActivityFromRecents", a -> {
                int id = a.getInt(3);
                Hang h = hung.get(id);
                if (h != null && h.moved && !restoring.contains(id)) leaveHang(id, "launched from recents");
                return false;
            }, null);

            // Bubble dismissed.
            H.hook(fhc, "dispatchViewRemoved", null, a -> {
                int id = H.get(a.getReference(2), "taskId");
                Hang h = hung.get(id);
                if (!unhang(id, "bubble removed")) return;
                handler.post(() -> {
                    try {
                        if (isTaskOnDisplay(id, h.display)) moveBack(id, false);
                    } catch (Throwable t) {
                        Log.e(TAG, "move back of task " + id + " failed", t);
                    }
                });
            });

            // The override is per uid, so it also slows the app's activities elsewhere (Chrome's Custom Tab inside
            // another app): lift it while one of them is the top resumed activity.
            H.hook(atms, "setLastResumedActivityUncheckLocked", a -> {
                Object r = a.getReference(1);
                int uid = H.<ActivityInfo>get(r, "info").applicationInfo.uid;
                setFront(isOurDisplay(H.call(H.get(r, "task"), "getDisplayId")) ? -1 : uid);
                return false;
            }, null);

            H.hook(tcn, "notifyTaskRemoved", a -> {
                unhang(a.getInt(1), "task removed");
                return false;
            }, null);

            Log.i(TAG, "server hooks installed");
        } catch (Throwable t) {
            Log.e(TAG, "server hooks failed", t);
        }
    }

    /** On {@link #handler}: create the display and wait until Mirage and WM know it. */
    private static void prepare(int taskId, Hang h, int attempt) {
        if (hung.get(taskId) != h) return; // cancelled meanwhile
        try {
            if (h.display == INVALID_DISPLAY) h.display = createDisplay();
            int d = h.display;
            if ((boolean) H.call(mirageService(), "isMirageDisplayId", d) && ensureDisplayContent(d)) {
                h.ready = true;
            } else if (attempt < 40) {
                handler.postDelayed(() -> prepare(taskId, h, attempt + 1), 50);
            } else {
                unhang(taskId, "display " + d + " never became ready");
            }
        } catch (Throwable t) {
            Log.e(TAG, "prepare " + taskId + " failed", t);
            unhang(taskId, "prepare failed");
        }
    }

    /** For a start from home / recents, which brings the task to front right after. */
    private static void leaveHang(int taskId, String why) {
        long ident = Binder.clearCallingIdentity(); // else surface placement in the move fails the caller's WAKE_LOCK check
        try {
            moveBack(taskId, true);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        unhang(taskId, why);
    }

    /** @return true if the task was hung. */
    private static boolean unhang(int taskId, String why) {
        Hang h = hung.remove(taskId);
        if (h == null) return false;
        Log.i(TAG, "unhang task " + taskId + ": " + why);
        if (h.moved) unpinFps(h.uid);
        int d = h.display;
        if (d != INVALID_DISPLAY) handler.postDelayed(() -> {
            try {
                forgetTasksThatLeft(d);
                mirage("releaseVirtualDisplayDevice", d);
            } catch (Throwable t) {
                // Uncaught on our thread would crash system_server.
                Log.e(TAG, "release display " + d + " failed", t);
            }
        }, RELEASE_DELAY_MS);
        return true;
    }

    /**
     * Mirage forgets a display's task only on stack removal, and releasing the display makeInvisible()s and hides
     * every task it still lists, including restored ones already back on display 0.
     */
    private static void forgetTasksThatLeft(int d) throws ReflectiveOperationException {
        synchronized ((Object) H.get(H.get(mirageService(), "mAtms"), "mGlobalLock")) {
            Object display = H.call(H.get(mirageService(), "mWindowController"), "getDevice", d);
            if (display == null) return;
            for (Object id : new ArrayList<>((Set<?>) H.call(display, "getTasks"))) {
                if (!isTaskOnDisplay((Integer) id, d)) H.call(display, "removeTask", id);
            }
        }
    }

    /** The override is per uid: two hung tasks of one app share it. */
    private static synchronized void pinFps(int uid) {
        if (uid == frontUid) frontUid = -1; // the minimized task itself: no longer in front
        if (uid >= 0 && fpsPins.merge(uid, 1, Integer::sum) == 1) setFps(uid, HANG_FPS);
    }

    private static synchronized void unpinFps(int uid) {
        if (uid >= 0 && fpsPins.computeIfPresent(uid, (k, n) -> n > 1 ? n - 1 : null) == null) setFps(uid, 0f);
    }

    private static synchronized void setFront(int uid) {
        int old = frontUid;
        if (old == uid) return;
        frontUid = uid;
        if (fpsPins.containsKey(old)) setFps(old, HANG_FPS);
        if (fpsPins.containsKey(uid)) setFps(uid, 0f);
    }

    private static boolean isOurDisplay(int d) {
        if (d == INVALID_DISPLAY) return false;
        for (Hang h : hung.values()) {
            if (h.display == d) return true;
        }
        return false;
    }

    private static boolean isTaskOnDisplay(int taskId, int d) {
        try {
            Object root = rootWindowContainer();
            synchronized ((Object) H.get(H.get(mirageService(), "mAtms"), "mGlobalLock")) {
                Object task = H.method(root.getClass(), "anyTaskForId", 1).invoke(root, taskId);
                return task != null && (int) H.call(task, "getDisplayId") == d;
            }
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "display lookup for task " + taskId + " failed", e);
            return false;
        }
    }

    /** SurfaceFlinger's per-uid override backdoor; unlike GameManager's it survives a forced rate (1035). 0 = clear. */
    private static void setFps(int uid, float fps) {
        if (uid < 0) return;
        handler.post(() -> {
            Parcel data = Parcel.obtain();
            try {
                IBinder sf = H.callStatic(Class.forName("android.os.ServiceManager"), "getService", "SurfaceFlinger");
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                data.writeInt(uid);
                data.writeFloat(fps);
                sf.transact(SF_SET_FRAME_RATE_OVERRIDE, data, null, 0);
            } catch (Throwable t) {
                Log.e(TAG, "frame rate override " + fps + " for uid " + uid + " failed", t);
            } finally {
                data.recycle();
            }
        });
    }

    private static int createDisplay() throws ReflectiveOperationException {
        DisplayMetrics dm = new DisplayMetrics();
        systemContext().getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm);

        Object o = H.callStatic(loader.loadClass("com.oplus.miragewindow.OplusMirageOptions"), "makeAppKeepModeOptions");
        H.call(o, "setDensityDpi", dm.densityDpi);
        H.call(o, "setXDpi", dm.xdpi);
        H.call(o, "setYDpi", dm.ydpi);
        H.call(o, "setDisplayWidth", Math.min(dm.widthPixels, dm.heightPixels));
        H.call(o, "setDisplayHeight", Math.max(dm.widthPixels, dm.heightPixels));
        Bundle b = H.call(o, "toBundle");
        // Own display group: stays on when the main screen turns off.
        b.putBoolean("mirage:ownDisplayGroup", true);
        return mirage("createVirtualDisplayDevice", b);
    }

    /** WM doesn't create a DisplayContent for keep-alive displays by itself. */
    private static boolean ensureDisplayContent(int d) throws ReflectiveOperationException {
        Object wms = H.get(mirageService(), "mWms");
        synchronized ((Object) H.get(wms, "mGlobalLock")) {
            return H.call(H.get(wms, "mRoot"), "getDisplayContentOrCreate", d) != null;
        }
    }

    /** Runs {@code body} with visibility updates deferred, so a reparented task is never briefly invisible (stopped). */
    private static void withDeferredVisibility(H.Invoker body) throws Throwable {
        Object atms = H.get(mirageService(), "mAtms");
        Object supervisor = H.get(atms, "mTaskSupervisor");
        Object root = H.get(atms, "mRootWindowContainer");
        synchronized ((Object) H.get(atms, "mGlobalLock")) {
            H.call(supervisor, "beginDeferResume");
            H.call(supervisor, "setDeferRootVisibilityUpdate", true);
            try {
                body.call();
            } finally {
                H.call(supervisor, "setDeferRootVisibilityUpdate", false);
                H.call(supervisor, "endDeferResume");
                // Keep focus (keys, IME) on the real screen.
                H.method(root.getClass(), "positionChildAt", 3)
                        .invoke(root, Integer.MAX_VALUE, H.call(root, "getDefaultDisplay"), true);
                H.method(root.getClass(), "updateFocusedWindowLocked", 2).invoke(root, 0, true);
                H.method(root.getClass(), "ensureActivitiesVisible", 0).invoke(root);
                H.method(root.getClass(), "resumeFocusedTasksTopActivities", 0).invoke(root);
            }
        }
    }

    /**
     * Back to display 0 as a plain hidden full-screen background task (what Mirage's move-in used to leave).
     *
     * @param launching hide the activities first, so Mirage's makeInvisible() is a no-op: it queues a stop for the
     *                  still-RESUMED activity that the start never takes back (resumeTopActivity returns early for a
     *                  resumed top), so the next idle stops it unless the launch transition holds it by then.
     */
    private static void moveBack(int taskId, boolean launching) {
        try {
            Object root = rootWindowContainer();
            synchronized ((Object) H.get(H.get(mirageService(), "mAtms"), "mGlobalLock")) {
                Object task = H.method(root.getClass(), "anyTaskForId", 1).invoke(root, taskId);
                if (task != null && (boolean) loader.loadClass("com.android.server.wm.FlexibleWindowUtils")
                        .getMethod("isFlexibleTaskAndHasCaption", task.getClass()).invoke(null, task)) {
                    Object fwms = H.callStatic(
                            loader.loadClass("com.android.server.wm.FlexibleWindowManagerService"), "getInstance", (Object) null);
                    H.call(H.call(fwms, "getFlexibleTaskController"), "resetFlexibleTaskToFullScreen", task);
                }
                if (task != null && launching) {
                    Method hide = H.method(loader.loadClass("com.android.server.wm.ActivityRecord"), "setVisibility", 1);
                    Method forAll = Reflection.getHiddenMethod(
                            loader.loadClass("com.android.server.wm.WindowContainer"), "forAllActivities", Consumer.class);
                    forAll.setAccessible(true);
                    forAll.invoke(task, (Consumer<Object>) r -> {
                        try {
                            if (H.<Boolean>get(r, "mVisibleRequested")) hide.invoke(r, false);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }
            }
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "flexible reset / hide for task " + taskId + " failed", e);
        }
        moveSync(taskId, Display.DEFAULT_DISPLAY);
    }

    /** Mirage's move, run synchronously; moving to display 0 it hides the task first. */
    private static void moveSync(int taskId, int displayId) {
        try {
            Object svc = mirageService();
            // shortcut: R8-numbered class on CPH2723 16.0.2; if a ROM update renames it, the async fallback is used.
            Constructor<?> c = loader.loadClass("com.android.server.wm.OplusMirageWindowManagerService$8")
                    .getDeclaredConstructor(svc.getClass(), int.class, int.class, boolean.class);
            c.setAccessible(true);
            ((Runnable) c.newInstance(svc, taskId, displayId, false)).run();
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "sync move unavailable, falling back to async", e);
            mirage("moveTaskToDisplay", taskId, displayId, false);
        }
    }

    private static Object rootWindowContainer() throws ReflectiveOperationException {
        return H.get(H.get(mirageService(), "mAtms"), "mRootWindowContainer");
    }

    private static Object mirageService() throws ReflectiveOperationException {
        return H.callStatic(loader.loadClass("com.android.server.wm.OplusMirageWindowManagerService"), "getInstance");
    }

    /** Public OplusMirageWindowManager client API. */
    private static <T> T mirage(String method, Object... args) {
        try {
            Object m = H.callStatic(loader.loadClass("com.oplus.miragewindow.OplusMirageWindowManager"), "getInstance");
            return H.call(m, method, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("mirage " + method, e);
        }
    }

    private static Context systemContext() throws ReflectiveOperationException {
        Object at = H.callStatic(Class.forName("android.app.ActivityThread"), "currentActivityThread");
        return H.call(at, "getSystemContext");
    }

    private ServerHooks() {
    }
}
