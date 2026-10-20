package com.tienda.inventario.application;

import com.tienda.inventario.infrastructure.database.Database;
import com.tienda.inventario.application.SimpleXlsxWriter.Cell;
import com.tienda.inventario.application.SimpleXlsxWriter.Sheet;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * Libro Excel comercial completo; consultas en una misma transacción de lectura SQLite.
 * No altera ninguna tabla y NO debe confundirse con un reporte tributario ni un CPE.
 */
public final class FullExcelReportService {
    private static final DateTimeFormatter DB_UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private final Database db;
    public FullExcelReportService(Database db) {this.db=db;}

    public Path export(Path output,LocalDate from,LocalDate to,int idleDays) throws Exception {
        if(output==null)throw new IllegalArgumentException("Indica dónde guardar el Excel");
        if(from==null||to==null||from.isAfter(to)||from.plusYears(3).isBefore(to))
            throw new IllegalArgumentException("Período incorrecto (máximo 3 años)");
        if(idleDays!=30&&idleDays!=60&&idleDays!=90&&idleDays!=180)
            throw new IllegalArgumentException("Plazo de rotación no válido");
        Path destination=output.toAbsolutePath().normalize();
        if(!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx"))
            throw new IllegalArgumentException("El archivo debe tener extensión .xlsx");
        Files.createDirectories(destination.getParent());
        String[] b={DB_UTC.format(from.atStartOfDay(ReportsService.BUSINESS_ZONE).toInstant()),
                DB_UTC.format(to.plusDays(1).atStartOfDay(ReportsService.BUSINESS_ZONE).toInstant())};
        String period="Ventas confirmadas: "+from+" a "+to+" (Perú)";
        Path temporary=Files.createTempFile(destination.getParent(),".reporte-comercial-",".tmp");
        try {
            try(Connection con=db.open();var os=Files.newOutputStream(temporary);
                SimpleXlsxWriter workbook=new SimpleXlsxWriter(os)) {
                con.setAutoCommit(false); // una sola instantánea de lectura para todas las hojas
                try {
                    summary(workbook,con,b,period);
                    sales(workbook,con,b,period);
                    saleLines(workbook,con,b,period);
                    products(workbook,con,b,period);
                    payments(workbook,con,b,period);
                    inventory(workbook,con,period);
                    rotation(workbook,con,b,idleDays,period);
                    demands(workbook,con,period);
                    daily(workbook,con,b,period);
                    con.commit();
                } catch(Exception ex) {con.rollback();throw ex;}
            }
            try {Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException ex) {Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
            return destination;
        } finally {Files.deleteIfExists(temporary);}
    }

    private static PreparedStatement query(Connection con,String sql,String... parameters) throws SQLException {
        PreparedStatement ps=con.prepareStatement(sql);
        try{for(int i=0;i<parameters.length;i++)ps.setString(i+1,parameters[i]);return ps;}
        catch(Exception ex){ps.close();throw ex;}
    }
    private static long scalar(Connection con,String sql,String... b) throws SQLException {
        try(var ps=query(con,sql,b);var r=ps.executeQuery()){return r.next()?r.getLong(1):0;}
    }
    private static String safe(String s){return s==null?"":s;}
    private static Cell txt(String s){return Cell.text(safe(s));}
    private static String peruvian(String utc){
        if(utc==null||utc.isBlank())return "";
        try{return Instant.parse(utc).atZone(ReportsService.BUSINESS_ZONE).format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"));}
        catch(Exception ignored){return utc;}
    }
    private static void rows(Connection c,String sql,String[] range,Sheet sheet,ResultRows mapping) throws Exception {
        try(PreparedStatement ps=query(c,sql,range);ResultSet rs=ps.executeQuery()){
            while(rs.next())mapping.map(rs,sheet);
        }
    }
    @FunctionalInterface private interface ResultRows {void map(ResultSet r,Sheet sheet) throws Exception;}

    private void summary(SimpleXlsxWriter book,Connection c,String[] b,String period)throws Exception {
        book.addSheet("Resumen","Indicadores operativos. No constituye declaración SUNAT.",period,
          new String[]{"Indicador","Valor","Alcance"},new int[]{30,21,70},s->{
            long count=scalar(c,"SELECT COUNT(*) FROM venta WHERE estado='CONFIRMADA' AND fecha>=? AND fecha<?",b);
            long total=scalar(c,"SELECT COALESCE(SUM(total_centavos),0) FROM venta WHERE estado='CONFIRMADA' AND fecha>=? AND fecha<?",b);
            long discount=scalar(c,"SELECT COALESCE(SUM(descuento_centavos),0) FROM venta WHERE estado='CONFIRMADA' AND fecha>=? AND fecha<?",b);
            long received=scalar(c,"SELECT COALESCE(SUM(vp.monto_centavos),0) FROM venta_pago vp JOIN venta v ON v.id=vp.venta_id WHERE v.estado='CONFIRMADA' AND v.fecha>=? AND v.fecha<?",b);
            s.row(txt("Ventas confirmadas"),Cell.count(count),txt("Se cuentan las ventas, no cada producto ni cada medio de pago"));
            s.row(txt("Facturación comercial"),Cell.money(total),txt("Venta registrada; no implica comprobante emitido/aceptado"));
            s.row(txt("Descuentos"),Cell.money(discount),txt("Descuento total registrado en ventas confirmadas"));
            s.row(txt("Ticket promedio"),Cell.money(count==0?0:java.math.BigDecimal.valueOf(total).divide(java.math.BigDecimal.valueOf(count),0,java.math.RoundingMode.HALF_UP).longValue()),txt("Facturación comercial / cantidad de ventas"));
            s.row(txt("Cobros registrados"),Cell.money(received),txt("Suma de pagos vinculados; evita duplicar ventas con pago mixto"));
            s.row(txt("Ventas con CPE pendiente"),Cell.count(scalar(c,"SELECT COUNT(*) FROM venta v JOIN comprobante co ON co.venta_id=v.id WHERE v.estado='CONFIRMADA' AND co.estado='PENDIENTE' AND v.fecha>=? AND v.fecha<?",b)),txt("Pendiente NO equivale a emisión electrónica"));
            s.row(txt("Productos activos"),Cell.count(scalar(c,"SELECT COUNT(*) FROM producto WHERE estado='ACTIVO'")),txt("Estado actual, sin filtro de fechas"));
            s.row(txt("Solicitudes pendientes"),Cell.count(scalar(c,"SELECT COUNT(*) FROM demanda_no_atendida WHERE estado='PENDIENTE'")),txt("Estado actual, sin filtro de fechas"));
        });
    }
    private void sales(SimpleXlsxWriter book,Connection c,String[] b,String period)throws Exception {
        book.addSheet("Ventas","Una fila por venta confirmada; pagos combinados NO multiplican ventas.",period,
          new String[]{"Venta #","Fecha (Perú)","Vendedor","Cliente","Tipo doc. cliente","Nº doc. cliente","Comprobante","Estado CPE","Subtotal S/","Descuento S/","IGV S/","Total S/","Formas de pago"},
          new int[]{12,21,27,36,18,20,17,24,17,17,15,18,44},s->{
            rows(c,"""
              SELECT v.id,v.fecha,u.nombre,COALESCE(v.cliente_nombre_snapshot,'Consumidor final'),
                v.cliente_tipo_documento_snapshot,v.cliente_documento_snapshot,co.tipo,co.estado,
                v.subtotal_centavos,v.descuento_centavos,v.impuesto_centavos,v.total_centavos,
                (SELECT group_concat(x.medio||' '||printf('%.2f',x.monto_centavos/100.0),' + ')
                 FROM venta_pago x WHERE x.venta_id=v.id)
              FROM venta v JOIN usuario u ON u.id=v.vendedor_id
              LEFT JOIN comprobante co ON co.venta_id=v.id
              WHERE v.estado='CONFIRMADA' AND v.fecha>=? AND v.fecha<?
              ORDER BY v.fecha,v.id
              """,b,s,(r,row)->row.row(Cell.count(r.getLong(1)),txt(peruvian(r.getString(2))),txt(r.getString(3)),txt(r.getString(4)),
                txt(r.getString(5)),txt(r.getString(6)),txt(r.getString(7)),txt(r.getString(8)),Cell.money(r.getLong(9)),
                Cell.money(r.getLong(10)),Cell.money(r.getLong(11)),Cell.money(r.getLong(12)),txt(r.getString(13))));
        });
    }
    private void saleLines(SimpleXlsxWriter book,Connection c,String[] b,String period)throws Exception {
        book.addSheet("Detalle de ventas","Una fila por producto vendido; importes guardados al confirmar la venta.",period,
          new String[]{"Venta #","Fecha (Perú)","SKU histórico","Producto histórico","Presentación","Cantidad presentada","Cantidad base","Precio final unit. S/","Descuento S/","IGV S/","Total línea S/"},
          new int[]{12,21,19,36,24,20,18,22,19,16,20},s->{
            rows(c,"""
              SELECT v.id,v.fecha,d.sku_snapshot,d.descripcion_snapshot,pp.nombre,
                d.cantidad_presentacion_milli,d.cantidad_base_milli,d.precio_final_micro,
                d.descuento_centavos,d.impuesto_centavos,d.total_centavos
              FROM venta_detalle d JOIN venta v ON v.id=d.venta_id
              JOIN producto_presentacion pp ON pp.id=d.presentacion_id
              WHERE v.estado='CONFIRMADA' AND v.fecha>=? AND v.fecha<?
              ORDER BY v.fecha,v.id,d.id
              """,b,s,(r,row)->row.row(Cell.count(r.getLong(1)),txt(peruvian(r.getString(2))),txt(r.getString(3)),
                txt(r.getString(4)),txt(r.getString(5)),Cell.qty(r.getLong(6)),Cell.qty(r.getLong(7)),
                // precio_final_micro = soles × 10000: mostrar numérico mediante escala por 100
                Cell.micro(r.getLong(8)),Cell.money(r.getLong(9)),Cell.money(r.getLong(10)),Cell.money(r.getLong(11))));
        });
    }
    private void products(SimpleXlsxWriter book,Connection c,String[] b,String period)throws Exception {
        book.addSheet("Productos vendidos","Productos por facturación (todos los del período, sin límite de 100).",period,
          new String[]{"SKU","Producto","Unidad base","Cantidad base","Ventas distintas","Facturación S/"},
          new int[]{20,44,18,19,20,21},s->{
            rows(c,"""
              SELECT p.sku,p.nombre,u.codigo,SUM(d.cantidad_base_milli),COUNT(DISTINCT v.id),SUM(d.total_centavos)
              FROM venta_detalle d JOIN venta v ON v.id=d.venta_id
              JOIN producto_presentacion pp ON pp.id=d.presentacion_id
              JOIN producto p ON p.id=pp.producto_id JOIN unidad_medida u ON u.id=p.unidad_base_id
              WHERE v.estado='CONFIRMADA' AND v.fecha>=? AND v.fecha<?
              GROUP BY p.id ORDER BY SUM(d.total_centavos) DESC,p.sku
              """,b,s,(r,row)->row.row(txt(r.getString(1)),txt(r.getString(2)),txt(r.getString(3)),Cell.qty(r.getLong(4)),Cell.count(r.getLong(5)),Cell.money(r.getLong(6))));
        });
    }
    private void payments(SimpleXlsxWriter book,Connection c,String[] b,String period)throws Exception {
        book.addSheet("Pagos","Suma de pagos confirmados por medio; una venta puede aparecer en varios medios.",period,
          new String[]{"Medio de pago","Operaciones de pago","Cobrado S/"},new int[]{26,24,23},s->{
            rows(c,"""
              SELECT vp.medio,COUNT(*),SUM(vp.monto_centavos) FROM venta_pago vp
              JOIN venta v ON v.id=vp.venta_id
              WHERE v.estado='CONFIRMADA' AND v.fecha>=? AND v.fecha<?
              GROUP BY vp.medio ORDER BY SUM(vp.monto_centavos) DESC
              """,b,s,(r,row)->row.row(txt(r.getString(1)),Cell.count(r.getLong(2)),Cell.money(r.getLong(3))));
        });
    }
    private void inventory(SimpleXlsxWriter book,Connection c,String period)throws Exception {
        book.addSheet("Inventario","Existencias actuales (inventario en incorporación progresiva; NO es un cierre físico).",
          "Stock al momento de exportar; independiente del filtro de ventas. "+period,
          new String[]{"SKU","Producto","Categoría","Marca","Unidad","Stock","Mínimo","Máximo","Estado","Ubicación / modelo"},
          new int[]{18,39,25,25,14,17,17,17,15,34},s->{
            try(var ps=query(c,"""
              SELECT p.sku,p.nombre,cat.nombre,COALESCE(m.nombre,''),u.codigo,
                COALESCE(ss.cantidad_milli,0),p.stock_min_milli,p.stock_max_milli,p.estado,p.modelo_referencia
              FROM producto p JOIN categoria cat ON cat.id=p.categoria_id
              LEFT JOIN marca m ON m.id=p.marca_id JOIN unidad_medida u ON u.id=p.unidad_base_id
              LEFT JOIN saldo_stock ss ON ss.producto_id=p.id
              ORDER BY p.sku
              """);var r=ps.executeQuery()){
              while(r.next()){
                long max=r.getLong(8);boolean hasMax=!r.wasNull();
                s.row(txt(r.getString(1)),txt(r.getString(2)),txt(r.getString(3)),txt(r.getString(4)),txt(r.getString(5)),
                  Cell.qty(r.getLong(6)),Cell.qty(r.getLong(7)),hasMax?Cell.qty(max):Cell.blank(),txt(r.getString(9)),txt(r.getString(10)));
              }
            }
        });
    }
    private void rotation(SimpleXlsxWriter book,Connection c,String[] b,int idleDays,String period)throws Exception {
        book.addSheet("Rotación","Venta en el período y última venta histórica; no implica pronóstico de demanda.",
          period+" · Umbral sin movimiento: "+idleDays+" días (hora Perú)",
          new String[]{"SKU","Producto","Unidad","Stock actual","Cantidad vendida período","Última venta Perú","Días desde última venta","Evaluación"},
          new int[]{18,39,15,18,23,23,25,32},s->{
            String cutoff=DB_UTC.format(LocalDate.now(ReportsService.BUSINESS_ZONE).minusDays(idleDays).atStartOfDay(ReportsService.BUSINESS_ZONE).toInstant());
            try(var ps=query(c,"""
              SELECT p.sku,p.nombre,u.codigo,ss.cantidad_milli,p.creado_en,
                (SELECT MAX(v2.fecha) FROM venta_detalle d2 JOIN venta v2 ON v2.id=d2.venta_id
                 JOIN producto_presentacion xp ON xp.id=d2.presentacion_id
                 WHERE xp.producto_id=p.id AND v2.estado='CONFIRMADA') AS ultima,
                COALESCE((SELECT SUM(d3.cantidad_base_milli) FROM venta_detalle d3 JOIN venta v3 ON v3.id=d3.venta_id
                 JOIN producto_presentacion yp ON yp.id=d3.presentacion_id
                 WHERE yp.producto_id=p.id AND v3.estado='CONFIRMADA' AND v3.fecha>=? AND v3.fecha<?),0)
              FROM producto p JOIN saldo_stock ss ON ss.producto_id=p.id JOIN unidad_medida u ON u.id=p.unidad_base_id
              WHERE p.estado='ACTIVO' ORDER BY p.sku
              """,b);var r=ps.executeQuery()){
              while(r.next()){
                String last=r.getString(6),created=r.getString(5);
                long days=last==null?0:java.time.temporal.ChronoUnit.DAYS.between(
                  Instant.parse(last).atZone(ReportsService.BUSINESS_ZONE).toLocalDate(),LocalDate.now(ReportsService.BUSINESS_ZONE));
                boolean older=created!=null&&created.compareTo(cutoff)<0;
                String label=(last==null)?(older?"Sin ventas registradas (antiguo)":"Nuevo; sin historial suficiente"):
                  (last.compareTo(cutoff)<0?"Sin ventas recientes":"Con ventas recientes");
                s.row(txt(r.getString(1)),txt(r.getString(2)),txt(r.getString(3)),Cell.qty(r.getLong(4)),Cell.qty(r.getLong(7)),
                  txt(peruvian(last)),last==null?Cell.blank():Cell.count(days),txt(label));
              }
            }
        });
    }
    private void demands(SimpleXlsxWriter book,Connection c,String period)throws Exception {
        book.addSheet("Solicitudes","Historial de solicitudes no atendidas: pendientes, atendidas y descartadas.",
          "Historial completo, sin filtro de fechas de ventas. "+period,
          new String[]{"Solicitud #","Fecha (Perú)","Producto solicitado","Marca / modelo","Cantidad conocida","Estado","Cliente","Responsable","Observaciones"},
          new int[]{16,22,40,30,22,19,34,27,55},s->{
            try(var ps=query(c,"""
              SELECT d.id,d.fecha,d.descripcion,d.marca_modelo,d.cantidad_milli,d.estado,
                COALESCE(cl.nombre_razon_social,''),u.nombre,d.observaciones
              FROM demanda_no_atendida d JOIN usuario u ON u.id=d.usuario_id
              LEFT JOIN cliente cl ON cl.id=d.cliente_id ORDER BY d.fecha DESC,d.id DESC
              """);var r=ps.executeQuery()){
              while(r.next()){
                long qty=r.getLong(5);boolean quantity=!r.wasNull();
                s.row(Cell.count(r.getLong(1)),txt(peruvian(r.getString(2))),txt(r.getString(3)),txt(r.getString(4)),
                  quantity?Cell.qty(qty):Cell.blank(),txt(r.getString(6)),txt(r.getString(7)),txt(r.getString(8)),txt(r.getString(9)));
              }
            }
        });
    }
    private void daily(SimpleXlsxWriter book,Connection c,String[] b,String period)throws Exception {
        book.addSheet("Ventas diarias","Resumen de ventas confirmadas por día de operación (Perú).",period,
          new String[]{"Fecha (Perú)","Ventas confirmadas","Total S/"},new int[]{22,24,23},s->{
            rows(c,"""
              SELECT date(v.fecha,'-5 hours'),COUNT(*),SUM(v.total_centavos)
              FROM venta v WHERE v.estado='CONFIRMADA' AND v.fecha>=? AND v.fecha<?
              GROUP BY date(v.fecha,'-5 hours') ORDER BY date(v.fecha,'-5 hours')
              """,b,s,(r,row)->row.row(txt(r.getString(1)),Cell.count(r.getLong(2)),Cell.money(r.getLong(3))));
        });
    }
}
