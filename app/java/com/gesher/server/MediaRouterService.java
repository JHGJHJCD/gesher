/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import android.media.AppId;
import android.media.AudioManager;
import android.media.IMediaRouter2;
import android.media.IMediaRouter2Manager;
import android.media.IMediaRouterClient;
import android.media.IMediaRouterService;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.MediaRouterClientState;
import android.media.RouteDiscoveryPreference;
import android.media.RouteListingPreference;
import android.media.RoutingChangeInfo;
import android.media.RoutingSessionInfo;
import android.media.SuggestedDeviceInfo;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Media routing for the Windows default audio output. There are no remote providers,
 * cast devices, Bluetooth selection, or Android output-switcher UI. The legacy router
 * constructs its own local route; MediaRouter2 receives an explicit matching system
 * route and persistent system session, as Android's default provider supplies them.
 */
public final class MediaRouterService extends IMediaRouterService.Default {
    private static final String PROVIDER_ID = "android/com.gesher.server.MediaRouterService";
    private static final String SESSION_ID = "SYSTEM_SESSION";
    private final AudioService mAudio;
    private final CallerIdentity mCallers;
    private final Object mLock = new Object();
    private final Map<IBinder, Registration<IMediaRouterClient>> mClients = new HashMap<>();
    private final Map<IBinder, Registration<IMediaRouter2>> mRouters = new HashMap<>();
    private final ExecutorService mCallbacks = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "Gesher media router callbacks");
        thread.setDaemon(true);
        return thread;
    });

    MediaRouterService(AudioService audio, CallerIdentity callers) {
        mAudio = audio;
        mCallers = callers;
        audio.addMediaVolumeListener(this::volumeChanged);
    }

    private interface RemoteCall<T> { void run(T callback) throws RemoteException; }

    private final class Registration<T extends IInterface> implements IBinder.DeathRecipient {
        final Map<IBinder, Registration<T>> owner;
        final T callback;
        final IBinder binder;
        final String packageName;
        final int pid;
        Registration(Map<IBinder, Registration<T>> owner, T callback, String packageName) {
            this.owner = owner;
            this.callback = callback;
            binder = Objects.requireNonNull(callback.asBinder(), "callback binder");
            this.packageName = packageName;
            pid = mCallers.callerPid();
        }
        @Override public void binderDied() { remove(this, false); }
    }

    private void packageName(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("A media router needs a package name");
        }
        mCallers.requirePackage(packageName);
    }

    private static void user(int userId) {
        if (userId != 0 && userId != UserHandle.USER_CURRENT
                && userId != UserHandle.USER_CURRENT_OR_SELF) {
            throw new SecurityException("Only media-routing user 0 is supported");
        }
    }

    private <T extends IInterface> Registration<T> registered(
            Map<IBinder, Registration<T>> registrations, T callback) {
        Objects.requireNonNull(callback, "callback");
        int pid = mCallers.callerPid();
        boolean system = mCallers.isSystem();
        synchronized (mLock) {
            Registration<T> registration = registrations.get(callback.asBinder());
            if (registration != null && !system && registration.pid != pid) {
                throw new SecurityException("Media router belongs to another caller");
            }
            return registration;
        }
    }

    private <T extends IInterface> Registration<T> register(
            Map<IBinder, Registration<T>> registrations, T callback, String packageName) {
        Objects.requireNonNull(callback, "callback");
        packageName(packageName);
        Registration<T> old = registered(registrations, callback);
        if (old != null) {
            if (!old.packageName.equals(packageName)) {
                throw new SecurityException("Media router package cannot change while registered");
            }
            return null;
        }
        Registration<T> registration = new Registration<>(registrations, callback, packageName);
        synchronized (mLock) {
            if (registrations.containsKey(registration.binder)) return null;
            registrations.put(registration.binder, registration);
            try {
                registration.binder.linkToDeath(registration, 0);
            } catch (RemoteException dead) {
                registrations.remove(registration.binder, registration);
                return null;
            }
        }
        return registration;
    }

    private <T extends IInterface> void remove(Registration<T> registration, boolean unlink) {
        if (registration == null) return;
        boolean removed;
        synchronized (mLock) {
            removed = registration.owner.remove(registration.binder, registration);
        }
        if (removed && unlink) registration.binder.unlinkToDeath(registration, 0);
    }

    private <T extends IInterface> void notify(Registration<T> registration, RemoteCall<T> action) {
        if (registration == null) return;
        mCallbacks.execute(() -> {
            synchronized (mLock) {
                if (registration.owner.get(registration.binder) != registration) return;
            }
            // Calls are serialized, and never execute while holding service/audio locks.
            try { action.run(registration.callback); }
            catch (RemoteException dead) { remove(registration, true); }
        });
    }

    private MediaRoute2Info route() {
        return new MediaRoute2Info.Builder(MediaRoute2Info.ROUTE_ID_DEFAULT, "Default audio output")
                .setProviderId(PROVIDER_ID)
                .setProviderPackageName("android")
                .setSystemRoute(true)
                .setType(MediaRoute2Info.TYPE_UNKNOWN)
                .setSupportedRoutingTypes(MediaRoute2Info.FLAG_ROUTING_TYPE_SYSTEM_AUDIO)
                .addFeature(MediaRoute2Info.FEATURE_LIVE_AUDIO)
                .addFeature(MediaRoute2Info.FEATURE_LOCAL_PLAYBACK)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED)
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .setVolume(mAudio.getStreamVolume(AudioManager.STREAM_MUSIC))
                .build();
    }

    private List<MediaRoute2Info> routes() { return Collections.singletonList(route()); }

    private RoutingSessionInfo session(String packageName) {
        return new RoutingSessionInfo.Builder(SESSION_ID, packageName == null ? "" : packageName)
                .setProviderId(PROVIDER_ID)
                .setSystemSession(true)
                .addSelectedRoute(MediaRoute2Info.ROUTE_ID_DEFAULT)
                // Android's default system session has no group-volume control and persists.
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_FIXED)
                .setReleaseType(RoutingSessionInfo.RELEASE_UNSUPPORTED)
                .build();
    }

    private boolean defaultRoute(MediaRoute2Info value) {
        return value != null && route().getId().equals(value.getId());
    }

    private static void sessionId(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Session ID must not be empty");
    }

    private void volumeChanged() {
        List<Registration<IMediaRouter2>> routers;
        synchronized (mLock) { routers = new ArrayList<>(mRouters.values()); }
        for (Registration<IMediaRouter2> router : routers) {
            notify(router, callback -> callback.notifyRoutesUpdated(routes()));
        }
    }

    @Override public void registerClientAsUser(IMediaRouterClient client, String packageName,
            int userId) {
        user(userId);
        notify(register(mClients, client, packageName), IMediaRouterClient::onStateChanged);
    }
    @Override public void unregisterClient(IMediaRouterClient client) {
        remove(registered(mClients, client), true);
    }
    @Override public void registerClientGroupId(IMediaRouterClient client, String groupId) {
        registered(mClients, client); // No remote routes are grouped or selected.
    }
    @Override public MediaRouterClientState getState(IMediaRouterClient client) {
        return registered(mClients, client) == null ? null : new MediaRouterClientState();
    }
    @Override public boolean isPlaybackActive(IMediaRouterClient client) {
        return registered(mClients, client) != null && mAudio.isMusicActive(false);
    }
    @Override public void setBluetoothA2dpOn(IMediaRouterClient client, boolean on) {
        registered(mClients, client); // No selectable Bluetooth output is advertised.
    }
    @Override public void setDiscoveryRequest(IMediaRouterClient client, int routeTypes,
            boolean activeScan) { registered(mClients, client); }
    @Override public void setSelectedRoute(IMediaRouterClient client, String routeId,
            boolean explicit) { registered(mClients, client); }
    @Override public void requestSetVolume(IMediaRouterClient client, String routeId, int volume) {
        registered(mClients, client); // Legacy local volume goes directly through IAudioService.
    }
    @Override public void requestUpdateVolume(IMediaRouterClient client, String routeId,
            int direction) { registered(mClients, client); }

    @Override public List<MediaRoute2Info> getSystemRoutes(String packageName, boolean proxy) {
        packageName(packageName);
        if (proxy) requireSystem();
        return routes();
    }
    @Override public RoutingSessionInfo getSystemSessionInfo() { return session(null); }
    @Override public boolean showMediaOutputSwitcherWithRouter2(String packageName,
            MediaSession.Token token) {
        packageName(packageName);
        return false;
    }
    @Override public void registerRouter2(IMediaRouter2 router, String packageName) {
        Registration<IMediaRouter2> registration = register(mRouters, router, packageName);
        notify(registration, callback -> callback.notifyRouterRegistered(routes(), session(packageName)));
    }
    @Override public void unregisterRouter2(IMediaRouter2 router) {
        remove(registered(mRouters, router), true);
    }
    @Override public void updateScanningStateWithRouter2(IMediaRouter2 router, int scanningState) {
        if (scanningState < MediaRouter2.SCANNING_STATE_NOT_SCANNING
                || scanningState > MediaRouter2.SCANNING_STATE_SCANNING_FULL) {
            throw new IllegalArgumentException("Unknown media route scanning state");
        }
        Registration<IMediaRouter2> registration = registered(mRouters, router);
        if (registration != null && scanningState == MediaRouter2.SCANNING_STATE_SCANNING_FULL) {
            requireSystem();
        }
    }
    @Override public void setDiscoveryRequestWithRouter2(IMediaRouter2 router,
            RouteDiscoveryPreference preference) {
        Objects.requireNonNull(preference, "preference");
        registered(mRouters, router);
    }
    @Override public void setRouteListingPreference(IMediaRouter2 router,
            RouteListingPreference preference) { registered(mRouters, router); }
    @Override public void setRouteVolumeWithRouter2(IMediaRouter2 router, MediaRoute2Info route,
            int volume) {
        Objects.requireNonNull(route, "route");
        Registration<IMediaRouter2> registration = registered(mRouters, router);
        if (registration == null || !defaultRoute(route)) return;
        mAudio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0, registration.packageName);
    }
    @Override public void requestCreateSessionWithRouter2(IMediaRouter2 router, int requestId,
            long managerRequestId, RoutingSessionInfo oldSession, MediaRoute2Info route,
            RoutingChangeInfo change, Bundle hints) {
        Objects.requireNonNull(oldSession, "oldSession");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(change, "routingChangeInfo");
        Registration<IMediaRouter2> registration = registered(mRouters, router);
        if (registration == null) return;
        // Remote providers do not exist. Complete failure requests rather than leave a
        // public transfer waiting for a callback that can never arrive.
        boolean available = managerRequestId == 0 && defaultRoute(route)
                && session(null).getId().equals(oldSession.getId());
        notify(registration, callback -> callback.notifySessionCreated(requestId,
                available ? session(registration.packageName) : null));
    }
    @Override public void selectRouteWithRouter2(IMediaRouter2 router, String sessionId,
            MediaRoute2Info route, RoutingChangeInfo change) {
        Objects.requireNonNull(route, "route"); sessionId(sessionId); registered(mRouters, router);
    }
    @Override public void deselectRouteWithRouter2(IMediaRouter2 router, String sessionId,
            MediaRoute2Info route, RoutingChangeInfo change) {
        Objects.requireNonNull(route, "route"); sessionId(sessionId); registered(mRouters, router);
    }
    @Override public void transferToRouteWithRouter2(IMediaRouter2 router, String sessionId,
            MediaRoute2Info route, RoutingChangeInfo change) {
        Objects.requireNonNull(route, "route"); sessionId(sessionId);
        Registration<IMediaRouter2> registration = registered(mRouters, router);
        if (registration == null || !session(null).getId().equals(sessionId)
                || !defaultRoute(route)) return;
        notify(registration, callback -> callback.notifySessionInfoChanged(
                session(registration.packageName)));
    }
    @Override public void setSessionVolumeWithRouter2(IMediaRouter2 router, String sessionId,
            int volume) {
        Objects.requireNonNull(sessionId, "sessionId"); sessionId(sessionId); registered(mRouters, router);
    } // No system group volume in AOSP either.
    @Override public void releaseSessionWithRouter2(IMediaRouter2 router, String sessionId) {
        sessionId(sessionId);
        registered(mRouters, router); // The system session cannot be released.
    }
    @Override public void setDeviceSuggestionsWithRouter2(IMediaRouter2 router,
            List<SuggestedDeviceInfo> suggestions) {
        registered(mRouters, router);
        if (suggestions != null && !suggestions.isEmpty()) {
            throw new UnsupportedOperationException("Media device suggestions are unavailable");
        }
    }
    @Override public Map<String, List<SuggestedDeviceInfo>> getDeviceSuggestionsWithRouter2(
            IMediaRouter2 router) {
        registered(mRouters, router);
        return Collections.emptyMap();
    }

    // Cross-application media management has no desktop backend. Ordinary app routers
    // must not inherit success-shaped defaults for these privileged operations.
    private void requireSystem() {
        if (!mCallers.isSystem()) throw new SecurityException("Media management requires host authority");
    }
    private void managementUnavailable() {
        requireSystem();
        throw new UnsupportedOperationException("Cross-application media route management is unavailable");
    }
    @Override public RoutingSessionInfo getSystemSessionInfoForPackage(String callerPackageName,
            String targetPackageName) {
        packageName(callerPackageName);
        if (targetPackageName != null && !targetPackageName.equals(callerPackageName)) requireSystem();
        return session(targetPackageName);
    }
    @Override public List<RoutingSessionInfo> getRemoteSessions(IMediaRouter2Manager manager) {
        requireSystem(); return Collections.emptyList();
    }
    @Override public List<AppId> getSystemSessionOverridesAppIds(IMediaRouter2Manager manager) {
        requireSystem(); return Collections.emptyList();
    }
    @Override public void registerManager(IMediaRouter2Manager manager, String packageName) {
        packageName(packageName); managementUnavailable();
    }
    @Override public void registerProxyRouter(IMediaRouter2Manager manager, String packageName,
            String targetPackageName, UserHandle targetUser) {
        packageName(packageName); managementUnavailable();
    }
    @Override public void unregisterManager(IMediaRouter2Manager manager) { requireSystem(); }
    @Override public void setRouteVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            MediaRoute2Info route, int volume) { managementUnavailable(); }
    @Override public void updateScanningState(IMediaRouter2Manager manager, int scanningState) {
        managementUnavailable();
    }
    @Override public void requestCreateSessionWithManager(IMediaRouter2Manager manager, int requestId,
            RoutingSessionInfo oldSession, RoutingChangeInfo change, MediaRoute2Info route) {
        managementUnavailable();
    }
    @Override public void selectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, MediaRoute2Info route, RoutingChangeInfo change) { managementUnavailable(); }
    @Override public void deselectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, MediaRoute2Info route, RoutingChangeInfo change) { managementUnavailable(); }
    @Override public void transferToRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, MediaRoute2Info route, UserHandle user, String packageName,
            RoutingChangeInfo change) { managementUnavailable(); }
    @Override public void setSessionVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, int volume) { managementUnavailable(); }
    @Override public void releaseSessionWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId) { managementUnavailable(); }
    @Override public boolean showMediaOutputSwitcherWithProxyRouter(IMediaRouter2Manager manager,
            MediaSession.Token token) { requireSystem(); return false; }
    @Override public void setDeviceSuggestionsWithManager(IMediaRouter2Manager manager,
            List<SuggestedDeviceInfo> suggestions) { managementUnavailable(); }
    @Override public Map<String, List<SuggestedDeviceInfo>> getDeviceSuggestionsWithManager(
            IMediaRouter2Manager manager) { requireSystem(); return Collections.emptyMap(); }
    @Override public void onDeviceSuggestionRequestedWithManager(IMediaRouter2Manager manager) {
        managementUnavailable();
    }
}
