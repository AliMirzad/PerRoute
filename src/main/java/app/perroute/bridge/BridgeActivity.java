package app.perroute.bridge;

import app.perroute.inspector.XrayLogParser;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BridgeActivity {
    private static final int RECENT = 6;

    public record Device(long lastSeen, String address, int connections, List<String> recent) {
        public static final Device NONE = new Device(0L, "", 0, List.of());

        public boolean activeAt(long now) { return lastSeen > 0 && now - lastSeen < 90_000L; }
    }

    private static final class Entry {
        long lastSeen;
        String address = "";
        int connections;
        final ArrayDeque<String> recent = new ArrayDeque<>();
    }

    private final Map<String, Entry> entries = new HashMap<>();

    private static final Pattern HTTP_REQUEST = Pattern.compile(
            "from (?:(?:tcp|udp):)?(\\S+?):\\d+ accepted https?://\\[?([^/\\]\\s:]+)\\]?\\S* \\[(perroute-bridge-\\d+) (?:->|>>) \\S+\\]");

    public void accept(String line, long now) {
        String inbound, source, host;
        if (XrayLogParser.parse(line) instanceof XrayLogParser.Access access) {
            inbound = access.inbound();
            source = access.sourceHost();
            host = access.destinationHost();
        } else {
            Matcher matcher = line == null || !line.contains(" accepted http") ? null : HTTP_REQUEST.matcher(line);
            if (matcher == null || !matcher.find()) return;
            source = matcher.group(1);
            host = matcher.group(2);
            inbound = matcher.group(3);
        }
        if (!inbound.startsWith("perroute-bridge-")) return;
        synchronized (entries) {
            Entry entry = entries.computeIfAbsent(inbound, tag -> new Entry());
            entry.lastSeen = now;
            entry.address = source;
            entry.connections++;
            entry.recent.remove(host);
            entry.recent.addFirst(host);
            while (entry.recent.size() > RECENT) entry.recent.removeLast();
        }
    }

    public Device device(String inboundTag) {
        synchronized (entries) {
            Entry entry = entries.get(inboundTag);
            if (entry == null) return Device.NONE;
            return new Device(entry.lastSeen, entry.address, entry.connections, List.copyOf(entry.recent));
        }
    }

    public void forget(String inboundTag) {
        synchronized (entries) { entries.remove(inboundTag); }
    }

    public void clear() {
        synchronized (entries) { entries.clear(); }
    }
}
