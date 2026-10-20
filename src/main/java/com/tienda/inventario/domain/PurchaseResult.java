package com.tienda.inventario.domain;

public record PurchaseResult(long id, long totalCents, int lines, int changedCosts) {}
