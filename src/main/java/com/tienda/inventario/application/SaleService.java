package com.tienda.inventario.application;

import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Ventas locales y solicitud de CPE pendiente. NUNCA emite documentos electrónicos. */
public final class SaleService {
    private final Database db;
    public SaleService(Database db){this.db=db;}

    public List<SalePresentation> sellablePresentations(long listId) throws SQLException {
        List<SalePresentation> out=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("""
            SELECT pp.id,p.id,p.sku,p.nombre,pp.nombre,pp.factor_base_milli,u.codigo,u.decimales_permitidos,
                   s.cantidad_milli,pr.precio_micro,
                   (SELECT ROUND(1.0*d.costo_unitario_micro*1000/d.factor_base_milli_snapshot)
                      FROM compra_detalle d JOIN compra co ON co.id=d.compra_id
                      JOIN producto_presentacion cp ON cp.id=d.presentacion_id
                      WHERE cp.producto_id=p.id AND co.estado='CONFIRMADA'
                      ORDER BY co.id DESC,d.id DESC LIMIT 1)
              FROM producto_presentacion pp JOIN producto p ON p.id=pp.producto_id
              JOIN unidad_medida u ON u.id=p.unidad_base_id JOIN saldo_stock s ON s.producto_id=p.id
              JOIN precio_presentacion pr ON pr.presentacion_id=pp.id AND pr.lista_precio_id=? AND pr.vigente_hasta IS NULL
              WHERE pp.se_vende=1 AND pp.activo=1 AND p.estado='ACTIVO' AND pr.precio_micro>0
              ORDER BY p.nombre COLLATE NOCASE,pp.factor_base_milli
            """)){
            ps.setLong(1,listId);try(var rs=ps.executeQuery()){while(rs.next()){
                long cost=rs.getLong(11);Long latestCost=rs.wasNull()?null:cost;
                out.add(new SalePresentation(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),
                        rs.getLong(6),rs.getString(7),rs.getInt(8),rs.getLong(9),rs.getLong(10),latestCost));
            }}
        }
        return out;
    }

    public List<SaleEntry> recent(int limit) throws SQLException {
        List<SaleEntry> out=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("SELECT id,fecha,cliente,total_centavos,tipo_comprobante,estado_comprobante,lineas,pagos FROM vw_ventas_confirmadas ORDER BY id DESC LIMIT ?")){
            ps.setInt(1,Math.min(500,Math.max(limit,1)));try(var rs=ps.executeQuery()){
                while(rs.next())out.add(new SaleEntry(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                        rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8)));
            }
        }
        return out;
    }
    public List<SaleDetailEntry> details(long id) throws SQLException {
        List<SaleDetailEntry> out=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("""
            SELECT sku_snapshot,descripcion_snapshot,cantidad_presentacion_milli,cantidad_base_milli,
                   precio_lista_micro,precio_final_micro,descuento_centavos,impuesto_centavos,total_centavos
            FROM venta_detalle WHERE venta_id=? ORDER BY id
            """)){
            ps.setLong(1,id);try(var rs=ps.executeQuery()){
                while(rs.next())out.add(new SaleDetailEntry(rs.getString(1),rs.getString(2),rs.getLong(3),
                        rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8),rs.getLong(9)));
            }
        }
        return out;
    }
    public List<SalePaymentEntry> payments(long id) throws SQLException {
        List<SalePaymentEntry> out=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("SELECT medio,monto_centavos,referencia FROM venta_pago WHERE venta_id=? ORDER BY id")){
            ps.setLong(1,id);try(var rs=ps.executeQuery()){
                while(rs.next())out.add(new SalePaymentEntry(rs.getString(1),rs.getLong(2),rs.getString(3)));
            }
        }
        return out;
    }

    public SaleResult confirm(NewSale request,Session actor) throws SQLException {
        if(request==null||request.operationUuid()==null||!request.operationUuid().matches("[a-fA-F0-9-]{36}"))
            throw new IllegalArgumentException("Identificador de operación inválido");
        if(!List.of("BOLETA","FACTURA").contains(request.documentType()))throw new IllegalArgumentException("Selecciona boleta o factura");
        if(request.lines()==null||request.lines().isEmpty()||request.lines().size()>150)
            throw new IllegalArgumentException("La venta requiere de 1 a 150 líneas");
        if(request.payments()==null||request.payments().isEmpty()||request.payments().size()>8)
            throw new IllegalArgumentException("Registra entre 1 y 8 pagos");
        String hash=hashInput(request);
        return db.inTransaction(c->{
            CustomerService.requireSeller(c,actor);
            try(var ps=c.prepareStatement("SELECT v.id,v.estado,v.solicitud_hash,v.total_centavos,(SELECT tipo FROM comprobante WHERE venta_id=v.id),(SELECT estado FROM comprobante WHERE venta_id=v.id),(SELECT COUNT(*) FROM venta_detalle WHERE venta_id=v.id),v.vendedor_id FROM venta v WHERE v.operacion_uuid=?")){
                ps.setString(1,request.operationUuid());try(var rs=ps.executeQuery()){
                    if(rs.next()){
                        if(!actor.isAdmin() && rs.getLong(8)!=actor.userId())throw new SecurityException("La operación pertenece a otro vendedor");
                        if(!hash.equals(rs.getString(3)))throw new IllegalArgumentException("La operación ya existe con otros datos");
                        if(!"CONFIRMADA".equals(rs.getString(2)))throw new IllegalStateException("Hay una operación incompleta con ese identificador");
                        return new SaleResult(rs.getLong(1),rs.getLong(4),rs.getInt(7),rs.getString(5),rs.getString(6),true);
                    }
                }
            }
            CustomerData customer=customer(c,request.customerId());
            long publicList=CustomerService.publicId(c);
            long allowed=customer==null||customer.priceListId()==null?publicList:customer.priceListId();
            if(!actor.isAdmin() && request.priceListId()!=allowed)
                throw new SecurityException("La lista seleccionada no corresponde al cliente. Solicita autorización al administrador");
            try(var p=c.prepareStatement("SELECT 1 FROM lista_precio WHERE id=? AND activo=1")){
                p.setLong(1,request.priceListId());try(var rs=p.executeQuery()){
                    if(!rs.next())throw new IllegalArgumentException("Lista de precios inexistente o inactiva");
                }
            }
            if("FACTURA".equals(request.documentType())&&
                (customer==null||!"RUC".equals(customer.documentType())||customer.name().isBlank()))
                throw new IllegalArgumentException("Factura requiere cliente identificado con RUC y razón social");
            Map<Long,Long> demands=new LinkedHashMap<>();
            List<PreparedLine> lines=new ArrayList<>();
            long total=0,subtotal=0,discount=0,tax=0;
            Set<Long> duplicate=new HashSet<>();
            for(SaleLineInput line:request.lines()){
                if(line==null||line.quantity()==null||line.finalUnitPrice()==null)throw new IllegalArgumentException("Línea incompleta");
                if(!duplicate.add(line.presentationId()))throw new IllegalArgumentException("Presentación duplicada; suma cantidades en una sola línea");
                SalePresentation p=findPresentation(c,line.presentationId(),request.priceListId());
                if(line.quantity().signum()<=0)throw new IllegalArgumentException("Cantidad de venta debe ser positiva");
                int decimals=p.factorMilli()==1000?p.unitDecimals():0;
                long quantity=DecimalUnits.toMilli(line.quantity(),decimals);
                long base;
                try{long multiplied=Math.multiplyExact(quantity,p.factorMilli());
                    if(multiplied%1000!=0)throw new IllegalArgumentException("Cantidad incompatible con presentación");
                    base=multiplied/1000;
                }catch(ArithmeticException ex){throw new IllegalArgumentException("Cantidad demasiado grande");}
                if(base<=0)throw new IllegalArgumentException("Cantidad inválida");
                long finalPrice=DecimalUnits.toPriceMicro(line.finalUnitPrice());
                if(finalPrice<=0||finalPrice>p.listPriceMicro())throw new IllegalArgumentException("Precio final debe ser mayor a cero y no exceder precio de lista");
                if(!actor.isAdmin() && finalPrice!=p.listPriceMicro())
                    throw new SecurityException("El vendedor no puede modificar precios; solicita autorización al administrador");
                long grossList=grossCents(quantity,p.listPriceMicro());
                long grossFinal=grossCents(quantity,finalPrice);
                if(grossFinal<=0)throw new IllegalArgumentException("Importe inválido");
                long netList=netCents(grossList),netFinal=netCents(grossFinal);
                long d=netList-netFinal,igv=grossFinal-netFinal;
                demands.merge(p.productId(),base,Math::addExact);
                total=Math.addExact(total,grossFinal);subtotal=Math.addExact(subtotal,netList);
                discount=Math.addExact(discount,d);tax=Math.addExact(tax,igv);
                lines.add(new PreparedLine(p,quantity,base,finalPrice,d,igv,grossFinal));
            }
            if(subtotal-discount+tax!=total)throw new IllegalStateException("Los importes no cuadran");
            for(var need:demands.entrySet()){
                try(var ps=c.prepareStatement("SELECT cantidad_milli FROM saldo_stock WHERE producto_id=?")){
                    ps.setLong(1,need.getKey());try(var rs=ps.executeQuery()){
                        if(!rs.next()||rs.getLong(1)<need.getValue())throw new IllegalArgumentException("Stock insuficiente para el producto #"+need.getKey());
                    }
                }
            }
            // Esta condición de identificación se validará nuevamente con el proveedor tributario elegido.
            if("BOLETA".equals(request.documentType())&&total>70000&&
                    (customer==null||customer.documentType()==null||customer.documentType().equals("OTRO")))
                throw new IllegalArgumentException("Boleta mayor de S/ 700 requiere identificar al cliente con DNI o RUC");
            List<PreparedPayment> payments=new ArrayList<>();long paid=0;
            for(SalePaymentInput pay:request.payments()){
                if(pay==null||!List.of("EFECTIVO","YAPE","PLIN","TARJETA","TRANSFERENCIA","OTRO").contains(pay.method()))
                    throw new IllegalArgumentException("Método de pago inválido");
                long cents=toCents(pay.amount());if(cents<=0)throw new IllegalArgumentException("El pago debe ser positivo");
                paid=Math.addExact(paid,cents);
                String reference=SupplierService.optional(pay.reference(),120);
                payments.add(new PreparedPayment(pay.method(),cents,reference));
            }
            if(paid!=total)throw new IllegalArgumentException("Los pagos suman "+money(paid)+" y la venta vale "+money(total));
            long id;
            try(var ps=c.prepareStatement("""
                INSERT INTO venta(operacion_uuid,vendedor_id,cliente_id,cliente_nombre_snapshot,cliente_tipo_documento_snapshot,
                 cliente_documento_snapshot,lista_precio_id,subtotal_centavos,descuento_centavos,impuesto_centavos,total_centavos,solicitud_hash)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """,Statement.RETURN_GENERATED_KEYS)){
                ps.setString(1,request.operationUuid());ps.setLong(2,actor.userId());
                if(customer==null)ps.setNull(3,Types.INTEGER);else ps.setLong(3,customer.id());
                ps.setString(4,customer==null?"Consumidor final":customer.name());
                ps.setString(5,customer==null?null:customer.documentType());
                ps.setString(6,customer==null?null:customer.documentNumber());
                ps.setLong(7,request.priceListId());ps.setLong(8,subtotal);ps.setLong(9,discount);
                ps.setLong(10,tax);ps.setLong(11,total);ps.setString(12,hash);ps.executeUpdate();id=key(ps);
            }
            try(var ps=c.prepareStatement("""
                INSERT INTO venta_detalle(venta_id,presentacion_id,sku_snapshot,descripcion_snapshot,
                cantidad_presentacion_milli,factor_base_milli_snapshot,cantidad_base_milli,precio_lista_micro,
                precio_final_micro,descuento_centavos,impuesto_centavos,total_centavos,costo_base_micro_snapshot)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)){
                for(PreparedLine l:lines){
                    ps.setLong(1,id);ps.setLong(2,l.p().id());ps.setString(3,l.p().sku());
                    ps.setString(4,l.p().product()+" · "+l.p().presentation());
                    ps.setLong(5,l.quantity());ps.setLong(6,l.p().factorMilli());ps.setLong(7,l.base());
                    ps.setLong(8,l.p().listPriceMicro());ps.setLong(9,l.finalPrice());ps.setLong(10,l.discount());
                    ps.setLong(11,l.igv());ps.setLong(12,l.total());
                    if(l.p().latestCostBaseMicro()==null)ps.setNull(13,Types.INTEGER);
                    else ps.setLong(13,l.p().latestCostBaseMicro());ps.addBatch();
                }
                ps.executeBatch();
            }
            try(var ps=c.prepareStatement("INSERT INTO venta_pago(venta_id,medio,monto_centavos,referencia) VALUES(?,?,?,?)")){
                for(PreparedPayment p:payments){ps.setLong(1,id);ps.setString(2,p.method());ps.setLong(3,p.cents());ps.setString(4,p.reference());ps.addBatch();}
                ps.executeBatch();
            }
            try(var ps=c.prepareStatement("INSERT INTO comprobante(venta_id,tipo,idempotency_key) VALUES(?,?,?)")){
                ps.setLong(1,id);ps.setString(2,request.documentType());ps.setString(3,"CPE:"+request.operationUuid());ps.executeUpdate();
            }
            // Confirmar dispara salida de stock; una insuficiencia cancela TODA la transacción.
            try(var ps=c.prepareStatement("UPDATE venta SET estado='CONFIRMADA',confirmada_en=? WHERE id=? AND estado='BORRADOR'")){
                ps.setString(1,Instant.now().toString());ps.setLong(2,id);
                if(ps.executeUpdate()!=1)throw new SQLException("No se pudo confirmar venta");
            }
            try(var ps=c.prepareStatement("INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,despues_json,motivo) VALUES(?,'venta',?,'CONFIRMAR',json_object('total_centavos',?,'lineas',?,'pagos',?),'Venta confirmada, comprobante PENDIENTE de emisión')")){
                ps.setLong(1,actor.userId());ps.setLong(2,id);ps.setLong(3,total);ps.setInt(4,lines.size());ps.setInt(5,payments.size());ps.executeUpdate();
            }
            return new SaleResult(id,total,lines.size(),request.documentType(),"PENDIENTE",false);
        });
    }

    /** Precio al público con IGV incluido. Para este piloto se presupone tasa 18%. */
    public static long grossCents(long quantityMilli,long unitPriceMicro){
        if(quantityMilli<0||unitPriceMicro<0)throw new IllegalArgumentException("Importe negativo");
        return BigDecimal.valueOf(quantityMilli).multiply(BigDecimal.valueOf(unitPriceMicro))
                .divide(BigDecimal.valueOf(100_000),0,RoundingMode.HALF_UP).longValueExact();
    }
    public static long netCents(long grossCents){
        return BigDecimal.valueOf(grossCents).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(118),0,RoundingMode.HALF_UP).longValueExact();
    }
    public static long toCents(BigDecimal amount){
        if(amount==null||amount.signum()<0||amount.stripTrailingZeros().scale()>2)
            throw new IllegalArgumentException("Los pagos solo admiten dos decimales");
        return amount.movePointRight(2).longValueExact();
    }
    private static String money(long cents){return "S/ "+BigDecimal.valueOf(cents,2).toPlainString();}
    private static long key(PreparedStatement ps) throws SQLException {
        try(var rs=ps.getGeneratedKeys()){if(rs.next())return rs.getLong(1);}throw new SQLException("Sin identificador generado");
    }
    private static CustomerData customer(Connection c,Long id) throws SQLException {
        if(id==null)return null;
        try(var ps=c.prepareStatement("SELECT id,nombre_razon_social,tipo_documento,numero_documento,lista_precio_id FROM cliente WHERE id=? AND activo=1")){
            ps.setLong(1,id);try(var rs=ps.executeQuery()){
                if(!rs.next())throw new IllegalArgumentException("Cliente inexistente o inactivo");
                long list=rs.getLong(5);return new CustomerData(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.wasNull()?null:list);
            }
        }
    }
    private static SalePresentation findPresentation(Connection c,long presentationId,long listId) throws SQLException {
        try(var ps=c.prepareStatement("""
            SELECT pp.id,p.id,p.sku,p.nombre,pp.nombre,pp.factor_base_milli,u.codigo,u.decimales_permitidos,s.cantidad_milli,
                   ph.precio_micro,
                   (SELECT d.costo_unitario_micro FROM compra_detalle d JOIN compra co ON co.id=d.compra_id
                      JOIN producto_presentacion xp ON xp.id=d.presentacion_id
                      WHERE xp.producto_id=p.id AND co.estado='CONFIRMADA' ORDER BY co.id DESC,d.id DESC LIMIT 1),
                   (SELECT d.factor_base_milli_snapshot FROM compra_detalle d JOIN compra co ON co.id=d.compra_id
                      JOIN producto_presentacion xp ON xp.id=d.presentacion_id
                      WHERE xp.producto_id=p.id AND co.estado='CONFIRMADA' ORDER BY co.id DESC,d.id DESC LIMIT 1)
            FROM producto_presentacion pp JOIN producto p ON p.id=pp.producto_id
            JOIN saldo_stock s ON s.producto_id=p.id JOIN unidad_medida u ON u.id=p.unidad_base_id
            JOIN precio_presentacion ph ON ph.presentacion_id=pp.id AND ph.lista_precio_id=? AND ph.vigente_hasta IS NULL
            WHERE pp.id=? AND pp.activo=1 AND pp.se_vende=1 AND p.estado='ACTIVO' AND ph.precio_micro>0
            """)){
            ps.setLong(1,listId);ps.setLong(2,presentationId);try(var rs=ps.executeQuery()){
                if(!rs.next())throw new IllegalArgumentException("Presentación sin precio para la lista escogida");
                long cMicro=rs.getLong(11);Long cost=null;
                if(!rs.wasNull()){
                    long factor=rs.getLong(12);
                    if(factor>0)cost=BigDecimal.valueOf(cMicro).multiply(BigDecimal.valueOf(1000))
                            .divide(BigDecimal.valueOf(factor),0,RoundingMode.HALF_UP).longValueExact();
                }
                return new SalePresentation(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),
                        rs.getLong(6),rs.getString(7),rs.getInt(8),rs.getLong(9),rs.getLong(10),cost);
            }
        }
    }
    private static String hashInput(NewSale request){
        try{
            StringBuilder b=new StringBuilder();
            add(b,request.operationUuid());add(b,String.valueOf(request.customerId()));add(b,String.valueOf(request.priceListId()));add(b,request.documentType());
            for(SaleLineInput l:request.lines()){
                if(l==null||l.quantity()==null||l.finalUnitPrice()==null)throw new IllegalArgumentException("Línea vacía");
                add(b,"L");add(b,String.valueOf(l.presentationId()));add(b,l.quantity().stripTrailingZeros().toPlainString());add(b,l.finalUnitPrice().stripTrailingZeros().toPlainString());
            }
            for(SalePaymentInput p:request.payments()){
                if(p==null||p.amount()==null)throw new IllegalArgumentException("Pago vacío");
                add(b,"P");add(b,p.method());add(b,p.amount().stripTrailingZeros().toPlainString());add(b,p.reference()==null?"":p.reference().trim());
            }
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void add(StringBuilder b,String s){String t=String.valueOf(s);b.append(t.length()).append(':').append(t);}
    private record CustomerData(long id,String name,String documentType,String documentNumber,Long priceListId){}
    private record PreparedLine(SalePresentation p,long quantity,long base,long finalPrice,long discount,long igv,long total){}
    private record PreparedPayment(String method,long cents,String reference){}
}
