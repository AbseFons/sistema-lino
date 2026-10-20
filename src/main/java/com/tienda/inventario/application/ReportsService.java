package com.tienda.inventario.application;

import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import java.sql.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Informes operativos: NO equivalen a facturación tributaria ni a utilidad contable. */
public final class ReportsService {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Lima");
    private static final DateTimeFormatter DB_UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private final Database db;
    public ReportsService(Database db) { this.db = db; }

    private static String[] boundaries(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("Rango de fechas inválido");
        if (from.plusYears(3).isBefore(to)) throw new IllegalArgumentException("Consulta de más de 3 años: reduce el período");
        return new String[]{DB_UTC.format(from.atStartOfDay(BUSINESS_ZONE).toInstant()),
                DB_UTC.format(to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant())};
    }
    private static void range(PreparedStatement ps, String[] b) throws SQLException {
        ps.setString(1,b[0]);ps.setString(2,b[1]);
    }

    public SalesSummary summary(LocalDate from, LocalDate to) throws SQLException {
        String[] b = boundaries(from,to);
        try (Connection c=db.open(); PreparedStatement ps=c.prepareStatement("""
            SELECT COUNT(*),COALESCE(SUM(total_centavos),0),COALESCE(SUM(descuento_centavos),0)
            FROM venta WHERE estado='CONFIRMADA' AND fecha>=? AND fecha<?
            """)) {
            range(ps,b);
            try (ResultSet r=ps.executeQuery()) {r.next();long n=r.getLong(1),total=r.getLong(2);
                return new SalesSummary(n,total,r.getLong(3),n==0?0:BigDecimal.valueOf(total).divide(BigDecimal.valueOf(n),0,RoundingMode.HALF_UP).longValueExact());}
        }
    }

    public List<SalesByDay> daily(LocalDate from, LocalDate to) throws SQLException {
        String[] b=boundaries(from,to);List<SalesByDay> data=new ArrayList<>();
        try (Connection c=db.open(); PreparedStatement ps=c.prepareStatement("""
            SELECT date(fecha,'-5 hours') AS dia,COUNT(*),SUM(total_centavos)
            FROM venta WHERE estado='CONFIRMADA' AND fecha>=? AND fecha<?
            GROUP BY dia ORDER BY dia
            """)) {
            range(ps,b);try (ResultSet r=ps.executeQuery()) {while(r.next())data.add(new SalesByDay(r.getString(1),r.getLong(2),r.getLong(3)));}
        }
        return data;
    }

    public List<SalesByPayment> paymentMix(LocalDate from,LocalDate to) throws SQLException {
        String[] b=boundaries(from,to);List<SalesByPayment> data=new ArrayList<>();
        try (Connection c=db.open(); PreparedStatement ps=c.prepareStatement("""
            SELECT pg.medio,SUM(pg.monto_centavos),COUNT(*) FROM venta_pago pg
            JOIN venta v ON v.id=pg.venta_id
            WHERE v.estado='CONFIRMADA' AND v.fecha>=? AND v.fecha<?
            GROUP BY pg.medio ORDER BY SUM(pg.monto_centavos) DESC
            """)) {
            range(ps,b);try(ResultSet r=ps.executeQuery()){while(r.next())data.add(new SalesByPayment(r.getString(1),r.getLong(2),r.getLong(3)));}
        }
        return data;
    }

    public List<ProductSales> topProducts(LocalDate from,LocalDate to,int limit) throws SQLException {
        String[] b=boundaries(from,to);List<ProductSales> data=new ArrayList<>();
        try(Connection c=db.open();PreparedStatement ps=c.prepareStatement("""
            SELECT p.sku,p.nombre,u.codigo,SUM(vd.cantidad_base_milli),SUM(vd.total_centavos),COUNT(DISTINCT v.id)
            FROM venta_detalle vd JOIN venta v ON v.id=vd.venta_id
            JOIN producto_presentacion pp ON pp.id=vd.presentacion_id
            JOIN producto p ON p.id=pp.producto_id
            JOIN unidad_medida u ON u.id=p.unidad_base_id
            WHERE v.estado='CONFIRMADA' AND v.fecha>=? AND v.fecha<?
            GROUP BY p.id ORDER BY SUM(vd.total_centavos) DESC,p.sku LIMIT ?
            """)) {
            range(ps,b);ps.setInt(3,Math.max(1,Math.min(limit,100)));
            try(ResultSet r=ps.executeQuery()){while(r.next())data.add(new ProductSales(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getLong(5),r.getLong(6)));}
        }
        return data;
    }

    /** Sin ventas CONFIRMADAS en N días y con stock físico positivo.
     * Excluye productos nuevos para no calificarlos prematuramente como inactivos.
     */
    public List<IdleProduct> inactiveProducts(int days,int limit) throws SQLException {
        if(days!=30 && days!=60 && days!=90 && days!=180)throw new IllegalArgumentException("Plazo inválido");
        Instant cutoff=LocalDate.now(BUSINESS_ZONE).minusDays(days).atStartOfDay(BUSINESS_ZONE).toInstant();
        List<IdleProduct> out=new ArrayList<>();
        try(Connection c=db.open();PreparedStatement ps=c.prepareStatement("""
            SELECT p.sku,p.nombre,u.codigo,s.cantidad_milli,
               (SELECT MAX(v2.fecha) FROM venta_detalle vd2
                JOIN producto_presentacion pp2 ON pp2.id=vd2.presentacion_id
                JOIN venta v2 ON v2.id=vd2.venta_id
                WHERE pp2.producto_id=p.id AND v2.estado='CONFIRMADA') AS ultima_venta
            FROM producto p JOIN saldo_stock s ON s.producto_id=p.id
            JOIN unidad_medida u ON u.id=p.unidad_base_id
            WHERE p.estado='ACTIVO' AND s.cantidad_milli>0 AND p.creado_en<?
              AND NOT EXISTS (SELECT 1 FROM venta_detalle vd
                JOIN producto_presentacion pp ON pp.id=vd.presentacion_id
                JOIN venta v ON v.id=vd.venta_id
                WHERE pp.producto_id=p.id AND v.estado='CONFIRMADA' AND v.fecha>=?)
            ORDER BY p.nombre LIMIT ?
            """)) {
            String cutoffDb=DB_UTC.format(cutoff);
            ps.setString(1,cutoffDb);ps.setString(2,cutoffDb);ps.setInt(3,Math.max(1,Math.min(limit,500)));
            try(ResultSet r=ps.executeQuery()){while(r.next())out.add(new IdleProduct(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5)));}
        }
        return out;
    }
}
