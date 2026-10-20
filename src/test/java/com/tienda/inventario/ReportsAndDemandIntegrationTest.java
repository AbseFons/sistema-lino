package com.tienda.inventario;

import com.tienda.inventario.application.*;
import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Integración v0.6: ejecutar ./gradlew test o desde panel Gradle. */
class ReportsAndDemandIntegrationTest {
    @TempDir Path temp;
    record Setup(Database db, Session admin,Session seller,long product,long presentation) {}
    private Setup setup()throws Exception {
        Database db=new Database(temp.resolve("v06.sqlite"));db.initialize();
        AuthService auth=new AuthService(db);
        auth.createFirstAdmin("Admin","admin","ClaveLargaAdmin2026!".toCharArray());
        Session admin=auth.authenticate("admin","ClaveLargaAdmin2026!".toCharArray()).orElseThrow();
        new UserService(db).createWorker("Operador","operador","ClaveLargaOperador2026!".toCharArray(),admin);
        Session seller=auth.authenticate("operador","ClaveLargaOperador2026!".toCharArray()).orElseThrow();
        long prod=new ProductService(db).register(new NewProduct("SKU-V06","Filtro de prueba",1,1,
                new BigDecimal("20"),new BigDecimal("2"),new BigDecimal("20.00")),admin);
        long presentation;
        try(var c=db.open();var ps=c.prepareStatement("SELECT id FROM producto_presentacion WHERE producto_id=?")){
            ps.setLong(1,prod);try(var r=ps.executeQuery()){assertTrue(r.next());presentation=r.getLong(1);}
        }
        return new Setup(db,admin,seller,prod,presentation);
    }
    @Test void ventasYMediosDePagoSeReportanSinDuplicaciones()throws Exception{
        Setup f=setup();SaleService s=new SaleService(f.db());ReportsService reports=new ReportsService(f.db());
        NewSale request=new NewSale(UUID.randomUUID().toString(),null,1,"BOLETA",
                List.of(new SaleLineInput(f.presentation(),BigDecimal.ONE,new BigDecimal("20.00"))),
                List.of(new SalePaymentInput("YAPE",new BigDecimal("12"),"Referencia"),
                        new SalePaymentInput("EFECTIVO",new BigDecimal("8"),null)));
        s.confirm(request,f.seller());
        LocalDate today=LocalDate.now(ReportsService.BUSINESS_ZONE);
        SalesSummary summary=reports.summary(today,today);
        assertEquals(1,summary.sales());assertEquals(2000,summary.revenueCents());
        assertEquals(2000,summary.averageTicketCents());
        assertEquals(1,reports.topProducts(today,today,10).size());
        assertEquals(1000,reports.topProducts(today,today,10).get(0).quantityBaseMilli());
        assertEquals(2000,reports.paymentMix(today,today).stream().mapToLong(SalesByPayment::amountCents).sum());
        assertEquals(2,reports.paymentMix(today,today).size());
    }
    @Test void solicitudQuedaAuditadaYNoModificaStock()throws Exception{
        Setup f=setup();DemandService d=new DemandService(f.db());
        long id=d.register("Filtro inexistente","Marca rara",new BigDecimal("2"),null,null,"Cliente pidió 2",f.seller());
        assertEquals(1,d.countPending());assertEquals("PENDIENTE",d.findAll(true,"Filtro",10).get(0).status());
        assertEquals(1,d.mostRequested(10).get(0).requests());
        assertEquals(1,scalar(f.db(),"SELECT COUNT(*) FROM auditoria WHERE entidad='demanda_no_atendida' AND accion='CREAR'"));
        assertEquals(20000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock"));
        d.resolve(id,"ATENDIDA","Se atendió la solicitud del cliente",f.seller());
        assertEquals(0,d.countPending());
        assertThrows(IllegalStateException.class,()->d.resolve(id,"DESCARTADA","Cambio",f.admin()));
        assertEquals(20000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock"));
    }
    @Test void soloAdministradorDescartaUnaSolicitud()throws Exception{
        Setup f=setup();DemandService d=new DemandService(f.db());
        long id=d.register("Repuesto a revisar",null,null,null,null,null,f.seller());
        assertThrows(SecurityException.class,()->d.resolve(id,"DESCARTADA","No rentable",f.seller()));
        assertEquals(1,d.countPending());
        d.resolve(id,"DESCARTADA","El negocio no trabajará esta marca",f.admin());
        assertEquals(0,d.countPending());
    }
    @Test void reportesSinVentasRegresanCeros()throws Exception{
        Setup f=setup();ReportsService r=new ReportsService(f.db());
        LocalDate today=LocalDate.now(ReportsService.BUSINESS_ZONE);
        SalesSummary summary=r.summary(today,today);
        assertEquals(0,summary.sales());assertEquals(0,summary.averageTicketCents());
        assertTrue(r.paymentMix(today,today).isEmpty());
        assertThrows(IllegalArgumentException.class,()->r.summary(today,today.minusDays(1)));
    }
    private long scalar(Database db,String sql)throws Exception {
        try(var c=db.open();var st=c.createStatement();var rs=st.executeQuery(sql)){
            assertTrue(rs.next());return rs.getLong(1);
        }
    }
}
