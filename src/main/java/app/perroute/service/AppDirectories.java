package app.perroute.service;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URISyntaxException;

public final class AppDirectories {
    private AppDirectories() {}

    public static Path dataDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local, "PerRoute");
        return Path.of(System.getProperty("user.home"), ".perroute");
    }

    public static Path coreDirectory() {
        String configured = System.getProperty("perroute.home");
        if (configured != null && !configured.isBlank()) return Path.of(configured, "tools", "xray").toAbsolutePath().normalize();
        String resources = System.getProperty("compose.application.resources.dir");
        if (resources != null && !resources.isBlank()) {
            Path bundled = Path.of(resources, "xray").toAbsolutePath().normalize();
            if (Files.isDirectory(bundled)) return bundled;
        }
        try {
            Path location = Path.of(AppDirectories.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location) && location.getFileName().toString().endsWith(".jar")) {
                Path appDirectory = location.getParent();
                Path packagedRoot = appDirectory == null ? null : appDirectory.getParent();
                if (packagedRoot != null && Files.isDirectory(packagedRoot.resolve("runtime"))) {
                    Path bundled = packagedRoot.resolve("tools").resolve("xray").toAbsolutePath().normalize();
                    if (Files.isDirectory(bundled)) return bundled;
                    return dataDirectory().resolve("tools").resolve("xray").toAbsolutePath().normalize();
                }
            }
        } catch (URISyntaxException | RuntimeException ignored) {
        }
        return Path.of(System.getProperty("user.dir"), "tools", "xray").toAbsolutePath().normalize();
    }
}
