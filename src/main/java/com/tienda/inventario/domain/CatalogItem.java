package com.tienda.inventario.domain;

public record CatalogItem(long id, String name, int decimalPlaces) {
    @Override public String toString() { return name; }
}
