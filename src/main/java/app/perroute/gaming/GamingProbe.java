package app.perroute.gaming;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Measures what a game feels: many small round trips in a row through one SOCKS port, over TCP and over UDP.
 *
 * <p>TCP: one kept-alive HTTP connection through the tunnel, with a tiny request repeated; every answer is one
 * round trip from this computer through the server to the target and back. UDP: a SOCKS5 UDP association
 * carrying DNS queries to a public resolver, which is the only widely available service that answers a single
 * datagram. A server without UDP support fails the UDP part, which matters for most games.
 */
public final class GamingProbe {
    /** Round trips per protocol. */
    public static final int SAMPLES = 20;
    private static final int TIMEOUT_MILLIS = 2_000;
    private static final int GAP_MILLIS = 60;

    private final String tcpHost;
    private final int tcpPort;
    private final InetSocketAddress udpTarget;

    public GamingProbe() { this("cp.cloudflare.com", 80, new InetSocketAddress("1.1.1.1", 53)); }

    /** Targets are configurable so tests can measure against servers on loopback. */
    public GamingProbe(String tcpHost, int tcpPort, InetSocketAddress udpTarget) {
        this.tcpHost = tcpHost;
        this.tcpPort = tcpPort;
        this.udpTarget = udpTarget;
    }

    /** Round trip times in milliseconds; -1 marks a lost sample. */
    public record Samples(List<Long> values, String error) {
        public int lost() { return (int) values.stream().filter(value -> value < 0).count(); }
        public List<Long> answered() { return values.stream().filter(value -> value >= 0).toList(); }
        public boolean failed() { return answered().isEmpty(); }
    }

    public record Result(Samples tcp, Samples udp) {}

    public Result measure(int socksPort) {
        return new Result(tcp(socksPort), udp(socksPort));
    }

    Samples tcp(int socksPort) {
        List<Long> values = new ArrayList<>();
        String error = "";
        Socket socket = null;
        try {
            for (int index = 0; index < SAMPLES; index++) {
                if (socket == null) {
                    socket = open(socksPort);
                    // The first answer on a new connection includes the server's dial; it is a warm-up, not a sample.
                    roundTrip(socket);
                }
                try {
                    values.add(roundTrip(socket));
                } catch (IOException lost) {
                    values.add(-1L);
                    error = message(lost);
                    close(socket);
                    socket = null;
                }
                pause();
            }
        } catch (IOException failed) {
            error = message(failed);
            while (values.size() < SAMPLES) values.add(-1L);
        } finally {
            close(socket);
        }
        return new Samples(List.copyOf(values), error);
    }

    private Socket open(int socksPort) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort), TIMEOUT_MILLIS);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            greet(socket.getInputStream(), socket.getOutputStream());
            OutputStream output = socket.getOutputStream();
            byte[] host = tcpHost.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.writeBytes(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) host.length});
            request.writeBytes(host);
            request.write((tcpPort >>> 8) & 0xff);
            request.write(tcpPort & 0xff);
            output.write(request.toByteArray());
            output.flush();
            readReply(socket.getInputStream());
            return socket;
        } catch (IOException error) {
            close(socket);
            throw error;
        }
    }

    /** One HEAD request on the open connection; returns the time until the whole response head arrived. */
    private long roundTrip(Socket socket) throws IOException {
        String request = "HEAD /generate_204 HTTP/1.1\r\nHost: " + tcpHost + "\r\nUser-Agent: PerRoute\r\nConnection: keep-alive\r\n\r\n";
        long started = System.nanoTime();
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        InputStream input = socket.getInputStream();
        int matched = 0;
        int read = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) throw new EOFException("The tunnel closed the connection.");
            if (++read > 16 * 1024) throw new IOException("The answer was too large.");
            boolean expectsReturn = matched % 2 == 0;
            if (value == (expectsReturn ? '\r' : '\n')) matched++;
            else matched = value == '\r' ? 1 : 0;
        }
        return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
    }

    Samples udp(int socksPort) {
        List<Long> values = new ArrayList<>();
        try (Socket control = new Socket(); DatagramSocket datagrams = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            control.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort), TIMEOUT_MILLIS);
            control.setSoTimeout(TIMEOUT_MILLIS);
            greet(control.getInputStream(), control.getOutputStream());
            // UDP ASSOCIATE from the address the datagrams will come from.
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.writeBytes(new byte[]{0x05, 0x03, 0x00, 0x01, 127, 0, 0, 1});
            int local = datagrams.getLocalPort();
            request.write((local >>> 8) & 0xff);
            request.write(local & 0xff);
            control.getOutputStream().write(request.toByteArray());
            control.getOutputStream().flush();
            InetSocketAddress relay = readReply(control.getInputStream());
            InetSocketAddress target = relay.getAddress().isAnyLocalAddress()
                    ? new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.getPort()) : relay;
            datagrams.setSoTimeout(TIMEOUT_MILLIS);
            for (int index = 0; index < SAMPLES; index++) {
                int id = ThreadLocalRandom.current().nextInt(1, 0xffff);
                byte[] packet = socksUdp(udpTarget, dnsQuery(id));
                long started = System.nanoTime();
                datagrams.send(new DatagramPacket(packet, packet.length, target));
                values.add(awaitAnswer(datagrams, id, started));
                pause();
            }
            return new Samples(List.copyOf(values), values.stream().allMatch(value -> value < 0)
                    ? "No UDP answers came back through this server; it may not carry UDP." : "");
        } catch (IOException error) {
            while (values.size() < SAMPLES) values.add(-1L);
            return new Samples(List.copyOf(values), "UDP could not be used through this server: " + message(error));
        }
    }

    /** Waits for the answer with this query id; answers to earlier, late queries are ignored. */
    private static long awaitAnswer(DatagramSocket datagrams, int id, long started) throws IOException {
        byte[] buffer = new byte[1500];
        long deadline = started + TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            DatagramPacket answer = new DatagramPacket(buffer, buffer.length);
            try {
                datagrams.receive(answer);
            } catch (SocketTimeoutException timeout) {
                return -1L;
            }
            int offset = socksHeaderLength(buffer, answer.getLength());
            if (offset < 0 || answer.getLength() - offset < 2) continue;
            int answeredId = ((buffer[offset] & 0xff) << 8) | (buffer[offset + 1] & 0xff);
            if (answeredId == id) return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
        }
        return -1L;
    }

    /** A DNS query for the A record of example.com with the given id. */
    static byte[] dnsQuery(int id) {
        ByteArrayOutputStream query = new ByteArrayOutputStream();
        query.writeBytes(new byte[]{(byte) (id >>> 8), (byte) id, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        for (String label : "example.com".split("\\.")) {
            query.write(label.length());
            query.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        }
        query.writeBytes(new byte[]{0x00, 0x00, 0x01, 0x00, 0x01});
        return query.toByteArray();
    }

    /** Wraps a datagram in the SOCKS5 UDP request header. */
    static byte[] socksUdp(InetSocketAddress destination, byte[] payload) {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.writeBytes(new byte[]{0x00, 0x00, 0x00});
        byte[] address = destination.getAddress().getAddress();
        packet.write(address.length == 4 ? 0x01 : 0x04);
        packet.writeBytes(address);
        packet.write((destination.getPort() >>> 8) & 0xff);
        packet.write(destination.getPort() & 0xff);
        packet.writeBytes(payload);
        return packet.toByteArray();
    }

    /** Length of the SOCKS5 UDP header of a received datagram, or -1 when it is not one. */
    static int socksHeaderLength(byte[] data, int length) {
        if (length < 4 || data[2] != 0x00) return -1;
        return switch (data[3]) {
            case 0x01 -> 10;
            case 0x04 -> 22;
            case 0x03 -> length > 4 ? 7 + (data[4] & 0xff) : -1;
            default -> -1;
        };
    }

    private static void greet(InputStream input, OutputStream output) throws IOException {
        output.write(new byte[]{0x05, 0x01, 0x00});
        output.flush();
        byte[] answer = input.readNBytes(2);
        if (answer.length != 2 || answer[0] != 0x05 || answer[1] != 0x00) throw new IOException("The local SOCKS port refused the connection.");
    }

    private static InetSocketAddress readReply(InputStream input) throws IOException {
        byte[] head = input.readNBytes(4);
        if (head.length != 4) throw new EOFException("The local SOCKS port closed the connection.");
        if (head[1] != 0x00) throw new IOException("The server refused the request (SOCKS " + (head[1] & 0xff) + ").");
        byte[] address = switch (head[3]) {
            case 0x01 -> input.readNBytes(4);
            case 0x04 -> input.readNBytes(16);
            case 0x03 -> input.readNBytes(input.read());
            default -> throw new IOException("The SOCKS answer was not understood.");
        };
        byte[] port = input.readNBytes(2);
        if (port.length != 2) throw new EOFException("The local SOCKS port closed the connection.");
        int portValue = ((port[0] & 0xff) << 8) | (port[1] & 0xff);
        InetAddress host = head[3] == 0x03 ? InetAddress.getByName(new String(address, StandardCharsets.US_ASCII)) : InetAddress.getByAddress(address);
        return new InetSocketAddress(host, portValue);
    }

    private static void pause() {
        try { Thread.sleep(GAP_MILLIS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static String message(IOException error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }
}
