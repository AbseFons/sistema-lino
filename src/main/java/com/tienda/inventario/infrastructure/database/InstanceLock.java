package com.tienda.inventario.infrastructure.database;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Previene dos instancias de esta aplicación usando la misma base SQLite. */
public final class InstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static InstanceLock acquire(Path databasePath) throws IOException {
        Path file = databasePath.toAbsolutePath().normalize().resolveSibling("inventario.instancia.lock");
        Files.createDirectories(file.getParent());
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Ya existe una instancia utilizando esta base de datos. Ciérrala antes de continuar.");
            return new InstanceLock(channel, lock);
        } catch (IOException | OverlappingFileLockException error) {
            channel.close();
            if (error instanceof IOException io) throw io;
            throw new IOException("La base de datos está siendo utilizada por otra instancia.", error);
        }
    }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); }
        finally { channel.close(); }
    }
}
