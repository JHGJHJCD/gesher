/* Copyright (C) 2026 The Gesher Authors. SPDX-License-Identifier: Apache-2.0 */
package android.bluetooth;

import android.os.Handler;
import com.gesher.server.WindowsBluetooth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Tests real production GATT work/callback ordering through a controlled Handler. */
public final class BluetoothGattHostTest {
    private static final String ADDRESS="C0:FF:EE:00:00:01";
    private final Handler handler=new Handler();
    private final List<String> events=new ArrayList<>();
    private final BluetoothGatt gatt=new BluetoothGatt(new BluetoothDevice(ADDRESS,1),null,
            new BluetoothGattCallback(){
                @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){events.add("state "+state);}
                @Override public void onServicesDiscovered(BluetoothGatt g,int status){events.add("services");}
                @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){events.add("read");}
                @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){events.add("notification");}
                @Override public void onServiceChanged(BluetoothGatt g){events.add("barrier");}
                @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status){events.add("mtu "+mtu+" "+status);}
            },handler,false);
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void connect()throws Exception{
        gatt.connectAsync();handler.await(1);handler.drain();
        check(events.equals(List.of("state 2")),"connected once: "+events);events.clear();
    }
    private BluetoothGattCharacteristic discover()throws Exception{
        check(gatt.discoverServices(),"discovery accepted");handler.await(1);handler.drain();
        check(events.equals(List.of("services")),"discovery completed");events.clear();
        return gatt.getService(UUID.fromString(WindowsBluetooth.SERVICE))
                .getCharacteristic(UUID.fromString(WindowsBluetooth.CHARACTERISTIC));
    }
    private void cancel()throws Exception{
        WindowsBluetooth.blockConnect=true;gatt.connectAsync();
        check(WindowsBluetooth.connectEntered.await(5,TimeUnit.SECONDS),"native connect entered");
        gatt.disconnect();
        check(WindowsBluetooth.closed.await(1,TimeUnit.SECONDS),"disconnect closes the in-flight native handle");
        check(WindowsBluetooth.connectExited.await(1,TimeUnit.SECONDS),"native connect promptly cancelled");
        handler.await(1);handler.drain();
        check(events.equals(List.of("state 0")),"no connected callback after cancellation: "+events);
        check(!BluetoothGatt.isConnected(ADDRESS),"cancelled device not counted connected");
    }
    private void closedCallback()throws Exception{
        gatt.connectAsync();handler.await(1);gatt.close();handler.drain();
        check(events.isEmpty(),"queued connection callback suppressed after close: "+events);
        check(!BluetoothGatt.isConnected(ADDRESS),"closed client not counted connected");
    }
    private void notifications()throws Exception{
        connect();BluetoothGattCharacteristic c=discover();
        WindowsBluetooth.notifyAndBarrier();handler.await(1);handler.drain();
        check(events.equals(List.of("barrier")),"no notification without local registration: "+events);
        events.clear();check(gatt.setCharacteristicNotification(c,true),"local registration enabled");
        WindowsBluetooth.notifyAndBarrier();handler.await(2);handler.drain();
        check(events.equals(List.of("notification","barrier")),"registered notification delivered: "+events);
        events.clear();check(gatt.setCharacteristicNotification(c,false),"local registration disabled");
        WindowsBluetooth.notifyAndBarrier();handler.await(1);handler.drain();
        check(events.equals(List.of("barrier")),"unregistered notification suppressed: "+events);
        events.clear();check(gatt.setCharacteristicNotification(c,true),"registration enabled again");
        WindowsBluetooth.notifyAndBarrier();handler.await(2);
        check(gatt.setCharacteristicNotification(c,false),"disable while callback is queued");handler.drain();
        check(events.equals(List.of("barrier")),"queued notification suppressed after disabling: "+events);
    }
    private void staleRead()throws Exception{
        connect();BluetoothGattCharacteristic c=discover();
        check(gatt.readCharacteristic(c),"read accepted");handler.await(1);
        gatt.disconnect();handler.await(2);handler.drain();
        check(events.equals(List.of("state 0")),"old read callback suppressed after disconnect: "+events);
        check(c.getValue()==null,"old read does not change characteristic after disconnect");
    }
    private void requestedMtu()throws Exception{
        connect();
        check(gatt.requestMtu(64),"MTU request accepted");handler.await(1);handler.drain();
        check(events.equals(List.of("mtu 247 0")),"callback reports negotiated MTU, not requested ceiling: "+events);
        events.clear();
        check(gatt.requestMtu(517),"larger MTU request accepted");handler.await(1);handler.drain();
        check(events.equals(List.of("mtu 247 0")),"callback preserves the smaller actual negotiated MTU: "+events);
        events.clear();
        check(gatt.requestMtu(64),"queued MTU request accepted");handler.await(1);
        gatt.disconnect();handler.await(2);handler.drain();
        check(events.equals(List.of("state 0")),"queued MTU request callback suppressed after disconnect: "+events);
        check(!gatt.requestMtu(517),"MTU request rejected while disconnected");
    }
    private void mtuEvent()throws Exception{
        connect();WindowsBluetooth.mtuAndBarrier(185);handler.await(2);handler.drain();
        check(events.equals(List.of("mtu 185 0","barrier")),"native MTU change delivered in event order: "+events);
        events.clear();WindowsBluetooth.mtuAndBarrier(123);handler.await(2);
        gatt.disconnect();handler.await(3);handler.drain();
        check(events.equals(List.of("state 0")),"queued native MTU event suppressed after disconnect: "+events);
    }
    public static void main(String[] args)throws Exception{
        BluetoothGattHostTest test=new BluetoothGattHostTest();
        try{
            switch(args[0]){
                case "cancel":test.cancel();break;
                case "closed-callback":test.closedCallback();break;
                case "notifications":test.notifications();break;
                case "stale-read":test.staleRead();break;
                case "requested-mtu":test.requestedMtu();break;
                case "mtu-event":test.mtuEvent();break;
                default:throw new AssertionError(args[0]);
            }
        }finally{test.gatt.close();}
        System.out.println("BluetoothGattHostTest passed: "+args[0]);
    }
}
