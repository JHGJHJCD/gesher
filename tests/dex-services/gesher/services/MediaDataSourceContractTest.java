/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.app.Application;
import android.content.Context;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Actual ART/public APIs, AOSP container readers and callback ownership. */
public final class MediaDataSourceContractTest {
    private static int checks;
    private interface Operation { void run() throws Exception; }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        ++checks;
    }
    private static void expect(Class<? extends Throwable> type, Operation operation, String name)
            throws Exception {
        try { operation.run(); }
        catch (Throwable e) {
            if (type.isInstance(e)) { ++checks; return; }
            throw new AssertionError(name + ": " + e, e);
        }
        throw new AssertionError(name + ": expected " + type.getSimpleName());
    }
    private static void bounded(Operation operation, String name) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { operation.run(); } catch (Throwable t) { failure.set(t); }
        }, name);
        worker.setDaemon(true);
        worker.start(); worker.join(10000);
        check(!worker.isAlive(), name + " terminates");
        if (failure.get() != null) throw new AssertionError(name, failure.get());
    }
    private static class Bytes extends MediaDataSource {
        final byte[] bytes;
        volatile int closes, reads, sizes;
        int limit = Integer.MAX_VALUE;
        Runnable closeAction, readAction;
        boolean throwOnRead, throwOnClose;
        Bytes(byte[] bytes) { this.bytes = bytes; }
        public int readAt(long position, byte[] target, int offset, int length) throws IOException {
            ++reads;
            Runnable action = readAction; readAction = null;
            if (action != null) action.run();
            if (throwOnRead) throw new IOException("controlled read error");
            if (length == 0) return 0;
            if (position < 0 || position >= bytes.length) return -1;
            int count = Math.min(Math.min(length, limit), bytes.length - (int) position);
            System.arraycopy(bytes, (int) position, target, offset, count);
            return count;
        }
        public long getSize() { ++sizes; return bytes.length; }
        public void close() throws IOException {
            ++closes;
            if (closeAction != null) closeAction.run();
            if (throwOnClose) throw new IOException("controlled close error");
        }
    }
    private static byte[] pcm(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; ++i) bytes[i] = (byte)(i * 17 + 3);
        return bytes;
    }
    private static byte[] wav(byte[] pcm) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R','I','F','F'}).putInt(36 + pcm.length);
        b.put(new byte[] {'W','A','V','E','f','m','t',' '}).putInt(16);
        b.putShort((short)1).putShort((short)1).putInt(8000).putInt(16000);
        b.putShort((short)2).putShort((short)16);
        b.put(new byte[] {'d','a','t','a'}).putInt(pcm.length).put(pcm);
        return b.array();
    }
    private static byte[] readAll(MediaExtractor e) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer block = ByteBuffer.allocateDirect(65536);
        for (;;) {
            block.clear();
            int n = e.readSampleData(block, 0);
            if (n < 0) break;
            byte[] part = new byte[n]; block.get(part); bytes.write(part);
            if (!e.advance()) break;
        }
        return bytes.toByteArray();
    }
    private static void extractor() throws Exception {
        byte[] expected = pcm(16000);
        Bytes source = new Bytes(wav(expected)); source.limit = 7;
        MediaExtractor e = new MediaExtractor();
        source.closeAction = e::release;
        try {
            expect(IllegalArgumentException.class, () -> e.setDataSource((MediaDataSource)null), "extractor null source");
            e.setDataSource(source);
            check(e.getTrackCount() == 1, "callback WAV has one track");
            check(source.reads > 0 && source.sizes <= 1, "extractor uses cached callback size");
            e.selectTrack(0);
            Bytes rejected = new Bytes(wav(pcm(32000)));
            expect(IOException.class, () -> e.setDataSource(rejected), "extractor refuses second callback source");
            check(rejected.closes == 1 && source.closes == 0, "only rejected source retires");
            check(Arrays.equals(readAll(e), expected), "original track survives rejected replacement");
        } finally { e.release(); e.release(); }
        check(source.closes == 1, "extractor closes once with reentrant release");
        expect(IllegalStateException.class, () -> e.setDataSource(new Bytes(wav(expected))), "released extractor source setter");
        MediaExtractor retry = new MediaExtractor();
        Bytes failed = new Bytes(new byte[1]); failed.throwOnRead = true; failed.throwOnClose = true;
        expect(IOException.class, () -> retry.setDataSource(failed), "failed callback reports extractor IOException");
        check(failed.closes == 1, "failed extractor source closes before error translation");
        Bytes good = new Bytes(wav(expected)); retry.setDataSource(good);
        check(retry.getTrackCount() == 1, "failed setup permits retry");
        retry.release(); check(good.closes == 1, "retried source retires");
        MediaExtractor retiring = new MediaExtractor();
        Bytes reentrant = new Bytes(wav(expected)); reentrant.readAction = retiring::release;
        retiring.setDataSource(reentrant);
        check(reentrant.closes == 1, "extractor released by read callback retires after admitted setter");
        expect(IllegalStateException.class, retiring::getTrackCount, "read callback release remains visible");
    }
    private static void extractorReleaseDuringRead() throws Exception {
        MediaExtractor e = new MediaExtractor();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        Bytes source = new Bytes(wav(pcm(16000)));
        source.readAction = () -> {
            entered.countDown();
            try { if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("read latch"); }
            catch (InterruptedException ex) { throw new AssertionError(ex); }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread setter = new Thread(() -> {
            try { e.setDataSource(source); } catch (Throwable t) { failure.set(t); }
        }, "extractor-source-setter");
        setter.setDaemon(true); setter.start();
        try {
            check(entered.await(5, TimeUnit.SECONDS), "extractor callback is active");
            e.release();
            check(source.closes == 0, "release retains admitted callback source");
        } finally { finish.countDown(); }
        setter.join(10000);
        check(!setter.isAlive(), "admitted setter finishes after release");
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(source.closes == 1, "last admitted extractor owner closes once");
    }
    private static final byte[] PICTURE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==");
    private static byte[] mp3WithPicture() throws IOException {
        ByteArrayOutputStream apic = new ByteArrayOutputStream();
        apic.write(0); apic.write("image/png".getBytes(StandardCharsets.US_ASCII));
        apic.write(0); apic.write(3); apic.write(0); apic.write(PICTURE);
        byte[] payload = apic.toByteArray();
        ByteBuffer frame = ByteBuffer.allocate(10 + payload.length).order(ByteOrder.BIG_ENDIAN);
        frame.put(new byte[] {'A','P','I','C'}).putInt(payload.length).putShort((short)0).put(payload);
        int size = frame.capacity();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(new byte[] {'I','D','3',3,0,0,(byte)(size >> 21 & 127),
                (byte)(size >> 14 & 127),(byte)(size >> 7 & 127),(byte)(size & 127)});
        file.write(frame.array());
        // Only container/ID3 metadata is read; no audio decoding claim.
        byte[] audio = new byte[417];
        audio[0] = (byte)0xff; audio[1] = (byte)0xfb; audio[2] = (byte)0x90;
        for (int i = 0; i < 6; ++i) file.write(audio);
        return file.toByteArray();
    }
    private static void releaseRetriever(MediaMetadataRetriever r) {
        try { r.release(); } catch (IOException e) { throw new AssertionError(e); }
    }
    private static void retriever() throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        Bytes withArt = new Bytes(mp3WithPicture());
        Bytes first = new Bytes(wav(pcm(16000))), second = new Bytes(wav(pcm(32000)));
        try {
            expect(IllegalArgumentException.class, () -> r.setDataSource((MediaDataSource)null), "retriever null source");
            expect(IllegalArgumentException.class, () -> r.setDataSource((FileDescriptor)null, 0, 1), "retriever null FD");
            expect(IllegalArgumentException.class, () -> r.setDataSource(new FileDescriptor(), -1, 1), "retriever negative FD offset");
            expect(IllegalArgumentException.class, () -> r.setDataSource(new FileDescriptor(), 0, -1), "retriever negative FD length");
            expect(IllegalArgumentException.class, () -> r.setDataSource(new FileDescriptor(), 0, 1), "retriever invalid FD");
            r.setDataSource(withArt);
            check(Arrays.equals(r.getEmbeddedPicture(), PICTURE), "retriever callback reads embedded ID3 picture");
            r.setDataSource(first);
            check(withArt.closes == 1, "replacement retires previous callback source");
            check(r.getEmbeddedPicture() == null, "source without art cannot reuse previous embedded picture");
            check("1000".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)), "retriever callback WAV duration");
            check("8000".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)), "retriever sample rate");
            Bytes bad = new Bytes(new byte[4]); bad.throwOnClose = true;
            expect(RuntimeException.class, () -> r.setDataSource(bad), "retriever invalid callback source");
            check(bad.closes == 1 && first.closes == 0, "failed retriever replacement only closes candidate");
            check("1000".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)), "failed replacement preserves published metadata");
            r.setDataSource(second);
            check(first.closes == 1 && second.closes == 0, "successful replacement transfers ownership");
            check("2000".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)), "replacement publishes fresh metadata");
            second.closeAction = () -> releaseRetriever(r);
        } finally { r.release(); r.release(); }
        check(second.closes == 1, "retriever close reenters release exactly once");
        expect(IllegalStateException.class, () -> r.setDataSource(new Bytes(wav(pcm(4)))), "released retriever source setter");
        MediaMetadataRetriever retiring = new MediaMetadataRetriever();
        Bytes source = new Bytes(wav(pcm(16000))); source.readAction = () -> releaseRetriever(retiring);
        retiring.setDataSource(source);
        check(source.closes == 1, "retriever read callback can retire receiver");
        expect(IllegalStateException.class,
                () -> retiring.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                "retriever callback release remains visible");
        MediaMetadataRetriever closeFailure = new MediaMetadataRetriever();
        Bytes throwing = new Bytes(wav(pcm(16000))); throwing.throwOnClose = true;
        closeFailure.setDataSource(throwing);
        expect(IOException.class, closeFailure::release, "retriever release propagates callback close IOException");
        closeFailure.release();
        check(throwing.closes == 1, "exceptional retriever release closes once and retires receiver");
    }
    private static void player() throws Exception {
        bounded(() -> {
            MediaPlayer p = new MediaPlayer();
            Bytes first = new Bytes(wav(pcm(16000)));
            first.closeAction = () -> check(!p.isLooping(), "close can query player after reset");
            try {
                p.setDataSource(first);
                Bytes rejected = new Bytes(wav(pcm(16000)));
                expect(IllegalStateException.class, () -> p.setDataSource(rejected), "player second callback source");
                check(rejected.closes == 1 && first.closes == 0, "player closes rejected source");
                p.reset(); check(first.closes == 1, "player reset closes once outside state lock");
                Bytes second = new Bytes(wav(pcm(16000))); second.closeAction = p::release;
                p.setDataSource(second); p.release(); p.release();
                check(second.closes == 1, "player release callback may reenter release");
            } finally { p.release(); }
        }, "MediaPlayer callback reset/release");
    }
    private static void bootstrap() throws Exception {
        // Keep media calls SDK-only. The standalone DEX needs the real app
        // context/services that FileUtils' FD wrapper and MediaPlayer consult.
        String root = System.getenv("ANDROID_ROOT");
        Class.forName("com.gesher.app.Main")
                .getMethod("bootstrap", String.class, String.class, String.class)
                .invoke(null, "media-data-source-contract", root + "/build.prop",
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
        // application binding does. Do not bypass FileUtils' provider lookup.
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
        check(threadType.getMethod("currentApplication").invoke(null) == application
                        && application.getApplicationContext() == application
                        && application.getPackageManager() != null,
                "real initial Application supplies the FD conversion context");
    }
    public static void main(String[] args) throws Exception {
        bootstrap();
        extractor(); extractorReleaseDuringRead(); retriever(); player();
        System.out.println("MediaDataSourceContractTest all passed: " + checks + " checks; native=true");
        // In-process services own workers; this isolated fixture has no event loop.
        System.exit(0);
    }
}
