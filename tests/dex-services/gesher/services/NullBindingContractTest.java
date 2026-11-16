/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedServiceImpl;
import com.gesher.server.ApkPackage;
import com.gesher.server.StandIn;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import sun.misc.Unsafe;

/** Actual ActiveServices admission/publication and LoadedApk's public ServiceConnection dispatch. */
public final class NullBindingContractTest {
    private static final String OWNER = "fixture.nullbinding.owner";
    private static final int PID = 884241, UID = 12622;
    private static final ComponentName SERVICE = new ComponentName(OWNER, OWNER + ".BoundService");
    private static final Unsafe UNSAFE;
    private static int checks;
    private static boolean binder;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private interface Operation { void run() throws Exception; }
    private static void check(boolean okay, String reason) {
        if (!okay) throw new AssertionError(reason);
        checks++;
    }
    private static Class<?> type(String name) throws Exception { return Class.forName("com.gesher.server." + name); }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static Object get(Object object, String name) throws Exception { return field(object.getClass(), name).get(object); }
    private static void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
    private static Object construct(Class<?> type, Class<?>[] parameters, Object... arguments) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters); constructor.setAccessible(true);
        try { return constructor.newInstance(arguments); }
        catch (InvocationTargetException failure) { return rethrow(failure.getCause()); }
    }
    private static Object rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
        throw new AssertionError(failure);
    }
    private static IBinder remote(IBinder target) {
        return new Binder() {
            @Override public IInterface queryLocalInterface(String descriptor) { return null; }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException { return target.transact(code, data, reply, flags); }
        };
    }
    private static ApkPackage code() throws Exception {
        PackageImpl parsed = (PackageImpl) PackageImpl.forTesting(OWNER, "/fixture/nullbinding/base.apk");
        parsed.setEnabled(true); parsed.setTargetSdkVersion(36);
        ParsedServiceImpl service = new ParsedServiceImpl();
        service.setPackageName(OWNER); service.setName(SERVICE.getClassName());
        service.setEnabled(true); service.setExported(true); service.setProcessName(OWNER);
        parsed.addService(service); parsed.hideAsFinal();
        ApkPackage code = (ApkPackage) UNSAFE.allocateInstance(ApkPackage.class);
        set(code, "packageName", OWNER); set(code, "parsed", parsed); set(code, "mUid", UID);
        set(code, "apkPath", "/fixture/nullbinding/base.apk"); set(code, "mDataDir", "/fixture/nullbinding/data");
        set(code, "sharedLibraries", field(type("SharedLibraryGraph"), "EMPTY").get(null));
        return code;
    }
    private static final class Bind {
        final IBinder service, binding;
        final boolean rebind;
        Bind(IBinder service, IBinder binding, boolean rebind) {
            this.service = service; this.binding = binding; this.rebind = rebind;
        }
    }
    private static final class AppThread extends IApplicationThread.Default {
        final IBinder binder = new Binder();
        final List<Bind> binds = new ArrayList<>();
        final List<Bind> unbinds = new ArrayList<>();
        int creates, stops, exits;
        @Override public IBinder asBinder() { return binder; }
        @Override public void scheduleCreateService(IBinder token, ServiceInfo info,
                CompatibilityInfo compatibility, int processState) { creates++; }
        @Override public void scheduleBindService(IBinder token, IBinder bindToken, Intent intent,
                boolean rebind, int processState, long sequence) {
            binds.add(new Bind(token, bindToken, rebind));
        }
        @Override public void scheduleUnbindService(IBinder token, IBinder bindToken, Intent intent) {
            unbinds.add(new Bind(token, bindToken, false));
        }
        @Override public void scheduleStopService(IBinder token) { stops++; }
        @Override public void scheduleExit() { exits++; }
    }
    private static final class Connection implements ServiceConnection {
        final Object dispatcher; // InnerConnection deliberately holds only a weak reference.
        final IServiceConnection callback;
        int nulls, connected, disconnected, died;
        IBinder value;
        Operation onNull;
        Connection(Executor executor) throws Exception {
            Class<?> type = Class.forName("android.app.LoadedApk$ServiceDispatcher");
            dispatcher = construct(type, new Class<?>[]{ServiceConnection.class, Context.class,
                    Executor.class, long.class}, this, new ContextWrapper(null), executor, 0L);
            Method method = type.getDeclaredMethod("getIServiceConnection"); method.setAccessible(true);
            IServiceConnection local = (IServiceConnection) method.invoke(dispatcher);
            callback = binder ? IServiceConnection.Stub.asInterface(remote(local.asBinder())) : local;
        }
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            check(SERVICE.equals(name) && service != null, "Wrong service identity in connected callback");
            connected++; value = service;
        }
        @Override public void onServiceDisconnected(ComponentName name) { disconnected++; }
        @Override public void onBindingDied(ComponentName name) { died++; }
        @Override public void onNullBinding(ComponentName name) {
            check(SERVICE.equals(name), "Wrong service identity in null callback"); nulls++;
            if (onNull != null) try { onNull.run(); }
            catch (Exception failure) { throw new AssertionError("Null callback reentry failed", failure); }
        }
        void nullOnce() {
            check(nulls == 1 && connected == 0 && disconnected == 0 && died == 0,
                    "Published null binding did not invoke onNullBinding exactly once");
        }
    }
    private static final class Harness {
        final Object services;
        final IActivityManager am;
        final AppThread thread = new AppThread();
        @SuppressWarnings("unchecked")
        Harness() throws Exception {
            Class<?> pmType = type("PackageManagerService"), atmType = type("ActivityTaskManagerService");
            Class<?> processType = type("ActivityTaskManagerService$ProcessRecord");
            Object pm = construct(pmType, new Class<?>[]{Collection.class}, List.of(code()));
            Object atm = construct(atmType, new Class<?>[]{pmType}, pm);
            Object implementation = construct(type("ActivityManagerService"), new Class<?>[]{atmType,
                    pmType, type("NotificationManagerService")}, atm, pm, null);
            services = get(implementation, "mServices");
            Object process;
            long original = Binder.clearCallingIdentity();
            try {
                Binder.restoreCallingIdentity(((long) UID << 32) | PID);
                process = construct(processType, new Class<?>[]{atmType, int.class, IApplicationThread.class}, atm, PID, thread);
            } finally { Binder.restoreCallingIdentity(original); }
            set(process, "packageName", OWNER); set(process, "processName", OWNER); set(process, "ready", true);
            ((List<Object>) get(atm, "mProcesses")).add(process);
            if (binder) {
                StandIn.setStrict(true);
                am = IActivityManager.Stub.asInterface(remote(
                        StandIn.of(IActivityManager.class, (IActivityManager) implementation).asBinder()));
            } else am = (IActivityManager) implementation;
        }
        void asApp(Operation action) throws Exception {
            long original = Binder.clearCallingIdentity();
            try {
                Binder.restoreCallingIdentity(((long) UID << 32) | PID);
                action.run();
                check(Binder.getCallingPid() == PID && Binder.getCallingUid() == UID,
                        "Binding operation did not restore the app's calling identity");
            } finally { Binder.restoreCallingIdentity(original); }
        }
        void bind(Connection connection, String action, long flags) throws Exception {
            asApp(() -> check(am.bindService(thread, null, new Intent(action).setComponent(SERVICE), null,
                    connection.callback, flags, OWNER, 0) == 1, "Binding failed to resolve its real parsed service"));
        }
        void start(String action) throws Exception {
            asApp(() -> check(SERVICE.equals(am.startService(thread,
                    new Intent(action).setComponent(SERVICE), null, false, OWNER, null, 0)),
                    "Started service was not resolved through the real ActivityManager"));
        }
        void stop(String action) throws Exception {
            asApp(() -> check(am.stopService(thread,
                    new Intent(action).setComponent(SERVICE), null, 0) == 1,
                    "Started service did not accept its stop request"));
        }
        void completedTrue(Bind attempt) throws Exception {
            asApp(() -> am.unbindFinished(attempt.service, attempt.binding));
        }
        void completedFalse(Bind attempt) throws Exception {
            asApp(() -> am.serviceDoneExecuting(attempt.service, 4 /* UNBIND */, 0, 0));
        }
        void publish(int index, IBinder value) throws Exception {
            Bind bind = thread.binds.get(index);
            asApp(() -> am.publishService(bind.service, bind.binding, value));
        }
        void unbind(Connection connection) throws Exception {
            asApp(() -> check(am.unbindService(connection.callback), "Recorded binding could not be unbound"));
        }
        int connections() throws Exception { return ((Map<?, ?>) get(services, "mConnections")).size(); }
    }

    public static int contracts(boolean realBinder) throws Exception {
        binder = realBinder; checks = 0;
        Harness h = new Harness();
        Connection first = new Connection(Runnable::run), waiting = new Connection(Runnable::run);
        h.bind(first, "null", Context.BIND_AUTO_CREATE);
        h.bind(waiting, "null", 0);
        check(first.nulls == 0 && waiting.nulls == 0 && h.thread.binds.size() == 1,
                "Unpublished binding did not wait for the one onBind result");
        h.publish(0, null); first.nullOnce(); waiting.nullOnce();
        Connection lateAuto = new Connection(Runnable::run), late = new Connection(Runnable::run);
        h.bind(lateAuto, "null", Context.BIND_AUTO_CREATE); lateAuto.nullOnce();
        h.bind(late, "null", 0); late.nullOnce();
        check(h.thread.creates == 1 && h.thread.binds.size() == 1 && h.connections() == 4,
                "Later null-bound clients recreated or rebound the service");

        ArrayDeque<Runnable> posted = new ArrayDeque<>();
        Connection deferred = new Connection(posted::addLast);
        h.bind(deferred, "null", 0);
        check(deferred.nulls == 0 && posted.size() == 1, "ServiceConnection executor was not honored");
        posted.removeFirst().run(); deferred.nullOnce();
        Connection reentrant = new Connection(Runnable::run);
        reentrant.onNull = () -> h.unbind(reentrant);
        h.bind(reentrant, "null", Context.BIND_AUTO_CREATE); reentrant.nullOnce();
        check(h.connections() == 5, "Reentrant null callback retained its released binding");
        for (Connection connection : List.of(first, waiting, lateAuto, late, deferred)) h.unbind(connection);
        check(h.connections() == 0 && h.thread.stops == 1 && h.thread.exits == 1,
                "Null-bound service did not release its last connection and idle process");

        Harness nonnull = new Harness();
        Connection one = new Connection(Runnable::run), two = new Connection(Runnable::run);
        nonnull.bind(one, "binder", Context.BIND_AUTO_CREATE);
        IBinder published = new Binder(); nonnull.publish(0, published);
        nonnull.bind(two, "binder", 0);
        check(one.connected == 1 && two.connected == 1 && one.value == published && two.value == published
                        && one.nulls == 0 && two.nulls == 0 && nonnull.thread.binds.size() == 1,
                "Existing non-null cached publication changed");
        Connection distinct = new Connection(Runnable::run);
        nonnull.bind(distinct, "different-intent", Context.BIND_AUTO_CREATE);
        check(nonnull.thread.binds.size() == 2 && distinct.nulls == 0 && distinct.connected == 0,
                "Another intent's published binding completed a new pending intent");
        nonnull.publish(1, null); distinct.nullOnce();
        nonnull.unbind(one); nonnull.unbind(two); nonnull.unbind(distinct);
        rebindContracts();
        System.out.println("Null binding contracts passed: " + checks + " checks; real LoadedApk onNullBinding, late clients, executor, reentry, cached Binder/onRebind, stale-token and false-unbind lifecycle");
        return checks;
    }
    /** Started services keep one Binder across true/unbind cycles; false requests onBind anew. */
    private static void rebindContracts() throws Exception {
        Harness h = new Harness();
        h.start("cycle");
        Connection first = new Connection(Runnable::run);
        h.bind(first, "cycle", Context.BIND_AUTO_CREATE);
        check(h.thread.creates == 1 && h.thread.binds.size() == 1
                        && !h.thread.binds.get(0).rebind,
                "First bind did not schedule exactly one onBind");
        IBinder published = new Binder();
        h.publish(0, published);
        check(first.connected == 1 && first.value == published,
                "First client did not receive the published Binder");
        h.unbind(first);
        check(h.thread.unbinds.size() == 1 && h.thread.stops == 0,
                "Started service did not retain a live onUnbind request");
        Bind originalUnbind = h.thread.unbinds.get(0);
        h.completedTrue(originalUnbind);
        check(h.thread.binds.size() == 1, "onUnbind(true) with no clients rebound prematurely");
        Connection returned = new Connection(Runnable::run);
        h.bind(returned, "cycle", Context.BIND_AUTO_CREATE);
        check(returned.connected == 1 && returned.value == published
                        && h.thread.binds.size() == 2 && h.thread.binds.get(1).rebind,
                "Returned client did not retain the Binder and schedule onRebind");
        Connection additional = new Connection(Runnable::run);
        h.bind(additional, "cycle", Context.BIND_AUTO_CREATE);
        check(additional.connected == 1 && additional.value == published
                        && h.thread.binds.size() == 2,
                "A second client duplicated an onRebind callback");

        h.unbind(returned);
        check(h.thread.unbinds.size() == 1, "Removing one of two clients ran onUnbind");
        h.unbind(additional);
        check(h.thread.unbinds.size() == 2, "Last client did not request the next onUnbind");
        Bind secondUnbind = h.thread.unbinds.get(1);
        Connection duringFalse = new Connection(Runnable::run);
        h.bind(duringFalse, "cycle", Context.BIND_AUTO_CREATE);
        check(duringFalse.connected == 0 && h.thread.binds.size() == 2,
                "Bind while onUnbind is unresolved consumed a stale publication");
        h.completedTrue(originalUnbind);
        check(duringFalse.connected == 0 && h.thread.binds.size() == 2,
                "An old unbind token completed a new unbind generation");
        h.completedFalse(secondUnbind);
        check(h.thread.binds.size() == 3 && !h.thread.binds.get(2).rebind
                        && duringFalse.connected == 0,
                "onUnbind(false) did not schedule fresh onBind");
        IBinder replacement = new Binder();
        h.publish(2, replacement);
        check(duringFalse.connected == 1 && duringFalse.value == replacement
                        && replacement != published,
                "Fresh onBind did not publish the replacement Binder");
        h.unbind(duringFalse);
        check(h.thread.unbinds.size() == 3, "Third unbind was not scheduled");
        Bind thirdUnbind = h.thread.unbinds.get(2);
        Connection duringTrue = new Connection(Runnable::run);
        h.bind(duringTrue, "cycle", Context.BIND_AUTO_CREATE);
        check(duringTrue.connected == 0, "Pending onUnbind returned a Binder before its result");
        h.completedTrue(thirdUnbind);
        check(duringTrue.connected == 1 && duringTrue.value == replacement
                        && h.thread.binds.size() == 4 && h.thread.binds.get(3).rebind,
                "Client waiting through onUnbind(true) did not rebind to the cached Binder");
        h.completedTrue(thirdUnbind);
        check(h.thread.binds.size() == 4, "Duplicate unbind completion requested two rebinds");

        Connection differentIntent = new Connection(Runnable::run);
        h.bind(differentIntent, "other", Context.BIND_AUTO_CREATE);
        check(h.thread.binds.size() == 5 && !h.thread.binds.get(4).rebind,
                "Distinct intent shared another intent's Binder/rebind state");
        h.publish(4, null);
        differentIntent.nullOnce();
        h.stop("cycle");
        check(h.thread.stops == 0, "Stopping a started service killed its live clients");
        h.unbind(duringTrue);
        check(h.thread.stops == 0, "One intent's last unbind destroyed another bound intent");
        h.unbind(differentIntent);
        check(h.thread.stops == 1 && h.connections() == 0,
                "Last client did not retire the stopped service");
        h.completedTrue(thirdUnbind);
        check(h.thread.binds.size() == 5, "Late callback revived a destroyed service");

        Harness nullService = new Harness();
        nullService.start("null");
        Connection before = new Connection(Runnable::run);
        nullService.bind(before, "null", Context.BIND_AUTO_CREATE);
        nullService.publish(0, null); before.nullOnce();
        nullService.unbind(before);
        nullService.completedTrue(nullService.thread.unbinds.get(0));
        Connection after = new Connection(Runnable::run);
        nullService.bind(after, "null", Context.BIND_AUTO_CREATE);
        after.nullOnce();
        check(nullService.thread.binds.size() == 2 && nullService.thread.binds.get(1).rebind,
                "Published null Binder lost its onRebind(true) lifecycle");
        nullService.stop("null"); nullService.unbind(after);
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime"); contracts(true);
    }
}
