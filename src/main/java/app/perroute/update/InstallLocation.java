package app.perroute.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the running PerRoute is installed, and the previous version the installer keeps beside it. The installer
 * moves the replaced installation to {@code <install folder>.rollback} and records its version there, so going
 * back never needs a download.
 */
public final class InstallLocation {
    public static final String ROLLBACK_SUFFIX = ".rollback";
    public static final String VERSION_FILE = "perroute-version.txt";
    public static final String RECOVERY_TOOL = "PerRouteRecovery.exe";

    private final Path root;

    public InstallLocation(Path root) { this.root = root; }

    /** The folder of the running PerRoute.exe; empty when running from source. */
    public static Optional<InstallLocation> current() {
        return ProcessHandle.current().info().command().map(Path::of)
                .filter(path -> path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase("PerRoute.exe"))
                .map(Path::getParent).map(InstallLocation::new);
    }

    public Path root() { return root; }

    public Path rollbackDirectory() { return root.resolveSibling(root.getFileName() + ROLLBACK_SUFFIX); }

    public Path recoveryTool() { return root.resolve(RECOVERY_TOOL); }

    /** The version kept for a rollback, or empty when none is kept (first installation, or already rolled back). */
    public Optional<String> rollbackVersion() {
        Path directory = rollbackDirectory();
        if (!Files.isRegularFile(directory.resolve("PerRoute.exe"))) return Optional.empty();
        try {
            String version = Files.readString(directory.resolve(VERSION_FILE), StandardCharsets.UTF_8).strip();
            return Optional.of(version.isEmpty() ? "the previous version" : version);
        } catch (IOException missing) {
            return Optional.of("the previous version");
        }
    }
}
