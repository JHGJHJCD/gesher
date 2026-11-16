/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.AppOpsManager;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.content.UriPermission;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.app.IAppOpsService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Real framework clients and containers with controlled provider and app-op boundaries. */
public final class MediaStoreAccessContractTest {
    private static int checks;
    private interface Operation { void run() throws Exception; }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static Throwable expect(Class<? extends Throwable> type, Operation operation,
            String message) {
        try {
            operation.run();
        } catch (Throwable error) {
            if (!type.isInstance(error)) throw new AssertionError(message, error);
            checks++;
            return error;
        }
        throw new AssertionError(message + ": no exception");
    }

    private static final class TestContext extends ContextWrapper {
        final ApplicationInfo app = new ApplicationInfo();
        ContentResolver resolver;
        AppOpsManager appOps;
        int permission = PackageManager.PERMISSION_DENIED;
        int permissionChecks;
        String checkedPermission;
        int checkedPid;
        int checkedUid;

        TestContext() {
            super(null);
            app.uid = 10042;
            app.targetSdkVersion = 36;
        }

        @Override public String getOpPackageName() { return "gesher.media.contract"; }
        @Override public ApplicationInfo getApplicationInfo() { return app; }
        @Override public PackageManager getPackageManager() { return null; }
        @Override public AttributionSource getAttributionSource() { return null; }
        @Override public ContentResolver getContentResolver() { return resolver; }
        @Override public String getSystemServiceName(Class<?> type) {
            return type == AppOpsManager.class ? Context.APP_OPS_SERVICE : null;
        }
        @Override public Object getSystemService(String name) {
            return Context.APP_OPS_SERVICE.equals(name) ? appOps : null;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            permissionChecks++;
            checkedPermission = permission;
            checkedPid = pid;
            checkedUid = uid;
            return this.permission;
        }
    }

    private static final class AppOpsObserver extends IAppOpsService.Default implements AutoCloseable {
        final Field service;
        final Object previousService;
        int mode;
        int calls;
        int operation;
        int uid;
        String packageName;
        String attributionTag;
        int deviceId;
        RuntimeException failure;

        AppOpsObserver() {
            try {
                // API 36's real manager may query its shared cache through this service.
                service = AppOpsManager.class.getDeclaredField("sService");
                service.setAccessible(true);
                previousService = service.get(null);
                service.set(null, this);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot install controlled app-op service", e);
            }
        }

        @Override public void close() {
            try {
                service.set(null, previousService);
            } catch (IllegalAccessException e) {
                throw new AssertionError("Cannot restore app-op service", e);
            }
        }

        AppOpsManager manager(Context context) {
            try {
                Constructor<AppOpsManager> constructor = AppOpsManager.class.getDeclaredConstructor(
                        Context.class, IAppOpsService.class);
                constructor.setAccessible(true);
                return constructor.newInstance(context, this);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot construct the real framework AppOpsManager", e);
            }
        }

        @Override public int checkOperationForDevice(int operation, int uid, String packageName,
                String attributionTag, int deviceId) {
            calls++;
            this.operation = operation;
            this.uid = uid;
            this.packageName = packageName;
            this.attributionTag = attributionTag;
            this.deviceId = deviceId;
            if (failure != null) throw failure;
            return mode;
        }

        @Override public int checkOperationRawForDevice(int operation, int uid, String packageName,
                String attributionTag, int deviceId) {
            return checkOperationForDevice(operation, uid, packageName, attributionTag, deviceId);
        }
    }

    private static final class Provider {
        final Binder binder = new Binder();
        int calls;
        String authority;
        String method;
        String argument;
        Bundle input;
        Bundle reply = new Bundle();
        Throwable failure;
        final IContentProvider transport = (IContentProvider) Proxy.newProxyInstance(
                IContentProvider.class.getClassLoader(), new Class<?>[]{IContentProvider.class},
                (proxy, called, args) -> {
                    if (called.getName().equals("asBinder")) return binder;
                    if (!called.getName().equals("call") || args.length != 5) {
                        throw new AssertionError("Unexpected provider operation: " + called);
                    }
                    calls++;
                    authority = (String) args[1];
                    method = (String) args[2];
                    argument = (String) args[3];
                    input = (Bundle) args[4];
                    if (failure != null) throw failure;
                    return reply;
                });

        void uri(Uri uri) { reply = new Bundle(); reply.putParcelable("uri", uri); }
        void uris(List<Uri> uris) {
            reply = new Bundle();
            reply.putParcelableArrayList("uri_list", uris == null ? null : new ArrayList<>(uris));
        }
        void route(String authority, String method) {
            check(Objects.equals(authority, this.authority) && method.equals(this.method)
                    && argument == null, "provider call protocol: " + method);
        }
    }

    private static final class Resolver extends ContentResolver {
        final Provider provider;
        final List<UriPermission> permissions = new ArrayList<>();
        boolean available = true;
        int acquired;
        int released;
        int grantQueries;
        String acquiredAuthority;
        RuntimeException grantFailure;

        Resolver(TestContext context, Provider provider) {
            super(context);
            this.provider = provider;
            context.resolver = this;
        }
        @Override public List<UriPermission> getPersistedUriPermissions() {
            grantQueries++;
            if (grantFailure != null) throw grantFailure;
            return permissions;
        }
        @Override protected IContentProvider acquireProvider(Context context, String name) {
            acquired++;
            acquiredAuthority = name;
            return available ? provider.transport : null;
        }
        @Override public boolean releaseProvider(IContentProvider provider) {
            check(provider == this.provider.transport, "releases the acquired provider identity");
            released++;
            return true;
        }
        @Override protected IContentProvider acquireUnstableProvider(Context context, String name) {
            throw new AssertionError("MediaStore calls require a stable provider client");
        }
        @Override public boolean releaseUnstableProvider(IContentProvider provider) {
            throw new AssertionError("unexpected unstable client release");
        }
        @Override public void unstableProviderDied(IContentProvider provider) {
            throw new AssertionError("unexpected unstable provider death");
        }
        void closed() { check(acquired == released, "all acquired stable clients released"); }
    }

    private static void management() {
        TestContext context = new TestContext();
        try (AppOpsObserver observer = new AppOpsObserver()) {
            management(context, observer);
        }
    }

    private static void management(TestContext context, AppOpsObserver observer) {
        context.appOps = observer.manager(context);
        for (int mode : new int[]{AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_IGNORED,
                AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_DEFAULT,
                AppOpsManager.MODE_FOREGROUND, -17, 999}) {
            for (int permission : new int[]{PackageManager.PERMISSION_GRANTED,
                    PackageManager.PERMISSION_DENIED}) {
                observer.mode = mode;
                // Distinct identities avoid reusing a previous reply in the real API 36 cache.
                context.app.uid++;
                context.permission = permission;
                int before = context.permissionChecks;
                int callsBefore = observer.calls;
                boolean expected = mode == AppOpsManager.MODE_ALLOWED
                        || mode == AppOpsManager.MODE_DEFAULT && permission == PackageManager.PERMISSION_GRANTED;
                check(MediaStoreAccessSdkCalls.manage(context) == expected,
                        "management policy mode=" + mode + " permission=" + permission);
                check(observer.calls > callsBefore && observer.operation == AppOpsManager.OP_MANAGE_MEDIA
                        && observer.uid == context.app.uid && observer.deviceId == 0
                        && observer.attributionTag == null
                        && context.getOpPackageName().equals(observer.packageName),
                        "app-op uses media permission, context UID and op-package identity");
                check(context.permissionChecks == before + (mode == AppOpsManager.MODE_DEFAULT ? 1 : 0),
                        "only default app-op consults permission");
                if (mode == AppOpsManager.MODE_DEFAULT) {
                    check(android.Manifest.permission.MANAGE_MEDIA.equals(context.checkedPermission)
                            && context.checkedPid == android.os.Process.myPid()
                            && context.checkedUid == context.app.uid, "permission fallback identity");
                }
            }
        }
        expect(NullPointerException.class, () -> MediaStoreAccessSdkCalls.manage(null), "null context");
        RuntimeException failure = new SecurityException("controlled app-op denial");
        observer.failure = failure;
        context.app.uid++;
        int before = context.permissionChecks;
        check(expect(SecurityException.class, () -> MediaStoreAccessSdkCalls.manage(context),
                "app-op failure is not turned into approval") == failure, "original app-op failure");
        check(context.permissionChecks == before, "failed app-op does not fall back to permission");
    }

    private static Uri equivalent(boolean document, TestContext context, Uri uri) {
        return document ? MediaStoreAccessSdkCalls.document(context, uri)
                : MediaStoreAccessSdkCalls.media(context, uri);
    }

    private static void equivalent(boolean document) {
        TestContext context = new TestContext();
        Provider provider = new Provider();
        Resolver resolver = new Resolver(context, provider);
        Uri input = Uri.parse(document ? "content://media/external/images/media/17?original=1"
                : "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fitem");
        Uri output = Uri.parse(document ? "content://com.android.providers.media.documents/document/image%3A17"
                : "content://media/external/images/media/17");
        resolver.permissions.add(new UriPermission(input, 1, 123));
        resolver.permissions.add(new UriPermission(Uri.parse("content://example/tree/second"), 3, 456));
        provider.uri(output);
        check(equivalent(document, context, input) == output, "provider owns equivalent URI result");
        provider.route("media", document ? "get_document_uri" : "get_media_uri");
        check("media".equals(resolver.acquiredAuthority), "equivalence acquires MediaProvider");
        check(provider.input.getParcelable("uri", Uri.class) == input, "original URI forwarded");
        List<UriPermission> grants = provider.input.getParcelableArrayList("uriPermissions", UriPermission.class);
        check(grants != resolver.permissions && grants.equals(resolver.permissions), "persisted grants snapshot forwarded");
        check(provider.input.size() == 2 && resolver.grantQueries == 1, "exact equivalence request envelope");
        resolver.permissions.clear();
        check(grants.size() == 2, "request grant snapshot remains independent");
        provider.uri(null);
        check(equivalent(document, context, input) == null, "provider can report no equivalent");
        provider.reply = new Bundle();
        check(equivalent(document, context, input) == null, "missing URI result remains null");
        provider.uri(output);
        check(equivalent(document, context, null) == output, "null URI is forwarded under pinned call contract");
        check(provider.input.containsKey("uri") && provider.input.getParcelable("uri") == null,
                "null URI not replaced with synthesized URI");
        resolver.closed();

        int acquired = resolver.acquired;
        SecurityException deniedGrants = new SecurityException("controlled persisted grant denial");
        resolver.grantFailure = deniedGrants;
        check(expect(SecurityException.class, () -> equivalent(document, context, input),
                "persisted grant query denial") == deniedGrants, "original grant-query denial");
        check(resolver.acquired == acquired, "grant failure never acquires provider");
        resolver.grantFailure = null;
        expect(NullPointerException.class, () -> equivalent(document, null, input), "null equivalence context");
        failures(resolver, () -> equivalent(document, context, input));
    }

    private static void singleRedacted() {
        TestContext context = new TestContext();
        Provider provider = new Provider();
        Resolver resolver = new Resolver(context, provider);
        Uri plain = Uri.parse("content://media/external/images/media/17?q=keep#fragment");
        Uri redacted = Uri.parse("content://media/external/images/media/RUID17");
        provider.uri(redacted);
        check(MediaStoreAccessSdkCalls.redacted(resolver, plain) == redacted, "exact unqualified redacted result");
        provider.route("media", "get_redacted_media_uri");
        check(provider.input.size() == 1 && provider.input.getParcelable("uri", Uri.class) == plain,
                "unqualified URI envelope");
        Uri qualified = Uri.parse("content://10@media/external/images/media/17?q=keep#fragment");
        Uri result = MediaStoreAccessSdkCalls.redacted(resolver, qualified);
        provider.route("10@media", "get_redacted_media_uri");
        check("10@media".equals(resolver.acquiredAuthority), "cross-user provider selection");
        check(provider.input.getParcelable("uri", Uri.class).equals(plain),
                "only user qualifier stripped; path/query/fragment retained");
        check(result.equals(Uri.parse("content://10@media/external/images/media/RUID17")),
                "result belongs to original user");
        check(qualified.getUserInfo().equals("10"), "caller URI remains unchanged");
        provider.uri(null);
        check(MediaStoreAccessSdkCalls.redacted(resolver, plain) == null, "unsupported redaction returns provider null");
        expect(NullPointerException.class, () -> MediaStoreAccessSdkCalls.redacted(resolver, qualified),
                "pinned qualified-null reply cannot manufacture a redacted URI");
        provider.uri(redacted);
        int before = resolver.acquired;
        expect(NullPointerException.class, () -> MediaStoreAccessSdkCalls.redacted(resolver, null), "null single URI");
        check(resolver.acquired == before, "null URI fails before provider acquisition");
        expect(NullPointerException.class, () -> MediaStoreAccessSdkCalls.redacted(null, plain), "null single resolver");
        resolver.closed();
        failures(resolver, () -> MediaStoreAccessSdkCalls.redacted(resolver, plain));
    }

    private static void batchRedacted() {
        TestContext context = new TestContext();
        Provider provider = new Provider();
        Resolver resolver = new Resolver(context, provider);
        Uri first = Uri.parse("content://media/external/images/media/17");
        Uri second = Uri.parse("content://media/external/video/media/9?q=keep");
        List<Uri> plain = Collections.unmodifiableList(Arrays.asList(first, second, first));
        List<Uri> replies = Arrays.asList(Uri.parse("content://media/external/images/media/RUID17"),
                Uri.parse("content://media/external/video/media/RUID9"),
                Uri.parse("content://media/external/images/media/RUID17"));
        provider.uris(replies);
        List<Uri> result = MediaStoreAccessSdkCalls.redactedList(resolver, plain);
        check(result.equals(replies), "batch reply preserves order and duplicates");
        provider.route("media", "get_redacted_media_uri_list");
        List<Uri> sent = provider.input.getParcelableArrayList("uri_list", Uri.class);
        check(sent.equals(plain) && sent != plain && provider.input.size() == 1, "independent batch request envelope");
        check(resolver.grantQueries == 0, "redaction asks provider, not persisted-grant API");
        List<Uri> qualified = Arrays.asList(Uri.parse("content://10@media/external/images/media/17"),
                Uri.parse("content://10@media/external/video/media/9?q=keep"),
                Uri.parse("content://10@media/external/images/media/17"));
        result = MediaStoreAccessSdkCalls.redactedList(resolver, qualified);
        provider.route("10@media", "get_redacted_media_uri_list");
        check(provider.input.getParcelableArrayList("uri_list", Uri.class).equals(plain), "strip every input user qualifier");
        for (int i = 0; i < result.size(); i++) {
            check("10".equals(result.get(i).getUserInfo())
                    && result.get(i).getPath().equals(replies.get(i).getPath()), "qualify result index " + i);
        }
        check("10".equals(qualified.get(0).getUserInfo()), "batch inputs remain unchanged");
        List<Uri> mixedCurrent = Arrays.asList(first, Uri.parse("content://"
                + UserHandle.myUserId() + "@media/external/video/media/9?q=keep"));
        provider.uris(replies);
        check(MediaStoreAccessSdkCalls.redactedList(resolver, mixedCurrent).equals(replies),
                "explicit current user and implicit current user are one identity");
        int before = resolver.acquired;
        expect(IllegalArgumentException.class, () -> MediaStoreAccessSdkCalls.redactedList(resolver,
                Arrays.asList(qualified.get(0), Uri.parse("content://11@media/external/images/media/18"))),
                "mixed-user batch rejected");
        expect(NumberFormatException.class, () -> MediaStoreAccessSdkCalls.redactedList(resolver,
                Collections.singletonList(Uri.parse("content://invalid@media/external/images/media/1"))),
                "invalid batch user ID rejected");
        expect(IndexOutOfBoundsException.class, () -> MediaStoreAccessSdkCalls.redactedList(resolver,
                Collections.emptyList()), "empty list preserves pinned pre-provider failure");
        expect(NullPointerException.class, () -> MediaStoreAccessSdkCalls.redactedList(resolver, null), "null batch");
        expect(NullPointerException.class, () -> MediaStoreAccessSdkCalls.redactedList(resolver,
                Arrays.asList(first, null)), "null batch item");
        check(resolver.acquired == before, "invalid batch never acquires a provider");
        provider.uris(Arrays.asList(replies.get(0), null, replies.get(2)));
        check(MediaStoreAccessSdkCalls.redactedList(resolver, plain).get(1) == null,
                "unqualified unsupported batch item retains null slot");
        provider.uris(null);
        check(MediaStoreAccessSdkCalls.redactedList(resolver, plain) == null,
                "null batch result remains null");
        expect(NullPointerException.class, () -> MediaStoreAccessSdkCalls.redactedList(resolver, qualified),
                "pinned qualified-null batch reply is not fabricated");
        expect(NullPointerException.class, () -> MediaStoreAccessSdkCalls.redactedList(null, plain), "null batch resolver");
        resolver.closed();
        failures(resolver, () -> MediaStoreAccessSdkCalls.redactedList(resolver, plain));
    }

    private static void failures(Resolver resolver, Operation call) {
        Provider provider = resolver.provider;
        SecurityException denied = new SecurityException("controlled MediaProvider permission denial");
        provider.failure = denied;
        check(expect(SecurityException.class, call, "provider denial propagates") == denied, "original provider denial");
        resolver.closed();
        IllegalArgumentException invalid = new IllegalArgumentException("controlled unsupported URI");
        provider.failure = invalid;
        check(expect(IllegalArgumentException.class, call, "provider argument error propagates") == invalid,
                "original provider argument failure");
        resolver.closed();
        RemoteException death = new RemoteException("controlled provider death");
        provider.failure = death;
        Throwable wrapped = expect(RuntimeException.class, call, "remote failure is a runtime exception");
        check(wrapped.getCause() == death, "remote failure cause preserved");
        resolver.closed();
        provider.failure = null;
        provider.reply = null;
        expect(NullPointerException.class, call, "null provider bundle cannot report successful conversion");
        resolver.closed();
        resolver.available = false;
        int calls = provider.calls;
        int released = resolver.released;
        expect(NullPointerException.class, call, "missing provider retains pinned client failure");
        check(provider.calls == calls && resolver.released == released,
                "missing provider has no call and no acquired reference to release");
    }

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--case")) System.loadLibrary("android_runtime");
        else System.load(args[0]);
        String selected = "all";
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--case")) selected = args[i + 1];
        switch (selected) {
            case "all": management(); equivalent(true); equivalent(false); singleRedacted(); batchRedacted(); break;
            case "management": management(); break;
            case "document": equivalent(true); break;
            case "media": equivalent(false); break;
            case "redacted": singleRedacted(); break;
            case "redacted-list": batchRedacted(); break;
            default: throw new IllegalArgumentException("unknown case: " + selected);
        }
        System.out.println("MediaStoreAccessContractTest all passed: " + checks);
    }
}
