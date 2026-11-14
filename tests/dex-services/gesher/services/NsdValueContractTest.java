/* Copyright (C) 2026 The Gesher Authors; SPDX-License-Identifier: Apache-2.0 */
package gesher.services;

import android.net.Network;
import android.net.nsd.NsdServiceInfo;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public call sites compile against SDK36; native Parcel is required only on Windows. */
public final class NsdValueContractTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { check(true, message); return; }
        throw new AssertionError(message + ": accepted");
    }
    private static Object hidden(NsdServiceInfo value, String name, Class<?> type, Object argument) throws Exception {
        Method method = type == null ? NsdServiceInfo.class.getMethod(name)
                : NsdServiceInfo.class.getMethod(name, type);
        return type == null ? method.invoke(value) : method.invoke(value, argument);
    }

    private static void entryBounds() {
        NsdServiceInfo value = new NsdServiceInfo();
        value.setAttribute("k", "x".repeat(253));
        check(value.getAttributes().get("k").length == 253, "254-byte key plus value accepted");
        rejects(() -> value.setAttribute("k", "x".repeat(254)), "255-byte key plus value rejected");
        check(value.getAttributes().get("k").length == 253, "rejected replacement preserves old value");
        NsdServiceInfo utf8 = new NsdServiceInfo();
        utf8.setAttribute("k", "א".repeat(126));
        check(utf8.getAttributes().get("k").length == 252, "UTF-8 value encoded before measuring bytes");
        rejects(() -> utf8.setAttribute("k", "א".repeat(127)), "UTF-8 byte boundary rejected");
        check(utf8.getAttributes().get("k").length == 252, "UTF-8 rejection leaves old bytes");
        NsdServiceInfo keys = new NsdServiceInfo();
        keys.setAttribute("k".repeat(254), (String)null);
        check(keys.getAttributes().containsKey("k".repeat(254)), "254-byte null-valued key accepted");
        rejects(() -> keys.setAttribute("k".repeat(255), (String)null), "255-byte null-valued key rejected");
        NsdServiceInfo recommendation = new NsdServiceInfo();
        recommendation.setAttribute("abcdefghij", "v");
        check(recommendation.getAttributes().size() == 1, "nine-byte key recommendation is not a hard limit");
        for (String bad : new String[] {null, "", "a=b", "a\u001f", "a\u007f", "א"}) {
            rejects(() -> recommendation.setAttribute(bad, "x"), "invalid TXT key rejected");
        }
        check(recommendation.getAttributes().size() == 1, "invalid keys never mutate attributes");
    }

    private static void totalBounds() {
        NsdServiceInfo value = new NsdServiceInfo();
        for (char key = 'a'; key <= 'e'; key++) value.setAttribute(String.valueOf(key), "x".repeat(253));
        value.setAttribute("t", "x".repeat(17)); // 5 * 256 + 20 = 1300 encoded bytes.
        check(value.getAttributes().size() == 6, "exact 1300-byte total accepted");
        rejects(() -> value.setAttribute("z", (String)null), "total beyond 1300 bytes rejected");
        check(!value.getAttributes().containsKey("z"), "oversized total leaves map unchanged");
        // Pinned Android checks old total plus the proposed entry even for replacement.
        rejects(() -> value.setAttribute("t", ""), "pinned replacement size validation retained");
        check(value.getAttributes().get("t").length == 17, "rejected aggregate replacement leaves old value");
        value.removeAttribute("a"); value.setAttribute("z", (String)null);
        check(value.getAttributes().containsKey("z") && value.getAttributes().get("z") == null,
                "removal frees record capacity and null value remains distinct");
    }

    private static List<InetAddress> addresses() throws Exception {
        byte[] local = new byte[16]; local[0] = (byte)0xfe; local[1] = (byte)0x80; local[15] = 1;
        byte[] mapped = new byte[16]; mapped[10] = mapped[11] = (byte)0xff;
        mapped[12] = (byte)192; mapped[13] = 0; mapped[14] = 2; mapped[15] = 9;
        return Arrays.asList(InetAddress.getByAddress(new byte[] {(byte)192, 0, 2, 1}),
                Inet6Address.getByAddress(null, local, 12), Inet6Address.getByAddress(null, mapped, 0), null);
    }
    private static void verifyAddresses(List<InetAddress> values, String boundary) throws Exception {
        List<InetAddress> expected = addresses();
        check(values.size() == 4, boundary + " address count");
        check(values.get(0) instanceof Inet4Address && values.get(0).equals(expected.get(0)), boundary + " IPv4");
        check(values.get(1) instanceof Inet6Address && ((Inet6Address)values.get(1)).getScopeId() == 12
                && Arrays.equals(values.get(1).getAddress(), expected.get(1).getAddress()), boundary + " scoped IPv6");
        check(values.get(2) instanceof Inet6Address && ((Inet6Address)values.get(2)).getScopeId() == 0
                && Arrays.equals(values.get(2).getAddress(), expected.get(2).getAddress()), boundary + " mapped IPv6 retains type");
        check(values.get(3) == null, boundary + " nullable legacy host");
    }

    private static NsdServiceInfo sample() throws Exception {
        NsdServiceInfo value = new NsdServiceInfo();
        value.setServiceName("fixture"); value.setServiceType("_http._tcp"); value.setPort(631);
        value.setSubtypes(Set.of("_one", "_two")); value.setHostAddresses(addresses());
        value.setAttribute("flag", (String)null); value.setAttribute("empty", "");
        value.setAttribute("title", "גשר");
        return value;
    }
    private static void values() throws Exception {
        NsdServiceInfo value = sample();
        verifyAddresses(value.getHostAddresses(), "value");
        List<InetAddress> detached = value.getHostAddresses(); detached.clear();
        check(value.getHostAddresses().size() == 4, "host address getter is detached");
        check(value.getAttributes().get("flag") == null && value.getAttributes().get("empty").length == 0,
                "null and empty TXT values remain distinct");
        check(Arrays.equals(value.getAttributes().get("title"), "גשר".getBytes(StandardCharsets.UTF_8)), "TXT value bytes preserved");
        value.setHost(null);
        check(value.getHost() == null && value.getHostAddresses().size() == 1
                && value.getHostAddresses().get(0) == null, "legacy null host retains nullable address entry");
        check(value.describeContents() == 0 && NsdServiceInfo.CREATOR.newArray(2).length == 2, "Parcelable metadata");
    }

    private static void completeValues() throws Exception {
        NsdServiceInfo value = new NsdServiceInfo();
        byte[] raw = new byte[] {3, 'a', '=', 'b'};
        hidden(value, "setTxtRecords", byte[].class, raw);
        check(Arrays.equals(value.getAttributes().get("a"), new byte[] {'b'}), "raw TXT record decoded");
        check(Arrays.equals((byte[])hidden(value, "getTxtRecord", null, null), raw), "raw TXT record encoded");
        NsdServiceInfo.class.getMethod("setAttribute", String.class, byte[].class)
                .invoke(value, "binary", new byte[] {(byte)0xff, (byte)0x80});
        check(value.toString().toLowerCase(java.util.Locale.ROOT).contains("0xff80"), "module HexDump formats binary TXT bytes");
        byte[] key = {1, 2, 3}; hidden(value, "setPublicKey", byte[].class, key); key[0] = 9;
        byte[] copy = (byte[])hidden(value, "getPublicKey", null, null);
        check(Arrays.equals(copy, new byte[] {1, 2, 3}), "key setter retains an independent value");
        copy[1] = 9;
        check(Arrays.equals((byte[])hidden(value, "getPublicKey", null, null), new byte[] {1, 2, 3}),
                "key getter returns an independent value");
        hidden(value, "setPublicKey", byte[].class, null);
        check(hidden(value, "getPublicKey", null, null) == null, "key can be cleared");
        hidden(value, "setExpirationTime", Instant.class, Instant.ofEpochSecond(123, 456));
        check(hidden(value, "getExpirationTime", null, null).equals(Instant.ofEpochSecond(123)),
                "expiration preserves whole-second precision");
        hidden(value, "setExpirationTime", Instant.class, null);
        check(hidden(value, "getExpirationTime", null, null) == null, "expiration can be cleared");
        hidden(value, "setInterfaceIndex", int.class, 12);
        check(((Integer)hidden(value, "getInterfaceIndex", null, null)) == 12, "interface metadata retained");
    }

    private static void parcelContracts() throws Exception {
        final int marker = 0x13579bdf;
        NsdServiceInfo value = sample();
        hidden(value, "setHostname", String.class, "fixture-host");
        hidden(value, "setInterfaceIndex", int.class, 12);
        hidden(value, "setExpirationTime", Instant.class, Instant.ofEpochSecond(1_700_000_000L, 123));
        hidden(value, "setPublicKey", byte[].class, new byte[] {1, 2, 3});
        Parcel parcel = Parcel.obtain();
        try {
            value.writeToParcel(parcel, 0); parcel.writeInt(marker); parcel.setDataPosition(0);
            NsdServiceInfo copy = NsdServiceInfo.CREATOR.createFromParcel(parcel);
            verifyAddresses(copy.getHostAddresses(), "native Parcel");
            check(copy.getServiceName().equals("fixture") && copy.getServiceType().equals("_http._tcp")
                    && copy.getPort() == 631 && copy.getSubtypes().equals(Set.of("_one", "_two")), "native Parcel service fields");
            check(copy.getAttributes().get("flag") == null && copy.getAttributes().get("empty").length == 0,
                    "native Parcel preserves nullable and empty values");
            check(copy.getHostname().equals("fixture-host") && copy.getNetwork() == null, "native Parcel host and network");
            check(((Integer)hidden(copy, "getInterfaceIndex", null, null)) == 12, "native Parcel interface scope metadata");
            check(hidden(copy, "getExpirationTime", null, null).equals(Instant.ofEpochSecond(1_700_000_000L)),
                    "native Parcel expiration has pinned whole-second precision");
            check(Arrays.equals((byte[])hidden(copy, "getPublicKey", null, null), new byte[] {1, 2, 3}), "native Parcel key bytes");
            check(parcel.readInt() == marker && parcel.dataAvail() == 0, "native Parcel consumes exact object boundary");
        } finally { parcel.recycle(); }

        // Read the production writer using an independent Android16-r4 field-order oracle.
        parcel = Parcel.obtain();
        try {
            value.writeToParcel(parcel, 0); parcel.writeInt(marker); parcel.setDataPosition(0);
            check(parcel.readString().equals("fixture") && parcel.readString().equals("_http._tcp"), "pinned schema name and type");
            check(Set.copyOf(parcel.createStringArrayList()).equals(Set.of("_one", "_two")), "pinned schema subtype set");
            check(parcel.readInt() == 631, "pinned schema port precedes TXT attributes");
            int count = parcel.readInt(); Map<String,byte[]> attributes = new HashMap<>();
            for (int i = 0; i < count; i++) {
                byte[] bytes = null;
                if (parcel.readInt() == 1) { bytes = new byte[parcel.readInt()]; parcel.readByteArray(bytes); }
                attributes.put(parcel.readString(), bytes);
            }
            check(count == 3 && attributes.containsKey("flag") && attributes.get("flag") == null
                    && attributes.get("empty").length == 0 && Arrays.equals(attributes.get("title"), "גשר".getBytes(StandardCharsets.UTF_8)),
                    "pinned schema nullable length/value then TXT key");
            check(parcel.readParcelable(Network.class.getClassLoader(), Network.class) == null && parcel.readInt() == 12,
                    "pinned schema network and interface before addresses");
            check(parcel.readInt() == 4, "pinned schema address count");
            check(Arrays.equals(parcel.createByteArray(), addresses().get(0).getAddress()), "pinned IPv4 has no scope marker");
            check(Arrays.equals(parcel.createByteArray(), addresses().get(1).getAddress())
                    && parcel.readBoolean() && parcel.readInt() == 12, "pinned scoped IPv6 bytes and scope");
            check(Arrays.equals(parcel.createByteArray(), addresses().get(2).getAddress()) && !parcel.readBoolean(),
                    "pinned mapped IPv6 keeps sixteen bytes and scope marker");
            check(parcel.createByteArray() == null, "pinned nullable address has no scope marker");
            check(parcel.readString().equals("fixture-host") && parcel.readLong() == 1_700_000_000L
                    && Arrays.equals(parcel.createByteArray(), new byte[] {1, 2, 3}), "pinned trailing hostname, expiration and key");
            check(parcel.readInt() == marker && parcel.dataAvail() == 0, "pinned schema boundary");
        } finally { parcel.recycle(); }

        // Feed the production reader a separately authored pinned-layout object.
        parcel = Parcel.obtain();
        try {
            parcel.writeString("external"); parcel.writeString("_printer._tcp");
            parcel.writeStringList(List.of()); parcel.writeInt(515);
            parcel.writeInt(1); parcel.writeInt(0); parcel.writeString("flag");
            parcel.writeParcelable(null, 0); parcel.writeInt(12); parcel.writeInt(1);
            parcel.writeByteArray(addresses().get(1).getAddress()); parcel.writeBoolean(true); parcel.writeInt(12);
            parcel.writeString(null); parcel.writeLong(-1); parcel.writeByteArray(null); parcel.writeInt(marker);
            parcel.setDataPosition(0);
            NsdServiceInfo copy = NsdServiceInfo.CREATOR.createFromParcel(parcel);
            check(copy.getServiceName().equals("external") && copy.getServiceType().equals("_printer._tcp")
                    && copy.getPort() == 515 && copy.getSubtypes().isEmpty(), "reader accepts independently encoded pinned fields");
            check(copy.getAttributes().size() == 1 && copy.getAttributes().containsKey("flag")
                    && copy.getAttributes().get("flag") == null, "reader accepts independently encoded null TXT value");
            check(copy.getHostAddresses().size() == 1 && copy.getHost() instanceof Inet6Address
                    && ((Inet6Address)copy.getHost()).getScopeId() == 12, "reader accepts independent scoped IPv6");
            check(copy.getHostname() == null && hidden(copy, "getExpirationTime", null, null) == null
                    && hidden(copy, "getPublicKey", null, null) == null, "reader accepts nullable trailing metadata");
            check(parcel.readInt() == marker && parcel.dataAvail() == 0, "independent reader boundary");
        } finally { parcel.recycle(); }
    }

    public static void main(String[] args) throws Exception {
        boolean nativeParcel = args.length == 0;
        if (nativeParcel) System.loadLibrary("android_runtime");
        else System.load(args[0]);
        String selected = args.length > 1 ? args[1] : "all";
        if (selected.equals("all") || selected.equals("entries")) entryBounds();
        if (selected.equals("all") || selected.equals("total")) totalBounds();
        if (selected.equals("all") || selected.equals("values")) values();
        if (selected.equals("all") || selected.equals("complete")) completeValues();
        if (nativeParcel) parcelContracts();
        System.out.println("NsdValueContractTest all passed: " + checks + "; parcel=" + nativeParcel + "; case=" + selected);
    }
}
