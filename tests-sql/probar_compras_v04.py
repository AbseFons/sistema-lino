#!/usr/bin/env python3
"""Pruebas SQLite reales del módulo compras (triggers y migración V004).
No reemplazan la compilación JavaFX ni la prueba manual en Windows.
"""
from pathlib import Path
import sqlite3
import unittest

MIG = Path(__file__).resolve().parents[1] / 'src/main/resources/db/migration'
MIGS = sorted(MIG.glob('V*.sql'))

class ComprasSqlite(unittest.TestCase):
    def setUp(self):
        self.c = sqlite3.connect(':memory:')
        self.c.execute('PRAGMA foreign_keys=ON')
        for p in MIGS:
            self.c.executescript(p.read_text(encoding='utf-8'))
        self.c.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Admin','admin',?)",('X'*64,))
        self.c.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(2,'Vendedor','v',?)",('X'*64,))
        self.c.execute("INSERT INTO proveedor(razon_social,ruc) VALUES('Distribuidora A','20111111111')")
        self.c.execute("INSERT INTO producto(sku,nombre,categoria_id,unidad_base_id,stock_min_milli,estado,creado_por) VALUES('FIL-001','Filtro',1,1,2000,'ACTIVO',1)")
        self.c.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(1,'Unidad',1000)")
        self.c.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(1,'Caja x12',12000)")

    def tearDown(self):self.c.close()
    def one(self,q,params=()):return self.c.execute(q,params).fetchone()[0]

    def create_purchase(self,doc='F001-001',provider=1,lines=((2,2000,12000,24000,850000,170000),),total=None):
        # por defecto 2 cajas x12 a S/85 => S/170.00
        if total is None:total=sum(line[5] for line in lines)
        cur=self.c.execute("INSERT INTO compra(proveedor_id,numero_documento,fecha_compra,total_centavos,usuario_id) VALUES(?,?,?, ?,1)",(provider,doc,'2026-10-09',total))
        pid=cur.lastrowid
        for presentation,qty,factor,base,cost,amount in lines:
            self.c.execute("INSERT INTO compra_detalle(compra_id,presentacion_id,cantidad_presentacion_milli,factor_base_milli_snapshot,cantidad_base_milli,costo_unitario_micro,importe_centavos) VALUES(?,?,?,?,?,?,?)",
                (pid,presentation,qty,factor,base,cost,amount))
        return pid
    def confirm(self,pid):
        self.c.execute("UPDATE compra SET estado='CONFIRMADA',confirmada_en='2026-10-09T23:00:00Z' WHERE id=?",(pid,))

    def test_dos_cajas_x12_aumentan_24_unidades(self):
        p=self.create_purchase();self.confirm(p)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),24000)
        self.assertEqual(self.one('SELECT cantidad_delta_milli FROM movimiento_stock WHERE compra_detalle_id=1'),24000)
        self.assertEqual(self.one('SELECT diferencia_milli FROM vw_stock_conciliacion WHERE producto_id=1'),0)

    def test_agregar_unidad_y_caja_en_una_compra(self):
        p=self.create_purchase(lines=((2,2000,12000,24000,850000,170000),(1,3000,1000,3000,70000,2100)))
        self.confirm(p)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),27000)
        self.assertEqual(self.one('SELECT COUNT(*) FROM movimiento_stock'),2)
        self.assertEqual(self.one('SELECT total_centavos FROM compra WHERE id=?',(p,)),172100)

    def test_no_confirmar_total_incorrecto(self):
        p=self.create_purchase(total=1)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'COMPRA_TOTAL_INCONSISTENTE'):self.confirm(p)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),0)
        self.assertEqual(self.one('SELECT estado FROM compra WHERE id=?',(p,)),'BORRADOR')

    def test_no_confirmar_sin_detalle(self):
        p=self.create_purchase(lines=(),total=0)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'COMPRA_SIN_DETALLE'):self.confirm(p)
        self.assertEqual(self.one('SELECT COUNT(*) FROM movimiento_stock'),0)

    def test_bloquea_documento_duplicado_confirmado(self):
        p=self.create_purchase('F001-001');self.confirm(p)
        p2=self.create_purchase('F001-001')
        with self.assertRaises(sqlite3.IntegrityError):self.confirm(p2)
        self.assertEqual(self.one('SELECT COUNT(*) FROM compra WHERE estado="CONFIRMADA"'),1)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),24000)

    def test_bloquea_presentacion_inactiva_o_no_comprable(self):
        self.c.execute('UPDATE producto_presentacion SET se_compra=0 WHERE id=2')
        with self.assertRaisesRegex(sqlite3.IntegrityError,'PRESENTACION_COMPRA_NO_DISPONIBLE'):
            self.create_purchase()

    def test_no_modificar_detalle_de_compra_confirmada(self):
        p=self.create_purchase();self.confirm(p)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'DETALLE_COMPRA_HISTORICA'):
            self.c.execute('UPDATE compra_detalle SET costo_unitario_micro=1 WHERE compra_id=?',(p,))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'DETALLE_COMPRA_HISTORICA'):
            self.c.execute('DELETE FROM compra_detalle WHERE compra_id=?',(p,))
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),24000)

    def test_no_confirmar_dos_veces(self):
        p=self.create_purchase();self.confirm(p)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'COMPRA_CONFIRMADA_INMUTABLE'):
            self.confirm(p)
        self.assertEqual(self.one('SELECT COUNT(*) FROM movimiento_stock'),1)

    def test_vista_ultimo_costo_y_conciliacion(self):
        a=self.create_purchase('F001-001');self.confirm(a)
        b=self.create_purchase('F001-002',lines=((2,1000,12000,12000,900000,90000),))
        self.c.execute('UPDATE compra SET estado="CONFIRMADA",confirmada_en="2026-10-09T23:10:00Z" WHERE id=?',(b,))
        self.assertEqual(self.one('SELECT costo_unitario_micro FROM vw_ultimo_costo_compra WHERE presentacion_id=2'),900000)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),36000)
        self.assertEqual(self.one('SELECT diferencia_milli FROM vw_stock_conciliacion WHERE producto_id=1'),0)

    def test_no_permitir_editar_kardex(self):
        p=self.create_purchase();self.confirm(p)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'KARDEX_INMUTABLE'):
            self.c.execute('DELETE FROM movimiento_stock WHERE tipo="COMPRA"')

    def test_migracion_desde_v03_preserva_administrador_y_stock(self):
        # Prueba de actualización sin eliminar datos existentes del usuario.
        old=sqlite3.connect(':memory:')
        old.execute('PRAGMA foreign_keys=ON')
        for version in ('V001__estructura_inicial.sql','V002__catalogos_basicos.sql','V003__vistas_consulta.sql'):
            old.executescript((MIG/version).read_text(encoding='utf-8'))
        old.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Dueño','cuenta_admin',?)",('Z'*64,))
        old.execute("INSERT INTO producto(sku,nombre,categoria_id,unidad_base_id,estado,creado_por) VALUES('SKU-OLD','Repuesto registrado',1,1,'ACTIVO',1)")
        old.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,motivo,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'INICIAL',9000,'Contado',1,1,'initial-test')")
        old.executescript((MIG/'V004__compras_indice_documento.sql').read_text(encoding='utf-8'))
        self.assertEqual(old.execute("SELECT COUNT(*) FROM usuario WHERE login='cuenta_admin'").fetchone()[0],1)
        self.assertEqual(old.execute('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1').fetchone()[0],9000)
        self.assertEqual(old.execute('PRAGMA integrity_check').fetchone()[0],'ok')
        self.assertIn('proveedor_ruc_snapshot',[row[1] for row in old.execute('PRAGMA table_info(compra)')])
        old.close()

    def test_integridad_referencial(self):
        p=self.create_purchase();self.confirm(p)
        self.assertEqual(self.one('PRAGMA integrity_check'),'ok')
        self.assertEqual(self.c.execute('PRAGMA foreign_key_check').fetchall(),[])

if __name__=='__main__':unittest.main(verbosity=2)
