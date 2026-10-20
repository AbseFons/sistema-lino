package com.tienda.inventario.domain;
import java.math.BigDecimal;
public record SalePaymentInput(String method,BigDecimal amount,String reference) {}
