/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Actual callback WAV sources through the public framework/ART/Windows JNI. */
public final class MediaCallbackContractTest {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private interface Operation { void run() throws Exception; }
    private static void requires(Class<? extends Throwable> type, Operation operation, String label) throws Exception {
        try { operation.run(); }
        catch (Throwable error) { if (type.isInstance(error)) { checks++; return; } throw error; }
        throw new AssertionError(label);
    }
    private static byte[] pcm() {
        byte[] result = new byte[16000];
        for (int i = 0; i < result.length; i++) result[i] = (byte) (i * 17 + 3);
        return result;
    }
    private static byte[] wav() {
        byte[] pcm = pcm();
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R', 'I', 'F', 'F'}).putInt(36 + pcm.length);
        b.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
        b.putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000);
        b.putShort((short) 2).putShort((short) 16);
        b.put(new byte[] {'d', 'a', 't', 'a'}).putInt(pcm.length).put(pcm);
        return b.array();
    }
    private interface ReadAction { void run() throws IOException; }
    private static final class Source extends MediaDataSource {
        final byte[] bytes;
        volatile int reads, closes;
        boolean unknown, failRead, failClose, oversize;
        CountDownLatch entered, resume;
        ReadAction onRead, onClose;
        Source(byte[] bytes) { this.bytes = bytes; }
        @Override public int readAt(long position, byte[] data, int offset, int size) throws IOException {
            reads++;
            ReadAction callback = onRead; onRead = null;
            if (callback != null) callback.run();
            if (entered != null) {
                entered.countDown();
                try { if (!resume.await(15, TimeUnit.SECONDS)) throw new IOException("callback timeout"); }
                catch (InterruptedException ex) { throw new IOException(ex); }
            }
            if (failRead) throw new IOException("fixture callback failure");
            if (oversize) return size + 1;
            if (size == 0) return 0;
            if (position >= bytes.length) return -1;
            int count = Math.min(Math.min(size, 137), bytes.length - (int) position);
            System.arraycopy(bytes, (int) position, data, offset, count);
            return count;
        }
        @Override public long getSize() { return unknown ? -1 : bytes.length; }
        @Override public void close() throws IOException {
            closes++;
            if (onClose != null) onClose.run();
            if (failClose) throw new IOException("fixture close failure");
        }
    }
    private static byte[] samples(MediaExtractor extractor) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocateDirect(65536);
        for (;;) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            byte[] data = new byte[size]; buffer.get(data); output.write(data);
            if (!extractor.advance()) break;
        }
        return output.toByteArray();
    }
    private static void extractor(boolean unknown) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        Source good = new Source(wav()); good.unknown = unknown;
        Source bad = new Source(new byte[128]); bad.failRead = true;
        Source second = new Source(wav());
        try {
            requires(IOException.class, () -> extractor.setDataSource(bad), "callback IOException must fail setup");
            check(bad.closes == 1, "failed source closes once before retry");
            extractor.setDataSource(good);
            check(extractor.getTrackCount() == 1, "callback WAV track count");
            check(extractor.getTrackFormat(0).getInteger("sample-rate") == 8000, "callback WAV sample rate");
            extractor.selectTrack(0);
            requires(IOException.class, () -> extractor.setDataSource(second), "second extractor source rejected");
            check(second.closes == 1 && good.closes == 0, "rejected source closes; original remains owned");
            check(Arrays.equals(pcm(), samples(extractor)), "all callback WAV samples preserve PCM bytes");
            check(extractor.readSampleData(ByteBuffer.allocateDirect(4), 0) == -1, "callback extractor EOF");
        } finally { extractor.release(); extractor.release(); }
        check(good.closes == 1, "extractor releases callback once");
        requires(IllegalStateException.class, extractor::getTrackCount, "released extractor rejects access");
    }
    private static void retriever() throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Source first = new Source(wav()), second = new Source(wav()), bad = new Source(new byte[128]);
        bad.failClose = true;
        try {
            retriever.setDataSource(first);
            check("1000".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)), "callback duration");
            check("8000".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)), "callback sample rate");
            check(retriever.getEmbeddedPicture() == null, "WAV has no embedded art");
            second.unknown = true; retriever.setDataSource(second);
            check(first.closes == 1, "replacement releases original callback");
            requires(RuntimeException.class, () -> retriever.setDataSource(bad), "invalid callback container rejected");
            check(second.closes == 0 && bad.closes == 1, "failed replacement retains accepted source");
            check("1000".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)),
                    "failed replacement retains old metadata");
            check(retriever.getEmbeddedPicture() == null, "failed replacement retains coherent artwork");
            requires(IllegalArgumentException.class,
                    () -> retriever.setDataSource(new java.io.FileDescriptor(), -1, 1),
                    "negative descriptor offset rejected before opening");
            requires(IllegalArgumentException.class,
                    () -> retriever.setDataSource(new java.io.FileDescriptor(), 0, -1),
                    "negative descriptor length rejected before opening");
            requires(IllegalArgumentException.class,
                    () -> retriever.setDataSource(new java.io.FileDescriptor(), 0, 1),
                    "invalid descriptor rejected before opening");
        } finally { retriever.release(); retriever.release(); }
        check(second.closes == 1, "retained source closes on receiver release");
        requires(IllegalStateException.class, () -> retriever.extractMetadata(9), "released retriever rejects access");
        MediaMetadataRetriever reentrant = new MediaMetadataRetriever();
        Source source = new Source(wav()); source.onRead = reentrant::release;
        reentrant.setDataSource(source);
        check(source.closes == 1, "callback can release its receiver without invalidating active parser");
        reentrant.release();
        MediaMetadataRetriever throwing = new MediaMetadataRetriever();
        Source closing = new Source(wav()); closing.failClose = true;
        throwing.setDataSource(closing);
        requires(IOException.class, throwing::release, "release propagates callback close IOException");
        throwing.release();
        check(closing.closes == 1, "throwing callback close still releases ownership once");
    }
    private static void reentrantOperations() throws Exception {
        for (int which = 0; which < 4; which++) {
            MediaExtractor extractor = new MediaExtractor();
            Source source = new Source(wav()), candidate = new Source(wav());
            extractor.setDataSource(source); extractor.selectTrack(0);
            final int operation = which;
            boolean[] rejected = {false};
            source.onRead = () -> {
                try {
                    if (operation == 0) extractor.selectTrack(0);
                    else if (operation == 1) extractor.unselectTrack(0);
                    else if (operation == 2) extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    else extractor.setDataSource(candidate);
                } catch (IllegalStateException expected) { rejected[0] = true; }
            };
            try {
                byte[] decoded = samples(extractor);
                check(rejected[0], "reentrant extractor mutation rejected: " + which);
                check(Arrays.equals(pcm(), decoded), "reentrant rejection preserves actual WAV samples");
                check(candidate.closes == 0, "reentrant setup never acquires candidate");
            } finally { extractor.release(); }
            check(source.closes == 1, "reentrant operation source releases once");
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Source old = new Source(wav()), next = new Source(wav());
        boolean[] rejected = {false};
        retriever.setDataSource(old);
        old.onClose = () -> {
            try { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); }
            catch (IllegalStateException expected) { rejected[0] = true; }
            retriever.release();
        };
        retriever.setDataSource(next);
        check(rejected[0], "close callback cannot observe a snapshot being replaced");
        check(old.closes == 1 && next.closes == 1, "close callback can release newly published snapshot");
        retriever.release();
    }
    private static void player() throws Exception {
        MediaPlayer player = new MediaPlayer();
        Source source = new Source(wav());
        try {
            player.setDataSource(source);
            player.prepare();
            check(player.getDuration() == 1000 && source.reads > 0, "MediaPlayer prepares real callback WAV");
            player.reset();
            check(source.closes == 1, "MediaPlayer reset releases callback source");
        } finally { player.release(); }
        check(source.closes == 1, "MediaPlayer release does not close reset source again");
    }
    private static void releaseRace(boolean useExtractor) throws Exception {
        MediaExtractor extractor = useExtractor ? new MediaExtractor() : null;
        MediaMetadataRetriever retriever = useExtractor ? null : new MediaMetadataRetriever();
        Source source = new Source(wav());
        source.entered = new CountDownLatch(1); source.resume = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread open = new Thread(() -> {
            try { if (useExtractor) extractor.setDataSource(source); else retriever.setDataSource(source); }
            catch (Throwable error) { failure.set(error); }
        });
        open.start();
        check(source.entered.await(15, TimeUnit.SECONDS), "source callback enters before release");
        try {
            if (useExtractor) { extractor.release(); extractor.release(); }
            else { retriever.release(); retriever.release(); }
            check(source.closes == 0, "active operation retains source after release");
        } finally { source.resume.countDown(); }
        open.join(15000);
        check(!open.isAlive() && failure.get() == null, "delayed setup safely finishes after release");
        check(source.closes == 1, "last receiver lease closes callback source once");
    }
    public static void main(String[] args) throws Exception {
        MediaTestBootstrap.initialize("media-callback-contract");
        extractor(false); extractor(true); retriever(); reentrantOperations(); player(); releaseRace(false); releaseRace(true);
        System.out.println("MediaCallbackContractTest all passed: " + checks + " checks");
        // In-process services own workers; this isolated fixture has no event loop.
        System.exit(0);
    }
}
