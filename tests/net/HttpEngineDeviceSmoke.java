// Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0
import android.net.http.*;
import java.net.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** Run on ART with all five real boot image spaces, without any user account. */
public class HttpEngineDeviceSmoke {
    public static void main(String[] args) throws Exception {
        ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
        Thread responder=new Thread(() -> {
            try (Socket socket=server.accept()) {
                BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream()));
                for (String line;(line=in.readLine())!=null && !line.isEmpty();) {}
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nPASS".getBytes("US-ASCII"));
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        responder.setDaemon(true); responder.start();
        HttpEngine engine=new HttpEngine.Builder(new android.content.ContextWrapper(null)).build();
        ExecutorService callbacks=Executors.newSingleThreadExecutor();
        CountDownLatch done=new CountDownLatch(1);
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        Throwable[] failure=new Throwable[1];
        engine.newUrlRequestBuilder("http://127.0.0.1:"+server.getLocalPort()+"/",callbacks,new UrlRequest.Callback() {
            public void onRedirectReceived(UrlRequest r,UrlResponseInfo i,String u) { r.followRedirect(); }
            public void onResponseStarted(UrlRequest r,UrlResponseInfo i) { r.read(ByteBuffer.allocateDirect(16)); }
            public void onReadCompleted(UrlRequest r,UrlResponseInfo i,ByteBuffer b) { b.flip(); while(b.hasRemaining()) body.write(b.get()); b.clear(); r.read(b); }
            public void onSucceeded(UrlRequest r,UrlResponseInfo i) { done.countDown(); }
            public void onFailed(UrlRequest r,UrlResponseInfo i,HttpException e) { failure[0]=e; done.countDown(); }
            public void onCanceled(UrlRequest r,UrlResponseInfo i) { failure[0]=new Exception("Canceled"); done.countDown(); }
        }).build().start();
        if (!done.await(15,TimeUnit.SECONDS)) throw new AssertionError("HttpEngine timed out");
        if (failure[0]!=null) throw new AssertionError("HttpEngine failed",failure[0]);
        if (!body.toString("US-ASCII").equals("PASS")) throw new AssertionError("Wrong body");
        engine.shutdown(); callbacks.shutdown(); server.close();
        System.out.println("HttpEngineDeviceSmoke passed: real ART, URLConnection and direct buffers");
    }
}
