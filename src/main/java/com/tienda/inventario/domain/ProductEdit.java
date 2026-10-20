package com.tienda.inventario.domain;

/** Ficha editable. El stock y la unidad base NO se editan desde aquí. */
public record ProductEdit(long id, String sku, String name, long categoryId, Long brandId,
                          String country, String model, String notes, int unitDecimals,
                          String unitCode, long minimumMilli, Long maximumMilli, String status,
                          long stockMilli) { }
