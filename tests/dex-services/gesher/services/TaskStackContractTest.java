/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IActivityTaskManager;
import android.app.IApplicationThread;
import android.app.servertransaction.ClientTransaction;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Display;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedActivityImpl;
import com.gesher.server.ApkPackage;
import com.gesher.server.StandIn;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import sun.misc.Unsafe;

/**
 * Exercises production startActivities, task snapshots and lifecycle handoffs.
 * No Android ActivityThread is simulated: test callbacks explicitly acknowledge
 * the real service's queued transitions in controlled order.
 */
public final class TaskStackContractTest {
    private static final String PKG = "fixture.taskstack";
    private static final int PID = 884370, UID = 12730;
    private static int checks;
    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    private interface Checked { void run() throws Exception; }
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }
    private static Class<?> type(String name) throws Exception {
        return Class.forName("com.gesher.server." + name);
    }
    private static Field field(Class<?> clazz, String name) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }
    private static Object make(Class<?> clazz, Class<?>[] signature, Object... args) throws Exception {
        Constructor<?> ctor = clazz.getDeclaredConstructor(signature);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }
    private static Intent launch(String name) {
        return new Intent("fixture.taskstack." + name)
                .setComponent(new ComponentName(PKG, PKG + "." + name));
    }
    private static ApkPackage code() throws Exception {
        PackageImpl parsed = (PackageImpl) PackageImpl.forTesting(PKG, "/fixture/taskstack/base.apk");
        parsed.setEnabled(true);
        parsed.setTargetSdkVersion(36);
        for (String name : List.of("Root", "Middle", "Top", "Other")) {
            ParsedActivityImpl activity = new ParsedActivityImpl();
            activity.setPackageName(PKG);
            activity.setName(PKG + "." + name);
            activity.setEnabled(true);
            activity.setExported(true);
            parsed.addActivity(activity);
        }
        parsed.hideAsFinal();
        ApkPackage apk = (ApkPackage) UNSAFE.allocateInstance(ApkPackage.class);
        set(apk, "packageName", PKG);
        set(apk, "parsed", parsed);
        set(apk, "mUid", UID);
        set(apk, "apkPath", "/fixture/taskstack/base.apk");
        set(apk, "mDataDir", "/fixture/taskstack/data");
        set(apk, "sharedLibraries", field(type("SharedLibraryGraph"), "EMPTY").get(null));
        return apk;
    }
    private static final class AppThread extends IApplicationThread.Default {
        final IBinder binder = new Binder();
        final List<ClientTransaction> transactions = new ArrayList<>();
        Runnable onSchedule;
        @Override public IBinder asBinder() { return binder; }
        @Override public void scheduleTransaction(ClientTransaction t) {
            transactions.add(t);
            if (onSchedule != null) onSchedule.run();
        }
    }
    private static final class Harness {
        final Object tasks;
        final IActivityTaskManager api;
        final AppThread thread = new AppThread();
        final Class<?> record;
        @SuppressWarnings("unchecked")
        Harness() throws Exception {
            Class<?> pm = type("PackageManagerService"), atm = type("ActivityTaskManagerService");
            record = type("ActivityTaskManagerService$ActivityRecord");
            Object packages = make(pm, new Class<?>[]{Collection.class}, List.of(code()));
            tasks = make(atm, new Class<?>[]{pm}, packages);
            make(type("ActivityManagerService"),
                    new Class<?>[]{atm, pm, type("NotificationManagerService")},
                    tasks, packages, null);
            Class<?> processType = type("ActivityTaskManagerService$ProcessRecord");
            Object process;
            long original = Binder.clearCallingIdentity();
            try {
                Binder.restoreCallingIdentity(((long)UID << 32) | PID);
                process = make(processType, new Class<?>[]{atm, int.class, IApplicationThread.class},
                        tasks, PID, thread);
            } finally { Binder.restoreCallingIdentity(original); }
            set(process, "packageName", PKG);
            set(process, "processName", PKG);
            set(process, "ready", true);
            ((List<Object>)get(tasks, "mProcesses")).add(process);
            IBinder target = StandIn.of(IActivityTaskManager.class,
                    (IActivityTaskManager) tasks).asBinder();
            IBinder transport = new Binder() {
                @Override public IInterface queryLocalInterface(String descriptor) { return null; }
                @Override protected boolean onTransact(int code, Parcel request, Parcel reply, int flags)
                        throws RemoteException {
                    return target.transact(code, request, reply, flags);
                }
            };
            api = IActivityTaskManager.Stub.asInterface(transport);
            check(api != tasks && api.asBinder() == transport,
                    "TaskStack calls did not enter the generated AIDL Parcel transport");
        }
        void asApp(Checked action) throws Exception {
            long previous = Binder.clearCallingIdentity();
            try {
                Binder.restoreCallingIdentity(((long)UID << 32) | PID);
                action.run();
                check(Binder.getCallingPid() == PID && Binder.getCallingUid() == UID,
                        "Caller identity was not preserved");
            } finally { Binder.restoreCallingIdentity(previous); }
        }
        @SuppressWarnings("unchecked")
        List<Object> records() throws Exception {
            return (List<Object>) get(tasks, "mActivities");
        }
        Object record(String name) throws Exception {
            for (Object record : records()) {
                ActivityInfo info = (ActivityInfo) get(record, "info");
                if ((PKG + "." + name).equals(info.name)) return record;
            }
            throw new AssertionError("No record for " + name);
        }
        String state(Object activity) throws Exception {
            return get(activity, "state").toString();
        }
        void acknowledge(String method, Object activity) throws Exception {
            Method callback = tasks.getClass().getDeclaredMethod(method, record);
            callback.setAccessible(true);
            callback.invoke(tasks, activity);
        }
        int start(Intent... intents) throws Exception {
            final int[] status = new int[1];
            asApp(() -> status[0] = api.startActivities(thread, PKG, null, intents, null,
                    null, null, 0));
            return status[0];
        }
    }
    private static void fullStack() throws Exception {
        Harness h = new Harness();
        Intent root = launch("Root").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent middle = launch("Middle");
        Intent top = launch("Top");
        check(h.start(root, middle, top) == ActivityManager.START_SUCCESS,
                "Stack launch was rejected");
        check(h.records().size() == 3, "Only the top Activity was published");
        Object r0 = h.record("Root"), r1 = h.record("Middle"), r2 = h.record("Top");
        int taskId = (Integer) get(r0, "taskId");
        check(taskId == (Integer)get(r1, "taskId")
                        && taskId == (Integer)get(r2, "taskId"),
                "Members of one TaskStackBuilder stack are in different tasks");
        check(get(r1, "waitingFor") == r0 && get(r2, "waitingFor") == r1,
                "Intermediate launches did not form one ordered pause chain");
        check("LAUNCHING".equals(h.state(r0))
                        && "INITIALIZING".equals(h.state(r1))
                        && "INITIALIZING".equals(h.state(r2)),
                "More than the root Activity launched before the first pause");
        List<ActivityManager.RunningTaskInfo> tasks = h.api.getTasks(1, false, true, Display.DEFAULT_DISPLAY);
        check(tasks.size() == 1 && tasks.get(0).numActivities == 3
                        && (PKG + ".Root").equals(tasks.get(0).baseActivity.getClassName())
                        && (PKG + ".Top").equals(tasks.get(0).topActivity.getClassName()),
                "The public task snapshot lost the root, count or top of the stack");
        check(Intent.FLAG_ACTIVITY_NEW_TASK == (tasks.get(0).baseIntent.getFlags()
                & Intent.FLAG_ACTIVITY_NEW_TASK), "Base Intent flags were not retained");
        h.acknowledge("activityResumed", r0);
        check("PAUSING".equals(h.state(r0)) && "INITIALIZING".equals(h.state(r1)),
                "Root was not paused before the next launch");
        h.acknowledge("activityPaused", r0);
        check("LAUNCHING".equals(h.state(r1)) && "INITIALIZING".equals(h.state(r2)),
                "The middle Activity did not launch exclusively after root pause");
        h.acknowledge("activityResumed", r1);
        h.acknowledge("activityPaused", r1);
        check("LAUNCHING".equals(h.state(r2)),
                "Top Activity did not launch after the middle pause");
        h.acknowledge("activityResumed", r2);
        check("RESUMED".equals(h.state(r2)) && h.records().size() == 3,
                "Top Activity was not resumed with the parent history retained");
        check(h.thread.transactions.size() >= 5,
                "The app did not receive Launch/Pause transactions for the full stack");
    }
    private static void invalidBatch() throws Exception {
        Harness h = new Harness();
        Intent root = launch("Root").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        check(h.start(root, null) == ActivityManager.START_INTENT_NOT_RESOLVED
                        && h.records().isEmpty(),
                "Null stack member published a partial task");
        final int[] status = new int[1];
        h.asApp(() -> status[0] = h.api.startActivities(h.thread, PKG, null,
                new Intent[]{root, launch("Top")}, new String[]{"only-one"},
                null, null, 0));
        check(status[0] == ActivityManager.START_INTENT_NOT_RESOLVED
                        && h.records().isEmpty(),
                "Mismatched MIME type array created a partial task");
        check(h.start(root, launch("DoesNotExist")) == ActivityManager.START_INTENT_NOT_RESOLVED,
                "Missing middle Activity did not return the original error");
        check(h.records().size() == 1 && (Boolean)get(h.records().get(0), "finishing"),
                "Failed member left an unowned running root");
    }
    /** Synchronous lifecycle callbacks can win the race before later intents publish. */
    private static void reentrantAndFailure() throws Exception {
        Harness h = new Harness();
        final boolean[] fired = {false};
        h.thread.onSchedule = () -> {
            if (fired[0]) return;
            fired[0] = true;
            try {
                Object root = h.record("Root");
                h.acknowledge("activityResumed", root);
                h.acknowledge("activityPaused", root);
            } catch (Exception failure) { throw new AssertionError(failure); }
        };
        check(h.start(launch("Root").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                launch("Middle"), launch("Top")) == ActivityManager.START_SUCCESS,
                "Reentrant parent callback rejected the rest of the stack");
        check(fired[0] && "PAUSED".equals(h.state(h.record("Root")))
                        && "LAUNCHING".equals(h.state(h.record("Middle")))
                        && "INITIALIZING".equals(h.state(h.record("Top"))),
                "Already-paused root stranded the next stack member");
        h.acknowledge("activityResumed", h.record("Middle"));
        h.acknowledge("activityPaused", h.record("Middle"));
        check("LAUNCHING".equals(h.state(h.record("Top"))),
                "Reentrant first member prevented the final Activity from launching");

        Harness throwing = new Harness();
        throwing.thread.onSchedule = () -> {
            throw new IllegalStateException("injected synchronous ActivityThread failure");
        };
        boolean observed = false;
        try {
            throwing.start(launch("Root").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    launch("Top"));
        } catch (IllegalStateException expected) {
            observed = expected.getMessage().contains("injected");
        }
        check(observed, "ActivityThread exception was swallowed during batch publication");
        Object interruptedRoot = throwing.record("Root");
        check((Boolean)get(interruptedRoot, "finishing"),
                "Exception before the first start returned escaped partial-stack rollback");
        throwing.thread.onSchedule = null;
        final int[] code = {ActivityManager.START_CANCELED};
        throwing.asApp(() -> code[0] = throwing.api.startActivity(throwing.thread, PKG,
                null, launch("Other"), null, null, null, -1, 0, null, null));
        check(code[0] == ActivityManager.START_SUCCESS
                        && get(throwing.record("Other"), "batchOwner") == null,
                "Failed batch leaked ThreadLocal ownership to an ordinary Activity");
    }

    /** A later batch must never roll back an earlier root borrowed via SINGLE_TOP. */
    private static void reusedRootRollback() throws Exception {
        Harness h = new Harness();
        check(h.start(launch("Root").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        == ActivityManager.START_SUCCESS, "First batch failed to publish Root");
        Object preexisting = h.record("Root");
        int taskId = (Integer)get(preexisting, "taskId");
        h.acknowledge("activityResumed", preexisting);
        final int[] result = {ActivityManager.START_SUCCESS};
        Intent reused = launch("Root").addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchTaskId(taskId);
        h.asApp(() -> result[0] = h.api.startActivities(h.thread, PKG, null,
                new Intent[]{reused, launch("DoesNotExist")}, null, null,
                options.toBundle(), 0));
        check(result[0] == ActivityManager.START_INTENT_NOT_RESOLVED,
                "Failed second batch did not report the missing member");
        check(h.records().size() == 1 && h.record("Root") == preexisting
                        && !(Boolean)get(preexisting, "finishing"),
                "Failed later batch finished a previously created SINGLE_TOP Root");
    }

    public static void main(String[] args) throws Exception {
        fullStack();
        invalidBatch();
        reentrantAndFailure();
        reusedRootRollback();
        System.out.println("TaskStackContractTest all passed: " + checks
                + " checks; ordered task history, synchronous/asynchronous pause handoffs, "
                + "reused singleTop ownership, malformed inputs and exception rollback");
    }
}
