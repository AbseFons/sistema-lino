#!/usr/bin/env python3
"""Contrato SQLite v0.6 y upgrade V005=>V006. Sin datos reales del usuario."""
from pathlib import Path
import sqlite3, unittest, uuid

MIG=Path(__file__).resolve().parents[1]/'src/main/resources/db/migration'

class ReportsAndDemandContract(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        for script in sorted(MIG.glob('V*.sql')):
            self.db.executescript(script.read_text('utf-8'))
        c=self.db
        c.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Admin','admin',?)",('X'*64,))
        c.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(2,'Vendedor','vendedor',?)",('X'*64,))
        c.execute("INSERT INTO producto(sku,nombre,categoria_id,unidad_base_id,estado,creado_por) VALUES('FIL-A','Filtro A',1,1,'ACTIVO',1)")
        c.execute("INSERT INTO producto_presentacion(producto_id,nombre,factor_base_milli) VALUES(1,'Unidad',1000)")
        c.execute("INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,usuario_id,autorizado_por_id,operacion_uuid) VALUES(1,'INICIAL',20000,1,1,?)",(str(uuid.uuid4()),))
        c.commit()

    def tearDown(self):self.db.close()
    def scalar(self,q,*args):return self.db.execute(q,args).fetchone()[0]
    def request(self,name='Filtro especial',brand='Marca X',state='PENDIENTE',qty=2000):
        return self.db.execute('INSERT INTO demanda_no_atendida(descripcion,marca_modelo,usuario_id,cantidad_milli,estado) VALUES(?,?,?,?,?)',
                               (name,brand,2,qty,state)).lastrowid
    def sale(self,timestamp='2026-10-10T02:00:00.000Z',status='CONFIRMADA',method='YAPE'):
        op=str(uuid.uuid4())
        c=self.db
        vid=c.execute("INSERT INTO venta(operacion_uuid,fecha,vendedor_id,lista_precio_id,subtotal_centavos,descuento_centavos,impuesto_centavos,total_centavos) VALUES(?,?,2,1,10000,0,1800,11800)",(op,timestamp)).lastrowid
        c.execute("INSERT INTO venta_detalle(venta_id,presentacion_id,sku_snapshot,descripcion_snapshot,cantidad_presentacion_milli,factor_base_milli_snapshot,cantidad_base_milli,precio_lista_micro,precio_final_micro,impuesto_centavos,total_centavos) VALUES(?,1,'FIL-A','Filtro A',1000,1000,1000,1180000,1180000,1800,11800)",(vid,))
        c.execute('INSERT INTO venta_pago(venta_id,medio,monto_centavos) VALUES(?,?,?)',(vid,method,11800))
        c.execute("INSERT INTO comprobante(venta_id,tipo,idempotency_key) VALUES(?,'BOLETA',?)",(vid,'CPE:'+op))
        if status=='CONFIRMADA':
            c.execute("UPDATE venta SET estado='CONFIRMADA',confirmada_en=? WHERE id=?",(timestamp,vid))
        return vid

    def test_tabla_demanda_preexistente_se_conserva(self):
        id=self.request()
        self.assertEqual(self.scalar('SELECT descripcion FROM demanda_no_atendida WHERE id=?',id),'Filtro especial')
    def test_demanda_no_borrable(self):
        id=self.request()
        with self.assertRaisesRegex(sqlite3.IntegrityError,'solicitud no puede eliminarse'):
            self.db.execute('DELETE FROM demanda_no_atendida WHERE id=?',(id,))
    def test_no_modificar_datos_originales(self):
        id=self.request()
        with self.assertRaisesRegex(sqlite3.IntegrityError,'inmutables'):
            self.db.execute("UPDATE demanda_no_atendida SET descripcion='Otra' WHERE id=?",(id,))
        self.assertEqual(self.scalar('SELECT descripcion FROM demanda_no_atendida WHERE id=?',id),'Filtro especial')
    def test_solo_pendiente_puede_resolverse(self):
        id=self.request()
        self.db.execute("UPDATE demanda_no_atendida SET estado='ATENDIDA',observaciones='Llegó stock' WHERE id=?",(id,))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'Solo se puede resolver'):
            self.db.execute("UPDATE demanda_no_atendida SET estado='DESCARTADA' WHERE id=?",(id,))
        self.assertEqual(self.scalar('SELECT estado FROM demanda_no_atendida WHERE id=?',id),'ATENDIDA')
    def test_notas_inmutables_tras_resolver(self):
        id=self.request()
        self.db.execute("UPDATE demanda_no_atendida SET estado='DESCARTADA',observaciones='No lo venderemos' WHERE id=?",(id,))
        with self.assertRaisesRegex(sqlite3.IntegrityError,'resuelta'):
            self.db.execute("UPDATE demanda_no_atendida SET observaciones='Cambio' WHERE id=?",(id,))
    def test_ranking_pendientes_y_agrupamiento(self):
        self.request('Filtro especial','Marca X')
        self.request(' filtro ESPECIAL ','marca x',qty=1000)
        self.request('Filtro especial','Marca X',state='ATENDIDA')
        result=self.db.execute("""
            SELECT COUNT(*),SUM(cantidad_milli) FROM demanda_no_atendida
            WHERE estado='PENDIENTE'
            GROUP BY lower(trim(descripcion)),lower(trim(COALESCE(marca_modelo,'')))
            """).fetchall()
        self.assertEqual(result,[(2,3000)])
    def test_ventas_confirmadas_y_borradores(self):
        self.sale(status='BORRADOR')
        self.sale()
        self.assertEqual(self.scalar("SELECT COUNT(*) FROM venta WHERE estado='CONFIRMADA'"),1)
        self.assertEqual(self.scalar("SELECT SUM(total_centavos) FROM venta WHERE estado='CONFIRMADA'"),11800)
        self.assertEqual(self.scalar("SELECT SUM(pg.monto_centavos) FROM venta_pago pg JOIN venta v ON v.id=pg.venta_id WHERE v.estado='CONFIRMADA'"),11800)
    def test_fecha_local_peru(self):
        self.sale('2026-10-10T02:00:00.000Z')  # 9 octubre 21:00 Perú
        self.assertEqual(self.scalar("SELECT date(fecha,'-5 hours') FROM venta WHERE estado='CONFIRMADA'"),'2026-10-09')
    def test_pago_mixto_reportado_por_medio(self):
        self.sale()
        # pagos históricos confirmados NO se pueden modificar; registrar otra venta con pago mixto.
        op=str(uuid.uuid4())
        w=self.db.execute("INSERT INTO venta(operacion_uuid,vendedor_id,lista_precio_id,subtotal_centavos,descuento_centavos,impuesto_centavos,total_centavos) VALUES(?,2,1,10000,0,1800,11800)",(op,)).lastrowid
        self.db.execute("INSERT INTO venta_detalle(venta_id,presentacion_id,sku_snapshot,descripcion_snapshot,cantidad_presentacion_milli,factor_base_milli_snapshot,cantidad_base_milli,precio_lista_micro,precio_final_micro,impuesto_centavos,total_centavos) VALUES(?,1,'FIL-A','Filtro A',1000,1000,1000,1180000,1180000,1800,11800)",(w,))
        self.db.execute("INSERT INTO venta_pago(venta_id,medio,monto_centavos) VALUES(?, 'YAPE',8000)",(w,))
        self.db.execute("INSERT INTO venta_pago(venta_id,medio,monto_centavos) VALUES(?, 'EFECTIVO',3800)",(w,))
        self.db.execute("INSERT INTO comprobante(venta_id,tipo,idempotency_key) VALUES(?,'BOLETA',?)",(w,'CPE:'+op))
        self.db.execute("UPDATE venta SET estado='CONFIRMADA',confirmada_en='2026-10-10T02:00:00.000Z' WHERE id=?",(w,))
        amounts=dict(self.db.execute("SELECT medio,SUM(monto_centavos) FROM venta_pago GROUP BY medio"))
        self.assertEqual(amounts,{'EFECTIVO':3800,'YAPE':19800})
    def test_stock_y_ventas_se_concilian(self):
        self.sale();self.sale()
        self.assertEqual(self.scalar('SELECT cantidad_milli FROM saldo_stock WHERE producto_id=1'),18000)
        self.assertEqual(self.scalar('SELECT diferencia_milli FROM vw_stock_conciliacion WHERE producto_id=1'),0)
    def test_upgrade_v005_v006_preserva_registros(self):
        old=sqlite3.connect(':memory:');old.execute('PRAGMA foreign_keys=ON')
        for p in sorted(MIG.glob('V*.sql'))[:5]:old.executescript(p.read_text('utf-8'))
        old.execute("INSERT INTO usuario(rol_id,nombre,login,password_hash) VALUES(1,'Admin existente','real_admin',?)",('Z'*64,))
        old.execute("INSERT INTO demanda_no_atendida(descripcion,usuario_id) VALUES('Repuesto histórico',1)")
        old.executescript((MIG/'V006__reportes_demanda.sql').read_text('utf-8'))
        self.assertEqual(old.execute("SELECT nombre FROM usuario WHERE login='real_admin'").fetchone()[0],'Admin existente')
        self.assertEqual(old.execute("SELECT descripcion FROM demanda_no_atendida WHERE id=1").fetchone()[0],'Repuesto histórico')
        self.assertEqual(old.execute('PRAGMA integrity_check').fetchone()[0],'ok')
        old.close()
    def test_fk_check(self):
        self.request();self.sale()
        self.assertEqual(self.db.execute('PRAGMA foreign_key_check').fetchall(),[])
        self.assertEqual(self.scalar('PRAGMA integrity_check'),'ok')

if __name__=='__main__':unittest.main(verbosity=2)
