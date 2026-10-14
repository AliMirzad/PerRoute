package app.perroute.gateway;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * A small loopback relay between the per-app driver and Xray, one listener per selected application:
 * ProxiFyre sends an application's traffic to its listener, and the gateway forwards it unchanged to that
 * application's own Xray inbound. Because every connection of an application passes the same listener, the
 * gateway can enforce one shared download and upload limit per application and see each connection's
 * destination and byte counts. It understands just enough SOCKS5 to read the destination and to carry UDP
 * associations through its own UDP relay; it never reads, stores or changes payload.
 *
 * <p>Routing is untouched: Xray still decides every connection's server, rule and direct/block outcome.
 */
public final class AppGateway implements AutoCloseable {
    /** Upper bound of simultaneous connections per application, so memory stays bounded. */
    public static final int MAX_CONNECTIONS_PER_APP = 4_096;
    static final int BUFFER_SIZE = 16 * 1024;
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    /** One application: the port ProxiFyre uses and the Xray inbound behind it. */
    public record Route(String applicationPath, int applicationIndex, int listenPort, int upstreamPort) {}

    /** Totals of one application since the gateway started. */
    public record Counters(long downloadBytes, long uploadBytes, int openConnections, long rejectedConnections) {}

    private final Consumer<GatewayConnection> onOpen;
    private final Consumer<String> log;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Map<String, long[]> limits = new ConcurrentHashMap<>();
    private volatile boolean running;

    public AppGateway(Consumer<GatewayConnection> onOpen, Consumer<String> log) {
        this.onOpen = onOpen == null ? connection -> { } : onOpen;
        this.log = log == null ? line -> { } : log;
    }

    public boolean running() { return running; }

    /**
     * Opens every listener. Either all of them are bound or none stays open, so a partial start can never
     * leave an application pointing at a port nobody listens on.
     */
    public synchronized void start(List<Route> routes) throws IOException {
        stop();
        List<Channel> started = new ArrayList<>();
        try {
            for (Route route : routes) {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(false);
                try {
                    server.bind(new InetSocketAddress(LOOPBACK, route.listenPort()), 256);
                } catch (IOException bindError) {
                    server.close();
                    throw new IOException("The traffic gateway could not listen on 127.0.0.1:" + route.listenPort()
                            + " for " + route.applicationPath() + ": " + bindError.getMessage(), bindError);
                }
                Channel channel = new Channel(route, server);
                long[] limit = limits.get(key(route.applicationPath()));
                if (limit != null) channel.setLimits(limit[0], limit[1]);
                started.add(channel);
            }
        } catch (IOException error) {
            started.forEach(Channel::close);
            throw error;
        }
        started.forEach(channel -> {
            channels.put(key(channel.route.applicationPath()), channel);
            Thread.ofVirtual().name("perroute-gateway-accept").start(channel::acceptLoop);
        });
        running = true;
    }

    /** Closes every listener and every relayed connection. */
    public synchronized void stop() {
        running = false;
        channels.values().forEach(Channel::close);
        channels.clear();
    }

    @Override public void close() { stop(); }

    /** Sets an application's limits in bytes per second (0 = unlimited). Applies to open connections at once. */
    public void setLimits(String applicationPath, long downloadBytesPerSecond, long uploadBytesPerSecond) {
        String key = key(applicationPath);
        if (downloadBytesPerSecond <= 0 && uploadBytesPerSecond <= 0) limits.remove(key);
        else limits.put(key, new long[]{Math.max(0, downloadBytesPerSecond), Math.max(0, uploadBytesPerSecond)});
        Channel channel = channels.get(key);
        if (channel != null) channel.setLimits(downloadBytesPerSecond, uploadBytesPerSecond);
    }

    /** True when the application has a listener right now, so a limit set for it is enforced. */
    public boolean carries(String applicationPath) {
        return running && channels.containsKey(key(applicationPath));
    }

    public Map<String, Counters> counters() {
        Map<String, Counters> result = new LinkedHashMap<>();
        channels.values().forEach(channel -> result.put(channel.route.applicationPath(), channel.counters()));
        return result;
    }

    public Counters counters(String applicationPath) {
        Channel channel = channels.get(key(applicationPath));
        return channel == null ? new Counters(0, 0, 0, 0) : channel.counters();
    }

    private static String key(String path) { return path == null ? "" : path.toLowerCase(java.util.Locale.ROOT); }

    /** The listener, limiters and live connections of one application. */
    private final class Channel {
        final Route route;
        final ServerSocket server;
        final RateLimiter download = new RateLimiter();
        final RateLimiter upload = new RateLimiter();
        final LongAdder downloadTotal = new LongAdder();
        final LongAdder uploadTotal = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final AtomicInteger open = new AtomicInteger();
        final Set<Relay> relays = ConcurrentHashMap.newKeySet();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean warnedFull = new AtomicBoolean();

        Channel(Route route, ServerSocket server) { this.route = route; this.server = server; }

        void setLimits(long down, long up) { download.setRate(down); upload.setRate(up); }

        Counters counters() { return new Counters(downloadTotal.sum(), uploadTotal.sum(), open.get(), rejected.sum()); }

        void acceptLoop() {
            while (!closed.get()) {
                Socket client;
                try { client = server.accept(); }
                catch (IOException error) {
                    if (!closed.get()) log.accept("Traffic gateway stopped accepting for " + route.applicationPath() + ": " + error.getMessage());
                    return;
                }
                if (open.get() >= MAX_CONNECTIONS_PER_APP) {
                    rejected.increment();
                    if (warnedFull.compareAndSet(false, true)) {
                        log.accept("Traffic gateway: " + route.applicationPath() + " reached " + MAX_CONNECTIONS_PER_APP + " simultaneous connections; new ones are refused.");
                    }
                    closeQuietly(client);
                    continue;
                }
                Relay relay = new Relay(this, client);
                relays.add(relay);
                open.incrementAndGet();
                Thread.ofVirtual().name("perroute-gateway-relay").start(relay::run);
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            closeQuietly(server);
            relays.forEach(Relay::abort);
        }
    }

    /** One relayed connection: SOCKS5 negotiation, then TCP piping or a UDP association. */
    private final class Relay {
        final Channel channel;
        final Socket client;
        final Socket upstream = new Socket();
        final GatewayConnection connection;
        volatile DatagramSocket udpClient;
        volatile DatagramSocket udpUpstream;

        Relay(Channel channel, Socket client) {
            this.channel = channel;
            this.client = client;
            this.connection = new GatewayConnection(channel.route.applicationPath(), channel.route.applicationIndex(), System.currentTimeMillis());
        }

        void run() {
            try {
                client.setTcpNoDelay(true);
                upstream.setTcpNoDelay(true);
                upstream.connect(new InetSocketAddress(LOOPBACK, channel.route.upstreamPort()), 3_000);
                connection.upstreamLocalPort(upstream.getLocalPort());
                negotiate();
            } catch (java.net.ConnectException | java.net.SocketTimeoutException unreachable) {
                connection.fail("The connection core did not accept the connection: " + unreachable.getMessage(), System.currentTimeMillis());
            } catch (EOFException | SocketException closed) {
                connection.close(System.currentTimeMillis());
            } catch (IOException error) {
                connection.fail(error.getMessage(), System.currentTimeMillis());
            } catch (InterruptedException interrupted) {
                connection.close(System.currentTimeMillis());
            } finally {
                abort();
                connection.close(System.currentTimeMillis());
                channel.relays.remove(this);
                channel.open.decrementAndGet();
            }
        }

        private void negotiate() throws IOException, InterruptedException {
            InputStream fromClient = client.getInputStream();
            OutputStream toClient = client.getOutputStream();
            InputStream fromUpstream = upstream.getInputStream();
            OutputStream toUpstream = upstream.getOutputStream();

            int version = fromClient.read();
            if (version < 0) return;
            if (version != 5) {
                // Not SOCKS5: relay it unchanged. Routing and limits still apply, the destination stays unknown.
                connection.protocol(GatewayConnection.Protocol.OTHER);
                toUpstream.write(version);
                announce();
                pipe(fromClient, toUpstream, fromUpstream, toClient);
                return;
            }
            int methodCount = readByte(fromClient);
            byte[] methods = readExactly(fromClient, methodCount);
            toUpstream.write(join(new byte[]{5, (byte) methodCount}, methods));
            toUpstream.flush();
            byte[] chosen = readExactly(fromUpstream, 2);
            toClient.write(chosen);
            toClient.flush();
            if ((chosen[1] & 0xff) == 0xff) return;
            if ((chosen[1] & 0xff) == 0x02) {
                // Username and password are passed through untouched and never kept.
                ByteArrayOutputStream auth = new ByteArrayOutputStream();
                auth.write(readByte(fromClient));
                int userLength = readByte(fromClient);
                auth.write(userLength);
                auth.writeBytes(readExactly(fromClient, userLength));
                int passwordLength = readByte(fromClient);
                auth.write(passwordLength);
                auth.writeBytes(readExactly(fromClient, passwordLength));
                toUpstream.write(auth.toByteArray());
                toUpstream.flush();
                byte[] result = readExactly(fromUpstream, 2);
                toClient.write(result);
                toClient.flush();
                if (result[1] != 0) return;
            }

            byte[] head = readExactly(fromClient, 4);
            Address destination = readAddress(fromClient, head[3]);
            int command = head[1] & 0xff;
            connection.protocol(command == 3 ? GatewayConnection.Protocol.UDP : command == 1 ? GatewayConnection.Protocol.TCP : GatewayConnection.Protocol.OTHER);
            if (command != 3) connection.destination(destination.host(), destination.port());
            toUpstream.write(join(head, destination.raw()));
            toUpstream.flush();

            byte[] replyHead = readExactly(fromUpstream, 4);
            Address bound = readAddress(fromUpstream, replyHead[3]);
            int reply = replyHead[1] & 0xff;
            announce();
            if (reply != 0) {
                toClient.write(join(replyHead, bound.raw()));
                toClient.flush();
                connection.fail("SOCKS " + reply + ": " + replyText(reply), System.currentTimeMillis());
                return;
            }
            if (command == 3) {
                associate(bound, fromClient, toClient, fromUpstream);
                return;
            }
            toClient.write(join(replyHead, bound.raw()));
            toClient.flush();
            connection.opened();
            pipe(fromClient, toUpstream, fromUpstream, toClient);
        }

        private void announce() {
            try { onOpen.accept(connection); } catch (RuntimeException ignored) { }
        }

        /** Copies both directions until both have ended, with half-close, limits and byte counts. */
        private void pipe(InputStream fromClient, OutputStream toUpstream, InputStream fromUpstream, OutputStream toClient)
                throws InterruptedException {
            Thread downstream = Thread.ofVirtual().name("perroute-gateway-down").start(() ->
                    copy(fromUpstream, toClient, client, channel.download, true));
            copy(fromClient, toUpstream, upstream, channel.upload, false);
            downstream.join();
        }

        private void copy(InputStream in, OutputStream out, Socket target, RateLimiter limiter, boolean download) {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    limiter.acquire(read);
                    out.write(buffer, 0, read);
                    out.flush();
                    long now = System.currentTimeMillis();
                    if (download) { connection.addDownload(read, now); channel.downloadTotal.add(read); }
                    else { connection.addUpload(read, now); channel.uploadTotal.add(read); }
                }
                try { target.shutdownOutput(); } catch (IOException ignored) { }
            } catch (IOException | InterruptedException error) {
                // One side broke: end the whole connection so the other direction cannot hang.
                abort();
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        /**
         * UDP: Xray's association is kept, but the application is given the gateway's own UDP port, so every
         * datagram passes the same limits. Datagrams are only accepted from the loopback address.
         */
        private void associate(Address bound, InputStream fromClient, OutputStream toClient, InputStream fromUpstream)
                throws IOException, InterruptedException {
            InetAddress upstreamHost = bound.address() == null || bound.address().isAnyLocalAddress() ? LOOPBACK : bound.address();
            InetSocketAddress upstreamTarget = new InetSocketAddress(upstreamHost, bound.port());
            udpClient = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
            udpUpstream = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
            connection.udpLocalPort(udpUpstream.getLocalPort());
            byte[] port = {(byte) (udpClient.getLocalPort() >>> 8), (byte) udpClient.getLocalPort()};
            toClient.write(join(new byte[]{5, 0, 0, 1}, LOOPBACK.getAddress(), port));
            toClient.flush();
            connection.opened();

            InetSocketAddress[] clientAddress = new InetSocketAddress[1];
            Set<String> destinations = new HashSet<>();
            Thread outgoing = Thread.ofVirtual().name("perroute-gateway-udp-up").start(() -> {
                byte[] buffer = new byte[65_535];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (true) {
                        packet.setLength(buffer.length);
                        udpClient.receive(packet);
                        if (!packet.getAddress().isLoopbackAddress()) continue;
                        clientAddress[0] = (InetSocketAddress) packet.getSocketAddress();
                        UdpHeader header = UdpHeader.parse(buffer, packet.getLength());
                        if (header == null || header.fragment() != 0) continue;
                        if (destinations.size() < 1_024 && destinations.add(header.host() + ":" + header.port())) {
                            connection.udpDestinations(destinations.size());
                        }
                        connection.destination(header.host(), header.port());
                        int payload = packet.getLength() - header.length();
                        channel.upload.acquire(packet.getLength());
                        udpUpstream.send(new DatagramPacket(buffer, packet.getLength(), upstreamTarget));
                        connection.addUpload(payload, System.currentTimeMillis());
                        channel.uploadTotal.add(payload);
                    }
                } catch (IOException | InterruptedException ended) {
                    abort();
                }
            });
            Thread incoming = Thread.ofVirtual().name("perroute-gateway-udp-down").start(() -> {
                byte[] buffer = new byte[65_535];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    while (true) {
                        packet.setLength(buffer.length);
                        udpUpstream.receive(packet);
                        if (!upstreamTarget.equals(packet.getSocketAddress()) || clientAddress[0] == null) continue;
                        UdpHeader header = UdpHeader.parse(buffer, packet.getLength());
                        int payload = packet.getLength() - (header == null ? 0 : header.length());
                        channel.download.acquire(packet.getLength());
                        udpClient.send(new DatagramPacket(buffer, packet.getLength(), clientAddress[0]));
                        connection.addDownload(payload, System.currentTimeMillis());
                        channel.downloadTotal.add(payload);
                    }
                } catch (IOException | InterruptedException ended) {
                    abort();
                }
            });
            // The association lives as long as its TCP control connection, from either side.
            Thread watcher = Thread.ofVirtual().name("perroute-gateway-udp-watch").start(() -> {
                try { while (fromUpstream.read() >= 0) { } } catch (IOException ignored) { }
                abort();
            });
            try { while (fromClient.read() >= 0) { } } catch (IOException ignored) { }
            abort();
            outgoing.join();
            incoming.join();
            watcher.join();
        }

        void abort() {
            closeQuietly(client);
            closeQuietly(upstream);
            DatagramSocket a = udpClient, b = udpUpstream;
            if (a != null) a.close();
            if (b != null) b.close();
        }
    }

    /**
     * A SOCKS5 address as it appeared on the wire after its type byte (which belongs to the 4-byte header that
     * precedes it), plus its readable form.
     */
    record Address(byte[] raw, String host, int port, InetAddress address) {}

    static Address readAddress(InputStream in, byte type) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        String host;
        InetAddress address = null;
        switch (type & 0xff) {
            case 0x01 -> {
                byte[] bytes = readExactly(in, 4);
                raw.writeBytes(bytes);
                address = InetAddress.getByAddress(bytes);
                host = address.getHostAddress();
            }
            case 0x03 -> {
                int length = readByte(in);
                byte[] bytes = readExactly(in, length);
                raw.write(length);
                raw.writeBytes(bytes);
                host = new String(bytes, StandardCharsets.US_ASCII);
            }
            case 0x04 -> {
                byte[] bytes = readExactly(in, 16);
                raw.writeBytes(bytes);
                address = InetAddress.getByAddress(bytes);
                host = address.getHostAddress();
            }
            default -> throw new IOException("Unsupported SOCKS address type " + (type & 0xff));
        }
        byte[] port = readExactly(in, 2);
        raw.writeBytes(port);
        return new Address(raw.toByteArray(), host, ((port[0] & 0xff) << 8) | (port[1] & 0xff), address);
    }

    /** The header of a SOCKS5 UDP datagram. */
    record UdpHeader(int fragment, String host, int port, int length) {
        static UdpHeader parse(byte[] data, int length) {
            if (length < 10) return null;
            int fragment = data[2] & 0xff;
            int type = data[3] & 0xff;
            try {
                return switch (type) {
                    case 0x01 -> new UdpHeader(fragment, InetAddress.getByAddress(java.util.Arrays.copyOfRange(data, 4, 8)).getHostAddress(),
                            ((data[8] & 0xff) << 8) | (data[9] & 0xff), 10);
                    case 0x03 -> {
                        int size = data[4] & 0xff;
                        if (length < 7 + size) yield null;
                        yield new UdpHeader(fragment, new String(data, 5, size, StandardCharsets.US_ASCII),
                                ((data[5 + size] & 0xff) << 8) | (data[6 + size] & 0xff), 7 + size);
                    }
                    case 0x04 -> length < 22 ? null : new UdpHeader(fragment,
                            InetAddress.getByAddress(java.util.Arrays.copyOfRange(data, 4, 20)).getHostAddress(),
                            ((data[20] & 0xff) << 8) | (data[21] & 0xff), 22);
                    default -> null;
                };
            } catch (IOException invalid) {
                return null;
            }
        }
    }

    static String replyText(int code) {
        return switch (code) {
            case 1 -> "general failure";
            case 2 -> "not allowed by the ruleset";
            case 3 -> "network unreachable";
            case 4 -> "host unreachable";
            case 5 -> "connection refused";
            case 6 -> "TTL expired";
            case 7 -> "command not supported";
            case 8 -> "address type not supported";
            default -> "unknown error";
        };
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new EOFException("Connection closed during SOCKS negotiation");
        return value;
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = in.readNBytes(length);
        if (data.length != length) throw new EOFException("Connection closed during SOCKS negotiation");
        return data;
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try { closeable.close(); } catch (IOException ignored) { }
    }
}
