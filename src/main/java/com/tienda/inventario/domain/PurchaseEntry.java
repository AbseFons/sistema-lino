package com.tienda.inventario.domain;

/** Registro histórico, ya confirmado. */
public record PurchaseEntry(long id, String date, String supplierName, String documentNumber,
                            long totalCents, int lines, String confirmedAt) {}
