package com.tienda.inventario.infrastructure.repository;

import com.tienda.inventario.domain.Product;
import com.tienda.inventario.infrastructure.database.Database;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ProductRepository {
    private final Database db;
    public ProductRepository(Database db) { this.db = db; }

    public List<Product> search(String query) throws SQLException {
        String q = "%" + (query == null ? "" : query.trim()) + "%";
        String sql = """
                SELECT p.id,p.sku,p.nombre,c.nombre,coalesce(m.nombre,'Sin marca'),u.codigo,p.estado,
                       s.cantidad_milli,p.stock_min_milli,s.nivel_alerta,pr.precio_micro
                FROM producto p
                JOIN categoria c ON c.id=p.categoria_id
                LEFT JOIN marca m ON m.id=p.marca_id
                JOIN unidad_medida u ON u.id=p.unidad_base_id
                JOIN vw_stock_producto s ON s.producto_id=p.id
                LEFT JOIN producto_presentacion pp ON pp.producto_id=p.id AND pp.factor_base_milli=1000 AND pp.activo=1
                LEFT JOIN vw_precio_actual pr ON pr.presentacion_id=pp.id AND pr.lista='PUBLICO'
                WHERE (p.sku LIKE ? OR p.nombre LIKE ? OR coalesce(m.nombre,'') LIKE ?
                    OR coalesce(p.modelo_referencia,'') LIKE ? OR EXISTS (
                      SELECT 1 FROM producto_compatibilidad pc WHERE pc.producto_id=p.id AND pc.activo=1
                      AND pc.descripcion LIKE ?))
                ORDER BY p.nombre,p.sku
                LIMIT 400
                """;
        var result = new ArrayList<Product>();
        try (var c = db.open(); var ps = c.prepareStatement(sql)) {
            for(int i=1;i<=5;i++) ps.setString(i,q);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long price = rs.getLong(11);
                    boolean priceMissing = rs.wasNull();
                    result.add(new Product(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),
                            rs.getString(5),rs.getString(6),rs.getString(7),rs.getLong(8),rs.getLong(9),
                            rs.getString(10),priceMissing?null:price));
                }
            }
        }
        return result;
    }

    public int total() throws SQLException { return count("SELECT count(*) FROM producto WHERE estado!='INACTIVO'"); }
    public int lowStock() throws SQLException {
        return count("SELECT count(*) FROM vw_stock_producto WHERE nivel_alerta IN ('BAJO','AGOTADO') AND estado='ACTIVO'");
    }
    private int count(String sql) throws SQLException {
        try (var c = db.open(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next()? rs.getInt(1):0;
        }
    }
}
