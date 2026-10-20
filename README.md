# Inventario Repuestos — v0.7.1 (JavaFX / Java 21 / SQLite)

**Incremento v0.7.1: exportación integral a Excel (.xlsx) de nueve hojas, conservando CSV, respaldos y funciones existentes.**
Las versiones v0.1–v0.6 fueron validadas por el usuario en Windows. **La v0.7.1 requiere validar la compilación JavaFX y la exportación real en tu PC**; si la v0.7 funciona en tu entorno, conserva ese respaldo.

> **MODO PILOTO / PRUEBAS.** Todavía NO se emiten comprobantes electrónicos tributarios válidos ante SUNAT. Los CPE continúan en estado pendiente.

## Novedad v0.7.1: Excel con 9 hojas

- **Reportes > Exportar Excel completo (.xlsx)**: un libro con **Resumen, Ventas, Detalle de ventas, Productos vendidos, Pagos, Inventario, Rotación, Solicitudes y Ventas diarias**.
- La exportación **CSV individual sigue disponible** y no se pierde ningún módulo de la v0.7.
- Cifras y cantidades en celdas **numéricas de Excel**, con filtros y encabezados formateados; no son cadenas CSV dentro de un XLSX.
- Exportación sin añadir bibliotecas externas; el escritor usa Java 21. El libro contiene datos comerciales sensibles: guárdalo de manera segura.
- **Cero cambios al esquema:** migraciones V001–V006 y ubicación de la base sin modificar. Ver `docs/CAMBIOS_V071.md`.

## Funcionalidades heredadas de la v0.7

- **Menú administrador > Respaldos:** crear copia manual `.sqlite` en ubicación elegida.
- **Automático:** respaldo diario consistente al abrir la app, más comprobación horaria mientras la aplicación permanece abierta. Se guardan **30 copias diarias recientes** en `%LOCALAPPDATA%\InventarioRepuestos\respaldos`.
- **Restauración controlada:** seleccionar copia verificable, confirmar y escribir `RESTAURAR`. La app se cierra y aplica la recuperación **en el próximo arranque**, con copia del estado anterior.
- **Bloqueo de instancia:** impide abrir dos copias de la aplicación usando el mismo archivo de base de datos.
- **Reportes > Exportar CSV:** resumen comercial, ventas diarias, productos más vendidos, cobros por método, inventario sin venta reciente, ranking de solicitudes.
- **Excel español:** CSV UTF-8 con BOM, separador `;`, números decimales con `,`, protección de inyección de fórmulas desde campos de texto.
- **Datos protegidos:** esquema y migraciones **V001–V006 sin cambios**, ubicación de base SQLite igual a la v0.6.

## Instalar / actualizar sin perder usuarios ni productos

1. **Cierra completamente la v0.7 (o la versión actual).** Haz una copia de `%LOCALAPPDATA%\InventarioRepuestos\inventario.sqlite` fuera del directorio de la aplicación (si hubiese `-wal` o `-shm`, conserva también esos archivos con la aplicación cerrada).
2. Descomprime v0.7.1 en **otra carpeta**. No reemplaces la carpeta v0.6 ni copies el ZIP sobre su raíz.
3. En **IntelliJ IDEA**, abre `build.gradle` y utiliza JDK 21.
4. Sincroniza Gradle; ejecuta las tareas `test` y después `run`.
5. Inicia sesión con **la misma cuenta de administrador** y revisa datos anteriores, proveedores, inventario, compras, ventas y reportes.
6. Revisa **Respaldos** y crea una copia manual en otra unidad. **Prueba la restauración solo con una base aislada** siguiendo `docs/PRUEBA_SEGURA_V07.md`.

Si tu configuración local ya tiene un `gradlew.bat`, puedes utilizarlo desde la carpeta en la que esté instalado. El ZIP de este incremento **no incorpora Gradle Wrapper**; si ya configuraste el wrapper en tu equipo, consérvalo, sin copiar bases de datos dentro del proyecto.

## Ejecutar

```powershell
gradle test
gradle run
```

O ejecutar en IntelliJ desde el panel Gradle. El directorio real de la base **no cambia al cambiar de ZIP**.

## Validaciones

- Las migraciones existentes V001–V006 se mantienen idénticas.
- Se ejecutaron todas las pruebas SQL anteriores y **4 pruebas adicionales de SQLite VACUUM INTO**.
- Compilación con `javac --release 21` de `BackupService`, `InstanceLock`, `ReportExportService`, `ReportsService`, `DemandService` y los modelos de dominio (con DB sustituta únicamente para verificar sintaxis sin dependencias externas).
- Se añadieron **6 pruebas de integración Java/JUnit** en `BackupAndExportIntegrationTest` que deben ejecutarse con `gradle test` en tu PC.
- **Pendiente:** compilación de JavaFX completo, pruebas Gradle/JUnit y validación manual en Windows con tu instancia.

## Advertencias importantes

- Los respaldos son **copias completas**, no archivos CSV. Una restauración sustituye toda la base, incluidos clientes, contraseñas y movimientos creados después de la copia.
- Las copias automáticas están en **el mismo equipo**: si falla el disco, pueden perderse. Guarda periódicamente una copia en otra unidad/carpeta externa segura.
- Los respaldos SQLite **no están cifrados**. Protege archivos con datos personales y comerciales; no los envíes por mensajería sin mecanismos de protección.
- Nunca copies el archivo `inventario.sqlite` manualmente mientras el programa está abierto: usa el botón de respaldo.
- Se requiere espacio en disco suficiente, permisos de escritura, y **una sola instancia** de la aplicación.
- Los reportes son **operativos**, no comprobantes SUNAT, balances tributarios ni cálculo de utilidad neta.

## Documentos adicionales

- `docs/CAMBIOS_V071.md`: cambio de exportación integral Excel y su prueba.
- `docs/CAMBIOS_V07.md`: respaldos y CSV de la versión anterior.
- `docs/PRUEBA_SEGURA_V07.md`: guía de prueba de restauración y exportación.
- `docs/PLAN_HITOS.md`: roadmap.

**Próxima fase propuesta:** definir proveedor/sistema de facturación SUNAT y validar modelo/tipo de impresora. La emisión real requiere pruebas oficiales y cumplimiento normativo; no está habilitada en v0.7.1.
