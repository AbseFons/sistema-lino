# Versión 0.5 — Clientes, POS, pagos combinados

- Se agregaron 10 registros de dominio y dos servicios nuevos.
- Se agregó la migración V005 sin alterar V001–V004.
- `venta.solicitud_hash` permite verificar reintentos de `operacion_uuid`, además del índice UNIQUE existente.
- Se crean detalle, pagos y comprobante provisional en la misma transacción que confirma y descuenta el stock.
- Se verifica stock por producto agregando cantidades de todas las líneas.
- La base impide pagos incompletos y ediciones directas de ventas confirmadas.
- Se mantiene un precio de lista por presentación; los descuentos solo están permitidos al administrador.
- Los importes suponen IGV 18 % con precio al público incluido. **No apto para emisión tributaria productiva todavía**.
- No se implementa anulación/devolución; usar exclusivamente datos de prueba por ahora.
