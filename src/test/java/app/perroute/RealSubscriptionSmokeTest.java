package app.perroute;

import app.perroute.parser.SubscriptionParser;
import app.perroute.service.AppDirectories;
import app.perroute.service.XrayConfigGenerator;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class RealSubscriptionSmokeTest {
    public static void main(String[] args) throws Exception {
        Path source = Path.of("build", "subscription-body.bin").toAbsolutePath();
        var result = new SubscriptionParser().parse(Files.readString(source, StandardCharsets.UTF_8));
        if (result.nodes().size() != 82) throw new AssertionError("Expected 82 profiles, got " + result.nodes().size() + ": " + result.warnings());
        validate(result.nodes().get(0));
        validate(result.nodes().get(2));
        System.out.println("Real JSON subscription parsed 82 profiles; direct and balanced profiles passed Xray validation.");
    }

    private static void validate(app.perroute.model.ProxyNode node) throws Exception {
        String generated = new XrayConfigGenerator().generateApplicationProxy(node);
        Path file = Path.of("build", "real-subscription-smoke.json").toAbsolutePath();
        Files.writeString(file, generated, StandardCharsets.UTF_8);
        Path core = AppDirectories.coreDirectory();
        Process process = new ProcessBuilder(core.resolve("xray.exe").toString(), "run", "-test", "-config", file.toString())
                .directory(core.toFile()).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = Thread.ofPlatform().daemon().start(() -> {
            try { process.getInputStream().transferTo(captured); } catch (java.io.IOException ignored) {}
        });
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Xray timed out"); }
        drain.join(2_000);
        String output = captured.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new AssertionError(output);
        Files.deleteIfExists(file);
    }
}
