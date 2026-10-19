package app.perroute.localnet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalNetworkScanner {
    private static final Pattern ARP_ROW = Pattern.compile("^\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+([0-9a-fA-F]{2}(?:-[0-9a-fA-F]{2}){5})\\s+\\S+\\s*$");

    public List<LocalNetwork> scan(List<String> gateways, List<String> dns, boolean resolveNames) {
        Map<String, String> neighbours = neighbours();
        List<LocalNetwork> networks = new ArrayList<>();
        try {
            for (NetworkInterface adapter : NetworkInterface.networkInterfaces().toList()) {
                if (!adapter.isUp() || adapter.isLoopback()) continue;
                for (InterfaceAddress address : adapter.getInterfaceAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address ipv4)) continue;
                    String own = ipv4.getHostAddress();
                    int prefix = address.getNetworkPrefixLength();
                    if (prefix <= 0 || prefix > 30 || !LocalNetwork.privateRange(own)) continue;
                    String cidr = LocalNetwork.cidrOf(own, prefix);
                    if (networks.stream().anyMatch(network -> network.cidr().equals(cidr))) continue;
                    String gateway = gateways.stream().filter(candidate -> LocalNetwork.contains(cidr, candidate)).findFirst().orElse("");
                    String gatewayMac = gateway.isBlank() ? "" : neighbours.getOrDefault(gateway, "");
                    List<String> resolvers = dns.stream().filter(server -> LocalNetwork.contains(cidr, server)).toList();
                    List<LocalNetwork.Device> devices = new ArrayList<>();
                    neighbours.forEach((ip, mac) -> {
                        if (!LocalNetwork.contains(cidr, ip) || ip.equals(own) || broadcast(cidr, ip) || mac.equalsIgnoreCase("ff-ff-ff-ff-ff-ff")) return;
                        devices.add(new LocalNetwork.Device(ip, mac, "", ip.equals(gateway)));
                    });
                    networks.add(new LocalNetwork(LocalNetwork.identity(cidr, gatewayMac, gateway), adapter.getDisplayName(), own, cidr, prefix,
                            gateway, gatewayMac, resolvers, resolveNames ? named(devices) : devices));
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return networks;
    }

    static Map<String, String> neighbours() {
        String output = run("arp.exe", "-a");
        return output == null ? Map.of() : parseArp(output);
    }

    public static Map<String, String> parseArp(String output) {
        Map<String, String> table = new LinkedHashMap<>();
        for (String line : output.lines().toList()) {
            Matcher matcher = ARP_ROW.matcher(line);
            if (!matcher.matches()) continue;
            String ip = matcher.group(1);
            if (ip.startsWith("224.") || ip.startsWith("239.") || ip.equals("255.255.255.255")) continue;
            table.putIfAbsent(ip, matcher.group(2).toLowerCase(Locale.ROOT));
        }
        return table;
    }

    private static boolean broadcast(String cidr, String ip) {
        try {
            int prefix = Integer.parseInt(cidr.split("/")[1]);
            String[] parts = ip.split("\\.");
            long value = 0;
            for (String part : parts) value = (value << 8) | Integer.parseInt(part);
            long host = value & ((1L << (32 - prefix)) - 1);
            return host == (1L << (32 - prefix)) - 1;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static List<LocalNetwork.Device> named(List<LocalNetwork.Device> devices) {
        if (devices.isEmpty()) return devices;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<String>> names = new ArrayList<>();
            for (LocalNetwork.Device device : devices) {
                names.add(executor.submit(() -> {
                    String name = InetAddress.getByName(device.address()).getCanonicalHostName();
                    return name.equals(device.address()) ? "" : name;
                }));
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_500);
            List<LocalNetwork.Device> result = new ArrayList<>();
            for (int index = 0; index < devices.size(); index++) {
                String name = "";
                try { name = names.get(index).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (Exception slow) { names.get(index).cancel(true); }
                LocalNetwork.Device device = devices.get(index);
                result.add(new LocalNetwork.Device(device.address(), device.mac(), name, device.gateway()));
            }
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try { process.getInputStream().transferTo(output); } catch (IOException ignored) {}
            });
            if (!process.waitFor(6, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            drain.join(1_000);
            return output.toString(java.nio.charset.StandardCharsets.US_ASCII);
        } catch (IOException error) {
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
