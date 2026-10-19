package app.perroute.localnet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public record LocalNetwork(String id, String adapter, String address, String cidr, int prefix, String gateway, String gatewayMac,
                           List<String> dnsServers, List<Device> devices) {

    public LocalNetwork {
        dnsServers = List.copyOf(dnsServers);
        devices = List.copyOf(devices);
    }

    public record Device(String address, String mac, String name, boolean gateway) {}

    public static String identity(String cidr, String gatewayMac, String gateway) {
        String basis = cidr + "|" + (gatewayMac == null || gatewayMac.isBlank() ? "ip:" + gateway : gatewayMac.toLowerCase(Locale.ROOT));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(basis.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String cidrOf(String address, int prefix) {
        String[] parts = address.split("\\.");
        if (parts.length != 4 || prefix < 0 || prefix > 32) throw new IllegalArgumentException("Not an IPv4 address: " + address);
        long value = 0;
        for (String part : parts) value = (value << 8) | (Integer.parseInt(part) & 0xff);
        long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = value & mask;
        return ((network >> 24) & 0xff) + "." + ((network >> 16) & 0xff) + "." + ((network >> 8) & 0xff) + "." + (network & 0xff) + "/" + prefix;
    }

    public static boolean contains(String cidr, String address) {
        try {
            String[] split = cidr.split("/");
            int prefix = Integer.parseInt(split[1]);
            return cidrOf(address, prefix).equals(cidrOf(split[0], prefix) );
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public static boolean privateRange(String address) {
        return contains("10.0.0.0/8", address) || contains("172.16.0.0/12", address) || contains("192.168.0.0/16", address)
                || contains("169.254.0.0/16", address) || contains("100.64.0.0/10", address);
    }
}
