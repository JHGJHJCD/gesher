/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.ActivityManager;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Display;

import com.gesher.server.ActivityTaskManagerService;
import com.gesher.server.StandIn;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/** Actual task records, production aggregation, and generated Binder parcel transport. */
public final class RunningTaskSnapshotTest {
    private static int checks;
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }

    private static Object activity(ActivityTaskManagerService service, int taskId,
            String name, boolean destroyed) throws Exception {
        Class<?> type = Class.forName("com.gesher.server.ActivityTaskManagerService$ActivityRecord");
        Constructor<?> constructor = type.getDeclaredConstructor(ActivityTaskManagerService.class,
                ActivityInfo.class, Intent.class, int.class, int.class, String.class,
                type, String.class, int.class);
        constructor.setAccessible(true);
        ActivityInfo info = new ActivityInfo();
        info.packageName = "com.gesher.task.snapshot";
        info.name = name;
        Intent intent = new Intent("task." + name).setComponent(new ComponentName(info.packageName, name))
                .putExtra("base_marker", name);
        Object result = constructor.newInstance(service, info, intent, taskId, 0,
                info.packageName, null, null, -1);
        if (destroyed) {
            Field state = type.getDeclaredField("state");
            state.setAccessible(true);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class) state.getType(), "DESTROYED");
            state.set(result, value);
        }
        return result;
    }

    private static void snapshot(IActivityTaskManager service) throws Exception {
        List<ActivityManager.RunningTaskInfo> one = service.getTasks(1, false, false,
                Display.INVALID_DISPLAY);
        check(one.size() == 1, "one-task cap");
        ActivityManager.RunningTaskInfo a = one.get(0);
        check(a.taskId == 11 && a.id == 11, "most recent live task wins");
        check(a.baseActivity.getClassName().equals("ABase"), "cap does not replace base with top");
        check(a.topActivity.getClassName().equals("ATop"), "destroyed activity is not top");
        check(a.numActivities == 3, "all live records of selected task are counted");
        check(a.baseIntent.getAction().equals("task.ABase") && a.baseIntent.getExtras() == null,
                "base intent belongs to base and strips extras");
        check(a.displayId == Display.DEFAULT_DISPLAY, "snapshot belongs to default display");

        List<ActivityManager.RunningTaskInfo> two = service.getTasks(2, false, true,
                Display.DEFAULT_DISPLAY);
        check(two.size() == 2, "two-task cap");
        check(two.get(0).baseActivity.equals(a.baseActivity)
                        && two.get(0).topActivity.equals(a.topActivity)
                        && two.get(0).numActivities == a.numActivities,
                "changing result cap does not change task metadata");
        check(two.get(1).taskId == 22 && two.get(1).numActivities == 2
                        && two.get(1).baseActivity.getClassName().equals("BBase")
                        && two.get(1).topActivity.getClassName().equals("BTop"),
                "interleaved older task is complete");
        check("ABase".equals(two.get(0).baseIntent.getStringExtra("base_marker")),
                "keepIntentExtra preserves base extras");
        two.get(0).baseIntent.putExtra("base_marker", "caller mutation");
        check("ABase".equals(service.getTasks(1, false, true, Display.INVALID_DISPLAY)
                        .get(0).baseIntent.getStringExtra("base_marker")),
                "returned intent does not alias task record");
        check(service.getTasks(0, false, false, Display.INVALID_DISPLAY).isEmpty(), "zero cap");
        check(service.getTasks(-1, false, false, Display.INVALID_DISPLAY).isEmpty(), "negative cap");
        check(service.getTasks(10, false, false, 99).isEmpty(), "foreign display is excluded");
        check(service.getTasks(10, false, false, Display.INVALID_DISPLAY).size() == 2,
                "destroyed-only task is excluded");
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        Class<?> pmType = Class.forName("com.gesher.server.PackageManagerService");
        Constructor<?> pmConstructor = pmType.getDeclaredConstructor(java.util.Collection.class);
        pmConstructor.setAccessible(true);
        Object pm = pmConstructor.newInstance(Collections.emptyList());
        Constructor<ActivityTaskManagerService> constructor = ActivityTaskManagerService.class
                .getDeclaredConstructor(pmType);
        constructor.setAccessible(true);
        ActivityTaskManagerService service = constructor.newInstance(pm);
        Field recordsField = ActivityTaskManagerService.class.getDeclaredField("mActivities");
        recordsField.setAccessible(true);
        List<Object> records = (List<Object>) recordsField.get(service);
        records.add(activity(service, 11, "ABase", false));
        records.add(activity(service, 22, "BBase", false));
        records.add(activity(service, 11, "AMiddle", false));
        records.add(activity(service, 22, "BTop", false));
        records.add(activity(service, 11, "ATop", false));
        records.add(activity(service, 11, "ADestroyed", true));
        records.add(activity(service, 33, "CDestroyed", true));
        snapshot(service);

        IBinder target = StandIn.of(IActivityTaskManager.class, service).asBinder();
        IBinder transport = new Binder() {
            @Override public IInterface queryLocalInterface(String descriptor) { return null; }
            @Override protected boolean onTransact(int code, Parcel request, Parcel reply, int flags)
                    throws RemoteException { return target.transact(code, request, reply, flags); }
        };
        IActivityTaskManager proxy = IActivityTaskManager.Stub.asInterface(transport);
        check(proxy != service && proxy.asBinder() == transport, "forced generated Binder proxy");
        snapshot(proxy);
        System.out.println("RunningTaskSnapshotTest all passed: " + checks);
    }
}
