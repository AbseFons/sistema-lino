package com.tienda.inventario.domain;
/** Solo ventas confirmadas; montos en céntimos de sol. */
public record SalesSummary(long sales, long revenueCents, long discountsCents, long averageTicketCents) {}
