/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class CameraCaptureLifetimeHost {
    private static final int ABORT = 0x80004004, RESULT = 0x1234;
    private static final Map<Long, Native> handles = new ConcurrentHashMap<>();
    private static long nextHandle;
    private static int checks;
    private static final class Native {
        final long id = ++nextHandle;
        final AtomicInteger active = new AtomicInteger(), calls = new AtomicInteger(), closes = new AtomicInteger();
        final CountDownLatch entered;
        final CountDownLatch[] release;
        volatile boolean closed;
        volatile boolean failRead, failClose;
        volatile Runnable onRead, onClose;
        final WindowsCamera.Capture capture;
        Native(int readers) throws Exception {
            entered = new CountDownLatch(readers);
            release = new CountDownLatch[readers];
            for (int i = 0; i < readers; ++i) release[i] = new CountDownLatch(1);
            Constructor<WindowsCamera.Capture> constructor = WindowsCamera.Capture.class
                    .getDeclaredConstructor(long.class, int.class, int.class);
            constructor.setAccessible(true);
            capture = constructor.newInstance(id, 2, 2);
            handles.put(id, this);
        }
        void releaseAll() { for (CountDownLatch latch : release) latch.countDown(); }
    }
    private static void check(boolean ok, String description) {
        ++checks;
        if (!ok) throw new AssertionError(description);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("camera lifetime latch timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    static int read(long handle, byte[] frame) {
        Native nativeState = handles.get(handle);
        if (nativeState == null || nativeState.closed) throw new AssertionError("admitted native read used a destroyed/zero handle");
        int call = nativeState.calls.getAndIncrement();
        nativeState.active.incrementAndGet();
        try {
            nativeState.entered.countDown();
            if (nativeState.onRead != null) nativeState.onRead.run();
            if (call < nativeState.release.length) await(nativeState.release[call]);
            if (nativeState.closed) throw new AssertionError("native capture was destroyed during admitted read");
            if (nativeState.failRead) throw new IllegalStateException("read fixture failure");
            frame[0] = 42;
            return RESULT;
        } finally { nativeState.active.decrementAndGet(); }
    }
    static void close(long handle) {
        Native nativeState = handles.get(handle);
        if (nativeState == null || nativeState.active.get() != 0) throw new AssertionError("native pointer destroyed while read is active");
        if (nativeState.closes.incrementAndGet() != 1) throw new AssertionError("native pointer destroyed twice");
        nativeState.closed = true;
        if (nativeState.onClose != null) nativeState.onClose.run();
        if (nativeState.failClose) throw new IllegalStateException("close fixture failure");
    }
    private static Thread reader(Native state, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                byte[] frame = new byte[6];
                int result = state.capture.read(frame);
                if (result != RESULT || frame[0] != 42) throw new AssertionError("read result/payload changed");
            } catch (Throwable problem) { failure.compareAndSet(null, problem); }
        });
        thread.start(); return thread;
    }
    private static void join(Thread thread) throws Exception {
        thread.join(5000); check(!thread.isAlive(), "camera lifetime thread failed to finish");
    }
    private static void blocked(boolean failRead, int readers) throws Exception {
        Native state = new Native(readers); state.failRead = failRead;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[readers];
        for (int i = 0; i < readers; ++i) threads[i] = reader(state, failure);
        try {
            await(state.entered);
            CountDownLatch closed = new CountDownLatch(1);
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread closer = new Thread(() -> {
                try { state.capture.close(); } catch (Throwable problem) { closeFailure.set(problem); }
                finally { closed.countDown(); }
            });
            closer.start(); await(closed); join(closer);
            if (closeFailure.get() != null) throw new AssertionError("close destroyed an admitted native call", closeFailure.get());
            check(state.closes.get() == 0, "close waited for/destroyed a blocked read");
            check(state.capture.read(new byte[6]) == ABORT && state.calls.get() == readers,
                    "closed capture admitted another native read");
            for (int i = 0; i < 5; ++i) state.capture.close();
            check(state.closes.get() == 0, "repeated close lost deferred handle ownership");
            for (int i = 0; i < readers; ++i) {
                state.release[i].countDown();
                // Join by call order rather than thread start order.
                if (i != readers - 1) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (state.active.get() > readers - i - 1 && System.nanoTime() < deadline) Thread.yield();
                    check(state.closes.get() == 0, "capture destroyed before its last reader exited");
                }
            }
            for (Thread thread : threads) join(thread);
            if (failRead) check(failure.get() instanceof IllegalStateException, "read exception was lost");
            else if (failure.get() != null) throw new AssertionError(failure.get());
            check(state.closes.get() == 1 && state.closed, "last reader did not destroy retired handle once");
            state.capture.close(); check(state.closes.get() == 1, "completed retirement was destroyed again");
        } finally {
            state.releaseAll();
            for (Thread thread : threads) join(thread);
        }
    }
    private static void idleAndReentrant() throws Exception {
        Native idle = new Native(0);
        idle.capture.close(); idle.capture.close();
        check(idle.closes.get() == 1 && idle.capture.read(new byte[6]) == ABORT, "idle close/read-after-close changed");
        Native reentrant = new Native(0);
        reentrant.onRead = reentrant.capture::close;
        reentrant.onClose = () -> {
            if (reentrant.capture.read(new byte[6]) != ABORT) throw new AssertionError("close callback admitted read");
        };
        check(reentrant.capture.read(new byte[6]) == RESULT && reentrant.closes.get() == 1,
                "reentrant close destroyed native pointer before read returned");
        Native throwing = new Native(0); throwing.failClose = true;
        try { throwing.capture.close(); throw new AssertionError("native close exception lost"); }
        catch (IllegalStateException expected) { ++checks; }
        throwing.capture.close(); check(throwing.closes.get() == 1, "native close failure caused double destruction");
    }
    private static void races() throws Exception {
        for (int i = 0; i < 500; ++i) {
            Native state = new Native(0);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                await(start);
                try {
                    int result = state.capture.read(new byte[6]);
                    if (result != RESULT && result != ABORT) throw new AssertionError("unexpected racing result");
                } catch (Throwable problem) { failure.set(problem); }
            });
            Thread closer = new Thread(() -> { await(start); state.capture.close(); state.capture.close(); });
            reader.start(); closer.start(); start.countDown(); join(reader); join(closer);
            if (failure.get() != null) throw new AssertionError("read/close race", failure.get());
            check(state.closes.get() == 1, "racing close did not destroy capture once");
            handles.remove(state.id);
        }
    }
    public static void main(String[] args) throws Exception {
        blocked(false, 1); blocked(false, 2); blocked(true, 1); blocked(true, 2);
        idleAndReentrant(); races();
        System.out.println("Camera capture lifetime PASS: " + checks + " checks");
    }
}
