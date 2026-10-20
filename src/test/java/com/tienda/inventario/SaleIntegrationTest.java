package com.tienda.inventario;

import com.tienda.inventario.application.*;
import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Tests reales sobre servicio Java + SQLite: ejecutar 'gradle test' en Windows. */
class SaleIntegrationTest {
    @TempDir Path temp;
    record Fixture(Database db,Session admin,Session seller,long product,long presentation){}
    Fixture fixture() throws Exception {
        Database db=new Database(temp.resolve("ventas.sqlite"));db.initialize();
        AuthService auth=new AuthService(db);
        auth.createFirstAdmin("Administrador","admin","ClaveLargaAdministrador2026!".toCharArray());
        Session admin=auth.authenticate("admin","ClaveLargaAdministrador2026!".toCharArray()).orElseThrow();
        new UserService(db).createWorker("Vendedor","vende","ClaveLargaVendedor2026!".toCharArray(),admin);
        Session seller=auth.authenticate("vende","ClaveLargaVendedor2026!".toCharArray()).orElseThrow();
        long product=new ProductService(db).register(new NewProduct("SKU-VENTA-01","Filtro",1,1,
            new BigDecimal("10"),new BigDecimal("2"),new BigDecimal("20.00")),admin);
        long presentation;
        try(var c=db.open();var ps=c.prepareStatement("SELECT id FROM producto_presentacion WHERE producto_id=?")){
            ps.setLong(1,product);try(var rs=ps.executeQuery()){assertTrue(rs.next());presentation=rs.getLong(1);}
        }
        return new Fixture(db,admin,seller,product,presentation);
    }
    NewSale request(Fixture f,String uuid,String quantity,String price,String pay){
        return new NewSale(uuid,null,1,"BOLETA",List.of(new SaleLineInput(f.presentation(),new BigDecimal(quantity),new BigDecimal(price))),
                List.of(new SalePaymentInput("YAPE",new BigDecimal(pay),"OPER-001")));
    }
    long scalar(Database db,String sql)throws Exception{
        try(var c=db.open();var st=c.createStatement();var rs=st.executeQuery(sql)){return rs.next()?rs.getLong(1):-1;}
    }
    @Test void ventaCombinadaYMovimientoStock() throws Exception {
        Fixture f=fixture();SaleService service=new SaleService(f.db());
        NewSale req=new NewSale(UUID.randomUUID().toString(),null,1,"BOLETA",
                List.of(new SaleLineInput(f.presentation(),new BigDecimal("2"),new BigDecimal("20"))),
                List.of(new SalePaymentInput("YAPE",new BigDecimal("25"),"123"),
                        new SalePaymentInput("EFECTIVO",new BigDecimal("15"),null)));
        SaleResult result=service.confirm(req,f.seller());
        assertEquals(4000,result.totalCents());assertEquals("PENDIENTE",result.documentStatus());
        assertEquals(8000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock"));
        assertEquals(2,scalar(f.db(),"SELECT COUNT(*) FROM venta_pago"));
        assertEquals(0,scalar(f.db(),"SELECT diferencia_milli FROM vw_stock_conciliacion"));
    }
    @Test void elMismoUuidNoCobraDosVeces() throws Exception {
        Fixture f=fixture();SaleService service=new SaleService(f.db());
        NewSale req=request(f,UUID.randomUUID().toString(),"1","20.00","20.00");
        SaleResult first=service.confirm(req,f.seller()),repeated=service.confirm(req,f.seller());
        assertEquals(first.id(),repeated.id());assertTrue(repeated.repeated());
        assertEquals(9000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock"));
        assertEquals(1,scalar(f.db(),"SELECT COUNT(*) FROM venta WHERE estado='CONFIRMADA'"));
    }
    @Test void noVendeSinStockNiPagosSuficientes() throws Exception {
        Fixture f=fixture();SaleService service=new SaleService(f.db());
        assertThrows(IllegalArgumentException.class,()->service.confirm(request(f,UUID.randomUUID().toString(),"11","20","220"),f.seller()));
        assertThrows(IllegalArgumentException.class,()->service.confirm(request(f,UUID.randomUUID().toString(),"2","20","39"),f.seller()));
        assertEquals(10000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock"));
        assertEquals(0,scalar(f.db(),"SELECT COUNT(*) FROM venta"));
    }
    @Test void descuentoSoloAdminYFacturasRequierenRuc() throws Exception {
        Fixture f=fixture();SaleService service=new SaleService(f.db());
        NewSale discounted=request(f,UUID.randomUUID().toString(),"1","18","18");
        assertThrows(SecurityException.class,()->service.confirm(discounted,f.seller()));
        assertEquals(1800,service.confirm(discounted,f.admin()).totalCents());
        NewSale invoice=new NewSale(UUID.randomUUID().toString(),null,1,"FACTURA",
                List.of(new SaleLineInput(f.presentation(),BigDecimal.ONE,new BigDecimal("20"))),
                List.of(new SalePaymentInput("EFECTIVO",new BigDecimal("20"),null)));
        assertThrows(IllegalArgumentException.class,()->service.confirm(invoice,f.seller()));
    }
    @Test void calculosExactos() {
        assertEquals(11800,SaleService.grossCents(1000,1180000));
        assertEquals(10000,SaleService.netCents(11800));
        assertEquals(2500,SaleService.toCents(new BigDecimal("25.00")));
    }
}
