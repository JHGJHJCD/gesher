/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.Application;
import android.content.Context;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Real process setup for standalone public media API fixtures. */
final class MediaTestBootstrap {
    private MediaTestBootstrap() {}

    static void initialize(String tag) throws Exception {
        // AudioEffect attribution, FileUtils' FD conversion and MediaPlayer
        // consult the process Application and services before entering JNI.
        // Main owns android_runtime loading on the boot class path. Loading it
        // first from this DEX would bind it to the test's PathClassLoader.
        String root = System.getenv("ANDROID_ROOT");
        Class.forName("com.gesher.app.Main")
                .getMethod("bootstrap", String.class, String.class, String.class)
                .invoke(null, tag, root + "/build.prop",
                        root + "/framework/framework-res.apk");
        Class<?> shared = Class.forName("com.android.internal.os.ApplicationSharedMemory");
        Object region = shared.getMethod("create").invoke(null);
        shared.getMethod("setInstance", shared).invoke(null, region);
        Class<?> services = Class.forName("com.gesher.server.Services");
        Object registry = services.getMethod("createInProcess", Collection.class)
                .invoke(null, List.of());
        services.getMethod("installInProcess", Map.class).invoke(null, registry);

        Class<?> threadType = Class.forName("android.app.ActivityThread");
        Constructor<?> constructor = threadType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();
        Context context = (Context) threadType.getMethod("getSystemContext").invoke(thread);
        Application application = new Application();
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        attach.invoke(application, context);
        // Publish the same Application in LoadedApk and ActivityThread, as
        // application binding does. Keep framework provider/attribution lookups.
        Field packageInfo = context.getClass().getDeclaredField("mPackageInfo");
        packageInfo.setAccessible(true);
        Object loaded = packageInfo.get(context);
        Field loadedApplication = loaded.getClass().getDeclaredField("mApplication");
        loadedApplication.setAccessible(true);
        loadedApplication.set(loaded, application);
        Field initial = threadType.getDeclaredField("mInitialApplication");
        initial.setAccessible(true);
        initial.set(thread, application);
        Field current = threadType.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        application.onCreate();
        if (threadType.getMethod("currentApplication").invoke(null) != application
                || application.getApplicationContext() != application
                || application.getPackageManager() == null
                || application.getAttributionSource() == null) {
            throw new AssertionError("real media Application supplies context and attribution");
        }
    }
}
