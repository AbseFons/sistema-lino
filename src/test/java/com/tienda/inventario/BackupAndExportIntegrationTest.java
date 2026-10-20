package com.tienda.inventario;

import com.tienda.inventario.application.*;
import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Copias SQLite, restauración real y exportación CSV: pruebas con JDBC y Flyway reales. */
class BackupAndExportIntegrationTest {
    @TempDir Path folder;

    private record Setup(Database db, BackupService backup, Session admin, long presentationId) {}
    private Setup setup() throws Exception {
        Path file=folder.resolve("datos/inventario.sqlite");
        Database db=new Database(file);db.initialize();
        AuthService auth=new AuthService(db);
        auth.createFirstAdmin("Administrador", "admin", "ClaveLargaAdministradora2026!".toCharArray());
        Session admin=auth.authenticate("admin","ClaveLargaAdministradora2026!".toCharArray()).orElseThrow();
        long id=new ProductService(db).register(new NewProduct("SKU-RESP","Filtro real",1,1,
                new BigDecimal("4"),new BigDecimal("2"),new BigDecimal("18.00")),admin);
        long presentation;
        try(var c=db.open();var q=c.prepareStatement("SELECT id FROM producto_presentacion WHERE producto_id=?")) {
            q.setLong(1,id);try(var rs=q.executeQuery()){assertTrue(rs.next());presentation=rs.getLong(1);}
        }
        return new Setup(db,new BackupService(file),admin,presentation);
    }

    @Test void copiaManualEsConsistenteYPreservaAdminYStock() throws Exception {
        Setup f=setup();
        Path target=folder.resolve("externo/seguridad.sqlite");
        f.backup().createManualBackup(target);
        BackupService.validate(target);
        assertTrue(Files.size(target)>0);
        Database copy=new Database(target);
        try(var c=copy.open();var st=c.createStatement();var r=st.executeQuery("SELECT cantidad_milli FROM saldo_stock")) {
            assertTrue(r.next());assertEquals(4000,r.getLong(1));
        }
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->f.backup().createManualBackup(target));
    }

    @Test void copiaAutomaticaDiariaNoSeDuplicaYEsValida() throws Exception {
        Setup f=setup();
        Path first=f.backup().ensureDailyBackup();
        Path next=f.backup().ensureDailyBackup();
        assertEquals(first,next);
        BackupService.validate(first);
    }

    @Test void restauracionRecuperaStockYConservaCopiaAnterior() throws Exception {
        Setup f=setup();
        Path original=folder.resolve("guardada.sqlite");
        f.backup().createManualBackup(original);
        NewSale sale=new NewSale(UUID.randomUUID().toString(),null,1,"BOLETA",
                List.of(new SaleLineInput(f.presentationId(),BigDecimal.ONE,new BigDecimal("18.00"))),
                List.of(new SalePaymentInput("EFECTIVO",new BigDecimal("18.00"),null)));
        new SaleService(f.db()).confirm(sale,f.admin());
        assertEquals(3000,stock(f.db()));
        f.backup().stageRestore(original);
        assertTrue(f.backup().hasPendingRestore());
        Path before=f.backup().restorePendingAtStartup();
        assertFalse(f.backup().hasPendingRestore());
        assertNotNull(before);BackupService.validate(before);
        assertEquals(4000,stock(new Database(f.db().path())));
        assertEquals(3000,stock(new Database(before)));
        assertFalse(new AuthService(new Database(f.db().path())).requiresBootstrap());
    }

    @Test void archivoInvalidoNoPuedeAlterarLaBase() throws Exception {
        Setup f=setup();
        Path bad=folder.resolve("incorrecto.sqlite");Files.writeString(bad,"No soy una base de datos");
        assertThrows(Exception.class,()->f.backup().stageRestore(bad));
        assertEquals(4000,stock(f.db()));
        assertFalse(f.backup().hasPendingRestore());
        assertThrows(IllegalArgumentException.class,()->f.backup().stageRestore(f.db().path()));
    }

    @Test void impideDosInstanciasSimultaneas() throws Exception {
        Setup f=setup();
        try (InstanceLock lock=InstanceLock.acquire(f.db().path())) {
            assertThrows(IOException.class,()->InstanceLock.acquire(f.db().path()));
        }
        try (InstanceLock reopened=InstanceLock.acquire(f.db().path())) {assertNotNull(reopened);}
    }

    @Test void csvIncluyeDatosCorrectosYNoDuplicaPagos() throws Exception {
        Setup f=setup();
        new SaleService(f.db()).confirm(new NewSale(UUID.randomUUID().toString(),null,1,"BOLETA",
                List.of(new SaleLineInput(f.presentationId(),BigDecimal.ONE,new BigDecimal("18.00"))),
                List.of(new SalePaymentInput("YAPE",new BigDecimal("10.00"),null),
                        new SalePaymentInput("EFECTIVO",new BigDecimal("8.00"),null))),f.admin());
        ReportExportService csv=new ReportExportService(new ReportsService(f.db()),new DemandService(f.db()));
        LocalDate today=LocalDate.now(ReportsService.BUSINESS_ZONE);
        Path file=csv.export(ReportExportService.Type.PAGOS,today,today,90,folder.resolve("export/pagos.csv"));
        String out=Files.readString(file,StandardCharsets.UTF_8);
        assertTrue(out.startsWith("\ufeff"));assertTrue(out.contains("YAPE"));assertTrue(out.contains("EFECTIVO"));
        assertTrue(out.contains("10,00"));assertTrue(out.contains("8,00"));
        Path summary=csv.export(ReportExportService.Type.RESUMEN,today,today,90,folder.resolve("export/resumen.csv"));
        assertTrue(Files.readString(summary).contains("18,00"));
    }

    private long stock(Database db) throws SQLException {
        try(var c=db.open();var st=c.createStatement();var r=st.executeQuery("SELECT cantidad_milli FROM saldo_stock")) {
            assertTrue(r.next());return r.getLong(1);
        }
    }
}
