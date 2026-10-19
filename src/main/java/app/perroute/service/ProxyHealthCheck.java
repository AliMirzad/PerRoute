package app.perroute.service;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class ProxyHealthCheck {
    private static final String[][] TARGETS = {
            {"cp.cloudflare.com", "/generate_204"},
            {"www.gstatic.com", "/generate_204"},
            {"www.msftconnecttest.com", "/connecttest.txt"}
    };

    public Result check() {
        return check("127.0.0.1", AppProxyLauncher.SOCKS_PORT);
    }

    public Result check(String proxyHost, int proxyPort) {
        String lastError = "The local proxy did not become ready.";
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(6).toNanos();
        do {
            for (String[] target : TARGETS) {
                try {
                    int status = probe(proxyHost, proxyPort, target[0], target[1]);
                    return new Result(true, "Tunnel answered from " + target[0] + " (HTTP " + status + ")");
                } catch (IOException e) {
                    lastError = readableMessage(e);
                }
                if (System.nanoTime() >= deadline) break;
            }
            try { Thread.sleep(150); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(false, "The health check was interrupted.");
            }
        } while (System.nanoTime() < deadline);
        return new Result(false, lastError);
    }

    private int probe(String proxyHost, int proxyPort, String targetHost, String path) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), 1_500);
            socket.setSoTimeout(4_000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            output.write(new byte[]{0x05, 0x01, 0x00});
            output.flush();
            byte[] greeting = readExactly(input, 2);
            if (greeting[0] != 0x05 || greeting[1] != 0x00) {
                throw new IOException("The local SOCKS proxy rejected negotiation.");
            }

            byte[] domain = targetHost.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.writeBytes(new byte[]{0x05, 0x01, 0x00, 0x03, (byte) domain.length});
            request.writeBytes(domain);
            request.write(0);
            request.write(80);
            output.write(request.toByteArray());
            output.flush();

            byte[] response = readExactly(input, 4);
            if (response[0] != 0x05 || response[1] != 0x00) {
                throw new IOException("The proxy could not reach the test endpoint (SOCKS " + (response[1] & 0xff) + ").");
            }
            skipSocksAddress(input, response[3]);
            readExactly(input, 2);

            String http = "HEAD " + path + " HTTP/1.1\r\nHost: " + targetHost + "\r\nUser-Agent: PerRoute\r\nConnection: close\r\n\r\n";
            try {
                output.write(http.getBytes(StandardCharsets.US_ASCII));
                output.flush();
            } catch (IOException closed) {
                throw new IOException(noAnswer(targetHost));
            }
            return statusCode(input, targetHost);
        }
    }

    private static String noAnswer(String host) {
        return "The tunnel closed without an answer from " + host + "; the server may be unreachable.";
    }

    private static int statusCode(InputStream input, String host) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        try {
            while ((value = input.read()) >= 0 && value != '\n' && line.size() < 256) line.write(value);
        } catch (java.net.SocketTimeoutException timeout) {
            throw new IOException("The server did not answer through the tunnel in time.");
        } catch (IOException closed) {
            if (line.size() == 0) throw new IOException(noAnswer(host));
            throw closed;
        }
        String text = line.toString(StandardCharsets.US_ASCII).trim();
        if (text.isEmpty()) throw new IOException(noAnswer(host));
        String[] parts = text.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("Unexpected answer through the tunnel from " + host + ".");
        try { return Integer.parseInt(parts[1]); }
        catch (NumberFormatException invalid) { throw new IOException("Unexpected answer through the tunnel from " + host + "."); }
    }

    private static void skipSocksAddress(InputStream input, byte addressType) throws IOException {
        switch (addressType & 0xff) {
            case 0x01 -> readExactly(input, 4);
            case 0x03 -> readExactly(input, readExactly(input, 1)[0] & 0xff);
            case 0x04 -> readExactly(input, 16);
            default -> throw new IOException("The SOCKS proxy returned an invalid address type.");
        }
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] data = input.readNBytes(length);
        if (data.length != length) throw new EOFException("The proxy closed the health-check connection early.");
        return data;
    }

    private static String readableMessage(IOException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record Result(boolean success, String detail) {}
}
