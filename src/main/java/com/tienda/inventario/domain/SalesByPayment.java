package com.tienda.inventario.domain;
public record SalesByPayment(String method, long amountCents, long paymentCount) {}
