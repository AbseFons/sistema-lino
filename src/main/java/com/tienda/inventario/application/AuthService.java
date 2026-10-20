package com.tienda.inventario.application;

import com.tienda.inventario.domain.Session;
import com.tienda.inventario.infrastructure.database.Database;
import com.tienda.inventario.infrastructure.security.PasswordHasher;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;

public final class AuthService {
    private final Database db;
    public AuthService(Database db) { this.db = db; }

    public boolean requiresBootstrap() throws SQLException {
        try (var c = db.open(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM usuario")) {
            return rs.next() && rs.getLong(1) == 0;
        }
    }

    /** Only creates an administrator on a fresh database, atomically. */
    public void createFirstAdmin(String name, String login, char[] password) throws SQLException {
        validateNewUser(name, login, password);
        try {
            db.inTransaction(c -> {
                try (var check = c.createStatement(); var rs = check.executeQuery("SELECT count(*) FROM usuario")) {
                    if (rs.next() && rs.getLong(1) != 0) throw new IllegalStateException("Ya existe un usuario");
                }
                try (var stmt = c.prepareStatement("""
                        INSERT INTO usuario(rol_id,nombre,login,password_hash)
                        SELECT id,?,?,? FROM rol WHERE codigo='ADMIN' AND activo=1
                        """)) {
                    stmt.setString(1, name.trim());
                    stmt.setString(2, login.trim());
                    stmt.setString(3, PasswordHasher.hash(password));
                    if (stmt.executeUpdate() != 1) throw new SQLException("No existe el rol administrador");
                }
                return null;
            });
        } finally { Arrays.fill(password, '\0'); }
    }

    public Optional<Session> authenticate(String login, char[] password) throws SQLException {
        try {
            String sql = """
                    SELECT u.id,u.nombre,u.login,u.password_hash,r.codigo
                    FROM usuario u JOIN rol r ON r.id=u.rol_id
                    WHERE u.login=? COLLATE NOCASE AND u.activo=1 AND r.activo=1
                    """;
            try (var c = db.open(); var stmt = c.prepareStatement(sql)) {
                stmt.setString(1, login.trim());
                try (var rs = stmt.executeQuery()) {
                    if (!rs.next() || !PasswordHasher.verify(password, rs.getString(4))) {
                        return Optional.empty();
                    }
                    return Optional.of(new Session(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(5)));
                }
            }
        } finally { Arrays.fill(password, '\0'); }
    }

    private static void validateNewUser(String name, String login, char[] password) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Ingresa el nombre del administrador");
        if (login == null || !login.trim().matches("[A-Za-z0-9._-]{3,32}"))
            throw new IllegalArgumentException("El usuario debe tener entre 3 y 32 letras, números o ._- ");
        if (password == null || password.length < 12)
            throw new IllegalArgumentException("La contraseña debe tener al menos 12 caracteres");
    }
}
