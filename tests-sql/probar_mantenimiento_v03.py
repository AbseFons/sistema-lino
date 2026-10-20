#!/usr/bin/env python3
"""Pruebas de contrato SQLite del incremento 0.3 (no son pruebas de UI ni JDBC)."""
from pathlib import Path
import sqlite3
import unittest

MIG=Path(__file__).resolve().parents[1]/'src/main/resources/db/migration'

class MaintenanceContractTests(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        for ver in ('V001__estructura_inicial.sql','V002__catalogos_basicos.sql','V003__vistas_consulta.sql'):
            self.db.executescript((MIG/ver).read_text(encoding='utf-8'))
        self.db.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Admin','admin',?)",('x'*48,))
        self.db.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(2,'Vendedor','vende',?)",('x'*48,))
        self.db.execute("INSERT INTO producto(sku,nombre,categoria_id,unidad_base_id,stock_min_milli,estado,creado_por) VALUES('TST-01','Filtro A',1,1,2000,'ACTIVO',1)")
        self.db.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(1,'Unidad',1000)")
        self.db.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por,motivo) VALUES(1,1,120000,'2026-10-09T04:00:00.000Z',1,'Alta')")
        self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'INICIAL',10000,'Conteo inicial',1,1,'ini-1')")
    def tearDown(self): self.db.close()
    def one(self,query,args=()): return self.db.execute(query,args).fetchone()[0]

    def test_ficha_actualizada_no_afecta_stock(self):
        self.db.execute("UPDATE producto SET nombre='Filtro premium',stock_min_milli=3000,stock_max_milli=15000,modelo_referencia='XR-30' WHERE id=1")
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),10000)
        self.assertEqual(self.one('SELECT stock_max_milli FROM producto WHERE id=1'),15000)
        self.assertEqual(self.one('SELECT diferencia_milli FROM vw_stock_conciliacion WHERE producto_id=1'),0)

    def test_precio_versionado_y_unico_vigente(self):
        self.db.execute("UPDATE precio_presentacion SET vigente_hasta='2026-10-09T05:00:00.000Z' WHERE id=1")
        self.db.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por,motivo) VALUES(1,1,145000,'2026-10-09T05:00:00.000Z',1,'Cambio de costo')")
        self.assertEqual(self.one('SELECT precio_micro FROM vw_precio_actual WHERE presentacion_id=1'),145000)
        self.assertEqual(self.one('SELECT COUNT(*) FROM precio_presentacion WHERE presentacion_id=1'),2)
        self.assertEqual(self.one('SELECT COUNT(*) FROM precio_presentacion WHERE vigente_hasta IS NULL AND presentacion_id=1'),1)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE precio_presentacion SET precio_micro=50000 WHERE id=1")

    def test_precio_no_se_solapa(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por) VALUES(1,1,125000,'2026-10-09T04:10:00.000Z',1)")

    def test_ajuste_positivo_y_negativo_conciliados(self):
        self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'AJUSTE_POSITIVO',3000,'Inventario físico',1,1,'adj-1')")
        self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'AJUSTE_NEGATIVO',-2000,'Producto dañado',1,1,'adj-2')")
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),11000)
        self.assertEqual(self.one('SELECT diferencia_milli FROM vw_stock_conciliacion WHERE producto_id=1'),0)
        self.assertEqual(self.one('SELECT COUNT(*) FROM movimiento_stock'),3)

    def test_no_stock_negativo(self):
        with self.assertRaisesRegex(sqlite3.IntegrityError,'STOCK_INSUFICIENTE'):
            self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'AJUSTE_NEGATIVO',-11000,'Conteo físico',1,1,'adj-too-low')")
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),10000)

    def test_solo_autorizacion_admin(self):
        with self.assertRaisesRegex(sqlite3.IntegrityError,'AJUSTE_REQUIERE_ADMIN_ACTIVO'):
            self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'AJUSTE_POSITIVO',1000,'Conteo físico',2,2,'adj-seller')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'AJUSTE_POSITIVO',1000,'',1,1,'adj-no-reason')")

    def test_kardex_y_auditoria_inmutables(self):
        self.db.execute("INSERT INTO auditoria(usuario_id,entidad,entidad_id,accion,antes_json,despues_json,motivo) VALUES(1,'producto',1,'AJUSTAR',json_object('stock_milli',10000),json_object('stock_milli',11000),'Prueba')")
        with self.assertRaises(sqlite3.IntegrityError):self.db.execute("DELETE FROM movimiento_stock WHERE id=1")
        with self.assertRaises(sqlite3.IntegrityError):self.db.execute("DELETE FROM auditoria WHERE id=1")

    def test_integridad_general(self):
        self.assertEqual(self.one('PRAGMA integrity_check'),'ok')
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(),[])

if __name__=='__main__':unittest.main(verbosity=2)
