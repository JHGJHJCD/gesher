/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package android.bluetooth;

import com.gesher.server.WindowsBluetooth;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class BluetoothSocketHostTest {
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
    private static void parallelConnect()throws Exception{
        BluetoothSocket socket=new BluetoothSocket(new BluetoothDevice("00:11:22:33:44:55"),UUID.randomUUID(),1,false);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread first=new Thread(()->{
            try{socket.connect();failure.set(new AssertionError("cancelled connect succeeded"));}
            catch(IOException expected){}catch(Throwable e){failure.set(e);}
        });
        first.start();
        try{
            check(WindowsBluetooth.connecting.await(5,TimeUnit.SECONDS),"first connect entered");
            boolean rejected=false;
            try{socket.connect();}catch(IOException expected){rejected=true;}
            check(rejected,"simultaneous connect is rejected before overwriting the first handle");
            check(WindowsBluetooth.opened.get()==1,"only one native socket opened");
        }finally{socket.close();first.join(5000);}
        check(!first.isAlive() && failure.get()==null,"first connect cancelled cleanly: "+failure.get());
    }
    private static void closedAccept()throws Exception{
        BluetoothServerSocket server=BluetoothServerSocket.listen(null,"fixture",UUID.randomUUID(),false);
        WindowsBluetooth.acceptHook=()->{try{server.close();}catch(IOException e){throw new AssertionError(e);}};
        boolean rejected=false;
        try(BluetoothSocket unexpected=server.accept(5000)){}
        catch(IOException expected){rejected=true;}
        check(rejected,"accept must not publish a socket after listener close");
        check(WindowsBluetooth.acceptedClosed==1,"unpublished accepted socket closed exactly once");
    }
    public static void main(String[] args)throws Exception{
        if(args[0].equals("parallel-connect"))parallelConnect();else closedAccept();
        System.out.println("BluetoothSocketHostTest passed: "+args[0]);
    }
}
