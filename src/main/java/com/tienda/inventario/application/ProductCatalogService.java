package com.tienda.inventario.application;

import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Presentaciones, compatibilidades y metadatos. NO modifica ni reconstruye el stock. */
public final class ProductCatalogService {
    private final Database db;
    public ProductCatalogService(Database db) { this.db=db; }

    public ProductDetails details(long productId) throws SQLException {
        try(var c=db.open(); var ps=c.prepareStatement("""
                SELECT p.id,p.sku,p.nombre,p.marca_id,p.pais_origen,p.modelo_referencia,
                       p.observaciones,u.codigo,u.decimales_permitidos
                FROM producto p JOIN unidad_medida u ON u.id=p.unidad_base_id WHERE p.id=?
                """)) {
            ps.setLong(1,productId);
            try(var rs=ps.executeQuery()) {
                if(!rs.next()) throw new IllegalArgumentException("Producto no encontrado");
                long brand=rs.getLong(4);
                Long brandId=rs.wasNull()?null:brand;
                return new ProductDetails(rs.getLong(1),rs.getString(2),rs.getString(3),brandId,
                        rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9));
            }
        }
    }

    public void editMetadata(long id, Long brandId, String country, String model, String notes, Session actor) throws SQLException {
        requireAdmin(actor);
        db.inTransaction(c -> {
            requireProduct(c,id);
            if(brandId!=null) {
                try(var ps=c.prepareStatement("SELECT 1 FROM marca WHERE id=? AND activo=1")) {
                    ps.setLong(1,brandId);
                    try(var rs=ps.executeQuery()) { if(!rs.next()) throw new IllegalArgumentException("Marca inexistente o desactivada"); }
                }
            }
            try(var ps=c.prepareStatement("""
                    UPDATE producto SET marca_id=?,pais_origen=?,modelo_referencia=?,observaciones=?,
                    actualizado_en=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?
                    """)) {
                if(brandId==null) ps.setNull(1,java.sql.Types.INTEGER); else ps.setLong(1,brandId);
                ps.setString(2,optional(country,100)); ps.setString(3,optional(model,150));
                ps.setString(4,optional(notes,1000)); ps.setLong(5,id); ps.executeUpdate();
            }
            audit(c,actor.userId(),"producto",id,"ACTUALIZAR", "Marca, país, referencia u observaciones");
            return null;
        });
    }

    public List<ProductCompatibility> compatibilities(long productId) throws SQLException {
        var result=new ArrayList<ProductCompatibility>();
        try(var c=db.open(); var ps=c.prepareStatement("SELECT id,descripcion,activo FROM producto_compatibilidad WHERE producto_id=? ORDER BY activo DESC,descripcion")) {
            ps.setLong(1,productId);
            try(var rs=ps.executeQuery()) { while(rs.next()) result.add(new ProductCompatibility(rs.getLong(1),rs.getString(2),rs.getInt(3)==1)); }
        }
        return result;
    }
    public long addCompatibility(long productId,String description,Session actor) throws SQLException {
        requireAdmin(actor); String text=required(description,180);
        return db.inTransaction(c -> {
            requireProduct(c,productId);
            try(var ps=c.prepareStatement("SELECT 1 FROM producto_compatibilidad WHERE producto_id=? AND descripcion=? COLLATE NOCASE")) {
                ps.setLong(1,productId); ps.setString(2,text);
                try(var rs=ps.executeQuery()) { if(rs.next()) throw new IllegalArgumentException("Esta compatibilidad ya está registrada"); }
            }
            long id;
            try(var ps=c.prepareStatement("INSERT INTO producto_compatibilidad(producto_id,descripcion) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1,productId); ps.setString(2,text); ps.executeUpdate(); id=key(ps);
            }
            audit(c,actor.userId(),"producto_compatibilidad",id,"CREAR",text);
            return id;
        });
    }
    public void setCompatibilityActive(long productId,long id,boolean active,Session actor) throws SQLException {
        requireAdmin(actor);
        db.inTransaction(c -> {
            try(var ps=c.prepareStatement("UPDATE producto_compatibilidad SET activo=? WHERE id=? AND producto_id=? AND activo!=?")) {
                ps.setInt(1,active?1:0); ps.setLong(2,id); ps.setLong(3,productId);ps.setInt(4,active?1:0);
                if(ps.executeUpdate()!=1) throw new IllegalArgumentException("Compatibilidad no encontrada o ya está en ese estado");
            }
            audit(c,actor.userId(),"producto_compatibilidad",id,active?"ACTUALIZAR":"DESACTIVAR",null); return null;
        });
    }

    public List<ProductPresentation> presentations(long productId) throws SQLException {
        var result=new ArrayList<ProductPresentation>();
        try(var c=db.open(); var ps=c.prepareStatement("""
                SELECT pp.id,pp.nombre,pp.factor_base_milli,pp.codigo_barras,pp.se_compra,pp.se_vende,pp.activo,
                       (SELECT pr.precio_micro FROM vw_precio_actual pr
                         WHERE pr.presentacion_id=pp.id AND pr.lista='PUBLICO' LIMIT 1)
                  FROM producto_presentacion pp WHERE pp.producto_id=? ORDER BY pp.factor_base_milli, pp.nombre
                """)) {
            ps.setLong(1,productId);
            try(var rs=ps.executeQuery()) {
                while(rs.next()) {
                    long p=rs.getLong(8);
                    Long price=rs.wasNull()?null:p;
                    result.add(new ProductPresentation(rs.getLong(1),rs.getString(2),rs.getLong(3),rs.getString(4),
                            rs.getInt(5)==1,rs.getInt(6)==1,rs.getInt(7)==1,price));
                }
            }
        }
        return result;
    }

    public long addPresentation(long productId,String name,BigDecimal baseUnits,String barcode,
                                boolean buy,boolean sell,BigDecimal publicPrice,Session actor) throws SQLException {
        requireAdmin(actor); String title=required(name,100);
        if(!buy && !sell) throw new IllegalArgumentException("La presentación debe poder comprarse o venderse");
        if(sell && publicPrice==null) throw new IllegalArgumentException("Una presentación vendible requiere precio público");
        String code=optional(barcode,60);
        long price=publicPrice==null?0:DecimalUnits.toPriceMicro(publicPrice);
        return db.inTransaction(c -> {
            int decimals;
            try(var ps=c.prepareStatement("""
                    SELECT u.decimales_permitidos FROM producto p JOIN unidad_medida u ON u.id=p.unidad_base_id
                     WHERE p.id=? AND p.estado='ACTIVO'
                    """)) {
                ps.setLong(1,productId);
                try(var rs=ps.executeQuery()) {
                    if(!rs.next()) throw new IllegalArgumentException("Producto inexistente o inactivo");
                    decimals=rs.getInt(1);
                }
            }
            long factor=DecimalUnits.toMilli(baseUnits,decimals);
            if(factor<=0 || factor==1000) throw new IllegalArgumentException("El factor debe ser positivo y distinto de 1; la unidad base ya está registrada");
            try(var ps=c.prepareStatement("SELECT 1 FROM producto_presentacion WHERE producto_id=? AND factor_base_milli=?")) {
                ps.setLong(1,productId); ps.setLong(2,factor);
                try(var rs=ps.executeQuery()) { if(rs.next()) throw new IllegalArgumentException("Ya existe una presentación con ese factor para este producto"); }
            }
            long id;
            try(var ps=c.prepareStatement("""
                    INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli,codigo_barras,se_compra,se_vende)
                    VALUES(?,?,?,?,?,?)
                    """,Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1,productId); ps.setString(2,title); ps.setLong(3,factor);
                ps.setString(4,code); ps.setInt(5,buy?1:0); ps.setInt(6,sell?1:0);
                ps.executeUpdate(); id=key(ps);
            }
            if(publicPrice!=null) {
                try(var ps=c.prepareStatement("""
                        INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por,motivo)
                        SELECT ?,id,?,?,?,'Alta de presentación' FROM lista_precio WHERE codigo='PUBLICO' AND activo=1
                        """)) {
                    ps.setLong(1,id); ps.setLong(2,price); ps.setString(3,Instant.now().toString());
                    ps.setLong(4,actor.userId());
                    if(ps.executeUpdate()!=1) throw new IllegalStateException("Falta lista de precios PUBLICO");
                }
            }
            audit(c,actor.userId(),"producto_presentacion",id,"CREAR","Factor base: "+factor);
            return id;
        });
    }
    public void setPresentationActive(long productId,long id,boolean active,Session actor) throws SQLException {
        requireAdmin(actor);
        db.inTransaction(c -> {
            try(var ps=c.prepareStatement("""
                    UPDATE producto_presentacion SET activo=? WHERE producto_id=? AND id=?
                      AND activo!=? AND (?=1 OR factor_base_milli!=1000)
                    """)) {
                ps.setInt(1,active?1:0);ps.setLong(2,productId); ps.setLong(3,id);
                ps.setInt(4,active?1:0);ps.setInt(5,active?1:0);
                if(ps.executeUpdate()!=1) throw new IllegalArgumentException("No se puede cambiar el estado: la presentación no existe, ya está en ese estado o es la unidad base");
            }
            audit(c,actor.userId(),"producto_presentacion",id,active?"ACTUALIZAR":"DESACTIVAR",null); return null;
        });
    }

    private static void requireProduct(Connection c,long id) throws SQLException {
        try(var ps=c.prepareStatement("SELECT 1 FROM producto WHERE id=? AND estado='ACTIVO'")) {
            ps.setLong(1,id);
            try(var rs=ps.executeQuery()) { if(!rs.next()) throw new IllegalArgumentException("Producto inexistente o inactivo"); }
        }
    }
    private static void requireAdmin(Session actor) {
        if(actor==null || !actor.isAdmin()) throw new SecurityException("Solo un administrador puede modificar productos");
    }
    private static String optional(String v,int max) {
        if(v==null || v.trim().isEmpty()) return null;
        return required(v,max);
    }
    private static String required(String v,int max) {
        if(v==null || v.trim().isEmpty()) throw new IllegalArgumentException("Dato obligatorio vacío");
        String normalized=v.trim().replaceAll("\\s+"," ");
        if(normalized.length()>max) throw new IllegalArgumentException("El texto supera "+max+" caracteres");
        return normalized;
    }
    private static long key(java.sql.PreparedStatement ps) throws SQLException {
        try(var keys=ps.getGeneratedKeys()) { if(keys.next()) return keys.getLong(1); }
        throw new SQLException("No se recibió el identificador");
    }
    private static void audit(Connection c,long user,String entity,long id,String action,String detail) throws SQLException {
        try(var ps=c.prepareStatement("""
                INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,despues_json,motivo)
                VALUES(?,?,?,?,json_object('detalle',?),'Gestión de catálogo')
                """)) {
            ps.setLong(1,user); ps.setString(2,entity); ps.setLong(3,id); ps.setString(4,action);
            ps.setString(5,detail); ps.executeUpdate();
        }
    }
}
