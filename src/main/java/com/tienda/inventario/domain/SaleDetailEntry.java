package com.tienda.inventario.domain;
public record SaleDetailEntry(String sku,String description,long quantityPresentationMilli,long quantityBaseMilli,
                              long listUnitMicro,long finalUnitMicro,long discountCents,long taxCents,long totalCents) {}
