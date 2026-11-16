/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Public framework/ART/JNI lifecycle smoke; use GESHER_AUDIO=null, no hardware needed.
 * Deterministic admission/destructor ordering is covered by the checked-JNI host suite.
 */
public final class MediaReceiverLifecycleTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        ++checks;
    }

    private static void together(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[2];
        Runnable[] operations = {first, second};
        for (int i = 0; i < 2; i++) {
            final Runnable operation = operations[i];
            threads[i] = new Thread(() -> {
                ready.countDown();
                try {
                    if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("release barrier");
                    operation.run();
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }, "media-receiver-release-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        check(ready.await(5, TimeUnit.SECONDS), "both release workers ready");
        go.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
            check(!thread.isAlive(), "release worker finishes");
        }
        if (failure.get() != null) throw new AssertionError("release worker failed", failure.get());
        ++checks;
    }

    private static byte[] wav() {
        ByteBuffer b = ByteBuffer.allocate(204).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R', 'I', 'F', 'F'}).putInt(196);
        b.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
        b.putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000);
        b.putShort((short) 2).putShort((short) 16);
        b.put(new byte[] {'d', 'a', 't', 'a'}).putInt(160);
        return b.array();
    }

    private static void soundPool() throws Exception {
        File file = File.createTempFile("gesher-receiver-", ".wav");
        HandlerThread callbacks = new HandlerThread("media-receiver-callbacks");
        callbacks.start();
        AtomicReference<SoundPool> pool = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch built = new CountDownLatch(1), loaded = new CountDownLatch(1);
        AtomicInteger loadedId = new AtomicInteger(), loadStatus = new AtomicInteger(-1);
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(wav()); }
            new Handler(callbacks.getLooper()).post(() -> {
                try {
                    SoundPool value = new SoundPool.Builder().setMaxStreams(2)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA).build()).build();
                    value.setOnLoadCompleteListener((ignored, sound, status) -> {
                        loadedId.set(sound); loadStatus.set(status); loaded.countDown();
                    });
                    pool.set(value);
                } catch (Throwable error) { failure.set(error); }
                finally { built.countDown(); }
            });
            check(built.await(5, TimeUnit.SECONDS), "SoundPool constructed on callback looper");
            if (failure.get() != null) throw new AssertionError("SoundPool setup", failure.get());
            SoundPool value = pool.get();
            int sound = value.load(file.getPath(), 1);
            check(sound > 0 && loaded.await(10, TimeUnit.SECONDS), "real WAV decode completion");
            check(loadedId.get() == sound && loadStatus.get() == 0, "real load callback identity/status");
            int stream = value.play(sound, 1, 1, 0, -1, 1);
            check(stream > 0, "real SoundPool track created");
            value.pause(stream); value.resume(stream); value.setVolume(stream, .25f, .5f);
            together(value::release, value::release);
            value.release();
            check(value.play(sound, 1, 1, 0, 0, 1) == 0, "released SoundPool cannot start another track");
        } finally {
            SoundPool value = pool.get();
            if (value != null) value.release();
            callbacks.quitSafely(); callbacks.join(5000); file.delete();
        }
    }

    @SuppressWarnings("deprecation")
    private static void recorder() throws Exception {
        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.reset();
            together(recorder::release, recorder::release);
            recorder.release();
            boolean rejected = false;
            try { recorder.getMaxAmplitude(); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "released public MediaRecorder rejects access");
        } finally { recorder.release(); }
    }

    public static void main(String[] args) throws Exception {
        MediaTestBootstrap.initialize("media-receiver-lifecycle");
        soundPool();
        recorder();
        System.out.println("MediaReceiverLifecycleTest all passed: " + checks);
    }
}
