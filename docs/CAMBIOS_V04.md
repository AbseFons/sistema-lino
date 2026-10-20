# Incremento v0.4 — Proveedores y compras

**Decisión aprobada:** solo una PC, SQLite local, stock controlado mediante kardex, productos con presentaciones comprables y precios versionados. No implementar devoluciones frecuentes en este incremento.

## Cambios técnicos

- V004: snapshots del nombre y RUC de proveedor en la compra, índice único parcial para documento confirmado por proveedor, vista del último costo por presentación.
- No modificar migraciones V001–V003. La base previa y el administrador se conservan mediante la misma ruta.
- Dos servicios nuevos: `SupplierService` y `PurchaseService`.
- Las inserciones de compra se confirman dentro de la misma transacción; los triggers existentes incrementan stock y validan total y kardex.
- Los importes usan `BigDecimal`/enteros escalados (sin `double`).
- Rol ADMIN es obligatorio en ambos módulos.
- Vista histórica de compras y detalle congelado de factores de presentación, cantidades, costos e importes.

## Política de compras

- Documento opcional, aunque se recomienda registrarlo.
- No se permite documentar como una nueva compra un comprobante ya confirmado para el mismo proveedor.
- Sin proveedor, no hay identificación única de comprobante.
- No se fracciona una caja. Cantidades de la presentación base pueden ser decimales solamente si la unidad lo permite.
- El costo de una presentación no cambia la lista de precios de venta; la UI informa variaciones frente a la última compra.
- No se edita/anula una compra confirmada en la V0.4; futuras notas de crédito y devolución deben diseñarse con movimientos inversos auditados.

## Riesgos y siguientes acciones

1. Validar compilación `gradle test` en Windows.
2. Validar login previo, nuevas pantallas y compra de dos cajas con el cliente.
3. Configurar un backup confiable antes de registrar compras reales.
4. Para producción, introducir gestión de errores operativos, backups automáticos y método de reversión documentada antes de permitir rectificaciones de proveedores.
