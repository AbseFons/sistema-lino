package com.tienda.inventario;

import com.tienda.inventario.application.*;
import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import com.tienda.inventario.infrastructure.repository.CatalogRepository;
import com.tienda.inventario.infrastructure.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class CatalogAndPresentationIntegrationTest {
    @TempDir Path temp;

    @Test void catalogCrudRequiresAdminAndPersists() throws Exception {
        var f=fixture();
        CatalogService svc=new CatalogService(f.db);
        long category=svc.createCategory("Filtros",f.admin);
        long brand=svc.createBrand("Bosch",f.admin);
        assertTrue(new CatalogRepository(f.db).categories().stream().anyMatch(c->c.id()==category));
        svc.renameCategory(category,"Filtros y bujías",f.admin);
        svc.renameBrand(brand,"BOSCH",f.admin);
        assertThrows(SQLException.class,()->svc.createBrand("bosch",f.admin));
        assertThrows(SecurityException.class,()->svc.createCategory("Sin permiso",f.worker));
        svc.setBrandActive(brand,false,f.admin);
        assertTrue(new CatalogRepository(f.db).brands().stream().noneMatch(b->b.id()==brand));
        svc.setBrandActive(brand,true,f.admin);
        assertTrue(new CatalogRepository(f.db).brands().stream().anyMatch(b->b.id()==brand));
        try(var c=f.db.open();var st=c.createStatement();var rs=st.executeQuery("SELECT count(*) FROM auditoria WHERE entidad IN ('categoria','marca')")) {
            assertTrue(rs.next());assertEquals(6,rs.getInt(1));
        }
    }

    @Test void productBrandCompatibilitiesAndBoxFactorDoNotChangeStock() throws Exception {
        var f=fixture();
        CatalogService catalogSvc=new CatalogService(f.db);
        long category=catalogSvc.createCategory("Repuestos",f.admin);
        long brand=catalogSvc.createBrand("NGK",f.admin);
        long productId=new ProductService(f.db).register(new NewProduct("BUJ-100","Bujía",category,1,
                new BigDecimal("18"),new BigDecimal("3"),new BigDecimal("12.00"),
                brand,"Japón","BPR5EY","Prueba"),f.admin);
        ProductCatalogService svc=new ProductCatalogService(f.db);
        assertEquals(Long.valueOf(brand),svc.details(productId).brandId());
        assertTrue(new ProductRepository(f.db).search("NGK").stream().anyMatch(p->p.id()==productId));
        long compatibility=svc.addCompatibility(productId,"Toyota Hilux 2020",f.admin);
        assertTrue(new ProductRepository(f.db).search("Hilux").stream().anyMatch(p->p.id()==productId));
        assertThrows(IllegalArgumentException.class,()->svc.addCompatibility(productId,"toyota hilux 2020",f.admin));
        long box=svc.addPresentation(productId,"Caja x 12",new BigDecimal("12"),"BUJ10012",true,true,
                new BigDecimal("130.50"),f.admin);
        var presentations=svc.presentations(productId);
        assertEquals(2,presentations.size());
        var packaging=presentations.stream().filter(p->p.id()==box).findFirst().orElseThrow();
        assertEquals(12000,packaging.factorMilli());
        assertEquals("S/ 130.50",DecimalUnits.displayPrice(packaging.publicPriceMicro()));
        assertThrows(IllegalArgumentException.class,()->svc.addPresentation(productId,"Caja distinta x12",
                new BigDecimal("12"),null,true,true,new BigDecimal("129"),f.admin));
        assertThrows(IllegalArgumentException.class,()->svc.addPresentation(productId,"Caja",new BigDecimal("10"),
                null,true,true,null,f.admin));
        assertThrows(SecurityException.class,()->svc.addCompatibility(productId,"Hilux 2017",f.worker));
        svc.setCompatibilityActive(productId,compatibility,false,f.admin);
        svc.setPresentationActive(productId,box,false,f.admin);
        assertFalse(svc.compatibilities(productId).get(0).active());
        assertFalse(svc.presentations(productId).stream().filter(p->p.id()==box).findFirst().orElseThrow().active());
        svc.setCompatibilityActive(productId,compatibility,true,f.admin);
        svc.setPresentationActive(productId,box,true,f.admin);
        assertTrue(svc.compatibilities(productId).get(0).active());
        assertTrue(svc.presentations(productId).stream().filter(p->p.id()==box).findFirst().orElseThrow().active());
        assertThrows(IllegalArgumentException.class,()->catalogSvc.setBrandActive(brand,false,f.admin));
        try(var c=f.db.open();var ps=c.prepareStatement("SELECT cantidad_milli FROM saldo_stock WHERE producto_id=?")) {
            ps.setLong(1,productId);
            try(var rs=ps.executeQuery()) {assertTrue(rs.next()); assertEquals(18000,rs.getLong(1));}
        }
        assertEquals(0, stockDifference(f.db));
    }

    private static int stockDifference(Database db) throws SQLException {
        try(var c=db.open();var st=c.createStatement();var rs=st.executeQuery(
                "SELECT count(*) FROM vw_stock_conciliacion WHERE diferencia_milli!=0")) {
            return rs.next()?rs.getInt(1):-1;
        }
    }
    private Fixture fixture() throws Exception {
        var db=new Database(temp.resolve("catalogs.sqlite")); db.initialize();
        var auth=new AuthService(db); auth.createFirstAdmin("Administradora","admin","ClaveLargaSegura#2026".toCharArray());
        Session admin=auth.authenticate("admin","ClaveLargaSegura#2026".toCharArray()).orElseThrow();
        new UserService(db).createWorker("Vendedor","worker","ClaveLargaSegura#2027".toCharArray(),admin);
        Session worker=auth.authenticate("worker","ClaveLargaSegura#2027".toCharArray()).orElseThrow();
        return new Fixture(db,admin,worker);
    }
    private record Fixture(Database db,Session admin,Session worker){}
}
