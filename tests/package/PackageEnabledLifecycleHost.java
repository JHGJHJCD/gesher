/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
public final class PackageEnabledLifecycleHost {
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        android.app.PropertyInvalidatedCache.setTestMode(true);
        var field = android.content.pm.PackageManager.class.getDeclaredField("sPackageInfoCache");
        field.setAccessible(true);
        ((android.app.PropertyInvalidatedCache<?, ?>)field.get(null)).testPropertyName();
        // Constructed locale setting; the host does not ship Android's ICU data bundle.
        var hour = Class.forName("com.gesher.app.locale.SystemLocale").getDeclaredField("sIs24Hour");
        hour.setAccessible(true); hour.set(null, Boolean.TRUE);
        int checks = com.gesher.server.PackageEnabledLifecycleFixture.run(false, args[1]);
        System.out.println("Package enabled lifecycle host passed: " + checks + " checks; group=" + args[1]);
        System.out.println("Actual PM/AM/controllers and attach dispatch; constructed metadata and recording native/process boundaries. No OS process control or ART transport claim.");
    }
}
