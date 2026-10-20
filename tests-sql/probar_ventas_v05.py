#!/usr/bin/env python3
"""Contrato de integridad para v0.5; usa SQLite real y todas las migraciones."""
import pathlib, sqlite3, unittest, uuid
MIG=pathlib.Path(__file__).resolve().parents[1]/'src/main/resources/db/migration'

class TestVentas(unittest.TestCase):
    def setUp(self):
        self.c=sqlite3.connect(':memory:')
        self.c.execute('PRAGMA foreign_keys=ON')
        for p in sorted(MIG.glob('V*.sql')):self.c.executescript(p.read_text(encoding='utf-8'))
        c=self.c
        c.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Admin','admin',?)",('A'*64,))
        c.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(2,'Vendedor','vende',?)",('A'*64,))
        c.execute("INSERT INTO producto(sku,nombre,categoria_id,unidad_base_id,estado,creado_por) VALUES('PRUEBA-1','Filtro',1,1,'ACTIVO',1)")
        c.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(1,'Unidad',1000)")
        c.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(1,'Caja x12',12000)")
        c.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por) VALUES(1,1,1180000,'2026-10-01T01:00:00.000Z',1)")
        c.execute("INSERT INTO precio_presentacion(presentacion_id,lista_precio_id,precio_micro,vigente_desde,registrado_por) VALUES(2,1,14160000,'2026-10-01T01:00:00.000Z',1)")
        c.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'INICIAL',24000,1,1,?)",(str(uuid.uuid4()),))
        c.execute("INSERT INTO cliente(tipo_persona,tipo_documento,numero_documento,nombre_razon_social) VALUES('JURIDICA','RUC','20111111111','Empresa ABC')")
        c.commit()

    def tearDown(self):self.c.close()
    def one(self,q,args=()):return self.c.execute(q,args).fetchone()[0]
    def prepare(self,total=11800,price=1180000,qty=1000,base=1000,kind='BOLETA',buyer=None,
                payments=(("YAPE",8000),("EFECTIVO",3800)),discount=0,tax=1800,subtotal=10000,presentation=1):
        c=self.c
        buyerName='Empresa ABC' if buyer else 'Consumidor final'
        document='20111111111' if buyer else None
        dtype='RUC' if buyer else None
        op=str(uuid.uuid4())
        v=c.execute('INSERT INTO venta(operacion_uuid,vendedor_id,cliente_id,cliente_nombre_snapshot,cliente_tipo_documento_snapshot,cliente_documento_snapshot,lista_precio_id,subtotal_centavos,descuento_centavos,impuesto_centavos,total_centavos,solicitud_hash) VALUES(?,2,?,?,?,?,1,?,?,?,?,?)',
              (op,buyer,buyerName,dtype,document,subtotal,discount,tax,total,'x'*64)).lastrowid
        factor=12000 if presentation==2 else 1000
        sku='PRUEBA-1'
        c.execute('INSERT INTO venta_detalle(venta_id,presentacion_id,sku_snapshot,descripcion_snapshot,cantidad_presentacion_milli,factor_base_milli_snapshot,cantidad_base_milli,precio_lista_micro,precio_final_micro,descuento_centavos,impuesto_centavos,total_centavos) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
               (v,presentation,sku,'Filtro',qty,factor,base,14160000 if presentation==2 else 1180000,price,discount,tax,total))
        for method,amount in payments:
            c.execute('INSERT INTO venta_pago(venta_id,medio,monto_centavos) VALUES(?,?,?)',(v,method,amount))
        c.execute('INSERT INTO comprobante(venta_id,tipo,idempotency_key) VALUES(?,?,?)',(v,kind,'CPE:'+op))
        return v

    def confirm(self,v):
        self.c.execute("UPDATE venta SET estado='CONFIRMADA',confirmada_en='2026-10-09T17:00:00Z' WHERE id=?",(v,))

    def test_pagado_combinado_y_stock(self):
        v=self.prepare();self.confirm(v)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),23000)
        self.assertEqual(self.one('SELECT COUNT(*) FROM venta_pago WHERE venta_id=?',(v,)),2)
        self.assertEqual(self.one('SELECT diferencia_centavos FROM vw_venta_pagos WHERE venta_id=?',(v,)),0)
        self.assertEqual(self.one('SELECT diferencia_milli FROM vw_stock_conciliacion WHERE producto_id=1'),0)
        self.assertEqual(self.one('SELECT estado FROM comprobante WHERE venta_id=?',(v,)),'PENDIENTE')

    def test_pago_incompleto_bloquea(self):
        v=self.prepare(payments=(('YAPE',8000),))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'VENTA_PAGOS_NO_CUADRAN'):self.confirm(v)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),24000)

    def test_pago_excesivo_bloquea(self):
        v=self.prepare(payments=(('EFECTIVO',11801),))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'VENTA_PAGOS_NO_CUADRAN'):self.confirm(v)

    def test_sin_comprobante_no_confirma(self):
        v=self.prepare();self.c.execute('DELETE FROM comprobante WHERE venta_id=?',(v,))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'VENTA_SIN_COMPROBANTE'):self.confirm(v)

    def test_factura_sin_ruc_no_confirma(self):
        v=self.prepare(kind='FACTURA')
        with self.assertRaisesRegex(sqlite3.IntegrityError,'FACTURA_REQUIERE_RUC'):self.confirm(v)

    def test_factura_con_ruc_pendiente(self):
        v=self.prepare(kind='FACTURA',buyer=1);self.confirm(v)
        self.assertEqual(self.one('SELECT tipo FROM comprobante WHERE venta_id=?',(v,)),'FACTURA')
        self.assertEqual(self.one('SELECT estado FROM comprobante WHERE venta_id=?',(v,)),'PENDIENTE')

    def test_stock_insuficiente_rechaza_salida(self):
        v=self.prepare(qty=25000,base=25000,total=295000,subtotal=250000,tax=45000,payments=(('EFECTIVO',295000),))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'STOCK_INSUFICIENTE'):self.confirm(v)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),24000)

    def test_caja_12_descontada_12_unidades(self):
        v=self.prepare(presentation=2,qty=1000,base=12000,price=14160000,subtotal=120000,tax=21600,total=141600,
                       payments=(('YAPE',60000),('EFECTIVO',81600)))
        self.confirm(v)
        self.assertEqual(self.one('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),12000)

    def test_confirmacion_no_puede_repetirse(self):
        v=self.prepare();self.confirm(v)
        with self.assertRaisesRegex(sqlite3.IntegrityError,'VENTA_CONFIRMADA_INMUTABLE'):self.confirm(v)
        self.assertEqual(self.one("SELECT COUNT(*) FROM movimiento_stock WHERE tipo='VENTA'"),1)

    def test_no_editar_venta_historica(self):
        v=self.prepare();self.confirm(v)
        with self.assertRaises(sqlite3.IntegrityError):self.c.execute('UPDATE venta_detalle SET precio_final_micro=1 WHERE venta_id=?',(v,))
        with self.assertRaises(sqlite3.IntegrityError):self.c.execute('DELETE FROM venta_pago WHERE venta_id=?',(v,))

    def test_uuid_unico(self):
        v=self.prepare();self.confirm(v)
        op=self.one('SELECT operacion_uuid FROM venta WHERE id=?',(v,))
        with self.assertRaises(sqlite3.IntegrityError):
            self.c.execute("INSERT INTO venta(operacion_uuid,vendedor_id,subtotal_centavos,descuento_centavos,impuesto_centavos,total_centavos) VALUES(?,2,0,0,0,0)",(op,))

    def test_migracion_v04_a_v05_preserva_datos(self):
        c=sqlite3.connect(':memory:');c.execute('PRAGMA foreign_keys=ON')
        for p in sorted(MIG.glob('V*.sql'))[:4]:c.executescript(p.read_text(encoding='utf-8'))
        c.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Persistente','admin',?)",('B'*64,))
        c.execute("INSERT INTO producto(sku,nombre,categoria_id,unidad_base_id,estado,creado_por) VALUES('LEGACY','Ya existe',1,1,'ACTIVO',1)")
        c.executescript((MIG/'V005__ventas_clientes.sql').read_text(encoding='utf-8'))
        self.assertEqual(c.execute('SELECT nombre FROM usuario WHERE id=1').fetchone()[0],'Persistente')
        self.assertEqual(c.execute('SELECT sku FROM producto WHERE id=1').fetchone()[0],'LEGACY')
        self.assertIn('solicitud_hash',[r[1] for r in c.execute('PRAGMA table_info(venta)')])
        c.close()

if __name__=='__main__': unittest.main(verbosity=2)
