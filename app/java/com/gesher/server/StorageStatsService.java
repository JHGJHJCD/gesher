/*
 * Copyright (C) 2026 The Gesher Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.gesher.server;

import android.app.usage.ExternalStorageStats;
import android.app.usage.IStorageStatsManager;
import android.app.usage.StorageStats;
import android.content.pm.ParceledListSlice;
import android.os.storage.StorageManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code storagestats}: StorageStatsManager (the storage screens of file managers and
 * cleaners). Without it getSystemService answered null and those apps showed no sizes
 * or failed ("No service published for: storagestats").
 *
 * <p>There is one volume, the private data volume (its emulated shared view is
 * {@code media/0} on the same Windows drive), and one user. Sizes are measured on the host
 * files: an app's code directory, its data directory with its cache, and its directories
 * under {@code media/0/Android}. Walks stop after {@link #MAX_ENTRIES} entries, so a huge
 * tree answers an underestimate instead of blocking the caller. Quotas and reserved space
 * are not supported, as on a device without ext4 quotas.
 */
final class StorageStatsService extends IStorageStatsManager.Default {
    static final int MAX_ENTRIES = 200_000;

    private final PackageManagerService mPm;
    private final File mData;

    StorageStatsService(PackageManagerService pm) {
        this(pm, android.os.Environment.getDataDirectory());
    }

    StorageStatsService(PackageManagerService pm, File dataDirectory) {
        mPm = Objects.requireNonNull(pm);
        mData = Objects.requireNonNull(dataDirectory);
    }

    private static void volume(String volumeUuid) {
        // StorageStatsManager.convert: UUID_DEFAULT arrives as null (UUID_PRIVATE_INTERNAL),
        // the emulated shared view as "primary_physical". Both are the one data volume.
        if (volumeUuid != null && !volumeUuid.equals(StorageManager.UUID_PRIMARY_PHYSICAL)
                && !volumeUuid.equals(StorageManager.UUID_DEFAULT.toString())) {
            throw new IllegalArgumentException("Unknown volume " + volumeUuid);
        }
    }

    private static void user(int userId) {
        if (userId != 0) throw new IllegalArgumentException("Unknown user " + userId);
    }

    @Override
    public boolean isQuotaSupported(String volumeUuid, String callingPackage) {
        return false;
    }

    @Override
    public boolean isReservedSupported(String volumeUuid, String callingPackage) {
        return false;
    }

    @Override
    public long getTotalBytes(String volumeUuid, String callingPackage) {
        volume(volumeUuid);
        return mData.getTotalSpace();
    }

    @Override
    public long getFreeBytes(String volumeUuid, String callingPackage) {
        volume(volumeUuid);
        return mData.getUsableSpace();
    }

    @Override
    public long getCacheBytes(String volumeUuid, String callingPackage) {
        volume(volumeUuid);
        long total = 0;
        for (ApkPackage p : mPm.packages()) total += cacheBytes(p);
        return total;
    }

    @Override
    public long getCacheQuotaBytes(String volumeUuid, int uid, String callingPackage) {
        volume(volumeUuid);
        // No quota daemon: the share of free space Android's default policy allows one app.
        return Math.max(0, mData.getUsableSpace() / 10);
    }

    @Override
    public StorageStats queryStatsForPackage(String volumeUuid, String packageName, int userId,
            String callingPackage) {
        volume(volumeUuid);
        user(userId);
        ApkPackage p = packageName == null ? null : mPm.get(packageName);
        if (p == null || mPm.isRemovalPending(packageName)) {
            throw new android.os.ParcelableException(
                    new android.content.pm.PackageManager.NameNotFoundException(packageName));
        }
        StorageStats stats = new StorageStats();
        add(stats, p);
        return stats;
    }

    @Override
    public StorageStats queryStatsForUid(String volumeUuid, int uid, String callingPackage) {
        volume(volumeUuid);
        StorageStats stats = new StorageStats();
        for (ApkPackage p : mPm.packages()) {
            if (p.uid() == uid && !mPm.isRemovalPending(p.packageName)) add(stats, p);
        }
        return stats;
    }

    @Override
    public StorageStats queryStatsForUser(String volumeUuid, int userId, String callingPackage) {
        volume(volumeUuid);
        user(userId);
        StorageStats stats = new StorageStats();
        for (ApkPackage p : mPm.packages()) {
            if (!mPm.isRemovalPending(p.packageName)) add(stats, p);
        }
        return stats;
    }

    @Override
    public ExternalStorageStats queryExternalStatsForUser(String volumeUuid, int userId,
            String callingPackage) {
        volume(volumeUuid);
        user(userId);
        File shared = new File(mData, "media/0");
        ExternalStorageStats stats = new ExternalStorageStats();
        long[] byKind = new long[5];  // total, audio, video, image, app (Android/data|media|obb)
        walk(shared, (path, size) -> {
            byKind[0] += size;
            String rel = shared.toPath().relativize(path).toString().replace('\\', '/');
            if (rel.startsWith("Android/obb/")) {
                stats.obbBytes += size;
                byKind[4] += size;
                return;
            }
            if (rel.startsWith("Android/data/") || rel.startsWith("Android/media/")) {
                byKind[4] += size;
                return;
            }
            switch (kind(rel)) {
                case 1: byKind[1] += size; break;
                case 2: byKind[2] += size; break;
                case 3: byKind[3] += size; break;
                default: break;
            }
        });
        stats.totalBytes = byKind[0];
        stats.audioBytes = byKind[1];
        stats.videoBytes = byKind[2];
        stats.imageBytes = byKind[3];
        stats.appBytes = byKind[4];
        return stats;
    }

    @Override
    public ParceledListSlice queryCratesForPackage(String volumeUuid, String packageName,
            int userId, String callingPackage) {
        return ParceledListSlice.emptyList();
    }

    @Override
    public ParceledListSlice queryCratesForUid(String volumeUuid, int uid, String callingPackage) {
        return ParceledListSlice.emptyList();
    }

    @Override
    public ParceledListSlice queryCratesForUser(String volumeUuid, int userId,
            String callingPackage) {
        return ParceledListSlice.emptyList();
    }

    /** 1 audio, 2 video, 3 image, 0 other: by extension, as MediaProvider's MIME map would. */
    static int kind(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.lastIndexOf('/') > dot) return 0;
        switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "mp3": case "m4a": case "aac": case "ogg": case "oga": case "opus": case "flac":
            case "wav": case "amr": case "mid": case "midi": case "wma":
                return 1;
            case "mp4": case "m4v": case "3gp": case "3gpp": case "mkv": case "webm": case "avi":
            case "mov": case "wmv": case "ts":
                return 2;
            case "jpg": case "jpeg": case "png": case "gif": case "webp": case "bmp": case "heic":
            case "heif": case "avif": case "dng": case "svg":
                return 3;
            default:
                return 0;
        }
    }

    private void add(StorageStats stats, ApkPackage p) {
        long code = codeBytes(p);
        long cache = cacheBytes(p);
        File external = new File(mData, "media/0/Android/data/" + p.packageName);
        long externalCache = size(new File(external, "cache"));
        // Android's dataBytes includes the cache directories and the app's external dirs.
        long data = size(p.dataDir() != null ? new File(p.dataDir()) : null)
                + size(external) + size(new File(mData, "media/0/Android/media/" + p.packageName));
        stats.codeBytes += code;
        stats.apkBytes += p.apkPath != null ? new File(p.apkPath).length() : 0;
        stats.dataBytes += data;
        stats.cacheBytes += cache + externalCache;
        stats.externalCacheBytes += externalCache;
    }

    /** The installed code directory (data/app/<package>-N: APKs, native libraries, oat). */
    private long codeBytes(ApkPackage p) {
        if (p.apkPath == null) return 0;
        File apk = new File(p.apkPath);
        File dir = apk.getParentFile();
        File apps = new File(mData, "app");
        if (dir != null && apps.equals(dir.getParentFile())) return size(dir);
        return apk.length();
    }

    private long cacheBytes(ApkPackage p) {
        if (p.dataDir() == null) return 0;
        File data = new File(p.dataDir());
        return size(new File(data, "cache")) + size(new File(data, "code_cache"));
    }

    private interface Visitor {
        void file(Path path, long size);
    }

    static long size(File root) {
        long[] total = {0};
        walk(root, (path, size) -> total[0] += size);
        return total[0];
    }

    /** Every regular file under root, not following links, up to MAX_ENTRIES entries. */
    private static void walk(File root, Visitor visitor) {
        if (root == null || !root.exists()) return;
        int[] entries = {0};
        try {
            Files.walkFileTree(root.toPath(), java.util.Collections.emptySet(), 64,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                            return ++entries[0] > MAX_ENTRIES ? FileVisitResult.TERMINATE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                            if (a.isRegularFile()) visitor.file(file, a.size());
                            return ++entries[0] > MAX_ENTRIES ? FileVisitResult.TERMINATE
                                    : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | RuntimeException e) {
            // What was counted so far: a partial size, never a failure of the caller's query.
        }
    }
}
