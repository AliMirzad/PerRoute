package app.perroute;

import app.perroute.service.AppDirectories;
import java.nio.file.Files;

public final class AppDirectoriesSmokeTest {
    public static void main(String[] args) {
        var directory = AppDirectories.coreDirectory();
        if (!Files.isRegularFile(directory.resolve("xray.exe")) || !Files.isRegularFile(directory.resolve("wintun.dll"))) {
            throw new AssertionError("Packaged core directory was not resolved: " + directory);
        }
        System.out.println("Resolved core directory: " + directory);
    }
}
