package com.tienda.inventario.application;

import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Una confirmación es atómica: compra -> detalles -> confirmación -> trigger de kardex -> auditoría. */
public final class PurchaseService {
    private final Database db;
    public PurchaseService(Database db){this.db=db;}

    public List<PurchasePresentation> buyablePresentations() throws SQLException {
        List<PurchasePresentation> result=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("""
                SELECT pp.id,p.id,p.sku,p.nombre,pp.nombre,pp.factor_base_milli,u.codigo,u.decimales_permitidos,
                       uc.costo_unitario_micro
                FROM producto_presentacion pp
                JOIN producto p ON p.id=pp.producto_id
                JOIN unidad_medida u ON u.id=p.unidad_base_id
                LEFT JOIN vw_ultimo_costo_compra uc ON uc.presentacion_id=pp.id
                WHERE pp.activo=1 AND pp.se_compra=1 AND p.estado='ACTIVO'
                ORDER BY p.nombre COLLATE NOCASE,pp.factor_base_milli
                """);var rs=ps.executeQuery()) {
            while(rs.next()){
                long cost=rs.getLong(9); Long lastCost=rs.wasNull()?null:cost;
                result.add(new PurchasePresentation(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),
                        rs.getString(5),rs.getLong(6),rs.getString(7),rs.getInt(8),lastCost));
            }
        }
        return result;
    }

    public List<PurchaseEntry> recentPurchases(int limit) throws SQLException {
        List<PurchaseEntry> result=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("""
                SELECT c.id,c.fecha_compra,COALESCE(c.proveedor_nombre_snapshot,p.razon_social,'Sin proveedor'),c.numero_documento,
                       c.total_centavos,(SELECT COUNT(*) FROM compra_detalle d WHERE d.compra_id=c.id),c.confirmada_en
                  FROM compra c LEFT JOIN proveedor p ON p.id=c.proveedor_id
                 WHERE c.estado='CONFIRMADA' ORDER BY c.id DESC LIMIT ?
                """)){
            ps.setInt(1,Math.max(1,Math.min(limit,500)));
            try(var rs=ps.executeQuery()){while(rs.next())result.add(new PurchaseEntry(rs.getLong(1),rs.getString(2),
                    rs.getString(3),rs.getString(4),rs.getLong(5),rs.getInt(6),rs.getString(7)));}
        }
        return result;
    }

    public List<PurchaseDetailEntry> details(long purchaseId) throws SQLException {
        List<PurchaseDetailEntry> result=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("""
                SELECT p.sku,p.nombre,pp.nombre,d.cantidad_presentacion_milli,d.cantidad_base_milli,
                       d.costo_unitario_micro,d.importe_centavos
                  FROM compra_detalle d JOIN producto_presentacion pp ON pp.id=d.presentacion_id
                  JOIN producto p ON p.id=pp.producto_id WHERE d.compra_id=? ORDER BY d.id
                """)) {
            ps.setLong(1,purchaseId);try(var rs=ps.executeQuery()){while(rs.next())result.add(new PurchaseDetailEntry(
                    rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7)));}
        }
        return result;
    }

    public PurchaseResult confirm(NewPurchase purchase,Session actor) throws SQLException {
        if(purchase==null||purchase.date()==null)throw new IllegalArgumentException("Selecciona la fecha de compra");
        if(purchase.date().isAfter(LocalDate.now()))throw new IllegalArgumentException("La fecha de compra no puede ser futura");
        if(purchase.lines()==null||purchase.lines().isEmpty())throw new IllegalArgumentException("Agrega al menos un producto");
        if(purchase.lines().size()>250)throw new IllegalArgumentException("Máximo 250 líneas por compra");
        String document=SupplierService.optional(purchase.documentNumber(),80);
        return db.inTransaction(c -> {
            SupplierService.requireAdmin(c,actor);
            String supplierName=null,supplierRuc=null;
            if(purchase.supplierId()!=null){
                try(var ps=c.prepareStatement("SELECT razon_social,ruc FROM proveedor WHERE id=? AND activo=1")){
                    ps.setLong(1,purchase.supplierId());try(var rs=ps.executeQuery()){
                        if(!rs.next())throw new IllegalArgumentException("El proveedor no está activo");
                        supplierName=rs.getString(1);supplierRuc=rs.getString(2);
                    }
                }
            }
            // Snapshot inmutable por cada línea (presentación y precio al comprar).
            List<PreparedLine> prepared=new ArrayList<>();
            long totalCents=0;int costChanges=0;
            for(PurchaseLineInput line:purchase.lines()){
                if(line==null||line.quantity()==null||line.unitCost()==null)throw new IllegalArgumentException("Cantidad y costo son obligatorios");
                PurchasePresentation p=buyable(c,line.presentationId());
                if(line.quantity().signum()<=0)throw new IllegalArgumentException("La cantidad debe ser mayor a cero: "+p.sku());
                int maxDecimals=p.factorMilli()==1000?p.unitDecimals():0;
                long quantityMilli=DecimalUnits.toMilli(line.quantity(),maxDecimals);
                long baseMilli;
                try{
                    long multiplied=Math.multiplyExact(quantityMilli,p.factorMilli());
                    if(multiplied%1000!=0)throw new IllegalArgumentException("Cantidad incompatible con el factor de presentación");
                    baseMilli=multiplied/1000;
                }catch(ArithmeticException ex){throw new IllegalArgumentException("Cantidad demasiado grande para la presentación");}
                if(baseMilli<=0)throw new IllegalArgumentException("Cantidad en unidad base inválida");
                long costMicro=DecimalUnits.toPriceMicro(line.unitCost());
                if(costMicro<=0)throw new IllegalArgumentException("El costo debe ser mayor a cero");
                long lineCents=lineCents(quantityMilli,costMicro);
                if(lineCents<=0)throw new IllegalArgumentException("El importe del detalle debe ser mayor a cero");
                totalCents=Math.addExact(totalCents,lineCents);
                if(p.latestCostMicro()!=null && p.latestCostMicro().longValue()!=costMicro)costChanges++;
                prepared.add(new PreparedLine(p.id(),quantityMilli,p.factorMilli(),baseMilli,costMicro,lineCents));
            }
            long purchaseId;
            try(var ps=c.prepareStatement("""
                    INSERT INTO compra(proveedor_id,numero_documento,fecha_compra,total_centavos,usuario_id,
                    proveedor_nombre_snapshot,proveedor_ruc_snapshot)
                    VALUES(?,?,?,?,?,?,?)
                    """,Statement.RETURN_GENERATED_KEYS)){
                if(purchase.supplierId()==null)ps.setNull(1,java.sql.Types.INTEGER);else ps.setLong(1,purchase.supplierId());
                ps.setString(2,document);ps.setString(3,purchase.date().toString());ps.setLong(4,totalCents);
                ps.setLong(5,actor.userId());ps.setString(6,supplierName);ps.setString(7,supplierRuc);
                ps.executeUpdate();purchaseId=key(ps);
            }
            try(var ps=c.prepareStatement("""
                    INSERT INTO compra_detalle(compra_id,presentacion_id,cantidad_presentacion_milli,
                    factor_base_milli_snapshot,cantidad_base_milli,costo_unitario_micro,importe_centavos)
                    VALUES(?,?,?,?,?,?,?)
                    """)){
                for(PreparedLine line:prepared){
                    ps.setLong(1,purchaseId);ps.setLong(2,line.presentationId());ps.setLong(3,line.quantityMilli());
                    ps.setLong(4,line.factorMilli());ps.setLong(5,line.baseMilli());ps.setLong(6,line.costMicro());
                    ps.setLong(7,line.lineCents());ps.addBatch();
                }
                ps.executeBatch();
            }
            // Los triggers validan el total y generan todos los movimientos, sin escrituras manuales de saldo.
            try(var ps=c.prepareStatement("UPDATE compra SET estado='CONFIRMADA',confirmada_en=? WHERE id=? AND estado='BORRADOR'")){
                ps.setString(1,Instant.now().toString());ps.setLong(2,purchaseId);
                if(ps.executeUpdate()!=1)throw new SQLException("No se pudo confirmar la compra");
            }
            try(var ps=c.prepareStatement("""
                    INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,despues_json,motivo)
                    VALUES(?,'compra',?,'CONFIRMAR',json_object('total_centavos',?,'lineas',?,'proveedor_id',?),'Registro de compra confirmada')
                    """)){
                ps.setLong(1,actor.userId());ps.setLong(2,purchaseId);ps.setLong(3,totalCents);
                ps.setInt(4,prepared.size());if(purchase.supplierId()==null)ps.setNull(5,java.sql.Types.INTEGER);
                else ps.setLong(5,purchase.supplierId());ps.executeUpdate();
            }
            return new PurchaseResult(purchaseId,totalCents,prepared.size(),costChanges);
        });
    }

    /** Cálculo de importe por línea, con HALF_UP a céntimos; nunca con double. */
    public static long lineCents(long quantityMilli,long unitCostMicro){
        if(quantityMilli<=0||unitCostMicro<=0)throw new IllegalArgumentException("Cantidad y costo deben ser positivos");
        return BigDecimal.valueOf(quantityMilli).multiply(BigDecimal.valueOf(unitCostMicro))
                .divide(BigDecimal.valueOf(100_000),0,RoundingMode.HALF_UP).longValueExact();
    }

    private static PurchasePresentation buyable(Connection c,long presentationId) throws SQLException {
        try(var ps=c.prepareStatement("""
                SELECT pp.id,p.id,p.sku,p.nombre,pp.nombre,pp.factor_base_milli,u.codigo,u.decimales_permitidos,
                       (SELECT d.costo_unitario_micro FROM compra_detalle d JOIN compra co ON co.id=d.compra_id
                        WHERE d.presentacion_id=pp.id AND co.estado='CONFIRMADA'
                        ORDER BY co.id DESC,d.id DESC LIMIT 1)
                FROM producto_presentacion pp JOIN producto p ON p.id=pp.producto_id
                JOIN unidad_medida u ON u.id=p.unidad_base_id
                WHERE pp.id=? AND pp.activo=1 AND pp.se_compra=1 AND p.estado='ACTIVO'
                """)){
            ps.setLong(1,presentationId);try(var rs=ps.executeQuery()){
                if(!rs.next())throw new IllegalArgumentException("Presentación no disponible para compras: "+presentationId);
                long cost=rs.getLong(9);Long previous=rs.wasNull()?null:cost;
                return new PurchasePresentation(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),
                        rs.getLong(6),rs.getString(7),rs.getInt(8),previous);
            }
        }
    }
    private static long key(java.sql.PreparedStatement ps) throws SQLException {
        try(var rs=ps.getGeneratedKeys()){if(rs.next())return rs.getLong(1);}
        throw new SQLException("No se recibió el ID de la compra");
    }
    private record PreparedLine(long presentationId,long quantityMilli,long factorMilli,long baseMilli,
                                long costMicro,long lineCents) {}
}
