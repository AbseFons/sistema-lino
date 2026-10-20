package com.tienda.inventario;

import com.tienda.inventario.application.*;
import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

/** Integration contract to execute on Windows with: gradle test */
class ProductMaintenanceIntegrationTest {
    @TempDir Path temp;
    private record Fixture(Database db,Session admin,Session seller,long product,long basePresentation) {}

    private Fixture setup() throws Exception {
        Database db=new Database(temp.resolve("v03-test.sqlite")); db.initialize();
        AuthService auth=new AuthService(db);
        auth.createFirstAdmin("Administrador", "admin", "ClaveLargaSegura#2026".toCharArray());
        Session admin=auth.authenticate("admin","ClaveLargaSegura#2026".toCharArray()).orElseThrow();
        new UserService(db).createWorker("Vendedor", "vende", "ClaveVendedorSegura#2026".toCharArray(),admin);
        Session seller=auth.authenticate("vende", "ClaveVendedorSegura#2026".toCharArray()).orElseThrow();
        long product=new ProductService(db).register(new NewProduct("SKU-V03","Filtro de aceite",1,1,
                new BigDecimal("10"),new BigDecimal("3"),new BigDecimal("12.00"),
                null,null,null,null),admin);
        long base=new ProductCatalogService(db).presentations(product).stream()
                .filter(p->p.factorMilli()==1000).findFirst().orElseThrow().id();
        return new Fixture(db,admin,seller,product,base);
    }

    @Test void editMetadataKeepsStockAndWritesAudit() throws Exception {
        Fixture f=setup();
        ProductMaintenanceService maintenance=new ProductMaintenanceService(f.db());
        maintenance.updateProduct(f.product(),"Filtro A mejorado",1,null,"Japón","FX-120","Nueva ficha",
                new BigDecimal("4"),new BigDecimal("30"),f.admin());
        ProductEdit edited=maintenance.product(f.product());
        assertEquals("Filtro A mejorado",edited.name());
        assertEquals(4000,edited.minimumMilli());
        assertEquals(30000,edited.maximumMilli());
        assertEquals(10000,edited.stockMilli());
        assertThrows(SecurityException.class,()->maintenance.updateProduct(f.product(),"Otro",1,null,null,null,null,
                new BigDecimal("1"),null,f.seller()));
        try(var c=f.db().open();var ps=c.prepareStatement("SELECT count(*) FROM auditoria WHERE entidad='producto' AND accion='ACTUALIZAR'")){
            try(var rs=ps.executeQuery()){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
        }
    }

    @Test void priceChangesProduceHistoryWithoutRewritingOldPrice() throws Exception {
        Fixture f=setup();
        ProductMaintenanceService maintenance=new ProductMaintenanceService(f.db());
        var lists=maintenance.priceLists();
        long publicId=lists.stream().filter(l->l.name().equals("Público")).findFirst().orElseThrow().id();
        maintenance.changePrice(f.basePresentation(),publicId,new BigDecimal("15.50"),"Costo de proveedor",f.admin());
        var versions=maintenance.priceHistory(f.product());
        assertEquals(2,versions.size());
        assertEquals(155000,versions.get(0).priceMicro());
        assertEquals(120000,versions.get(1).priceMicro());
        assertNotNull(versions.get(1).until());
        assertEquals("S/ 15.50",DecimalUnits.displayPrice(versions.get(0).priceMicro()));
        assertThrows(IllegalArgumentException.class,()->maintenance.changePrice(f.basePresentation(),publicId,
                new BigDecimal("15.50"),"El mismo precio",f.admin()));
        assertThrows(SecurityException.class,()->maintenance.changePrice(f.basePresentation(),publicId,
                new BigDecimal("17.00"),"No autorizado",f.seller()));
    }

    @Test void authorizedAdjustmentsAreTrackedAndCannotMakeNegativeStock() throws Exception {
        Fixture f=setup();
        ProductMaintenanceService service=new ProductMaintenanceService(f.db());
        service.adjustStock(f.product(),new BigDecimal("2"),"Corrección de conteo físico",f.admin());
        service.adjustStock(f.product(),new BigDecimal("-4"),"Cuatro piezas dañadas",f.admin());
        assertEquals(8000,service.product(f.product()).stockMilli());
        assertEquals(3,service.stockHistory(f.product()).size());
        assertThrows(IllegalArgumentException.class,()->service.adjustStock(f.product(),
                new BigDecimal("-9"),"Recuento incorrecto",f.admin()));
        assertThrows(SecurityException.class,()->service.adjustStock(f.product(),
                new BigDecimal("1"),"Vendedor sin autorización",f.seller()));
        assertThrows(IllegalArgumentException.class,()->service.adjustStock(f.product(),
                new BigDecimal("1"),"",f.admin()));
        assertEquals(8000,service.product(f.product()).stockMilli());
        try(var c=f.db().open();var st=c.createStatement();var rs=st.executeQuery(
                "SELECT count(*) FROM vw_stock_conciliacion WHERE diferencia_milli!=0")){
            assertTrue(rs.next());assertEquals(0,rs.getInt(1));
        }
    }
}
