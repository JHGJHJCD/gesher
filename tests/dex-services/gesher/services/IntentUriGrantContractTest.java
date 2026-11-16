/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package gesher.services;

import android.app.IUriGrantsManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.pkg.component.ParsedPermissionImpl;
import com.android.internal.pm.pkg.component.ParsedProviderImpl;
import com.gesher.server.ActivityManagerService;
import com.gesher.server.ActivityTaskManagerService;
import com.gesher.server.ApkPackage;
import com.gesher.server.CallerIdentity;
import com.gesher.server.PackageManagerService;
import com.gesher.server.SharedLibraryGraph;
import com.gesher.server.StandIn;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sun.misc.Unsafe;

/** Contract tests for F10: Intent URI grants and IBinder owner tokens. */
public final class IntentUriGrantContractTest {
    private static final String SENDER = "fixture.intent.sender";
    private static final String TARGET = "fixture.intent.target";
    private static final String OTHER = "fixture.intent.other";
    private static final int SENDER_UID = 10100;
    private static final int TARGET_UID = 10200;
    private static final int OTHER_UID = 10300;
    private static final String AUTHORITY = "fixture.intent.provider";

    private static Unsafe unsafe;
    private static int checks = 0;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object get(Object target, String name) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static ApkPackage createPackage(String name, int uid, boolean withProvider) throws Exception {
        PackageImpl parsed = (PackageImpl) PackageImpl.forTesting(name, "/fixture/" + name + "/base.apk");
        if (withProvider) {
            ParsedPermissionImpl rPerm = new ParsedPermissionImpl();
            rPerm.setName(name + ".READ"); rPerm.setPackageName(name);
            rPerm.setProtectionLevel(PermissionInfo.PROTECTION_NORMAL); parsed.addPermission(rPerm);
            ParsedPermissionImpl wPerm = new ParsedPermissionImpl();
            wPerm.setName(name + ".WRITE"); wPerm.setPackageName(name);
            wPerm.setProtectionLevel(PermissionInfo.PROTECTION_NORMAL); parsed.addPermission(wPerm);

            ParsedProviderImpl provider = new ParsedProviderImpl();
            provider.setName(name + ".Provider");
            provider.setPackageName(name);
            provider.setEnabled(true);
            provider.setExported(true);
            provider.setAuthority(AUTHORITY);
            provider.setGrantUriPermissions(true);
            provider.setReadPermission(name + ".READ");
            provider.setWritePermission(name + ".WRITE");
            parsed.addProvider(provider);
        }
        parsed.hideAsFinal();
        ApkPackage code = (ApkPackage) unsafe.allocateInstance(ApkPackage.class);
        set(code, "packageName", name);
        set(code, "parsed", parsed);
        set(code, "mUid", uid);
        set(code, "apkPath", "/fixture/" + name + "/base.apk");
        set(code, "mDataDir", "/fixture/" + name + "/data");
        set(code, "sharedLibraries", SharedLibraryGraph.EMPTY);
        return code;
    }

    public static void main(String[] args) throws Exception {
        System.loadLibrary("android_runtime");
        Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        unsafe = (Unsafe) singleton.get(null);

        PackageManagerService pm = (PackageManagerService) unsafe.allocateInstance(PackageManagerService.class);
        Map<String, ApkPackage> pkgs = new LinkedHashMap<>();
        ApkPackage senderPkg = createPackage(SENDER, SENDER_UID, true);
        ApkPackage targetPkg = createPackage(TARGET, TARGET_UID, false);
        ApkPackage otherPkg = createPackage(OTHER, OTHER_UID, false);
        pkgs.put(SENDER, senderPkg);
        pkgs.put(TARGET, targetPkg);
        pkgs.put(OTHER, otherPkg);
        set(pm, "mPackages", pkgs);
        set(pm, "mRemoving", new LinkedHashMap<>());
        set(pm, "mPackageRemoval", new ArrayList<>());
        set(pm, "mPackageRemovalChecks", new ArrayList<>());
        Class<?> policy = Class.forName("com.gesher.server.PermissionPolicy");
        Constructor<?> policyConstructor = policy.getDeclaredConstructor(PackageManagerService.class);
        policyConstructor.setAccessible(true);
        set(pm, "mPermissions", policyConstructor.newInstance(pm));

        ActivityTaskManagerService atm = (ActivityTaskManagerService) unsafe.allocateInstance(ActivityTaskManagerService.class);
        set(atm, "mPm", pm);
        set(atm, "mProcesses", new ArrayList<>());

        ActivityManagerService am = (ActivityManagerService) unsafe.allocateInstance(ActivityManagerService.class);
        set(am, "mPm", pm);
        set(am, "mAtm", atm);

        Constructor<?> grantsCtor = Class.forName("com.gesher.server.UriGrants")
                .getDeclaredConstructor(PackageManagerService.class, ActivityTaskManagerService.class);
        grantsCtor.setAccessible(true);
        Object grants = grantsCtor.newInstance(pm, atm);
        Object providers = unsafe.allocateInstance(Class.forName("com.gesher.server.ContentProviders"));
        set(providers, "mPm", pm);
        set(providers, "mUriGrants", grants);
        set(am, "mProviders", providers);
        set(am, "mCallers", new CallerIdentity(pm, atm));

        Constructor<?> ugmCtor = Class.forName("com.gesher.server.UriGrantsManagerService")
                .getDeclaredConstructor(PackageManagerService.class, ActivityTaskManagerService.class, ActivityManagerService.class);
        ugmCtor.setAccessible(true);
        IUriGrantsManager ugm = (IUriGrantsManager) ugmCtor.newInstance(pm, atm, am);

        Uri testUri = Uri.parse("content://" + AUTHORITY + "/document/123");
        Uri childUri = Uri.parse("content://" + AUTHORITY + "/document/123/child");
        int read = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        int write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        int prefix = Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

        // Test 1: Initially target has no access
        Method checkMethod = grants.getClass().getDeclaredMethod("check", Uri.class, int.class, int.class, int.class, int.class);
        checkMethod.setAccessible(true);
        int initialCheck = (Integer) checkMethod.invoke(grants, testUri, 500, TARGET_UID, read, 0);
        check(initialCheck == PackageManager.PERMISSION_DENIED, "Initial access must be denied");

        // Test 2: grantUriPermissionFromOwner with owner token
        IBinder owner1 = new Binder();
        Method grantFromOwnerMethod = grants.getClass().getDeclaredMethod("grantFromOwner",
                IBinder.class, int.class, String.class, Uri.class, int.class, int.class, int.class);
        grantFromOwnerMethod.setAccessible(true);

        grantFromOwnerMethod.invoke(grants, owner1, SENDER_UID, TARGET, testUri, read, 0, 0);

        Method canAccessMethod = grants.getClass().getDeclaredMethod("canAccess", ApkPackage.class, Uri.class, int.class, int.class);
        canAccessMethod.setAccessible(true);
        boolean targetAccess = (Boolean) canAccessMethod.invoke(grants, targetPkg, testUri, read, 0);
        check(targetAccess, "Target package must have read access after owner grant");

        boolean targetWrite = (Boolean) canAccessMethod.invoke(grants, targetPkg, testUri, write, 0);
        check(!targetWrite, "Target package must not have write access when only read was granted");

        boolean otherAccess = (Boolean) canAccessMethod.invoke(grants, otherPkg, testUri, read, 0);
        check(!otherAccess, "Unrelated package must not have access");

        // Test 3: checkGrantUriPermission_ignoreNonSystem
        Method checkGrantMethod = grants.getClass().getDeclaredMethod("checkGrantUriPermission_ignoreNonSystem",
                int.class, String.class, Uri.class, int.class, int.class);
        checkGrantMethod.setAccessible(true);

        int checkSystem = (Integer) checkGrantMethod.invoke(grants, SENDER_UID, TARGET, testUri, read, 0);
        check(checkSystem == TARGET_UID, "checkGrantUriPermission_ignoreNonSystem must return target UID for valid grant");

        int checkInvalid = (Integer) checkGrantMethod.invoke(grants, OTHER_UID, TARGET, testUri, read, 0);
        check(checkInvalid == -1, "checkGrantUriPermission_ignoreNonSystem must return -1 when sender lacks grant permission");

        // Test 4: Multiple owners on same URI with different modes
        IBinder owner2 = new Binder();
        grantFromOwnerMethod.invoke(grants, owner2, SENDER_UID, TARGET, testUri, write, 0, 0);
        check((Boolean) canAccessMethod.invoke(grants, targetPkg, testUri, read, 0),
                "Target must have read from owner1");
        check((Boolean) canAccessMethod.invoke(grants, targetPkg, testUri, write, 0),
                "Target must have write from owner2");

        // Test 5: Revocation of owner1 revokes read, but keeps owner2 write
        Method revokeByOwnerMethod = grants.getClass().getDeclaredMethod("revokeByOwner", IBinder.class);
        revokeByOwnerMethod.setAccessible(true);
        revokeByOwnerMethod.invoke(grants, owner1);

        check(!(Boolean) canAccessMethod.invoke(grants, targetPkg, testUri, read, 0),
                "Target must lose read access after owner1 is revoked");
        check((Boolean) canAccessMethod.invoke(grants, targetPkg, testUri, write, 0),
                "Target must retain write access from owner2");

        // Test 6: Revocation of owner2 revokes write as well
        revokeByOwnerMethod.invoke(grants, owner2);
        check(!(Boolean) canAccessMethod.invoke(grants, targetPkg, testUri, write, 0),
                "Target must lose write access after owner2 is revoked");

        // Test 7: Prefix owner grant
        IBinder ownerPrefix = new Binder();
        grantFromOwnerMethod.invoke(grants, ownerPrefix, SENDER_UID, TARGET, testUri, read | prefix, 0, 0);
        check((Boolean) canAccessMethod.invoke(grants, targetPkg, childUri, read, 0),
                "Target must have read access to child URI under prefix grant");
        revokeByOwnerMethod.invoke(grants, ownerPrefix);
        check(!(Boolean) canAccessMethod.invoke(grants, targetPkg, childUri, read, 0),
                "Child URI access must be revoked when prefix owner is revoked");

        // Test 8: ActivityCallerState contentUris extraction
        Intent testIntent = new Intent();
        testIntent.setData(testUri);
        ClipData clip = ClipData.newRawUri("label", childUri);
        testIntent.setClipData(clip);

        Class<?> callerStateClass = Class.forName("com.gesher.server.ActivityCallerState");
        Method contentUrisMethod = callerStateClass.getDeclaredMethod("contentUris", Intent.class);
        contentUrisMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<Uri> extractedUris = (java.util.Set<Uri>) contentUrisMethod.invoke(null, testIntent);
        check(extractedUris.contains(testUri), "contentUris must extract intent data URI");
        check(extractedUris.contains(childUri), "contentUris must extract ClipData URI");

        System.out.println("IntentUriGrantContractTest passed: " + checks + " checks");
    }
}
