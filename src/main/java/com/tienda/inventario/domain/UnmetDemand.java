package com.tienda.inventario.domain;
public record UnmetDemand(long id, String date, String description, String brandModel,
                          Long quantityMilli, String sku, String customer,
                          String worker, String status, String notes) {}
