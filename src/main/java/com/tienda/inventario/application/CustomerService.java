package com.tienda.inventario.application;

import com.tienda.inventario.domain.*;
import com.tienda.inventario.infrastructure.database.Database;
import java.sql.*;
import java.util.*;

/** Clientes frecuentes; los compradores anónimos no generan filas de cliente. */
public final class CustomerService {
    private final Database db;
    public CustomerService(Database db){this.db=db;}

    public List<PriceList> priceLists() throws SQLException {
        List<PriceList> out=new ArrayList<>();
        try(var c=db.open();var p=c.prepareStatement("SELECT id,codigo,nombre FROM lista_precio WHERE activo=1 ORDER BY id");var rs=p.executeQuery()){
            while(rs.next())out.add(new PriceList(rs.getLong(1),rs.getString(2),rs.getString(3)));
        }
        return out;
    }
    public List<Customer> all() throws SQLException {
        List<Customer> out=new ArrayList<>();
        try(var c=db.open();var p=c.prepareStatement("SELECT id,tipo_persona,tipo_documento,numero_documento,nombre_razon_social,direccion,telefono,lista_precio_id,activo FROM cliente ORDER BY activo DESC,nombre_razon_social COLLATE NOCASE");var rs=p.executeQuery()){
            while(rs.next()){long l=rs.getLong(8);Long list=rs.wasNull()?null:l;
                out.add(new Customer(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),list,rs.getInt(9)==1));}
        }
        return out;
    }
    public long create(String kind,String docType,String document,String name,String address,String phone,Long priceList,Session actor) throws SQLException {
        Data d=validated(kind,docType,document,name,address,phone);
        return db.inTransaction(c->{
            requireSeller(c,actor);
            if(priceList!=null){validateList(c,priceList);if(!actor.isAdmin()&&!isPublic(c,priceList))
                throw new SecurityException("Solo el administrador puede asignar listas especiales de precios");}
            try(var ps=c.prepareStatement("INSERT INTO cliente(tipo_persona,tipo_documento,numero_documento,nombre_razon_social,direccion,telefono,lista_precio_id) VALUES(?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                ps.setString(1,d.kind);ps.setString(2,d.docType);ps.setString(3,d.document);ps.setString(4,d.name);
                ps.setString(5,d.address);ps.setString(6,d.phone);setList(ps,7,priceList);ps.executeUpdate();
                long id=key(ps);audit(c,actor.userId(),id,"CREAR","Alta de cliente");return id;
            }
        });
    }
    public void update(long id,String kind,String docType,String document,String name,String address,String phone,Long priceList,boolean active,Session actor) throws SQLException {
        Data d=validated(kind,docType,document,name,address,phone);
        db.inTransaction(c->{
            SupplierService.requireAdmin(c,actor);
            if(priceList!=null)validateList(c,priceList);
            try(var ps=c.prepareStatement("UPDATE cliente SET tipo_persona=?,tipo_documento=?,numero_documento=?,nombre_razon_social=?,direccion=?,telefono=?,lista_precio_id=?,activo=? WHERE id=?")){
                ps.setString(1,d.kind);ps.setString(2,d.docType);ps.setString(3,d.document);ps.setString(4,d.name);
                ps.setString(5,d.address);ps.setString(6,d.phone);setList(ps,7,priceList);ps.setInt(8,active?1:0);ps.setLong(9,id);
                if(ps.executeUpdate()!=1)throw new IllegalArgumentException("Cliente no encontrado");
            }
            audit(c,actor.userId(),id,active?"ACTUALIZAR":"DESACTIVAR","Edición de cliente");return null;
        });
    }
    static void requireSeller(Connection c,Session actor) throws SQLException {
        if(actor==null)throw new SecurityException("Debes ingresar a tu cuenta");
        try(var p=c.prepareStatement("SELECT r.codigo FROM usuario u JOIN rol r ON r.id=u.rol_id WHERE u.id=? AND u.activo=1 AND r.activo=1")){
            p.setLong(1,actor.userId());try(var rs=p.executeQuery()){
                if(!rs.next()||!rs.getString(1).equals(actor.role()))throw new SecurityException("Usuario no autorizado o inactivo");
            }
        }
    }
    static long publicId(Connection c) throws SQLException {
        try(var p=c.prepareStatement("SELECT id FROM lista_precio WHERE codigo='PUBLICO' AND activo=1");var rs=p.executeQuery()){
            if(rs.next())return rs.getLong(1);throw new IllegalStateException("Falta la lista PUBLICO");
        }
    }
    private static boolean isPublic(Connection c,long id) throws SQLException{return publicId(c)==id;}
    private static void validateList(Connection c,long id) throws SQLException {
        try(var p=c.prepareStatement("SELECT 1 FROM lista_precio WHERE id=? AND activo=1")){
            p.setLong(1,id);try(var rs=p.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Lista de precio inactiva");}
        }
    }
    private static Data validated(String kind,String docType,String document,String name,String address,String phone){
        if(!"NATURAL".equals(kind)&&!"JURIDICA".equals(kind))throw new IllegalArgumentException("Tipo de persona inválido");
        String title=SupplierService.required(name,180);
        String type=SupplierService.optional(docType,5),number=SupplierService.optional(document,25);
        if((type==null)!=(number==null))throw new IllegalArgumentException("Indica tipo y número de documento juntos");
        if(type!=null){
            if(!List.of("DNI","RUC","OTRO").contains(type))throw new IllegalArgumentException("Documento inválido");
            if("DNI".equals(type)&&!number.matches("[0-9]{8}"))throw new IllegalArgumentException("DNI debe tener 8 dígitos");
            if("RUC".equals(type)&&!number.matches("[0-9]{11}"))throw new IllegalArgumentException("RUC debe tener 11 dígitos");
        }
        if("JURIDICA".equals(kind)&&!"RUC".equals(type))throw new IllegalArgumentException("Empresa requiere RUC");
        return new Data(kind,type,number,title,SupplierService.optional(address,250),SupplierService.optional(phone,40));
    }
    private static void setList(PreparedStatement ps,int index,Long id) throws SQLException {
        if(id==null)ps.setNull(index,Types.INTEGER);else ps.setLong(index,id);
    }
    private static long key(PreparedStatement p) throws SQLException {try(var k=p.getGeneratedKeys()){if(k.next())return k.getLong(1);}throw new SQLException("No se generó ID");}
    private static void audit(Connection c,long user,long id,String action,String text) throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,motivo) VALUES(?,'cliente',?,?,?)")){
            p.setLong(1,user);p.setLong(2,id);p.setString(3,action);p.setString(4,text);p.executeUpdate();
        }
    }
    private record Data(String kind,String docType,String document,String name,String address,String phone){}
}
