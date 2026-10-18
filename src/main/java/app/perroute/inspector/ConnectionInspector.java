package app.perroute.inspector;

import app.perroute.gateway.GatewayConnection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the running core and the traffic gateway actually observed for each connection, kept in memory only
 * and bounded in size. Nothing is written to disk and no payload is ever seen.
 *
 * <p>Connections come from the gateway (destination, protocol, bytes, open/closed) and are joined with Xray's
 * own log: the access line names the outbound Xray chose for the gateway's source port, and the session lines
 * name the sniffed domain and the rule that matched. Without the gateway, connections are built from the access
 * lines alone and carry no byte counts.
 */
public final class ConnectionInspector {
    public static final int MAX_RECORDS = 1_500;
    static final long CLOSED_RETENTION_MILLIS = 120_000L;
    static final long CORE_ONLY_RETENTION_MILLIS = 60_000L;
    static final long MATCH_WINDOW_MILLIS = 3_000L;
    static final int MAX_SESSIONS = 2_048;
    static final int MAX_PENDING_ACCESS = 512;

    /** An observed connection. Every field here was seen at runtime; nothing is predicted. */
    public record Observation(long id, String applicationPath, String inboundTag, String protocol,
                              String destinationHost, int destinationPort, String sniffedDomain,
                              String observedOutbound, String observedRuleTag, boolean ruleObserved,
                              String status, String failure, long openedAt, long lastActivityAt, long closedAt,
                              long downloadBytes, long uploadBytes, long downloadRate, long uploadRate,
                              boolean viaGateway, int udpDestinations) {}

    private static final class Record {
        final long id;
        final GatewayConnection gateway;
        final long createdAt;
        String applicationPath;
        String inboundTag = "";
        String protocol;
        String host = "";
        int port;
        String domain = "";
        String outbound = "";
        String ruleTag = "";
        boolean ruleObserved;
        String failure = "";
        long lastSeenAt;
        long previousDown, previousUp, previousAt;
        long downRate, upRate;
        boolean sessionAttached;

        Record(long id, GatewayConnection gateway, long now) {
            this.id = id;
            this.gateway = gateway;
            this.createdAt = now;
            this.lastSeenAt = now;
            this.previousAt = now;
        }
    }

    private static final class Session {
        final String id;
        final long at;
        String host = "";
        int port;
        String domain = "";
        String ruleTag;
        String outbound = "";
        String failure = "";
        boolean attached;

        Session(String id, long at) { this.id = id; this.at = at; }
    }

    private record PendingAccess(XrayLogParser.Access access, long at) {}

    private final Map<Long, Record> records = new LinkedHashMap<>();
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>(256, .75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) { return size() > MAX_SESSIONS; }
    };
    private final ArrayDeque<PendingAccess> pending = new ArrayDeque<>();
    private long nextCoreId = -1;
    private volatile boolean gatewayActive;

    /** Whether connections arrive through the gateway. Without it the access log is the only source. */
    public void gatewayActive(boolean active) { gatewayActive = active; }

    public synchronized void clear() {
        records.clear();
        sessions.clear();
        pending.clear();
    }

    public synchronized void onGatewayConnection(GatewayConnection connection, long now) {
        Record record = new Record(connection.id(), connection, now);
        record.applicationPath = connection.applicationPath();
        records.put(record.id, record);
        enforceLimit();
        Iterator<PendingAccess> iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingAccess entry = iterator.next();
            if (matchesPort(record, entry.access().sourcePort())) {
                applyAccess(record, entry.access(), entry.at());
                iterator.remove();
            }
        }
    }

    public synchronized void onLogLine(String line, long now) {
        XrayLogParser.Entry entry = XrayLogParser.parse(line);
        if (entry == null) return;
        switch (entry) {
            case XrayLogParser.Access access -> onAccess(access, now);
            case XrayLogParser.Request request -> {
                Session session = session(request.session(), now);
                session.host = request.destinationHost();
                session.port = request.destinationPort();
            }
            case XrayLogParser.Sniffed sniffed -> session(sniffed.session(), now).domain = sniffed.domain();
            case XrayLogParser.RuleHit hit -> {
                Session session = session(hit.session(), now);
                session.ruleTag = hit.ruleTag();
                session.outbound = hit.outbound();
                if (session.host.isBlank()) { session.host = hit.destinationHost(); session.port = hit.destinationPort(); }
                attach(session);
            }
            case XrayLogParser.Failure failure -> {
                Session session = sessions.get(failure.session());
                if (session != null) {
                    session.failure = failure.detail();
                    attach(session);
                    for (Record record : records.values()) {
                        if (record.sessionAttached && failure.session().equals(attachedSession(record))) record.failure = failure.detail();
                    }
                }
            }
        }
    }

    private final Map<Long, String> sessionOfRecord = new java.util.HashMap<>();

    private String attachedSession(Record record) { return sessionOfRecord.get(record.id); }

    private void onAccess(XrayLogParser.Access access, long now) {
        if (access.outbound().equals("api") || access.inbound().equals("perroute-api")) return;
        for (Record record : records.values()) {
            if (record.gateway != null && !record.gateway.finished() && matchesPort(record, access.sourcePort())) {
                applyAccess(record, access, now);
                return;
            }
        }
        if (gatewayActive) {
            pending.addLast(new PendingAccess(access, now));
            while (pending.size() > MAX_PENDING_ACCESS) pending.removeFirst();
            return;
        }
        Record record = new Record(nextCoreId--, null, now);
        record.inboundTag = access.inbound();
        record.protocol = access.network().toUpperCase(java.util.Locale.ROOT);
        record.host = access.destinationHost();
        record.port = access.destinationPort();
        record.outbound = access.outbound();
        records.put(record.id, record);
        enforceLimit();
        attachSessionsFor(record.host);
    }

    private void applyAccess(Record record, XrayLogParser.Access access, long now) {
        record.inboundTag = access.inbound();
        record.outbound = access.outbound();
        record.lastSeenAt = now;
        attachSessionsFor(record.gateway != null ? record.gateway.destinationHost() : record.host);
    }

    private void attachSessionsFor(String host) {
        if (host == null || host.isBlank()) return;
        for (Session session : List.copyOf(sessions.values())) {
            if (!session.attached && session.ruleTag != null && session.host.equalsIgnoreCase(host)) attach(session);
        }
    }

    private static boolean matchesPort(Record record, int port) {
        return record.gateway != null && port > 0
                && (record.gateway.upstreamLocalPort() == port || record.gateway.udpLocalPort() == port);
    }

    private Session session(String id, long now) {
        Session session = sessions.get(id);
        if (session == null) { session = new Session(id, now); sessions.put(id, session); }
        return session;
    }

    /**
     * Joins a session (sniffed domain, rule) to the earliest connection with the same destination, opened
     * within a few seconds and, when the outbound is already known on both sides, sent to the same outbound.
     */
    private void attach(Session session) {
        if (session.attached || session.host.isBlank() || session.ruleTag == null) return;
        Record best = null;
        for (Record record : records.values()) {
            if (record.sessionAttached) continue;
            String host = record.gateway != null ? record.gateway.destinationHost() : record.host;
            int port = record.gateway != null ? record.gateway.destinationPort() : record.port;
            if (!session.host.equalsIgnoreCase(host) || session.port != port) continue;
            long opened = record.gateway != null ? record.gateway.openedAt() : record.createdAt;
            if (Math.abs(opened - session.at) > MATCH_WINDOW_MILLIS) continue;
            if (!record.outbound.isBlank() && !session.outbound.isBlank() && !record.outbound.equals(session.outbound)) continue;
            if (best == null || opened < (best.gateway != null ? best.gateway.openedAt() : best.createdAt)) best = record;
        }
        if (best == null) return;
        best.sessionAttached = true;
        best.domain = session.domain;
        best.ruleTag = session.ruleTag;
        best.ruleObserved = true;
        if (best.outbound.isBlank()) best.outbound = session.outbound;
        if (!session.failure.isBlank()) best.failure = session.failure;
        session.attached = true;
        sessionOfRecord.put(best.id, session.id);
    }

    public synchronized List<Observation> snapshot(long now) {
        prune(now);
        List<Observation> result = new ArrayList<>(records.size());
        for (Record record : records.values()) {
            GatewayConnection gateway = record.gateway;
            long down = gateway == null ? 0 : gateway.downloadBytes();
            long up = gateway == null ? 0 : gateway.uploadBytes();
            long elapsed = now - record.previousAt;
            if (elapsed >= 500) {
                record.downRate = Math.max(0, (down - record.previousDown) * 1_000L / elapsed);
                record.upRate = Math.max(0, (up - record.previousUp) * 1_000L / elapsed);
                record.previousDown = down;
                record.previousUp = up;
                record.previousAt = now;
            }
            boolean finished = gateway != null && gateway.finished();
            String status;
            if (gateway == null) status = "SEEN";
            else if (gateway.status() == GatewayConnection.Status.FAILED || !record.failure.isBlank()) status = "FAILED";
            else if (record.outbound.equals("block") || record.outbound.endsWith("perroute-block")) status = "BLOCKED";
            else if (finished) status = down == 0 && up > 0 ? "NO_RESPONSE" : "CLOSED";
            else if (gateway.status() == GatewayConnection.Status.CONNECTING) status = "CONNECTING";
            else status = "ACTIVE";
            String failure = !record.failure.isBlank() ? record.failure : gateway == null ? "" : gateway.failure();
            result.add(new Observation(record.id,
                    gateway != null ? gateway.applicationPath() : record.applicationPath,
                    record.inboundTag,
                    gateway != null ? gateway.protocol().name() : record.protocol,
                    gateway != null ? gateway.destinationHost() : record.host,
                    gateway != null ? gateway.destinationPort() : record.port,
                    record.domain, record.outbound, record.ruleTag, record.ruleObserved, status, failure,
                    gateway != null ? gateway.openedAt() : record.createdAt,
                    gateway != null ? gateway.lastActivityAt() : record.lastSeenAt,
                    gateway != null ? gateway.closedAt() : 0L,
                    down, up, finished ? 0 : record.downRate, finished ? 0 : record.upRate,
                    gateway != null, gateway != null ? gateway.udpDestinations() : 0));
        }
        result.sort(Comparator.comparingLong(Observation::lastActivityAt).reversed());
        return result;
    }

    public synchronized int size() { return records.size(); }

    private void prune(long now) {
        records.values().removeIf(record -> {
            boolean expired = record.gateway == null
                    ? now - record.lastSeenAt > CORE_ONLY_RETENTION_MILLIS
                    : record.gateway.finished() && now - record.gateway.closedAt() > CLOSED_RETENTION_MILLIS;
            if (expired) sessionOfRecord.remove(record.id);
            return expired;
        });
        pending.removeIf(entry -> now - entry.at() > 10_000L);
    }

    /** Drops the oldest finished records first, then the oldest of all, so the table never grows unbounded. */
    private void enforceLimit() {
        if (records.size() <= MAX_RECORDS) return;
        Iterator<Record> iterator = records.values().iterator();
        while (records.size() > MAX_RECORDS && iterator.hasNext()) {
            Record record = iterator.next();
            if (record.gateway == null || record.gateway.finished()) { iterator.remove(); sessionOfRecord.remove(record.id); }
        }
        iterator = records.values().iterator();
        while (records.size() > MAX_RECORDS && iterator.hasNext()) {
            Record record = iterator.next();
            iterator.remove();
            sessionOfRecord.remove(record.id);
        }
    }
}
