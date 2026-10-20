package com.tienda.inventario.domain;

public record StockHistoryEntry(long id, String date, String type, long deltaMilli,
                                long accumulatedMilli, String author, String reason) { }
