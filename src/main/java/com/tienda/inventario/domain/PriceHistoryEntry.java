package com.tienda.inventario.domain;

public record PriceHistoryEntry(long id, String presentation, String priceList, long priceMicro,
                                String from, String until, String author, String reason) { }
