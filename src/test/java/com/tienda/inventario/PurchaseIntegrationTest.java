package com.tienda.inventario;

import com.tienda.inventario.application.*;
import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.sql.SQLException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Contrato del servicio real; ejecutar en Windows: gradle test */
class PurchaseIntegrationTest {
    @TempDir Path tmp;
    private record Fixture(Database db,Session admin,Session worker, long productId, long boxId,long supplierId) {}

    private Fixture setup() throws Exception {
        Database db=new Database(tmp.resolve("test-compras.sqlite"));db.initialize();
        AuthService auth=new AuthService(db);
        auth.createFirstAdmin("Administrador","admin","ClaveCompraSegura#2026".toCharArray());
        Session admin=auth.authenticate("admin","ClaveCompraSegura#2026".toCharArray()).orElseThrow();
        new UserService(db).createWorker("Vendedor","vende","ClaveVendedorSegura#2026".toCharArray(),admin);
        Session worker=auth.authenticate("vende","ClaveVendedorSegura#2026".toCharArray()).orElseThrow();
        long product=new ProductService(db).register(new NewProduct("FIL-COMPRA-01","Filtro de aceite",1,1,
                new BigDecimal("10"),new BigDecimal("2"),new BigDecimal("20.00")),admin);
        long box=new ProductCatalogService(db).addPresentation(product,"Caja x12",new BigDecimal("12"),null,
                true,false,null,admin);
        long supplier=new SupplierService(db).create("Distribuidora de Filtros","20111111111","989123456",admin);
        return new Fixture(db,admin,worker,product,box,supplier);
    }
    private NewPurchase makePurchase(Fixture f,String document,String quantity,String price){
        return new NewPurchase(f.supplierId(),document,LocalDate.now(),
                List.of(new PurchaseLineInput(f.boxId(),new BigDecimal(quantity),new BigDecimal(price))));
    }
    private long scalar(Database db,String sql) throws SQLException {
        try(var c=db.open();var st=c.createStatement();var rs=st.executeQuery(sql)){
            return rs.next()?rs.getLong(1):-1;
        }
    }

    @Test void twoBoxesEnter24UnitsAndPreserveCost() throws Exception {
        Fixture f=setup();
        PurchaseResult result=new PurchaseService(f.db()).confirm(makePurchase(f,"F001-0005","2","85.00"),f.admin());
        assertEquals(17000,result.totalCents());
        assertEquals(1,result.lines());
        assertEquals(34000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock WHERE producto_id="+f.productId()));
        assertEquals(24000,scalar(f.db(),"SELECT cantidad_delta_milli FROM movimiento_stock WHERE tipo='COMPRA'"));
        assertEquals(0,scalar(f.db(),"SELECT diferencia_milli FROM vw_stock_conciliacion WHERE producto_id="+f.productId()));
        assertEquals(850000,scalar(f.db(),"SELECT costo_unitario_micro FROM compra_detalle"));
        assertEquals(1,scalar(f.db(),"SELECT COUNT(*) FROM auditoria WHERE entidad='compra' AND accion='CONFIRMAR'"));
    }

    @Test void duplicateDocumentRollsBackWithoutExtraStock() throws Exception {
        Fixture f=setup();PurchaseService svc=new PurchaseService(f.db());
        svc.confirm(makePurchase(f,"F001-0005","2","85.00"),f.admin());
        assertThrows(SQLException.class,()->svc.confirm(makePurchase(f,"f001-0005","3","90.00"),f.admin()));
        assertEquals(34000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock WHERE producto_id="+f.productId()));
        assertEquals(1,scalar(f.db(),"SELECT COUNT(*) FROM compra"));
        assertEquals(1,scalar(f.db(),"SELECT COUNT(*) FROM movimiento_stock WHERE tipo='COMPRA'"));
    }

    @Test void costChangesAreReportedWithoutChangingSalePrice() throws Exception {
        Fixture f=setup();PurchaseService svc=new PurchaseService(f.db());
        svc.confirm(makePurchase(f,"F001-0005","2","85.00"),f.admin());
        PurchaseResult next=svc.confirm(makePurchase(f,"F001-0006","1","89.00"),f.admin());
        assertEquals(1,next.changedCosts());
        assertEquals(890000,scalar(f.db(),"SELECT costo_unitario_micro FROM vw_ultimo_costo_compra WHERE presentacion_id="+f.boxId()));
        assertEquals(200000,scalar(f.db(),"SELECT precio_micro FROM vw_precio_actual WHERE lista='PUBLICO'"));
        assertEquals(46000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock WHERE producto_id="+f.productId()));
    }

    @Test void onlyAdminCanConfirmAndPartialBoxRejected() throws Exception {
        Fixture f=setup();PurchaseService svc=new PurchaseService(f.db());
        assertThrows(SecurityException.class,()->svc.confirm(makePurchase(f,"F001-0005","2","85.00"),f.worker()));
        assertThrows(IllegalArgumentException.class,()->svc.confirm(makePurchase(f,"F001-0005","1.5","85.00"),f.admin()));
        assertEquals(10000,scalar(f.db(),"SELECT cantidad_milli FROM saldo_stock WHERE producto_id="+f.productId()));
        assertEquals(0,scalar(f.db(),"SELECT COUNT(*) FROM compra"));
    }

    @Test void supplierSnapshotStaysAfterRenaming() throws Exception {
        Fixture f=setup();PurchaseService svc=new PurchaseService(f.db());
        svc.confirm(makePurchase(f,"F001-0005","2","85.00"),f.admin());
        new SupplierService(f.db()).update(f.supplierId(),"Nombre Modificado","20111111111",null,f.admin());
        assertEquals("Distribuidora de Filtros",svc.recentPurchases(10).getFirst().supplierName());
    }

    @Test void currencyRoundingIsExact() {
        assertEquals(17000,PurchaseService.lineCents(2000,850000));
        assertEquals(333,PurchaseService.lineCents(1000,33333));
        assertEquals(1,PurchaseService.lineCents(1,50000));
    }
}
