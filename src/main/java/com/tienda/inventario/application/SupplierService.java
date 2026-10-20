package com.tienda.inventario.application;

import com.tienda.inventario.domain.Session;
import com.tienda.inventario.domain.Supplier;
import com.tienda.inventario.infrastructure.database.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Catálogo de proveedores. No se borran proveedores usados en compras. */
public final class SupplierService {
    private final Database db;
    public SupplierService(Database db) { this.db=db; }

    public List<Supplier> findAll() throws SQLException {
        List<Supplier> result = new ArrayList<>();
        try(var c=db.open();var ps=c.prepareStatement("SELECT id,ruc,razon_social,telefono,activo FROM proveedor ORDER BY activo DESC, razon_social COLLATE NOCASE");var rs=ps.executeQuery()) {
            while(rs.next()) result.add(new Supplier(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5)==1));
        }
        return result;
    }

    public long create(String name, String ruc, String phone, Session actor) throws SQLException {
        String title=required(name,180); String document=ruc(ruc);String contact=optional(phone,50);
        return db.inTransaction(c -> {
            requireAdmin(c,actor);
            long id;
            try(var ps=c.prepareStatement("INSERT INTO proveedor(razon_social,ruc,telefono) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1,title);ps.setString(2,document);ps.setString(3,contact);ps.executeUpdate();id=key(ps);
            }
            audit(c,actor.userId(),id,"CREAR",title);
            return id;
        });
    }

    public void update(long id,String name,String ruc,String phone,Session actor) throws SQLException {
        String title=required(name,180);String document=ruc(ruc);String contact=optional(phone,50);
        db.inTransaction(c -> {
            requireAdmin(c,actor);
            try(var ps=c.prepareStatement("UPDATE proveedor SET razon_social=?,ruc=?,telefono=? WHERE id=?")) {
                ps.setString(1,title);ps.setString(2,document);ps.setString(3,contact);ps.setLong(4,id);
                if(ps.executeUpdate()!=1)throw new IllegalArgumentException("Proveedor no encontrado");
            }
            audit(c,actor.userId(),id,"ACTUALIZAR",title);
            return null;
        });
    }

    public void setActive(long id,boolean active,Session actor) throws SQLException {
        db.inTransaction(c -> {
            requireAdmin(c,actor);
            try(var ps=c.prepareStatement("UPDATE proveedor SET activo=? WHERE id=? AND activo!=?")) {
                ps.setInt(1,active?1:0);ps.setLong(2,id);ps.setInt(3,active?1:0);
                if(ps.executeUpdate()!=1)throw new IllegalArgumentException("Proveedor inexistente o ya tiene ese estado");
            }
            audit(c,actor.userId(),id,active?"ACTUALIZAR":"DESACTIVAR",active?"Activar proveedor":"Desactivar proveedor");
            return null;
        });
    }

    static void requireAdmin(Connection c,Session actor) throws SQLException {
        if(actor==null || !actor.isAdmin())throw new SecurityException("Operación reservada al administrador");
        try(var ps=c.prepareStatement("SELECT 1 FROM usuario u JOIN rol r ON u.rol_id=r.id WHERE u.id=? AND u.activo=1 AND r.activo=1 AND r.codigo='ADMIN'")) {
            ps.setLong(1,actor.userId());try(var rs=ps.executeQuery()) {if(!rs.next())throw new SecurityException("Administrador no autorizado o inactivo");}
        }
    }
    static String required(String data,int max) {
        if(data==null || data.trim().isEmpty())throw new IllegalArgumentException("Completa el nombre o razón social");
        String result=data.trim().replaceAll("\\s+"," ");
        if(result.length()>max)throw new IllegalArgumentException("Texto demasiado largo (máx. "+max+")");
        return result;
    }
    static String optional(String data,int max) {
        if(data==null || data.trim().isEmpty())return null;
        String result=data.trim();if(result.length()>max)throw new IllegalArgumentException("Texto demasiado largo");
        return result;
    }
    static String ruc(String data) {
        String s=optional(data,11);
        if(s!=null&&!s.matches("[0-9]{11}"))throw new IllegalArgumentException("El RUC debe tener 11 dígitos");
        return s;
    }
    private static long key(java.sql.PreparedStatement ps) throws SQLException {
        try(var keys=ps.getGeneratedKeys()){if(keys.next())return keys.getLong(1);}
        throw new SQLException("No se recibió el identificador");
    }
    private static void audit(Connection c,long actor,long id,String action,String value) throws SQLException {
        try(var ps=c.prepareStatement("INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,despues_json,motivo) VALUES(?,'proveedor',?,?,json_object('detalle',?),'Gestión de proveedores')")) {
            ps.setLong(1,actor);ps.setLong(2,id);ps.setString(3,action);ps.setString(4,value);ps.executeUpdate();
        }
    }
}
