-- V001: SQLite | Inventario y facturacion | Modelo fisico v1.0
-- Fecha: 2026-10-08. Requiere SQLite >= 3.37 (tablas STRICT).
-- Para Flyway, PRAGMA foreign_keys=ON se activa por CONEXION.
-- Fechas TEXT en UTC ISO-8601 con milisegundos y sufijo Z.
-- Importes: *_centavos S/ x 100; *_micro S/ x 10000; cantidades *_milli x 1000.
-- Sin datos de negocio ni usuarios precargados. No editar una migracion aplicada.

CREATE TABLE rol (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(codigo)) > 0),
 nombre TEXT NOT NULL CHECK (length(trim(nombre)) > 0),
 activo INTEGER NOT NULL DEFAULT 1 CHECK (activo IN (0,1))
) STRICT;
CREATE TABLE usuario (
 id INTEGER PRIMARY KEY,
 rol_id INTEGER NOT NULL REFERENCES rol(id) ON DELETE RESTRICT,
 nombre TEXT NOT NULL CHECK(length(trim(nombre)) > 0),
 login TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(login)) > 0),
 password_hash TEXT NOT NULL CHECK(length(password_hash) >= 20),
 activo INTEGER NOT NULL DEFAULT 1 CHECK(activo IN(0,1)),
 creado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
) STRICT;
CREATE TABLE categoria (
 id INTEGER PRIMARY KEY,
 nombre TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(nombre)) > 0),
 activo INTEGER NOT NULL DEFAULT 1 CHECK(activo IN(0,1))
) STRICT;
CREATE TABLE marca (
 id INTEGER PRIMARY KEY,
 nombre TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(nombre)) > 0),
 activo INTEGER NOT NULL DEFAULT 1 CHECK(activo IN(0,1))
) STRICT;
CREATE TABLE unidad_medida (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(codigo)) > 0),
 nombre TEXT NOT NULL,
 decimales_permitidos INTEGER NOT NULL DEFAULT 0 CHECK(decimales_permitidos BETWEEN 0 AND 3)
) STRICT;
CREATE TABLE producto (
 id INTEGER PRIMARY KEY,
 sku TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(sku)) > 0),
 nombre TEXT NOT NULL CHECK(length(trim(nombre)) > 0),
 categoria_id INTEGER NOT NULL REFERENCES categoria(id) ON DELETE RESTRICT,
 marca_id INTEGER REFERENCES marca(id) ON DELETE RESTRICT,
 pais_origen TEXT,
 modelo_referencia TEXT,
 unidad_base_id INTEGER NOT NULL REFERENCES unidad_medida(id) ON DELETE RESTRICT,
 stock_min_milli INTEGER NOT NULL DEFAULT 0 CHECK(stock_min_milli >= 0),
 stock_max_milli INTEGER CHECK(stock_max_milli IS NULL OR stock_max_milli >= stock_min_milli),
 observaciones TEXT,
 estado TEXT NOT NULL DEFAULT 'PENDIENTE' CHECK(estado IN ('PENDIENTE','ACTIVO','INACTIVO')),
 creado_por INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
 creado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 actualizado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
) STRICT;
CREATE TABLE producto_compatibilidad (
 id INTEGER PRIMARY KEY,
 producto_id INTEGER NOT NULL REFERENCES producto(id) ON DELETE RESTRICT,
 descripcion TEXT NOT NULL CHECK(length(trim(descripcion)) > 0),
 activo INTEGER NOT NULL DEFAULT 1 CHECK(activo IN(0,1)),
 UNIQUE(producto_id, descripcion)
) STRICT;
CREATE TABLE producto_presentacion (
 id INTEGER PRIMARY KEY,
 producto_id INTEGER NOT NULL REFERENCES producto(id) ON DELETE RESTRICT,
 nombre TEXT NOT NULL CHECK(length(trim(nombre)) > 0),
 factor_base_milli INTEGER NOT NULL CHECK(factor_base_milli > 0),
 codigo_barras TEXT UNIQUE,
 se_compra INTEGER NOT NULL DEFAULT 1 CHECK(se_compra IN(0,1)),
 se_vende INTEGER NOT NULL DEFAULT 1 CHECK(se_vende IN(0,1)),
 activo INTEGER NOT NULL DEFAULT 1 CHECK(activo IN(0,1)),
 UNIQUE(producto_id, nombre)
) STRICT;
CREATE TABLE lista_precio (
 id INTEGER PRIMARY KEY,
 codigo TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(codigo)) > 0),
 nombre TEXT NOT NULL,
 activo INTEGER NOT NULL DEFAULT 1 CHECK(activo IN(0,1))
) STRICT;
CREATE TABLE precio_presentacion (
 id INTEGER PRIMARY KEY,
 presentacion_id INTEGER NOT NULL REFERENCES producto_presentacion(id) ON DELETE RESTRICT,
 lista_precio_id INTEGER NOT NULL REFERENCES lista_precio(id) ON DELETE RESTRICT,
 precio_micro INTEGER NOT NULL CHECK(precio_micro >= 0),
 vigente_desde TEXT NOT NULL,
 vigente_hasta TEXT CHECK(vigente_hasta IS NULL OR vigente_hasta > vigente_desde),
 registrado_por INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
 motivo TEXT
) STRICT;
CREATE TABLE saldo_stock (
 producto_id INTEGER PRIMARY KEY REFERENCES producto(id) ON DELETE RESTRICT,
 cantidad_milli INTEGER NOT NULL DEFAULT 0 CHECK(cantidad_milli >= 0),
 actualizado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
) STRICT;
CREATE TABLE proveedor (
 id INTEGER PRIMARY KEY,
 ruc TEXT UNIQUE CHECK(ruc IS NULL OR (length(ruc)=11 AND ruc NOT GLOB '*[^0-9]*')),
 razon_social TEXT NOT NULL CHECK(length(trim(razon_social)) > 0),
 telefono TEXT,
 activo INTEGER NOT NULL DEFAULT 1 CHECK(activo IN(0,1))
) STRICT;
CREATE TABLE compra (
 id INTEGER PRIMARY KEY,
 proveedor_id INTEGER REFERENCES proveedor(id) ON DELETE RESTRICT,
 numero_documento TEXT,
 fecha_compra TEXT NOT NULL,
 estado TEXT NOT NULL DEFAULT 'BORRADOR' CHECK(estado IN ('BORRADOR','CONFIRMADA')),
 total_centavos INTEGER NOT NULL DEFAULT 0 CHECK(total_centavos >= 0),
 usuario_id INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
 confirmada_en TEXT
) STRICT;
CREATE TABLE compra_detalle (
 id INTEGER PRIMARY KEY,
 compra_id INTEGER NOT NULL REFERENCES compra(id) ON DELETE RESTRICT,
 presentacion_id INTEGER NOT NULL REFERENCES producto_presentacion(id) ON DELETE RESTRICT,
 cantidad_presentacion_milli INTEGER NOT NULL CHECK(cantidad_presentacion_milli > 0),
 factor_base_milli_snapshot INTEGER NOT NULL CHECK(factor_base_milli_snapshot > 0),
 cantidad_base_milli INTEGER NOT NULL CHECK(cantidad_base_milli > 0),
 costo_unitario_micro INTEGER NOT NULL CHECK(costo_unitario_micro >= 0),
 importe_centavos INTEGER NOT NULL CHECK(importe_centavos >= 0),
 CHECK(cantidad_presentacion_milli * factor_base_milli_snapshot % 1000 = 0),
 CHECK(cantidad_base_milli = cantidad_presentacion_milli * factor_base_milli_snapshot / 1000)
) STRICT;
CREATE TABLE cliente (
 id INTEGER PRIMARY KEY,
 tipo_persona TEXT NOT NULL CHECK(tipo_persona IN ('NATURAL','JURIDICA')),
 tipo_documento TEXT CHECK(tipo_documento IN ('DNI','RUC','OTRO')),
 numero_documento TEXT,
 nombre_razon_social TEXT NOT NULL CHECK(length(trim(nombre_razon_social)) > 0),
 direccion TEXT,
 telefono TEXT,
 lista_precio_id INTEGER REFERENCES lista_precio(id) ON DELETE RESTRICT,
 activo INTEGER NOT NULL DEFAULT 1 CHECK(activo IN(0,1)),
 CHECK((tipo_documento IS NULL AND numero_documento IS NULL) OR
       (tipo_documento IS NOT NULL AND numero_documento IS NOT NULL AND length(trim(numero_documento))>0)),
 CHECK(tipo_documento IS NOT 'DNI' OR (length(numero_documento)=8 AND numero_documento NOT GLOB '*[^0-9]*')),
 CHECK(tipo_documento IS NOT 'RUC' OR (length(numero_documento)=11 AND numero_documento NOT GLOB '*[^0-9]*')),
 CHECK(tipo_documento IS NOT 'RUC' OR tipo_persona IN ('NATURAL','JURIDICA')),
 UNIQUE(tipo_documento,numero_documento)
) STRICT;
CREATE TABLE venta (
 id INTEGER PRIMARY KEY,
 operacion_uuid TEXT NOT NULL UNIQUE CHECK(length(trim(operacion_uuid))>0),
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 vendedor_id INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
 cliente_id INTEGER REFERENCES cliente(id) ON DELETE RESTRICT,
 cliente_nombre_snapshot TEXT,
 cliente_tipo_documento_snapshot TEXT CHECK(cliente_tipo_documento_snapshot IS NULL OR cliente_tipo_documento_snapshot IN ('DNI','RUC','OTRO')),
 cliente_documento_snapshot TEXT,
 lista_precio_id INTEGER REFERENCES lista_precio(id) ON DELETE RESTRICT,
 estado TEXT NOT NULL DEFAULT 'BORRADOR' CHECK(estado IN ('BORRADOR','CONFIRMADA')),
 subtotal_centavos INTEGER NOT NULL DEFAULT 0 CHECK(subtotal_centavos >= 0),
 descuento_centavos INTEGER NOT NULL DEFAULT 0 CHECK(descuento_centavos >= 0),
 impuesto_centavos INTEGER NOT NULL DEFAULT 0 CHECK(impuesto_centavos >= 0),
 total_centavos INTEGER NOT NULL DEFAULT 0 CHECK(total_centavos >= 0),
 confirmada_en TEXT,
 CHECK(descuento_centavos <= subtotal_centavos),
 CHECK(total_centavos = subtotal_centavos - descuento_centavos + impuesto_centavos),
 CHECK((cliente_tipo_documento_snapshot IS NULL AND cliente_documento_snapshot IS NULL) OR
       (cliente_tipo_documento_snapshot IS NOT NULL AND cliente_documento_snapshot IS NOT NULL)),
 CHECK(cliente_tipo_documento_snapshot IS NOT 'DNI' OR (length(cliente_documento_snapshot)=8 AND cliente_documento_snapshot NOT GLOB '*[^0-9]*')),
 CHECK(cliente_tipo_documento_snapshot IS NOT 'RUC' OR (length(cliente_documento_snapshot)=11 AND cliente_documento_snapshot NOT GLOB '*[^0-9]*'))
) STRICT;
CREATE TABLE venta_detalle (
 id INTEGER PRIMARY KEY,
 venta_id INTEGER NOT NULL REFERENCES venta(id) ON DELETE RESTRICT,
 presentacion_id INTEGER NOT NULL REFERENCES producto_presentacion(id) ON DELETE RESTRICT,
 sku_snapshot TEXT NOT NULL CHECK(length(trim(sku_snapshot))>0),
 descripcion_snapshot TEXT NOT NULL CHECK(length(trim(descripcion_snapshot))>0),
 cantidad_presentacion_milli INTEGER NOT NULL CHECK(cantidad_presentacion_milli > 0),
 factor_base_milli_snapshot INTEGER NOT NULL CHECK(factor_base_milli_snapshot > 0),
 cantidad_base_milli INTEGER NOT NULL CHECK(cantidad_base_milli > 0),
 precio_lista_micro INTEGER NOT NULL CHECK(precio_lista_micro >= 0),
 precio_final_micro INTEGER NOT NULL CHECK(precio_final_micro >= 0),
 descuento_centavos INTEGER NOT NULL DEFAULT 0 CHECK(descuento_centavos >= 0),
 impuesto_centavos INTEGER NOT NULL DEFAULT 0 CHECK(impuesto_centavos >= 0),
 total_centavos INTEGER NOT NULL CHECK(total_centavos >= 0),
 costo_base_micro_snapshot INTEGER CHECK(costo_base_micro_snapshot IS NULL OR costo_base_micro_snapshot >= 0),
 CHECK(precio_final_micro <= precio_lista_micro),
 CHECK(cantidad_presentacion_milli * factor_base_milli_snapshot % 1000 = 0),
 CHECK(cantidad_base_milli = cantidad_presentacion_milli * factor_base_milli_snapshot / 1000)
) STRICT;
CREATE TABLE venta_pago (
 id INTEGER PRIMARY KEY,
 venta_id INTEGER NOT NULL REFERENCES venta(id) ON DELETE RESTRICT,
 medio TEXT NOT NULL CHECK(medio IN ('EFECTIVO','YAPE','PLIN','TARJETA','TRANSFERENCIA','OTRO')),
 monto_centavos INTEGER NOT NULL CHECK(monto_centavos > 0),
 referencia TEXT,
 registrado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
) STRICT;
CREATE TABLE serie_comprobante (
 id INTEGER PRIMARY KEY,
 tipo TEXT NOT NULL CHECK(tipo IN ('BOLETA','FACTURA')),
 serie TEXT NOT NULL CHECK(length(trim(serie))>0),
 ultimo_numero INTEGER NOT NULL DEFAULT 0 CHECK(ultimo_numero >= 0),
 activa INTEGER NOT NULL DEFAULT 1 CHECK(activa IN(0,1)),
 UNIQUE(tipo,serie)
) STRICT;
CREATE TABLE comprobante (
 id INTEGER PRIMARY KEY,
 venta_id INTEGER NOT NULL UNIQUE REFERENCES venta(id) ON DELETE RESTRICT,
 tipo TEXT NOT NULL CHECK(tipo IN ('BOLETA','FACTURA')),
 serie_id INTEGER REFERENCES serie_comprobante(id) ON DELETE RESTRICT,
 numero INTEGER CHECK(numero IS NULL OR numero > 0),
 estado TEXT NOT NULL DEFAULT 'PENDIENTE' CHECK(estado IN ('PENDIENTE','ENVIANDO','ACEPTADO','ACEPTADO_CON_OBSERVACION','RECHAZADO','ERROR_ENVIO','ANULADO')),
 idempotency_key TEXT NOT NULL UNIQUE CHECK(length(trim(idempotency_key))>0),
 proveedor_id_externo TEXT,
 xml_ruta TEXT,
 cdr_ruta TEXT,
 pdf_ruta TEXT,
 emitido_en TEXT,
 actualizado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 CHECK((serie_id IS NULL AND numero IS NULL) OR (serie_id IS NOT NULL AND numero IS NOT NULL)),
 UNIQUE(serie_id,numero)
) STRICT;
CREATE TABLE comprobante_evento (
 id INTEGER PRIMARY KEY,
 comprobante_id INTEGER NOT NULL REFERENCES comprobante(id) ON DELETE RESTRICT,
 numero_intento INTEGER NOT NULL CHECK(numero_intento > 0),
 accion TEXT NOT NULL CHECK(accion IN ('GENERAR','ENVIAR','CONSULTAR','REINTENTAR','ANULAR','IMPRIMIR')),
 estado_resultante TEXT NOT NULL CHECK(estado_resultante IN ('PENDIENTE','ENVIANDO','ACEPTADO','ACEPTADO_CON_OBSERVACION','RECHAZADO','ERROR_ENVIO','ANULADO')),
 codigo_respuesta TEXT,
 mensaje_resumen TEXT,
 registrado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 UNIQUE(comprobante_id,numero_intento)
) STRICT;
CREATE TABLE movimiento_stock (
 id INTEGER PRIMARY KEY,
 producto_id INTEGER NOT NULL REFERENCES producto(id) ON DELETE RESTRICT,
 tipo TEXT NOT NULL CHECK(tipo IN ('INICIAL','COMPRA','VENTA','AJUSTE_POSITIVO','AJUSTE_NEGATIVO')),
 cantidad_delta_milli INTEGER NOT NULL CHECK(cantidad_delta_milli != 0),
 compra_detalle_id INTEGER UNIQUE REFERENCES compra_detalle(id) ON DELETE RESTRICT,
 venta_detalle_id INTEGER UNIQUE REFERENCES venta_detalle(id) ON DELETE RESTRICT,
 motivo TEXT,
 usuario_id INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
 autorizado_por_id INTEGER REFERENCES usuario(id) ON DELETE RESTRICT,
 operacion_uuid TEXT NOT NULL UNIQUE CHECK(length(trim(operacion_uuid)) > 0),
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 CHECK(
    (tipo='INICIAL' AND cantidad_delta_milli>0 AND compra_detalle_id IS NULL AND venta_detalle_id IS NULL AND autorizado_por_id IS NOT NULL)
 OR (tipo='COMPRA' AND cantidad_delta_milli>0 AND compra_detalle_id IS NOT NULL AND venta_detalle_id IS NULL AND autorizado_por_id IS NULL)
 OR (tipo='VENTA' AND cantidad_delta_milli<0 AND venta_detalle_id IS NOT NULL AND compra_detalle_id IS NULL AND autorizado_por_id IS NULL)
 OR (tipo='AJUSTE_POSITIVO' AND cantidad_delta_milli>0 AND compra_detalle_id IS NULL AND venta_detalle_id IS NULL AND autorizado_por_id IS NOT NULL AND length(trim(coalesce(motivo,'')))>0)
 OR (tipo='AJUSTE_NEGATIVO' AND cantidad_delta_milli<0 AND compra_detalle_id IS NULL AND venta_detalle_id IS NULL AND autorizado_por_id IS NOT NULL AND length(trim(coalesce(motivo,'')))>0)
 )
) STRICT;
CREATE TABLE demanda_no_atendida (
 id INTEGER PRIMARY KEY,
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 descripcion TEXT NOT NULL CHECK(length(trim(descripcion)) > 0),
 marca_modelo TEXT,
 cantidad_milli INTEGER CHECK(cantidad_milli IS NULL OR cantidad_milli > 0),
 producto_id INTEGER REFERENCES producto(id) ON DELETE RESTRICT,
 cliente_id INTEGER REFERENCES cliente(id) ON DELETE RESTRICT,
 usuario_id INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
 estado TEXT NOT NULL DEFAULT 'PENDIENTE' CHECK(estado IN ('PENDIENTE','ATENDIDA','DESCARTADA')),
 observaciones TEXT
) STRICT;
CREATE TABLE auditoria (
 id INTEGER PRIMARY KEY,
 fecha TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 usuario_id INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
 entidad TEXT NOT NULL CHECK(length(trim(entidad))>0),
 entidad_id INTEGER NOT NULL,
 accion TEXT NOT NULL CHECK(accion IN ('CREAR','ACTUALIZAR','DESACTIVAR','CONFIRMAR','ANULAR','AJUSTAR')),
 antes_json TEXT CHECK(antes_json IS NULL OR json_valid(antes_json)),
 despues_json TEXT CHECK(despues_json IS NULL OR json_valid(despues_json)),
 motivo TEXT
) STRICT;
CREATE TABLE configuracion (
 clave TEXT PRIMARY KEY NOT NULL CHECK(length(trim(clave))>0),
 valor TEXT NOT NULL,
 actualizado_por INTEGER REFERENCES usuario(id) ON DELETE RESTRICT,
 actualizado_en TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
) STRICT;

-- Indices auxiliares (SQLite ya crea indices para PRIMARY KEY y UNIQUE).
CREATE INDEX idx_producto_nombre ON producto(nombre COLLATE NOCASE);
CREATE INDEX idx_producto_cat_marca ON producto(categoria_id,marca_id);
CREATE INDEX idx_compatibilidad_descripcion ON producto_compatibilidad(descripcion COLLATE NOCASE);
CREATE INDEX idx_presentacion_producto ON producto_presentacion(producto_id);
CREATE UNIQUE INDEX ux_precio_actual ON precio_presentacion(presentacion_id,lista_precio_id) WHERE vigente_hasta IS NULL;
CREATE INDEX idx_precio_historico ON precio_presentacion(presentacion_id,lista_precio_id,vigente_desde DESC);
CREATE INDEX idx_movimiento_producto_fecha ON movimiento_stock(producto_id,fecha DESC);
CREATE INDEX idx_compra_fecha ON compra(fecha_compra DESC);
CREATE INDEX idx_compra_detalle_presentacion ON compra_detalle(presentacion_id);
CREATE INDEX idx_venta_fecha ON venta(fecha DESC);
CREATE INDEX idx_venta_vendedor_fecha ON venta(vendedor_id,fecha DESC);
CREATE INDEX idx_venta_detalle_presentacion ON venta_detalle(presentacion_id);
CREATE INDEX idx_pago_venta ON venta_pago(venta_id);
CREATE INDEX idx_comprobante_estado ON comprobante(estado);
CREATE INDEX idx_comprobante_evento_fecha ON comprobante_evento(comprobante_id,registrado_en DESC);
CREATE INDEX idx_demanda_pendientes ON demanda_no_atendida(estado,fecha DESC);
CREATE INDEX idx_auditoria_entidad ON auditoria(entidad,entidad_id,fecha DESC);
CREATE INDEX idx_stock_tipo_fecha ON movimiento_stock(tipo,fecha DESC);

-- Historial de precios sin intervalos superpuestos.
CREATE TRIGGER trg_precio_no_solapa_ins BEFORE INSERT ON precio_presentacion
BEGIN
 SELECT CASE WHEN EXISTS (
 SELECT 1 FROM precio_presentacion p
 WHERE p.presentacion_id=NEW.presentacion_id AND p.lista_precio_id=NEW.lista_precio_id
 AND NEW.vigente_desde < coalesce(p.vigente_hasta,'9999-12-31T23:59:59.999Z')
 AND p.vigente_desde < coalesce(NEW.vigente_hasta,'9999-12-31T23:59:59.999Z')
 ) THEN RAISE(ABORT,'PRECIO_INTERVALO_SOLAPADO') END;
END;
CREATE TRIGGER trg_precio_solo_cerrar BEFORE UPDATE ON precio_presentacion
BEGIN
 SELECT CASE WHEN OLD.vigente_hasta IS NOT NULL
   OR NEW.id != OLD.id OR NEW.presentacion_id != OLD.presentacion_id
   OR NEW.lista_precio_id != OLD.lista_precio_id OR NEW.precio_micro != OLD.precio_micro
   OR NEW.vigente_desde != OLD.vigente_desde OR NEW.registrado_por != OLD.registrado_por
   OR NEW.motivo IS NOT OLD.motivo OR NEW.vigente_hasta IS NULL
   THEN RAISE(ABORT,'PRECIO_HISTORICO_INMUTABLE') END;
END;
CREATE TRIGGER trg_precio_no_borrar BEFORE DELETE ON precio_presentacion
BEGIN SELECT RAISE(ABORT,'PRECIO_HISTORICO_NO_BORRAR'); END;

-- Saldo: alta automatica y proteccion contra ediciones que no reflejen el libro.
CREATE TRIGGER trg_producto_crea_saldo AFTER INSERT ON producto
BEGIN INSERT INTO saldo_stock(producto_id,cantidad_milli) VALUES(NEW.id,0); END;
CREATE TRIGGER trg_saldo_solo_ledger BEFORE UPDATE OF cantidad_milli ON saldo_stock
WHEN NEW.cantidad_milli != (SELECT COALESCE(SUM(cantidad_delta_milli),0) FROM movimiento_stock WHERE producto_id=OLD.producto_id)
BEGIN SELECT RAISE(ABORT,'SALDO_NO_COINCIDE_CON_KARDEX'); END;
CREATE TRIGGER trg_saldo_no_borrar BEFORE DELETE ON saldo_stock
BEGIN SELECT RAISE(ABORT,'SALDO_NO_BORRAR'); END;
CREATE TRIGGER trg_saldo_no_editar_pk BEFORE UPDATE OF producto_id ON saldo_stock
BEGIN SELECT RAISE(ABORT,'SALDO_PK_INMUTABLE'); END;

-- Transiciones cerradas para la V1: las rectificaciones fiscales se modelaran despues.
CREATE TRIGGER trg_compra_insert_borrador BEFORE INSERT ON compra WHEN NEW.estado!='BORRADOR'
BEGIN SELECT RAISE(ABORT,'COMPRA_DEBE_INICIAR_BORRADOR'); END;
CREATE TRIGGER trg_compra_confirmada_inmutable BEFORE UPDATE ON compra WHEN OLD.estado='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'COMPRA_CONFIRMADA_INMUTABLE'); END;
CREATE TRIGGER trg_compra_transicion BEFORE UPDATE OF estado ON compra
WHEN NEW.estado!=OLD.estado AND (OLD.estado!='BORRADOR' OR NEW.estado!='CONFIRMADA')
BEGIN SELECT RAISE(ABORT,'TRANSICION_COMPRA_INVALIDA'); END;
CREATE TRIGGER trg_compra_detalle_no_insertar_confirmada BEFORE INSERT ON compra_detalle
WHEN (SELECT estado FROM compra WHERE id=NEW.compra_id)!='BORRADOR'
BEGIN SELECT RAISE(ABORT,'DETALLE_COMPRA_CERRADA'); END;
CREATE TRIGGER trg_compra_detalle_no_modificar BEFORE UPDATE ON compra_detalle
WHEN (SELECT estado FROM compra WHERE id=OLD.compra_id)='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'DETALLE_COMPRA_HISTORICA'); END;
CREATE TRIGGER trg_compra_detalle_no_eliminar BEFORE DELETE ON compra_detalle
WHEN (SELECT estado FROM compra WHERE id=OLD.compra_id)='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'DETALLE_COMPRA_HISTORICA'); END;
CREATE TRIGGER trg_compra_validar_confirmacion BEFORE UPDATE OF estado ON compra
WHEN OLD.estado='BORRADOR' AND NEW.estado='CONFIRMADA'
BEGIN
 SELECT CASE WHEN (SELECT COUNT(*) FROM compra_detalle WHERE compra_id=NEW.id)=0
 THEN RAISE(ABORT,'COMPRA_SIN_DETALLE') END;
 SELECT CASE WHEN NEW.total_centavos != (SELECT COALESCE(SUM(importe_centavos),0) FROM compra_detalle WHERE compra_id=NEW.id)
 THEN RAISE(ABORT,'COMPRA_TOTAL_INCONSISTENTE') END;
 SELECT CASE WHEN NEW.confirmada_en IS NULL THEN RAISE(ABORT,'COMPRA_SIN_FECHA_CONFIRMACION') END;
END;
CREATE TRIGGER trg_compra_aplicar_stock AFTER UPDATE OF estado ON compra
WHEN OLD.estado='BORRADOR' AND NEW.estado='CONFIRMADA'
BEGIN
 INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,compra_detalle_id,usuario_id,operacion_uuid)
 SELECT pp.producto_id,'COMPRA',cd.cantidad_base_milli,cd.id,NEW.usuario_id,'COMPRA:'||cd.id
 FROM compra_detalle cd JOIN producto_presentacion pp ON pp.id=cd.presentacion_id
 WHERE cd.compra_id=NEW.id;
END;

CREATE TRIGGER trg_venta_insert_borrador BEFORE INSERT ON venta WHEN NEW.estado!='BORRADOR'
BEGIN SELECT RAISE(ABORT,'VENTA_DEBE_INICIAR_BORRADOR'); END;
CREATE TRIGGER trg_venta_confirmada_inmutable BEFORE UPDATE ON venta WHEN OLD.estado='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'VENTA_CONFIRMADA_INMUTABLE'); END;
CREATE TRIGGER trg_venta_transicion BEFORE UPDATE OF estado ON venta
WHEN NEW.estado!=OLD.estado AND (OLD.estado!='BORRADOR' OR NEW.estado!='CONFIRMADA')
BEGIN SELECT RAISE(ABORT,'TRANSICION_VENTA_INVALIDA'); END;
CREATE TRIGGER trg_venta_detalle_no_insertar_confirmada BEFORE INSERT ON venta_detalle
WHEN (SELECT estado FROM venta WHERE id=NEW.venta_id)!='BORRADOR'
BEGIN SELECT RAISE(ABORT,'DETALLE_VENTA_CERRADA'); END;
CREATE TRIGGER trg_venta_detalle_no_modificar BEFORE UPDATE ON venta_detalle
WHEN (SELECT estado FROM venta WHERE id=OLD.venta_id)='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'DETALLE_VENTA_HISTORICA'); END;
CREATE TRIGGER trg_venta_detalle_no_eliminar BEFORE DELETE ON venta_detalle
WHEN (SELECT estado FROM venta WHERE id=OLD.venta_id)='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'DETALLE_VENTA_HISTORICA'); END;
CREATE TRIGGER trg_pago_no_insertar_confirmada BEFORE INSERT ON venta_pago
WHEN (SELECT estado FROM venta WHERE id=NEW.venta_id)!='BORRADOR'
BEGIN SELECT RAISE(ABORT,'PAGO_VENTA_CERRADA'); END;
CREATE TRIGGER trg_pago_no_editar_confirmada BEFORE UPDATE ON venta_pago
WHEN (SELECT estado FROM venta WHERE id=OLD.venta_id)='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'PAGO_VENTA_HISTORICA'); END;
CREATE TRIGGER trg_pago_no_borrar_confirmada BEFORE DELETE ON venta_pago
WHEN (SELECT estado FROM venta WHERE id=OLD.venta_id)='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'PAGO_VENTA_HISTORICA'); END;
CREATE TRIGGER trg_venta_validar_confirmacion BEFORE UPDATE OF estado ON venta
WHEN OLD.estado='BORRADOR' AND NEW.estado='CONFIRMADA'
BEGIN
 SELECT CASE WHEN (SELECT COUNT(*) FROM venta_detalle WHERE venta_id=NEW.id)=0
 THEN RAISE(ABORT,'VENTA_SIN_DETALLE') END;
 SELECT CASE WHEN NEW.subtotal_centavos-NEW.descuento_centavos+NEW.impuesto_centavos != NEW.total_centavos
 THEN RAISE(ABORT,'VENTA_COMPONENTES_NO_CUADRAN') END;
 SELECT CASE WHEN (SELECT COALESCE(SUM(descuento_centavos),0) FROM venta_detalle WHERE venta_id=NEW.id) != NEW.descuento_centavos
 THEN RAISE(ABORT,'VENTA_DESCUENTO_NO_CUADRA') END;
 SELECT CASE WHEN (SELECT COALESCE(SUM(impuesto_centavos),0) FROM venta_detalle WHERE venta_id=NEW.id) != NEW.impuesto_centavos
 THEN RAISE(ABORT,'VENTA_IMPUESTO_NO_CUADRA') END;
 SELECT CASE WHEN (SELECT COALESCE(SUM(total_centavos),0) FROM venta_detalle WHERE venta_id=NEW.id) != NEW.total_centavos
 THEN RAISE(ABORT,'VENTA_LINEAS_NO_CUADRAN') END;
 SELECT CASE WHEN (SELECT COALESCE(SUM(total_centavos+descuento_centavos-impuesto_centavos),0) FROM venta_detalle WHERE venta_id=NEW.id) != NEW.subtotal_centavos
 THEN RAISE(ABORT,'VENTA_SUBTOTAL_NO_CUADRA') END;
 SELECT CASE WHEN (SELECT COUNT(*) FROM venta_pago WHERE venta_id=NEW.id)=0
 THEN RAISE(ABORT,'VENTA_SIN_PAGO') END;
 SELECT CASE WHEN (SELECT COALESCE(SUM(monto_centavos),0) FROM venta_pago WHERE venta_id=NEW.id) != NEW.total_centavos
 THEN RAISE(ABORT,'VENTA_PAGOS_NO_CUADRAN') END;
 SELECT CASE WHEN (SELECT COUNT(*) FROM comprobante WHERE venta_id=NEW.id)!=1
 THEN RAISE(ABORT,'VENTA_SIN_COMPROBANTE') END;
 SELECT CASE WHEN EXISTS(SELECT 1 FROM comprobante WHERE venta_id=NEW.id AND tipo='FACTURA'
 AND (NEW.cliente_tipo_documento_snapshot IS NOT 'RUC' OR length(coalesce(NEW.cliente_nombre_snapshot,''))=0))
 THEN RAISE(ABORT,'FACTURA_REQUIERE_RUC_Y_RAZON_SOCIAL') END;
 SELECT CASE WHEN NEW.confirmada_en IS NULL THEN RAISE(ABORT,'VENTA_SIN_FECHA_CONFIRMACION') END;
END;
CREATE TRIGGER trg_venta_aplicar_stock AFTER UPDATE OF estado ON venta
WHEN OLD.estado='BORRADOR' AND NEW.estado='CONFIRMADA'
BEGIN
 INSERT INTO movimiento_stock(producto_id,tipo,cantidad_delta_milli,venta_detalle_id,usuario_id,operacion_uuid)
 SELECT pp.producto_id,'VENTA',-vd.cantidad_base_milli,vd.id,NEW.vendedor_id,'VENTA:'||vd.id
 FROM venta_detalle vd JOIN producto_presentacion pp ON pp.id=vd.presentacion_id
 WHERE vd.venta_id=NEW.id;
END;

-- Libro: controla permisos de ajustes, origen, cantidades; actualiza el saldo de manera atomica.
CREATE TRIGGER trg_movimiento_validar BEFORE INSERT ON movimiento_stock
BEGIN
 SELECT CASE WHEN NEW.tipo IN ('INICIAL','AJUSTE_POSITIVO','AJUSTE_NEGATIVO') AND NOT EXISTS (
    SELECT 1 FROM usuario u JOIN rol r ON r.id=u.rol_id
    WHERE u.id=NEW.autorizado_por_id AND u.activo=1 AND r.activo=1 AND r.codigo='ADMIN')
 THEN RAISE(ABORT,'AJUSTE_REQUIERE_ADMIN_ACTIVO') END;
 SELECT CASE WHEN NEW.tipo='COMPRA' AND NOT EXISTS (
    SELECT 1 FROM compra_detalle cd JOIN compra c ON c.id=cd.compra_id
    JOIN producto_presentacion pp ON pp.id=cd.presentacion_id
    WHERE cd.id=NEW.compra_detalle_id AND c.estado='CONFIRMADA'
    AND pp.producto_id=NEW.producto_id AND cd.cantidad_base_milli=NEW.cantidad_delta_milli
    AND c.usuario_id=NEW.usuario_id)
 THEN RAISE(ABORT,'MOVIMIENTO_COMPRA_ORIGEN_INVALIDO') END;
 SELECT CASE WHEN NEW.tipo='VENTA' AND NOT EXISTS (
    SELECT 1 FROM venta_detalle vd JOIN venta v ON v.id=vd.venta_id
    JOIN producto_presentacion pp ON pp.id=vd.presentacion_id
    WHERE vd.id=NEW.venta_detalle_id AND v.estado='CONFIRMADA'
    AND pp.producto_id=NEW.producto_id AND vd.cantidad_base_milli=-NEW.cantidad_delta_milli
    AND v.vendedor_id=NEW.usuario_id)
 THEN RAISE(ABORT,'MOVIMIENTO_VENTA_ORIGEN_INVALIDO') END;
 SELECT CASE WHEN NEW.tipo='INICIAL' AND EXISTS(
    SELECT 1 FROM movimiento_stock WHERE producto_id=NEW.producto_id)
 THEN RAISE(ABORT,'STOCK_INICIAL_SOLO_PRIMER_MOVIMIENTO') END;
END;
CREATE TRIGGER trg_movimiento_actualizar_saldo AFTER INSERT ON movimiento_stock
BEGIN
 UPDATE saldo_stock
 SET cantidad_milli=cantidad_milli+NEW.cantidad_delta_milli,
     actualizado_en=NEW.fecha
 WHERE producto_id=NEW.producto_id
   AND cantidad_milli+NEW.cantidad_delta_milli>=0;
 SELECT CASE WHEN changes()=0 THEN RAISE(ABORT,'STOCK_INSUFICIENTE') END;
END;
CREATE TRIGGER trg_movimiento_no_update BEFORE UPDATE ON movimiento_stock
BEGIN SELECT RAISE(ABORT,'KARDEX_INMUTABLE'); END;
CREATE TRIGGER trg_movimiento_no_delete BEFORE DELETE ON movimiento_stock
BEGIN SELECT RAISE(ABORT,'KARDEX_INMUTABLE'); END;

-- No borrar eventos operativos (no impide administracion de borradores; DAO debe controlarla).
CREATE TRIGGER trg_comprobante_evento_no_delete BEFORE DELETE ON comprobante_evento
BEGIN SELECT RAISE(ABORT,'EVENTO_COMPROBANTE_INMUTABLE'); END;
CREATE TRIGGER trg_comprobante_evento_no_update BEFORE UPDATE ON comprobante_evento
BEGIN SELECT RAISE(ABORT,'EVENTO_COMPROBANTE_INMUTABLE'); END;
CREATE TRIGGER trg_auditoria_no_delete BEFORE DELETE ON auditoria
BEGIN SELECT RAISE(ABORT,'AUDITORIA_INMUTABLE'); END;
CREATE TRIGGER trg_auditoria_no_update BEFORE UPDATE ON auditoria
BEGIN SELECT RAISE(ABORT,'AUDITORIA_INMUTABLE'); END;
CREATE TRIGGER trg_producto_sku_historico BEFORE UPDATE OF sku ON producto
WHEN EXISTS (SELECT 1 FROM movimiento_stock WHERE producto_id=OLD.id)
BEGIN SELECT RAISE(ABORT,'SKU_CON_MOVIMIENTOS_NO_EDITABLE'); END;


-- Campos de detalle se congelan al registrar y deben corresponder a presentaciones validas.
CREATE TRIGGER trg_venta_detalle_validar_presentacion BEFORE INSERT ON venta_detalle
BEGIN
 SELECT CASE WHEN NOT EXISTS (
  SELECT 1 FROM producto_presentacion pp JOIN producto p ON p.id=pp.producto_id
  WHERE pp.id=NEW.presentacion_id AND pp.factor_base_milli=NEW.factor_base_milli_snapshot
  AND pp.activo=1 AND pp.se_vende=1 AND p.estado!='INACTIVO')
 THEN RAISE(ABORT,'PRESENTACION_VENTA_NO_DISPONIBLE') END;
END;
CREATE TRIGGER trg_compra_detalle_validar_presentacion BEFORE INSERT ON compra_detalle
BEGIN
 SELECT CASE WHEN NOT EXISTS (
  SELECT 1 FROM producto_presentacion pp JOIN producto p ON p.id=pp.producto_id
  WHERE pp.id=NEW.presentacion_id AND pp.factor_base_milli=NEW.factor_base_milli_snapshot
  AND pp.activo=1 AND pp.se_compra=1 AND p.estado!='INACTIVO')
 THEN RAISE(ABORT,'PRESENTACION_COMPRA_NO_DISPONIBLE') END;
END;
CREATE TRIGGER trg_venta_detalle_no_trasladar BEFORE UPDATE OF venta_id ON venta_detalle
WHEN (SELECT estado FROM venta WHERE id=NEW.venta_id)!='BORRADOR'
BEGIN SELECT RAISE(ABORT,'DETALLE_NO_MOVER_A_VENTA_CONFIRMADA'); END;
CREATE TRIGGER trg_compra_detalle_no_trasladar BEFORE UPDATE OF compra_id ON compra_detalle
WHEN (SELECT estado FROM compra WHERE id=NEW.compra_id)!='BORRADOR'
BEGIN SELECT RAISE(ABORT,'DETALLE_NO_MOVER_A_COMPRA_CONFIRMADA'); END;
CREATE TRIGGER trg_pago_no_trasladar BEFORE UPDATE OF venta_id ON venta_pago
WHEN (SELECT estado FROM venta WHERE id=NEW.venta_id)!='BORRADOR'
BEGIN SELECT RAISE(ABORT,'PAGO_NO_MOVER_A_VENTA_CONFIRMADA'); END;
CREATE TRIGGER trg_comprobante_identidad_inmutable BEFORE UPDATE OF tipo,venta_id,idempotency_key ON comprobante
WHEN NEW.tipo!=OLD.tipo OR NEW.venta_id!=OLD.venta_id OR NEW.idempotency_key!=OLD.idempotency_key
BEGIN SELECT RAISE(ABORT,'COMPROBANTE_IDENTIDAD_INMUTABLE'); END;
CREATE TRIGGER trg_comprobante_numero_no_cambiar BEFORE UPDATE OF serie_id,numero ON comprobante
WHEN OLD.numero IS NOT NULL AND (NEW.numero IS NOT OLD.numero OR NEW.serie_id IS NOT OLD.serie_id)
BEGIN SELECT RAISE(ABORT,'COMPROBANTE_NUMERO_INMUTABLE'); END;
CREATE TRIGGER trg_comprobante_borrado_confirmado BEFORE DELETE ON comprobante
WHEN (SELECT estado FROM venta WHERE id=OLD.venta_id)='CONFIRMADA'
BEGIN SELECT RAISE(ABORT,'COMPROBANTE_VENTA_CONFIRMADA_NO_BORRAR'); END;

-- Previene alteraciones del factor de conversion en borradores por la via UPDATE.
CREATE TRIGGER trg_compra_detalle_update_presentacion BEFORE UPDATE ON compra_detalle
WHEN NOT EXISTS (
 SELECT 1 FROM producto_presentacion pp JOIN producto p ON p.id=pp.producto_id
 WHERE pp.id=NEW.presentacion_id AND pp.factor_base_milli=NEW.factor_base_milli_snapshot
   AND pp.activo=1 AND pp.se_compra=1 AND p.estado!='INACTIVO')
BEGIN SELECT RAISE(ABORT,'PRESENTACION_COMPRA_NO_DISPONIBLE'); END;
CREATE TRIGGER trg_venta_detalle_update_presentacion BEFORE UPDATE ON venta_detalle
WHEN NOT EXISTS (
 SELECT 1 FROM producto_presentacion pp JOIN producto p ON p.id=pp.producto_id
 WHERE pp.id=NEW.presentacion_id AND pp.factor_base_milli=NEW.factor_base_milli_snapshot
   AND pp.activo=1 AND pp.se_vende=1 AND p.estado!='INACTIVO')
BEGIN SELECT RAISE(ABORT,'PRESENTACION_VENTA_NO_DISPONIBLE'); END;
-- El comprobante se crea PENDIENTE; el conector actualiza su estado despues.
CREATE TRIGGER trg_comprobante_inicial_pendiente BEFORE INSERT ON comprobante
WHEN NEW.estado!='PENDIENTE'
BEGIN SELECT RAISE(ABORT,'COMPROBANTE_DEBE_INICIAR_PENDIENTE'); END;
