package com.tienda.inventario.domain;

import java.math.BigDecimal;

public record NewProduct(String sku, String name, long categoryId, long unitId,
                         BigDecimal initialStock, BigDecimal minimumStock,
                         BigDecimal publicPrice, Long brandId, String country,
                         String model, String observations) {
    public NewProduct(String sku, String name, long categoryId, long unitId,
                      BigDecimal initialStock, BigDecimal minimumStock, BigDecimal publicPrice) {
        this(sku,name,categoryId,unitId,initialStock,minimumStock,publicPrice,null,null,null,null);
    }
}
