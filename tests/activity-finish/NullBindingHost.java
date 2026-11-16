/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
public final class NullBindingHost {
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        // Constructed device settings, unrelated to the production binding path.
        var hour = Class.forName("com.gesher.app.locale.SystemLocale").getDeclaredField("sIs24Hour");
        hour.setAccessible(true); hour.set(null, Boolean.TRUE);
        int checks = gesher.services.NullBindingContractTest.contracts(false);
        System.out.println("Null binding host all passed: " + checks + " checks");
        System.out.println("Production Java service manager and LoadedApk dispatcher; constructed parsed metadata and controlled native identity. No ART or Parcel transport claim.");
    }
}
