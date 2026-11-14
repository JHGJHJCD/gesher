/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.performance;

import com.gesher.server.StandIn;
import gesher.services.StandInDispatchTest;
import java.lang.management.ManagementFactory;

/** HotSpot allocation measurement only; the dispatch fixture separately runs on Android. */
public final class StandInAllocationsHost {
    private static volatile int observed;
    public static void main(String[] args) {
        StandInDispatchTest.Sample proxy = StandIn.of(StandInDispatchTest.Sample.class,
                new StandInDispatchTest.Impl()).asInterface();
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new AssertionError("Thread allocation accounting required");
        bean.setThreadAllocatedMemoryEnabled(true);
        int sum = 0;
        for (int i = 0; i < 200000; i++) sum += proxy.ping();
        final int calls = 250000;
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        for (int i = 0; i < calls; i++) sum += proxy.ping();
        long elapsed = System.nanoTime() - start;
        long bytes = bean.getThreadAllocatedBytes(thread) - before;
        observed = sum;
        if (observed != 450000 * 17) throw new AssertionError("Dispatch result changed");
        System.out.println("StandIn warmed Proxy: calls=" + calls + " allocatedBytes=" + bytes + " elapsedNs=" + elapsed);
        // A small allowance accommodates VM bookkeeping; repeating reflective Method
        // resolution allocates far more. Time is informational, never a CI threshold.
        if (bytes > calls * 32L + 4096) throw new AssertionError("Repeated method-resolution allocations remain");
    }
}
