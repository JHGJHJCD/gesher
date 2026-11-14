/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import android.media.IMediaRouter2;
import android.media.MediaRoute2Info;
import android.media.RoutingChangeInfo;
import android.media.RoutingSessionInfo;
import android.os.IBinder;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Production service and framework values with explicit host audio/identity/Binder boundaries. */
public final class MediaRoutingHost {
    private static int checks;
    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }
    private static final class Token {
        final List<IBinder.DeathRecipient> deaths = new ArrayList<>();
        final IBinder binder = (IBinder) Proxy.newProxyInstance(IBinder.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "controlled-host-binder";
                        case "linkToDeath": synchronized (deaths) { deaths.add((IBinder.DeathRecipient) args[0]); } return null;
                        case "unlinkToDeath": synchronized (deaths) { return deaths.remove(args[0]); }
                        case "isBinderAlive": case "pingBinder": return true;
                        default: throw new UnsupportedOperationException("No host Binder transport: " + method);
                    }
                });
        void die() {
            List<IBinder.DeathRecipient> snapshot;
            synchronized (deaths) { snapshot = new ArrayList<>(deaths); deaths.clear(); }
            for (IBinder.DeathRecipient death : snapshot) death.binderDied();
        }
    }
    private static final class Router extends IMediaRouter2.Default {
        final Token token = new Token();
        int registrations, updates, creations;
        int request;
        RoutingSessionInfo session;
        List<MediaRoute2Info> routes;
        Runnable onRegister;
        @Override public IBinder asBinder() { return token.binder; }
        @Override public void notifyRouterRegistered(List<MediaRoute2Info> values,
                RoutingSessionInfo current) {
            registrations++; routes = values; session = current;
            if (onRegister != null) onRegister.run();
        }
        @Override public void notifyRoutesUpdated(List<MediaRoute2Info> values) { updates++; routes = values; }
        @Override public void notifySessionCreated(int id, RoutingSessionInfo value) {
            creations++; request = id; session = value;
        }
    }
    private static ExecutorService executor(MediaRouterService service) throws Exception {
        Field field = MediaRouterService.class.getDeclaredField("mCallbacks");
        field.setAccessible(true); return (ExecutorService) field.get(service);
    }
    private static void drain(MediaRouterService service) throws Exception {
        executor(service).submit(() -> {}).get(5, TimeUnit.SECONDS);
    }
    private interface Operation { void run(); }
    private static void rejects(Class<? extends Throwable> type, Operation action) {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) { checks++; return; }
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    public static void main(String[] args) throws Exception {
        AudioService audio = new AudioService();
        CallerIdentity identity = new CallerIdentity();
        MediaRouterService service = new MediaRouterService(audio, identity);
        try {
            List<MediaRoute2Info> routes = service.getSystemRoutes("fixture.routes", false);
            RoutingSessionInfo current = service.getSystemSessionInfo();
            check(routes.size() == 1 && routes.get(0).isSystemRoute(), "one real default route");
            check(current.getSelectedRoutes().equals(Collections.singletonList(routes.get(0).getId())),
                    "actual framework provider qualification is consistent");
            check(current.isSystemSession() && current.getReleaseType() == RoutingSessionInfo.RELEASE_UNSUPPORTED,
                    "persistent system session metadata");
            Router router = new Router();
            router.onRegister = () -> check(service.getSystemRoutes("fixture.routes", false).size() == 1,
                    "registration callback can reenter service");
            service.registerRouter2(router, "fixture.routes"); drain(service);
            check(router.registrations == 1 && "fixture.routes".equals(router.session.getClientPackageName()),
                    "registration callback and owner package");
            service.registerRouter2(router, "fixture.routes"); drain(service);
            check(router.registrations == 1 && router.token.deaths.size() == 1, "duplicate registration is idempotent");
            service.setRouteVolumeWithRouter2(router, routes.get(0), 9); drain(service);
            check(audio.volume == 9 && router.updates == 1 && router.routes.get(0).getVolume() == 9,
                    "route changes host audio boundary and publishes resulting value");
            audio.setStreamVolume(3, 6, 0, "fixture.routes"); drain(service);
            check(router.updates == 2 && router.routes.get(0).getVolume() == 6,
                    "external framework audio change reaches routing callback");
            RoutingChangeInfo change = new RoutingChangeInfo(RoutingChangeInfo.ENTRY_POINT_LOCAL_ROUTER_UNSPECIFIED, false);
            MediaRoute2Info absent = new MediaRoute2Info.Builder("absent", "Absent")
                    .addFeature(MediaRoute2Info.FEATURE_REMOTE_PLAYBACK).build();
            service.requestCreateSessionWithRouter2(router, 41, 0, current, absent, change, null); drain(service);
            check(router.creations == 1 && router.request == 41 && router.session == null,
                    "unsupported session request completes failure");
            service.requestCreateSessionWithRouter2(router, 42, 0, current, routes.get(0), change, null); drain(service);
            check(router.creations == 2 && router.request == 42 && router.session.getId().equals(current.getId()),
                    "default route retains the existing system session");
            service.requestCreateSessionWithRouter2(router, 43, 10, current, routes.get(0), change, null); drain(service);
            check(router.creations == 3 && router.session == null, "unknown manager request completes failure");
            rejects(NullPointerException.class, () -> service.setRouteVolumeWithRouter2(router, null, 1));
            rejects(NullPointerException.class, () -> service.requestCreateSessionWithRouter2(router, 1, 0, null,
                    routes.get(0), change, null));
            rejects(IllegalArgumentException.class, () -> service.releaseSessionWithRouter2(router, ""));
            rejects(SecurityException.class, () -> service.getSystemRoutes("another.package", false));
            rejects(SecurityException.class, () -> service.updateScanningStateWithRouter2(router, 2));
            rejects(SecurityException.class, () -> service.registerManager(null, "fixture.routes"));
            identity.pid = 2;
            rejects(SecurityException.class, () -> service.unregisterRouter2(router));
            identity.pid = 1;
            router.token.die();
            audio.setStreamVolume(3, 8, 0, "fixture.routes"); drain(service);
            check(router.updates == 2, "Binder death retires volume delivery");
            service.unregisterRouter2(router); service.unregisterRouter2(router);

            Router blocked = new Router();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            blocked.onRegister = () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            };
            service.registerRouter2(blocked, "fixture.routes");
            check(entered.await(5, TimeUnit.SECONDS), "in-flight registration callback begins");
            audio.setStreamVolume(3, 4, 0, "fixture.routes");
            service.unregisterRouter2(blocked);
            release.countDown(); drain(service);
            check(blocked.updates == 0 && blocked.token.deaths.isEmpty(),
                    "unregister removes queued delivery and death recipient without callback lock deadlock");
            System.out.println("MediaRoutingHost all passed: " + checks);
        } finally { executor(service).shutdownNow(); }
    }
}

/** Controlled host boundary only: no audio engine is replaced in the shipped runtime. */
final class AudioService {
    int volume = 4;
    Runnable listener;
    void addMediaVolumeListener(Runnable listener) { this.listener = listener; }
    int getStreamMaxVolume(int stream) { return 15; }
    int getStreamVolume(int stream) { return volume; }
    boolean isMusicActive(boolean remotely) { return false; }
    void setStreamVolume(int stream, int volume, int flags, String name) {
        this.volume = volume; listener.run();
    }
}

/** Controlled caller identity; Windows fixtures exercise production CallerIdentity over Binder. */
final class CallerIdentity {
    volatile int pid = 1;
    int callerPid() { return pid; }
    boolean isSystem() { return false; }
    String requirePackage(String value) {
        if (!"fixture.routes".equals(value)) throw new SecurityException("fixture identity");
        return value;
    }
}
