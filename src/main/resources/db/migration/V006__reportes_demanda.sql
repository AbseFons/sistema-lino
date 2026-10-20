-- V006: reportes (indices) y protección de solicitudes no atendidas.
-- Compatible con V001-V005; no cambia ni elimina registros existentes.
CREATE INDEX idx_demanda_producto_estado ON demanda_no_atendida(producto_id, estado);
CREATE INDEX idx_demanda_descripcion ON demanda_no_atendida(descripcion COLLATE NOCASE);
CREATE INDEX idx_venta_detalle_venta ON venta_detalle(venta_id);

-- La demanda es un registro de oportunidad perdida: se resuelve, nunca se borra.
CREATE TRIGGER trg_demanda_no_borrar BEFORE DELETE ON demanda_no_atendida
BEGIN SELECT RAISE(ABORT,'La solicitud no puede eliminarse: conservar historial'); END;

-- Se conservan descripción, usuario, fecha, producto, cliente y cantidad originales.
CREATE TRIGGER trg_demanda_datos_inmutables BEFORE UPDATE OF fecha,descripcion,marca_modelo,
    cantidad_milli,producto_id,cliente_id,usuario_id ON demanda_no_atendida
BEGIN SELECT RAISE(ABORT,'Los datos originales de la solicitud son inmutables'); END;

CREATE TRIGGER trg_demanda_transicion BEFORE UPDATE OF estado ON demanda_no_atendida
WHEN OLD.estado <> 'PENDIENTE' OR NEW.estado NOT IN ('ATENDIDA','DESCARTADA')
BEGIN SELECT RAISE(ABORT,'Solo se puede resolver una solicitud PENDIENTE'); END;

CREATE TRIGGER trg_demanda_notas_cerradas BEFORE UPDATE OF observaciones ON demanda_no_atendida
WHEN OLD.estado <> 'PENDIENTE'
BEGIN SELECT RAISE(ABORT,'Una solicitud resuelta ya no admite modificaciones'); END;
