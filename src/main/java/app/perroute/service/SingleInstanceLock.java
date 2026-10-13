package app.perroute.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class SingleInstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SingleInstanceLock acquire(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        FileChannel channel = FileChannel.open(dataDirectory.resolve("perroute.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new SingleInstanceLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            return null;
        }
    }

    @Override public void close() {
        try { if (lock.isValid()) lock.release(); } catch (IOException ignored) {}
        try { channel.close(); } catch (IOException ignored) {}
    }
}
