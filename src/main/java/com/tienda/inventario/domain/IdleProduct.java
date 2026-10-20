package com.tienda.inventario.domain;
public record IdleProduct(String sku, String name, String unit, long stockMilli, String lastSaleAt) {}
