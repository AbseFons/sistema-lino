# Incremento v0.3 — Edición, precios y kardex

## Alcance

Implementado sobre la v0.2, manteniendo **exactamente las migraciones V001–V003**.
No se agregan tablas ni se recrean usuarios.

### 1. Edición del producto

Desde **Productos → seleccionar → Editar producto**, el administrador puede modificar
nombre, categoría, marca, origen, modelo, observaciones, stock mínimo y máximo.
No se edita el SKU, la unidad base o el stock actual: este último se calcula del kardex.

### 2. Precios con historial

Desde **Productos → Precios**, seleccionar presentación y lista: Público, Mecánico o Mayorista.
Ingresar el nuevo precio en soles (máximo dos decimales) y un motivo de al menos cinco caracteres.
Si existe un precio vigente, se cierra su vigencia y se genera otro registro sin borrar el anterior.
Una presentación activa puede tener distintos precios vigentes en distintas listas.

### 3. Ajustes de inventario

Desde **Productos → Ajustar stock** elegir Entrada (+) o Salida (-), cantidad y motivo.
El usuario autenticado debe ser administrador activo, el producto debe estar activo,
el motivo debe tener al menos ocho caracteres, y el ajuste negativo no puede superar el saldo.
Se inserta el movimiento en `movimiento_stock`, el trigger actualiza `saldo_stock`, y se audita en una única transacción.
El historial (Kardex) muestra movimientos y saldo acumulado.

### 4. Estado del producto

**Activar / Desactivar** conserva stock, presentaciones, precios y movimientos históricos.
El SKU no se elimina ni se reutiliza por desactivar.

## Actualización segura de v0.2

1. Cerrar la aplicación v0.2.
2. Guardar una **copia** del archivo `%LOCALAPPDATA%\InventarioRepuestos\inventario.sqlite` fuera de esa carpeta, con la aplicación cerrada.
3. Descomprimir v0.3 en una carpeta nueva. Abrir el nuevo `build.gradle` con IntelliJ.
4. Seleccionar JDK 21, sincronizar dependencias y ejecutar la tarea `run`.
5. Debe aparecer el login anterior. Si pide crear administrador, cancelar: probablemente está apuntando a otro archivo SQLite.
6. Iniciar sesión y comprobar que el producto y la marca de las pruebas v0.2 continúan presentes.

El respaldo se debe hacer antes de probar ajustes, pues los movimientos del kardex son permanentes.
Se recomienda usar **datos de demostración** para estas pruebas, no mercadería del negocio.

## Prueba manual sugerida

- Producto creado en v0.2: stock 12 piezas, precio S/ 25.
- Editar nombre y cambiar mínimo a 5 y máximo a 30: comprobar que stock sigue 12.
- Cambiar precio público a S/ 27.50 con motivo «Aumento del proveedor»: debe verse precio previo e historial.
- Registrar ajuste **Entrada (+) 2**, motivo «Inventario físico encontrado»: stock 14.
- Registrar ajuste **Salida (-) 3**, motivo «Tres piezas deterioradas»: stock 11.
- Intentar salida de 12: debe rechazarse y conservar stock 11.
- Abrir Historial: deben observarse el movimiento inicial, los ajustes y los precios anterior y actual.
- Acceder con VENDEDOR: los botones de edición, precios y ajuste deben estar deshabilitados.

## Verificación automatizada

- `python tests-sql/probar_esquema.py` — 20 pruebas base.
- `python tests-sql/probar_mantenimiento_v03.py` — 8 pruebas de contratos de SQLite.
- `gradle test` — pruebas Java (requiere Gradle y las dependencias).

En el entorno de generación se verificó el **parsing de los 25 archivos Java del módulo principal** y
la compilación del servicio nuevo con JDK 21, pero no está disponible el entorno JavaFX/Gradle para confirmar compilación y GUI completas.

## Fuera del alcance

No se emiten boletas ni facturas, ni se imprimen comprobantes, ni se gestionan todavía
compras, devoluciones, pedidos o ventas. Siguiente módulo: proveedores y compras.
