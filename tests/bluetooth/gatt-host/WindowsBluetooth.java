/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package com.gesher.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Declared radio boundary: controls blocking connects and ordered native events. */
public final class WindowsBluetooth {
    public static final int EVENT_NONE=0, EVENT_NOTIFICATION=1, EVENT_CONNECTION=2,
            EVENT_MTU=3, EVENT_SERVICES_CHANGED=4, EVENT_CLOSED=5;
    public static final int ATTRIBUTE_SERVICE=0, ATTRIBUTE_CHARACTERISTIC=1,
            ATTRIBUTE_DESCRIPTOR=2, ATTRIBUTE_INCLUDED=3;
    public static final String SERVICE="0000180f-0000-1000-8000-00805f9b34fb";
    public static final String CHARACTERISTIC="00002a19-0000-1000-8000-00805f9b34fb";
    public static volatile boolean blockConnect;
    public static volatile long lastHandle;
    public static final CountDownLatch connectEntered=new CountDownLatch(1);
    public static final CountDownLatch connectExited=new CountDownLatch(1);
    public static final CountDownLatch closed=new CountDownLatch(1);
    private static final AtomicLong next=new AtomicLong();
    private static final Map<Long,Session> sessions=new ConcurrentHashMap<>();
    private static final class Session {
        volatile boolean closed;
        final CountDownLatch connect=new CountDownLatch(1);
        final LinkedBlockingQueue<int[]> events=new LinkedBlockingQueue<>();
    }
    public static String hresult(int hr){return Integer.toHexString(hr);}
    public static long nativeGattOpen(long address,int type,int[] hr){
        long id=next.incrementAndGet();sessions.put(id,new Session());lastHandle=id;return id;
    }
    public static int nativeGattConnect(long id,int timeout,int[] mtu){
        Session session=sessions.get(id);
        connectEntered.countDown();
        try {
            if(blockConnect && !session.connect.await(5,TimeUnit.SECONDS))
                throw new AssertionError("connect was not cancelled by native close");
            mtu[0]=247;
            return session.closed ? -1 : 0;
        } catch(InterruptedException e){return -1;}
        finally{connectExited.countDown();}
    }
    public static void nativeGattClose(long id){
        Session session=sessions.remove(id);
        if(session!=null){session.closed=true;session.connect.countDown();
            session.events.add(new int[]{EVENT_CLOSED,0,0});}
        closed.countDown();
    }
    public static String[] nativeGattDiscover(long id,int[] status){
        return new String[]{"0\t"+SERVICE+"\t1\t0","1\t"+CHARACTERISTIC+"\t2\t18"};
    }
    public static byte[] nativeGattRead(long id,int kind,int handle,int[] status){return new byte[]{87};}
    public static int nativeGattWrite(long id,int kind,int handle,byte[] value,boolean response){return 0;}
    public static int nativeGattNotify(long id,int handle,int mode){return 0;}
    public static int nativeGattMtu(long id){return 247;}
    public static int nativeGattPhy(long id,int[] phy){phy[0]=phy[1]=1;return 0;}
    public static byte[] nativeGattNext(long id,int timeout,int[] meta){
        Session session=sessions.get(id);
        if(session==null){meta[0]=EVENT_CLOSED;return null;}
        try{
            int[] event=session.events.poll(timeout,TimeUnit.MILLISECONDS);
            if(event==null){meta[0]=EVENT_NONE;return null;}
            System.arraycopy(event,0,meta,0,3);
            return event[0]==EVENT_NOTIFICATION ? new byte[]{86} : null;
        }catch(InterruptedException e){meta[0]=EVENT_CLOSED;return null;}
    }
    public static void notifyAndBarrier(){
        Session session=sessions.get(lastHandle);
        session.events.add(new int[]{EVENT_NOTIFICATION,2,0});
        session.events.add(new int[]{EVENT_SERVICES_CHANGED,0,0});
    }
    public static void mtuAndBarrier(int mtu){
        Session session=sessions.get(lastHandle);
        session.events.add(new int[]{EVENT_MTU,0,mtu});
        session.events.add(new int[]{EVENT_SERVICES_CHANGED,0,0});
    }
}
