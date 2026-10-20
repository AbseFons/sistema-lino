#!/usr/bin/env python3
"""Prueba SQLite sin librerías externas: nuevas operaciones de catálogo y presentaciones."""
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
MIG = ROOT / 'src/main/resources/db/migration'
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for name in ('V001__estructura_inicial.sql','V002__catalogos_basicos.sql','V003__vistas_consulta.sql'):
    db.executescript((MIG/name).read_text('utf-8'))
print('Migrations V001-V003: OK')

db.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Admin','admin',?)",('x'*48,))
db.execute("INSERT INTO categoria(nombre) VALUES('Repuestos')")
cat=db.execute("SELECT id FROM categoria WHERE nombre='Repuestos'").fetchone()[0]
db.execute("INSERT INTO marca(nombre) VALUES('NGK')")
brand=db.execute("SELECT id FROM marca WHERE nombre='NGK'").fetchone()[0]
db.execute("INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,despues_json,motivo) VALUES(1,'marca',?,'CREAR',json_object('nombre','NGK'),'test')",(brand,))
db.execute("INSERT INTO producto(sku,nombre,categoria_id,marca_id,pais_origen,unidad_base_id,stock_min_milli,estado,creado_por) VALUES('BUJ-1','Bujía',?,?,?,1,3000,'ACTIVO',1)",(cat,brand,'Japón'))
product=db.execute("SELECT id FROM producto WHERE sku='BUJ-1'").fetchone()[0]
db.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(?,'Unidad',1000)",(product,))
base=db.execute("SELECT id FROM producto_presentacion WHERE producto_id=?",(product,)).fetchone()[0]
db.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por,motivo) VALUES(?,1,120000,'2026-10-09T01:00:00.000Z',1,'Alta')",(base,))
db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid) VALUES(?,'INICIAL',18000,'Conteo inicial',1,1,'cat-v02-test')",(product,))
assert db.execute('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=?',(product,)).fetchone()[0]==18000
print('Initial stock and audit: OK')

db.execute("INSERT INTO producto_compatibilidad(producto_id,descripcion) VALUES(?,'Toyota Hilux 2020')",(product,))
assert db.execute("SELECT count(*) FROM producto_compatibilidad WHERE descripcion LIKE '%Hilux%'").fetchone()[0]==1
print('Compatibility: OK')

db.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli,codigo_barras,se_compra,se_vende) VALUES(?,'Caja x 12',12000,'BOX123',1,1)",(product,))
box=db.execute("SELECT id FROM producto_presentacion WHERE producto_id=? AND factor_base_milli=12000",(product,)).fetchone()[0]
db.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por,motivo) VALUES(?,1,1305000,'2026-10-09T01:00:00.000Z',1,'Alta caja')",(box,))
assert db.execute('SELECT precio_micro FROM vw_precio_actual WHERE presentacion_id=?',(box,)).fetchone()[0]==1305000
assert db.execute('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=?',(product,)).fetchone()[0]==18000
print('Package and public price without changing inventory: OK')

db.execute("UPDATE producto_presentacion SET activo=0 WHERE id=?",(box,))
db.execute("UPDATE producto_compatibilidad SET activo=0 WHERE producto_id=?",(product,))
db.execute("UPDATE producto SET modelo_referencia='BPR5EY' WHERE id=?",(product,))
assert db.execute('SELECT COUNT(*) FROM vw_stock_conciliacion WHERE diferencia_milli!=0').fetchone()[0]==0
assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
assert db.execute('PRAGMA foreign_key_check').fetchall()==[]
print('Soft disable, stock reconciliation, foreign keys and DB integrity: OK')
