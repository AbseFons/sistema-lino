package com.tienda.inventario.application;

import com.tienda.inventario.domain.NewProduct;
import com.tienda.inventario.domain.Session;
import com.tienda.inventario.infrastructure.database.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** One atomic operation: product + base presentation + optional price and initial inventory. */
public final class ProductService {
    private final Database db;
    public ProductService(Database db) { this.db = db; }

    public long register(NewProduct data, Session actor) throws SQLException {
        if (data == null || actor == null) throw new IllegalArgumentException("Faltan datos");
        if (!actor.isAdmin()) throw new IllegalArgumentException("Solo el administrador puede registrar productos y precios en esta versión");
        if (data.sku() == null || !data.sku().trim().matches("[A-Za-z0-9._-]{2,40}"))
            throw new IllegalArgumentException("SKU inválido (2-40 letras, números o ._-)");
        if (data.name() == null || data.name().trim().isBlank())
            throw new IllegalArgumentException("El nombre es obligatorio");
        if (data.name().trim().length() > 150)
            throw new IllegalArgumentException("El nombre es demasiado largo");
        long priceMicro = DecimalUnits.toPriceMicro(data.publicPrice());
        return db.inTransaction(c -> {
            int decimals = unitDecimals(c,data.unitId());
            long startMilli = DecimalUnits.toMilli(data.initialStock(),decimals);
            long minMilli = DecimalUnits.toMilli(data.minimumStock(),decimals);
            if (startMilli > 0 && !actor.isAdmin())
                throw new IllegalArgumentException("El stock inicial solo puede autorizarlo un administrador");
            if (categoryActive(c, data.categoryId()) == false)
                throw new IllegalArgumentException("La categoría no está activa");
            if (data.brandId()!=null && !brandActive(c,data.brandId()))
                throw new IllegalArgumentException("La marca no está activa");
            long productId;
            try (var ps = c.prepareStatement("""
                    INSERT INTO producto(sku,nombre,categoria_id,unidad_base_id,stock_min_milli,
                                         marca_id,pais_origen,modelo_referencia,observaciones,estado,creado_por)
                    VALUES(?,?,?,?,?,?,?,?,?,'ACTIVO',?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1,data.sku().trim().toUpperCase(Locale.ROOT));
                ps.setString(2,data.name().trim());
                ps.setLong(3,data.categoryId());
                ps.setLong(4,data.unitId());
                ps.setLong(5,minMilli);
                if(data.brandId()==null) ps.setNull(6,java.sql.Types.INTEGER);
                else ps.setLong(6,data.brandId());
                ps.setString(7,optional(data.country(),100));
                ps.setString(8,optional(data.model(),150));
                ps.setString(9,optional(data.observations(),1000));
                ps.setLong(10,actor.userId());
                ps.executeUpdate();
                productId = generated(ps);
            }
            long presentationId;
            try (var ps = c.prepareStatement("""
                    INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli)
                    VALUES(?,(SELECT nombre FROM unidad_medida WHERE id=?),1000)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1,productId); ps.setLong(2,data.unitId());
                ps.executeUpdate(); presentationId = generated(ps);
            }
            // Price scaled in 1/10,000 PEN as required by the existing schema.
            try (var ps = c.prepareStatement("""
                    INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por,motivo)
                    SELECT ?,id,?,?,?,'Alta de producto' FROM lista_precio WHERE codigo='PUBLICO'
                    """)) {
                ps.setLong(1,presentationId); ps.setLong(2,priceMicro);
                ps.setString(3,Instant.now().toString()); ps.setLong(4,actor.userId());
                if (ps.executeUpdate()!=1) throw new SQLException("Lista PUBLICO no encontrada");
            }
            if (startMilli > 0) {
                try (var ps = c.prepareStatement("""
                        INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid)
                        VALUES(?,'INICIAL',?,'Inventario progresivo inicial',?,?,?)
                        """)) {
                    ps.setLong(1,productId); ps.setLong(2,startMilli); ps.setLong(3,actor.userId());
                    ps.setLong(4,actor.userId()); ps.setString(5,UUID.randomUUID().toString());
                    ps.executeUpdate();
                }
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,despues_json,motivo)
                    VALUES(?,'producto',?,'CREAR',json_object('sku',?,'nombre',?),'Alta inicial')
                    """)) {
                ps.setLong(1,actor.userId()); ps.setLong(2,productId);
                ps.setString(3,data.sku().trim().toUpperCase(Locale.ROOT)); ps.setString(4,data.name().trim());
                ps.executeUpdate();
            }
            return productId;
        });
    }

    private static long generated(java.sql.PreparedStatement ps) throws SQLException {
        try (var ids = ps.getGeneratedKeys()) {
            if (ids.next()) return ids.getLong(1);
            throw new SQLException("No se recibió un ID");
        }
    }
    private static int unitDecimals(Connection c, long unitId) throws SQLException {
        try (var ps = c.prepareStatement("SELECT decimales_permitidos FROM unidad_medida WHERE id=?")) {
            ps.setLong(1,unitId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                throw new IllegalArgumentException("Unidad inexistente");
            }
        }
    }
    private static boolean categoryActive(Connection c, long categoryId) throws SQLException {
        try (var ps = c.prepareStatement("SELECT 1 FROM categoria WHERE id=? AND activo=1")) {
            ps.setLong(1,categoryId);
            try (var rs = ps.executeQuery()) { return rs.next(); }
        }
    }
    private static boolean brandActive(Connection c,long brandId) throws SQLException {
        try (var ps=c.prepareStatement("SELECT 1 FROM marca WHERE id=? AND activo=1")) {
            ps.setLong(1,brandId);
            try(var rs=ps.executeQuery()) { return rs.next(); }
        }
    }
    private static String optional(String text,int maxLength) {
        if(text==null || text.trim().isEmpty()) return null;
        String value=text.trim();
        if(value.length()>maxLength) throw new IllegalArgumentException("Campo demasiado largo (máx. "+maxLength+")");
        return value;
    }
}
