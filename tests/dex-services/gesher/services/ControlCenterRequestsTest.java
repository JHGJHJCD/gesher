/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Actual production request publication on the host filesystem and on ART/Windows. */
public final class ControlCenterRequestsTest {
    public static void main(String[] args) throws Exception {
        Method write = Class.forName("com.gesher.server.ControlCenter")
                .getDeclaredMethod("writeRequest", File.class, String.class);
        write.setAccessible(true);
        File directory = Files.createTempDirectory("gesher-panel-publish-").toFile();
        File request = new File(directory, "control-panel-request");
        Set<String> complete = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            StringBuilder name = new StringBuilder("com.example.writer" + i);
            for (int n = 0; n < i * 19; n++) name.append('x');
            complete.add(name + "\n");
        }
        String[] names = complete.toArray(new String[0]);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread observer = new Thread(() -> {
            try {
                start.await();
                while (!done.get()) {
                    try {
                        String value = new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8);
                        if (!complete.contains(value)) throw new AssertionError("Partial or mixed request: " + value);
                    } catch (NoSuchFileException absentBeforeFirstPublication) { }
                }
            } catch (Throwable t) { failure.compareAndSet(null, t); }
        }, "panel-request-observer");
        Thread[] writers = new Thread[names.length];
        try {
            observer.start();
            for (int i = 0; i < writers.length; i++) {
                final String name = names[i].trim();
                writers[i] = new Thread(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < 80; n++) write.invoke(null, request, name);
                    } catch (Throwable t) { failure.compareAndSet(null, t); }
                }, "panel-request-writer-" + i);
                writers[i].start();
            }
            start.countDown();
            for (Thread writer : writers) writer.join(30000);
            for (Thread writer : writers) if (writer.isAlive()) throw new AssertionError("Request writer did not finish");
            done.set(true);
            observer.join(30000);
            if (observer.isAlive()) throw new AssertionError("Request observer did not finish");
            if (failure.get() != null) throw new AssertionError("Concurrent publication failed", failure.get());
            String value = new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8);
            if (!complete.contains(value)) throw new AssertionError("Final request was corrupted");
            File[] files = directory.listFiles();
            if (files == null || files.length != 1 || !files[0].equals(request)) {
                throw new AssertionError("Request publication left temporary files");
            }
            System.out.println("ControlCenterRequestsTest all passed: 640 concurrent writes, complete reads, no temporary files");
        } finally {
            start.countDown();
            done.set(true);
            for (Thread writer : writers) if (writer != null) writer.join(30000);
            observer.join(30000);
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }
}
