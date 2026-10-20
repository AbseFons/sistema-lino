-- V004: impide confirmar dos compras con el mismo documento del mismo proveedor.
-- Compatible con la v0.3: no altera tablas, roles, administradores ni saldos.
-- En compras sin proveedor o sin documento se permite el registro, con advertencia en UI.
-- Congela datos identificatorios del proveedor al registrar la compra.
ALTER TABLE compra ADD COLUMN proveedor_nombre_snapshot TEXT;
ALTER TABLE compra ADD COLUMN proveedor_ruc_snapshot TEXT;

CREATE UNIQUE INDEX idx_compra_documento_confirmado
ON compra(proveedor_id, numero_documento COLLATE NOCASE)
WHERE estado='CONFIRMADA' AND proveedor_id IS NOT NULL AND numero_documento IS NOT NULL;

-- Consulta del último costo *por presentación* y solo en compras confirmadas.
CREATE VIEW vw_ultimo_costo_compra AS
SELECT pp.id AS presentacion_id, (
  SELECT cd.costo_unitario_micro
  FROM compra_detalle cd
  JOIN compra co ON co.id=cd.compra_id
  WHERE cd.presentacion_id=pp.id AND co.estado='CONFIRMADA'
  ORDER BY co.id DESC, cd.id DESC LIMIT 1
) AS costo_unitario_micro
FROM producto_presentacion pp;
