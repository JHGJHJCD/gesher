#!/usr/bin/env python3
"""Exercise whole production BluetoothGatt and AOSP value classes on a host JVM.

Android initialization, permissions, Handler dispatch and native radio calls are
explicit host boundaries. Real Java executors drive production lifecycle code;
Parcel methods throw because parcels are covered by the Windows ART tests.
Use --source-ref with each --case for independent pre-fix negative controls.
"""
from __future__ import annotations
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BOUNDARIES = {
 'android/content/Context.java': 'package android.content; public class Context {}',
 'android/bluetooth/BluetoothHost.java': 'package android.bluetooth; public final class BluetoothHost {public static void enforceConnect(android.content.Context c){}}',
 'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth; public final class BluetoothDevice {
 public static final int PHY_LE_1M=1; private final String address;
 public BluetoothDevice(String address,int type){this.address=address;}
 public String getAddress(){return address;} public int getAddressType(){return 0;}
 public long value(){return 1;} public String toString(){return address;}}
''',
 'android/bluetooth/BluetoothAdapter.java': 'package android.bluetooth; public final class BluetoothAdapter {public static BluetoothAdapter getDefaultAdapter(){return null;}}',
 'android/bluetooth/le/BluetoothLeScanner.java': 'package android.bluetooth.le; public final class BluetoothLeScanner {public static Integer lastRssi(String address){return null;}}',
 'android/os/Looper.java': 'package android.os; public final class Looper {}',
 'android/os/HandlerThread.java': '''package android.os; public class HandlerThread extends Thread {
 public HandlerThread(String name){super(name);} public Looper getLooper(){return null;}
 public boolean quitSafely(){return true;}}
''',
 'android/os/Handler.java': '''package android.os; public final class Handler {
 private final java.util.concurrent.LinkedBlockingQueue<Runnable> queue=new java.util.concurrent.LinkedBlockingQueue<>();
 public Handler(){} public Handler(Looper looper){}
 public boolean post(Runnable r){synchronized(queue){queue.add(r);queue.notifyAll();}return true;}
 public void await(int count)throws Exception{
  long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
  synchronized(queue){while(queue.size()<count){long left=end-System.nanoTime();
   if(left<=0)throw new AssertionError("callback queue timed out: "+queue.size()+" < "+count);
   java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(queue,left);}}}
 public void drain(){Runnable r;while((r=queue.poll())!=null)r.run();}}
''',
 'android/util/Log.java': '''package android.util; public final class Log {
 public static int i(String tag,String text){return 0;}
 public static int e(String tag,String text,Throwable cause){return 0;}}
''',
 'android/os/Build.java': 'package android.os; public final class Build {public static final class VERSION_CODES {public static final int R=30;}}',
 'android/os/Parcelable.java': '''package android.os; public interface Parcelable {
 int describeContents();void writeToParcel(Parcel out,int flags);
 interface Creator<T>{T createFromParcel(Parcel in);T[] newArray(int size);}}
''',
 'android/os/Parcel.java': '''package android.os; public final class Parcel {
 public void writeInt(int i){throw new AssertionError("Parcel boundary");}
 public int readInt(){throw new AssertionError("Parcel boundary");}
 public <T extends Parcelable> void writeTypedList(java.util.List<T> items){throw new AssertionError("Parcel boundary");}
 public <T> java.util.ArrayList<T> createTypedArrayList(Parcelable.Creator<T> creator){throw new AssertionError("Parcel boundary");}}
''',
 'android/os/ParcelUuid.java': '''package android.os; public final class ParcelUuid implements Parcelable {
 public ParcelUuid(java.util.UUID uuid){} public java.util.UUID getUuid(){throw new AssertionError("Parcel boundary");}
 public int describeContents(){return 0;} public void writeToParcel(Parcel p,int flags){throw new AssertionError("Parcel boundary");}
 public static final Parcelable.Creator<ParcelUuid> CREATOR=null;}
''',
 'android/annotation/IntDef.java': 'package android.annotation; public @interface IntDef {long[] value() default {}; boolean flag() default false; String[] prefix() default {};}',
 'android/annotation/FlaggedApi.java': 'package android.annotation; public @interface FlaggedApi {String value();}',
 'android/annotation/NonNull.java': 'package android.annotation; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE,java.lang.annotation.ElementType.METHOD,java.lang.annotation.ElementType.PARAMETER,java.lang.annotation.ElementType.FIELD}) public @interface NonNull {}',
 'android/annotation/RequiresNoPermission.java': 'package android.annotation; public @interface RequiresNoPermission {}',
 'android/annotation/SystemApi.java': 'package android.annotation; public @interface SystemApi {}',
 'android/compat/annotation/UnsupportedAppUsage.java': 'package android.compat.annotation; public @interface UnsupportedAppUsage {int maxTargetSdk() default 0; long trackingBug() default 0;}',
}


def main() -> None:
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--java-home',type=Path,required=True)
 parser.add_argument('--source-ref')
 parser.add_argument('--case',choices=('all','cancel','closed-callback','notifications','stale-read','requested-mtu','mtu-event'),default='all')
 args=parser.parse_args()
 with tempfile.TemporaryDirectory(prefix='gesher-gatt-') as tmp:
  work=Path(tmp);sources=[]
  for name,content in BOUNDARIES.items():
   path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content);sources.append(str(path))
  for name in ('BluetoothGatt','BluetoothGattCallback','BluetoothGattService','BluetoothGattCharacteristic',
               'BluetoothGattDescriptor','BluetoothGattIncludedService','BluetoothStatusCodes','BluetoothProfile'):
   relative='app/java/android/bluetooth/'+name+'.java';path=ROOT/relative
   if args.source_ref and name=='BluetoothGatt':
    path=work/(name+'.java');path.write_bytes(subprocess.check_output(['git','show',args.source_ref+':'+relative],cwd=ROOT))
   sources.append(str(path))
   if name=='BluetoothGatt':print('BluetoothGatt SHA256',hashlib.sha256(path.read_bytes()).hexdigest(),flush=True)
  sources += [str(p) for p in sorted((ROOT/'tests/bluetooth/gatt-host').glob('*.java'))]
  classes=work/'classes';classes.mkdir()
  subprocess.run([str(args.java_home/'bin/javac'),'-J-XX:-UsePerfData','-encoding','UTF-8','-d',str(classes),*sources],check=True,timeout=60)
  cases=('cancel','closed-callback','notifications','stale-read','requested-mtu','mtu-event') if args.case=='all' else (args.case,)
  for case in cases:
   subprocess.run([str(args.java_home/'bin/java'),'-XX:-UsePerfData','-cp',str(classes),'android.bluetooth.BluetoothGattHostTest',case],check=True,timeout=30)


if __name__=='__main__':main()
