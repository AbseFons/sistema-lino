# Cambios técnicos v0.6

## Compatibilidad
- No se modificaron V001–V005. Se suma V006 con índices y triggers de demanda.
- Resguarda `%LOCALAPPDATA%\InventarioRepuestos\inventario.sqlite` antes de iniciar: Flyway migrará la base a versión 6.
- No confundir cierre de solicitudes con movimientos de stock o ventas.

## Diseño
- `ReportsService`: consultas de solo lectura (ventanas [desde, hasta+1) convertidas a UTC según zona Lima), resúmenes de ventas CONFIRMADAS, días comerciales, pagos, productos y alertas de no rotación.
- `DemandService`: alta/consulta/resolución transaccional, validaciones de roles, campos opcionales, auditoría y sin cambios de existencias.
- `presentation`: páginas independientes del monolito InventarioApp, reutilizando estilos e integración en navegación.
- `ReportsAndDemandIntegrationTest`: tests Java con Database real, requiere dependencias Gradle.
- `tests-sql/probar_reportes_demanda_v06.py`: 12 casos SQL, incluyendo migración V005→V006.
- Se actualiza test que cuenta migraciones de Flyway a 6 (antes tenía un recuento obsoleto).

## Pendientes detectados
- No hay reporte de margen neto confiable mientras impuesto/costo estén normalizados.
- Top productos se ordena por valor vendido, no por cantidades de unidades heterogéneas.
- Los días sin ventas se omiten en el gráfico: no equivale a una serie diaria completa con ceros.
- La agregación de demanda por texto usa coincidencia exacta normalizada; errores de digitación pueden crear grupos distintos. Corregirlos en un módulo futuro, sin alterar el dato original.
- No se implementó proveedor tributario ni backup automático en este incremento.
