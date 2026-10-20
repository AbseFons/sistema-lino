package com.tienda.inventario.domain;

/** Fila de administración del catálogo, distinta de la unidad con decimales permitidos. */
public record CatalogEntry(long id, String name, boolean active) {}
