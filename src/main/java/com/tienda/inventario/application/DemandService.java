package com.tienda.inventario.application;

import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Solicitudes por mercancía que no pudo venderse; guarda la evidencia original. */
public final class DemandService {
    private final Database db;
    public DemandService(Database db){this.db=db;}

    public long register(String description,String brandModel,BigDecimal quantity,
                         Long productId,Long customerId,String notes,Session actor) throws SQLException {
        String name=mandatory(description,180,"Producto solicitado");
        String brand=optional(brandModel,150,"Marca/modelo");
        String comment=optional(notes,600,"Observaciones");
        Long milli=null;
        if(quantity!=null){milli=DecimalUnits.toMilli(quantity,3);
            if(milli<=0)throw new IllegalArgumentException("La cantidad solicitada debe ser positiva");}
        final Long qty=milli;
        return db.inTransaction(c->{
            requireSeller(c,actor);
            if(productId!=null)requireExists(c,"producto",productId);
            if(customerId!=null)requireExists(c,"cliente",customerId);
            long id;
            try(PreparedStatement ps=c.prepareStatement("""
                INSERT INTO demanda_no_atendida(descripcion,marca_modelo,cantidad_milli,producto_id,cliente_id,usuario_id,observaciones)
                VALUES(?,?,?,?,?,?,?)
                """,Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1,name);ps.setString(2,brand);
                nullableId(ps,3,qty);nullableId(ps,4,productId);nullableId(ps,5,customerId);
                ps.setLong(6,actor.userId());ps.setString(7,comment);ps.executeUpdate();
                try(ResultSet keys=ps.getGeneratedKeys()) {if(!keys.next())throw new SQLException("Sin identificador de solicitud");id=keys.getLong(1);}
            }
            try(PreparedStatement ps=c.prepareStatement("""
                INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,despues_json,motivo)
                VALUES(?,'demanda_no_atendida',?,'CREAR',json_object('estado','PENDIENTE','descripcion',?),?)
                """)){
                ps.setLong(1,actor.userId());ps.setLong(2,id);ps.setString(3,name);ps.setString(4,"Solicitud no atendida");ps.executeUpdate();
            }
            return id;
        });
    }

    public void resolve(long id,String status,String reason,Session actor) throws SQLException {
        if(!List.of("ATENDIDA","DESCARTADA").contains(status))throw new IllegalArgumentException("Estado de resolución inválido");
        String why=mandatory(reason,500,"Motivo de resolución");
        returnVoidResolve(id,status,why,actor);
    }

    private void returnVoidResolve(long id,String status,String why,Session actor) throws SQLException {
        db.inTransaction(c->{
            requireSeller(c,actor);
            if("DESCARTADA".equals(status) && !isCurrentAdmin(c,actor))throw new SecurityException("Solo el administrador activo puede descartar solicitudes");
            String before;
            try(PreparedStatement ps=c.prepareStatement("SELECT estado,observaciones FROM demanda_no_atendida WHERE id=?")) {
                ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){
                    if(!rs.next())throw new IllegalArgumentException("Solicitud no encontrada");
                    if(!"PENDIENTE".equals(rs.getString(1)))throw new IllegalStateException("Esta solicitud ya fue resuelta");
                    before=rs.getString(2);
                }
            }
            String notes=(before==null||before.isBlank()?"":before+"\n")+"Resolución: "+why;
            if(notes.length()>2000)throw new IllegalArgumentException("El historial de observaciones es demasiado largo");
            try(PreparedStatement ps=c.prepareStatement("UPDATE demanda_no_atendida SET estado=?,observaciones=? WHERE id=? AND estado='PENDIENTE'")) {
                ps.setString(1,status);ps.setString(2,notes);ps.setLong(3,id);
                if(ps.executeUpdate()!=1)throw new SQLException("No se pudo resolver la solicitud");
            }
            try(PreparedStatement ps=c.prepareStatement("""
                INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,antes_json,despues_json,motivo)
                VALUES(?,'demanda_no_atendida',?,'ACTUALIZAR',json_object('estado','PENDIENTE'),json_object('estado',?),?)
                """)){
                ps.setLong(1,actor.userId());ps.setLong(2,id);ps.setString(3,status);ps.setString(4,why);ps.executeUpdate();
            }
            return null;
        });
    }

    public List<UnmetDemand> findAll(boolean pendingOnly,String search,int limit) throws SQLException {
        List<UnmetDemand> out=new ArrayList<>();
        String filter=search==null?"":search.strip().toLowerCase(Locale.ROOT);
        if(filter.length()>150)throw new IllegalArgumentException("Búsqueda demasiado larga");
        try(Connection c=db.open();PreparedStatement ps=c.prepareStatement("""
            SELECT d.id,d.fecha,d.descripcion,d.marca_modelo,d.cantidad_milli,p.sku,
                   cl.nombre_razon_social,u.nombre,d.estado,d.observaciones
            FROM demanda_no_atendida d LEFT JOIN producto p ON p.id=d.producto_id
                 LEFT JOIN cliente cl ON cl.id=d.cliente_id
                 JOIN usuario u ON u.id=d.usuario_id
            WHERE (?=0 OR d.estado='PENDIENTE')
              AND (?='' OR lower(d.descripcion) LIKE '%'||?||'%' OR lower(COALESCE(d.marca_modelo,'')) LIKE '%'||?||'%')
            ORDER BY CASE WHEN d.estado='PENDIENTE' THEN 0 ELSE 1 END,d.fecha DESC,d.id DESC LIMIT ?
            """)) {
            ps.setInt(1,pendingOnly?1:0);ps.setString(2,filter);ps.setString(3,filter);ps.setString(4,filter);ps.setInt(5,Math.max(1,Math.min(1000,limit)));
            try(ResultSet r=ps.executeQuery()){while(r.next()){
                long q=r.getLong(5);Long qty=r.wasNull()?null:q;
                out.add(new UnmetDemand(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),qty,
                        r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10)));
            }}
        }
        return out;
    }

    public long countPending() throws SQLException {
        try(Connection c=db.open();PreparedStatement ps=c.prepareStatement("SELECT COUNT(*) FROM demanda_no_atendida WHERE estado='PENDIENTE'");ResultSet r=ps.executeQuery()){
            r.next();return r.getLong(1);
        }
    }

    public List<DemandRanking> mostRequested(int limit) throws SQLException {
        List<DemandRanking> out=new ArrayList<>();
        try(Connection c=db.open();PreparedStatement ps=c.prepareStatement("""
            SELECT MIN(descripcion),COALESCE(MIN(marca_modelo),''),COUNT(*),COALESCE(SUM(cantidad_milli),0)
            FROM demanda_no_atendida WHERE estado='PENDIENTE'
            GROUP BY lower(trim(descripcion)),lower(trim(COALESCE(marca_modelo,'')))
            ORDER BY COUNT(*) DESC,MIN(descripcion) LIMIT ?
            """)) {
            ps.setInt(1,Math.max(1,Math.min(100,limit)));
            try(ResultSet r=ps.executeQuery()){while(r.next())out.add(new DemandRanking(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4)));}
        }
        return out;
    }

    private static void nullableId(PreparedStatement p,int position,Long value)throws SQLException {
        if(value==null)p.setNull(position,Types.INTEGER);else p.setLong(position,value);
    }
    private static String mandatory(String text,int max,String label){
        if(text==null||text.isBlank())throw new IllegalArgumentException(label+" es obligatorio");
        return optional(text,max,label);
    }
    private static String optional(String text,int max,String label){
        if(text==null||text.isBlank())return null;
        String clean=text.strip();if(clean.length()>max)throw new IllegalArgumentException(label+" supera "+max+" caracteres");
        return clean;
    }
    private static void requireSeller(Connection c,Session actor) throws SQLException {
        if(actor==null)throw new SecurityException("Inicia sesión");
        try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM usuario u JOIN rol r ON r.id=u.rol_id WHERE u.id=? AND u.activo=1 AND r.activo=1 AND r.codigo IN ('ADMIN','VENDEDOR')")){
            ps.setLong(1,actor.userId());try(ResultSet r=ps.executeQuery()){
                if(!r.next())throw new SecurityException("La cuenta ya no está activa");
            }
        }
    }
    private static boolean isCurrentAdmin(Connection c,Session actor)throws SQLException{
        if(!actor.isAdmin())return false;
        try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM usuario u JOIN rol r ON r.id=u.rol_id WHERE u.id=? AND u.activo=1 AND r.codigo='ADMIN' AND r.activo=1")){
            ps.setLong(1,actor.userId());try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }
    }
    private static void requireExists(Connection c,String table,long id)throws SQLException{
        if(!Set.of("producto","cliente").contains(table))throw new IllegalArgumentException("Tabla no permitida");
        try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM "+table+" WHERE id=?")){
            ps.setLong(1,id);try(ResultSet rs=ps.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Referencia no encontrada: "+table);}
        }
    }
}
