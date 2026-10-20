# Verificación del paquete — 08/10/2026

- Pruebas físicas Python/SQLite originales de la etapa previa: **20/20 aprobadas** (tras ajustar la ruta de migraciones al nuevo proyecto).
- `PRAGMA integrity_check`: `ok`.
- Tablas del esquema aprobado: **25**; triggers: **46**; vistas: **4**.
- Compilación con `javac` (JDK 21) de clases sin dependencias externas: correcta.
- Ejecución de prueba Java de hashing y conversión de cantidades/precios: correcta.

## Pendiente de validación técnica

- **No** se ejecutó `gradle build`, `gradle test` ni la interfaz JavaFX. El entorno de generación no cuenta con Gradle ni con acceso a las dependencias de Maven/Gradle.
- Quedan pendientes de comprobar el parser SQLite de Flyway para los triggers y el funcionamiento del driver JDBC en PC real.

**Conclusión:** la estructura SQL y núcleo independiente de Java pasan sus pruebas, pero el hito H01 no se considera terminado hasta completar las pruebas Gradle y GUI en Windows.
