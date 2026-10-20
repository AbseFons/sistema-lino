package com.tienda.inventario.domain;

public record PurchaseDetailEntry(String sku, String product, String presentation,
                                  long quantityPresentationMilli, long quantityBaseMilli,
                                  long costUnitMicro, long lineCents) {}
