package com.tienda.inventario;

import com.tienda.inventario.application.*;
import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class FullExcelReportIntegrationTest {
    @TempDir Path folder;

    @Test void libroCompletoUsaNueveHojasYNoDuplicaVentaConPagoMixto() throws Exception {
        Database db=new Database(folder.resolve("db/inventario.sqlite"));db.initialize();
        AuthService auth=new AuthService(db);
        auth.createFirstAdmin("Administrador","admin","ClaveDePruebaMuyLarga2026!".toCharArray());
        Session admin=auth.authenticate("admin","ClaveDePruebaMuyLarga2026!".toCharArray()).orElseThrow();
        long prod=new ProductService(db).register(new NewProduct("FIL-001","Filtro para motor",1,1,
                new BigDecimal("4"),new BigDecimal("2"),new BigDecimal("18")),admin);
        long pp;
        try(var c=db.open();var st=c.prepareStatement("SELECT id FROM producto_presentacion WHERE producto_id=?")){
            st.setLong(1,prod);try(var rs=st.executeQuery()){assertTrue(rs.next());pp=rs.getLong(1);}
        }
        new SaleService(db).confirm(new NewSale(UUID.randomUUID().toString(),null,1,"BOLETA",
          List.of(new SaleLineInput(pp,BigDecimal.ONE,new BigDecimal("18"))),
          List.of(new SalePaymentInput("YAPE",new BigDecimal("10.00"),null),
                  new SalePaymentInput("EFECTIVO",new BigDecimal("8.00"),null))),admin);
        new DemandService(db).register("=HYPERLINK(\"malicioso\")","=marca",BigDecimal.ONE,null,null,
                "No hay stock",admin);
        LocalDate today=LocalDate.now(ReportsService.BUSINESS_ZONE);
        Path file=folder.resolve("reporte/informe completo.xlsx");
        assertEquals(file.toAbsolutePath(),new FullExcelReportService(db).export(file,today,today,90));
        assertTrue(Files.size(file)>2000);
        try(ZipFile xlsx=new ZipFile(file.toFile())){
            assertNotNull(xlsx.getEntry("xl/styles.xml"));
            String names=read(xlsx,"xl/workbook.xml");
            for(String name:List.of("Resumen","Ventas","Detalle de ventas","Productos vendidos","Pagos",
                    "Inventario","Rotación","Solicitudes","Ventas diarias"))assertTrue(names.contains(name),name);
            // Nueve hojas; una venta, no una fila por cada pago.
            for(int i=1;i<=9;i++){
                String name="xl/worksheets/sheet"+i+".xml";
                try(InputStream stream=xlsx.getInputStream(xlsx.getEntry(name))){
                    var parser=DocumentBuilderFactory.newInstance();
                    parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                    parser.setFeature("http://xml.org/sax/features/external-general-entities",false);
                    parser.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                    parser.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
                    assertNotNull(parser.newDocumentBuilder().parse(stream));
                }
            }
            String sales=read(xlsx,"xl/worksheets/sheet2.xml");
            assertTrue(sales.contains("<row r=\"6\""));
            assertFalse(sales.contains("<row r=\"7\""),"Dos pagos no deben duplicar una venta");
            assertTrue(sales.contains("18.00"));
            String pays=read(xlsx,"xl/worksheets/sheet5.xml");
            assertTrue(pays.contains("YAPE"));assertTrue(pays.contains("EFECTIVO"));
            assertTrue(pays.contains("10.00"));assertTrue(pays.contains("8.00"));
            // Datos introducidos por usuario deben ser cadenas explícitas, jamás fórmulas.
            String demand=read(xlsx,"xl/worksheets/sheet8.xml");
            assertTrue(demand.contains("=HYPERLINK"));assertTrue(demand.contains("t=\"inlineStr\""));
            assertFalse(demand.contains("<f>"));
        }
    }
    @Test void fechasInvalidasNoGeneranArchivo() throws Exception {
        Database db=new Database(folder.resolve("db2/inventario.sqlite"));
        Path out=folder.resolve("salida.xlsx");
        assertThrows(IllegalArgumentException.class,()->new FullExcelReportService(db).export(out,
                LocalDate.of(2026,10,11),LocalDate.of(2026,10,10),90));
        assertFalse(Files.exists(out));
    }
    private static String read(ZipFile zip,String entry) throws Exception {
        try(var in=zip.getInputStream(zip.getEntry(entry))){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
}
