# Incremento v0.2 — Catálogos y presentaciones

Fecha: 2026-10-09 · Rama conceptual: catálogo y preparación de inventario.

## Alcance implementado

- Nueva pantalla **Catálogos** (solo administrador): crear, renombrar, activar/desactivar categorías y marcas.
- Protecciones: nombres no vacíos, duplicados no permitidos y no desactivar catálogos usados por productos activos.
- Formulario de nuevo producto ampliado: marca opcional, país, modelo/referencia y observaciones.
- Consulta de productos por SKU, nombre, marca, modelo o compatibilidad.
- **Ficha de producto**: edición de marca, país, modelo y observaciones (sin edición directa de stock).
- **Compatibilidades**: agregar y activar/desactivar referencias sin borrarlas.
- **Presentaciones**: registrar caja/paquete con factor de conversión, código de barras opcional, permisos de compra/venta, precio público opcional cuando no se vende. Activar/desactivar sin eliminar el historial.
- La unidad base no se puede desactivar desde estas pantallas; el factor de cada presentación no es editable.
- Registro de cambios en `auditoria`; permisos administrativos obligatorios para escrituras.

## Ejemplo de prueba manual

1. Entrar con la cuenta de administrador creada en v0.1.
2. Catálogos → Categorías: crear `Filtros`.
3. Catálogos → Marcas: crear `Bosch`.
4. Productos → Nuevo producto: SKU `FIL-TEST-001`, nombre `Filtro de aceite de prueba`, categoría `Filtros`, marca `Bosch`, unidad base `Pieza` (PZA), precio `25.00`, stock inicial `12`, mínimo `3`.
5. Seleccionar producto → Compatibilidades → agregar `Toyota Hilux 2020`.
6. Seleccionar producto → Presentaciones → `Caja x 12`, factor `12`, compra sí, venta sí, precio `270.00`.
7. Confirmar que la existencia siga siendo **12 piezas**, no 24. Crear una presentación no debe registrar compras ni ventas.
8. Buscar `Hilux` y `Bosch` por separado; el producto debe aparecer.
9. Desactivar y reactivar la compatibilidad y la caja; los datos siguen registrados.
10. Cerrar la aplicación y volver a abrir: usuario y datos deben conservarse.

## No implementado en este incremento

- No existe todavía una pantalla de compras o ventas; la alta de cajas **no añade inventario**.
- No se implementan todavía historial/cambio de precios existentes, edición del factor de conversión, modificaciones de stock mediante ajustes ni inventario físico.
- No hay integración SUNAT ni impresión de tickets, ni garantías de operación en producción.

## Compatibilidad de datos

- **No hay migración V004**: se aprovechan las tablas de V001 y las listas preexistentes de V002–V003.
- No se modificó ningún archivo de migración anterior. Flyway usará la base existente (`%LOCALAPPDATA%\\InventarioRepuestos\\inventario.sqlite`).
- El administrador de v0.1 **no debe crearse de nuevo**.
- Por seguridad, cierra completamente el programa y copia `inventario.sqlite` a una ubicación separada como respaldo antes de actualizar.

## Validación

- Prueba del esquema preexistente: `python tests-sql/probar_esquema.py` (20 verificaciones).
- Prueba de catálogo SQLite: `python tests-sql/probar_catalogos_v02.py`.
- Pruebas JUnit `CatalogAndPresentationIntegrationTest`: ejecutar con `gradle test` en un equipo con dependencias descargadas.
- Sin Gradle/JavaFX disponible en el entorno de construcción, la UI final debe compilarse y probarse en el equipo del desarrollador.
