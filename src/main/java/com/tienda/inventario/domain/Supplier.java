package com.tienda.inventario.domain;

public record Supplier(long id, String ruc, String name, String phone, boolean active) {
    @Override public String toString() { return name + (ruc==null ? "" : " · "+ruc); }
}
