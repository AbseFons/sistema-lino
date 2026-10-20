package com.tienda.inventario.domain;

/** Unidades controladas por el inventario y factor congelado en cada compra. */
public record PurchasePresentation(long id, long productId, String sku, String productName,
                                   String presentationName, long factorMilli, String unitCode,
                                   int unitDecimals, Long latestCostMicro) {
    @Override public String toString() {
        return sku+" · "+productName+" · "+presentationName;
    }
}
