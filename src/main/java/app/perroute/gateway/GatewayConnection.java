package app.perroute.gateway;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One connection an application opened through the gateway. Only addressing and byte counts are kept,
 * never payload. The fields are updated by the relay threads and read by the inspector.
 */
public final class GatewayConnection {
    public enum Protocol { TCP, UDP, OTHER }
    public enum Status { CONNECTING, OPEN, CLOSED, FAILED }

    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    private final long id = NEXT_ID.getAndIncrement();
    private final String applicationPath;
    private final int applicationIndex;
    private final long openedAt;
    private final AtomicLong downloadBytes = new AtomicLong();
    private final AtomicLong uploadBytes = new AtomicLong();
    private volatile Protocol protocol = Protocol.OTHER;
    private volatile String destinationHost = "";
    private volatile int destinationPort;
    private volatile int upstreamLocalPort;
    private volatile int udpLocalPort;
    private volatile long lastActivityAt;
    private volatile long closedAt;
    private volatile Status status = Status.CONNECTING;
    private volatile String failure = "";
    private volatile int udpDestinations;

    GatewayConnection(String applicationPath, int applicationIndex, long openedAt) {
        this.applicationPath = applicationPath;
        this.applicationIndex = applicationIndex;
        this.openedAt = openedAt;
        this.lastActivityAt = openedAt;
    }

    public long id() { return id; }
    public String applicationPath() { return applicationPath; }
    public int applicationIndex() { return applicationIndex; }
    public long openedAt() { return openedAt; }
    public long downloadBytes() { return downloadBytes.get(); }
    public long uploadBytes() { return uploadBytes.get(); }
    public Protocol protocol() { return protocol; }
    public String destinationHost() { return destinationHost; }
    public int destinationPort() { return destinationPort; }
    /** The loopback port the gateway used towards Xray; Xray's access log names it, which links the two. */
    public int upstreamLocalPort() { return upstreamLocalPort; }
    /** For UDP: the loopback port datagrams are sent to Xray from; Xray's access log names it. */
    public int udpLocalPort() { return udpLocalPort; }
    public long lastActivityAt() { return lastActivityAt; }
    public long closedAt() { return closedAt; }
    public Status status() { return status; }
    public String failure() { return failure; }
    /** For UDP: how many different destinations the association has sent to. */
    public int udpDestinations() { return udpDestinations; }

    void protocol(Protocol value) { protocol = value; }
    void destination(String host, int port) { destinationHost = host == null ? "" : host; destinationPort = port; }
    void upstreamLocalPort(int value) { upstreamLocalPort = value; }
    void udpLocalPort(int value) { udpLocalPort = value; }
    void udpDestinations(int value) { udpDestinations = value; }
    void opened() { status = Status.OPEN; }

    void addDownload(long bytes, long now) { downloadBytes.addAndGet(bytes); lastActivityAt = now; }
    void addUpload(long bytes, long now) { uploadBytes.addAndGet(bytes); lastActivityAt = now; }

    void close(long now) {
        if (status == Status.CLOSED || status == Status.FAILED) return;
        closedAt = now;
        status = Status.CLOSED;
    }

    void fail(String reason, long now) {
        if (status == Status.CLOSED || status == Status.FAILED) return;
        failure = reason == null ? "" : reason;
        closedAt = now;
        status = Status.FAILED;
    }

    public boolean finished() { return status == Status.CLOSED || status == Status.FAILED; }
}
