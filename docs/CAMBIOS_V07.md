# v0.7 — Seguridad de datos y exportación

## Alcance

- Respaldos SQLite consistentes (`VACUUM INTO`), aun con la app abierta: no copiar en caliente el archivo principal.
- Copia automática una vez por día calendario de Perú (al abrir y verificación horaria mientras está abierto), retención de 30 copias automáticas.
- Copia manual verificada a ubicación elegida por administrador (idealmente unidad externa).
- Restauración diferida con doble confirmación y validación SQLite previa. Al reiniciar, copia de seguridad pre-restauración, sustitución y comprobación, copia post-restauración.
- Bloqueo de instancia para evitar abrir dos aplicaciones sobre el mismo SQLite.
- Exportación CSV UTF-8 con BOM, separador ;, decimal coma y prevención de fórmulas en texto.
- Tipos de CSV: resumen, ventas diarias, productos más vendidos (máx. 100), medios de pago, sin rotación (máx. 500), solicitudes pendientes (ranking máx. 100).
- Respaldo y exportación restringidos a administrador.

## Base de datos

- V001–V006 **idénticas** a v0.6. No se requiere V007; no hubo cambios estructurales.
- Base SQLite persistente en `%LOCALAPPDATA%\InventarioRepuestos\inventario.sqlite`.
- Copias diarias: `%LOCALAPPDATA%\InventarioRepuestos\respaldos\automatico_YYYY-MM-DD.sqlite`.
- Staging de recuperación: `restauracion_pendiente.sqlite`, en carpeta de la base.
- Copias de recuperación: `antes_de_restaurar_*.sqlite`, `despues_de_restaurar_*.sqlite`.
- Las copias automáticas, por sí solas, NO protegen contra fallas o robo del disco.

## Limitaciones y precauciones

1. El sistema **no cifra** los respaldos; pueden contener datos personales (DNI/RUC). Guardar en soporte restringido y protegido.
2. La restauración reemplaza **todos** los datos de la base por el estado del archivo; no es una fusión.
3. La restauración debe practicarse en una base de prueba antes de aplicarse a la del negocio.
4. No se integra todavía SUNAT. El historial de ventas no equivale a boletas/facturas electrónicas emitidas.
5. La restauración de una base ya ilegible o que impide iniciar la aplicación puede necesitar asistencia técnica y recuperación manual del respaldo con el programa cerrado.
6. Los respaldos automáticos no garantizan que la PC esté encendida ni que el programa haya podido escribir en el disco; la alerta de inicio avisa si hay error.
7. Se requiere permiso de escritura en el directorio de copias y espacio libre suficiente. Los CSV pueden contener nombres de productos y datos comerciales sensibles.

## Verificación automatizada

- `python tests-sql/probar_respaldo_v07.py`: pruebas reales de la operación VACUUM INTO en SQLite.
- `gradle test`: incluye `BackupAndExportIntegrationTest` con JDBC SQLite y JUnit; ejecutar en el equipo con Gradle, JDK 21 y dependencias.
- Las pruebas SQL anteriores siguen disponibles y se ejecutaron en este entorno.
- Aquí **no se ejecutó JavaFX ni Gradle completo** por falta de dependencias disponibles; la v0.7 requiere validación Windows.
