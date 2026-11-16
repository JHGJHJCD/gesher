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
        Bind(IBinder service, IBinder binding) { this.service = service; this.binding = binding; }
    }
    private static final class AppThread extends IApplicationThread.Default {
        final IBinder binder = new Binder();
        final List<Bind> binds = new ArrayList<>();
        int creates, stops, exits;
        @Override public IBinder asBinder() { return binder; }
        @Override public void scheduleCreateService(IBinder token, ServiceInfo info,
                CompatibilityInfo compatibility, int processState) { creates++; }
        @Override public void scheduleBindService(IBinder token, IBinder bindToken, Intent intent,
                boolean rebind, int processState, long sequence) {
            check(!rebind, "Initial bind unexpectedly became a rebind");
            binds.add(new Bind(token, bindToken));
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
        System.out.println("Null binding contracts passed: " + checks + " checks; real LoadedApk onNullBinding, late clients, executor, reentry, cleanup and non-null controls");
        return checks;
    }
    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime"); contracts(true);
    }
}
