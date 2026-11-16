/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
public final class ActivityFinishHost {
    public static void main(String[] args) throws Throwable {
        System.load(args[0]);
        // The host API archive has no ICU data image. Pin unrelated device locale
        // settings; the production lifecycle and configuration Java still run.
        Class<?> locales = Class.forName("com.gesher.app.locale.SystemLocale");
        var hour = locales.getDeclaredField("sIs24Hour"); hour.setAccessible(true); hour.set(null, Boolean.TRUE);
        var list = locales.getDeclaredField("sLocales"); list.setAccessible(true);
        list.set(null, new android.os.LocaleList(java.util.Locale.ROOT));
        String fixtureName = "com.gesher.server.ActivityFinishFixture";
        try (var fixtureLoader = new java.net.URLClassLoader(
                new java.net.URL[]{ActivityFinishHost.class.getProtectionDomain().getCodeSource().getLocation()},
                ActivityFinishHost.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(fixtureName) && !name.startsWith(fixtureName + "$")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) loaded = findClass(name);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }) {
            Class<?> fixture = Class.forName(fixtureName, true, fixtureLoader);
            if (fixture.getClassLoader() == com.gesher.server.ActivityTaskManagerService.class.getClassLoader())
                throw new AssertionError("Fixture must not share the production runtime package");
            int checks;
            try { checks = (int) fixture.getMethod("run", boolean.class, String.class).invoke(null, false, args[1]); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            System.out.println("Activity finish host all passed: " + checks + " checks");
            System.out.println("Actual public Activity/controller/task-manager code and transaction objects, separate fixture loader; controlled native identity and app acknowledgements. No ART, Parcel transport or window rendering claim.");
        }
    }
}
