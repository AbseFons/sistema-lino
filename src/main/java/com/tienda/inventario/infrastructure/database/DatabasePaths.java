package com.tienda.inventario.infrastructure.database;

import java.nio.file.Path;

/** Database is kept outside the jar, under the user's application data directory. */
public final class DatabasePaths {
    private DatabasePaths() {}

    public static Path resolve() {
        String override = System.getenv("INVENTARIO_DB_PATH");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null && !localAppData.isBlank()
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("InventarioRepuestos").resolve("inventario.sqlite")
                .toAbsolutePath().normalize();
    }
}
