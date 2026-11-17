/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import android.app.Activity;
import android.app.ContentProviderHolder;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.DestroyActivityItem;
import android.content.ComponentName;
import android.content.Context;
import android.content.IContentProvider;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedActivityImpl;
import com.android.internal.pm.pkg.component.ParsedIntentInfoImpl;
import com.android.internal.pm.pkg.component.ParsedProviderImpl;
import com.android.internal.pm.pkg.component.ParsedServiceImpl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import sun.misc.Unsafe;

/** Unchanged service/API surface: also compiles against the pre-fix PR head. */
public final class PackageEnabledLifecycleFixture {
    private static final String OWNER = "fixture.enabled.lifecycle.owner";
    private static final String CLIENT = "fixture.enabled.lifecycle.client";
    private static final int UID = 12345;
    private static final int KEEP = PackageManager.DONT_KILL_APP;
    private static final Unsafe UNSAFE;
    private static int checks, nextPid = 817400;
    private static boolean binder;
    private static volatile int fakeChild;
    private static volatile boolean childKilled;
    static {
        try { Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); UNSAFE = (Unsafe)f.get(null); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private interface Operation { void run() throws Exception; }
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason); checks++;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static Object get(Object target, String name) throws Exception { return field(target.getClass(), name).get(target); }
    private static void set(Object target, String name, Object value) throws Exception { field(target.getClass(), name).set(target, value); }
    private static ComponentName part(String suffix) { return new ComponentName(OWNER, OWNER + "." + suffix); }
    private static Intent intent(String suffix) { return new Intent().setComponent(part(suffix)); }
    private static void as(ActivityTaskManagerService.ProcessRecord app, Operation operation) throws Exception {
        long old = Binder.clearCallingIdentity();
        try { Binder.restoreCallingIdentity(((long)app.uid << 32) | (app.pid & 0xffffffffL)); operation.run(); }
        finally { Binder.restoreCallingIdentity(old); }
    }
    private static IBinder remote(IBinder target) {
        return new Binder() {
            @Override public IInterface queryLocalInterface(String descriptor) { return null; }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException { return target.transact(code, data, reply, flags); }
        };
    }
    private static ApkPackage code(String name) throws Exception {
        return code(name, false);
    }
    private static ApkPackage code(String name, boolean collidingAliases) throws Exception {
        PackageImpl parsed = (PackageImpl)PackageImpl.forTesting(name, "/fixture/" + name + "/base.apk");
        parsed.setEnabled(true); parsed.setTargetSdkVersion(36);
        for (String suffix : List.of("A", "B")) {
            ParsedServiceImpl service = new ParsedServiceImpl();
            service.setName(name + ".Service" + suffix); service.setPackageName(name);
            service.setEnabled(true); service.setExported(true); service.setProcessName(name);
            parsed.addService(service);
            ParsedActivityImpl activity = new ParsedActivityImpl();
            activity.setName(name + ".Activity" + suffix); activity.setPackageName(name);
            activity.setEnabled(true); activity.setExported(true); activity.setProcessName(name);
            parsed.addActivity(activity);
            ParsedActivityImpl receiver = new ParsedActivityImpl();
            receiver.setName(name + ".Receiver" + suffix); receiver.setPackageName(name);
            receiver.setEnabled(true); receiver.setExported(true); receiver.setProcessName(name);
            ParsedIntentInfoImpl filter = new ParsedIntentInfoImpl();
            filter.getIntentFilter().addAction(name + ".RECEIVE");
            filter.getIntentFilter().setPriority(suffix.equals("A") ? 10 : 0);
            receiver.addIntent(filter); parsed.addReceiver(receiver);
            ParsedProviderImpl provider = new ParsedProviderImpl();
            provider.setName(name + ".Provider" + suffix); provider.setPackageName(name);
            provider.setEnabled(true); provider.setExported(true); provider.setProcessName(name);
            provider.setAuthority(name + ".provider" + suffix); parsed.addProvider(provider);
        }
        if (collidingAliases) for (String suffix : List.of("ServiceA", "ReceiverA", "ProviderA")) {
            ParsedActivityImpl alias = new ParsedActivityImpl();
            alias.setName(name + "." + suffix); alias.setPackageName(name); alias.setEnabled(false);
            alias.setTargetActivity(name + ".ActivityB"); alias.setProcessName(name); parsed.addActivity(alias);
        }
        parsed.hideAsFinal();
        ApkPackage result = (ApkPackage)UNSAFE.allocateInstance(ApkPackage.class);
        set(result, "packageName", name); set(result, "parsed", parsed); set(result, "apkPath", parsed.getBaseApkPath());
        set(result, "mDataDir", "/fixture/" + name + "/data"); set(result, "mUid", UID);
        set(result, "sharedLibraries", field(SharedLibraryGraph.class, "EMPTY").get(null));
        return result;
    }
    private static final class RecordingThread extends IApplicationThread.Default {
        final IBinder binder = new Binder();
        final Harness h;
        final List<String> creates = new ArrayList<>(), starts = new ArrayList<>(), stops = new ArrayList<>();
        final List<String> receivers = new ArrayList<>();
        final List<Integer> receiverResults = new ArrayList<>();
        final List<IBinder> providerDeaths = new ArrayList<>();
        final List<IBinder> destroys = new ArrayList<>();
        final Map<IBinder, String> services = new java.util.HashMap<>();
        int exits, binds;
        Operation onCreate, onBind, onExit;
        RecordingThread(Harness h) { this.h = h; }
        @Override public IBinder asBinder() { return binder; }
        void invoke(Operation operation) {
            h.unlocked();
            if (operation != null) try { operation.run(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }
        @Override public void scheduleCreateService(IBinder token, ServiceInfo info,
                CompatibilityInfo compatibility, int state) {
            services.put(token, info.name); creates.add(info.name); invoke(onCreate);
        }
        @Override public void scheduleServiceArgs(IBinder token, ParceledListSlice args) {
            h.unlocked(); starts.add(services.get(token));
        }
        @Override public void scheduleStopService(IBinder token) { h.unlocked(); stops.add(services.get(token)); }
        @Override public void scheduleBindService(IBinder token, IBinder binding, Intent intent,
                boolean rebind, int state, long sequence) { binds++; invoke(onBind); }
        @Override public void scheduleExit() { exits++; invoke(onExit); }
        @Override public void unstableProviderDied(IBinder provider) { h.unlocked(); providerDeaths.add(provider); }
        @Override public void scheduleReceiver(Intent intent, ActivityInfo info, CompatibilityInfo compat,
                int code, String data, Bundle extras, boolean ordered, boolean delivered, int user,
                int state, int uid, String sender) {
            h.unlocked(); receivers.add(info.name); receiverResults.add(code);
        }
        @Override public void scheduleTransaction(ClientTransaction transaction) {
            h.unlocked();
            for (var item : transaction.getTransactionItems()) {
                if (item instanceof DestroyActivityItem destroy) destroys.add(destroy.getActivityToken());
            }
        }
    }
    private static final class Connection extends IServiceConnection.Stub {
        final Harness h;
        int disconnected, died;
        Connection(Harness h) { this.h = h; }
        @Override public void connected(ComponentName name, IBinder service, android.app.IBinderSession session, boolean dead) {
            h.unlocked(); if (service == null) disconnected++; if (dead) died++;
        }
    }
    private static final class Harness {
        final ApkPackage owner = code(OWNER), client = code(CLIENT);
        final PackageManagerService pm = new PackageManagerService(List.of(owner, client), false);
        final ActivityTaskManagerService atm = new ActivityTaskManagerService(pm);
        final ActivityManagerService am = new ActivityManagerService(atm, pm, null);
        final IPackageManager api;
        final Object services = get(am, "mServices"), broadcasts = get(am, "mBroadcasts"), providers = get(am, "mProviders");
        final ArrayDeque<Runnable> events = new ArrayDeque<>();
        final List<Intent> notifications = new ArrayList<>();
        final List<ActivityTaskManagerService.ProcessRecord> processes;
        final List<?> pending;
        Harness() throws Exception {
            set(pm, "mPackageEventExecutor", (Executor)events::add);
            set(pm, "mPackageEventSender", (Consumer<Intent>)event -> {
                unlocked(); notifications.add(event); broadcast(event, false, null, 0);
            });
            api = binder ? IPackageManager.Stub.asInterface(remote(StandIn.of(IPackageManager.class, pm).asBinder())) : pm;
            processes = (List<ActivityTaskManagerService.ProcessRecord>)get(atm, "mProcesses");
            pending = (List<?>)get(atm, "mPendingWork");
        }
        void unlocked() {
            check(!Thread.holdsLock(pm) && !Thread.holdsLock(atm) && !Thread.holdsLock(services)
                    && !Thread.holdsLock(broadcasts) && !Thread.holdsLock(providers), "Application callback ran under a lifecycle monitor");
        }
        ActivityTaskManagerService.ProcessRecord process(String name, boolean ready) throws Exception {
            RecordingThread thread = new RecordingThread(this);
            long old = Binder.clearCallingIdentity();
            ActivityTaskManagerService.ProcessRecord app;
            try { Binder.restoreCallingIdentity(((long)UID << 32) | nextPid);
                app = atm.new ProcessRecord(nextPid++, thread); }
            finally { Binder.restoreCallingIdentity(old); }
            app.packageName = name; app.processName = name; app.ready = ready;
            processes.add(app); return app;
        }
        RecordingThread thread(ActivityTaskManagerService.ProcessRecord app) { return (RecordingThread)app.thread; }
        void start(String suffix) {
            check(part(suffix).equals(am.startService(null, intent(suffix), null, false, "android", null, 0)), "Service failed to start");
        }
        void component(String suffix, int value, int flags) throws Exception {
            api.setComponentEnabledSetting(part(suffix), value, flags, 0, "android");
        }
        void application(int value, int flags) throws Exception {
            api.setApplicationEnabledSetting(OWNER, value, flags, 0, "android");
        }
        void drain() { while (!events.isEmpty()) events.remove().run(); }
        void ready(ActivityTaskManagerService.ProcessRecord app) throws Exception {
            as(app, () -> am.finishAttachApplication(0, 0));
        }
        Map<?, ?> services() throws Exception { return (Map<?, ?>)get(services, "mServices"); }
        void broadcast(Intent intent, boolean ordered, IIntentReceiver result, int code) {
            am.broadcastIntent(null, intent, null, result, code, null, null, null,
                    android.app.AppOpsManager.OP_NONE, null, ordered, false, 0);
        }
        void finishReceiver(ActivityTaskManagerService.ProcessRecord app, int code, boolean abort) throws Exception {
            as(app, () -> am.finishReceiver(app.thread.asBinder(), code, null, null, abort, 0));
        }
    }

    private static void services() throws Exception {
        Harness h = new Harness(); var app = h.process(OWNER, true); RecordingThread t = h.thread(app);
        h.start("ServiceA"); h.start("ServiceB");
        Connection connection = new Connection(h);
        check(h.am.bindService(null, null, intent("ServiceA"), null, connection, Context.BIND_AUTO_CREATE,
                "android", 0) == 1, "Live service binding was not accepted");
        h.component("ServiceA", 2, KEEP); h.drain();
        check(t.stops.equals(List.of(OWNER + ".ServiceA")), "DONT_KILL_APP left the disabled running service alive");
        check(!h.services().containsKey(part("ServiceA")) && h.services().containsKey(part("ServiceB")),
                "Component cleanup removed a peer or retained the disabled service");
        check(connection.disconnected == 1 && connection.died == 1,
                "Disabled service did not retire its auto-create binding as binding death");
        check(((Map<?, ?>)get(h.services, "mConnections")).isEmpty(),
                "Disabled binding retained an empty client-capability entry");
        check(t.exits == 0 && !app.exiting, "DONT_KILL_APP retired the shared process");
        h.component("ServiceA", 1, KEEP); h.drain(); h.start("ServiceA");
        check(t.creates.size() == 3 && t.starts.size() == 3, "Explicit re-enable/start reused the retired service generation");

        Harness last = new Harness(); var lastApp = last.process(OWNER, true);
        last.start("ServiceA"); last.component("ServiceA", 2, KEEP); last.drain();
        check(last.thread(lastApp).stops.size() == 1 && last.thread(lastApp).exits == 0 && !lastApp.exiting,
                "Stopping the last disabled service violated DONT_KILL_APP");

        Harness reentrant = new Harness(); var reentrantApp = reentrant.process(OWNER, true);
        reentrant.thread(reentrantApp).onCreate = () -> reentrant.component("ServiceA", 2, KEEP);
        reentrant.start("ServiceA");
        check(reentrant.thread(reentrantApp).starts.isEmpty(), "A disable during onCreate delivered queued start args");
        check(reentrant.thread(reentrantApp).stops.size() == 1 && reentrant.services().isEmpty(),
                "A disable before event dispatch did not retire a partially created service");
        reentrant.drain();

        Harness binding = new Harness(); var bindingApp = binding.process(OWNER, true);
        Connection bound = new Connection(binding);
        binding.thread(bindingApp).onBind = () -> { binding.component("ServiceA", 2, KEEP); binding.drain(); };
        check(binding.am.bindService(null, null, intent("ServiceA"), null, bound, Context.BIND_AUTO_CREATE,
                "android", 0) == 1, "Reentrant binding failed");
        check(bound.disconnected == 1 && bound.died == 1 && binding.thread(bindingApp).stops.size() == 1
                && binding.thread(bindingApp).exits == 0 && binding.services().isEmpty(),
                "Cleanup during binding dispatch lost final stop or killed the shared process");

        Harness families = new Harness(); families.pm.add(code(OWNER, true));
        var familyApp = families.process(OWNER, true);
        check(families.pm.getActivityInfo(part("ServiceA"), 0, 0) == null
                && families.pm.getServiceInfo(part("ServiceA"), 0, 0) != null,
                "Cross-family alias metadata fixture has no independent manifest defaults");
        families.start("ServiceA"); families.broadcast(intent("ReceiverA"), false, null, 0);
        ContentProviderHolder provider = holder(families, "A");
        as(familyApp, () -> families.am.publishContentProviders(familyApp.thread, List.of(provider)));
        check(families.thread(familyApp).creates.size() == 1 && families.thread(familyApp).receivers.size() == 1
                && ((Map<?, ?>)get(families.providers, "mPublished")).containsKey(OWNER + ".providerA"),
                "Disabled activity-alias default shadowed an enabled service, receiver or provider of the same name");
    }

    private static void pending() throws Exception {
        for (boolean eventFirst : List.of(false, true)) for (int flags : new int[]{KEEP, 0}) {
            Harness h = new Harness(); var app = h.process(OWNER, false);
            h.start("ServiceA"); check(h.pending.size() == 1, "Queued start never reached production withProcess");
            h.application(2, flags);
            if (eventFirst) h.drain();
            h.ready(app);
            check(h.thread(app).creates.isEmpty() && h.thread(app).starts.isEmpty(),
                    "Whole-app disable dispatched service work through finishAttachApplication; eventFirst=" + eventFirst);
            check(h.services().isEmpty(), "Rejected attach work retained its service reservation until the event");
            h.drain();
            check(h.pending.isEmpty() && h.services().isEmpty(), "Disabled package retained pending service admission");
            check(h.thread(app).exits == (flags == 0 ? 1 : 0), "Queued disable ignored process-restart flags");
        }
        for (boolean eventFirst : List.of(false, true)) {
            Harness h = new Harness(); var app = h.process(OWNER, false);
            h.start("ServiceA"); h.start("ServiceB"); h.component("ServiceA", 2, KEEP);
            if (eventFirst) h.drain();
            h.ready(app); h.drain();
            check(h.thread(app).creates.equals(List.of(OWNER + ".ServiceB"))
                    && h.thread(app).starts.equals(List.of(OWNER + ".ServiceB")),
                    "Component-only disable dispatched its work or canceled its pending peer");
            check(h.pending.isEmpty() && h.services().size() == 1 && !app.exiting,
                    "Pending component cleanup did not converge without killing its peer");
        }
        Harness reenabled = new Harness(); var reenabledApp = reenabled.process(OWNER, false);
        reenabled.start("ServiceA"); reenabled.component("ServiceA", 2, KEEP); reenabled.ready(reenabledApp);
        check(reenabled.services().isEmpty(), "Attach cancellation kept old start authority available for a later enable");
        reenabled.component("ServiceA", 1, KEEP); reenabled.drain();
        check(reenabled.thread(reenabledApp).creates.isEmpty(), "Re-enable resurrected canceled pre-attach work");
        reenabled.start("ServiceA");
        check(reenabled.thread(reenabledApp).creates.size() == 1 && reenabled.thread(reenabledApp).starts.size() == 1,
                "Explicit start after re-enable did not create a fresh service");
    }

    private static void events() throws Exception {
        Harness h = new Harness(); var app = h.process(OWNER, true);
        h.start("ServiceA"); h.start("ServiceB");
        h.component("ServiceA", 2, KEEP); h.component("ServiceB", 2, KEEP); h.drain();
        check(h.thread(app).stops.size() == 2 && h.services().isEmpty(), "Coalescing B discarded still-disabled A cleanup");
        check(h.thread(app).exits == 0, "Two KEEP changes retired their process");

        Harness enabled = new Harness(); var valid = enabled.process(OWNER, true);
        enabled.start("ServiceA"); enabled.component("ServiceA", 2, 0); enabled.component("ServiceA", 1, KEEP);
        enabled.drain();
        check(enabled.thread(valid).stops.isEmpty() && enabled.thread(valid).exits == 0,
                "A stale disable event retired a component re-enabled before dispatch");

        Harness mixed = new Harness(); var mixedApp = mixed.process(OWNER, true);
        mixed.start("ServiceA"); mixed.start("ServiceB"); mixed.component("ServiceA", 2, 0);
        mixed.component("ServiceB", 2, KEEP); mixed.drain();
        check(mixed.thread(mixedApp).exits == 1 && mixed.services().isEmpty(),
                "Unrelated KEEP change canceled a still-applicable non-KEEP restart");

        Harness batch = new Harness(); var batchApp = batch.process(OWNER, true);
        batch.start("ServiceA"); batch.start("ServiceB");
        batch.api.setComponentEnabledSettings(List.of(
                new PackageManager.ComponentEnabledSetting(part("ServiceA"), 2, 0),
                new PackageManager.ComponentEnabledSetting(part("ServiceB"), 2, 0)), 0, "android");
        batch.component("ServiceA", 1, KEEP); batch.drain();
        check(batch.thread(batchApp).stops.equals(List.of(OWNER + ".ServiceB"))
                && batch.thread(batchApp).exits == 1,
                "Re-enabling one batch entry canceled another entry's required process restart");

        Harness unrelated = new Harness(); var owner = unrelated.process(OWNER, true);
        unrelated.start("ServiceA"); unrelated.component("ServiceA", 2, 0);
        unrelated.api.setApplicationEnabledSetting(CLIENT, 2, KEEP, 0, "android"); unrelated.drain();
        check(unrelated.thread(owner).stops.size() == 1 && unrelated.thread(owner).exits == 1,
                "Another package's snapshot suppressed owner cleanup");

        Harness replacement = new Harness(); replacement.component("ServiceA", 2, 0);
        replacement.pm.add(code(OWNER)); var newer = replacement.process(OWNER, true); replacement.start("ServiceB");
        replacement.drain();
        check(replacement.thread(newer).exits == 0 && replacement.thread(newer).stops.isEmpty(),
                "Old installation event retired replacement work");

        Harness spoof = new Harness(); var victim = spoof.process(OWNER, true); var attacker = spoof.process(CLIENT, true);
        spoof.start("ServiceA"); spoof.component("ServiceA", 2, KEEP);
        Intent fake = new Intent(Intent.ACTION_PACKAGE_CHANGED, android.net.Uri.parse("package:" + OWNER))
                .putExtra(Intent.EXTRA_UID, UID).putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST,
                        new String[]{OWNER + ".ServiceA"}).putExtra(Intent.EXTRA_DONT_KILL_APP, false);
        try { as(attacker, () -> spoof.broadcast(fake, false, null, 0)); }
        catch (SecurityException expected) { /* Protected broadcast rejection is also correct. */ }
        spoof.broadcast(fake, false, null, 0); // Even notification from the server is not a lifecycle command.
        check(spoof.thread(victim).stops.isEmpty() && spoof.thread(victim).exits == 0,
                "Untrusted PACKAGE_CHANGED extras acquired lifecycle authority");
        spoof.drain(); check(spoof.thread(victim).stops.size() == 1, "Trusted PM change did not perform cleanup");
    }

    private static void broadcasts() throws Exception {
        for (boolean eventFirst : List.of(false, true)) {
            Harness h = new Harness(); var app = h.process(OWNER, false); h.start("ServiceB");
            h.broadcast(intent("ReceiverA"), false, null, 0); h.broadcast(intent("ReceiverB"), false, null, 0);
            h.component("ReceiverA", 2, KEEP); if (eventFirst) h.drain();
            h.ready(app); h.drain();
            check(h.thread(app).receivers.equals(List.of(OWNER + ".ReceiverB")),
                    "Queued legacy receiver ignored disabled state or canceled its peer");
            h.finishReceiver(app, 0, false);
            check(((List<?>)get(h.broadcasts, "mManifest")).isEmpty() && h.pending.isEmpty(),
                    "Disabled manifest receiver left a pending reservation");
        }
        Harness legacy = new Harness(); var app = legacy.process(OWNER, true); legacy.start("ServiceB");
        legacy.broadcast(intent("ReceiverA"), false, null, 0); legacy.broadcast(intent("ReceiverB"), false, null, 0);
        legacy.component("ReceiverA", 2, KEEP); legacy.drain();
        check(legacy.thread(app).receivers.size() == 1, "Cleanup reused an in-flight receiver acknowledgement slot");
        legacy.finishReceiver(app, 0, false);
        check(legacy.thread(app).receivers.equals(List.of(OWNER + ".ReceiverA", OWNER + ".ReceiverB")),
                "The first receiver's finish did not release exactly its own slot");
        legacy.finishReceiver(app, 0, false);
        check(legacy.thread(app).exits == 0, "Receiver cleanup killed an unrelated service process");

        Harness ordered = new Harness(); var orderedApp = ordered.process(OWNER, true); ordered.start("ServiceB");
        List<Integer> result = new ArrayList<>();
        IIntentReceiver callback = new IIntentReceiver.Stub() {
            @Override public void performReceive(Intent intent, int code, String data, Bundle extras,
                    boolean orderedFlag, boolean sticky, int user) { ordered.unlocked(); result.add(code); }
        };
        ordered.broadcast(new Intent(OWNER + ".RECEIVE").setPackage(OWNER), true, callback, 5);
        ordered.component("ReceiverA", 2, KEEP); ordered.drain();
        check(ordered.thread(orderedApp).receivers.size() == 1 && result.isEmpty(),
                "Disabled active ordered receiver lost acknowledgement ownership");
        ordered.finishReceiver(orderedApp, 99, true);
        check(ordered.thread(orderedApp).receiverResults.equals(List.of(5, 5)),
                "Retired receiver changed the ordered result or aborted its enabled peer");
        ordered.finishReceiver(orderedApp, 7, false);
        check(result.equals(List.of(7)), "Ordered chain failed to complete after component cleanup");
    }

    private static ContentProviderHolder holder(Harness h, String suffix) {
        ProviderInfo info = h.pm.getProviderInfo(part("Provider" + suffix), 0, 0);
        ContentProviderHolder holder = new ContentProviderHolder(info);
        IBinder binder = new Binder();
        holder.provider = (IContentProvider)Proxy.newProxyInstance(PackageEnabledLifecycleFixture.class.getClassLoader(),
                new Class<?>[]{IContentProvider.class}, (proxy, method, args) -> {
                    if (method.getName().equals("asBinder")) return binder;
                    throw new AssertionError("Unexpected provider application invocation " + method);
                });
        return holder;
    }
    private static ContentProviderHolder acquire(Harness h, ActivityTaskManagerService.ProcessRecord client,
            String suffix, boolean stable) throws Exception {
        ContentProviderHolder[] result = new ContentProviderHolder[1];
        as(client, () -> result[0] = h.am.getContentProvider(client.thread, CLIENT, OWNER + ".provider" + suffix, 0, stable));
        return result[0];
    }
    private static void providers() throws Exception {
        Harness h = new Harness(); var owner = h.process(OWNER, true); h.start("ServiceB");
        var unstable = h.process(CLIENT, true); var stable = h.process(CLIENT, true);
        ContentProviderHolder a = holder(h, "A"), b = holder(h, "B");
        as(owner, () -> h.am.publishContentProviders(owner.thread, List.of(a, b)));
        ContentProviderHolder lost = acquire(h, unstable, "A", false), peer = acquire(h, unstable, "B", true);
        check(lost != null && peer != null && acquire(h, stable, "A", true) != null, "Provider references were not acquired");
        h.component("ProviderA", 2, KEEP);
        check(acquire(h, unstable, "A", false) == null && !h.am.refContentProvider(lost.connection, 0, 0),
                "Disabled provider remained acquireable before cleanup event");
        h.drain();
        check(!((Map<?, ?>)get(h.providers, "mPublished")).containsKey(OWNER + ".providerA")
                && ((Map<?, ?>)get(h.providers, "mPublished")).containsKey(OWNER + ".providerB"),
                "Provider cleanup removed a peer authority or retained the disabled one");
        check(h.thread(unstable).providerDeaths.equals(List.of(a.provider.asBinder())) && h.thread(unstable).exits == 0,
                "Unstable provider client did not receive scoped invalidation");
        check(h.thread(stable).exits == 1 && stable.exiting, "Stable provider dependency was left alive");
        check(!h.am.refContentProvider(lost.connection, 0, 0) && h.am.refContentProvider(peer.connection, 0, 0),
                "Disabled provider reference survived or peer reference was retired");
        check(h.thread(owner).exits == 0 && !owner.exiting, "Provider cleanup violated owner DONT_KILL_APP");
        try { as(owner, () -> h.am.publishContentProviders(owner.thread, List.of(a))); }
        catch (SecurityException expected) { checks++; }
        check(!((Map<?, ?>)get(h.providers, "mPublished")).containsKey(OWNER + ".providerA"),
                "Late provider publication resurrected a disabled authority");

        Harness waiting = new Harness(); var waitingClient = waiting.process(CLIENT, true);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try { result.set(acquire(waiting, waitingClient, "A", false)); }
            catch (Throwable failure) { result.set(failure); }
        }, "enabled-provider-waiter");
        request.setDaemon(true); request.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            synchronized (waiting.atm) { if (!waiting.pending.isEmpty()) break; }
            Thread.sleep(2);
        }
        synchronized (waiting.atm) { check(!waiting.pending.isEmpty(), "Provider wait did not reserve a process"); }
        waiting.component("ProviderA", 2, KEEP); waiting.drain(); request.join(3000);
        check(!request.isAlive() && result.get() == null && waiting.pending.isEmpty(),
                "Disabled provider left its acquisition waiter or process admission pending: " + result.get());
    }

    private static void activities() throws Exception {
        for (boolean eventFirst : List.of(false, true)) {
            Harness h = new Harness(); var app = h.process(OWNER, false); h.start("ServiceB");
            ActivityTaskManagerService.ActivityRecord result = h.atm.new ActivityRecord(
                    h.pm.getActivityInfo(part("ActivityB"), 0, 0), intent("ActivityB"), 1, 0, "android", null, null, -1);
            ActivityTaskManagerService.ActivityRecord queued = h.atm.new ActivityRecord(
                    h.pm.getActivityInfo(part("ActivityA"), 0, 0), intent("ActivityA"), 2, 0, "android", result, "result", 19);
            ((List<ActivityTaskManagerService.ActivityRecord>)get(h.atm, "mActivities")).addAll(List.of(result, queued));
            result.state = ActivityTaskManagerService.State.STOPPED;
            queued.app = app; app.activities.add(queued);
            h.component("ActivityA", 2, KEEP); if (eventFirst) h.drain(); h.ready(app); h.drain();
            check(queued.state == ActivityTaskManagerService.State.DESTROYED && queued.finishing,
                    "Disabled queued activity survived finishAttachApplication");
            check(result.pendingResults.size() == 1 && result.pendingResults.get(0).mResultCode == Activity.RESULT_CANCELED,
                    "Disabled activity did not complete its pending result exactly once");
            check(!result.finishing && h.thread(app).exits == 0, "Activity cleanup finished a peer or killed the process");
        }
        Harness h = new Harness(); var app = h.process(OWNER, true);
        var activity = h.atm.new ActivityRecord(h.pm.getActivityInfo(part("ActivityA"), 0, 0),
                intent("ActivityA"), 1, 0, "android", null, null, -1);
        activity.app = app; activity.state = ActivityTaskManagerService.State.STOPPED;
        app.activities.add(activity); ((List<ActivityTaskManagerService.ActivityRecord>)get(h.atm, "mActivities")).add(activity);
        h.component("ActivityA", 2, KEEP); h.drain();
        check(h.thread(app).destroys.equals(List.of(activity.token)), "Disabled attached activity did not get lifecycle destruction");
        h.atm.activityDestroyed(activity);
        check(h.thread(app).exits == 0 && !app.exiting, "Last activity cleanup violated DONT_KILL_APP");
    }

    /** Recording native boundary used only by the host runner; never controls an OS PID. */
    public static long childStartTime(int pid) { return pid == fakeChild ? 7001 : 0; }
    public static boolean killChild(int pid, long time) {
        check(pid == fakeChild && time == 7001, "Pending process retirement lost its retained generation");
        childKilled = true; return true;
    }
    public static int exitCode(int pid) { return pid == fakeChild && !childKilled ? 259 : -1; }

    private static void startingProcess() throws Exception {
        Harness h = new Harness(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        fakeChild = nextPid++; childKilled = false;
        set(h.atm, "mStarter", (ActivityTaskManagerService.ProcessStarter)(name, sequence, sdk) -> {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS), "Blocked fake start was not released"); return fakeChild;
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread start = new Thread(() -> { try { h.start("ServiceA"); } catch (Throwable error) { failure.set(error); } });
        start.setDaemon(true); start.start(); check(entered.await(3, TimeUnit.SECONDS), "Fake native start was not entered");
        h.application(2, KEEP); h.drain();
        check(((Map<?, ?>)get(h.atm, "mStarting")).isEmpty() && h.pending.isEmpty(),
                "Disabled application retained an unauthenticated process reservation");
        release.countDown(); start.join(3000);
        check(!start.isAlive() && failure.get() == null && childKilled, "Late starter result was not retired using its retained identity: " + failure.get());
        h.application(1, KEEP); h.drain();
        set(h.atm, "mStarter", null); h.start("ServiceB");
        Thread.sleep(30); // Let the canceled generation's exit observer run.
        check(h.pending.size() == 1, "Old canceled process watcher cleared a newly enabled generation's work");
    }

    public static int run(boolean useBinder, String group) throws Exception {
        binder = useBinder; checks = 0;
        if (group.equals("all") || group.equals("services")) services();
        if (group.equals("all") || group.equals("pending")) pending();
        if (group.equals("all") || group.equals("events")) events();
        if (group.equals("all") || group.equals("broadcasts")) broadcasts();
        if (group.equals("all") || group.equals("providers")) providers();
        if (group.equals("all") || group.equals("activities")) activities();
        if (!useBinder && (group.equals("all") || group.equals("starting"))) startingProcess();
        return checks;
    }
}
