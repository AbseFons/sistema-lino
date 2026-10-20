-- V003: consultas de lectura. Estas vistas NO reemplazan las reglas transaccionales.
CREATE VIEW vw_stock_producto AS
SELECT p.id AS producto_id,p.sku,p.nombre,p.estado,
       s.cantidad_milli,p.stock_min_milli,p.stock_max_milli,
       CASE WHEN s.cantidad_milli=0 THEN 'AGOTADO'
            WHEN s.cantidad_milli<=p.stock_min_milli THEN 'BAJO'
            WHEN p.stock_max_milli IS NOT NULL AND s.cantidad_milli>p.stock_max_milli THEN 'SOBRESTOCK'
            ELSE 'NORMAL' END AS nivel_alerta
FROM producto p JOIN saldo_stock s ON s.producto_id=p.id;
CREATE VIEW vw_precio_actual AS
SELECT pp.producto_id, pp.id AS presentacion_id,pp.nombre AS presentacion,
       lp.codigo AS lista, ph.precio_micro,ph.vigente_desde
FROM precio_presentacion ph
JOIN producto_presentacion pp ON pp.id=ph.presentacion_id
JOIN lista_precio lp ON lp.id=ph.lista_precio_id
WHERE ph.vigente_hasta IS NULL;
CREATE VIEW vw_stock_conciliacion AS
SELECT p.id AS producto_id,p.sku,s.cantidad_milli AS saldo_milli,
       COALESCE(SUM(m.cantidad_delta_milli),0) AS movimientos_milli,
       s.cantidad_milli-COALESCE(SUM(m.cantidad_delta_milli),0) AS diferencia_milli
FROM producto p JOIN saldo_stock s ON s.producto_id=p.id
LEFT JOIN movimiento_stock m ON m.producto_id=p.id
GROUP BY p.id,p.sku,s.cantidad_milli;
CREATE VIEW vw_venta_pagos AS
SELECT v.id AS venta_id,v.operacion_uuid,v.estado,v.total_centavos,
       COALESCE(SUM(vp.monto_centavos),0) AS pagado_centavos,
       v.total_centavos-COALESCE(SUM(vp.monto_centavos),0) AS diferencia_centavos
FROM venta v LEFT JOIN venta_pago vp ON vp.venta_id=v.id
GROUP BY v.id,v.operacion_uuid,v.estado,v.total_centavos;
