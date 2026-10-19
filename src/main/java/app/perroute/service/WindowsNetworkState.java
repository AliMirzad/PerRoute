package app.perroute.service;

import app.perroute.netstate.NetworkStateKeys;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Reads the network settings a {@link app.perroute.netstate.NetworkSnapshot} records, without changing any of them. */
public final class WindowsNetworkState {
    private final WindowsSystemProxyManager proxy;
    private final ProxiFyreManager engine;
    private final XrayProcessManager core;
    private final Path dataDirectory;

    public WindowsNetworkState(WindowsSystemProxyManager proxy, ProxiFyreManager engine, XrayProcessManager core, Path dataDirectory) {
        this.proxy = proxy;
        this.engine = engine;
        this.core = core;
        this.dataDirectory = dataDirectory;
    }

    /** Every reading; one that cannot be taken is left out rather than guessed. */
    public Map<String, String> read(List<String> dnsServers) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            Map<String, Map<String, Object>> current = proxy.currentValues();
            put(values, NetworkStateKeys.PROXY_ENABLE, current.get("ProxyEnable"));
            put(values, NetworkStateKeys.PROXY_SERVER, current.get("ProxyServer"));
            put(values, NetworkStateKeys.PROXY_OVERRIDE, current.get("ProxyOverride"));
            put(values, NetworkStateKeys.PROXY_PAC, current.get("AutoConfigURL"));
        } catch (IOException error) {
            // Registry not readable: those readings stay missing.
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        values.put(NetworkStateKeys.PROXY_BACKUP, yes(proxy.hasPendingRestore()));
        values.put(NetworkStateKeys.RUN_ONCE, yes(proxy.recoveryEntryRegistered()));
        boolean installed = engine.inspect().installed();
        values.put(NetworkStateKeys.ENGINE_INSTALLED, yes(installed));
        values.put(NetworkStateKeys.ENGINE_SERVICE, engine.inspect().serviceInstalled() ? engine.serviceState() : NetworkStateKeys.MISSING);
        if (installed) {
            values.put(NetworkStateKeys.ENGINE_CONFIG, engine.configOwner());
            values.put(NetworkStateKeys.ENGINE_APPS, Integer.toString(engine.configuredApps()));
            values.put(NetworkStateKeys.ENGINE_BACKUP, yes(engine.backupPending()));
        }
        values.put(NetworkStateKeys.ENGINE_LIMITED, engine.limitedRunning() ? NetworkStateKeys.RUNNING : NetworkStateKeys.STOPPED);
        values.put(NetworkStateKeys.CORE_PROCESSES, Integer.toString(core.orphanCount()));
        values.put(NetworkStateKeys.SESSION, yes(Files.exists(dataDirectory.resolve("session.active"))));
        String winHttp = winHttpProxy();
        if (winHttp != null) values.put(NetworkStateKeys.WINHTTP, winHttp);
        if (dnsServers != null && !dnsServers.isEmpty()) values.put(NetworkStateKeys.DNS, String.join(", ", dnsServers));
        List<String> gateways = defaultGateways();
        if (!gateways.isEmpty()) values.put(NetworkStateKeys.GATEWAY, String.join(", ", gateways));
        String adapters = adapters();
        if (!adapters.isEmpty()) values.put(NetworkStateKeys.ADAPTERS, adapters);
        return values;
    }

    private static void put(Map<String, String> values, String key, Map<String, Object> reading) {
        if (reading == null) return;
        values.put(key, Boolean.TRUE.equals(reading.get("present")) ? String.valueOf(reading.get("data")).trim() : "");
    }

    private static String yes(boolean value) { return value ? "yes" : "no"; }

    /** "Direct" or the WinHTTP proxy server, from netsh. Null when it cannot be read. */
    static String winHttpProxy() {
        String output = run("netsh.exe", "winhttp", "show", "proxy");
        if (output == null) return null;
        for (String line : output.lines().toList()) {
            int colon = line.indexOf(':');
            String label = colon < 0 ? "" : line.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            if (colon > 0 && label.contains("proxy server")) return line.substring(colon + 1).trim();
        }
        return "Direct";
    }

    /** Default gateways from the IPv4 route table; the rows are not localized even when the headings are. */
    public static List<String> defaultGateways() {
        String output = run("route.exe", "print", "-4", "0.0.0.0");
        if (output == null) return List.of();
        LinkedHashSet<String> gateways = new LinkedHashSet<>();
        for (String line : output.lines().toList()) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 5 && parts[0].equals("0.0.0.0") && parts[1].equals("0.0.0.0") && parts[2].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                gateways.add(parts[2]);
            }
        }
        return new ArrayList<>(gateways);
    }

    /** Connected adapters with their IPv4 networks: "Wi-Fi 192.168.1.20/24". */
    static String adapters() {
        List<String> parts = new ArrayList<>();
        try {
            for (NetworkInterface network : NetworkInterface.networkInterfaces().toList()) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (InterfaceAddress address : network.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) {
                        parts.add(network.getDisplayName() + " " + address.getAddress().getHostAddress() + "/" + address.getNetworkPrefixLength());
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return String.join("; ", parts);
    }

    static String run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            // Read on the side so a tool that never exits cannot block the caller past the timeout.
            Thread drain = Thread.ofVirtual().start(() -> {
                try { process.getInputStream().transferTo(output); } catch (IOException ignored) {}
            });
            if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            drain.join(1_000);
            return output.toString(consoleCharset());
        } catch (IOException error) {
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Console tools write in the OEM code page; it only matters for the few localized words read here. */
    private static Charset consoleCharset() {
        try { return Charset.forName(System.getProperty("sun.stdout.encoding", System.getProperty("native.encoding", "UTF-8"))); }
        catch (RuntimeException unknown) { return Charset.defaultCharset(); }
    }
}
