-- V002: Catalogos iniciales. NO crea usuarios con claves predeterminadas.
INSERT INTO rol(codigo,nombre) VALUES('ADMIN','Administrador'),('VENDEDOR','Vendedor');
INSERT INTO categoria(nombre) VALUES('Por clasificar');
INSERT INTO unidad_medida(codigo,nombre,decimales_permitidos) VALUES
 ('UND','Unidad',0),('PZA','Pieza',0),('JGO','Juego',0),('L','Litro',3),('M','Metro',3);
INSERT INTO lista_precio(codigo,nombre) VALUES
 ('PUBLICO','Público'),('MECANICO','Mecánico'),('MAYORISTA','Mayorista');
