/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package gesher.services;

import android.app.IApplicationThread;
import android.app.Service;
import android.app.ServiceStartArgs;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.os.Binder;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Actual ActiveServices lifecycle callbacks with local fake application threads; no APK code runs. */
public final class ServiceRecoveryTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object construct(Class<?> type, Class<?>[] parameters, Object... values) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static final class AppThread extends IApplicationThread.Default {
        final Binder binder = new Binder();
        final CountDownLatch started = new CountDownLatch(1);
        IBinder service;
        ServiceStartArgs args;
        int exits;
        @Override public IBinder asBinder() { return binder; }
        @Override public void scheduleCreateService(IBinder token, ServiceInfo info,
                CompatibilityInfo compat, int processState) { service = token; }
        @Override public void scheduleServiceArgs(IBinder token, ParceledListSlice starts) {
            List<ServiceStartArgs> items = starts.getList();
            if (!items.isEmpty()) args = items.get(items.size() - 1);
            started.countDown();
        }
        @Override public void scheduleExit() { exits++; }
    }

    /** Accepted attachment retains prior generations until their real death cleanup. */
    @SuppressWarnings("unchecked")
    private static Object attachProcess(Object atm, int pid, AppThread thread,
            String pkg, String processName) throws Exception {
        Class<?> atmClass = atm.getClass();
        List<Object> processes = (List<Object>) field(atmClass, "mProcesses").get(atm);
        int before = processes.size();
        method(atmClass, "attachApplication", IApplicationThread.class, int.class, long.class)
                .invoke(atm, thread, pid, 0L);
        require(processes.size() == before + 1, "Attachment discarded an old process before cleanup");
        Object process = processes.get(before);
        synchronized (atm) {
            field(process.getClass(), "packageName").set(process, pkg);
            field(process.getClass(), "processName").set(process, processName);
            field(process.getClass(), "ready").setBoolean(process, true);
        }
        return process;
    }

    private static void exercise(Object apk, Class<?> apkClass, int result,
            boolean exiting, boolean forceStop) throws Exception {
        exercise(apk, apkClass, "org.microg.gms.gcm.McsService", result, exiting, forceStop, 0, false);
    }

    @SuppressWarnings("unchecked")
    private static void exercise(Object apk, Class<?> apkClass, String serviceName, int result,
            boolean exiting, boolean forceStop, int replacements, boolean shutdown) throws Exception {
        Class<?> pmClass = Class.forName("com.gesher.server.PackageManagerService");
        Class<?> atmClass = Class.forName("com.gesher.server.ActivityTaskManagerService");
        Class<?> amClass = Class.forName("com.gesher.server.ActivityManagerService");
        Class<?> processClass = Class.forName("com.gesher.server.ActivityTaskManagerService$ProcessRecord");
        Class<?> servicesClass = Class.forName("com.gesher.server.ActiveServices");
        Class<?> notificationsClass = Class.forName("com.gesher.server.NotificationManagerService");
        Object pm = construct(pmClass, new Class<?>[]{java.util.Collection.class}, Collections.singletonList(apk));
        Object atm = construct(atmClass, new Class<?>[]{pmClass}, pm);
        Object am = construct(amClass, new Class<?>[]{atmClass, pmClass, notificationsClass},
                atm, pm, construct(notificationsClass, new Class<?>[0]));
        Object services = field(amClass, "mServices").get(am);
        String pkg = (String) apkClass.getField("packageName").get(apk);
        ComponentName component = new ComponentName(pkg, serviceName);
        ServiceInfo declared = (ServiceInfo) pmClass.getMethod("getServiceInfo", ComponentName.class,
                long.class, int.class).invoke(pm, component, 0L, 0);
        require(declared != null, "service metadata fixture missing");
        List<Object> processes = (List<Object>) field(atmClass, "mProcesses").get(atm);
        AppThread oldThread = new AppThread();
        Object oldProcess = attachProcess(atm, 999_991, oldThread, pkg, declared.processName);
        Intent start = new Intent().setComponent(component)
                .putExtra("recovery-probe", "preserved");
        method(servicesClass, "startService", IApplicationThread.class, Intent.class,
                String.class, boolean.class, String.class).invoke(services, null, start, null, false, pkg);
        require(oldThread.started.await(2, TimeUnit.SECONDS), "initial service start did not run");
        method(servicesClass, "serviceDoneExecuting", IBinder.class, int.class, int.class, int.class)
                .invoke(services, oldThread.service, 1, oldThread.args.startId, result);
        synchronized (atm) {
            field(processClass, "exiting").setBoolean(oldProcess, exiting);
        }

        AppThread nextThread = null;
        Object nextProcess = null;
        List<AppThread> supersededThreads = new ArrayList<>();
        for (int i = 0; i < replacements; i++) {
            if (nextThread != null) supersededThreads.add(nextThread);
            nextThread = new AppThread();
            nextProcess = attachProcess(atm, 999_991, nextThread, pkg, declared.processName);
        }
        if (shutdown) {
            require(replacements > 0, "Shutdown fixture needs a superseded service owner");
            method(atmClass, "endAllProcesses", long.class).invoke(atm, 0L);
            require(oldThread.exits == 1, "Explicit shutdown did not retire the old generation");
            // A later live attachment makes an erroneous restart observable. It must
            // not restore the old generation's saved recovery intent after shutdown.
            supersededThreads.add(nextThread);
            nextThread = new AppThread();
            nextProcess = attachProcess(atm, 999_991, nextThread, pkg, declared.processName);
        }
        method(atmClass, "processDied", processClass).invoke(atm, oldProcess);
        require(!processes.contains(oldProcess), "Death cleanup retained the original process");
        if (forceStop) method(servicesClass, "stopPackage", String.class).invoke(services, pkg);

        if (nextThread == null) {
            nextThread = new AppThread();
            nextProcess = attachProcess(atm, 999_992, nextThread, pkg, declared.processName);
        }
        boolean restarted = nextThread.started.await(1800, TimeUnit.MILLISECONDS);
        boolean expected = !exiting && !forceStop && !shutdown && result != Service.START_NOT_STICKY;
        require(restarted == expected, "restart policy mismatch for result=" + result
                + " exiting=" + exiting + " forceStop=" + forceStop
                + " replacements=" + replacements + " shutdown=" + shutdown);
        for (AppThread superseded : supersededThreads) {
            require(superseded.started.getCount() == 1, "Restart reached a superseded application thread");
        }
        if (restarted && result == Service.START_REDELIVER_INTENT) {
            require(nextThread.args.args != null
                            && "preserved".equals(nextThread.args.args.getStringExtra("recovery-probe"))
                            && (nextThread.args.flags & Service.START_FLAG_REDELIVERY) != 0,
                    "redelivery lost the original intent/flag");
        }
        if (restarted && result == Service.START_STICKY) {
            require(nextThread.args.args == null, "sticky restart must receive a null intent");
        }
        synchronized (atm) { field(processClass, "ready").setBoolean(nextProcess, false); }
        method(servicesClass, "stopPackage", String.class).invoke(services, pkg);
    }

    /** Mandatory ordinary-APK coverage; does not require the optional microG fixture. */
    public static void pidSupersessionContracts(String apkPath) throws Exception {
        Class<?> apkClass = Class.forName("com.gesher.server.ApkPackage");
        Object apk = apkClass.getMethod("parse", String.class).invoke(null, apkPath);
        String pkg = (String) apkClass.getField("packageName").get(apk);
        Class<?> pmClass = Class.forName("com.gesher.server.PackageManagerService");
        Object pm = construct(pmClass, new Class<?>[]{java.util.Collection.class}, Collections.singletonList(apk));
        PackageInfo info = (PackageInfo) pmClass.getMethod("getPackageInfo", String.class, long.class, int.class)
                .invoke(pm, pkg, (long) PackageManager.GET_SERVICES, 0);
        String serviceName = null;
        if (info != null && info.services != null) {
            for (ServiceInfo declared : info.services) {
                if (declared.exported) { serviceName = declared.name; break; }
            }
        }
        require(serviceName != null, "PID supersession fixture needs an ordinary exported service");
        exercise(apk, apkClass, serviceName, Service.START_REDELIVER_INTENT, false, false, 1, false);
        exercise(apk, apkClass, serviceName, Service.START_STICKY, false, false, 2, false);
        exercise(apk, apkClass, serviceName, Service.START_STICKY, true, false, 2, false);
        exercise(apk, apkClass, serviceName, Service.START_REDELIVER_INTENT, false, true, 2, false);
        exercise(apk, apkClass, serviceName, Service.START_STICKY, false, false, 2, true);
        System.out.println("ServiceRecoveryTest: mandatory PID supersession, prior exit,"
                + " force-stop and shutdown recovery contracts passed");
    }

    public static void main(String[] args) throws Exception {
        Class<?> apkClass = Class.forName("com.gesher.server.ApkPackage");
        Object apk = apkClass.getMethod("parse", String.class).invoke(null, args[0]);
        exercise(apk, apkClass, Service.START_REDELIVER_INTENT, false, false);
        exercise(apk, apkClass, Service.START_STICKY, false, false);
        exercise(apk, apkClass, Service.START_NOT_STICKY, false, false);
        exercise(apk, apkClass, Service.START_REDELIVER_INTENT, true, false);
        exercise(apk, apkClass, Service.START_REDELIVER_INTENT, false, true);
        System.out.println("ServiceRecoveryTest: redelivery, sticky, non-sticky and force-stop policies passed");
    }
}
