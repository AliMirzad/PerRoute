package app.perroute.service;

import app.perroute.util.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class CoreInstaller {
    private static final URI RELEASE_API = URI.create("https://api.github.com/repos/XTLS/Xray-core/releases/latest");
    private static final URI WINTUN_ZIP = URI.create("https://www.wintun.net/builds/wintun-0.14.1.zip");
    private static final int MAX_DOWNLOAD_BYTES = 180 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CoreInfo inspect() {
        Path core = AppDirectories.coreDirectory();
        return new CoreInfo(Files.isRegularFile(core.resolve("xray.exe")),
                Files.isRegularFile(core.resolve("wintun.dll")), core);
    }

    public CoreInfo install(Consumer<String> progress) throws IOException, InterruptedException {
        Path target = AppDirectories.coreDirectory();
        Files.createDirectories(target);
        progress.accept("Looking up the latest official Xray release…");
        Map<String, Object> release = Json.object(getText(RELEASE_API, "application/vnd.github+json"));
        URI xrayZip = findWindowsAsset(release);
        String version = String.valueOf(release.getOrDefault("tag_name", "latest"));

        progress.accept("Downloading Xray " + version + "…");
        byte[] xrayBytes = getBytes(xrayZip, "application/octet-stream");
        progress.accept("Downloading Wintun…");
        byte[] wintunBytes = getBytes(WINTUN_ZIP, "application/zip");

        Path stage = Files.createTempDirectory(target, ".install-");
        try {
            extractXray(xrayBytes, stage);
            extractWintun(wintunBytes, stage);
            require(stage.resolve("xray.exe"));
            require(stage.resolve("wintun.dll"));
            for (String file : List.of("xray.exe", "wintun.dll", "geoip.dat", "geosite.dat")) {
                Path source = stage.resolve(file);
                if (Files.isRegularFile(source)) {
                    Files.move(source, target.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(target.resolve("version.txt"), version + System.lineSeparator());
        } finally {
            deleteTree(stage);
        }
        progress.accept("The core is ready: " + version);
        return inspect();
    }

    @SuppressWarnings("unchecked")
    private static URI findWindowsAsset(Map<String, Object> release) throws IOException {
        Object raw = release.get("assets");
        if (!(raw instanceof List<?> assets)) throw new IOException("GitHub release has no assets");
        for (Object item : assets) {
            if (!(item instanceof Map<?, ?> asset)) continue;
            String name = String.valueOf(asset.get("name"));
            if (name.equalsIgnoreCase("Xray-windows-64.zip")) {
                return URI.create(String.valueOf(asset.get("browser_download_url")));
            }
        }
        throw new IOException("Official Windows x64 Xray package was not found");
    }

    private void extractXray(byte[] zip, Path stage) throws IOException {
        List<String> wanted = List.of("xray.exe", "geoip.dat", "geosite.dat");
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = Path.of(entry.getName()).getFileName().toString().toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && wanted.contains(name)) Files.copy(input, stage.resolve(name));
            }
        }
    }

    private void extractWintun(byte[] zip, Path stage) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String normalized = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && normalized.endsWith("/bin/amd64/wintun.dll")) {
                    Files.copy(input, stage.resolve("wintun.dll"));
                    return;
                }
            }
        }
        throw new IOException("wintun.dll was not found in the official archive");
    }

    private String getText(URI uri, String accept) throws IOException, InterruptedException {
        return new String(getBytes(uri, accept), java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] getBytes(URI uri, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "PerRoute/0.1")
                .header("Accept", accept).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed with HTTP " + response.statusCode());
        }
        if (response.body().length > MAX_DOWNLOAD_BYTES) throw new IOException("Download is unexpectedly large");
        return response.body();
    }

    private static void require(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Missing required file: " + file.getFileName());
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public record CoreInfo(boolean xrayPresent, boolean wintunPresent, Path directory) {
        public boolean ready() { return xrayPresent && wintunPresent; }
    }
}
