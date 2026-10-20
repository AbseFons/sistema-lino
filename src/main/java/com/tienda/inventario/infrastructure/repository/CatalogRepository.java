package com.tienda.inventario.infrastructure.repository;

import com.tienda.inventario.domain.CatalogItem;
import com.tienda.inventario.domain.CatalogEntry;
import com.tienda.inventario.infrastructure.database.Database;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class CatalogRepository {
    private final Database db;
    public CatalogRepository(Database db) { this.db = db; }
    public List<CatalogItem> categories() throws SQLException {
        return read("SELECT id,nombre,0 FROM categoria WHERE activo=1 ORDER BY nombre");
    }
    public List<CatalogItem> brands() throws SQLException {
        return read("SELECT id,nombre,0 FROM marca WHERE activo=1 ORDER BY nombre");
    }
    public List<CatalogItem> units() throws SQLException {
        return read("SELECT id,nombre,decimales_permitidos FROM unidad_medida ORDER BY id");
    }
    public List<CatalogEntry> allCategories() throws SQLException {
        return readAll("SELECT id,nombre,activo FROM categoria ORDER BY nombre");
    }
    public List<CatalogEntry> allBrands() throws SQLException {
        return readAll("SELECT id,nombre,activo FROM marca ORDER BY nombre");
    }
    private List<CatalogEntry> readAll(String sql) throws SQLException {
        var result=new ArrayList<CatalogEntry>();
        try(var c=db.open();var st=c.createStatement();var rs=st.executeQuery(sql)) {
            while(rs.next()) result.add(new CatalogEntry(rs.getLong(1),rs.getString(2),rs.getInt(3)==1));
        }
        return result;
    }
    private List<CatalogItem> read(String sql) throws SQLException {
        var result = new ArrayList<CatalogItem>();
        try (var c = db.open(); var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            while (rs.next()) result.add(new CatalogItem(rs.getLong(1),rs.getString(2),rs.getInt(3)));
        }
        return result;
    }
}
