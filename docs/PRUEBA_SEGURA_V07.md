# Prueba segura de la v0.7 (Windows / PowerShell)

## 1. Conservar la v0.6 y sus datos

1. Cierra v0.6.
2. Copia `%LOCALAPPDATA%\InventarioRepuestos\inventario.sqlite` en una memoria externa, después de cerrar por completo la app.
3. Extrae el ZIP de v0.7 **en una nueva carpeta**. No mezcles `src`, `build` ni carpetas Gradle con una versión vieja.
4. Abre `build.gradle` en IntelliJ. JDK 21. Ejecuta primero `test` y luego `run`.
5. Debes poder entrar con el mismo administrador y consultar ventas e inventario. **No crees otro administrador en la base de producción.**

## 2. Validar la restauración sin arriesgar datos reales

En una terminal PowerShell distinta, define una ruta SQLite vacía de prueba y ejecuta la app allí:

```powershell
$env:INVENTARIO_DB_PATH = "$env:TEMP\InventarioRepuestosPrueba\inventario.sqlite"
.\gradlew.bat run
```

Si no tienes `gradlew.bat` en la carpeta extraída, usa la tarea **run** de IntelliJ configurando `INVENTARIO_DB_PATH` o el comando `gradle run` si lo tienes instalado.

1. Crea un administrador de **pruebas**.
2. Crea un producto y agrega stock inicial. Verifica el stock.
3. Abre **Respaldos** > **Crear copia ahora** y guarda `copia_prueba.sqlite` fuera de la carpeta de la base.
4. Registra un cambio **de prueba** (producto/solicitud) que NO esté en el respaldo.
5. En **Respaldos**, selecciona `copia_prueba.sqlite` para restaurar; confirma dos veces, escribiendo `RESTAURAR`.
6. La aplicación se cerrará. Vuelve a ejecutarla **con la misma variable `INVENTARIO_DB_PATH`**.
7. Verifica que la prueba posterior a la copia desapareció, pero el administrador y producto originales permanecen.
8. Comprueba las copias `antes_de_restaurar_*.sqlite` y `despues_de_restaurar_*.sqlite`.
9. Verifica que una copia inválida o texto renombrado a `.sqlite` es rechazada sin modificar la base.
10. Cierra la aplicación y elimina la variable de entorno de esa terminal:

```powershell
Remove-Item Env:INVENTARIO_DB_PATH
```

**No uses la restauración con el SQLite real del negocio hasta haber probado el ciclo en una base aislada.**

## 3. Validar exportaciones

1. Inicia sesión como administrador.
2. Abre **Reportes**, selecciona un período con ventas y el tipo **Cobros por medio de pago**.
3. Pulsa **Exportar CSV**. Abre el archivo con Excel usando `;` como delimitador.
4. Confirma que Yape y efectivo aparecen como filas distintas y que la suma coincide con los cobros registrados.
5. Prueba también **Ventas por día**, **Productos más vendidos** y **Solicitudes pendientes**.
6. Confirma que en la sesión de vendedor el botón **Exportar CSV** esté deshabilitado.

## 4. Restauración de emergencia (orientación)

No copies nunca una base respaldada sobre `inventario.sqlite` mientras la aplicación está abierta. Si la app no puede iniciar por corrupción, conserva el original y sus posibles archivos `-wal`/`-shm`, llama a soporte y utiliza un respaldo SQLite previamente validado. No borres los archivos originales para intentar repararlos a ciegas.
