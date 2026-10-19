package app.perroute.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class QuickRouteRequests {
    public static final String ROUTE_ARGUMENT = "--route";
    private static final long MAX_AGE_MILLIS = 10 * 60_000L;
    private static final int MAX_BYTES = 4_096;
    private static final int MAX_PER_TAKE = 8;
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

    public enum Kind { SHOW, ROUTE }

    public record Request(Kind kind, String target) {}

    private final Path directory;

    public QuickRouteRequests(Path dataDirectory) {
        this.directory = dataDirectory.resolve("requests");
    }

    public void submitRoute(String target) throws IOException {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("No application was given");
        write("route\n" + target.trim());
    }

    public void submitShow() throws IOException {
        write("show\n");
    }

    public synchronized List<Request> take() {
        List<Request> requests = new ArrayList<>();
        if (!Files.isDirectory(directory)) return requests;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "request-*.txt")) {
            stream.forEach(files::add);
        } catch (IOException error) {
            return requests;
        }
        files.sort(null);
        long now = System.currentTimeMillis();
        for (Path file : files) {
            try {
                boolean fresh = now - Files.getLastModifiedTime(file).toMillis() <= MAX_AGE_MILLIS;
                boolean small = Files.size(file) <= MAX_BYTES;
                String text = fresh && small ? Files.readString(file, StandardCharsets.UTF_8) : "";
                Files.deleteIfExists(file);
                Request request = parse(text);
                if (request != null && requests.size() < MAX_PER_TAKE) requests.add(request);
            } catch (IOException ignored) {
            }
        }
        return requests;
    }

    public static Request parse(String text) {
        if (text == null || text.isBlank()) return null;
        String[] lines = text.split("\\R", 2);
        String kind = lines[0].trim();
        String target = lines.length > 1 ? lines[1].trim() : "";
        if (kind.equals("show")) return new Request(Kind.SHOW, "");
        if (kind.equals("route") && !target.isBlank() && !target.contains("\n")) return new Request(Kind.ROUTE, target);
        return null;
    }

    private void write(String text) throws IOException {
        Files.createDirectories(directory);
        String name = "request-" + String.format("%015d-%06d", System.currentTimeMillis(), SEQUENCE.incrementAndGet() % 1_000_000) + "-" + UUID.randomUUID();
        Path temporary = directory.resolve(name + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, directory.resolve(name + ".txt"), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, directory.resolve(name + ".txt"), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
