package com.tienda.inventario.application;

import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Updates ficha, price versions and audited stock ledger; never writes saldo_stock directly. */
public final class ProductMaintenanceService {
    private static final DateTimeFormatter ISO_MS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private final Database db;

    public ProductMaintenanceService(Database db) { this.db = db; }

    public ProductEdit product(long id) throws SQLException {
        try (var c=db.open(); var ps=c.prepareStatement("""
                SELECT p.id,p.sku,p.nombre,p.categoria_id,p.marca_id,p.pais_origen,
                       p.modelo_referencia,p.observaciones,u.decimales_permitidos,u.codigo,
                       p.stock_min_milli,p.stock_max_milli,p.estado,s.cantidad_milli
                  FROM producto p JOIN unidad_medida u ON u.id=p.unidad_base_id
                  JOIN saldo_stock s ON s.producto_id=p.id WHERE p.id=?
                """)) {
            ps.setLong(1,id);
            try (var rs=ps.executeQuery()) {
                if(!rs.next()) throw new IllegalArgumentException("Producto no encontrado");
                long brand=rs.getLong(5); Long brandId=rs.wasNull()?null:brand;
                long max=rs.getLong(12); Long maxMilli=rs.wasNull()?null:max;
                return new ProductEdit(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),brandId,
                        rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9),rs.getString(10),
                        rs.getLong(11),maxMilli,rs.getString(13),rs.getLong(14));
            }
        }
    }

    /** Name, category, brand and inventory thresholds are editable; SKU and base unit remain fixed. */
    public void updateProduct(long id,String name,long categoryId,Long brandId,String country,String model,
                              String notes,BigDecimal min,BigDecimal max,Session actor) throws SQLException {
        String cleanName=required(name,150,"Nombre");
        String cleanCountry=optional(country,100), cleanModel=optional(model,150),cleanNotes=optional(notes,1000);
        db.inTransaction(c -> {
            admin(c,actor);
            ProductEdit old=load(c,id);
            long minMilli=DecimalUnits.toMilli(min,old.unitDecimals());
            Long maxMilli=max==null?null:DecimalUnits.toMilli(max,old.unitDecimals());
            if(maxMilli!=null && maxMilli<minMilli) throw new IllegalArgumentException("Stock máximo menor al mínimo");
            requireActive(c,"categoria",categoryId);
            if(brandId!=null) requireActive(c,"marca",brandId);
            try(var ps=c.prepareStatement("""
                    UPDATE producto SET nombre=?,categoria_id=?,marca_id=?,pais_origen=?,modelo_referencia=?,
                    observaciones=?,stock_min_milli=?,stock_max_milli=?,actualizado_en=? WHERE id=?
                    """)) {
                ps.setString(1,cleanName);ps.setLong(2,categoryId);
                if(brandId==null)ps.setNull(3,java.sql.Types.INTEGER);else ps.setLong(3,brandId);
                ps.setString(4,cleanCountry);ps.setString(5,cleanModel);ps.setString(6,cleanNotes);
                ps.setLong(7,minMilli);if(maxMilli==null)ps.setNull(8,java.sql.Types.INTEGER);else ps.setLong(8,maxMilli);
                ps.setString(9,now());ps.setLong(10,id);
                if(ps.executeUpdate()!=1) throw new SQLException("No se pudo actualizar producto");
            }
            try(var ps=c.prepareStatement("""
                    INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,antes_json,despues_json,motivo)
                    VALUES(?,'producto',?,'ACTUALIZAR',
                    json_object('nombre',?,'categoria_id',?,'marca_id',?,'stock_min_milli',?,'stock_max_milli',?,
                                'pais_origen',?,'modelo_referencia',?,'observaciones',?),
                    json_object('nombre',?,'categoria_id',?,'marca_id',?,'stock_min_milli',?,'stock_max_milli',?,
                                'pais_origen',?,'modelo_referencia',?,'observaciones',?),
                    'Edición de ficha de producto')
                    """)) {
                int n=1;ps.setLong(n++,actor.userId());ps.setLong(n++,id);
                ps.setString(n++,old.name());ps.setLong(n++,old.categoryId());setNullable(ps,n++,old.brandId());
                ps.setLong(n++,old.minimumMilli());setNullable(ps,n++,old.maximumMilli());
                ps.setString(n++,old.country());ps.setString(n++,old.model());ps.setString(n++,old.notes());
                ps.setString(n++,cleanName);ps.setLong(n++,categoryId);setNullable(ps,n++,brandId);
                ps.setLong(n++,minMilli);setNullable(ps,n++,maxMilli);
                ps.setString(n++,cleanCountry);ps.setString(n++,cleanModel);ps.setString(n++,cleanNotes);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void setProductActive(long id,boolean active,Session actor) throws SQLException {
        db.inTransaction(c -> {
            admin(c,actor);
            ProductEdit p=load(c,id);
            String next=active?"ACTIVO":"INACTIVO";
            if(p.status().equals(next)) throw new IllegalArgumentException("El producto ya tiene ese estado");
            if(active){ requireActive(c,"categoria",p.categoryId()); if(p.brandId()!=null)requireActive(c,"marca",p.brandId()); }
            try(var ps=c.prepareStatement("UPDATE producto SET estado=?,actualizado_en=? WHERE id=?")){
                ps.setString(1,next);ps.setString(2,now());ps.setLong(3,id);ps.executeUpdate();
            }
            audit(c,actor,"producto",id,active?"ACTUALIZAR":"DESACTIVAR",
                    "Estado anterior: "+p.status()+" → "+next);
            return null;
        });
    }

    public long changePrice(long presentationId,long listId,BigDecimal soles,String reason,Session actor) throws SQLException {
        long price=DecimalUnits.toPriceMicro(soles);
        if(price==0) throw new IllegalArgumentException("El precio debe ser mayor que cero");
        String why=required(reason,300,"Motivo");
        if(why.length()<5) throw new IllegalArgumentException("Explica brevemente el motivo del cambio (mínimo 5 caracteres)");
        return db.inTransaction(c -> {
            admin(c,actor);
            try(var ps=c.prepareStatement("""
                    SELECT 1 FROM producto_presentacion pp JOIN producto p ON p.id=pp.producto_id
                    WHERE pp.id=? AND pp.activo=1 AND p.estado='ACTIVO'
                    """)){ps.setLong(1,presentationId);try(var rs=ps.executeQuery()){
                if(!rs.next()) throw new IllegalArgumentException("La presentación o el producto no está activo");
            }}
            requireActive(c,"lista_precio",listId);
            Long oldId=null,oldPrice=null;
            String since=null;
            try(var ps=c.prepareStatement("""
                    SELECT id,precio_micro,vigente_desde FROM precio_presentacion
                    WHERE presentacion_id=? AND lista_precio_id=? AND vigente_hasta IS NULL
                    """)){
                ps.setLong(1,presentationId);ps.setLong(2,listId);
                try(var rs=ps.executeQuery()){if(rs.next()){
                    oldId=rs.getLong(1);oldPrice=rs.getLong(2);since=rs.getString(3);
                }}
            }
            if(oldPrice!=null && oldPrice==price) throw new IllegalArgumentException("El nuevo precio coincide con el vigente");
            String instant=now();
            if(since!=null && instant.compareTo(since)<=0){
                // V0.2 generated timestamps with varying precision; chronological and lexicographic order must both be safe.
                Instant next=Instant.parse(since).plus(1,ChronoUnit.SECONDS).truncatedTo(ChronoUnit.SECONDS);
                if(!next.isAfter(Instant.parse(since))) next=next.plusSeconds(1);
                instant=ISO_MS.format(next);
            }
            if(oldId!=null){
                try(var ps=c.prepareStatement("UPDATE precio_presentacion SET vigente_hasta=? WHERE id=? AND vigente_hasta IS NULL")){
                    ps.setString(1,instant);ps.setLong(2,oldId);
                    if(ps.executeUpdate()!=1)throw new SQLException("El precio anterior cambió durante la operación");
                }
            }
            long created;
            try(var ps=c.prepareStatement("""
                    INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por,motivo)
                    VALUES(?,?,?,?,?,?)
                    """,java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1,presentationId);ps.setLong(2,listId);ps.setLong(3,price);
                ps.setString(4,instant);ps.setLong(5,actor.userId());ps.setString(6,why);
                ps.executeUpdate();try(var ids=ps.getGeneratedKeys()){
                    if(!ids.next())throw new SQLException("No se generó ID de precio");created=ids.getLong(1);
                }
            }
            try(var ps=c.prepareStatement("""
                    INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,antes_json,despues_json,motivo)
                    VALUES(?,'precio_presentacion',?,'ACTUALIZAR',json_object('precio_micro',?),
                    json_object('precio_micro',?,'lista_precio_id',?),?)
                    """)) {
                ps.setLong(1,actor.userId());ps.setLong(2,created);
                setNullable(ps,3,oldPrice);ps.setLong(4,price);ps.setLong(5,listId);ps.setString(6,why);ps.executeUpdate();
            }
            return created;
        });
    }

    /** Adjust by a signed positive/negative amount, preserving a legible reason in the immutable ledger. */
    public long adjustStock(long productId,BigDecimal signedQuantity,String reason,Session actor) throws SQLException {
        String why=required(reason,400,"Motivo");
        if(why.length()<8) throw new IllegalArgumentException("El motivo del ajuste debe tener al menos 8 caracteres");
        if(signedQuantity==null || signedQuantity.signum()==0)throw new IllegalArgumentException("La cantidad no puede ser cero");
        return db.inTransaction(c -> {
            admin(c,actor);
            ProductEdit item=load(c,productId);
            if(!item.status().equals("ACTIVO"))throw new IllegalArgumentException("Solo se puede ajustar un producto activo");
            long abs=DecimalUnits.toMilli(signedQuantity.abs(),item.unitDecimals());
            long delta=signedQuantity.signum()<0?Math.negateExact(abs):abs;
            if(delta<0 && item.stockMilli()<abs) throw new IllegalArgumentException("El ajuste no puede dejar stock negativo");
            long id;
            try(var ps=c.prepareStatement("""
                    INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid)
                    VALUES(?,?,?,?,?,?,?)
                    """,java.sql.Statement.RETURN_GENERATED_KEYS)){
                ps.setLong(1,productId);ps.setString(2,delta>0?"AJUSTE_POSITIVO":"AJUSTE_NEGATIVO");
                ps.setLong(3,delta);ps.setString(4,why);ps.setLong(5,actor.userId());ps.setLong(6,actor.userId());
                ps.setString(7,UUID.randomUUID().toString());ps.executeUpdate();
                try(var ids=ps.getGeneratedKeys()){if(!ids.next())throw new SQLException("Falta ID de movimiento");id=ids.getLong(1);}
            }
            try(var ps=c.prepareStatement("""
                    INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,antes_json,despues_json,motivo)
                    VALUES(?,'producto',?,'AJUSTAR',json_object('stock_milli',?),
                    json_object('stock_milli',?,'movimiento_id',?),?)
                    """)){
                ps.setLong(1,actor.userId());ps.setLong(2,productId);ps.setLong(3,item.stockMilli());
                ps.setLong(4,Math.addExact(item.stockMilli(),delta));ps.setLong(5,id);ps.setString(6,why);ps.executeUpdate();
            }
            return id;
        });
    }

    public List<PriceHistoryEntry> priceHistory(long productId) throws SQLException {
        List<PriceHistoryEntry> result=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("""
                SELECT ph.id, pp.nombre,lp.nombre,ph.precio_micro,ph.vigente_desde,ph.vigente_hasta,u.nombre,ph.motivo
                FROM precio_presentacion ph
                JOIN producto_presentacion pp ON pp.id=ph.presentacion_id
                JOIN lista_precio lp ON lp.id=ph.lista_precio_id
                JOIN usuario u ON u.id=ph.registrado_por
                WHERE pp.producto_id=? ORDER BY ph.id DESC LIMIT 200
                """)){
            ps.setLong(1,productId);try(var rs=ps.executeQuery()){while(rs.next()){
                result.add(new PriceHistoryEntry(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)));
            }}
        }
        return result;
    }

    public List<StockHistoryEntry> stockHistory(long productId) throws SQLException {
        List<StockHistoryEntry> result=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("""
                SELECT id,fecha,tipo,cantidad_delta_milli,acumulado,nombre,motivo FROM (
                    SELECT m.id,m.fecha,m.tipo,m.cantidad_delta_milli,
                    SUM(m.cantidad_delta_milli) OVER (ORDER BY m.id) AS acumulado,
                    u.nombre,m.motivo
                    FROM movimiento_stock m JOIN usuario u ON u.id=m.usuario_id WHERE m.producto_id=?
                ) ORDER BY id DESC LIMIT 200
                """)){
            ps.setLong(1,productId);try(var rs=ps.executeQuery()){while(rs.next()){
                result.add(new StockHistoryEntry(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),
                        rs.getLong(5),rs.getString(6),rs.getString(7)));
            }}
        }
        return result;
    }

    public List<CatalogItem> priceLists() throws SQLException {
        List<CatalogItem> result=new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("SELECT id,nombre FROM lista_precio WHERE activo=1 ORDER BY id");
            var rs=ps.executeQuery()){
            while(rs.next())result.add(new CatalogItem(rs.getLong(1),rs.getString(2),0));
        }
        return result;
    }

    private static ProductEdit load(Connection c,long id) throws SQLException {
        try(var ps=c.prepareStatement("""
                SELECT p.id,p.sku,p.nombre,p.categoria_id,p.marca_id,p.pais_origen,p.modelo_referencia,
                    p.observaciones,u.decimales_permitidos,u.codigo,p.stock_min_milli,p.stock_max_milli,
                    p.estado,s.cantidad_milli
                FROM producto p JOIN unidad_medida u ON p.unidad_base_id=u.id
                JOIN saldo_stock s ON s.producto_id=p.id WHERE p.id=?
                """)){
            ps.setLong(1,id);try(var rs=ps.executeQuery()){
                if(!rs.next())throw new IllegalArgumentException("Producto no encontrado");
                long b=rs.getLong(5);Long brand=rs.wasNull()?null:b;
                long v=rs.getLong(12);Long max=rs.wasNull()?null:v;
                return new ProductEdit(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4),brand,
                        rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9),rs.getString(10),
                        rs.getLong(11),max,rs.getString(13),rs.getLong(14));
            }
        }
    }

    private static void admin(Connection c,Session session) throws SQLException {
        if(session==null || !session.isAdmin())throw new SecurityException("Operación permitida solo al administrador");
        try(var ps=c.prepareStatement("""
                SELECT 1 FROM usuario u JOIN rol r ON r.id=u.rol_id
                WHERE u.id=? AND u.activo=1 AND r.activo=1 AND r.codigo='ADMIN'
                """)){
            ps.setLong(1,session.userId());try(var rs=ps.executeQuery()){
                if(!rs.next())throw new SecurityException("La cuenta administradora ya no está activa");
            }
        }
    }
    private static void requireActive(Connection c,String table,long id) throws SQLException {
        // Table name can come only from controlled literals in this class.
        if(!List.of("categoria","marca","lista_precio").contains(table))throw new IllegalArgumentException("Catálogo no permitido");
        try(var ps=c.prepareStatement("SELECT 1 FROM "+table+" WHERE id=? AND activo=1")){
            ps.setLong(1,id);try(var rs=ps.executeQuery()){
                if(!rs.next())throw new IllegalArgumentException("Categoría, marca o lista de precios no activa");
            }
        }
    }
    private static String now(){return ISO_MS.format(Instant.now());}
    private static String required(String s,int max,String title){
        if(s==null||s.trim().isEmpty())throw new IllegalArgumentException(title+" obligatorio");
        String clean=s.trim().replaceAll("\\s+"," ");
        if(clean.length()>max)throw new IllegalArgumentException(title+" demasiado largo (máximo "+max+")");
        return clean;
    }
    private static String optional(String s,int max){
        if(s==null||s.trim().isEmpty())return null;
        String clean=s.trim();if(clean.length()>max)throw new IllegalArgumentException("Texto demasiado largo");return clean;
    }
    private static void audit(Connection c,Session session,String entity,long id,String action,String reason)throws SQLException {
        try(var ps=c.prepareStatement("INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,motivo) VALUES(?,?,?,?,?)")){
            ps.setLong(1,session.userId());ps.setString(2,entity);ps.setLong(3,id);ps.setString(4,action);
            ps.setString(5,reason);ps.executeUpdate();
        }
    }
    private static void setNullable(java.sql.PreparedStatement ps,int index,Long val) throws SQLException {
        if(val==null)ps.setNull(index,java.sql.Types.INTEGER);else ps.setLong(index,val);
    }
}
