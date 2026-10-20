package com.tienda.inventario.domain;
/** Solicitudes pendientes agrupadas por descripción y marca/modelo normalizados. */
public record DemandRanking(String description, String brandModel, long requests, long quantityKnownMilli) {}
