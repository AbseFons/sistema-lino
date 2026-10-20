package com.tienda.inventario.domain;
import java.math.BigDecimal;
public record SaleLineInput(long presentationId,BigDecimal quantity,BigDecimal finalUnitPrice) {}
