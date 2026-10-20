package com.tienda.inventario.domain;

public record Product(long id, String sku, String name, String category, String brand, String unit,
                      String status, long stockMilli, long minimumMilli,
                      String alert, Long publicPriceMicro) {}
