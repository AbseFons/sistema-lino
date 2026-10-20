package com.tienda.inventario.domain;

import java.time.LocalDate;
import java.util.List;
public record NewPurchase(Long supplierId, String documentNumber, LocalDate date, List<PurchaseLineInput> lines) {}
