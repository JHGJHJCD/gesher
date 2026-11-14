/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
import android.media.MediaDataSource;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Genuine JNI callback contract; media containers/ART are Windows gates. */
public final class MediaCallbackHost {
    private static int checks;
    private static native long open(Object source);
    private static native long read(long handle, long offset, int length, byte[] output);
    private static native long size(long handle);
    private static native void close(long handle, boolean pending);
    private static void check(boolean value, String label) {
        ++checks;
        if (!value) throw new AssertionError(label);
    }
    private static class Source extends MediaDataSource {
        volatile int reads, sizes, closes, mode;
        volatile long length = 1024;
        volatile int limit = Integer.MAX_VALUE;
        volatile boolean failSize, failClose;
        public int readAt(long at, byte[] bytes, int offset, int size) throws IOException {
            ++reads;
            if (mode == 1) return -1;
            if (mode == 2) return -2;
            if (mode == 3) throw new IOException("controlled read failure");
            if (mode == 4) { Arrays.fill(bytes, offset, offset + size + 1, (byte)42); return size + 1; }
            int count = Math.min(size, limit);
            for (int i = 0; i < count; ++i) bytes[offset + i] = (byte)(at + i);
            return count;
        }
        public long getSize() throws IOException {
            ++sizes;
            if (failSize) throw new IOException("controlled size failure");
            return length;
        }
        public void close() throws IOException {
            ++closes;
            if (failClose) throw new IOException("controlled close failure");
        }
    }
    private static byte[] output(int n) { return new byte[n + 16]; }
    private static void valid() {
        Source source = new Source();
        source.limit = 3;
        long h = open(source);
        check(h != 0, "adapter opens");
        byte[] bytes = output(100000);
        check(read(h, 7, 100000, bytes) == 100000, "accumulates valid short reads across shared buffer");
        check(bytes[0] == 7 && bytes[99999] == (byte)100006, "callback payload offsets");
        check(bytes[100000] == 0x6d, "destination suffix stays intact");
        int before = source.reads;
        check(read(h, 0, 0, output(0)) == 0 && source.reads == before, "zero read never invokes callback");
        source.mode = 1;
        check(read(h, 0, 4, output(4)) == 0, "minus one is EOF");
        source.mode = 0;
        check(read(h, 3, 4, output(4)) == 4, "EOF does not poison another random access");
        close(h, false);
        check(source.closes == 1, "closes once");
    }
    private static void status(int mode) {
        Source source = new Source(); source.mode = mode;
        long h = open(source);
        byte[] bytes = output(4);
        try {
            check(read(h, 0, 4, bytes) == -1, "callback failure status " + mode);
            check(bytes[4] == 0x6d, "failed report never touches destination suffix");
            source.mode = 0;
            check(read(h, 0, 4, output(4)) == -1 && source.reads == 1,
                    "failure is retained without another callback");
            check(size(h) == -1 && source.sizes == 0, "failed read prevents size callback");
        } finally { close(h, false); }
        check(source.closes == 1, "failed source still closes");
    }
    private static void sizeCache(boolean unknown) {
        Source source = new Source(); source.length = unknown ? -9 : 4242;
        long h = open(source);
        try {
            check(size(h) == (unknown ? -1 : 4242), "normalizes negative length");
            source.length = 9999;
            check(size(h) == (unknown ? -1 : 4242) && source.sizes == 1, "size is cached");
        } finally { close(h, false); }
    }
    private static void failedSize() {
        Source source = new Source(); source.failSize = true;
        long h = open(source);
        try {
            check(size(h) == -1, "getSize exception becomes source error");
            source.failSize = false;
            check(size(h) == -1 && source.sizes == 1, "getSize error remains latched");
            check(read(h, 0, 4, output(4)) == -1 && source.reads == 0, "getSize error prevents future reads");
        } finally { close(h, false); }
    }
    private static void serialized() throws Exception {
        CountDownLatch first = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1), secondAttempt = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), max = new AtomicInteger();
        Source source = new Source() {
            public int readAt(long at, byte[] buffer, int offset, int count) throws IOException {
                int n = active.incrementAndGet(); max.accumulateAndGet(n, Math::max);
                try {
                    if (at == 1) {
                        Arrays.fill(buffer, offset, offset + count, (byte)1);
                        first.countDown();
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
                    } else {
                        Arrays.fill(buffer, offset, offset + count, (byte)2);
                        secondEntered.countDown();
                    }
                    return count;
                } catch (InterruptedException e) { throw new IOException(e); }
                finally { active.decrementAndGet(); }
            }
        };
        long h = open(source);
        AtomicReference<Throwable> error = new AtomicReference<>();
        byte[] a = output(4), b = output(4);
        Thread t1 = new Thread(() -> {
            try { if (read(h, 1, 4, a) != 4) throw new AssertionError("first read"); }
            catch (Throwable t) { error.compareAndSet(null, t); }
        });
        Thread t2 = new Thread(() -> {
            try { secondAttempt.countDown(); if (read(h, 2, 4, b) != 4) throw new AssertionError("second read"); }
            catch (Throwable t) { error.compareAndSet(null, t); }
        });
        t1.setDaemon(true); t2.setDaemon(true);
        boolean overlap = false;
        try {
            t1.start(); check(first.await(5, TimeUnit.SECONDS), "first callback entered");
            t2.start(); check(secondAttempt.await(5, TimeUnit.SECONDS), "second reader started");
            overlap = secondEntered.await(200, TimeUnit.MILLISECONDS);
        } finally {
            release.countDown();
            t1.join(5000); t2.join(5000);
        }
        check(!t1.isAlive() && !t2.isAlive(), "reads complete in bounded time");
        close(h, false);
        if (error.get() != null) throw new AssertionError(error.get());
        check(!overlap && max.get() == 1, "Java shared buffer callbacks serialize");
        check(a[0] == 1 && b[0] == 2, "concurrent reads keep their own bytes");
        check(source.closes == 1, "serialized source closes");
    }
    private static void pending() {
        Source source = new Source(); source.failClose = true;
        long h = open(source);
        try { close(h, true); throw new AssertionError("original exception expected"); }
        catch (IllegalStateException expected) { check("original setup failure".equals(expected.getMessage()), "cleanup preserves original exception"); }
        check(source.closes == 1, "close executes once without a pending JNI exception");
        try { open(null); throw new AssertionError("null source"); }
        catch (IllegalArgumentException expected) { ++checks; }
        try { open(new Object()); throw new AssertionError("missing callback methods"); }
        catch (NoSuchMethodError expected) { ++checks; }
    }
    private static void closeError() {
        Source source = new Source(); source.failClose = true;
        long h = open(source);
        Throwable failure = null;
        try { close(h, false); } catch (Throwable error) { failure = error; }
        check(failure instanceof IOException, "ordinary release propagates close IOException");
        check(source.closes == 1, "exceptional close retires source once");
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        String group = args[1];
        switch (group) {
            case "valid": valid(); break;
            case "negative": status(2); break;
            case "exception": status(3); break;
            case "overcount": status(4); break;
            case "known-size": sizeCache(false); break;
            case "unknown-size": sizeCache(true); break;
            case "size-error": failedSize(); break;
            case "serialized": serialized(); break;
            case "close-error": closeError(); break;
            case "pending": pending(); break;
            default: throw new IllegalArgumentException(group);
        }
        System.out.println("MediaDataSource " + group + ": " + checks + " checks passed");
    }
}
