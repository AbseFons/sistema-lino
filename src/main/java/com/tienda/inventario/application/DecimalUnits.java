package com.tienda.inventario.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Fixed-point rules of the approved SQLite schema. */
public final class DecimalUnits {
    private DecimalUnits() {}
    public static long toMilli(BigDecimal quantity, int maxDecimals) {
        if (quantity == null || quantity.signum() < 0 || maxDecimals < 0 || maxDecimals > 3)
            throw new IllegalArgumentException("Cantidad inválida");
        if (quantity.stripTrailingZeros().scale() > maxDecimals)
            throw new IllegalArgumentException("La unidad no admite tantos decimales");
        return quantity.setScale(3, RoundingMode.UNNECESSARY).movePointRight(3).longValueExact();
    }
    public static long toPriceMicro(BigDecimal soles) {
        if (soles == null || soles.signum() < 0 || soles.stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("El precio debe tener como máximo dos decimales");
        return soles.setScale(4, RoundingMode.UNNECESSARY).movePointRight(4).longValueExact();
    }
    public static String displayMilli(long milli) {
        return BigDecimal.valueOf(milli,3).stripTrailingZeros().toPlainString();
    }
    public static String displayPrice(Long micro) {
        return micro == null ? "Sin precio" : "S/ " + BigDecimal.valueOf(micro,4).setScale(2,RoundingMode.HALF_UP).toPlainString();
    }
}
