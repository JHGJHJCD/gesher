/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
import gesher.services.ConnectivityCallbackContractTest;
public final class ConnectivityCallbackHost {
    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        String selected = args.length > 1 ? args[1] : "all";
        if (!selected.equals("freshness")) ConnectivityCallbackContractTest.contracts(selected, false);
        if (selected.equals("all") || selected.equals("freshness")) ConnectivityServiceSnapshotHost.contracts();
        System.out.println("Production ConnectivityManager, controlled Handler scheduling and network service; no Windows, Looper or Parcel transport claim.");
    }
}
