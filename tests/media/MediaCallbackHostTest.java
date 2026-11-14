/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Host JVM exercises production JNI adapters; the media-parser boundary is synthetic. */
public final class MediaCallbackHostTest {
    private long context;
    private native void setup(boolean extractor);
    private native void release(boolean extractor);
    private native void source(boolean extractor, Source callback) throws IOException;
    private native String metadata(int key);
    private native byte[] artwork(int type);
    private native int tracks();
    private static native byte[] read(Source callback, long offset, int size);
    private static native long probe(Source callback, int mode) throws IOException;
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    private interface Operation { void run() throws Exception; }
    private static void requires(Class<? extends Throwable> type, Operation op, String label) throws Exception {
        try { op.run(); } catch (Throwable t) { if (type.isInstance(t)) { checks++; return; } throw t; }
        throw new AssertionError(label);
    }
    public static final class Source {
        final byte[] bytes;
        volatile int reads, sizes, closes;
        long size;
        int invalid;
        boolean failRead, failSize, failClose;
        CountDownLatch entered, resume;
        Source(byte[] bytes) { this.bytes = bytes; size = bytes.length; }
        Source(int tag) { this(new byte[] {(byte) tag}); }
        public int readAt(long offset, byte[] data, int start, int length) throws IOException {
            ++reads;
            if (entered != null) {
                entered.countDown();
                try { if (!resume.await(10, TimeUnit.SECONDS)) throw new IOException("barrier timeout"); }
                catch (InterruptedException ex) { throw new IOException(ex); }
            }
            if (failRead) throw new IOException("source read failure");
            if (invalid != 0) return invalid == 1 ? length + 1 : -7;
            if (offset >= bytes.length) return -1;
            int count = Math.min(Math.min(length, 777), bytes.length - (int) offset);
            System.arraycopy(bytes, (int) offset, data, start, count);
            return count;
        }
        public long getSize() throws IOException { ++sizes; if (failSize) throw new IOException("size failure"); return size; }
        public void close() throws IOException { ++closes; if (failClose) throw new IOException("close failure"); }
    }
    private static void adapter() throws Exception {
        byte[] data = new byte[131077];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 17 + 3);
        Source good = new Source(data);
        check(Arrays.equals(read(good, 0, data.length + 11), data), "short reads, bounded chunks and EOF");
        check(good.closes == 1, "read adapter closes once");
        Source concurrent = new Source(data);
        check(probe(concurrent, 3) == 2 && concurrent.closes == 1, "concurrent native readers serialize callback buffer");
        Source unknown = new Source(1); unknown.size = -1;
        check(probe(unknown, 0) == -1 && unknown.sizes == 1, "unknown size cached as -1");
        Source invalidSize = new Source(1); invalidSize.size = -8;
        check(probe(invalidSize, 0) == -1 && invalidSize.sizes == 1, "negative sizes normalize and cache");
        for (int bad : new int[] {1, 2}) {
            Source source = new Source(1); source.invalid = bad;
            check(probe(source, 1) == -1 && source.reads == 1, "invalid return count latches failure");
            check(source.closes == 1, "invalid callback closes once");
        }
        Source throwing = new Source(1); throwing.failRead = true;
        check(probe(throwing, 1) == -1 && throwing.reads == 1, "read IOException converted and latched");
        Source sizeFailure = new Source(1); sizeFailure.failSize = true;
        check(probe(sizeFailure, 0) == -1 && sizeFailure.sizes == 1, "size IOException converted and latched");
        Source zero = new Source(1);
        check(read(zero, 0, 0).length == 0 && zero.reads == 0, "zero read avoids callback");
        Source negative = new Source(1);
        check(read(negative, -1, 1) == null && negative.reads == 0, "negative offset rejected");
        Source overflow = new Source(1);
        check(read(overflow, Long.MAX_VALUE, 1) == null && overflow.reads == 0, "offset extent overflow rejected");
        Source closeFailure = new Source(1); closeFailure.failClose = true;
        try { probe(closeFailure, 2); throw new AssertionError("pending error lost"); }
        catch (IOException expected) { check("original failure".equals(expected.getMessage()), "close preserves pending exception"); }
        check(closeFailure.closes == 1, "throwing close invoked once");
        requires(IllegalArgumentException.class, () -> read(null, 0, 1), "null source rejected");
    }
    private static void retriever() throws Exception {
        MediaCallbackHostTest r = new MediaCallbackHostTest(); r.setup(false);
        Source art = new Source(2), plain = new Source(1), bad = new Source(0), next = new Source(3);
        try {
            r.source(false, art);
            check(Arrays.equals(r.artwork(-1), new byte[] {11, 22, 33}), "art belongs to first source");
            check("2".equals(r.metadata(7)), "first metadata is readable");
            r.source(false, plain);
            check(r.artwork(-1) == null && "1".equals(r.metadata(7)), "replacement clears absent art and replaces metadata");
            check(art.closes == 1, "replacement closes original source");
            requires(RuntimeException.class, () -> r.source(false, bad), "unreadable callback is rejected");
            check(r.artwork(-1) == null && r.metadata(7) == null, "failed replacement clears coherent snapshot");
            check(plain.closes == 1 && bad.closes == 1, "failed replacement closes both unused sources");
            next.size = Long.MAX_VALUE; r.source(false, next);
            check("3".equals(r.metadata(7)) && r.metadata(20) == null, "retry succeeds; overflowing derived bitrate is absent");
        } finally { r.release(false); r.release(false); }
        check(next.closes == 1, "retriever releases source once");
        requires(IllegalStateException.class, () -> r.metadata(7), "released retriever rejects metadata");
        requires(IllegalStateException.class, () -> r.artwork(-1), "released retriever rejects artwork");
    }
    private static void extractor() throws Exception {
        MediaCallbackHostTest e = new MediaCallbackHostTest(); e.setup(true);
        Source bad = new Source(0), good = new Source(1), second = new Source(2);
        try {
            requires(IOException.class, () -> e.source(true, bad), "first invalid extractor source rejected");
            check(bad.closes == 1, "failed extractor source closes");
            e.source(true, good); check(e.tracks() == 1, "retry extractor succeeds");
            requires(IOException.class, () -> e.source(true, second), "second extractor source rejected");
            check(e.tracks() == 1 && good.closes == 0 && second.closes == 1, "rejected candidate closes, original retained");
        } finally { e.release(true); e.release(true); }
        check(good.closes == 1, "extractor releases source once");
        requires(IllegalStateException.class, e::tracks, "released extractor rejects query");
    }
    private static void releaseRace(boolean extractor) throws Exception {
        MediaCallbackHostTest r = new MediaCallbackHostTest(); r.setup(extractor);
        Source delayed = new Source(1);
        delayed.entered = new CountDownLatch(1); delayed.resume = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread open = new Thread(() -> { try { r.source(extractor, delayed); } catch (Throwable t) { failure.set(t); } });
        open.start();
        check(delayed.entered.await(10, TimeUnit.SECONDS), "callback enters active source operation");
        r.release(extractor); r.release(extractor);
        check(delayed.closes == 0, "release retains active receiver and callback source");
        delayed.resume.countDown(); open.join(10000);
        check(!open.isAlive() && failure.get() == null, "active setup completes after release");
        check(delayed.closes == 1, "last active operation closes source exactly once");
    }
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        switch (args[1]) {
            case "adapter": adapter(); break;
            case "retriever": retriever(); break;
            case "extractor": extractor(); break;
            case "release": releaseRace(false); releaseRace(true); break;
            default: throw new AssertionError(args[1]);
        }
        System.out.println("Media callback " + args[1] + ": " + checks + " checks passed");
    }
}
