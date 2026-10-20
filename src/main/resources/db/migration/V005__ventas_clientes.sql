-- V005: Registro de solicitudes de venta idempotentes y consultas de caja.
-- No asigna series, números ni simula documentos tributarios emitidos.
ALTER TABLE venta ADD COLUMN solicitud_hash TEXT;
CREATE INDEX idx_venta_cliente_fecha ON venta(cliente_id,fecha DESC);
CREATE INDEX idx_venta_fecha_estado ON venta(estado,fecha DESC);
CREATE INDEX idx_venta_pago_medio ON venta_pago(medio,venta_id);
CREATE INDEX idx_cliente_nombre ON cliente(nombre_razon_social COLLATE NOCASE);
CREATE VIEW vw_ventas_confirmadas AS
SELECT v.id,v.fecha,v.operacion_uuid,v.vendedor_id,v.cliente_id,
       COALESCE(v.cliente_nombre_snapshot,'Consumidor final') AS cliente,
       v.total_centavos,c.tipo AS tipo_comprobante,c.estado AS estado_comprobante,
       (SELECT COUNT(*) FROM venta_detalle d WHERE d.venta_id=v.id) AS lineas,
       (SELECT group_concat(medio||': '||printf('%.2f',monto_centavos/100.0), ' + ')
          FROM venta_pago pg WHERE pg.venta_id=v.id) AS pagos
FROM venta v JOIN comprobante c ON c.venta_id=v.id
WHERE v.estado='CONFIRMADA';
