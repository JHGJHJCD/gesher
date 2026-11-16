// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
import android.net.http.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class HttpEngineHostTest {
    static int checks;
    static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    static class Result implements UrlRequest.Callback {
        final CountDownLatch done=new CountDownLatch(1);
        final ByteArrayOutputStream body=new ByteArrayOutputStream();
        final AtomicInteger terminals=new AtomicInteger();
        volatile String outcome;
        volatile HttpException error;
        volatile UrlResponseInfo info;
        int redirects;
        @Override public void onRedirectReceived(UrlRequest r, UrlResponseInfo i, String url) {
            redirects++; r.followRedirect();
        }
        @Override public void onResponseStarted(UrlRequest r, UrlResponseInfo i) {
            info=i; r.read(ByteBuffer.allocateDirect(7));
        }
        @Override public void onReadCompleted(UrlRequest r, UrlResponseInfo i, ByteBuffer b) {
            b.flip(); while (b.hasRemaining()) body.write(b.get()); b.clear(); r.read(b);
        }
        void terminal(String value) { outcome=value; terminals.incrementAndGet(); done.countDown(); }
        @Override public void onSucceeded(UrlRequest r, UrlResponseInfo i) { info=i; terminal("success"); }
        @Override public void onFailed(UrlRequest r, UrlResponseInfo i, HttpException e) { error=e; terminal("failed"); }
        @Override public void onCanceled(UrlRequest r, UrlResponseInfo i) { terminal("canceled"); }
        Result await() throws Exception {
            check(done.await(12,TimeUnit.SECONDS),"Request timed out");
            check(terminals.get()==1,"Exactly one terminal callback"); return this;
        }
        String text() { return new String(body.toByteArray(),StandardCharsets.UTF_8); }
    }
    static class Upload extends UploadDataProvider {
        final byte[] bytes="upload payload".getBytes(StandardCharsets.UTF_8);
        final boolean chunked;
        int offset;
        final AtomicInteger rewinds=new AtomicInteger(), closes=new AtomicInteger();
        Upload(boolean chunked) { this.chunked=chunked; }
        @Override public long getLength() { return chunked ? -1 : bytes.length; }
        @Override public void read(UploadDataSink sink, ByteBuffer b) {
            int n=Math.min(3,Math.min(b.remaining(),bytes.length-offset)); b.put(bytes,offset,n); offset+=n;
            sink.onReadSucceeded(chunked && offset==bytes.length);
        }
        @Override public void rewind(UploadDataSink sink) { offset=0; rewinds.incrementAndGet(); sink.onRewindSucceeded(); }
        @Override public void close() { closes.incrementAndGet(); }
    }
    static class AsyncUpload extends UploadDataProvider {
        final boolean deferRewind;
        final CountDownLatch pending=new CountDownLatch(1), closed=new CountDownLatch(1);
        final AtomicInteger closes=new AtomicInteger();
        volatile UploadDataSink sink;
        volatile ByteBuffer buffer;
        AsyncUpload(boolean deferRewind) { this.deferRewind=deferRewind; }
        @Override public long getLength() { return 1; }
        @Override public void read(UploadDataSink value, ByteBuffer target) {
            if (deferRewind) { target.put((byte)'x'); value.onReadSucceeded(false); }
            else { sink=value; buffer=target; pending.countDown(); }
        }
        @Override public void rewind(UploadDataSink value) { sink=value; pending.countDown(); }
        void complete() {
            if (deferRewind) sink.onRewindSucceeded();
            else { buffer.put((byte)'x'); sink.onReadSucceeded(false); }
        }
        @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
    }
    static Result get(HttpEngine e, String url, Executor ex) throws Exception {
        Result r=new Result(); e.newUrlRequestBuilder(url,ex,r).build().start(); return r.await();
    }
    public static void main(String[] args) throws Exception {
        ExecutorService callbacks=Executors.newFixedThreadPool(4), uploads=Executors.newSingleThreadExecutor();
        ExecutorService serverThreads=Executors.newCachedThreadPool();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(serverThreads);
        CountDownLatch delayed=new CountDownLatch(1), release=new CountDownLatch(1);
        server.createContext("/", exchange -> {
            String path=exchange.getRequestURI().getPath();
            if (path.equals("/redirect") || path.equals("/redirect307") || path.equals("/redirect303")) {
                exchange.getResponseHeaders().add("Location","/echo");
                exchange.sendResponseHeaders(path.equals("/redirect307") ? 307 : path.equals("/redirect303") ? 303 : 302,-1);
                exchange.close(); return;
            }
            if (path.equals("/loop")) {
                exchange.getResponseHeaders().add("Location","/loop"); exchange.sendResponseHeaders(302,-1); exchange.close(); return;
            }
            if (path.equals("/delay")) {
                delayed.countDown(); try { release.await(10,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] body=(path.equals("/echo") ? exchange.getRequestMethod()+":"+
                    new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8) : "hello from HttpEngine").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Test","one"); exchange.getResponseHeaders().add("X-Test","two");
            exchange.sendResponseHeaders(path.equals("/missing")?404:200,body.length);
            try { exchange.getResponseBody().write(body); } finally { exchange.close(); }
        });
        server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        HttpEngine engine=new HttpEngine.Builder(new android.content.Context()).setUserAgent("GesherTest/1").build();
        try {
            if (args.length>0 && args[0].equals("global-factory")) {
                URL.setURLStreamHandlerFactory(engine.createUrlStreamHandlerFactory());
                try (InputStream in=new URL(base+"/").openStream()) {
                    check(new String(in.readAllBytes(),StandardCharsets.UTF_8).equals("hello from HttpEngine"),"Globally installed URL factory");
                }
                engine.shutdown();
                System.out.println("HttpEngineHostTest global factory passed");
                return;
            }
            java.lang.reflect.Method authority=Class.forName("android.net.http.Http2Transport").getDeclaredMethod("authority",URL.class,boolean.class);
            authority.setAccessible(true);
            check(authority.invoke(null,new URL("https://[::1]/"),true).equals("[::1]:443"),"IPv6 CONNECT authority");
            check(authority.invoke(null,new URL("https://[::1]/"),false).equals("[::1]"),"IPv6 HTTP/2 authority");
            check(authority.invoke(null,new URL("https://[::1]:9443/"),false).equals("[::1]:9443"),"IPv6 explicit port");
            check(authority.invoke(null,new URL("https://example.com/"),true).equals("example.com:443"),"DNS CONNECT authority");
            Result first=get(engine,base+"/",callbacks);
            check("success".equals(first.outcome) && first.text().equals("hello from HttpEngine"),"GET body via repeated direct reads");
            check(first.info.getReceivedByteCount()==first.body.size(),"Received body count");
            check(first.info.getHeaders().getAsMap().get("x-test").size()==2,"Case insensitive duplicate headers");
            try { first.info.getUrlChain().add("bad"); throw new AssertionError("Mutable response"); }
            catch (UnsupportedOperationException expected) { checks++; }
            check(get(engine,base+"/missing",callbacks).info.getHttpStatusCode()==404,"HTTP error is a successful transport response");
            Result redirected=get(engine,base+"/redirect",callbacks);
            check(redirected.redirects==1 && redirected.info.getUrlChain().size()==2,"Explicit redirect and URL chain");
            for (boolean chunked:new boolean[]{false,true}) {
                Upload provider=new Upload(chunked); Result r=new Result();
                UrlRequest.Builder b=engine.newUrlRequestBuilder(base+"/redirect307",callbacks,r)
                        .addHeader("Content-Type","text/plain").setUploadDataProvider(provider,uploads);
                UrlRequest request=b.build();
                b.addHeader("X-Later","not in snapshot");
                check(request.getHeaders().getAsList().size()==1,"Builder snapshot");
                request.start(); r.await();
                check("success".equals(r.outcome) && r.text().equals("POST:upload payload"),"Fixed/chunked upload through 307");
                check(provider.rewinds.get()==1,"Upload rewound before redirect");
            }
            Upload provider=new Upload(false); Result post=new Result();
            engine.newUrlRequestBuilder(base+"/redirect303",callbacks,post).addHeader("Content-Type","text/plain")
                    .setUploadDataProvider(provider,uploads).build().start(); post.await();
            check(post.text().equals("GET:"),"303 switches POST to GET");
            Result broken=new Result() {
                @Override public void onResponseStarted(UrlRequest r, UrlResponseInfo i) { throw new IllegalStateException("callback test"); }
            };
            engine.newUrlRequestBuilder(base+"/",callbacks,broken).build().start(); broken.await();
            check(broken.error instanceof CallbackException,"Callback exception is delivered as failure");
            Result direct=new Result();
            engine.newUrlRequestBuilder(base+"/",Runnable::run,direct).setDirectExecutorAllowed(true).build().start(); direct.await();
            check("success".equals(direct.outcome),"Explicitly allowed direct executor");
            Result prohibited=new Result();
            engine.newUrlRequestBuilder(base+"/",Runnable::run,prohibited).build().start(); prohibited.await();
            check(prohibited.error instanceof CallbackException && prohibited.error.getCause() instanceof InlineExecutionProhibitedException,"Direct executor policy");
            Result loop=get(engine,base+"/loop",callbacks);
            check("failed".equals(loop.outcome),"Redirect loop is bounded");
            Result canceled=new Result(); UrlRequest pending=engine.newUrlRequestBuilder(base+"/delay",callbacks,canceled).build();
            pending.start(); check(delayed.await(5,TimeUnit.SECONDS),"Pending connection reached server");
            try { engine.shutdown(); throw new AssertionError("Shutdown with active request"); }
            catch (IllegalStateException expected) { checks++; }
            pending.cancel(); canceled.await(); release.countDown();
            check("canceled".equals(canceled.outcome) && pending.isDone(),"Cancel pending response");
            check(get(engine,base+"/",callbacks).outcome.equals("success"),"Engine usable after cancellation");
            for (boolean deferRewind:new boolean[]{false,true}) {
                AsyncUpload async=new AsyncUpload(deferRewind); Result result=new Result();
                UrlRequest request=engine.newUrlRequestBuilder(base+(deferRewind?"/redirect307":"/echo"),callbacks,result)
                        .addHeader("Content-Type","text/plain").setUploadDataProvider(async,uploads).build();
                request.start();
                check(async.pending.await(5,TimeUnit.SECONDS),"Asynchronous upload operation started");
                request.cancel(); result.await();
                boolean closedBeforeCompletion=async.closed.await(150,TimeUnit.MILLISECONDS);
                async.complete();
                check(!closedBeforeCompletion,"Canceled upload stays open until asynchronous sink completion");
                check(async.closed.await(5,TimeUnit.SECONDS) && async.closes.get()==1,"Upload closes once after sink completion");
            }
            try { engine.newUrlRequestBuilder("file:///tmp/x",callbacks,new Result()); throw new AssertionError("Non HTTP URL"); }
            catch (IllegalArgumentException expected) { checks++; }
            try { engine.newUrlRequestBuilder(base,callbacks,new Result()).addHeader("bad","x\r\ny"); throw new AssertionError("Header injection"); }
            catch (IllegalArgumentException expected) { checks++; }
            try { new HttpEngine.Builder(new android.content.Context()).addPublicKeyPins("localhost",Set.of(new byte[32]),false,java.time.Instant.now()); throw new AssertionError("Ignored pins"); }
            catch (UnsupportedOperationException expected) { checks++; }
            URLConnection opened=engine.openConnection(new URL(base));
            check(opened.getRequestProperty("User-Agent").equals("GesherTest/1"),"openConnection user agent");
            URL viaFactory=new URL(null,base,engine.createUrlStreamHandlerFactory().createURLStreamHandler("http"));
            try (InputStream in=viaFactory.openStream()) { check(in.readAllBytes().length>0,"URLStreamHandlerFactory does not recurse"); }
            engine.shutdown();
            try { engine.newUrlRequestBuilder(base,callbacks,new Result()); throw new AssertionError("Use after shutdown"); }
            catch (IllegalStateException expected) { checks++; }
            System.out.println("HttpEngineHostTest passed: "+checks+" checks");
        } finally {
            release.countDown(); server.stop(0); callbacks.shutdownNow(); uploads.shutdownNow(); serverThreads.shutdownNow();
        }
    }
}
