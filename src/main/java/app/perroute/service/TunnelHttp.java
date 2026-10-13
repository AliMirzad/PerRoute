package app.perroute.service;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class TunnelHttp {
    private TunnelHttp() {}

    public record Route(String proxyHost, int proxyPort) {
        public static Route direct() { return new Route("", 0); }
        public static Route socks(int port) { return new Route("127.0.0.1", port); }
        public boolean proxied() { return proxyPort > 0; }
    }

    public static Socket openGet(Route route, String host, String path, int timeoutMillis) throws IOException {
        Socket raw = new Socket();
        try {
            if (route.proxied()) {
                raw.connect(new InetSocketAddress(route.proxyHost(), route.proxyPort()), 2_000);
                raw.setSoTimeout(timeoutMillis);
                socksConnect(raw, host, 443);
            } else {
                raw.connect(new InetSocketAddress(host, 443), timeoutMillis);
                raw.setSoTimeout(timeoutMillis);
            }
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, 443, true);
            tls.setSoTimeout(timeoutMillis);
            tls.startHandshake();
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n"
                    + "User-Agent: PerRoute\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
            tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            tls.getOutputStream().flush();
            return tls;
        } catch (IOException | RuntimeException error) {
            try { raw.close(); } catch (IOException ignored) {}
            throw error;
        }
    }

    public static String getText(Route route, String host, String path, int timeoutMillis) throws IOException {
        try (Socket socket = openGet(route, host, path, timeoutMillis)) {
            InputStream input = socket.getInputStream();
            int status = readHeaders(input);
            if (status < 200 || status >= 300) throw new IOException(host + " answered HTTP " + status);
            return new String(input.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        }
    }

    public static int readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int value = input.read();
            if (value < 0) throw new EOFException("The server closed the connection before answering.");
            head.write(value);
            boolean expectsCarriageReturn = matched % 2 == 0;
            if (value == (expectsCarriageReturn ? '\r' : '\n')) matched++;
            else matched = value == '\r' ? 1 : 0;
            if (head.size() > 32 * 1024) throw new IOException("The response headers are too large.");
        }
        String statusLine = head.toString(StandardCharsets.US_ASCII).lines().findFirst().orElse("");
        String[] parts = statusLine.split(" ");
        try { return Integer.parseInt(parts.length > 1 ? parts[1] : ""); }
        catch (NumberFormatException error) { throw new IOException("Unexpected response: " + statusLine); }
    }

    private static void socksConnect(Socket socket, String host, int port) throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        output.write(new byte[]{0x05, 0x01, 0x00});
        output.flush();
        byte[] greeting = readExactly(input, 2);
        if (greeting[0] != 0x05 || greeting[1] != 0x00) throw new IOException("The local SOCKS proxy rejected negotiation.");

        byte[] domain = host.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.writeBytes(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) domain.length});
        request.writeBytes(domain);
        request.write((port >>> 8) & 0xff);
        request.write(port & 0xff);
        output.write(request.toByteArray());
        output.flush();

        byte[] response = readExactly(input, 4);
        if (response[0] != 0x05 || response[1] != 0x00) {
            throw new IOException("The proxy could not reach " + host + " (SOCKS " + (response[1] & 0xff) + ").");
        }
        switch (response[3] & 0xff) {
            case 0x01 -> readExactly(input, 4);
            case 0x03 -> readExactly(input, readExactly(input, 1)[0] & 0xff);
            case 0x04 -> readExactly(input, 16);
            default -> throw new IOException("The SOCKS proxy returned an invalid address type.");
        }
        readExactly(input, 2);
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] data = input.readNBytes(length);
        if (data.length != length) throw new EOFException("The proxy closed the connection early.");
        return data;
    }
}
