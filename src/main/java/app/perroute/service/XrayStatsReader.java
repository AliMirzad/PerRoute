package app.perroute.service;

import app.perroute.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class XrayStatsReader {
    @SuppressWarnings("unchecked")
    public Map<String, Long> readInboundCounters() throws IOException, InterruptedException {
        Path executable = AppDirectories.coreDirectory().resolve("xray.exe");
        if (!Files.isRegularFile(executable)) throw new IOException("Xray is not installed");
        Process process = new ProcessBuilder(
                executable.toString(), "api", "statsquery",
                "--server=127.0.0.1:" + XrayConfigGenerator.STATS_API_PORT,
                "-pattern", "inbound>>>", "-reset", "false")
                .directory(AppDirectories.coreDirectory().toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try { process.getInputStream().transferTo(output); } catch (IOException ignored) {}
        });
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Xray statistics timed out");
        }
        drain.join(1_000);
        String text = output.toString(StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) throw new IOException(text.isBlank() ? "Xray statistics failed" : text);

        Map<String, Object> root = Json.object(text);
        Map<String, Long> result = new LinkedHashMap<>();
        Object rawStats = root.get("stat");
        if (!(rawStats instanceof List<?> stats)) return result;
        for (Object raw : stats) {
            if (!(raw instanceof Map<?, ?> entry)) continue;
            Object rawName = entry.get("name");
            String name = rawName == null ? "" : String.valueOf(rawName);
            if (name.isBlank() || name.contains(">>>perroute-api>>>")) continue;
            Object rawValue = entry.get("value");
            long value = rawValue instanceof Number number ? number.longValue() : 0L;
            result.put(name, Math.max(0L, value));
        }
        return result;
    }
}
