package app.perroute.platform.bootstrap;

import app.perroute.backend.api.PlatformServices;
import app.perroute.platform.windows.WindowsConnectionBackend;
import app.perroute.service.AppDirectories;

import java.nio.file.Path;
import java.util.Locale;

/** The single production selection point, including guard and recovery launches. */
public final class PlatformBootstrap {
    private PlatformBootstrap() {}

    public static PlatformServices create() {
        String os = System.getProperty("os.name", "");
        requireWindows(os);
        return forOs(os, AppDirectories.dataDirectory());
    }

    public static PlatformServices forOs(String osName, Path dataDirectory) {
        requireWindows(osName);
        var backend = new WindowsConnectionBackend(dataDirectory);
        return new PlatformServices(backend, backend);
    }

    private static void requireWindows(String osName) {
        String normalized = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
        // Darwin contains "win", so substring matching would select the wrong native implementation.
        if (!normalized.equals("windows") && !normalized.startsWith("windows ")) {
            throw new UnsupportedOperationException("PerRoute does not yet support this operating system: " + osName);
        }
    }
}
