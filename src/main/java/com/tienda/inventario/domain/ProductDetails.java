package com.tienda.inventario.domain;

public record ProductDetails(long id, String sku, String name, Long brandId, String country,
                             String model, String observations, String unitCode, int unitDecimals) {}
