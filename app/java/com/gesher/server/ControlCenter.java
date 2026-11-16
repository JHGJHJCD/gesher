/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Opens Gesher's Control Center (tools/package/gesher-control-panel.ps1), optionally on one
 * app's details: Android's "App info", which Gesher has no Settings APK for
 * ({@link SettingsIntents}).
 *
 * <p>The app goes in {@code system/control-panel-request} (one package name, validated by
 * the caller); the Control Center reads and deletes it as it starts and on its live-refresh
 * timer, so a Control Center already open shows the app too. ShellExecute takes no
 * arguments here ({@link WindowsHost#openUrl}), so the Control Center is opened through a
 * shortcut to {@code gesher-launch.exe control-panel} that this class keeps next to the
 * request; a second Control Center leaves the request to the first and exits.
 */
final class ControlCenter {
    private ControlCenter() {
    }

    static final String REQUEST = "system/control-panel-request";
    static final String SHORTCUT = "system/gesher-control-panel.lnk";

    /** Replaced by tests: opens the Control Center on a package (null: its main view). */
    static volatile java.util.function.Predicate<String> sOpen = ControlCenter::openOnHost;

    static boolean open(String packageName) {
        try {
            return sOpen.test(packageName);
        } catch (RuntimeException | LinkageError e) {
            SystemServer.log("Control Center: " + e);
            return false;
        }
    }

    private static boolean openOnHost(String packageName) {
        File request = new File(Device.dataDir(), REQUEST);
        try {
            writeRequest(request, packageName);
        } catch (IOException e) {
            SystemServer.log("Control Center: no request file: " + e);
            return false;
        }
        String exe = Zygote.exePath();
        if (exe == null || exe.lastIndexOf('\\') < 0) return false;
        String launcher = exe.substring(0, exe.lastIndexOf('\\')) + "\\gesher-launch.exe";
        if (!com.gesher.app.shell.Shell.exists(launcher)) {
            SystemServer.log("Control Center: no " + launcher + " (not an installed Gesher)");
            request.delete();
            return false;
        }
        String link = WindowsHost.fullPath(new File(Device.dataDir(), SHORTCUT).getPath());
        String dir = launcher.substring(0, launcher.lastIndexOf('\\'));
        int hr = com.gesher.app.shell.Shell.createShortcut(link, launcher, "control-panel", dir,
                dir + "\\gesher.ico", "Gesher", "Gesher.ControlPanel");
        if (hr != 0 && !com.gesher.app.shell.Shell.exists(link)) {
            SystemServer.log("Control Center: no shortcut: 0x" + Integer.toHexString(hr));
            request.delete();
            return false;
        }
        return WindowsHost.openUrl(link);
    }

    /** The request: one line, the package (empty for the main view); replaced atomically. */
    static void writeRequest(File request, String packageName) throws IOException {
        File dir = request.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        File temp = new File(dir, request.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write((packageName == null ? "" : packageName).getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        }
        if (!temp.renameTo(request)) {
            request.delete();
            if (!temp.renameTo(request)) {
                temp.delete();
                throw new IOException("cannot replace " + request);
            }
        }
    }
}
