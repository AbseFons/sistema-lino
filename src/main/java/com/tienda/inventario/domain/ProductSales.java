package com.tienda.inventario.domain;
/** Cantidades en unidad BASE, expresadas x1000; no sumar cantidades de unidades distintas. */
public record ProductSales(String sku, String name, String unit, long quantityBaseMilli, long revenueCents, long transactions) {}
