package com.tienda.inventario.application;

import com.tienda.inventario.domain.Session;
import com.tienda.inventario.infrastructure.database.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Catálogos controlados por administración. Solo los nombres se editan; nunca se borra historial. */
public final class CatalogService {
    private final Database db;
    public CatalogService(Database db) { this.db = db; }

    public long createCategory(String name, Session actor) throws SQLException { return create("categoria",name,actor); }
    public long createBrand(String name, Session actor) throws SQLException { return create("marca",name,actor); }
    public void renameCategory(long id, String name, Session actor) throws SQLException { rename("categoria",id,name,actor); }
    public void renameBrand(long id, String name, Session actor) throws SQLException { rename("marca",id,name,actor); }
    public void setCategoryActive(long id, boolean active, Session actor) throws SQLException { active("categoria",id,active,actor); }
    public void setBrandActive(long id, boolean active, Session actor) throws SQLException { active("marca",id,active,actor); }

    private long create(String table, String name, Session actor) throws SQLException {
        requireAdmin(actor);
        String clean = clean(name);
        return db.inTransaction(c -> {
            long id;
            try (var ps=c.prepareStatement("INSERT INTO "+table+"(nombre) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1,clean); ps.executeUpdate();
                try (var ids=ps.getGeneratedKeys()) {
                    if (!ids.next()) throw new SQLException("Sin ID de catálogo");
                    id=ids.getLong(1);
                }
            }
            audit(c,actor.userId(),table,id,"CREAR",clean);
            return id;
        });
    }
    private void rename(String table,long id,String name,Session actor) throws SQLException {
        requireAdmin(actor);
        String clean=clean(name);
        db.inTransaction(c -> {
            try(var ps=c.prepareStatement("UPDATE "+table+" SET nombre=? WHERE id=?")) {
                ps.setString(1,clean); ps.setLong(2,id);
                if(ps.executeUpdate()!=1) throw new IllegalArgumentException("Registro no encontrado");
            }
            audit(c,actor.userId(),table,id,"ACTUALIZAR",clean);
            return null;
        });
    }
    private void active(String table,long id,boolean isActive,Session actor) throws SQLException {
        requireAdmin(actor);
        db.inTransaction(c -> {
            if(!isActive) {
                String reference="categoria".equals(table)?"categoria_id":"marca_id";
                try(var ps=c.prepareStatement("SELECT count(*) FROM producto WHERE "+reference+"=? AND estado='ACTIVO'")) {
                    ps.setLong(1,id);
                    try(var rs=ps.executeQuery()) {
                        if(rs.next() && rs.getInt(1)>0)
                            throw new IllegalArgumentException("No se puede desactivar: tiene productos activos asociados");
                    }
                }
            }
            try(var ps=c.prepareStatement("UPDATE "+table+" SET activo=? WHERE id=?")) {
                ps.setInt(1,isActive?1:0); ps.setLong(2,id);
                if(ps.executeUpdate()!=1) throw new IllegalArgumentException("Registro no encontrado");
            }
            audit(c,actor.userId(),table,id,isActive?"ACTUALIZAR":"DESACTIVAR",null);
            return null;
        });
    }
    private static void requireAdmin(Session actor) {
        if(actor==null || !actor.isAdmin()) throw new SecurityException("Solo el administrador puede cambiar catálogos");
    }
    private static String clean(String name) {
        if(name==null || name.trim().isEmpty()) throw new IllegalArgumentException("El nombre es obligatorio");
        String value=name.trim().replaceAll("\\s+"," ");
        if(value.length()>80) throw new IllegalArgumentException("Máximo 80 caracteres");
        return value;
    }
    private static void audit(Connection c,long user,String table,long id,String action,String name) throws SQLException {
        try(var ps=c.prepareStatement("INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,despues_json,motivo) VALUES(?,?,?,?,json_object('nombre',?),'Gestión de catálogo')")) {
            ps.setLong(1,user); ps.setString(2,table); ps.setLong(3,id); ps.setString(4,action); ps.setString(5,name);
            ps.executeUpdate();
        }
    }
}
