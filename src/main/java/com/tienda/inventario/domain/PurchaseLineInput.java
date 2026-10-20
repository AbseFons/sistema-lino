package com.tienda.inventario.domain;

import java.math.BigDecimal;
public record PurchaseLineInput(long presentationId, BigDecimal quantity, BigDecimal unitCost) {}
