package com.tienda.inventario.infrastructure.database;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Copia CONSISTENTE SQLite mediante VACUUM INTO; nunca copia una base abierta con Files.copy.
 * Restauración diferida: se prepara mientras la app funciona y se aplica al siguiente arranque,
 * antes de ejecutar las migraciones. No sustituye un respaldo fuera de la PC.
 */
public final class BackupService {
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final String PENDING_FILE = "restauracion_pendiente.sqlite";
    private final Path databaseFile;
    private final Path backupsDir;

    public BackupService(Path databaseFile) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
        this.backupsDir = this.databaseFile.getParent().resolve("respaldos");
    }
    public Path backupsDirectory() { return backupsDir; }
    public Path pendingRestorePath() { return databaseFile.resolveSibling(PENDING_FILE); }
    public boolean hasPendingRestore() { return Files.exists(pendingRestorePath()); }

    /** Una copia diaria local. Conserva las 30 más recientes generadas automáticamente. */
    public synchronized Path ensureDailyBackup() throws IOException, SQLException {
        Files.createDirectories(backupsDir);
        Path daily = backupsDir.resolve("automatico_" + LocalDate.now(LIMA) + ".sqlite");
        if (Files.exists(daily)) { validate(daily); return daily; }
        backupTo(daily);
        pruneAutomatic(30);
        return daily;
    }

    /** La copia del día anterior podría no reflejar una restauración del mismo día. */
    public synchronized Path createPostRestoreBackup() throws IOException, SQLException {
        Files.createDirectories(backupsDir);
        return backupTo(backupsDir.resolve("despues_de_restaurar_" + timestamp() + ".sqlite"));
    }

    /** El usuario elige una ruta; no se sobrescribe ningún archivo existente. */
    public synchronized Path createManualBackup(Path destination) throws IOException, SQLException {
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new FileAlreadyExistsException("Elige otro nombre: el archivo ya existe: " + target);
        return backupTo(target);
    }

    /** Solo se aceptan respaldos SQLite completos de esta aplicación, no su base abierta. */
    public synchronized void stageRestore(Path source) throws IOException, SQLException {
        Path original = source.toAbsolutePath().normalize();
        if (Files.isSameFile(original, databaseFile)) throw new IllegalArgumentException("No puedes restaurar la base activa sobre sí misma.");
        if (hasPendingRestore()) throw new IllegalStateException("Ya existe una restauración pendiente de reinicio.");
        if (Files.exists(Path.of(original + "-wal")))
            throw new IOException("El archivo seleccionado tiene un WAL asociado; genera un respaldo consistente desde la aplicación.");
        validate(original);
        Path partial = temporaryPath(pendingRestorePath());
        try {
            Files.copy(original, partial);
            validate(partial);
            moveNew(partial, pendingRestorePath());
        } finally { Files.deleteIfExists(partial); }
    }

    /** Ejecutar SOLO tras obtener el bloqueo de instancia y ANTES de Database.initialize(). */
    public synchronized Path restorePendingAtStartup() throws IOException, SQLException {
        Path pending = pendingRestorePath();
        if (!Files.exists(pending)) return null;
        validate(pending);
        Files.createDirectories(backupsDir);
        // Antes de sustituir la base conservamos una copia recuperable de su estado actual.
        Path previous = backupsDir.resolve("antes_de_restaurar_" + timestamp() + ".sqlite");
        backupTo(previous);
        try {
            // La app anterior terminó. Rechazar WAL con transacciones no consolidadas.
            try (Connection c = connect(databaseFile); Statement st = c.createStatement()) {
                try (ResultSet r = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    if (r.next() && r.getInt(1) != 0) throw new SQLException("Base ocupada; no puede restaurarse todavía");
                }
            }
            moveReplace(pending, databaseFile);
            Files.deleteIfExists(Path.of(databaseFile + "-wal"));
            Files.deleteIfExists(Path.of(databaseFile + "-shm"));
            try { validate(databaseFile); }
            catch (Exception invalid) {
                Files.copy(previous, databaseFile, StandardCopyOption.REPLACE_EXISTING);
                if (invalid instanceof IOException io) throw io;
                if (invalid instanceof SQLException sql) throw sql;
                throw new IOException("La restauración no pasó la validación", invalid);
            }
            return previous;
        } catch (Exception error) {
            // El archivo de protección queda en respaldos por si se requiere recuperación manual.
            if (error instanceof IOException io) throw io;
            if (error instanceof SQLException sql) throw sql;
            throw new IOException("No fue posible restaurar la base", error);
        }
    }

    /** Integridad, claves foráneas y marcadores básicos de esta aplicación. */
    public static void validate(Path file) throws SQLException, IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) throw new IOException("No es un respaldo SQLite válido");
        // Abrir en modo de solo lectura para evitar que un fichero inválido se convierta en base nueva.
        String url = "jdbc:sqlite:file:" + file.toAbsolutePath().toUri().getRawPath() + "?mode=ro";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1)) || rs.next())
                    throw new IOException("SQLite informó errores de integridad");
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
                if (rs.next()) throw new IOException("El respaldo tiene referencias de datos inválidas");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM usuario u JOIN rol r ON r.id=u.rol_id WHERE r.codigo='ADMIN' AND u.activo=1 AND r.activo=1")) {
                if (!rs.next() || rs.getLong(1) < 1) throw new IOException("El respaldo no contiene un administrador activo");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")) {
                if (!rs.next() || rs.getLong(1) < 1) throw new IOException("El respaldo no tiene migraciones conocidas");
            }
        } catch (SQLException ex) {
            throw new IOException("El archivo no coincide con una base de datos válida del sistema: " + ex.getMessage(), ex);
        }
    }

    private Path backupTo(Path target) throws IOException, SQLException {
        if (!Files.isRegularFile(databaseFile)) throw new IOException("No existe la base de datos activa");
        if (Files.exists(target) && Files.isSameFile(databaseFile, target))
            throw new IOException("El respaldo no puede reemplazar la base activa");
        Files.createDirectories(target.toAbsolutePath().getParent());
        if (Files.exists(target)) throw new FileAlreadyExistsException("El respaldo ya existe: " + target);
        Path partial = temporaryPath(target);
        try {
            try (Connection c = connect(databaseFile); Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + partial.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            validate(partial);
            moveNew(partial, target);
            return target;
        } finally { Files.deleteIfExists(partial); }
    }

    private static Connection connect(Path path) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement st = c.createStatement()) {st.execute("PRAGMA busy_timeout=5000");st.execute("PRAGMA foreign_keys=ON");}
        return c;
    }
    private void pruneAutomatic(int keep) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(backupsDir)) {
            list.filter(p->p.getFileName().toString().matches("automatico_\\d{4}-\\d{2}-\\d{2}\\.sqlite"))
                    .sorted(Comparator.comparing(p->p.getFileName().toString())).forEach(files::add);
        }
        for (int i=0;i<files.size()-keep;i++) Files.delete(files.get(i));
    }
    private static Path temporaryPath(Path target) {
        return target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".partial");
    }
    private static String timestamp() { return LocalDateTime.now(LIMA).format(TIME); }
    private static void moveNew(Path from,Path to) throws IOException {
        // Sin REPLACE_EXISTING: un respaldo previo nunca debe sobrescribirse.
        if (Files.exists(to)) throw new FileAlreadyExistsException(to.toString());
        Files.move(from,to);
    }
    private static void moveReplace(Path from,Path to) throws IOException {
        try { Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from,to,StandardCopyOption.REPLACE_EXISTING); }
    }
}
