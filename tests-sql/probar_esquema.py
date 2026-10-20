"""Pruebas de integridad para SQLite. Ejecutar: python tests/probar_esquema.py"""
import sqlite3
import unittest
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1] / 'src' / 'main' / 'resources' / 'db' / 'migration'
SCRIPTS = sorted(ROOT.glob('V*.sql'))


def ahora():
    return '2026-10-08T17:00:00.000Z'


class EsquemaFisicoTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        for script in SCRIPTS:
            self.db.executescript(script.read_text(encoding='utf-8'))
        self.db.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Admin test','admin','$argon2id$hash_simulado_para_pruebas')")
        self.db.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(2,'Vendedor test','vende','$argon2id$hash_simulado_para_pruebas')")
        self.db.execute("INSERT INTO producto(sku,nombre,categoria_id,unidad_base_id,creado_por,estado,stock_min_milli) VALUES('SKU-01','Filtro de prueba',1,2,1,'ACTIVO',3000)")
        self.db.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(1,'Unidad',1000)")
        self.db.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(1,'Caja x12',12000)")
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def one(self, sql, args=()):
        return self.db.execute(sql,args).fetchone()[0]

    def compra(self, unidades=2):
        self.db.execute("INSERT INTO compra(fecha_compra,usuario_id,total_centavos) VALUES(?,?,?)",(ahora(),1,12000*unidades))
        cid = self.one('SELECT max(id) FROM compra')
        self.db.execute("INSERT INTO compra_detalle(compra_id,presentacion_id,cantidad_presentacion_milli,factor_base_milli_snapshot,cantidad_base_milli,costo_unitario_micro,importe_centavos) VALUES(?,?,?,?,?,?,?)",
                        (cid,2,1000*unidades,12000,12000*unidades,1200000,12000*unidades))
        self.db.execute("UPDATE compra SET estado='CONFIRMADA',confirmada_en=? WHERE id=?",(ahora(),cid))
        return cid

    def preparar_venta(self, cantidad_unidades=3, pagos=(1200,1800), tipo='BOLETA', vendedor=2, sku='SKU-01'):
        total = cantidad_unidades*1000
        self.db.execute("INSERT INTO venta(operacion_uuid,vendedor_id,subtotal_centavos,total_centavos) VALUES(?,?,?,?)",(str(uuid4()),vendedor,total,total))
        vid=self.one('SELECT max(id) FROM venta')
        self.db.execute("INSERT INTO venta_detalle(venta_id,presentacion_id,sku_snapshot,descripcion_snapshot,cantidad_presentacion_milli,factor_base_milli_snapshot,cantidad_base_milli,precio_lista_micro,precio_final_micro,descuento_centavos,impuesto_centavos,total_centavos) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        (vid,1,sku,'Filtro de prueba',cantidad_unidades*1000,1000,cantidad_unidades*1000,100000,100000,0,0,total))
        for i,monto in enumerate(pagos):
            self.db.execute('INSERT INTO venta_pago(venta_id,medio,monto_centavos) VALUES(?,?,?)', (vid,['EFECTIVO','YAPE','PLIN'][i],monto))
        self.db.execute('INSERT INTO comprobante(venta_id,tipo,idempotency_key) VALUES(?,?,?)',(vid,tipo,str(uuid4())))
        return vid

    def confirmar(self, vid):
        self.db.execute('UPDATE venta SET estado=?,confirmada_en=? WHERE id=?',('CONFIRMADA',ahora(),vid))

    def test_01_schema_25_tablas_y_fk(self):
        self.assertEqual(self.one("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"),25)
        self.assertEqual(self.one('PRAGMA foreign_keys'),1)
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(),[])

    def test_02_catalagos_y_saldo_vacio(self):
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),0)
        self.assertEqual(self.one('SELECT count(*) FROM lista_precio'),3)

    def test_03_compra_caja_se_convierte_unidades(self):
        self.compra(2)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock'),24000)
        self.assertEqual(self.one('SELECT SUM(cantidad_delta_milli) FROM movimiento_stock'),24000)
        self.assertEqual(self.one('SELECT diferencia_milli FROM vw_stock_conciliacion'),0)

    def test_04_venta_pagos_combinados_y_stock(self):
        self.compra(2)
        vid=self.preparar_venta()
        self.confirmar(vid)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock'),21000)
        self.assertEqual(self.one('SELECT SUM(monto_centavos) FROM venta_pago WHERE venta_id=?',(vid,)),3000)
        self.assertEqual(self.one('SELECT count(*) FROM movimiento_stock WHERE tipo="VENTA"'),1)

    def test_05_venta_sin_stock_bloqueada_con_rollback(self):
        vid=self.preparar_venta(cantidad_unidades=3,pagos=(3000,))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'STOCK_INSUFICIENTE'):
            self.confirmar(vid)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock'),0)
        self.assertEqual(self.one('SELECT estado FROM venta WHERE id=?',(vid,)),'BORRADOR')
        self.assertEqual(self.one('SELECT count(*) FROM movimiento_stock'),0)

    def test_06_pago_incompleto_bloqueado(self):
        self.compra(1)
        vid=self.preparar_venta(pagos=(2000,))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'VENTA_PAGOS_NO_CUADRAN'):
            self.confirmar(vid)

    def test_07_sin_comprobante_no_confirma(self):
        self.compra(1)
        vid=self.preparar_venta(pagos=(3000,))
        self.db.execute('DELETE FROM comprobante WHERE venta_id=?',(vid,))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'VENTA_SIN_COMPROBANTE'):
            self.confirmar(vid)

    def test_08_ajuste_negativo_autorizado(self):
        self.compra(1)
        self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,usuario_id,autorizado_por_id,motivo,operacion_uuid) VALUES(1,'AJUSTE_NEGATIVO',-2000,2,1,'Conteo físico',?)",(str(uuid4()),))
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock'),10000)

    def test_09_ajuste_no_autorizado(self):
        self.compra(1)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'AJUSTE_REQUIERE_ADMIN_ACTIVO'):
            self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,usuario_id,autorizado_por_id,motivo,operacion_uuid) VALUES(1,'AJUSTE_NEGATIVO',-2000,2,2,'Conteo',?)",(str(uuid4()),))

    def test_10_ajuste_sin_motivo(self):
        self.compra(1)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'AJUSTE_NEGATIVO',-2000,2,1,?)",(str(uuid4()),))

    def test_11_no_manipular_saldo(self):
        self.compra(1)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'SALDO_NO_COINCIDE_CON_KARDEX'):
            self.db.execute('UPDATE saldo_stock SET cantidad_milli=999000 WHERE producto_id=1')

    def test_12_kardex_no_editable(self):
        self.compra(1)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'KARDEX_INMUTABLE'):
            self.db.execute("UPDATE movimiento_stock SET cantidad_delta_milli=1000 WHERE id=1")
        with self.assertRaisesRegex(sqlite3.IntegrityError,'KARDEX_INMUTABLE'):
            self.db.execute('DELETE FROM movimiento_stock WHERE id=1')

    def test_13_precio_historico_no_solapa(self):
        self.db.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por) VALUES(1,1,100000,'2026-10-01T00:00:00.000Z',1)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por) VALUES(1,1,110000,'2026-10-02T00:00:00.000Z',1)")
        self.db.execute("UPDATE precio_presentacion SET vigente_hasta='2026-10-02T00:00:00.000Z' WHERE id=1")
        self.db.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por) VALUES(1,1,110000,'2026-10-02T00:00:00.000Z',1)")
        self.assertEqual(self.one("SELECT precio_micro FROM vw_precio_actual WHERE presentacion_id=1"),110000)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'PRECIO_HISTORICO_INMUTABLE'):
            self.db.execute('UPDATE precio_presentacion SET precio_micro=9999 WHERE id=1')

    def test_14_venta_confirmada_inmutable(self):
        self.compra(1)
        vid=self.preparar_venta()
        self.confirmar(vid)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'VENTA_CONFIRMADA_INMUTABLE'):
            self.db.execute('UPDATE venta SET total_centavos=10 WHERE id=?',(vid,))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'DETALLE_VENTA_HISTORICA'):
            self.db.execute('DELETE FROM venta_detalle WHERE venta_id=?',(vid,))

    def test_15_no_duplicar_operacion(self):
        self.db.execute("INSERT INTO venta(operacion_uuid,vendedor_id) VALUES('operacion-duplicada',1)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO venta(operacion_uuid,vendedor_id) VALUES('operacion-duplicada',1)")

    def test_16_factura_requiere_ruc(self):
        self.compra(1)
        vid=self.preparar_venta(tipo='FACTURA')
        with self.assertRaisesRegex(sqlite3.IntegrityError,'FACTURA_REQUIERE_RUC_Y_RAZON_SOCIAL'):
            self.confirmar(vid)
        self.db.execute("UPDATE venta SET cliente_nombre_snapshot='Cliente de prueba S.A.C.',cliente_tipo_documento_snapshot='RUC',cliente_documento_snapshot='20123456789' WHERE id=?",(vid,))
        self.confirmar(vid)

    def test_17_factor_incorrecto_rechazado(self):
        self.db.execute("INSERT INTO compra(fecha_compra,usuario_id,total_centavos) VALUES(?,?,?)",(ahora(),1,1000))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO compra_detalle(compra_id,presentacion_id,cantidad_presentacion_milli,factor_base_milli_snapshot,cantidad_base_milli,costo_unitario_micro,importe_centavos) VALUES(1,2,1000,1000,1000,100000,1000)")

    def test_18_no_venta_fraccion_mal_convertida(self):
        vid=self.preparar_venta(pagos=(3000,))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('UPDATE venta_detalle SET cantidad_base_milli=999 WHERE venta_id=?',(vid,))

    def test_19_no_venta_stock_inicial_usuario_normal(self):
        with self.assertRaisesRegex(sqlite3.IntegrityError,'AJUSTE_REQUIERE_ADMIN_ACTIVO'):
            self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'INICIAL',8000,2,2,?)",(str(uuid4()),))
        self.db.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'INICIAL',8000,2,1,?)",(str(uuid4()),))
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock'),8000)

    def test_20_verificar_integridad_sqlite(self):
        self.compra(1)
        self.assertEqual(self.one('PRAGMA integrity_check'),'ok')
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(),[])
        self.assertEqual(self.one('SELECT diferencia_milli FROM vw_stock_conciliacion'),0)

if __name__=='__main__':
    unittest.main(verbosity=2)
