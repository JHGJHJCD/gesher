/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
import java.io.FileDescriptor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real JNI object fields/entrypoints; codecs, tracks and boot classes are host boundaries. */
public final class MediaReceiverLifetimeHost {
    private long context;
    private final CountDownLatch loaded = new CountDownLatch(1);
    private static final AtomicInteger checks = new AtomicInteger();
    private static final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    private static final AtomicReference<Throwable> reentryFailure = new AtomicReference<>();
    private native int poolSetup(int streams, Object attributes, String packageName);
    private native int poolLoad(FileDescriptor fd, long offset, long length, int priority);
    private native int poolPlay(int sound, float left, float right, int priority, int loop, float rate, int player);
    private native void poolRelease();
    private native void poolMute(boolean mute);
    private native void poolVolume(int stream, float left, float right);
    private native void recorderSetup(Object self, String packageName, Object attribution);
    private native void recorderRelease();
    private native void recorderPrepare();
    private native void recorderStart();
    private native void recorderReset();
    private native int recorderAmplitude();
    private native Object recorderSurface();
    private native int recorderReferences();
    private static native void arm(int which);
    private static native boolean await(int which);
    private static native void open(int which);
    private static native int count(int which);
    private static native float volume(int side);
    private static native void eventMode(int value);

    private void loaded(int event, int sound, int status, Object unused) {
        if (event == 1 && sound > 0 && status == 0) loaded.countDown();
    }

    private static void recorderEvent(Object self, int what, int extra, int unused, Object data) {
        MediaReceiverLifetimeHost receiver = (MediaReceiverLifetimeHost) self;
        try {
            if (extra == 1) receiver.recorderRelease();
            else if (extra == 2) receiver.recorderReset();
        } catch (Throwable error) {
            reentryFailure.set(error);
        }
    }

    private static void check(boolean value, String message) {
        checks.incrementAndGet();
        if (!value) {
            System.err.println("FAIL media receiver: " + message);
            // Stop a baseline failure before the blocked operation touches a
            // receiver that the old implementation has already destroyed.
            Runtime.getRuntime().halt(1);
        }
    }

    private static Thread worker(Runnable body) {
        Thread thread = new Thread(() -> {
            try { body.run(); } catch (Throwable error) { workerFailure.set(error); }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) throws Exception {
        thread.join(5000);
        check(!thread.isAlive(), "worker completed");
        check(workerFailure.get() == null, "worker exception: " + workerFailure.get());
    }

    private static void releasedRecorder(MediaReceiverLifetimeHost receiver) {
        boolean thrown = false;
        try { receiver.recorderAmplitude(); } catch (IllegalStateException expected) { thrown = true; }
        check(thrown, "released recorder throws IllegalStateException");
    }

    private static MediaReceiverLifetimeHost pool() throws Exception {
        MediaReceiverLifetimeHost receiver = new MediaReceiverLifetimeHost();
        check(receiver.poolSetup(4, new Object(), "host") == 0, "SoundPool setup");
        int sound = receiver.poolLoad(FileDescriptor.in, 0, 16, 1);
        check(sound > 0 && receiver.loaded.await(5, TimeUnit.SECONDS), "SoundPool loader completed");
        check(receiver.poolPlay(sound, 1, 1, 0, 0, 1, 0) > 0, "SoundPool play");
        return receiver;
    }

    private static MediaReceiverLifetimeHost recorder() {
        MediaReceiverLifetimeHost receiver = new MediaReceiverLifetimeHost();
        receiver.recorderSetup(receiver, "host", null);
        return receiver;
    }

    private static void poolLifetime() throws Exception {
        MediaReceiverLifetimeHost receiver = pool();
        arm(0);
        Thread loading = worker(() -> receiver.poolLoad(FileDescriptor.in, 0, 16, 1));
        check(await(0), "load reached file-source boundary with receiver admitted");
        receiver.poolRelease();
        check(count(0) == 0, "release retains the SoundPool while load is admitted");
        receiver.poolRelease();
        check(count(0) == 0, "duplicate release cannot destroy an admitted SoundPool");
        check(receiver.poolPlay(1, 1, 1, 0, 0, 1, 0) == 0, "new play sees detached SoundPool");
        open(0); join(loading);
        check(count(0) == 1, "last admitted load releases SoundPool tracks exactly once");
        receiver.poolRelease();
        check(count(0) == 1, "post-completion release is inert");
    }

    private static void poolVolume() throws Exception {
        MediaReceiverLifetimeHost receiver = pool();
        receiver.poolMute(true);
        receiver.poolVolume(1, .25f, .5f);
        check(volume(0) == 0 && volume(1) == 0, "setVolume cannot unmute a muted pool");
        receiver.poolMute(false);
        check(volume(0) == .25f && volume(1) == .5f, "unmute restores requested volume");
        receiver.poolVolume(1, .75f, 1f);
        check(volume(0) == .75f && volume(1) == 1, "unmuted volume changes immediately");
        receiver.poolRelease();
    }

    private static void recorderLifetime() throws Exception {
        MediaReceiverLifetimeHost receiver = recorder();
        arm(1);
        Thread preparing = worker(receiver::recorderPrepare);
        check(await(1), "prepare reached backend with receiver admitted");
        receiver.recorderRelease();
        check(count(1) == 0, "release retains recorder while prepare is admitted");
        receiver.recorderRelease();
        releasedRecorder(receiver);
        open(1); join(preparing);
        check(count(1) == 1, "last admitted operation destroys recorder exactly once");
        receiver.recorderRelease();
        check(count(1) == 1, "duplicate recorder release is inert");
    }

    private static void serializedRecorder() throws Exception {
        MediaReceiverLifetimeHost receiver = recorder();
        Object surface = receiver.recorderSurface();
        check(surface == receiver.recorderSurface() && count(3) == 1, "getSurface retains one Java surface");
        arm(1);
        Thread preparing = worker(receiver::recorderPrepare);
        check(await(1), "prepare is active");
        Thread resetting = worker(receiver::recorderReset);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (receiver.recorderReferences() < 3 && System.nanoTime() < deadline) Thread.yield();
        check(receiver.recorderReferences() == 3, "reset admitted behind prepare");
        check(count(2) == 0, "reset cannot enter the backend during prepare");
        open(1); join(preparing); join(resetting);
        check(count(2) == 1, "queued reset enters after prepare completes");
        check(surface != receiver.recorderSurface() && count(3) == 2, "reset retires the cached Java surface");
        receiver.recorderRelease();
    }

    private static void concurrentRelease() throws Exception {
        MediaReceiverLifetimeHost pool = pool();
        MediaReceiverLifetimeHost recorder = recorder();
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        Runnable release = () -> {
            ready.countDown();
            try { check(go.await(5, TimeUnit.SECONDS), "release barrier"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            pool.poolRelease(); recorder.recorderRelease();
        };
        Thread one = worker(release), two = worker(release);
        check(ready.await(5, TimeUnit.SECONDS), "both release workers ready");
        go.countDown(); join(one); join(two);
        check(count(0) == 1 && count(1) == 1, "concurrent releases destroy each receiver once");
    }

    private static void reentrantRecorder() {
        MediaReceiverLifetimeHost receiver = recorder();
        eventMode(2); receiver.recorderStart();
        check(reentryFailure.get() instanceof IllegalStateException, "reentrant reset rejected before backend mutation");
        check(count(2) == 0, "reentrant reset did not run");
        reentryFailure.set(null);
        eventMode(1); receiver.recorderStart();
        check(reentryFailure.get() == null, "callback may release recorder");
        check(count(1) == 1, "callback release destroys recorder after active call returns");
        releasedRecorder(receiver);
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        switch (args[1]) {
            case "pool-lifetime": poolLifetime(); break;
            case "pool-volume": poolVolume(); break;
            case "recorder-lifetime": recorderLifetime(); break;
            case "recorder-serialization": serializedRecorder(); break;
            case "concurrent-release": concurrentRelease(); break;
            case "recorder-reentry": reentrantRecorder(); break;
            default: throw new AssertionError(args[1]);
        }
        System.out.println("Media receiver " + args[1] + ": " + checks.get() + " checks passed");
    }
}
