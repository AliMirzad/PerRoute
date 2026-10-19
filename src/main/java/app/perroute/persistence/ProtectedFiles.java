package app.perroute.persistence;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ProtectedFiles {
    private static final String KEY_FILE = "state.bin.key";

    private ProtectedFiles() {}

    public static void write(Path file, byte[] plain) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        byte[] encrypted = new StateProtector(directory.resolve(KEY_FILE)).protect(plain);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temporary, encrypted);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static byte[] read(Path file) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        return new StateProtector(directory.resolve(KEY_FILE)).unprotect(Files.readAllBytes(file));
    }
}
