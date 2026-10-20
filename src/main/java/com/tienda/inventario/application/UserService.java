package com.tienda.inventario.application;

import com.tienda.inventario.domain.Session;
import com.tienda.inventario.infrastructure.database.Database;
import com.tienda.inventario.infrastructure.security.PasswordHasher;

import java.sql.SQLException;
import java.util.Arrays;

/** Adds an individual worker account. Role changes and deactivation are separate future use cases. */
public final class UserService {
    private final Database db;
    public UserService(Database db) { this.db = db; }

    public void createWorker(String name, String login, char[] password, Session actor) throws SQLException {
        try {
            if (actor == null || !actor.isAdmin()) throw new IllegalArgumentException("Solo el administrador crea usuarios");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("El nombre es obligatorio");
            if (login == null || !login.trim().matches("[A-Za-z0-9._-]{3,32}"))
                throw new IllegalArgumentException("Usuario inválido");
            if (password == null || password.length < 12)
                throw new IllegalArgumentException("La contraseña necesita al menos 12 caracteres");
            db.inTransaction(c -> {
                try (var ps = c.prepareStatement("""
                        INSERT INTO usuario(rol_id,nombre,login,password_hash)
                        SELECT id,?,?,? FROM rol WHERE codigo='VENDEDOR' AND activo=1
                        """)) {
                    ps.setString(1,name.trim()); ps.setString(2,login.trim());
                    ps.setString(3,PasswordHasher.hash(password));
                    if (ps.executeUpdate()!=1) throw new SQLException("No existe el rol VENDEDOR");
                }
                return null;
            });
        } finally { if (password != null) Arrays.fill(password, '\0'); }
    }
}
