package com.tienda.inventario.infrastructure.database;

import org.flywaydb.core.Flyway;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

public final class Database {
    private final Path path;
    private final DataSource source;

    public Database(Path path) {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        this.source = new PragmaDataSource("jdbc:sqlite:" + this.path);
    }

    public Path path() { return path; }

    public void initialize() {
        try {
            Files.createDirectories(path.getParent());
            // Flyway refuses to apply V001 over an existing demo database without its history.
            Flyway.configure()
                    .dataSource(source)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .validateMigrationNaming(true)
                    .load()
                    .migrate();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo preparar la base de datos: " + path, e);
        }
    }

    public Connection open() throws SQLException { return source.getConnection(); }

    public <T> T inTransaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception e) {
                try { connection.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                if (e instanceof SQLException sqlException) throw sqlException;
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new SQLException("Error en transacción", e);
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> { T execute(Connection connection) throws Exception; }

    private static final class PragmaDataSource implements DataSource {
        private final String url;
        private PragmaDataSource(String url) { this.url = url; }
        @Override public Connection getConnection() throws SQLException {
            Connection c = DriverManager.getConnection(url);
            try (var st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA busy_timeout = 5000");
            } catch (SQLException ex) { c.close(); throw ex; }
            return c;
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Unwrap no soportado"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
