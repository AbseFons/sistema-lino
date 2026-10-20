package com.tienda.inventario.domain;

public record ProductPresentation(long id, String name, long factorMilli, String barcode,
                                  boolean buyEnabled, boolean sellEnabled, boolean active,
                                  Long publicPriceMicro) {}
