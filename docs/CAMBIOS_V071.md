# Versión 0.7.1 — Exportación integral de Excel

## Alcance

Se añade **Reportes → Exportar Excel completo (.xlsx)**. Esta acción genera **un único libro con nueve hojas** y mantiene sin cambios el desplegable y botón **Exportar CSV** para reportes individuales.

| Hoja | Datos | Filtro |
|---|---|---|
| Resumen | Ventas confirmadas, facturación operativa, descuentos, ticket promedio, cobros, CPE pendientes, productos y solicitudes pendientes | Ventas: fechas indicadas; productos y solicitudes: estado actual |
| Ventas | Una fila por venta, vendedor, cliente, tipo y estado del comprobante, impuestos y total; métodos de pago en una misma celda | Fechas indicadas |
| Detalle de ventas | Línea por producto, SKU histórico, presentación, cantidades, precio, descuentos e impuesto | Fechas indicadas |
| Productos vendidos | SKU, producto, cantidad base, ventas distintas, facturación | Fechas indicadas |
| Pagos | Cantidad de operaciones y montos por medio de pago | Fechas indicadas |
| Inventario | Todos los productos, con stock, mínimo/máximo, unidad, marca, categoría y estado | Estado actual, sin filtro |
| Rotación | Stock, cantidad vendida en el período, última venta histórica y estado según el umbral de 30/60/90/180 días | Ventas del período + última venta histórica |
| Solicitudes | Historial de solicitudes pendientes, atendidas o descartadas | Historial completo, sin filtro |
| Ventas diarias | Unidades de transacción y facturación por día comercial de Perú | Fechas indicadas |

## Notas técnicas

- Se incorpora `FullExcelReportService` y `SimpleXlsxWriter` (OOXML XLSX usando únicamente Java 21). **No se agrega una dependencia nueva.**
- El libro se escribe por filas y se guarda en archivo temporal antes de moverlo a destino. Si ocurre un error, no se reemplaza un informe previo con contenido incompleto.
- Todas las hojas se consultan dentro de una transacción de lectura sobre una sola conexión SQLite para evitar mezclas de diferentes estados durante el proceso.
- Celdas de dinero son numéricas con formato **S/ #,##0.00**, cantidades numéricas con hasta tres decimales y precios unitarios con cuatro decimales (precisión en base a micro-precios del esquema).
- Los datos de clientes y productos son cadenas OOXML `inlineStr`, por lo que no se ejecutan como fórmulas si comienzan por `=`, `+`, `-` o `@`.
- Encabezados de color, hojas con ancho configurado, panel superior inmovilizado y autofiltros en tablas.
- Las ventas confirmadas se cuentan una vez aunque tengan varios pagos. Las cifras **no implican emisión de comprobantes electrónicos**.
- No se calculan utilidades contables, pues todavía hay reglas tributarias/costos que validar.
- **No se modifica ninguna migración V001–V006** ni la ubicación de los datos ni el mecanismo de respaldos.
- Los archivos XLSX contienen información comercial y datos personales: se deben guardar en ubicaciones seguras.

## Prueba en Windows (recomendada)

1. Cierra la app y realiza un respaldo manual de SQLite desde la versión que está funcionando.
2. Abre esta carpeta como proyecto Gradle en IntelliJ IDEA (JDK 21); ejecuta `gradle test` y `gradle run` (o `./gradlew.bat test` y `./gradlew.bat run` si dispones del wrapper).
3. Ingresa con el administrador preexistente. Comprueba que ventas, stock, proveedores, compras y solicitudes sigan intactos.
4. En Reportes, selecciona `Desde`/`Hasta` cubriendo la fecha de tus ventas y pulsa **Exportar Excel completo (.xlsx)**.
5. Abre el archivo con Excel. Comprueba que existan **nueve hojas** y que los importes puedan sumarse numéricamente (`=SUM(...)`).
6. En las pestañas **Ventas** y **Pagos**, verifica que una venta de S/ 36 cobrada con Yape y efectivo se cuente solo una vez como venta, pero con dos filas de cobro por medios de pago.
7. Revisa las pestañas **Inventario**, **Rotación** y **Solicitudes**. Si el catálogo está incompleto por el inventariado progresivo, las cifras solo reflejarán los artículos registrados.
8. Exporta también un CSV individual para comprobar que la función anterior sigue disponible.
9. Para una prueba aislada, registra un nombre como `=SUM(1,2)` y comprueba que Excel lo trata como texto y **no lo ejecuta como fórmula**.

## Verificaciones en entorno de desarrollo

- El escritor XLSX se compiló con `javac` de Java 21.
- Se generó un libro de prueba y se validaron los XML y la estructura del ZIP de Excel. Un lector de libros XLSX pudo leer sus hojas y celdas.
- Se verificaron las ocho consultas SQL escritas como bloques de texto contra el esquema real SQLite V001–V006, sin errores de sintaxis ni columnas faltantes.
- Se comprobó la compilación aislada de los servicios Java nuevos mediante tipos sustitutos (no es una prueba de integración real).
- Se añadieron pruebas JUnit (`FullExcelReportIntegrationTest`) para ejecutar contra JDBC y Flyway reales cuando Gradle esté disponible.
- La interfaz JavaFX y el flujo real con base de datos local **quedan pendientes de validación con Gradle en Windows**.
