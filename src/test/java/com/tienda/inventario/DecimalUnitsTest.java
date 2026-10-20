package com.tienda.inventario;

import com.tienda.inventario.application.DecimalUnits;
import com.tienda.inventario.infrastructure.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class DecimalUnitsTest {
    @Test void moneyIsExactlyScaled() {
        assertEquals(299000,DecimalUnits.toPriceMicro(new BigDecimal("29.90")));
        assertThrows(IllegalArgumentException.class,()->DecimalUnits.toPriceMicro(new BigDecimal("19.999")));
    }
    @Test void unitPrecisionIsRespected() {
        assertEquals(2500,DecimalUnits.toMilli(new BigDecimal("2.5"),1));
        assertThrows(IllegalArgumentException.class,()->DecimalUnits.toMilli(new BigDecimal("2.5"),0));
        assertEquals("2.5",DecimalUnits.displayMilli(2500));
    }
    @Test void passwordHashUsesUniqueSalt() {
        String a = PasswordHasher.hash("ClaveLargaSegura#2026".toCharArray());
        String b = PasswordHasher.hash("ClaveLargaSegura#2026".toCharArray());
        assertNotEquals(a,b);
        assertTrue(PasswordHasher.verify("ClaveLargaSegura#2026".toCharArray(),a));
        assertFalse(PasswordHasher.verify("incorrecta".toCharArray(),a));
    }
}
