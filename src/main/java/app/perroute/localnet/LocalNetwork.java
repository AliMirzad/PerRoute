package app.perroute.localnet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * A local network this computer is connected to, and the devices seen on it.
 *
 * @param id       stable identity: the network's address range and its router's hardware address, hashed, so the
 *                 same Wi-Fi is recognised again while a different network with the same range is not
 * @param cidr     the network's IPv4 range, such as 192.168.1.0/24
 * @param gatewayMac the router's hardware address, or blank when it could not be read
 */
public record LocalNetwork(String id, String adapter, String address, String cidr, int prefix, String gateway, String gatewayMac,
                           List<String> dnsServers, List<Device> devices) {

    public LocalNetwork {
        dnsServers = List.copyOf(dnsServers);
        devices = List.copyOf(devices);
    }

    /** A device answering on the network, from the computer's neighbour table. */
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

    /** The range of an IPv4 address with a prefix length: 192.168.1.20 and 24 give 192.168.1.0/24. */
    public static String cidrOf(String address, int prefix) {
        String[] parts = address.split("\\.");
        if (parts.length != 4 || prefix < 0 || prefix > 32) throw new IllegalArgumentException("Not an IPv4 address: " + address);
        long value = 0;
        for (String part : parts) value = (value << 8) | (Integer.parseInt(part) & 0xff);
        long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = value & mask;
        return ((network >> 24) & 0xff) + "." + ((network >> 16) & 0xff) + "." + ((network >> 8) & 0xff) + "." + (network & 0xff) + "/" + prefix;
    }

    /** Whether an IPv4 address is inside a range. */
    public static boolean contains(String cidr, String address) {
        try {
            String[] split = cidr.split("/");
            int prefix = Integer.parseInt(split[1]);
            return cidrOf(address, prefix).equals(cidrOf(split[0], prefix) );
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** The address ranges people use at home and at work (RFC 1918, link-local and carrier-grade NAT). */
    public static boolean privateRange(String address) {
        return contains("10.0.0.0/8", address) || contains("172.16.0.0/12", address) || contains("192.168.0.0/16", address)
                || contains("169.254.0.0/16", address) || contains("100.64.0.0/10", address);
    }
}
