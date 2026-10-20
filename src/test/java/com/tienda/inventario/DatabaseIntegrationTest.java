package com.tienda.inventario;

import com.tienda.inventario.application.AuthService;
import com.tienda.inventario.application.ProductService;
import com.tienda.inventario.application.UserService;
import com.tienda.inventario.domain.NewProduct;
import com.tienda.inventario.infrastructure.database.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseIntegrationTest {
    @TempDir Path tmp;

    @Test void migrationsAndAuthentication() throws Exception {
        Database db = fresh();
        AuthService auth = new AuthService(db);
        assertTrue(auth.requiresBootstrap());
        auth.createFirstAdmin("Administradora", "admin", "ClaveLargaSegura#2026".toCharArray());
        assertFalse(auth.requiresBootstrap());
        assertTrue(auth.authenticate("admin","fallida".toCharArray()).isEmpty());
        assertTrue(auth.authenticate("ADMIN","ClaveLargaSegura#2026".toCharArray()).isPresent());
        assertThrows(IllegalStateException.class, () -> auth.createFirstAdmin("Otro", "otro","ClaveLargaSegura#2027".toCharArray()));
        try(var c=db.open(); var st=c.createStatement(); var rs=st.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(rs.next()); assertEquals(1,rs.getInt(1));
        }
    }

    @Test void productRegistrationIsAtomicAndUsesKardex() throws Exception {
        Database db = fresh();
        AuthService auth = new AuthService(db);
        auth.createFirstAdmin("Administradora","admin","ClaveLargaSegura#2026".toCharArray());
        var session = auth.authenticate("admin","ClaveLargaSegura#2026".toCharArray()).orElseThrow();
        ProductService service = new ProductService(db);
        var product = new NewProduct("FIL-001","Filtro",1,1,new BigDecimal("8"),
                new BigDecimal("2"),new BigDecimal("29.90"));
        long id = service.register(product,session);
        try(var c=db.open(); var st=c.prepareStatement("SELECT cantidad_milli FROM saldo_stock WHERE producto_id=?")) {
            st.setLong(1,id);
            try(var rs=st.executeQuery()) { assertTrue(rs.next()); assertEquals(8000,rs.getLong(1)); }
        }
        try(var c=db.open(); var st=c.prepareStatement("SELECT count(*) FROM movimiento_stock WHERE producto_id=? AND tipo='INICIAL'")) {
            st.setLong(1,id);
            try(var rs=st.executeQuery()) { assertTrue(rs.next()); assertEquals(1,rs.getLong(1)); }
        }
        assertThrows(SQLException.class,()->service.register(product,session));
        try(var c=db.open(); var st=c.createStatement(); var rs=st.executeQuery("SELECT count(*) FROM producto")) {
            assertTrue(rs.next()); assertEquals(1,rs.getInt(1));
        }
    }

    @Test void workerCannotDeclareInitialStock() throws Exception {
        Database db = fresh();
        AuthService auth = new AuthService(db);
        auth.createFirstAdmin("Administradora","admin","ClaveLargaSegura#2026".toCharArray());
        var admin = auth.authenticate("admin","ClaveLargaSegura#2026".toCharArray()).orElseThrow();
        new UserService(db).createWorker("Vendedor","vendedor","ClaveLargaSegura#2027".toCharArray(),admin);
        var worker = auth.authenticate("vendedor","ClaveLargaSegura#2027".toCharArray()).orElseThrow();
        ProductService service = new ProductService(db);
        assertThrows(IllegalArgumentException.class,()->service.register(
                new NewProduct("ACE-001","Aceite",1,1,new BigDecimal("6"),BigDecimal.ZERO,new BigDecimal("10.50")),worker));
        try(var c=db.open(); var st=c.createStatement(); var rs=st.executeQuery("SELECT count(*) FROM producto")) {
            assertTrue(rs.next()); assertEquals(0,rs.getInt(1));
        }
    }

    @Test void reopeningKeepsMigrationsAndData() throws Exception {
        Path dbFile = tmp.resolve("retained.sqlite");
        Database first = new Database(dbFile);
        first.initialize();
        new AuthService(first).createFirstAdmin("Admin","admin","ClaveLargaSegura#2026".toCharArray());
        Database second = new Database(dbFile);
        assertDoesNotThrow(second::initialize);
        assertFalse(new AuthService(second).requiresBootstrap());
        try(var c=second.open();var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success=1")) {
            assertTrue(rs.next()); assertEquals(6,rs.getInt(1));
        }
    }

    private Database fresh() {
        Database db = new Database(tmp.resolve("test.sqlite"));
        db.initialize();
        return db;
    }
}
